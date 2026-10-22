package com.example.metaedit.ui.editor

import android.app.Application
import android.content.IntentSender
import android.net.Uri
import android.provider.MediaStore
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.metaedit.MetaEditApp
import com.example.metaedit.data.ArtworkChange
import com.example.metaedit.data.ArtworkData
import com.example.metaedit.data.FilenameParser
import com.example.metaedit.data.ImageUtils
import com.example.metaedit.data.LoadedMedia
import com.example.metaedit.data.MetaField
import com.example.metaedit.data.MetadataRepository
import com.example.metaedit.data.TagEdits
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** A value found in some of the files being edited together. */
data class ValueOption(val value: String, val count: Int)

/** A distinct cover found in the files being edited together; null artwork means "no cover". */
data class CoverOption(val artwork: ArtworkData?, val count: Int)

data class FieldState(
    val value: String,
    val original: String,
    /** Batch edit where the files disagree; untouched means "leave each file as it is". */
    val mixed: Boolean = false,
    val touched: Boolean = false,
    /** For mixed fields: the existing values, most common first, so one can be applied to all. */
    val options: List<ValueOption> = emptyList(),
) {
    val dirty get() = if (mixed) touched else value != original
}

data class EditorState(
    val loading: Boolean = true,
    val loadError: String? = null,
    val media: List<LoadedMedia> = emptyList(),
    val fields: Map<MetaField, FieldState> = emptyMap(),
    val supported: Set<MetaField> = emptySet(),
    val originalArtwork: ArtworkData? = null,
    val artworkMixed: Boolean = false,
    val coverOptions: List<CoverOption> = emptyList(),
    val artworkChange: ArtworkChange = ArtworkChange.Keep,
    val saving: Boolean = false,
    val saveProgress: Pair<Int, Int>? = null,
) {
    val isBatch get() = media.size > 1
    val editableCount get() = media.count { it.editable }
    val canEdit get() = editableCount > 0
    val isDirty get() = artworkChange != ArtworkChange.Keep || fields.values.any { it.dirty }

    /** The cover to show: pending change first, then what's in the file(s). */
    val displayedArtwork: ArtworkData?
        get() = when (val change = artworkChange) {
            is ArtworkChange.Replace -> change.artwork
            ArtworkChange.Remove -> null
            ArtworkChange.Keep -> originalArtwork
        }
}

sealed interface EditorEvent {
    data class Message(val text: String) : EditorEvent
    data object Saved : EditorEvent
    data class RequestWriteAccess(val intentSender: IntentSender) : EditorEvent
}

class EditorViewModel(app: Application, private val uris: List<Uri>) : AndroidViewModel(app) {
    private val repository: MetadataRepository = (app as MetaEditApp).repository

    private val _state = MutableStateFlow(EditorState())
    val state: StateFlow<EditorState> = _state.asStateFlow()

    private val _events = Channel<EditorEvent>(Channel.BUFFERED)
    val events = _events.receiveAsFlow()

    /** Where each editable file will be written, resolved when the user taps save. */
    private var pendingTargets: List<Pair<LoadedMedia, Uri>> = emptyList()

    init {
        load()
    }

    fun load() {
        viewModelScope.launch {
            _state.update { it.copy(loading = true, loadError = null) }
            val loaded = uris.mapNotNull { uri -> runCatching { repository.load(uri) }.getOrNull() }
            if (loaded.isEmpty()) {
                _state.update { it.copy(loading = false, loadError = "Couldn't open the selected file.") }
                return@launch
            }
            _state.value = buildState(loaded)
        }
    }

    private fun buildState(media: List<LoadedMedia>): EditorState {
        val editable = media.filter { it.editable }.ifEmpty { media }
        val fields = MetaField.entries.associateWith { field ->
            val counts = editable.map { it.tags.values[field].orEmpty().trim() }.groupingBy { it }.eachCount()
            if (counts.size == 1) {
                val only = editable.first().tags.values[field].orEmpty()
                FieldState(only, only)
            } else {
                val options = counts.entries
                    .sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key.isEmpty() })
                    .map { ValueOption(it.key, it.value) }
                FieldState("", "", mixed = true, options = options)
            }
        }
        val supported = media.filter { it.editable }.flatMapTo(mutableSetOf()) { it.tags.supported }
        val coverCounts = editable.map { it.tags.artwork }.groupingBy { it }.eachCount()
        val covers = coverCounts.keys.toList()
        return EditorState(
            loading = false,
            media = media,
            fields = fields,
            supported = supported,
            originalArtwork = covers.singleOrNull() ?: editable.firstNotNullOfOrNull { it.tags.artwork },
            artworkMixed = covers.size > 1,
            coverOptions = if (covers.size > 1) {
                coverCounts.entries.sortedByDescending { it.value }.map { CoverOption(it.key, it.value) }
            } else {
                emptyList()
            },
        )
    }

    fun setField(field: MetaField, value: String) = _state.update { s ->
        val current = s.fields[field] ?: return@update s
        s.copy(fields = s.fields + (field to current.copy(value = value, touched = true)))
    }

    fun revertField(field: MetaField) = _state.update { s ->
        val current = s.fields[field] ?: return@update s
        s.copy(fields = s.fields + (field to current.copy(value = current.original, touched = false)))
    }

    fun revertAll() = _state.update { s ->
        s.copy(
            fields = s.fields.mapValues { (_, f) -> f.copy(value = f.original, touched = false) },
            artworkChange = ArtworkChange.Keep,
        )
    }

    fun fillFromFileName() {
        val media = _state.value.media.singleOrNull() ?: return
        val guesses = FilenameParser.parse(media.displayName)
        if (guesses.isEmpty()) {
            _events.trySend(EditorEvent.Message("Couldn't find anything useful in the file name"))
            return
        }
        guesses.forEach { (field, value) -> setField(field, value) }
        _events.trySend(EditorEvent.Message("Filled ${guesses.keys.joinToString { it.label.lowercase() }} from file name"))
    }

    fun setCover(imageUri: Uri) {
        viewModelScope.launch {
            val artwork = withContext(Dispatchers.IO) {
                runCatching {
                    val bytes = getApplication<Application>().contentResolver.openInputStream(imageUri)
                        ?.use { it.readBytes() } ?: error("empty")
                    ImageUtils.prepareCover(bytes)
                }.getOrNull()
            }
            if (artwork == null) {
                _events.send(EditorEvent.Message("That image couldn't be read"))
            } else {
                _state.update { it.copy(artworkChange = ArtworkChange.Replace(artwork)) }
            }
        }
    }

    /** Applies one of the covers already in the selection to every file. */
    fun useCoverForAll(option: CoverOption) = _state.update {
        it.copy(artworkChange = option.artwork?.let(ArtworkChange::Replace) ?: ArtworkChange.Remove)
    }

    fun removeCover() = _state.update { it.copy(artworkChange = ArtworkChange.Remove) }

    fun keepCover() = _state.update { it.copy(artworkChange = ArtworkChange.Keep) }

    fun save() {
        val s = _state.value
        if (!s.isDirty || s.saving) return
        val targets = s.media.filter { it.editable }.map { media ->
            val writable = if (repository.hasWriteAccess(media.uri)) media.uri
            else repository.mediaStoreUriFor(media.uri) ?: media.uri
            media to writable
        }
        pendingTargets = targets
        val needConsent = targets.map { it.second }.filter {
            MetadataRepository.isMediaStoreUri(it) && !repository.hasWriteAccess(it)
        }
        if (needConsent.isNotEmpty()) {
            val request = MediaStore.createWriteRequest(getApplication<Application>().contentResolver, needConsent)
            _events.trySend(EditorEvent.RequestWriteAccess(request.intentSender))
        } else {
            performSave()
        }
    }

    fun onWriteAccessResult(granted: Boolean) {
        if (granted) performSave()
        else _events.trySend(EditorEvent.Message("Write access was denied, nothing was changed"))
    }

    private fun performSave() {
        val s = _state.value
        val edits = TagEdits(
            fields = s.fields.filterValues { it.dirty }.mapValues { it.value.value },
            artwork = s.artworkChange,
        )
        val targets = pendingTargets
        if (edits.isEmpty || targets.isEmpty()) return

        viewModelScope.launch {
            _state.update { it.copy(saving = true, saveProgress = 0 to targets.size) }
            val failures = mutableListOf<String>()
            val skipped = mutableSetOf<String>()
            targets.forEachIndexed { index, (media, target) ->
                try {
                    val report = repository.save(target, media.format!!, edits)
                    report.skipped.forEach { (field, reason) -> skipped += "${field.label} ($reason)" }
                    report.artworkError?.let { skipped += "Cover art ($it)" }
                } catch (_: SecurityException) {
                    failures += "${media.displayName}: no permission to modify it (try opening it with \"Open files\")"
                } catch (e: Exception) {
                    failures += "${media.displayName}: ${e.message ?: e::class.simpleName}"
                }
                _state.update { it.copy(saveProgress = index + 1 to targets.size) }
            }
            val saved = targets.size - failures.size
            val message = buildString {
                append(
                    when {
                        failures.isEmpty() && targets.size == 1 -> "Saved"
                        failures.isEmpty() -> "Saved $saved files"
                        saved == 0 -> "Save failed — ${failures.first()}"
                        else -> "Saved $saved of ${targets.size} files. Failed: ${failures.joinToString()}"
                    },
                )
                if (skipped.isNotEmpty() && saved > 0) append(". Not supported by this format: ${skipped.joinToString()}")
            }
            if (saved > 0) _events.send(EditorEvent.Saved)
            _events.send(EditorEvent.Message(message))
            _state.update { it.copy(saving = false, saveProgress = null) }
            if (saved > 0) load()
        }
    }
}
