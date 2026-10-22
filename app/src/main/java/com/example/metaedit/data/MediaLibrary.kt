package com.example.metaedit.data

import android.content.ContentUris
import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.util.LruCache
import android.util.Size
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.security.MessageDigest

/** Lists the device's music and videos, from MediaStore or from a user-picked folder. */
class MediaLibrary(private val context: Context) {
    private val thumbnails = LruCache<Uri, Bitmap>(120)

    /** Cover-art fingerprints keyed by uri + modification time, so edits invalidate them. */
    private val coverKeys = HashMap<String, String>()

    /** Lists media of every kind in [source]. */
    suspend fun query(source: MediaSource): Map<MediaKind, List<MediaItem>> = withContext(Dispatchers.IO) {
        when (source) {
            is MediaSource.Tree -> queryTree(source.treeUri).groupBy { it.kind }
            else -> MediaKind.entries.associateWith { kind ->
                runCatching { queryMediaStore(kind, source) }.getOrDefault(emptyList())
            }
        }
    }

    /** Folders that contain music or videos, with how many files each holds directly or below. */
    suspend fun folders(): List<MediaFolder> = withContext(Dispatchers.IO) {
        val counts = HashMap<Pair<String, String>, Int>()
        for (kind in MediaKind.entries) {
            runCatching {
                context.contentResolver.query(
                    collection(kind),
                    arrayOf(MediaStore.MediaColumns.VOLUME_NAME, MediaStore.MediaColumns.RELATIVE_PATH),
                    null, null, null,
                )?.use { c ->
                    while (c.moveToNext()) {
                        val volume = c.getString(0) ?: continue
                        val path = c.getString(1) ?: continue
                        // Count the file in its folder and every parent, so "Music/" includes "Music/Albums/".
                        val segments = path.trimEnd('/').split('/').filter { it.isNotEmpty() }
                        for (depth in 1..segments.size) {
                            val key = volume to segments.take(depth).joinToString("/", postfix = "/")
                            counts[key] = (counts[key] ?: 0) + 1
                        }
                    }
                }
            }
        }
        counts.map { (key, count) -> MediaFolder(key.first, key.second, count) }
            .sortedWith(compareBy({ it.volume != MediaStore.VOLUME_EXTERNAL_PRIMARY }, { it.relativePath.lowercase() }))
    }

    fun treeName(treeUri: Uri): String = runCatching {
        val doc = DocumentsContract.buildDocumentUriUsingTree(treeUri, DocumentsContract.getTreeDocumentId(treeUri))
        context.contentResolver.query(doc, arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME), null, null, null)
            ?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
    }.getOrNull() ?: "Selected folder"

    private fun collection(kind: MediaKind) = when (kind) {
        MediaKind.AUDIO -> MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
        MediaKind.VIDEO -> MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
    }

    private fun queryMediaStore(kind: MediaKind, source: MediaSource): List<MediaItem> {
        val collection = collection(kind)
        val projection = arrayOf(
            MediaStore.MediaColumns._ID,
            MediaStore.MediaColumns.DISPLAY_NAME,
            MediaStore.MediaColumns.TITLE,
            MediaStore.MediaColumns.ARTIST,
            MediaStore.MediaColumns.ALBUM,
            MediaStore.MediaColumns.DURATION,
            MediaStore.MediaColumns.SIZE,
            MediaStore.MediaColumns.DATE_MODIFIED,
        )
        var selection: String? = null
        var args: Array<String>? = null
        if (source is MediaSource.Folder) {
            val escaped = source.relativePath.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")
            selection = "${MediaStore.MediaColumns.VOLUME_NAME} = ? AND " +
                "${MediaStore.MediaColumns.RELATIVE_PATH} LIKE ? ESCAPE '\\'"
            args = arrayOf(source.volume, "$escaped%")
        }
        val items = mutableListOf<MediaItem>()
        context.contentResolver.query(
            collection, projection, selection, args, "${MediaStore.MediaColumns.DATE_MODIFIED} DESC",
        )?.use { c ->
            val id = c.getColumnIndexOrThrow(MediaStore.MediaColumns._ID)
            val name = c.getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME)
            val title = c.getColumnIndexOrThrow(MediaStore.MediaColumns.TITLE)
            val artist = c.getColumnIndexOrThrow(MediaStore.MediaColumns.ARTIST)
            val album = c.getColumnIndexOrThrow(MediaStore.MediaColumns.ALBUM)
            val duration = c.getColumnIndexOrThrow(MediaStore.MediaColumns.DURATION)
            val size = c.getColumnIndexOrThrow(MediaStore.MediaColumns.SIZE)
            val modified = c.getColumnIndexOrThrow(MediaStore.MediaColumns.DATE_MODIFIED)
            while (c.moveToNext()) {
                items += MediaItem(
                    uri = ContentUris.withAppendedId(collection, c.getLong(id)),
                    displayName = c.getString(name) ?: "",
                    title = c.getString(title),
                    artist = c.getString(artist)?.takeUnless { it == MediaStore.UNKNOWN_STRING },
                    album = c.getString(album),
                    durationMs = c.getLong(duration),
                    sizeBytes = c.getLong(size),
                    kind = kind,
                    dateModified = c.getLong(modified) * 1000,
                )
            }
        }
        return items
    }

    private class TreeDoc(val uri: Uri, val name: String, val mime: String, val size: Long, val modified: Long)

    /** Walks a picked folder tree; tags come from the platform extractor since MediaStore isn't involved. */
    private suspend fun queryTree(treeUri: Uri): List<MediaItem> = coroutineScope {
        val docs = mutableListOf<TreeDoc>()
        val pending = ArrayDeque(listOf(DocumentsContract.getTreeDocumentId(treeUri)))
        while (pending.isNotEmpty() && docs.size < MAX_TREE_FILES) {
            val parent = pending.removeFirst()
            val children = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, parent)
            runCatching {
                context.contentResolver.query(
                    children,
                    arrayOf(
                        DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                        DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                        DocumentsContract.Document.COLUMN_MIME_TYPE,
                        DocumentsContract.Document.COLUMN_SIZE,
                        DocumentsContract.Document.COLUMN_LAST_MODIFIED,
                    ),
                    null, null, null,
                )?.use { c ->
                    while (c.moveToNext()) {
                        val docId = c.getString(0) ?: continue
                        val mime = c.getString(2) ?: continue
                        if (mime == DocumentsContract.Document.MIME_TYPE_DIR) {
                            pending += docId
                        } else if (mime.startsWith("audio/") || mime.startsWith("video/")) {
                            docs += TreeDoc(
                                DocumentsContract.buildDocumentUriUsingTree(treeUri, docId),
                                c.getString(1) ?: docId, mime,
                                if (c.isNull(3)) 0 else c.getLong(3),
                                if (c.isNull(4)) 0 else c.getLong(4),
                            )
                        }
                    }
                }
            }
        }
        val gate = Semaphore(4)
        docs.map { doc ->
            async(Dispatchers.IO) { gate.withPermit { describe(doc) } }
        }.awaitAll().sortedByDescending { it.dateModified }
    }

    private fun describe(doc: TreeDoc): MediaItem {
        val kind = if (doc.mime.startsWith("video/")) MediaKind.VIDEO else MediaKind.AUDIO
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(context, doc.uri)
            MediaItem(
                uri = doc.uri,
                displayName = doc.name,
                title = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE),
                artist = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST),
                album = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUM),
                durationMs = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0,
                sizeBytes = doc.size,
                kind = kind,
                dateModified = doc.modified,
            )
        } catch (_: Exception) {
            MediaItem(doc.uri, doc.name, null, null, null, 0, doc.size, kind, doc.modified)
        } finally {
            runCatching { retriever.release() }
        }
    }

    fun cachedThumbnail(uri: Uri): Bitmap? = thumbnails.get(uri)

    suspend fun thumbnail(uri: Uri): Bitmap? = withContext(Dispatchers.IO) {
        thumbnails.get(uri) ?: (
            runCatching { context.contentResolver.loadThumbnail(uri, Size(160, 160), null) }.getOrNull()
                // Folder-picker documents often have no provider thumbnail; extract one ourselves.
                ?: extractThumbnail(uri)
            )?.also { thumbnails.put(uri, it) }
    }

    private fun extractThumbnail(uri: Uri): Bitmap? {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(context, uri)
            retriever.embeddedPicture?.let { ImageUtils.decodeThumbnail(it, 160) }
                ?: retriever.getScaledFrameAtTime(1_000_000, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, 160, 160)
        } catch (_: Exception) {
            null
        } finally {
            runCatching { retriever.release() }
        }
    }

    fun cachedCoverKey(item: MediaItem): String? = synchronized(coverKeys) { coverKeys[cacheKey(item)] }

    /** A fingerprint of the embedded cover art, or [NO_COVER]. Two files share it only if their covers are identical. */
    suspend fun coverKey(item: MediaItem): String = withContext(Dispatchers.IO) {
        cachedCoverKey(item) ?: run {
            val retriever = MediaMetadataRetriever()
            val key = try {
                retriever.setDataSource(context, item.uri)
                retriever.embeddedPicture?.let { bytes ->
                    MessageDigest.getInstance("SHA-1").digest(bytes).joinToString("") { "%02x".format(it) }
                } ?: NO_COVER
            } catch (_: Exception) {
                NO_COVER
            } finally {
                runCatching { retriever.release() }
            }
            synchronized(coverKeys) { coverKeys[cacheKey(item)] = key }
            key
        }
    }

    private fun cacheKey(item: MediaItem) = "${item.uri}|${item.dateModified}|${item.sizeBytes}"

    fun invalidate(uris: Collection<Uri>) {
        uris.forEach { thumbnails.remove(it) }
        val prefixes = uris.map { "$it|" }
        synchronized(coverKeys) { coverKeys.keys.removeAll { key -> prefixes.any(key::startsWith) } }
    }

    companion object {
        const val NO_COVER = "none"
        private const val MAX_TREE_FILES = 5000
    }
}
