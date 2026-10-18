package com.nova.assistant.privacy

/**
 * Leak guard: anything Nova is about to send off the phone (searches, messages, map queries,
 * web look-ups) is scanned first. Sensitive finds make the pop-up ask, with Abort as the default.
 */
object Guard {
    data class Finding(val kind: String, val sample: String)

    private fun mask(s: String): String {
        val t = s.trim()
        if (t.length <= 4) return "•".repeat(t.length)
        val keep = if (t.length < 12) 2 else 4
        return t.take(keep) + "•".repeat(minOf(8, t.length - 2 * keep)) + t.takeLast(keep)
    }

    private fun luhn(d: String): Boolean {
        var sum = 0
        var alt = false
        for (i in d.length - 1 downTo 0) {
            var n = d[i] - '0'
            if (alt) { n *= 2; if (n > 9) n -= 9 }
            sum += n
            alt = !alt
        }
        return sum % 10 == 0
    }

    private val patterns = listOf(
        "private key" to Regex("-----BEGIN [A-Z ]*PRIVATE KEY-----"),
        "API key" to Regex("\\b(sk-[A-Za-z0-9_-]{20,}|AKIA[0-9A-Z]{16}|gh[pousr]_[A-Za-z0-9]{30,}|AIza[0-9A-Za-z_-]{35})\\b"),
        "password" to Regex("(?i)\\b(?:password|passwd|pwd|passcode)\\b\\s*(?:is|=|:)?\\s*(\\S{4,})"),
        "OTP / PIN" to Regex("(?i)\\b(?:otp|pin|cvv|cvc)\\b\\s*(?:is|=|:)?\\s*(\\d{3,8})\\b"),
        "PAN card" to Regex("\\b[A-Z]{5}\\d{4}[A-Z]\\b"),
        "Aadhaar number" to Regex("\\b[2-9]\\d{3}[ -]?\\d{4}[ -]?\\d{4}\\b"),
        "email address" to Regex("\\b[\\w.+-]+@[\\w-]+\\.[\\w.-]{2,}\\b"),
    )
    private val card = Regex("\\b(?:\\d[ -]?){13,19}\\b")
    private val phone = Regex("(?<![\\w])(\\+?\\d[\\d ()-]{8,16}\\d)(?![\\w])")

    fun scan(text: String): List<Finding> {
        if (text.isBlank()) return emptyList()
        val found = mutableListOf<Finding>()
        val taken = mutableListOf<IntRange>()
        fun add(kind: String, range: IntRange, value: String) {
            if (taken.any { it.first <= range.last && range.first <= it.last }) return
            taken += range
            found += Finding(kind, mask(value))
        }
        card.findAll(text).forEach { m ->
            val digits = m.value.filter { it.isDigit() }
            if (digits.length in 13..19 && luhn(digits)) add("card number", m.range, m.value)
        }
        for ((kind, rx) in patterns) rx.findAll(text).forEach { m ->
            val g = (if (m.groups.size > 1) m.groups[1] else null) ?: m.groups[0]!!
            add(kind, g.range, g.value)
        }
        phone.findAll(text).forEach { m ->
            val g = m.groups[1]!!
            if (g.value.count { it.isDigit() } in 10..13) add("phone number", g.range, g.value)
        }
        return found
    }
}
