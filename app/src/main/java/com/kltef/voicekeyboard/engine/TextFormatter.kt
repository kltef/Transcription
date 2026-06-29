package com.kltef.voicekeyboard.engine

/**
 * Deterministic casing cleanup for dictated text. The on-device punctuation model can
 * occasionally return ALL-CAPS output; this guarantees normal sentence case regardless of
 * what any engine produces, while preserving existing punctuation.
 */
object TextFormatter {

    private val standaloneI = Regex("\\bi\\b")
    private val contractionI = Regex("\\bi(?=')")
    private val whitespace = Regex("\\s+")

    /** Full cleanup for dictated text: collapse engine repetition loops, then sentence-case. */
    fun clean(input: String): String = sentenceCase(collapseRepeats(input))

    /**
     * Collapses runaway repetition (a known Whisper failure mode), e.g.
     * "hello hello hello hello" -> "hello", or a repeated phrase down to one copy.
     * Any 1–4 word block repeated 3+ times in a row is reduced to a single occurrence.
     */
    fun collapseRepeats(input: String): String {
        val words = input.trim().split(whitespace).filter { it.isNotEmpty() }
        if (words.size < 4) return input.trim()

        val out = ArrayList<String>(words.size)
        var i = 0
        while (i < words.size) {
            var collapsed = false
            for (k in 1..4) {
                if (i + 2 * k > words.size) continue
                val block = words.subList(i, i + k).map { it.lowercase() }
                var reps = 1
                var j = i + k
                while (j + k <= words.size && words.subList(j, j + k).map { it.lowercase() } == block) {
                    reps++; j += k
                }
                if (reps >= 3) {
                    for (w in words.subList(i, i + k)) out.add(w)
                    i = j
                    collapsed = true
                    break
                }
            }
            if (!collapsed) { out.add(words[i]); i++ }
        }
        return out.joinToString(" ")
    }

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
