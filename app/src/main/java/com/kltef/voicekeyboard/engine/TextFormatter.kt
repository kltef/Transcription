package com.kltef.voicekeyboard.engine

/**
 * Deterministic casing cleanup for dictated text. The on-device punctuation model can
 * occasionally return ALL-CAPS output; this guarantees normal sentence case regardless of
 * what any engine produces, while preserving existing punctuation.
 */
object TextFormatter {

    private val standaloneI = Regex("\\bi\\b")
    private val contractionI = Regex("\\bi(?=')")

    fun sentenceCase(input: String): String {
        val text = input.trim()
        if (text.isEmpty()) return text

        // If the text has no lowercase letters at all, it's effectively all-caps — flatten it
        // first. Otherwise keep the engine's casing (e.g. Whisper's proper nouns) and just tidy.
        val base = if (text.none { it.isLowerCase() }) text.lowercase() else text

        val sb = StringBuilder(base.length)
        var capitalizeNext = true
        for (c in base) {
            if (capitalizeNext && c.isLetter()) {
                sb.append(c.uppercaseChar())
                capitalizeNext = false
            } else {
                sb.append(c)
            }
            if (c == '.' || c == '!' || c == '?') capitalizeNext = true
        }

        var result = sb.toString()
        result = standaloneI.replace(result) { "I" }
        result = contractionI.replace(result) { "I" }
        return result
    }
}
