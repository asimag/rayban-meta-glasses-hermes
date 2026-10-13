package ai.hermes.glasses

import android.Manifest
import android.annotation.SuppressLint
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.os.SystemClock
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.ToneGenerator
import android.os.Build
import android.os.Bundle
import android.text.InputType
import android.util.Log
import android.view.Gravity
import android.view.View
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts.RequestPermission
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import ai.hermes.glasses.camera.GlassesPhotoCapture
import ai.hermes.glasses.camera.PhotoEncoding
import com.meta.wearable.dat.camera.photo.types.PhotoQuality
import com.meta.wearable.dat.camera.photo.types.PhotoResolution
import ai.hermes.glasses.interaction.TapToTalkEffect
import ai.hermes.glasses.interaction.TapToTalkEvent
import ai.hermes.glasses.interaction.TapToTalkState
import ai.hermes.glasses.interaction.TapToTalkStateMachine
import ai.hermes.glasses.net.BridgeEndpoint
import ai.hermes.glasses.net.HermesBridgeClient
import ai.hermes.glasses.storage.SecureSettings
import com.meta.wearable.dat.core.Wearables
import com.meta.wearable.dat.core.selectors.AutoDeviceSelector
import com.meta.wearable.dat.core.session.DeviceSession
import com.meta.wearable.dat.core.session.DeviceSessionState
import com.meta.wearable.dat.core.types.Permission as DatPermission
import com.meta.wearable.dat.core.types.PermissionStatus
import com.meta.wearable.dat.core.types.RegistrationState
import com.meta.wearable.dat.core.types.WearablesError
import com.meta.wearable.dat.inputs.Inputs
import com.meta.wearable.dat.inputs.addInputs
import com.meta.wearable.dat.inputs.removeInputs
import com.meta.wearable.dat.inputs.types.ButtonType
import com.meta.wearable.dat.inputs.types.InputEvent
import com.meta.wearable.dat.inputs.types.InputSource
import com.meta.wearable.dat.inputs.types.InputsConfiguration
import com.meta.wearable.dat.inputs.types.InputsState
import com.meta.wearable.dat.speech.Speech
import com.meta.wearable.dat.speech.addSpeech
import com.meta.wearable.dat.speech.types.SpeechState
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@SuppressLint("SetTextI18n")
class MainActivity : ComponentActivity() {
    private lateinit var settings: SecureSettings
    private lateinit var bridgeUrl: EditText
    private lateinit var apiKey: EditText
    private lateinit var language: Spinner
    private lateinit var prompt: EditText
    private lateinit var status: TextView
    private lateinit var interactionStatus: TextView
    private lateinit var transcript: TextView
    private lateinit var answer: TextView
    private lateinit var sendButton: Button
    private lateinit var listenButton: Button
    private lateinit var wakeButton: Button
    private lateinit var registrationButton: Button
    private lateinit var updateAppButton: Button
    private lateinit var photoButton: Button
    private lateinit var photoPreview: ImageView

    private var datInitialized = false
    private var deviceSession: DeviceSession? = null
    private var speech: Speech? = null
    private var inputs: Inputs? = null
    private var sessionJob: Job? = null
    private var speechJobs = mutableListOf<Job>()
    private val inputJobs = mutableListOf<Job>()
    private var player: MediaPlayer? = null
    private var toneGenerator: ToneGenerator? = null
    private var rearmJob: Job? = null
    private var questionTimerJob: Job? = null
    private var questionTimerId: Int? = null
    private var lastArmAt = 0L
    private var speechStartedAt = 0L
    private var restartSpeechWhenStopped = false
    private var wakeServiceRunning = false
    private var rapidRecognizerStops = 0
    private var reconnectJob: Job? = null
    private var reconnectAttempts = 0

    /** Pure idle/listening/busy transitions live here; see ai.hermes.glasses.interaction. */
    private val tapToTalk = TapToTalkStateMachine()

    private val bluetoothPermission = registerForActivityResult(RequestPermission()) { granted ->
        if (granted) initializeDat() else showStatus("Bluetooth permission is required for the glasses")
    }

    /**
     * Without it the wake-up notification is hidden, but the service still runs. A notification
     * posted while denied is not shown after a later grant, so have the service post it again.
     */
    private val notificationPermission = registerForActivityResult(RequestPermission()) { granted ->
        if (granted && wakeServiceRunning) runCatching { WakeListeningService.start(this) }
    }

    private val datMicrophonePermission =
        registerForActivityResult(Wearables.RequestPermissionContract()) { result ->
            result.onSuccess { permission ->
                if (permission == PermissionStatus.Granted) startDeviceSession()
                else showStatus("Meta AI microphone permission was not granted")
            }.onFailure { error, _ -> showStatus("Permission error: ${error.description}") }
        }

    private val datCameraPermission =
        registerForActivityResult(Wearables.RequestPermissionContract()) { result ->
            result.onSuccess { permission ->
                if (permission == PermissionStatus.Granted) showStatus("Camera allowed. Ask again to use it.")
                else showStatus("Meta AI camera permission was not granted")
            }.onFailure { error, _ -> showStatus("Permission error: ${error.description}") }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        settings = SecureSettings(this)
        setContentView(buildUi())
        populateSettings()
        wireActions()
        toneGenerator = runCatching { ToneGenerator(AudioManager.STREAM_MUSIC, 70) }.getOrNull()
        // DAT leaves photos it received for an abandoned capture here; nothing transfers before init.
        File(filesDir, "recv/dat").listFiles()?.forEach(File::delete)
        File(filesDir, "photo-test").deleteRecursively()
        if (settings.wakeEnabled) dispatch(TapToTalkEvent.EnableWake) else updateInteractionUi()
        ensureBluetoothThenInitialize()
        lifecycleScope.launch {
            WakeListeningService.stopRequested.collect { dispatch(TapToTalkEvent.DisableWake) }
        }
        lifecycleScope.launch {
            while (true) {
                delay(SPEECH_WATCHDOG_MS)
                if (tapToTalk.state == TapToTalkState.STANDBY) ensureFreshSpeech(STANDBY_MAX_SESSION_MS)
            }
        }
    }

    override fun onDestroy() {
        player?.release()
        player = null
        toneGenerator?.release()
        toneGenerator = null
        speechJobs.forEach(Job::cancel)
        inputJobs.forEach(Job::cancel)
        rearmJob?.cancel()
        reconnectJob?.cancel()
        questionTimerJob?.cancel()
        sessionJob?.cancel()
        if (isFinishing) {
            stopWakeService()
            deviceSession?.let { session ->
                detachInputs(session)
                session.stop()
            }
        }
        super.onDestroy()
    }

    private fun ensureBluetoothThenInitialize() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
            ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) ==
            PackageManager.PERMISSION_GRANTED
        ) {
            initializeDat()
        } else {
            bluetoothPermission.launch(Manifest.permission.BLUETOOTH_CONNECT)
        }
    }

    private fun initializeDat() {
        if (datInitialized) return
        Wearables.initialize(applicationContext).fold(
            onSuccess = { onDatReady() },
            onFailure = { error, _ ->
                // Wearables is process-wide; a recreated Activity finds it already initialized.
                if (error == WearablesError.ALREADY_INITIALIZED) onDatReady()
                else showStatus("Meta toolkit error: ${error.description}")
            },
        )
    }

    private fun onDatReady() {
        datInitialized = true
        showStatus("Meta toolkit ready")
        lifecycleScope.launch {
            Wearables.registrationState.collect { state ->
                registrationButton.text = when (state) {
                    RegistrationState.REGISTERED -> "Meta AI connected"
                    RegistrationState.REGISTERING -> "Connecting to Meta AI…"
                    else -> "Connect Meta glasses"
                }
                // With wake-up on, reconnect on launch so standby resumes without a tap.
                if (state == RegistrationState.REGISTERED && tapToTalk.wakeEnabled && deviceSession == null) {
                    requestSpeechPermission()
                }
            }
        }
    }

    private fun connectGlasses() {
        if (!datInitialized) {
            ensureBluetoothThenInitialize()
            return
        }
        if (Wearables.registrationState.value == RegistrationState.REGISTERED) {
            requestSpeechPermission()
        } else {
            Wearables.startRegistration(this)
        }
    }

    private fun requestSpeechPermission() {
        lifecycleScope.launch {
            Wearables.checkPermissionStatus(DatPermission.MICROPHONE).fold(
                onSuccess = { state ->
                    if (state == PermissionStatus.Granted) startDeviceSession()
                    else datMicrophonePermission.launch(DatPermission.MICROPHONE)
                },
                onFailure = { error, _ -> showStatus("Permission check failed: ${error.description}") },
            )
        }
    }

    private fun startDeviceSession() {
        if (deviceSession != null) {
            return
        }
        Wearables.createSession(AutoDeviceSelector()).fold(
            onSuccess = { created ->
                deviceSession = created
                // Collectors of a previous, stopped session would otherwise pile up on reconnects.
                sessionJob?.cancel()
                sessionJob = lifecycleScope.launch {
                    launch {
                        created.errors.collect { error -> handleGlassesError(error.description) }
                    }
                    launch {
                        created.state.collect { state ->
                            showStatus("Glasses session: ${state.name.lowercase()}")
                            when (state) {
                                DeviceSessionState.STARTED -> {
                                    reconnectAttempts = 0
                                    attachSpeech(created)
                                    attachInputs(created)
                                }
                                DeviceSessionState.STOPPED -> {
                                    detachInputs(created)
                                    speech = null
                                    deviceSession = null
                                    dispatch(TapToTalkEvent.Disconnected)
                                    scheduleReconnect()
                                }
                                else -> Unit
                            }
                        }
                    }
                }
                // `start()` returns Unit: it reports only that the request left the phone.
                // Everything the glasses have to say about hosting the session arrives
                // afterwards on `created.state`, which the collector above is watching.
                created.start()
            },
            onFailure = { error, _ ->
                showStatus("Could not create glasses session: ${error.description}")
                scheduleReconnect()
            },
        )
    }

    /**
     * While wake-up is on, reconnect after the glasses end the session. A touchpad double tap
     * does that (and a single tap pauses it), as do folding or taking off the glasses; without
     * this, standby stays deaf until the wearer reconnects in the app. Backs off up to a minute.
     */
    private fun scheduleReconnect() {
        if (!tapToTalk.wakeEnabled || isFinishing || reconnectJob?.isActive == true) return
        val delayMs = RECONNECT_DELAYS_MS[reconnectAttempts.coerceAtMost(RECONNECT_DELAYS_MS.lastIndex)]
        reconnectAttempts++
        showStatus("Glasses disconnected; reconnecting in ${delayMs / 1000} s")
        reconnectJob = lifecycleScope.launch {
            delay(delayMs)
            if (tapToTalk.wakeEnabled && deviceSession == null &&
                Wearables.registrationState.value == RegistrationState.REGISTERED
            ) {
                requestSpeechPermission()
            }
        }
    }

    private fun attachSpeech(session: DeviceSession) {
        if (speech != null) return
        session.addSpeech().fold(
            onSuccess = { attached ->
                speech = attached
                speechJobs += lifecycleScope.launch {
                    attached.errors.collect { error ->
                        if (error != null) handleGlassesError(error.description)
                    }
                }
                speechJobs += lifecycleScope.launch {
                    attached.transcriptions.collect { result ->
                        if (result == null) return@collect
                        // Standby hears ordinary conversation; keep it off the screen.
                        transcript.text = when {
                            tapToTalk.state == TapToTalkState.STANDBY -> "Standby: listening for the wake phrase"
                            result.isFinal -> "You: ${result.text}"
                            else -> "Hearing: ${result.text}"
                        }
                        if (result.isFinal) {
                            dispatch(TapToTalkEvent.FinalTranscript(result.text))
                        } else if (tapToTalk.state == TapToTalkState.LISTENING && result.text.isNotBlank()) {
                            // Still talking: the final only arrives once the question ends.
                            questionTimerId?.let(::startQuestionTimer)
                        }
                    }
                }
                speechJobs += lifecycleScope.launch {
                    // DAT Speech is utterance-scoped: each completed utterance returns to STOPPED.
                    // Only report real transitions, not the STOPPED value replayed on subscribe.
                    var previous: SpeechState? = null
                    attached.state.collect { state ->
                        if (state == SpeechState.STOPPED && previous != null && previous != SpeechState.STOPPED) {
                            if (restartSpeechWhenStopped) {
                                restartSpeechWhenStopped = false
                                startSpeech()
                            }
                            onRecognizerStopped()
                        }
                        previous = state
                    }
                }
                dispatch(TapToTalkEvent.SpeechReady)
            },
            onFailure = { error, _ -> showStatus("Speech is unavailable: ${error.description}") },
        )
    }

    /**
     * Only ACTION_BUTTON is requested: Allen wants the physical action button as the hands-free
     * trigger, not a temple swipe. Per the DAT 1.0 ButtonType docs the action button surfaces as
     * InputEvent.Button(ButtonType.ACTION); captouch nav/select arrive as Nav/Select and
     * capture-button presses as Capture, so subscribing to this one source keeps the other
     * surfaces out of the voice flow entirely.
     *
     * We deliberately do NOT subscribe to CAPTURE_BUTTON: it is not physically verified whether a
     * capture press also writes a photo to the wearer's camera roll on this model, and we will not
     * risk a hidden capture as a side effect of talking to Hermes.
     *
     * consumeBack = true is kept even though CAPTOUCH is not subscribed. It is cheap insurance:
     * an unconsumed back gesture tears down the entire DeviceSession, and the phone then sees only
     * a session that ended for no nameable reason.
     */
    private fun attachInputs(session: DeviceSession) {
        if (inputs != null) return
        session.addInputs(InputsConfiguration(sources = setOf(InputSource.ACTION_BUTTON), consumeBack = true)).fold(
            onSuccess = { attached ->
                inputs = attached
                var reachedActive = false
                inputJobs += lifecycleScope.launch {
                    attached.state.collect { state ->
                        when (state) {
                            InputsState.ACTIVE -> reachedActive = true
                            // INACTIVE is also the birth state and is replayed to a new
                            // collector; only treat it as a real drop once we know inputs had
                            // actually reached ACTIVE, otherwise we'd tear down a capability
                            // still coming up.
                            InputsState.INACTIVE -> if (reachedActive) {
                                showStatus("Action button is no longer active")
                            }
                            else -> Unit
                        }
                    }
                }
                inputJobs += lifecycleScope.launch {
                    attached.errors.collect { error ->
                        if (error != null) handleGlassesError(error.description)
                    }
                }
                inputJobs += lifecycleScope.launch {
                    attached.events.collect { event ->
                        // The action button surfaces as InputEvent.Button with
                        // ButtonType.ACTION (per the DAT 1.0 ButtonType docs: captouch nav/select
                        // arrive as Nav/Select, and capture-button presses as Capture, NOT as
                        // Button). We accept Select from ACTION_BUTTON too, because some firmware
                        // maps a confirm press onto the semantic select event instead.
                        when {
                            event is InputEvent.Button &&
                                event.button == ButtonType.ACTION -> dispatch(TapToTalkEvent.TriggerPressed)

                            event is InputEvent.Select &&
                                event.source == InputSource.ACTION_BUTTON -> dispatch(TapToTalkEvent.TriggerPressed)

                            else -> showStatus("Glasses input ignored: $event")
                        }
                    }
                }
            },
            onFailure = { error, _ -> showStatus("Action button is unavailable: ${error.description}") },
        )
    }

    private fun detachInputs(session: DeviceSession) {
        inputJobs.forEach(Job::cancel)
        inputJobs.clear()
        inputs = null
        runCatching { session.removeInputs() }
    }

    private fun startSpeech() {
        val activeSpeech = speech ?: return
        if (activeSpeech.state.value != SpeechState.STOPPED) return
        activeSpeech.start().fold(
            onSuccess = { speechStartedAt = System.currentTimeMillis() },
            onFailure = { error, _ -> showStatus("Could not start speech: ${error.description}") },
        )
    }

    /**
     * A DAT Speech session streams many utterances, then the glasses end it about 60 s after it
     * started. The SDK (1.0.0) logs that completion but leaves `state` at STARTED, so a session
     * cannot be seen to die. Restart any session older than [maxAgeMs] instead of trusting state.
     */
    private fun ensureFreshSpeech(maxAgeMs: Long) {
        val activeSpeech = speech ?: return
        when (activeSpeech.state.value) {
            SpeechState.STOPPED -> startSpeech()
            SpeechState.STARTED -> if (System.currentTimeMillis() - speechStartedAt >= maxAgeMs) {
                restartSpeechWhenStopped = true
                activeSpeech.stop()
            }
            SpeechState.STOPPING -> restartSpeechWhenStopped = true
            else -> Unit
        }
    }

    /**
     * Times out a wake-started capture after [QUESTION_SILENCE_MS] with no speech. Partial
     * transcripts restart it; on the glasses a whole question took 6–8 s to arrive as a final.
     */
    private fun startQuestionTimer(id: Int) {
        questionTimerId = id
        questionTimerJob?.cancel()
        questionTimerJob = lifecycleScope.launch {
            delay(QUESTION_SILENCE_MS)
            questionTimerId = null
            dispatch(TapToTalkEvent.QuestionTimeout(id))
        }
    }

    private fun stopSpeech() {
        restartSpeechWhenStopped = false
        speech?.takeIf { it.state.value != SpeechState.STOPPED }?.stop()
    }

    /** A short delay lets a stopping session finish; its STOPPED transition re-arms again anyway. */
    private fun scheduleRearm() {
        rearmJob?.cancel()
        rearmJob = lifecycleScope.launch {
            delay(REARM_DELAY_MS)
            if (tapToTalk.state != TapToTalkState.STANDBY) return@launch
            lastArmAt = System.currentTimeMillis()
            ensureFreshSpeech(STANDBY_MAX_SESSION_MS)
        }
    }

    /** Stops that follow arming almost immediately mean the recognizer cannot stay up; stop looping. */
    private fun onRecognizerStopped() {
        if (tapToTalk.state == TapToTalkState.STANDBY) {
            val rapid = System.currentTimeMillis() - lastArmAt < RAPID_STOP_MS
            rapidRecognizerStops = if (rapid) rapidRecognizerStops + 1 else 0
            if (rapidRecognizerStops >= MAX_RAPID_STOPS) {
                rapidRecognizerStops = 0
                showStatus("Wake standby stopped: glasses speech keeps ending immediately")
                dispatch(TapToTalkEvent.RecognizerFailed)
                return
            }
        }
        dispatch(TapToTalkEvent.RecognizerStopped)
    }

    /** Started only from foreground paths (launch, the toggle); Android refuses it from the background. */
    private fun startWakeService() {
        if (wakeServiceRunning) return
        // Wake-up is often already on from a saved setting, so ask here rather than on the toggle.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        runCatching { WakeListeningService.start(this) }
            .onSuccess { wakeServiceRunning = true }
            .onFailure { showStatus("Wake-up will stop when the phone locks: ${it.message}") }
    }

    private fun stopWakeService() {
        if (!wakeServiceRunning) return
        WakeListeningService.stop(this)
        wakeServiceRunning = false
    }

    private fun toggleWake() {
        val enable = !tapToTalk.wakeEnabled
        settings.wakeEnabled = enable
        if (enable) {
            dispatch(TapToTalkEvent.EnableWake)
            if (speech == null) connectGlasses()
        } else {
            dispatch(TapToTalkEvent.DisableWake)
        }
    }

    /** Applies the effects of a tap-to-talk transition, then refreshes the UI to match. */
    private fun dispatch(event: TapToTalkEvent) {
        applyTapToTalkEffects(tapToTalk.on(event))
        // "Stop listening" can turn wake-up off by voice; keep the saved toggle and service in step.
        if (settings.wakeEnabled != tapToTalk.wakeEnabled) settings.wakeEnabled = tapToTalk.wakeEnabled
        if (tapToTalk.wakeEnabled) startWakeService() else stopWakeService()
        updateInteractionUi()
    }

    private fun applyTapToTalkEffects(effects: List<TapToTalkEffect>) {
        effects.forEach { effect ->
            when (effect) {
                // Leave room in the session for a whole question before the ~60 s cutoff.
                TapToTalkEffect.StartListening -> ensureFreshSpeech(CAPTURE_MAX_SESSION_MS)
                TapToTalkEffect.StopListening -> stopSpeech()
                is TapToTalkEffect.Submit -> handleSubmitEffect(effect.text, effect.withImage)
                TapToTalkEffect.PlayReadyCue -> playCue(ToneGenerator.TONE_PROP_ACK)
                TapToTalkEffect.PlayCapturedCue -> playCue(ToneGenerator.TONE_PROP_BEEP)
                TapToTalkEffect.PlayCancelCue -> playCue(ToneGenerator.TONE_PROP_NACK)
                TapToTalkEffect.PlayWakeCue -> playCue(ToneGenerator.TONE_PROP_BEEP2)
                TapToTalkEffect.ArmWake -> scheduleRearm()
                TapToTalkEffect.DisarmWake -> {
                    rearmJob?.cancel()
                    stopSpeech()
                }
                is TapToTalkEffect.StartQuestionTimer -> startQuestionTimer(effect.id)
            }
        }
    }

    private fun playCue(tone: Int) {
        runCatching { toneGenerator?.startTone(tone, 120) }
    }

    private fun updateInteractionUi() {
        val state = tapToTalk.state
        sendButton.isEnabled = state != TapToTalkState.BUSY
        photoButton.isEnabled = state != TapToTalkState.BUSY
        listenButton.text = when (state) {
            TapToTalkState.IDLE, TapToTalkState.STANDBY -> "Start glasses listening"
            TapToTalkState.LISTENING -> "Listening on glasses (tap again to cancel)"
            TapToTalkState.BUSY -> "Asking Hermes…"
        }
        wakeButton.text = if (tapToTalk.wakeEnabled) "Turn off voice wake-up" else "Turn on voice wake-up (experimental)"
        interactionStatus.text = when {
            state == TapToTalkState.STANDBY -> "Glasses state: standby — say “Hey Hermes”"
            state == TapToTalkState.IDLE && tapToTalk.wakeEnabled -> "Glasses state: idle — wake-up waits for glasses speech"
            else -> "Glasses state: ${state.name.lowercase()}"
        }
    }

    private fun handleGlassesError(description: String) {
        showStatus("Glasses error: $description")
        // Best-effort match: this build could not verify the SDK's real error-code type against
        // the reference implementation (sandboxed away from it), so it matches on the error
        // description text rather than a typed ErrorCode constant. Verify this against a real
        // DAT_APP_ON_THE_GLASSES_UPDATE_REQUIRED failure and tighten to a typed check if the SDK
        // exposes one.
        if (description.contains("UPDATE_REQUIRED", ignoreCase = true) ||
            description.contains("update required", ignoreCase = true)
        ) {
            updateAppButton.visibility = View.VISIBLE
        }
    }

    private fun handleSubmitEffect(text: String, withImage: Boolean) {
        val question = text.trim()
        if (question.isEmpty()) {
            showStatus("Nothing to ask Hermes")
            dispatch(TapToTalkEvent.ResponseFailed)
            return
        }
        val normalizedUrl = try {
            BridgeEndpoint.normalize(bridgeUrl.text.toString())
        } catch (error: IllegalArgumentException) {
            showStatus(error.message ?: "Invalid bridge URL")
            dispatch(TapToTalkEvent.ResponseFailed)
            return
        }
        val key = apiKey.text.toString().trim()
        if (key.isEmpty()) {
            showStatus("Enter the bridge API key first")
            dispatch(TapToTalkEvent.ResponseFailed)
            return
        }
        saveSettings(normalizedUrl, key)
        lifecycleScope.launch {
            try {
                val image = if (withImage) takePhotoForQuestion() ?: return@launch else null
                showStatus(if (image != null) "Asking Hermes about the photo…" else "Asking Hermes… cloned speech can take several seconds")
                val client = HermesBridgeClient(normalizedUrl, key)
                val askedAt = SystemClock.elapsedRealtime()
                val response = withContext(Dispatchers.IO) {
                    client.turn(question, selectedLanguage(), settings.sessionId, image)
                }
                if (image != null) Log.i(PHOTO_LOG_TAG, "Hermes answered in ${SystemClock.elapsedRealtime() - askedAt} ms")
                settings.sessionId = response.sessionId
                answer.text = "Hermes: ${response.text}"
                if (response.audioPath != null) {
                    val file = File(cacheDir, "hermes-reply-${System.currentTimeMillis()}.wav")
                    withContext(Dispatchers.IO) { client.downloadAudio(response.audioPath, file) }
                    play(file)
                    showStatus("Playing Hermes in the cloned voice")
                } else {
                    showStatus("Hermes answered without audio")
                    dispatch(TapToTalkEvent.ResponseFinished)
                }
            } catch (error: Exception) {
                showStatus(error.message ?: "Hermes request failed")
                dispatch(TapToTalkEvent.ResponseFailed)
            }
        }
    }

    /**
     * Takes the photo for a visual question, or reports why not and ends the turn (returns null).
     * The photo is kept in memory only: shown as a preview and sent once as JPEG.
     */
    private suspend fun takePhotoForQuestion(): ByteArray? {
        val session = deviceSession?.takeIf { it.state.value == DeviceSessionState.STARTED }
        if (session == null) {
            showStatus("Connect the glasses to take a photo")
            dispatch(TapToTalkEvent.ResponseFailed)
            return null
        }
        val permission = Wearables.checkPermissionStatus(DatPermission.CAMERA).fold(
            onSuccess = { it },
            onFailure = { error, _ ->
                showStatus("Camera permission check failed: ${error.description}")
                null
            },
        )
        if (permission != PermissionStatus.Granted) {
            if (permission != null) datCameraPermission.launch(DatPermission.CAMERA)
            dispatch(TapToTalkEvent.ResponseFailed)
            return null
        }
        val startedAt = SystemClock.elapsedRealtime()
        showStatus("Taking photo on glasses…")
        return try {
            val photo = GlassesPhotoCapture.capture(session, PHOTO_RESOLUTION, PHOTO_QUALITY) { fraction ->
                showStatus("Receiving photo… ${(fraction * 100).toInt()}%")
            }
            playCue(ToneGenerator.TONE_PROP_BEEP2)
            val jpeg = withContext(Dispatchers.Default) { PhotoEncoding.toJpeg(photo.imageData) }
            val preview = BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size)
            photoPreview.setImageBitmap(preview)
            photoPreview.visibility = View.VISIBLE
            Log.i(
                PHOTO_LOG_TAG,
                "photo ${photo.imageData.size / 1024} KB HEIC → ${jpeg.size / 1024} KB JPEG " +
                    "${preview?.width}×${preview?.height}; capture ${photo.captureMs} ms, " +
                    "total ${SystemClock.elapsedRealtime() - startedAt} ms, stale skipped ${photo.staleSkipped}",
            )
            jpeg
        } catch (error: TimeoutCancellationException) {
            Log.w(PHOTO_LOG_TAG, "photo timed out")
            showStatus("The glasses photo timed out")
            playCue(ToneGenerator.TONE_PROP_NACK)
            dispatch(TapToTalkEvent.ResponseFailed)
            null
        } catch (error: Exception) {
            Log.w(PHOTO_LOG_TAG, "photo failed: ${error.message}")
            showStatus(error.message ?: "The glasses photo failed")
            playCue(ToneGenerator.TONE_PROP_NACK)
            dispatch(TapToTalkEvent.ResponseFailed)
            null
        }
    }

    private fun testBridge() {
        val normalizedUrl = try {
            BridgeEndpoint.normalize(bridgeUrl.text.toString())
        } catch (error: IllegalArgumentException) {
            showStatus(error.message ?: "Invalid bridge URL")
            return
        }
        lifecycleScope.launch {
            showStatus("Checking bridge…")
            try {
                val result = withContext(Dispatchers.IO) {
                    HermesBridgeClient(normalizedUrl, apiKey.text.toString().trim()).health()
                }
                showStatus(result)
            } catch (error: Exception) {
                showStatus(error.message ?: "Bridge check failed")
            }
        }
    }

    private fun play(file: File) {
        player?.release()
        player = MediaPlayer().apply {
            setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ASSISTANT)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build(),
            )
            setDataSource(file.absolutePath)
            setOnCompletionListener {
                it.release()
                if (player === it) player = null
                file.delete()
                dispatch(TapToTalkEvent.ResponseFinished)
            }
            setOnErrorListener { mediaPlayer, _, _ ->
                mediaPlayer.release()
                if (player === mediaPlayer) player = null
                file.delete()
                showStatus("Could not play the returned audio")
                dispatch(TapToTalkEvent.ResponseFailed)
                true
            }
            prepare()
            start()
        }
    }

    private fun selectedLanguage(): String = when (language.selectedItemPosition) {
        1 -> "hi"
        2 -> "fr"
        else -> "en"
    }

    private fun saveSettings(url: String, key: String) {
        settings.bridgeUrl = url
        settings.language = selectedLanguage()
        settings.saveApiKey(key)
    }

    private fun populateSettings() {
        bridgeUrl.setText(settings.bridgeUrl)
        apiKey.setText(settings.loadApiKey())
        language.setSelection(when (settings.language) { "hi" -> 1; "fr" -> 2; else -> 0 })
    }

    private fun wireActions() {
        registrationButton.setOnClickListener { connectGlasses() }
        listenButton.setOnClickListener {
            if (speech == null) connectGlasses() else dispatch(TapToTalkEvent.TriggerPressed)
        }
        wakeButton.setOnClickListener { toggleWake() }
        sendButton.setOnClickListener { dispatch(TapToTalkEvent.ManualSubmit(prompt.text.toString())) }
        updateAppButton.setOnClickListener { Wearables.openDATGlassesAppUpdate(this) }
        photoButton.setOnClickListener {
            val typed = prompt.text.toString().trim()
            dispatch(TapToTalkEvent.ManualSubmit(typed.ifEmpty { DEFAULT_LOOK_QUESTION }, withImage = true))
        }
        findViewById<Button>(R.id.test_bridge).setOnClickListener { testBridge() }
        findViewById<Button>(R.id.clear_session).setOnClickListener {
            settings.clearSession()
            answer.text = "Conversation cleared."
            showStatus("The next question starts a new Hermes session")
        }
    }

    private fun showStatus(message: String) {
        status.text = message
    }

    private fun buildUi(): View {
        val padding = dp(20)
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(padding, padding, padding, padding)
            setBackgroundColor(0xFFF4F1EA.toInt())
        }

        content.addView(TextView(this).apply {
            text = "Hermes Glasses"
            textSize = 30f
            setTextColor(0xFF17223B.toInt())
        })
        content.addView(label("Private voice assistant over your tailnet"))

        bridgeUrl = field("Bridge URL")
        apiKey = field("Bridge API key").apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        content.addView(bridgeUrl)
        content.addView(apiKey)

        language = Spinner(this).apply {
            adapter = ArrayAdapter(
                this@MainActivity,
                android.R.layout.simple_spinner_dropdown_item,
                listOf("English", "हिन्दी", "Français"),
            )
        }
        content.addView(language, rowParams())

        content.addView(Button(this).apply { id = R.id.test_bridge; text = "Test Mac bridge" }, rowParams())
        registrationButton = Button(this).apply { text = "Connect Meta glasses" }
        listenButton = Button(this).apply { text = "Start glasses listening" }
        content.addView(registrationButton, rowParams())
        content.addView(listenButton, rowParams())
        wakeButton = Button(this).apply { text = "Turn on voice wake-up (experimental)" }
        content.addView(wakeButton, rowParams())

        updateAppButton = Button(this).apply {
            text = "Update Meta AI app on glasses"
            visibility = View.GONE
        }
        content.addView(updateAppButton, rowParams())

        transcript = label("Glasses transcript will appear here.")
        content.addView(transcript)

        interactionStatus = label("Glasses state: idle").apply { textSize = 14f }
        content.addView(interactionStatus)

        prompt = field("Or type a question")
        content.addView(prompt)
        sendButton = Button(this).apply { text = "Ask Hermes" }
        content.addView(sendButton, rowParams())
        photoButton = Button(this).apply { text = "Look: ask Hermes about what I see" }
        content.addView(photoButton, rowParams())
        photoPreview = ImageView(this).apply {
            adjustViewBounds = true
            visibility = View.GONE
        }
        content.addView(photoPreview, rowParams())
        content.addView(Button(this).apply { id = R.id.clear_session; text = "New conversation" }, rowParams())

        answer = label("Hermes's answer will appear here.").apply { textSize = 18f }
        content.addView(answer)
        status = label("Starting…").apply {
            setPadding(0, dp(20), 0, dp(24))
            setTextColor(0xFF275DAD.toInt())
        }
        content.addView(status)

        return ScrollView(this).apply { addView(content) }
    }

    private fun field(hintText: String) = EditText(this).apply {
        hint = hintText
        setSingleLine(true)
        layoutParams = rowParams()
    }

    private fun label(value: String) = TextView(this).apply {
        text = value
        textSize = 16f
        gravity = Gravity.START
        setTextColor(0xFF39445D.toInt())
        setPadding(0, dp(8), 0, dp(8))
    }

    private fun rowParams() = LinearLayout.LayoutParams(
        LinearLayout.LayoutParams.MATCH_PARENT,
        LinearLayout.LayoutParams.WRAP_CONTENT,
    ).apply { topMargin = dp(10) }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private companion object {
        const val REARM_DELAY_MS = 300L
        const val SPEECH_WATCHDOG_MS = 5_000L
        const val STANDBY_MAX_SESSION_MS = 50_000L
        const val CAPTURE_MAX_SESSION_MS = 40_000L
        const val RAPID_STOP_MS = 1_500L
        const val MAX_RAPID_STOPS = 5
        const val QUESTION_SILENCE_MS = 8_000L
        const val PHOTO_LOG_TAG = "HermesPhoto"
        val RECONNECT_DELAYS_MS = longArrayOf(3_000, 5_000, 10_000, 30_000, 60_000)

        /** 720×1280, about 140 KB HEIC: about 5 s over the glasses' Bluetooth link (Phase 0). */
        val PHOTO_RESOLUTION = PhotoResolution.MEDIUM
        val PHOTO_QUALITY = PhotoQuality.MEDIUM
        const val DEFAULT_LOOK_QUESTION = "What am I looking at?"
    }
}
