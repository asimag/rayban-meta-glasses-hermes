package ai.hermes.glasses.interaction

import java.text.Normalizer

/** What a final transcript means with respect to the wake phrase. */
sealed class WakeMatch {
    object None : WakeMatch()

    /** The utterance was only the wake phrase; the question follows in a later utterance. */
    object WakeOnly : WakeMatch()

    /** "Hey Hermes, what time is it?" — [question] keeps the original wording after the phrase. */
    data class WakeWithQuestion(val question: String) : WakeMatch()
}

/** Spoken controls recognised only when they are the whole utterance, never inside a question. */
enum class VoiceCommand {
    /** Drop the question being captured; wake-up stays on. */
    CANCEL,

    /** Turn voice wake-up off until it is turned on again in the app. */
    STOP_LISTENING,
    ;

    companion object {
        private val PHRASES = mapOf(
            "cancel" to CANCEL,
            "stop" to CANCEL,
            "never mind" to CANCEL,
            "nevermind" to CANCEL,
            "forget it" to CANCEL,
            "stop listening" to STOP_LISTENING,
            "go to sleep" to STOP_LISTENING,
        )

        fun parse(text: String): VoiceCommand? = PHRASES[foldedWords(text).joinToString(" ")]
    }
}

/**
 * Transcript gate for the wake phrase. Works on final transcripts from any recognizer, so it does
 * not depend on whether wake standby ends up on DAT Speech or an offline Android engine.
 *
 * The phrase must open the utterance (after optional fillers such as "um"): a greeting followed by
 * one of [nameVariants]. Requiring the greeting keeps ordinary mentions of the name ("I bought it
 * at Hermes") from waking the assistant. Matching ignores case, punctuation and accents, so
 * "Hé, Hermès" matches too. The name variants cover common mis-hearings; tune them against real
 * glasses transcripts rather than guessing further.
 */
class WakePhraseMatcher(
    private val greetings: Set<String> = DEFAULT_GREETINGS,
    nameVariants: List<String> = DEFAULT_NAME_VARIANTS,
) {
    private val names: List<List<String>> = nameVariants.map(::foldedWords)

    fun match(transcript: String): WakeMatch {
        val tokens = words(transcript)
        var index = 0
        while (index < tokens.size && tokens[index].key in FILLERS) index++
        if (index >= tokens.size || tokens[index].key !in greetings) return WakeMatch.None
        index++
        val name = names.firstOrNull { variant -> startsWith(tokens, index, variant) } ?: return WakeMatch.None
        val phraseEnd = tokens[index + name.size - 1].end
        val question = transcript.substring(phraseEnd).trimStart { !it.isLetterOrDigit() }.trimEnd()
        return if (question.isEmpty()) WakeMatch.WakeOnly else WakeMatch.WakeWithQuestion(question)
    }

    private fun startsWith(tokens: List<Word>, from: Int, variant: List<String>): Boolean =
        variant.isNotEmpty() &&
            from + variant.size <= tokens.size &&
            variant.indices.all { tokens[from + it].key == variant[it] }

    private class Word(val key: String, val end: Int)

    companion object {
        val DEFAULT_GREETINGS = setOf("hey", "hi", "hay", "he", "ok", "okay")
        val DEFAULT_NAME_VARIANTS = listOf(
            "hermes", "herms", "hermis", "hermies", "hermez", "her mees", "her mes", "her maze",
        )
        private val FILLERS = setOf("um", "uh", "erm", "oh", "so")

        private fun words(text: String): List<Word> =
            WORD.findAll(text).map { Word(fold(it.value), it.range.last + 1) }.toList()
    }
}

private val WORD = Regex("[\\p{L}\\p{N}']+")
private val MARKS = Regex("\\p{Mn}+")

/** Lowercase, accent-free word keys with punctuation dropped, so "Hé, Hermès!" reads as "he hermes". */
private fun fold(word: String): String =
    Normalizer.normalize(word.lowercase(), Normalizer.Form.NFD).replace(MARKS, "").replace("'", "")

internal fun foldedWords(text: String): List<String> = WORD.findAll(text).map { fold(it.value) }.toList()
