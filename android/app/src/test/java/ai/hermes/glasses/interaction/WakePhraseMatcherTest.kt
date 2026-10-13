package ai.hermes.glasses.interaction

import org.junit.Assert.assertEquals
import org.junit.Test

class WakePhraseMatcherTest {
    private val matcher = WakePhraseMatcher()

    @Test
    fun wakePhraseAloneIsWakeOnly() {
        assertEquals(WakeMatch.WakeOnly, matcher.match("Hey Hermes"))
        assertEquals(WakeMatch.WakeOnly, matcher.match("hey hermes."))
        assertEquals(WakeMatch.WakeOnly, matcher.match("  Hey, Hermes!  "))
    }

    @Test
    fun combinedUtterancePreservesOriginalQuestion() {
        assertEquals(
            WakeMatch.WakeWithQuestion("What time is it?"),
            matcher.match("Hey Hermes, What time is it?"),
        )
        assertEquals(
            WakeMatch.WakeWithQuestion("what's the weather in Lahore"),
            matcher.match("hey hermes what's the weather in Lahore"),
        )
    }

    @Test
    fun accentsAndFrenchGreetingMatch() {
        assertEquals(WakeMatch.WakeWithQuestion("quelle heure est-il ?"), matcher.match("Hé Hermès, quelle heure est-il ?"))
    }

    @Test
    fun leadingFillerIsSkipped() {
        assertEquals(WakeMatch.WakeWithQuestion("set a timer"), matcher.match("Um, hey Hermes, set a timer"))
    }

    @Test
    fun multiWordMishearingMatches() {
        assertEquals(WakeMatch.WakeWithQuestion("call mom"), matcher.match("hey her mees call mom"))
    }

    @Test
    fun ordinaryMentionsDoNotWake() {
        assertEquals(WakeMatch.None, matcher.match("I bought a scarf at Hermes"))
        assertEquals(WakeMatch.None, matcher.match("Hermes what time is it"))
        assertEquals(WakeMatch.None, matcher.match("hey there"))
        assertEquals(WakeMatch.None, matcher.match("they said hey hermes yesterday"))
        assertEquals(WakeMatch.None, matcher.match("hey hermeneutics is hard"))
        assertEquals(WakeMatch.None, matcher.match(""))
        assertEquals(WakeMatch.None, matcher.match("hey"))
    }

    @Test
    fun customPhrase() {
        val glasses = WakePhraseMatcher(greetings = setOf("hey"), nameVariants = listOf("glasses"))
        assertEquals(WakeMatch.WakeWithQuestion("hello"), glasses.match("Hey glasses, hello"))
        assertEquals(WakeMatch.None, glasses.match("Hey Hermes, hello"))
    }

    @Test
    fun voiceCommandsMatchOnlyWholeUtterances() {
        assertEquals(VoiceCommand.CANCEL, VoiceCommand.parse("Never mind."))
        assertEquals(VoiceCommand.CANCEL, VoiceCommand.parse("stop"))
        assertEquals(VoiceCommand.STOP_LISTENING, VoiceCommand.parse("Stop listening!"))
        assertEquals(null, VoiceCommand.parse("stop the timer in five minutes"))
        assertEquals(null, VoiceCommand.parse("how do I cancel my subscription"))
    }
}
