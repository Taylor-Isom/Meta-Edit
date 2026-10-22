package com.example.metaedit.data

import android.graphics.BitmapFactory
import com.example.metaedit.data.mp4.Mp4Metadata
import org.jaudiotagger.audio.AudioFileIO
import org.jaudiotagger.audio.mp3.MP3File
import org.jaudiotagger.tag.Tag
import org.jaudiotagger.tag.TagOptionSingleton
import org.jaudiotagger.tag.images.AndroidArtwork
import java.io.File
import java.util.logging.Level
import java.util.logging.Logger

/**
 * Containers we can write. MP4-family files (including video) use our own [Mp4Metadata]:
 * jaudiotagger refuses MP4s with video tracks and can't update 64-bit chunk offsets.
 */
enum class TagFormat(val label: String, val ext: String, val isMp4: Boolean = false) {
    MP3("MP3", "mp3"),
    FLAC("FLAC", "flac"),
    OGG("Ogg Vorbis", "ogg"),
    WAV("WAV", "wav"),
    AIFF("AIFF", "aif"),
    WMA("WMA", "wma"),
    DSF("DSF", "dsf"),
    M4A("MPEG-4 audio", "m4a", isMp4 = true),
    MP4("MPEG-4 video", "mp4", isMp4 = true),
    MOV("QuickTime movie", "mov", isMp4 = true),
    THREE_GP("3GPP", "3gp", isMp4 = true);

    companion object {
        private val byExtension = mapOf(
            "mp3" to MP3,
            "flac" to FLAC,
            "ogg" to OGG, "oga" to OGG,
            "wav" to WAV,
            "aif" to AIFF, "aiff" to AIFF, "aifc" to AIFF,
            "wma" to WMA,
            "dsf" to DSF,
            "m4a" to M4A, "m4b" to M4A, "m4p" to M4A, "m4r" to M4A,
            "mp4" to MP4, "m4v" to MP4,
            "mov" to MOV, "qt" to MOV,
            "3gp" to THREE_GP, "3g2" to THREE_GP,
        )

        private val byMime = mapOf(
            "audio/mpeg" to MP3, "audio/mp3" to MP3,
            "audio/flac" to FLAC, "audio/x-flac" to FLAC,
            "audio/ogg" to OGG, "audio/vorbis" to OGG,
            "audio/wav" to WAV, "audio/x-wav" to WAV, "audio/wave" to WAV,
            "audio/aiff" to AIFF, "audio/x-aiff" to AIFF,
            "audio/x-ms-wma" to WMA,
            "audio/mp4" to M4A, "audio/x-m4a" to M4A, "audio/m4a" to M4A,
            "video/mp4" to MP4, "video/x-m4v" to MP4,
            "video/quicktime" to MOV,
            "video/3gpp" to THREE_GP, "video/3gpp2" to THREE_GP, "audio/3gpp" to THREE_GP,
        )

        fun detect(displayName: String?, mimeType: String?): TagFormat? {
            val ext = displayName?.substringAfterLast('.', "")?.lowercase()
            return byExtension[ext] ?: byMime[mimeType?.lowercase()]
        }
    }
}

/** Thin wrapper around jaudiotagger that works on local files only. */
object TagEngine {
    init {
        // Use AndroidArtwork instead of the javax.imageio-based default.
        TagOptionSingleton.getInstance().isAndroid = true
        Logger.getLogger("org.jaudiotagger").level = Level.OFF
    }

    fun read(file: File, format: TagFormat): TagSnapshot {
        if (format.isMp4) return Mp4Metadata.read(file)
        val tag: Tag = AudioFileIO.readAs(file, format.ext).tagAndConvertOrCreateDefault
        val values = mutableMapOf<MetaField, String>()
        for (field in MetaField.entries) {
            try {
                values[field] = tag.getFirst(field.key).orEmpty()
            } catch (_: Exception) {
                // Key not representable in this tag format.
            }
        }
        val artwork = runCatching {
            tag.firstArtwork?.binaryData?.takeIf { it.isNotEmpty() }?.let { bytes ->
                ArtworkData(bytes, tag.firstArtwork.mimeType ?: ImageUtils.sniffMime(bytes))
            }
        }.getOrNull()
        return TagSnapshot(values, values.keys, artwork)
    }

    fun write(file: File, format: TagFormat, edits: TagEdits): SaveReport {
        if (format.isMp4) return Mp4Metadata.write(file, edits)
        val skipped = mutableMapOf<MetaField, String>()
        val audioFile = AudioFileIO.readAs(file, format.ext)
        val tag = audioFile.tagAndConvertOrCreateAndSetDefault
        applyFields(tag, edits, skipped)
        val artworkError = applyArtwork(tag, edits.artwork)
        // Keep a legacy ID3v1 tag in sync so older players don't show stale values.
        if (audioFile is MP3File && audioFile.hasID3v1Tag()) {
            applyFields(audioFile.getID3v1Tag(), edits, mutableMapOf())
        }
        audioFile.commit()
        return SaveReport(skipped, artworkError)
    }

    private fun applyFields(tag: Tag, edits: TagEdits, skipped: MutableMap<MetaField, String>) {
        for ((field, raw) in edits.fields) {
            val value = raw.trim()
            try {
                if (value.isEmpty()) tag.deleteField(field.key) else tag.setField(field.key, value)
            } catch (e: Exception) {
                skipped[field] = when (e) {
                    is NumberFormatException -> "must be a number"
                    else -> e.message ?: "not supported by this format"
                }
            }
        }
    }

    private fun applyArtwork(tag: Tag, change: ArtworkChange): String? = try {
        when (change) {
            ArtworkChange.Keep -> Unit
            ArtworkChange.Remove -> tag.deleteArtworkField()
            is ArtworkChange.Replace -> {
                tag.deleteArtworkField()
                tag.setField(SizedArtwork(change.artwork))
            }
        }
        null
    } catch (e: Exception) {
        e.message ?: "cover art not supported by this format"
    }

    /**
     * AndroidArtwork throws from setImageFromData(), which FLAC and Vorbis call to learn the
     * picture dimensions. Decode just the bounds with BitmapFactory instead.
     */
    private class SizedArtwork(data: ArtworkData) : AndroidArtwork() {
        init {
            binaryData = data.bytes
            mimeType = data.mimeType
            pictureType = 3 // Front cover
            description = ""
        }

        override fun setImageFromData(): Boolean {
            val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(binaryData, 0, binaryData.size, options)
            if (options.outWidth <= 0 || options.outHeight <= 0) return false
            width = options.outWidth
            height = options.outHeight
            return true
        }
    }
}
