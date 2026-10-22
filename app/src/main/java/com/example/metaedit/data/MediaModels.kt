package com.example.metaedit.data

import android.net.Uri

enum class MediaKind { AUDIO, VIDEO }

/** The properties the library sorts and groups by. */
interface Sortable {
    val displayName: String
    val title: String?
    val artist: String?
    val album: String?
    val dateModified: Long
}

/** A row in the library list, sourced from MediaStore or a folder picked with the file picker. */
data class MediaItem(
    val uri: Uri,
    override val displayName: String,
    override val title: String?,
    override val artist: String?,
    override val album: String?,
    val durationMs: Long,
    val sizeBytes: Long,
    val kind: MediaKind,
    /** Epoch millis; used for ordering and to know when cached cover hashes are stale. */
    override val dateModified: Long = 0,
) : Sortable

/** Where the library lists media from. */
sealed interface MediaSource {
    /** Everything MediaStore knows about. */
    data object All : MediaSource

    /** A MediaStore folder (and its subfolders), e.g. volume "external_primary", path "Music/Albums/". */
    data class Folder(val volume: String, val relativePath: String) : MediaSource

    /** Any folder the user granted through the system folder picker, read directly. */
    data class Tree(val treeUri: Uri, val name: String) : MediaSource
}

/** A MediaStore folder that contains media, for the folder chooser. */
data class MediaFolder(val volume: String, val relativePath: String, val count: Int)

class ArtworkData(val bytes: ByteArray, val mimeType: String) {
    override fun equals(other: Any?) =
        other is ArtworkData && mimeType == other.mimeType && bytes.contentEquals(other.bytes)

    override fun hashCode() = 31 * bytes.contentHashCode() + mimeType.hashCode()
}

sealed interface ArtworkChange {
    data object Keep : ArtworkChange
    data object Remove : ArtworkChange
    data class Replace(val artwork: ArtworkData) : ArtworkChange
}

/** Only the fields present in [fields] are touched; a blank value deletes the field. */
data class TagEdits(
    val fields: Map<MetaField, String>,
    val artwork: ArtworkChange,
) {
    val isEmpty get() = fields.isEmpty() && artwork == ArtworkChange.Keep
}

/** Tags as read from a file. Fields absent from [supported] cannot be stored by that format. */
data class TagSnapshot(
    val values: Map<MetaField, String>,
    val supported: Set<MetaField>,
    val artwork: ArtworkData?,
)

data class TechInfo(
    val durationMs: Long? = null,
    val bitrate: Int? = null,
    val sampleRate: Int? = null,
    val width: Int? = null,
    val height: Int? = null,
    val hasVideo: Boolean = false,
)

data class LoadedMedia(
    val uri: Uri,
    val displayName: String,
    val sizeBytes: Long?,
    val mimeType: String?,
    /** Null when the container can't be written by this app. */
    val format: TagFormat?,
    val tags: TagSnapshot,
    val info: TechInfo,
) {
    val editable get() = format != null
}

class SaveReport(
    /** Fields the format rejected (unsupported key, or invalid value such as a non-number track). */
    val skipped: Map<MetaField, String>,
    val artworkError: String? = null,
) {
    val isClean get() = skipped.isEmpty() && artworkError == null
}
