package com.aspirelending.jobcards.core

import android.graphics.Rect

data class OcrLine(val text: String, val box: Rect, val confidence: Float)
enum class Tier { HIGH, MEDIUM, LOW }
data class RegCandidate(val raw: String, val value: String, val score: Float, val corrected: Boolean)

/**
 * Evidence-scored detector for Kenyan plates. Formats are a data table, so adding a new series is one line.
 * Slot codes: L = letter, D = digit, lowercase = that exact letter (e.g. 'k' = K).
 */
object RegistrationDetector {
    private data class Fmt(val slots: String, val split: List<Int>, val needsLabel: Boolean, val weight: Float)
    /** Most specific first: when two formats read the same characters, the earlier one decides the display. Source: NTSA Registration Plates Rules / 2026 plate guide. */
    private val formats = listOf(
        Fmt("ktcDDDDD", listOf(3), false, 1f),     // KTC 12345 tractors
        Fmt("khmDDDDD", listOf(3), false, 1f),     // KHM 12345 heavy machinery
        Fmt("ktwDDDDD", listOf(3), false, 1f),     // KTW 12345 three-wheelers
        Fmt("gkLDDDL", listOf(2), false, 1f),      // GK A123A government of Kenya
        Fmt("gkDDDL", listOf(2), false, 0.95f),    // GK 123A
        Fmt("kgDDDD", listOf(2), false, 0.9f),     // KG 1234 garage
        Fmt("kdDDDD", listOf(2), false, 0.9f),     // KD 1234 dealer (not KDA 123A)
        Fmt("ktDDDD", listOf(2), false, 0.9f),     // KT 1234 tow truck
        Fmt("kcDDDD", listOf(2), false, 0.9f),     // KC 1234 container freight station
        Fmt("kLLLDDDL", listOf(4), false, 1f),     // KMDA 123A motorcycles, KTWA 123A, KHMA 123A, other 4-letter K series
        Fmt("LLLDDDL", listOf(3), false, 1f),      // KBW 890M, KDA 123A, KEA 123A (ordinary, incl. 2026 KE series), KMC 123A
        Fmt("zDDDDD", listOf(1), false, 0.9f),     // Z 12345 trailers
        Fmt("kLLDDD", listOf(3), true, 0.9f),      // legacy KAB 123
        Fmt("zLDDDD", listOf(2), true, 0.9f),      // trailers ZF 1234
        Fmt("LLDDDL", listOf(2), true, 0.85f))     // other 2-letter series, label required
    private class Special(val rx: Regex, val free: Boolean, val weight: Float = 0.85f)
    // Special-purpose plates written with separators; matched on the line text, grouped with single spaces.
    private val specials = listOf(
        Special(Regex("""\b(\d{1,3})\s?[- ]?(CD|CC|DC)\s?[- ]?(\d{1,3})(?:\s?[- ]?([A-Z]{1,2}))?\b"""), false),   // 5 CD 18 K, 029 CD 123 AK
        Special(Regex("""\b(\d{1,3})\s?[- ]?(UN)\s?[- ]?(\d{1,3})(?:\s?[- ]?([A-Z]{1,2}))?\b"""), false),          // 029 UN 123 AK
        Special(Regex("""\b(UN|CD)\s?(\d{3})\b"""), false, 0.75f),                                                  // UN 001, UN 002
        Special(Regex("""\b(UNON)\s?(DG)\b"""), true),                                                              // UNON DG
        Special(Regex("""\bKX\s?(\d{2})\s?([AB])\b"""), true),                                                     // KX 05 A / B
        Special(Regex("""\b(\d{2})\s?(KCG)\s?(\d{2})\b"""), true),                                                // 00 KCG 00 coast guard
        Special(Regex("""\b(\d{2})\s?(CG)(?:\s?(\d{1,4}))?\b"""), false),                                         // 47 CG county government
        Special(Regex("""\b(GVN)\s?(\d{3})\b"""), true),                                                           // GVN 047 governors
        Special(Regex("""\b(SNA|SSN|CJ)\s?(\d{1,3})\b"""), false))                                                // SNA 1, SSN 001, CJ 1
    private val toLetter = mapOf('0' to 'O', '1' to 'I', '5' to 'S', '8' to 'B', '2' to 'Z', '6' to 'G', '4' to 'A')
    private val toDigit = mapOf('O' to '0', 'Q' to '0', 'D' to '0', 'I' to '1', 'L' to '1', 'S' to '5', 'B' to '8', 'Z' to '2', 'G' to '6')
    private val labels = listOf("REGISTRATION", "REG NO", "REG.", "REG:", "VEHICLE REG", "CAR REG", "NUMBER PLATE", "PLATE", "REG")
    private val vehicleCtx = listOf("CHASSIS", "ENGINE", "MODEL", "COLOUR", "COLOR", "VEHICLE", "MAKE")

    fun detect(lines: List<OcrLine>): List<RegCandidate> {
        val out = mutableListOf<RegCandidate>()
        for (ln in lines) {
            val up = ln.text.uppercase()
            val s = up.filter { it.isLetterOrDigit() }
            val label = labelScore(ln, lines)
            val ctx = if (lines.any { l -> vehicleCtx.any { l.text.uppercase().contains(it) } && near(l, ln, 4f) }) 1f else 0f
            fun score(pattern: Float, tight: Float) = 0.35f * pattern + 0.3f * label + 0.15f * ln.confidence.coerceIn(0f, 1f) + 0.1f * ctx + 0.1f * tight
            val here = mutableListOf<RegCandidate>()

            for (f in formats) {
                val n = f.slots.length
                if (f.needsLabel && label < 0.7f) continue
                for (i in 0..(s.length - n)) {
                    val w = s.substring(i, i + n); var fixes = 0
                    val v = StringBuilder()
                    for (k in 0 until n) {
                        val ch = w[k]; val sl = f.slots[k]
                        val r = when {
                            sl.isLowerCase() -> if (ch == sl.uppercaseChar()) ch else if (toLetter[ch] == sl.uppercaseChar()) { fixes++; sl.uppercaseChar() } else null
                            sl == 'L' -> if (ch.isLetter()) ch else toLetter[ch]?.also { fixes++ }
                            else -> if (ch.isDigit()) ch else toDigit[ch]?.also { fixes++ }
                        } ?: break
                        v.append(r)
                    }
                    if (v.length < n || fixes > 2) continue
                    var weight = f.weight
                    if (f.slots == "LLLDDDL" && v[0] !in "KGCZ") weight *= 0.85f
                    val pattern = (if (fixes == 0) 1f else 0.75f - 0.1f * (fixes - 1)) * weight
                    val tight = if (s.length == n) 1f else if (label == 1f) 0.9f else 0.6f
                    var d = 0; val text = buildString { f.split.forEach { p -> append(v.substring(d, p)).append(' '); d = p }; append(v.substring(d)) }
                    here += RegCandidate(w, text, score(pattern, tight), fixes > 0)
                }
            }
            for (sp in specials) sp.rx.findAll(up).forEach { m ->
                val whole = m.value.filter { it.isLetterOrDigit() } == s
                if (!sp.free && !whole && label < 0.7f) return@forEach
                val text = m.groupValues.drop(1).filter { it.isNotEmpty() }.joinToString(" ")
                here += RegCandidate(m.value, text, score(sp.weight, if (whole) 1f else 0.6f), false)
            }
            // drop shorter matches sitting inside a longer one (e.g. MDA123A inside KMDA123A)
            val compact = { c: RegCandidate -> c.value.replace(" ", "") }
            val firstSeen = here.distinctBy { compact(it) }   // same characters from two formats: keep the more specific reading
            out += firstSeen.filterNot { c -> firstSeen.any { d -> compact(d).length > compact(c).length && compact(d).contains(compact(c)) } }
        }
        if (out.isEmpty()) out += unknownFormat(lines)
        return merge(listOf(out))
    }

    /** Future-proofing: an unrecognised series sitting right beside a Registration label is offered as a low-confidence suggestion. */
    private fun unknownFormat(lines: List<OcrLine>): List<RegCandidate> = lines.mapNotNull { ln ->
        if (labelScore(ln, lines) < 0.85f) return@mapNotNull null
        var t = ln.text.uppercase(); labels.sortedByDescending { it.length }.forEach { t = t.replace(it, " ") }
        val m = Regex("""\b([A-Z]{2,4})\s?-?(\d{1,4})\s?([A-Z])?\b""").find(t) ?: return@mapNotNull null
        RegCandidate(m.value, m.groupValues.drop(1).filter { it.isNotEmpty() }.joinToString(" "), 0.45f + 0.1f * ln.confidence.coerceIn(0f, 1f), false)
    }

    /** Combine candidates from several OCR passes; agreement between passes raises confidence. */
    fun merge(passes: List<List<RegCandidate>>): List<RegCandidate> =
        passes.flatten().groupBy { it.value }.map { (v, g) ->
            RegCandidate(g.first().raw, v, (g.maxOf { it.score } + 0.08f * (passes.count { p -> p.any { it.value == v } } - 1).coerceAtLeast(0) + 0.03f * (g.size - 1).coerceAtMost(2)).coerceAtMost(1f), g.any { it.corrected })
        }.sortedByDescending { it.score }

    private fun near(a: OcrLine, b: OcrLine, k: Float) =
        kotlin.math.abs(a.box.centerY() - b.box.centerY()) < b.box.height() * k

    private fun labelScore(c: OcrLine, all: List<OcrLine>): Float {
        val t = c.text.uppercase()
        if (labels.any { t.contains(it) }) return 1f
        val lab = all.filter { l -> l !== c && labels.any { l.text.uppercase().contains(it) } }
        return when {
            lab.any { near(it, c, 1.2f) && it.box.left <= c.box.left + c.box.width() } -> 0.85f
            lab.any { it.box.centerY() < c.box.centerY() && c.box.top - it.box.bottom < c.box.height() * 2 &&
                kotlin.math.abs(it.box.left - c.box.left) < c.box.width() } -> 0.7f
            else -> 0f
        }
    }

    fun tier(score: Float) = when { score >= 0.85f -> Tier.HIGH; score >= 0.65f -> Tier.MEDIUM; else -> Tier.LOW }
    fun sanitizeFilename(reg: String) = reg.trim().replace(Regex("\\s+"), " ").replace(Regex("[\\\\/:*?\"<>|]"), "").uppercase() + ".jpeg"
}
