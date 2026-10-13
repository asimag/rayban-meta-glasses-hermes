package ai.hermes.glasses.interaction

/**
 * Whether a spoken question is about what the wearer sees, so the glasses should take a photo
 * before asking Hermes. Matching uses the same folded words as [WakePhraseMatcher], so case,
 * punctuation and apostrophes ("what's" → "whats") do not matter.
 *
 * Phrases are deliberately narrow: a missed photo only costs a rephrase ("look at this"), while a
 * false match spends about five seconds on a photo nobody asked for. "What is this" therefore
 * only counts as the whole question ("what is this week's forecast" is not visual).
 */
object VisualIntent {
    private val ANYWHERE = listOf(
        "looking at",
        "take a photo", "take a picture", "take a pic", "take a look",
        "can you see", "do you see",
        "in front of me",
        "what does this say", "what does that say", "what does it say",
        "read this", "read that", "read these", "read those",
        "describe this", "describe that", "describe what", "describe the scene",
        "identify this", "identify that",
    )
    private val STARTS = listOf("look at", "look here")
    private val WHOLE = Regex(
        "(what|whats) (is |are )?(this|that|these|those)( thing| things| one| here| called)?",
    )

    fun matches(text: String): Boolean {
        val words = foldedWords(text)
        if (words.isEmpty()) return false
        val phrase = words.joinToString(" ")
        val padded = " $phrase "
        return words == listOf("look") ||
            STARTS.any { phrase.startsWith(it) } ||
            ANYWHERE.any { padded.contains(" $it ") } ||
            WHOLE.matches(phrase)
    }
}
