package com.example.metaedit.data

import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.MediaMetadataRetriever
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Process
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.provider.OpenableColumns
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException

/**
 * Reads and writes tags for content URIs. jaudiotagger needs a real seekable file, so writes go
 * through a scratch copy in the cache dir that is copied back over the original once it has been
 * re-read successfully.
 */
class MetadataRepository(private val context: Context) {
    private val resolver: ContentResolver = context.contentResolver
    private val workDir get() = File(context.cacheDir, "work").apply { mkdirs() }

    suspend fun load(uri: Uri): LoadedMedia = withContext(Dispatchers.IO) {
        val (name, size) = queryNameAndSize(uri)
        val mime = resolver.getType(uri)
        val retrieved = retrieve(uri)
        val format = TagFormat.detect(name, mime)

        val tags = format?.let { fmt ->
            // Try reading in place through the file descriptor, then fall back to a scratch copy
            // for providers that only hand out pipes (cloud storage etc).
            readViaDescriptor(uri, fmt) ?: runCatching {
                withWorkCopy(uri, fmt) { TagEngine.read(it, fmt) }
            }.getOrNull()
        }

        LoadedMedia(
            uri = uri,
            displayName = name ?: uri.lastPathSegment ?: "Unknown file",
            sizeBytes = size,
            mimeType = mime,
            format = format.takeIf { tags != null },
            tags = tags ?: retrieved.tags,
            info = retrieved.info,
        )
    }

    /** Applies [edits] to [uri]. Throws on failure; the original file is untouched in that case. */
    suspend fun save(uri: Uri, format: TagFormat, edits: TagEdits): SaveReport = withContext(Dispatchers.IO) {
        val size = queryNameAndSize(uri).second ?: 0L
        if (workDir.usableSpace < size + 16 * 1024 * 1024) {
            throw IOException("Not enough free space to edit this file")
        }
        withWorkCopy(uri, format) { work ->
            val report = TagEngine.write(work, format, edits)
            // Make sure what we're about to copy back is still a readable file.
            TagEngine.read(work, format)
            writeBack(work, uri)
            report
        }.also { rescan(uri) }
    }

    fun hasWriteAccess(uri: Uri): Boolean =
        context.checkUriPermission(
            uri, Process.myPid(), Process.myUid(), Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
        ) == PackageManager.PERMISSION_GRANTED

    /**
     * Returns the MediaStore URI for a document picked through the system file picker, if any.
     * Write consent can be requested for MediaStore URIs with [MediaStore.createWriteRequest].
     */
    fun mediaStoreUriFor(uri: Uri): Uri? = when {
        isMediaStoreUri(uri) -> uri
        DocumentsContract.isDocumentUri(context, uri) -> runCatching { MediaStore.getMediaUri(context, uri) }.getOrNull()
        else -> null
    }

    private fun readViaDescriptor(uri: Uri, format: TagFormat): TagSnapshot? = runCatching {
        resolver.openFileDescriptor(uri, "r")?.use { pfd ->
            val path = File("/proc/self/fd/${pfd.fd}")
            if (!path.canRead()) null else TagEngine.read(path, format)
        }
    }.getOrNull()

    private inline fun <T> withWorkCopy(uri: Uri, format: TagFormat, block: (File) -> T): T {
        val work = File.createTempFile("edit_", ".${format.ext}", workDir)
        try {
            resolver.openInputStream(uri)?.use { input ->
                work.outputStream().use { input.copyTo(it, BUFFER) }
            } ?: throw FileNotFoundException("Can't open $uri")
            return block(work)
        } finally {
            work.delete()
        }
    }

    private fun writeBack(source: File, uri: Uri) {
        // "rwt" truncates; some providers only accept "w", so truncate explicitly afterwards too.
        val pfd = runCatching { resolver.openFileDescriptor(uri, "rwt") }.getOrNull()
            ?: resolver.openFileDescriptor(uri, "w")
            ?: throw FileNotFoundException("Can't write $uri")
        pfd.use {
            java.io.FileOutputStream(it.fileDescriptor).use { out ->
                source.inputStream().use { input -> input.copyTo(out, BUFFER) }
                out.flush()
                runCatching { out.channel.truncate(source.length()) }
                runCatching { out.fd.sync() }
            }
        }
    }

    private fun rescan(uri: Uri) {
        val mediaUri = mediaStoreUriFor(uri) ?: return
        // MediaProvider rescans its own files when a write descriptor closes; this nudges it for
        // files written through other providers.
        val path = runCatching {
            resolver.query(mediaUri, arrayOf(MediaStore.MediaColumns.DATA), null, null, null)?.use { c ->
                if (c.moveToFirst()) c.getString(0) else null
            }
        }.getOrNull() ?: return
        MediaScannerConnection.scanFile(context, arrayOf(path), null, null)
    }

    private fun queryNameAndSize(uri: Uri): Pair<String?, Long?> = runCatching {
        resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { c ->
            if (!c.moveToFirst()) return@use null
            val name = c.getColumnIndex(OpenableColumns.DISPLAY_NAME).takeIf { it >= 0 }?.let(c::getString)
            val size = c.getColumnIndex(OpenableColumns.SIZE).takeIf { it >= 0 && !c.isNull(it) }?.let(c::getLong)
            name to size
        }
    }.getOrNull() ?: (uri.lastPathSegment to null)

    private class Retrieved(val tags: TagSnapshot, val info: TechInfo)

    /** Uses the platform extractor for technical info and as a read-only fallback for tags. */
    private fun retrieve(uri: Uri): Retrieved {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(context, uri)
            fun key(k: Int) = retriever.extractMetadata(k)
            val hasVideo = key(MediaMetadataRetriever.METADATA_KEY_HAS_VIDEO) == "yes"
            val info = TechInfo(
                durationMs = key(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull(),
                bitrate = key(MediaMetadataRetriever.METADATA_KEY_BITRATE)?.toIntOrNull(),
                sampleRate = key(MediaMetadataRetriever.METADATA_KEY_SAMPLERATE)?.toIntOrNull(),
                width = if (hasVideo) key(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() else null,
                height = if (hasVideo) key(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() else null,
                hasVideo = hasVideo,
            )
            val values = buildMap {
                put(MetaField.TITLE, key(MediaMetadataRetriever.METADATA_KEY_TITLE))
                put(MetaField.ARTIST, key(MediaMetadataRetriever.METADATA_KEY_ARTIST))
                put(MetaField.ALBUM, key(MediaMetadataRetriever.METADATA_KEY_ALBUM))
                put(MetaField.ALBUM_ARTIST, key(MediaMetadataRetriever.METADATA_KEY_ALBUMARTIST))
                put(MetaField.GENRE, key(MediaMetadataRetriever.METADATA_KEY_GENRE))
                put(MetaField.YEAR, key(MediaMetadataRetriever.METADATA_KEY_YEAR))
                put(MetaField.COMPOSER, key(MediaMetadataRetriever.METADATA_KEY_COMPOSER))
                put(MetaField.TRACK, key(MediaMetadataRetriever.METADATA_KEY_CD_TRACK_NUMBER))
                put(MetaField.DISC_NO, key(MediaMetadataRetriever.METADATA_KEY_DISC_NUMBER))
            }.mapValues { it.value.orEmpty() }
            val art = retriever.embeddedPicture?.let { ArtworkData(it, ImageUtils.sniffMime(it)) }
            Retrieved(TagSnapshot(values, emptySet(), art), info)
        } catch (_: Exception) {
            Retrieved(TagSnapshot(emptyMap(), emptySet(), null), TechInfo())
        } finally {
            runCatching { retriever.release() }
        }
    }

    companion object {
        private const val BUFFER = 256 * 1024

        fun isMediaStoreUri(uri: Uri) = uri.authority == MediaStore.AUTHORITY
    }
}
