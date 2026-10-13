package ai.hermes.glasses.interaction

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VisualIntentTest {
    @Test
    fun visualQuestionsMatch() {
        listOf(
            "What am I looking at?",
            "what's this",
            "What is that thing?",
            "What are these?",
            "Look at this and tell me what it is",
            "look",
            "Read this sign for me",
            "What does this say?",
            "Take a photo",
            "Can you see the label?",
            "Describe what's in front of me",
            "Identify this plant",
        ).forEach { assertTrue(it, VisualIntent.matches(it)) }
    }

    @Test
    fun ordinaryQuestionsDoNotMatch() {
        listOf(
            "What time is it?",
            "What is this week's forecast?",
            "What's the weather in Lahore",
            "Read me the news",
            "I was looking forward to the weekend",
            "Look up the capital of France",
            "Who is Hermes?",
            "",
        ).forEach { assertFalse(it, VisualIntent.matches(it)) }
    }
}
