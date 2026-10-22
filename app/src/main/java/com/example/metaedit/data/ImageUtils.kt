package com.example.metaedit.data

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import kotlin.math.max

object ImageUtils {
    /** Covers bigger than this are scaled down before embedding; players rarely show more. */
    private const val MAX_COVER_EDGE = 1200
    private const val MAX_UNTOUCHED_BYTES = 1_000_000

    fun sniffMime(bytes: ByteArray): String = when {
        bytes.size >= 8 && bytes[0] == 0x89.toByte() && bytes[1] == 'P'.code.toByte() -> "image/png"
        bytes.size >= 3 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xD8.toByte() -> "image/jpeg"
        else -> "image/jpeg"
    }

    fun decodeThumbnail(bytes: ByteArray, maxEdge: Int): Bitmap? = runCatching {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        var sample = 1
        while (max(bounds.outWidth, bounds.outHeight) / (sample * 2) >= maxEdge) sample *= 2
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
    }.getOrNull()

    /**
     * Turns any decodable picture (JPEG, PNG, WebP, HEIC…) into cover art every tag format accepts:
     * JPEG/PNG kept as-is when already small, otherwise re-encoded as a bounded JPEG.
     */
    fun prepareCover(bytes: ByteArray): ArtworkData {
        val mime = sniffMime(bytes)
        val isJpegOrPng = (bytes.size >= 3 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xD8.toByte()) ||
            (bytes.size >= 8 && bytes[0] == 0x89.toByte() && bytes[1] == 'P'.code.toByte())
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        val longEdge = max(bounds.outWidth, bounds.outHeight)
        if (isJpegOrPng && bytes.size <= MAX_UNTOUCHED_BYTES && longEdge in 1..MAX_COVER_EDGE) {
            return ArtworkData(bytes, mime)
        }
        val bitmap = ImageDecoder.decodeBitmap(ImageDecoder.createSource(ByteBuffer.wrap(bytes))) { decoder, info, _ ->
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            val edge = max(info.size.width, info.size.height)
            if (edge > MAX_COVER_EDGE) {
                val scale = MAX_COVER_EDGE.toFloat() / edge
                decoder.setTargetSize(
                    (info.size.width * scale).toInt().coerceAtLeast(1),
                    (info.size.height * scale).toInt().coerceAtLeast(1),
                )
            }
        }
        val out = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, 90, out)
        bitmap.recycle()
        return ArtworkData(out.toByteArray(), "image/jpeg")
    }
}
