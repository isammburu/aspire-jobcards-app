package com.aspirelending.jobcards.core

import android.graphics.Rect

data class OcrLine(val text: String, val box: Rect, val confidence: Float)
enum class Tier { HIGH, MEDIUM, LOW }
data class RegCandidate(val raw: String, val value: String, val score: Float, val corrected: Boolean)

/**
 * Minimal detector for the common Kenyan civilian plate formats only.
 *
 * This intentionally ignores special-purpose, military, government and dealer-only plates to keep
 * the detector stable and aligned with the app's normal use case.
 */
object RegistrationDetector {
    private data class Fmt(val slots: String, val split: List<Int>, val needsLabel: Boolean, val weight: Float)

    private val formats = listOf(
        Fmt("kLLLDDDL", listOf(4), false, 1f),     // KMDA 123A, KTWA 123A, KHMA 123A
        Fmt("LLLDDDL", listOf(3), false, 1f),      // KBW 890M, KDA 123A, KEA 123A
        Fmt("zDDDDD", listOf(1), false, 0.9f),     // Z 12345
        Fmt("kLLDDD", listOf(3), true, 0.9f),      // KAB 123
        Fmt("zLDDDD", listOf(2), true, 0.9f),      // ZF 1234
        Fmt("LLDDDL", listOf(2), true, 0.85f)      // ordinary label-required plate
    )

    private fun stripLabels(t: String): String {
        var r = t
        labels.sortedByDescending { it.length }.forEach { r = r.replace(Regex("(?<![A-Z0-9])" + Regex.escape(it) + "(?![A-Z])"), " ") }
        return r
    }
    private val toLetter = mapOf('0' to 'O', '1' to 'I', '5' to 'S', '8' to 'B', '2' to 'Z', '6' to 'G', '4' to 'A')
    private val toDigit = mapOf('O' to '0', 'Q' to '0', 'D' to '0', 'I' to '1', 'L' to '1', 'S' to '5', 'B' to '8', 'Z' to '2', 'G' to '6')
    private val labels = listOf("REGISTRATION", "REG NO", "REG.", "REG:", "VEHICLE REG", "CAR REG", "NUMBER PLATE", "PLATE", "REG")
    private val vehicleCtx = listOf("CHASSIS", "ENGINE", "MODEL", "COLOUR", "COLOR", "VEHICLE", "MAKE")

    private fun validPlateText(raw: String): Boolean {
        val cleaned = raw.filter { it.isLetterOrDigit() }
        if (cleaned.length < 4) return false
        val letters = cleaned.count { it.isLetter() }
        val digits = cleaned.count { it.isDigit() }
        return letters >= 2 && digits >= 1 && letters + digits <= 10
    }

    fun detect(lines: List<OcrLine>): List<RegCandidate> {
        val out = mutableListOf<RegCandidate>()
        for (ln in lines) {
            val up = ln.text.uppercase()
            val s = up.filter { it.isLetterOrDigit() }
            val sFmt = stripLabels(up).filter { it.isLetterOrDigit() }
            val label = labelScore(ln, lines)
            val ctx = if (lines.any { l -> vehicleCtx.any { l.text.uppercase().contains(it) } && near(l, ln, 4f) }) 1f else 0f
            fun score(pattern: Float, tight: Float) = 0.35f * pattern + 0.3f * label + 0.15f * ln.confidence.coerceIn(0f, 1f) + 0.1f * ctx + 0.1f * tight
            val here = mutableListOf<RegCandidate>()

            for (f in formats) {
                val n = f.slots.length
                if (f.needsLabel && label < 0.7f) continue
                for (i in 0..(sFmt.length - n)) {
                    val w = sFmt.substring(i, i + n)
                    var fixes = 0
                    val v = StringBuilder()
                    for (k in 0 until n) {
                        val ch = w[k]
                        val sl = f.slots[k]
                        val r = when {
                            sl.isLowerCase() -> if (ch == sl.uppercaseChar()) ch else if (label >= 0.7f && toLetter[ch] == sl.uppercaseChar()) { fixes++; sl.uppercaseChar() } else null
                            sl == 'L' -> if (ch.isLetter()) ch else toLetter[ch]?.also { fixes++ }
                            else -> if (ch.isDigit()) ch else toDigit[ch]?.also { fixes++ }
                        } ?: break
                        v.append(r)
                    }
                    if (v.length < n || fixes > 2) continue

                    var weight = f.weight
                    if (f.slots == "LLLDDDL" && v[0] !in "KGCZ") {
                        if (fixes > 0) continue
                        weight *= 0.85f
                    }

                    val pattern = (if (fixes == 0) 1f else 0.75f - 0.1f * (fixes - 1)) * weight
                    val tight = if (s.length == n) 1f else if (label == 1f) 0.9f else 0.6f
                    var d = 0
                    val text = buildString {
                        f.split.forEach { p ->
                            append(v.substring(d, p))
                            append(' ')
                            d = p
                        }
                        append(v.substring(d))
                    }
                    if (!validPlateText(text)) continue
                    here += RegCandidate(w, text, score(pattern, tight), fixes > 0)
                }
            }

            val compact = { c: RegCandidate -> c.value.replace(" ", "") }
            val firstSeen = here.distinctBy { compact(it) }
            out += firstSeen.filterNot { c -> firstSeen.any { d -> compact(d).length > compact(c).length && compact(d).contains(compact(c)) } }
        }

        if (out.isEmpty()) out += unknownFormat(lines)
        return merge(listOf(out))
    }

    private fun unknownFormat(lines: List<OcrLine>): List<RegCandidate> = lines.mapNotNull { ln ->
        if (labelScore(ln, lines) < 0.85f) return@mapNotNull null
        var t = ln.text.uppercase(); labels.sortedByDescending { it.length }.forEach { t = t.replace(it, " ") }
        val m = Regex("""\b([A-Z]{2,4})\s?-?(\d{1,4})\s?([A-Z])?\b""").find(t) ?: return@mapNotNull null
        val text = m.groupValues.drop(1).filter { it.isNotEmpty() }.joinToString(" ")
        if (!validPlateText(text)) return@mapNotNull null
        RegCandidate(m.value, text, 0.45f + 0.1f * ln.confidence.coerceIn(0f, 1f), false)
    }

    fun merge(passes: List<List<RegCandidate>>): List<RegCandidate> =
        passes.flatten().groupBy { it.value }.map { (v, g) ->
            RegCandidate(
                g.first().raw,
                v,
                (
                    g.maxOf { it.score } +
                        0.08f * (passes.count { p -> p.any { it.value == v } } - 1).coerceAtLeast(0) +
                        0.03f * (g.size - 1).coerceAtMost(2)
                    ).coerceAtMost(1f),
                g.first().corrected
            )
        }

    private fun near(a: OcrLine, b: OcrLine, k: Float) =
        kotlin.math.abs(a.box.centerY() - b.box.centerY()) < b.box.height() * k

    private fun labelScore(c: OcrLine, all: List<OcrLine>): Float {
        val t = c.text.uppercase()
        if (labels.any { t.contains(it) }) return 1f
        val lab = all.filter { l -> l !== c && labels.any { l.text.uppercase().contains(it) } }
        return when {
            lab.any { near(it, c, 1.2f) && it.box.left <= c.box.left + c.box.width() } -> 0.85f
            lab.any {
                it.box.centerY() < c.box.centerY() &&
                    c.box.top - it.box.bottom < c.box.height() * 2 &&
                    kotlin.math.abs(it.box.left - c.box.left) < c.box.width()
            } -> 0.7f
            else -> 0f
        }
    }

    fun tier(score: Float) = when { score >= 0.85f -> Tier.HIGH; score >= 0.65f -> Tier.MEDIUM; else -> Tier.LOW }
    fun sanitizeFilename(reg: String) = reg.trim().replace(Regex("\\s+"), " ").replace(Regex("[\\/:*?\"<>|]"), "").uppercase() + ".jpeg"
}
