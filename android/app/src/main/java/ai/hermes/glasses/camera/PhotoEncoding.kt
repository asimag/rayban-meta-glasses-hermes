package ai.hermes.glasses.camera

import android.graphics.Bitmap
import android.graphics.ImageDecoder
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer

/**
 * The glasses deliver HEIC; the bridge and vision model take JPEG. ImageDecoder applies the EXIF
 * orientation, so the JPEG is upright without carrying EXIF (or its timestamps) along.
 */
object PhotoEncoding {
    const val MAX_EDGE_PX = 1280
    const val JPEG_QUALITY = 85

    fun toJpeg(image: ByteArray, maxEdge: Int = MAX_EDGE_PX, quality: Int = JPEG_QUALITY): ByteArray {
        val bitmap = ImageDecoder.decodeBitmap(ImageDecoder.createSource(ByteBuffer.wrap(image))) { decoder, info, _ ->
            val longest = maxOf(info.size.width, info.size.height)
            if (longest > maxEdge) {
                val scale = maxEdge.toDouble() / longest
                decoder.setTargetSize(
                    (info.size.width * scale).toInt().coerceAtLeast(1),
                    (info.size.height * scale).toInt().coerceAtLeast(1),
                )
            }
            // Software allocation so Bitmap.compress can read the pixels.
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
        }
        return try {
            ByteArrayOutputStream().use { out ->
                check(bitmap.compress(Bitmap.CompressFormat.JPEG, quality, out)) { "JPEG encoding failed" }
                out.toByteArray()
            }
        } finally {
            bitmap.recycle()
        }
    }
}
