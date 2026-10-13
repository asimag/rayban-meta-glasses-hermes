package ai.hermes.glasses.interaction

/**
 * Idle/standby/listening/busy states for the glasses voice triggers. No Android or DAT types.
 *
 * STANDBY only exists while voice wake-up is enabled and a recognizer is available: the detector
 * hears speech, but only the wake phrase turns it into a question.
 */
enum class TapToTalkState {
    IDLE,
    STANDBY,
    LISTENING,
    BUSY,
}

sealed class TapToTalkEvent {
    /** A press of the physical action button, delivered as InputEvent.Button(ButtonType.ACTION). */
    object TriggerPressed : TapToTalkEvent()

    data class FinalTranscript(val text: String) : TapToTalkEvent()

    object ResponseFinished : TapToTalkEvent()

    object ResponseFailed : TapToTalkEvent()

    /** Glasses disconnected/folded, or the DeviceSession stopped. */
    object Disconnected : TapToTalkEvent()

    /** The phone button or typed-question fallback was used directly; [withImage] is the Look button. */
    data class ManualSubmit(val text: String, val withImage: Boolean = false) : TapToTalkEvent()

    /** A recognizer is attached and can be armed for wake standby. */
    object SpeechReady : TapToTalkEvent()

    object EnableWake : TapToTalkEvent()

    object DisableWake : TapToTalkEvent()

    /** The recognizer stopped on its own, e.g. a DAT Speech utterance completed. */
    object RecognizerStopped : TapToTalkEvent()

    /** Re-arming wake standby keeps failing; give up until the recognizer is ready again. */
    object RecognizerFailed : TapToTalkEvent()

    /** Fired by the timer started with [TapToTalkEffect.StartQuestionTimer] of the same [id]. */
    data class QuestionTimeout(val id: Int) : TapToTalkEvent()
}

sealed class TapToTalkEffect {
    object StartListening : TapToTalkEffect()
    object StopListening : TapToTalkEffect()
    /** Ask Hermes; with [withImage] the glasses take a photo first and send it with [text]. */
    data class Submit(val text: String, val withImage: Boolean = false) : TapToTalkEffect()
    object PlayReadyCue : TapToTalkEffect()
    object PlayCapturedCue : TapToTalkEffect()
    object PlayCancelCue : TapToTalkEffect()
    object PlayWakeCue : TapToTalkEffect()

    /** Start (or restart) wake standby listening. */
    object ArmWake : TapToTalkEffect()

    /** Release the wake detector, e.g. before Hermes replies so playback cannot wake it. */
    object DisarmWake : TapToTalkEffect()

    /** Bound a wake-started question capture; timeouts with an older [id] are ignored. */
    data class StartQuestionTimer(val id: Int) : TapToTalkEffect()
}

/**
 * Pure state machine for the tap-to-talk and voice wake-up interaction. Callers translate
 * [TapToTalkEffect]s into real Speech/Hermes/audio calls; this class holds no Android or DAT
 * dependency so it is unit-testable without instrumentation.
 *
 * With wake-up disabled it behaves exactly like plain tap-to-talk. With it enabled, every turn
 * ends back in STANDBY instead of IDLE, and the detector is disarmed while Hermes is busy so the
 * spoken reply cannot trigger another question.
 */
class TapToTalkStateMachine(private val wakePhrase: WakePhraseMatcher = WakePhraseMatcher()) {
    var state: TapToTalkState = TapToTalkState.IDLE
        private set

    var wakeEnabled: Boolean = false
        private set

    private var recognizerReady = false

    /** True while LISTENING was entered without a fresh tap, so the capture must be re-armed and bounded. */
    private var handsFreeCapture = false
    private var questionTimerId = 0

    fun on(event: TapToTalkEvent): List<TapToTalkEffect> {
        val (nextState, effects) = transition(state, event)
        if (nextState != TapToTalkState.LISTENING) handsFreeCapture = false
        state = nextState
        return effects
    }

    private fun transition(
        current: TapToTalkState,
        event: TapToTalkEvent,
    ): Pair<TapToTalkState, List<TapToTalkEffect>> = when (event) {
        is TapToTalkEvent.TriggerPressed -> when (current) {
            TapToTalkState.IDLE ->
                TapToTalkState.LISTENING to listOf(TapToTalkEffect.StartListening)
            TapToTalkState.STANDBY -> startHandsFreeCapture(cue = null)
            TapToTalkState.LISTENING ->
                restingState() to listOf(TapToTalkEffect.StopListening, TapToTalkEffect.PlayCancelCue) + armIfResting()
            TapToTalkState.BUSY ->
                current to emptyList()
        }

        is TapToTalkEvent.FinalTranscript -> when {
            event.text.isBlank() -> current to emptyList()
            current == TapToTalkState.STANDBY -> when (val match = wakePhrase.match(event.text)) {
                WakeMatch.None -> current to emptyList()
                WakeMatch.WakeOnly -> startHandsFreeCapture(cue = TapToTalkEffect.PlayWakeCue)
                is WakeMatch.WakeWithQuestion -> when (VoiceCommand.parse(match.question)) {
                    VoiceCommand.STOP_LISTENING -> stopListeningByVoice(TapToTalkEffect.DisarmWake)
                    // Nothing is being captured yet; the recognizer re-arms on its own.
                    VoiceCommand.CANCEL -> current to listOf(TapToTalkEffect.PlayCancelCue)
                    null -> submit(match.question, listOf(TapToTalkEffect.DisarmWake))
                }
            }
            current == TapToTalkState.LISTENING -> {
                val match = wakePhrase.match(event.text)
                val text = (match as? WakeMatch.WakeWithQuestion)?.question ?: event.text
                when {
                    // A repeated "Hey Hermes" while already capturing is not a question.
                    match == WakeMatch.WakeOnly -> current to emptyList()
                    VoiceCommand.parse(text) == VoiceCommand.STOP_LISTENING ->
                        stopListeningByVoice(TapToTalkEffect.StopListening)
                    VoiceCommand.parse(text) == VoiceCommand.CANCEL ->
                        restingState() to listOf(TapToTalkEffect.StopListening, TapToTalkEffect.PlayCancelCue) +
                            armIfResting()
                    else -> submit(text, emptyList())
                }
            }
            else -> current to emptyList()
        }

        is TapToTalkEvent.ResponseFinished, is TapToTalkEvent.ResponseFailed -> when (current) {
            TapToTalkState.BUSY -> restingState() to listOf(TapToTalkEffect.PlayReadyCue) + armIfResting()
            else -> current to emptyList()
        }

        is TapToTalkEvent.Disconnected -> {
            recognizerReady = false
            when (current) {
                TapToTalkState.LISTENING -> TapToTalkState.IDLE to listOf(TapToTalkEffect.StopListening)
                TapToTalkState.STANDBY, TapToTalkState.BUSY -> TapToTalkState.IDLE to emptyList()
                TapToTalkState.IDLE -> current to emptyList()
            }
        }

        is TapToTalkEvent.ManualSubmit -> when (current) {
            TapToTalkState.BUSY -> current to emptyList()
            TapToTalkState.LISTENING ->
                TapToTalkState.BUSY to listOf(TapToTalkEffect.StopListening, TapToTalkEffect.Submit(event.text, event.withImage))
            TapToTalkState.STANDBY ->
                TapToTalkState.BUSY to listOf(TapToTalkEffect.DisarmWake, TapToTalkEffect.Submit(event.text, event.withImage))
            TapToTalkState.IDLE ->
                TapToTalkState.BUSY to listOf(TapToTalkEffect.Submit(event.text, event.withImage))
        }

        is TapToTalkEvent.SpeechReady -> {
            recognizerReady = true
            if (current == TapToTalkState.IDLE && wakeEnabled) {
                TapToTalkState.STANDBY to listOf(TapToTalkEffect.ArmWake)
            } else {
                current to emptyList()
            }
        }

        is TapToTalkEvent.EnableWake -> {
            wakeEnabled = true
            if (current == TapToTalkState.IDLE && recognizerReady) {
                TapToTalkState.STANDBY to listOf(TapToTalkEffect.ArmWake)
            } else {
                current to emptyList()
            }
        }

        is TapToTalkEvent.DisableWake -> {
            wakeEnabled = false
            when (current) {
                TapToTalkState.STANDBY -> TapToTalkState.IDLE to listOf(TapToTalkEffect.DisarmWake)
                // A wake-started capture is abandoned; a tapped one is the wearer's own choice.
                TapToTalkState.LISTENING -> if (handsFreeCapture) {
                    TapToTalkState.IDLE to listOf(TapToTalkEffect.StopListening)
                } else {
                    current to emptyList()
                }
                else -> current to emptyList()
            }
        }

        is TapToTalkEvent.RecognizerStopped -> when {
            current == TapToTalkState.STANDBY -> current to listOf(TapToTalkEffect.ArmWake)
            current == TapToTalkState.LISTENING && handsFreeCapture ->
                current to listOf(TapToTalkEffect.StartListening)
            else -> current to emptyList()
        }

        is TapToTalkEvent.RecognizerFailed -> {
            recognizerReady = false
            when (current) {
                TapToTalkState.STANDBY -> TapToTalkState.IDLE to listOf(TapToTalkEffect.DisarmWake)
                TapToTalkState.LISTENING -> if (handsFreeCapture) {
                    TapToTalkState.IDLE to listOf(TapToTalkEffect.StopListening, TapToTalkEffect.PlayCancelCue)
                } else {
                    current to emptyList()
                }
                else -> current to emptyList()
            }
        }

        is TapToTalkEvent.QuestionTimeout ->
            if (current == TapToTalkState.LISTENING && handsFreeCapture && event.id == questionTimerId) {
                restingState() to listOf(TapToTalkEffect.StopListening, TapToTalkEffect.PlayCancelCue) +
                    armIfResting()
            } else {
                current to emptyList()
            }
    }

    private fun startHandsFreeCapture(cue: TapToTalkEffect?): Pair<TapToTalkState, List<TapToTalkEffect>> {
        handsFreeCapture = true
        questionTimerId++
        return TapToTalkState.LISTENING to listOfNotNull(
            cue,
            TapToTalkEffect.StartListening,
            TapToTalkEffect.StartQuestionTimer(questionTimerId),
        )
    }

    private fun stopListeningByVoice(release: TapToTalkEffect): Pair<TapToTalkState, List<TapToTalkEffect>> {
        wakeEnabled = false
        return TapToTalkState.IDLE to listOf(release, TapToTalkEffect.PlayCancelCue)
    }

    private fun submit(
        text: String,
        before: List<TapToTalkEffect>,
    ): Pair<TapToTalkState, List<TapToTalkEffect>> =
        TapToTalkState.BUSY to before + listOf(
            TapToTalkEffect.Submit(text, withImage = VisualIntent.matches(text)),
            TapToTalkEffect.PlayCapturedCue,
        )

    private fun restingState(): TapToTalkState =
        if (wakeEnabled && recognizerReady) TapToTalkState.STANDBY else TapToTalkState.IDLE

    private fun armIfResting(): List<TapToTalkEffect> =
        if (restingState() == TapToTalkState.STANDBY) listOf(TapToTalkEffect.ArmWake) else emptyList()
}
