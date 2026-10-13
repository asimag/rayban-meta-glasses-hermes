package ai.hermes.glasses.interaction

import org.junit.Assert.assertEquals
import org.junit.Test

class TapToTalkStateMachineTest {
    @Test
    fun startsIdle() {
        val machine = TapToTalkStateMachine()
        assertEquals(TapToTalkState.IDLE, machine.state)
    }

    @Test
    fun tapWhileIdleStartsListening() {
        val machine = TapToTalkStateMachine()
        val effects = machine.on(TapToTalkEvent.TriggerPressed)
        assertEquals(TapToTalkState.LISTENING, machine.state)
        assertEquals(listOf(TapToTalkEffect.StartListening), effects)
    }

    @Test
    fun tapWhileListeningCancelsAndReturnsToIdle() {
        val machine = TapToTalkStateMachine()
        machine.on(TapToTalkEvent.TriggerPressed)
        val effects = machine.on(TapToTalkEvent.TriggerPressed)
        assertEquals(TapToTalkState.IDLE, machine.state)
        assertEquals(listOf(TapToTalkEffect.StopListening, TapToTalkEffect.PlayCancelCue), effects)
    }

    @Test
    fun tapWhileBusyIsIgnored() {
        val machine = TapToTalkStateMachine()
        machine.on(TapToTalkEvent.TriggerPressed) // -> LISTENING
        machine.on(TapToTalkEvent.FinalTranscript("what time is it")) // -> BUSY
        val effects = machine.on(TapToTalkEvent.TriggerPressed)
        assertEquals(TapToTalkState.BUSY, machine.state)
        assertEquals(emptyList<TapToTalkEffect>(), effects)
    }

    @Test
    fun finalTranscriptWhileListeningSubmitsAndGoesBusy() {
        val machine = TapToTalkStateMachine()
        machine.on(TapToTalkEvent.TriggerPressed)
        val effects = machine.on(TapToTalkEvent.FinalTranscript("hello there"))
        assertEquals(TapToTalkState.BUSY, machine.state)
        assertEquals(
            listOf(TapToTalkEffect.Submit("hello there"), TapToTalkEffect.PlayCapturedCue),
            effects,
        )
    }

    @Test
    fun blankFinalTranscriptWhileListeningIsIgnored() {
        val machine = TapToTalkStateMachine()
        machine.on(TapToTalkEvent.TriggerPressed)
        val effects = machine.on(TapToTalkEvent.FinalTranscript("   "))
        assertEquals(TapToTalkState.LISTENING, machine.state)
        assertEquals(emptyList<TapToTalkEffect>(), effects)
    }

    @Test
    fun finalTranscriptWhileIdleIsIgnored() {
        val machine = TapToTalkStateMachine()
        val effects = machine.on(TapToTalkEvent.FinalTranscript("stray"))
        assertEquals(TapToTalkState.IDLE, machine.state)
        assertEquals(emptyList<TapToTalkEffect>(), effects)
    }

    @Test
    fun finalTranscriptWhileBusyDoesNotQueueSecondRequest() {
        val machine = TapToTalkStateMachine()
        machine.on(TapToTalkEvent.TriggerPressed)
        machine.on(TapToTalkEvent.FinalTranscript("first question"))
        val effects = machine.on(TapToTalkEvent.FinalTranscript("second question"))
        assertEquals(TapToTalkState.BUSY, machine.state)
        assertEquals(emptyList<TapToTalkEffect>(), effects)
    }

    @Test
    fun responseFinishedReturnsToIdleAndPlaysReadyCue() {
        val machine = TapToTalkStateMachine()
        machine.on(TapToTalkEvent.TriggerPressed)
        machine.on(TapToTalkEvent.FinalTranscript("question"))
        val effects = machine.on(TapToTalkEvent.ResponseFinished)
        assertEquals(TapToTalkState.IDLE, machine.state)
        assertEquals(listOf(TapToTalkEffect.PlayReadyCue), effects)
    }

    @Test
    fun responseFailedReturnsToIdleAndPlaysReadyCue() {
        val machine = TapToTalkStateMachine()
        machine.on(TapToTalkEvent.TriggerPressed)
        machine.on(TapToTalkEvent.FinalTranscript("question"))
        val effects = machine.on(TapToTalkEvent.ResponseFailed)
        assertEquals(TapToTalkState.IDLE, machine.state)
        assertEquals(listOf(TapToTalkEffect.PlayReadyCue), effects)
    }

    @Test
    fun disconnectedWhileListeningStopsListeningAndReturnsToIdle() {
        val machine = TapToTalkStateMachine()
        machine.on(TapToTalkEvent.TriggerPressed)
        val effects = machine.on(TapToTalkEvent.Disconnected)
        assertEquals(TapToTalkState.IDLE, machine.state)
        assertEquals(listOf(TapToTalkEffect.StopListening), effects)
    }

    @Test
    fun disconnectedWhileBusyReturnsToIdleWithoutStoppingListening() {
        val machine = TapToTalkStateMachine()
        machine.on(TapToTalkEvent.TriggerPressed)
        machine.on(TapToTalkEvent.FinalTranscript("question"))
        val effects = machine.on(TapToTalkEvent.Disconnected)
        assertEquals(TapToTalkState.IDLE, machine.state)
        assertEquals(emptyList<TapToTalkEffect>(), effects)
    }

    @Test
    fun disconnectedWhileIdleIsANoOp() {
        val machine = TapToTalkStateMachine()
        val effects = machine.on(TapToTalkEvent.Disconnected)
        assertEquals(TapToTalkState.IDLE, machine.state)
        assertEquals(emptyList<TapToTalkEffect>(), effects)
    }

    @Test
    fun manualSubmitWhileIdleGoesBusyAndSubmits() {
        val machine = TapToTalkStateMachine()
        val effects = machine.on(TapToTalkEvent.ManualSubmit("typed question"))
        assertEquals(TapToTalkState.BUSY, machine.state)
        assertEquals(listOf(TapToTalkEffect.Submit("typed question")), effects)
    }

    @Test
    fun manualSubmitWhileListeningStopsListeningThenSubmits() {
        val machine = TapToTalkStateMachine()
        machine.on(TapToTalkEvent.TriggerPressed)
        val effects = machine.on(TapToTalkEvent.ManualSubmit("typed question"))
        assertEquals(TapToTalkState.BUSY, machine.state)
        assertEquals(
            listOf(TapToTalkEffect.StopListening, TapToTalkEffect.Submit("typed question")),
            effects,
        )
    }

    @Test
    fun manualSubmitWhileBusyIsIgnoredAndDoesNotQueue() {
        val machine = TapToTalkStateMachine()
        machine.on(TapToTalkEvent.ManualSubmit("first"))
        val effects = machine.on(TapToTalkEvent.ManualSubmit("second"))
        assertEquals(TapToTalkState.BUSY, machine.state)
        assertEquals(emptyList<TapToTalkEffect>(), effects)
    }

    // --- Voice wake-up ---

    private fun standbyMachine(): TapToTalkStateMachine {
        val machine = TapToTalkStateMachine()
        machine.on(TapToTalkEvent.SpeechReady)
        machine.on(TapToTalkEvent.EnableWake)
        return machine
    }

    @Test
    fun enablingWakeWithoutRecognizerWaitsForSpeechReady() {
        val machine = TapToTalkStateMachine()
        assertEquals(emptyList<TapToTalkEffect>(), machine.on(TapToTalkEvent.EnableWake))
        assertEquals(TapToTalkState.IDLE, machine.state)
        assertEquals(listOf(TapToTalkEffect.ArmWake), machine.on(TapToTalkEvent.SpeechReady))
        assertEquals(TapToTalkState.STANDBY, machine.state)
    }

    @Test
    fun speechReadyWithoutWakeEnabledStaysIdle() {
        val machine = TapToTalkStateMachine()
        assertEquals(emptyList<TapToTalkEffect>(), machine.on(TapToTalkEvent.SpeechReady))
        assertEquals(TapToTalkState.IDLE, machine.state)
    }

    @Test
    fun ordinaryConversationInStandbyIsNotSubmitted() {
        val machine = standbyMachine()
        assertEquals(emptyList<TapToTalkEffect>(), machine.on(TapToTalkEvent.FinalTranscript("pass the salt")))
        assertEquals(TapToTalkState.STANDBY, machine.state)
    }

    @Test
    fun standbyRearmsWhenRecognizerStops() {
        val machine = standbyMachine()
        assertEquals(listOf(TapToTalkEffect.ArmWake), machine.on(TapToTalkEvent.RecognizerStopped))
        assertEquals(TapToTalkState.STANDBY, machine.state)
    }

    @Test
    fun wakePhraseAloneStartsBoundedCapture() {
        val machine = standbyMachine()
        val effects = machine.on(TapToTalkEvent.FinalTranscript("Hey Hermes"))
        assertEquals(TapToTalkState.LISTENING, machine.state)
        assertEquals(
            listOf(TapToTalkEffect.PlayWakeCue, TapToTalkEffect.StartListening, TapToTalkEffect.StartQuestionTimer(1)),
            effects,
        )
    }

    @Test
    fun wakeCaptureRestartsRecognizerIfWakeUtteranceCompletesLate() {
        val machine = standbyMachine()
        machine.on(TapToTalkEvent.FinalTranscript("Hey Hermes"))
        assertEquals(listOf(TapToTalkEffect.StartListening), machine.on(TapToTalkEvent.RecognizerStopped))
        assertEquals(TapToTalkState.LISTENING, machine.state)
    }

    @Test
    fun tappedCaptureIsNotRestartedWhenRecognizerStops() {
        val machine = TapToTalkStateMachine()
        machine.on(TapToTalkEvent.TriggerPressed)
        assertEquals(emptyList<TapToTalkEffect>(), machine.on(TapToTalkEvent.RecognizerStopped))
    }

    @Test
    fun questionAfterWakeIsSubmitted() {
        val machine = standbyMachine()
        machine.on(TapToTalkEvent.FinalTranscript("Hey Hermes"))
        val effects = machine.on(TapToTalkEvent.FinalTranscript("what time is it"))
        assertEquals(TapToTalkState.BUSY, machine.state)
        assertEquals(listOf(TapToTalkEffect.Submit("what time is it"), TapToTalkEffect.PlayCapturedCue), effects)
    }

    @Test
    fun repeatedWakePhraseWhileCapturingIsNotSubmitted() {
        val machine = standbyMachine()
        machine.on(TapToTalkEvent.FinalTranscript("Hey Hermes"))
        assertEquals(emptyList<TapToTalkEffect>(), machine.on(TapToTalkEvent.FinalTranscript("hey hermes")))
        assertEquals(TapToTalkState.LISTENING, machine.state)
    }

    @Test
    fun combinedUtteranceSubmitsQuestionWithoutPhrase() {
        val machine = standbyMachine()
        val effects = machine.on(TapToTalkEvent.FinalTranscript("Hey Hermes, what time is it?"))
        assertEquals(TapToTalkState.BUSY, machine.state)
        assertEquals(
            listOf(
                TapToTalkEffect.DisarmWake,
                TapToTalkEffect.Submit("what time is it?"),
                TapToTalkEffect.PlayCapturedCue,
            ),
            effects,
        )
    }

    @Test
    fun transcriptsWhileBusyCannotSubmitAgain() {
        val machine = standbyMachine()
        machine.on(TapToTalkEvent.FinalTranscript("Hey Hermes, first"))
        assertEquals(emptyList<TapToTalkEffect>(), machine.on(TapToTalkEvent.FinalTranscript("Hey Hermes, second")))
        assertEquals(emptyList<TapToTalkEffect>(), machine.on(TapToTalkEvent.RecognizerStopped))
        assertEquals(TapToTalkState.BUSY, machine.state)
    }

    @Test
    fun replyFinishedReturnsToStandbyAndRearmsAfterCue() {
        val machine = standbyMachine()
        machine.on(TapToTalkEvent.FinalTranscript("Hey Hermes, hi"))
        val effects = machine.on(TapToTalkEvent.ResponseFinished)
        assertEquals(TapToTalkState.STANDBY, machine.state)
        assertEquals(listOf(TapToTalkEffect.PlayReadyCue, TapToTalkEffect.ArmWake), effects)
    }

    @Test
    fun replyFailedAlsoReturnsToStandby() {
        val machine = standbyMachine()
        machine.on(TapToTalkEvent.FinalTranscript("Hey Hermes, hi"))
        machine.on(TapToTalkEvent.ResponseFailed)
        assertEquals(TapToTalkState.STANDBY, machine.state)
    }

    @Test
    fun threeConsecutiveCyclesReturnToStandby() {
        val machine = standbyMachine()
        repeat(3) {
            machine.on(TapToTalkEvent.FinalTranscript("Hey Hermes"))
            machine.on(TapToTalkEvent.FinalTranscript("question $it"))
            machine.on(TapToTalkEvent.ResponseFinished)
            assertEquals(TapToTalkState.STANDBY, machine.state)
        }
    }

    @Test
    fun questionTimeoutCancelsCaptureAndRearms() {
        val machine = standbyMachine()
        machine.on(TapToTalkEvent.FinalTranscript("Hey Hermes"))
        val effects = machine.on(TapToTalkEvent.QuestionTimeout(1))
        assertEquals(TapToTalkState.STANDBY, machine.state)
        assertEquals(
            listOf(TapToTalkEffect.StopListening, TapToTalkEffect.PlayCancelCue, TapToTalkEffect.ArmWake),
            effects,
        )
    }

    @Test
    fun staleQuestionTimeoutIsIgnored() {
        val machine = standbyMachine()
        machine.on(TapToTalkEvent.FinalTranscript("Hey Hermes"))
        machine.on(TapToTalkEvent.FinalTranscript("first question"))
        machine.on(TapToTalkEvent.ResponseFinished)
        machine.on(TapToTalkEvent.FinalTranscript("Hey Hermes")) // timer 2
        assertEquals(emptyList<TapToTalkEffect>(), machine.on(TapToTalkEvent.QuestionTimeout(1)))
        assertEquals(TapToTalkState.LISTENING, machine.state)
    }

    @Test
    fun tapInStandbyStartsCaptureAndTapAgainReturnsToStandby() {
        val machine = standbyMachine()
        machine.on(TapToTalkEvent.TriggerPressed)
        assertEquals(TapToTalkState.LISTENING, machine.state)
        val effects = machine.on(TapToTalkEvent.TriggerPressed)
        assertEquals(TapToTalkState.STANDBY, machine.state)
        assertEquals(
            listOf(TapToTalkEffect.StopListening, TapToTalkEffect.PlayCancelCue, TapToTalkEffect.ArmWake),
            effects,
        )
    }

    @Test
    fun typedQuestionInStandbyDisarmsBeforeSubmitting() {
        val machine = standbyMachine()
        val effects = machine.on(TapToTalkEvent.ManualSubmit("typed"))
        assertEquals(TapToTalkState.BUSY, machine.state)
        assertEquals(listOf(TapToTalkEffect.DisarmWake, TapToTalkEffect.Submit("typed")), effects)
    }

    @Test
    fun disableWakeInStandbyReleasesDetector() {
        val machine = standbyMachine()
        val effects = machine.on(TapToTalkEvent.DisableWake)
        assertEquals(TapToTalkState.IDLE, machine.state)
        assertEquals(listOf(TapToTalkEffect.DisarmWake), effects)
        assertEquals(emptyList<TapToTalkEffect>(), machine.on(TapToTalkEvent.FinalTranscript("Hey Hermes, hi")))
        assertEquals(emptyList<TapToTalkEffect>(), machine.on(TapToTalkEvent.RecognizerStopped))
        assertEquals(TapToTalkState.IDLE, machine.state)
    }

    @Test
    fun disableWakeDuringWakeCaptureStopsIt() {
        val machine = standbyMachine()
        machine.on(TapToTalkEvent.FinalTranscript("Hey Hermes"))
        assertEquals(listOf(TapToTalkEffect.StopListening), machine.on(TapToTalkEvent.DisableWake))
        assertEquals(TapToTalkState.IDLE, machine.state)
    }

    @Test
    fun disableWakeWhileBusyEndsTurnInIdle() {
        val machine = standbyMachine()
        machine.on(TapToTalkEvent.FinalTranscript("Hey Hermes, hi"))
        machine.on(TapToTalkEvent.DisableWake)
        assertEquals(listOf(TapToTalkEffect.PlayReadyCue), machine.on(TapToTalkEvent.ResponseFinished))
        assertEquals(TapToTalkState.IDLE, machine.state)
    }

    @Test
    fun disconnectInStandbyGoesIdleAndReconnectRearms() {
        val machine = standbyMachine()
        assertEquals(emptyList<TapToTalkEffect>(), machine.on(TapToTalkEvent.Disconnected))
        assertEquals(TapToTalkState.IDLE, machine.state)
        assertEquals(listOf(TapToTalkEffect.ArmWake), machine.on(TapToTalkEvent.SpeechReady))
        assertEquals(TapToTalkState.STANDBY, machine.state)
    }

    @Test
    fun disconnectWhileBusyDoesNotRearmOrResubmit() {
        val machine = standbyMachine()
        machine.on(TapToTalkEvent.FinalTranscript("Hey Hermes, hi"))
        machine.on(TapToTalkEvent.Disconnected)
        assertEquals(TapToTalkState.IDLE, machine.state)
        assertEquals(emptyList<TapToTalkEffect>(), machine.on(TapToTalkEvent.ResponseFinished))
        assertEquals(TapToTalkState.IDLE, machine.state)
    }

    @Test
    fun recognizerFailureLeavesStandbyUntilReady() {
        val machine = standbyMachine()
        assertEquals(listOf(TapToTalkEffect.DisarmWake), machine.on(TapToTalkEvent.RecognizerFailed))
        assertEquals(TapToTalkState.IDLE, machine.state)
        assertEquals(listOf(TapToTalkEffect.ArmWake), machine.on(TapToTalkEvent.SpeechReady))
    }

    @Test
    fun cancelPhraseDropsWakeCaptureAndReturnsToStandby() {
        val machine = standbyMachine()
        machine.on(TapToTalkEvent.FinalTranscript("Hey Hermes"))
        val effects = machine.on(TapToTalkEvent.FinalTranscript("never mind"))
        assertEquals(TapToTalkState.STANDBY, machine.state)
        assertEquals(
            listOf(TapToTalkEffect.StopListening, TapToTalkEffect.PlayCancelCue, TapToTalkEffect.ArmWake),
            effects,
        )
    }

    @Test
    fun cancelPhraseInTappedCaptureWithoutWakeGoesIdle() {
        val machine = TapToTalkStateMachine()
        machine.on(TapToTalkEvent.TriggerPressed)
        machine.on(TapToTalkEvent.FinalTranscript("cancel"))
        assertEquals(TapToTalkState.IDLE, machine.state)
    }

    @Test
    fun combinedStopListeningTurnsWakeOff() {
        val machine = standbyMachine()
        val effects = machine.on(TapToTalkEvent.FinalTranscript("Hey Hermes, stop listening"))
        assertEquals(TapToTalkState.IDLE, machine.state)
        assertEquals(false, machine.wakeEnabled)
        assertEquals(listOf(TapToTalkEffect.DisarmWake, TapToTalkEffect.PlayCancelCue), effects)
        assertEquals(emptyList<TapToTalkEffect>(), machine.on(TapToTalkEvent.FinalTranscript("Hey Hermes")))
    }

    @Test
    fun stopListeningAfterWakeTurnsWakeOff() {
        val machine = standbyMachine()
        machine.on(TapToTalkEvent.FinalTranscript("Hey Hermes"))
        val effects = machine.on(TapToTalkEvent.FinalTranscript("stop listening"))
        assertEquals(TapToTalkState.IDLE, machine.state)
        assertEquals(false, machine.wakeEnabled)
        assertEquals(listOf(TapToTalkEffect.StopListening, TapToTalkEffect.PlayCancelCue), effects)
    }

    @Test
    fun combinedCancelInStandbyStaysInStandby() {
        val machine = standbyMachine()
        assertEquals(listOf(TapToTalkEffect.PlayCancelCue), machine.on(TapToTalkEvent.FinalTranscript("Hey Hermes, never mind")))
        assertEquals(TapToTalkState.STANDBY, machine.state)
    }

    @Test
    fun questionMentioningStopIsStillSubmitted() {
        val machine = standbyMachine()
        machine.on(TapToTalkEvent.FinalTranscript("Hey Hermes, stop the timer"))
        assertEquals(TapToTalkState.BUSY, machine.state)
    }
}
