package ai.hermes.glasses.camera

import android.media.ExifInterface
import android.os.SystemClock
import com.meta.wearable.dat.camera.addCamera
import com.meta.wearable.dat.camera.photo.types.PhotoCaptureData
import com.meta.wearable.dat.camera.photo.types.PhotoQuality
import com.meta.wearable.dat.camera.photo.types.PhotoResolution
import com.meta.wearable.dat.camera.photo.types.PhotoState
import com.meta.wearable.dat.camera.removeCamera
import com.meta.wearable.dat.camera.types.StreamConfiguration
import com.meta.wearable.dat.core.session.DeviceSession
import java.io.ByteArrayInputStream
import java.text.SimpleDateFormat
import java.util.Locale
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.withTimeout

class CapturedPhoto(
    val imageData: ByteArray,
    val metadataBytes: Int,
    /** addCamera until Photo reports STARTED. */
    val setupMs: Long,
    /** capturePhoto until the image arrived on the phone. */
    val captureMs: Long,
    /** Earlier captures delivered first by the SDK and discarded. */
    val staleSkipped: Int,
    /** EXIF shutter time in epoch ms, or null when the image has none. */
    val takenAt: Long?,
)

class PhotoCaptureException(message: String) : Exception(message)

/**
 * One standalone photo from the glasses through DAT's experimental `camera.photo`.
 *
 * The SDK's Photo is terminal after `stop()`, so each capture adds a fresh Camera to the session
 * and removes it afterwards. The caller must ensure the session is STARTED and Permission.CAMERA
 * is granted, and must not run two captures at once.
 */
object GlassesPhotoCapture {
    private const val SETUP_TIMEOUT_MS = 15_000L
    private const val CAPTURE_TIMEOUT_MS = 120_000L
    /** EXIF time has one-second precision, and the glasses' clock may drift from the phone's. */
    private const val CLOCK_SLACK_MS = 3_000L

    suspend fun capture(
        session: DeviceSession,
        resolution: PhotoResolution = PhotoResolution.FULL,
        quality: PhotoQuality = PhotoQuality.HIGH,
        onProgress: (Double) -> Unit = {},
    ): CapturedPhoto = coroutineScope {
        val setupStart = SystemClock.elapsedRealtime()
        val camera = session.addCamera(StreamConfiguration()).fold(
            onSuccess = { it },
            onFailure = { error, _ -> throw PhotoCaptureException("Camera unavailable: ${error.description}") },
        )
        try {
            val photo = camera.photo
            // photoStream and errors are SharedFlows: subscribe before capturing so nothing is missed.
            // Photo re-delivers captures still queued on the glasses from earlier, abandoned
            // requests (seen on hardware), so only accept one taken after this request.
            var requestedAt = Long.MAX_VALUE
            var staleSkipped = 0
            val outcome = CompletableDeferred<PhotoCaptureData>()
            val collectors = listOf(
                launch {
                    photo.photoStream.collect {
                        val takenAt = takenAt(it.imageData)
                        if (takenAt != null && takenAt < requestedAt - CLOCK_SLACK_MS) staleSkipped++
                        else outcome.complete(it)
                    }
                },
                launch {
                    photo.errors.collect { outcome.completeExceptionally(PhotoCaptureException("Photo error: ${it.description}")) }
                },
                launch { photo.transferProgressStream.collect { onProgress(it.fraction) } },
            )
            try {
                val ready = async { photo.state.first { it == PhotoState.STARTED } }
                try {
                    withTimeout(SETUP_TIMEOUT_MS) {
                        // An error before STARTED (permission, busy, setup) fails here.
                        select<Unit> {
                            outcome.onAwait { }
                            ready.onAwait { }
                        }
                    }
                } finally {
                    ready.cancel()
                }
                val captureStart = SystemClock.elapsedRealtime()
                requestedAt = System.currentTimeMillis()
                photo.capturePhoto(resolution, quality)
                val data = withTimeout(CAPTURE_TIMEOUT_MS) { outcome.await() }
                val done = SystemClock.elapsedRealtime()
                CapturedPhoto(
                    imageData = data.imageData,
                    metadataBytes = data.metadata?.size ?: 0,
                    setupMs = captureStart - setupStart,
                    captureMs = done - captureStart,
                    staleSkipped = staleSkipped,
                    takenAt = takenAt(data.imageData),
                )
            } finally {
                collectors.forEach { it.cancel() }
                runCatching { photo.stop() }
            }
        } finally {
            runCatching { camera.stop() }
            runCatching { session.removeCamera() }
        }
    }

    /**
     * When the shutter fired, from EXIF. `PhotoCaptureData.timestamp` is the arrival time on the
     * phone, so it cannot tell a re-delivered earlier capture from a new one.
     */
    fun takenAt(image: ByteArray): Long? = runCatching {
        val exif = ExifInterface(ByteArrayInputStream(image))
        val local = exif.getAttribute(ExifInterface.TAG_DATETIME_ORIGINAL)
            ?: exif.getAttribute(ExifInterface.TAG_DATETIME)
            ?: return null
        val offset = exif.getAttribute(ExifInterface.TAG_OFFSET_TIME_ORIGINAL)
        val parsed = if (offset != null) {
            SimpleDateFormat("yyyy:MM:dd HH:mm:ssXXX", Locale.US).parse(local + offset)
        } else {
            SimpleDateFormat("yyyy:MM:dd HH:mm:ss", Locale.US).parse(local)
        }
        parsed?.time
    }.getOrNull()
}
