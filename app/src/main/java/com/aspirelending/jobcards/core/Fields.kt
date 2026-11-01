package com.aspirelending.jobcards.core

data class JobFields(val jobCardNo: String? = null, val customer: String? = null, val phone: String? = null,
                     val imei: String? = null, val date: String? = null)

/** Label-driven (not coordinate-driven) extraction: value is after the label, to its right, or just below it. */
object FieldExtractor {
    private val L = mapOf(
        "job" to listOf("JOB CARD NO", "JOB CARD NUMBER", "JOB NO", "JC NO", "CARD NO"),
        "cust" to listOf("CUSTOMER NAME", "CUSTOMER", "CLIENT"),
        "phone" to listOf("PHONE", "TEL", "MOBILE", "CONTACT"),
        "imei" to listOf("IMEI", "DEVICE NO", "DEVICE"),
        "date" to listOf("DATE"))
    private val keywords = listOf("JOB CARD", "REGISTRATION", "IMEI", "CHASSIS", "ENGINE", "INSTALL", "REMOVAL", "CUSTOMER", "INSTALLER", "TECHNICIAN", "DEVICE", "ASPIRE", "TRAILMYCAR")

    fun looksLikeJobCard(lines: List<OcrLine>): Boolean {
        val t = lines.joinToString(" ") { it.text.uppercase() }
        return keywords.count { t.contains(it) } >= 2
    }

    fun extract(lines: List<OcrLine>): JobFields {
        fun get(key: String, ok: (String) -> String?): String? {
            for (ln in lines) {
                val up = ln.text.uppercase()
                val lab = L[key]!!.firstOrNull { up.contains(it) } ?: continue
                val after = ln.text.substring(up.indexOf(lab) + lab.length).trimStart(' ', ':', '-', '.', '#', '/')
                val cands = listOf(after) +
                    lines.filter { it !== ln && it.box.left >= ln.box.right - 10 && kotlin.math.abs(it.box.centerY() - ln.box.centerY()) < ln.box.height() }
                        .sortedBy { it.box.left }.map { it.text } +
                    lines.filter { it !== ln && it.box.top >= ln.box.bottom - 5 && it.box.top - ln.box.bottom < ln.box.height() * 2 && it.box.left < ln.box.right && it.box.right > ln.box.left }
                        .sortedBy { it.box.top }.map { it.text }
                cands.firstNotNullOfOrNull { ok(it.trim()) }?.let { return it }
            }
            return null
        }
        return JobFields(
            jobCardNo = get("job") { s -> s.filter { it.isLetterOrDigit() }.takeIf { it.length in 3..10 && it.any(Char::isDigit) } },
            customer = get("cust") { s -> s.takeIf { it.length >= 3 && it.count(Char::isLetter) >= it.length * 0.7 && !it.uppercase().contains("PHONE") } },
            phone = get("phone") { s -> s.replace(Regex("[\\s-]"), "").takeIf { Regex("\\+?\\d{9,13}").matches(it) } },
            imei = get("imei") { s -> s.uppercase().replace('O', '0').filter { it.isDigit() }.takeIf { it.length in 14..16 } },
            date = get("date") { s -> Regex("\\d{1,2}[/.-]\\d{1,2}[/.-]\\d{2,4}").find(s)?.value })
    }
}
