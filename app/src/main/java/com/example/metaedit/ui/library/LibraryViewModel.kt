package com.example.metaedit.ui.library

import android.app.Application
import android.content.Intent
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.metaedit.MetaEditApp
import com.example.metaedit.data.GroupBy
import com.example.metaedit.data.LibraryOrganizer
import com.example.metaedit.data.MediaFolder
import com.example.metaedit.data.MediaGroup
import com.example.metaedit.data.MediaItem
import com.example.metaedit.data.MediaKind
import com.example.metaedit.data.MediaSource
import com.example.metaedit.data.SortField
import com.example.metaedit.data.SortOrder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

data class LibraryState(
    val kind: MediaKind = MediaKind.AUDIO,
    val source: MediaSource = MediaSource.All,
    val items: Map<MediaKind, List<MediaItem>> = emptyMap(),
    val loading: Boolean = false,
    val query: String = "",
    val sort: SortOrder = SortOrder(),
    val groupBy: GroupBy = GroupBy.NONE,
    val selection: Set<Uri> = emptySet(),
    /** Cover fingerprints found so far; grouping by cover fills this in the background. */
    val coverKeys: Map<Uri, String> = emptyMap(),
    val folders: List<MediaFolder>? = null,
) {
    val filtered: List<MediaItem> by lazy {
        val all = items[kind].orEmpty()
        val q = query.trim()
        val matching = if (q.isEmpty()) all else all.filter { item ->
            listOfNotNull(item.title, item.artist, item.album, item.displayName).any { it.contains(q, ignoreCase = true) }
        }
        LibraryOrganizer.sort(matching, sort)
    }

    val groups: List<MediaGroup<MediaItem>> by lazy { LibraryOrganizer.group(filtered, groupBy) { coverKeys[it.uri] } }

    /** Number of items still waiting for a cover fingerprint while grouping by cover. */
    val coversPending: Int get() = if (groupBy != GroupBy.COVER) 0 else filtered.count { it.uri !in coverKeys }
}

class LibraryViewModel(app: Application) : AndroidViewModel(app) {
    private val metaEdit = app as MetaEditApp
    val library = metaEdit.library
    private val prefs = metaEdit.libraryPrefs

    private val _state = MutableStateFlow(
        LibraryState(source = restoredSource(), sort = prefs.sort, groupBy = prefs.groupBy),
    )

    /** The saved folder, unless it was a picked folder whose access has since been revoked. */
    private fun restoredSource(): MediaSource {
        val saved = prefs.source
        if (saved !is MediaSource.Tree) return saved
        val granted = getApplication<Application>().contentResolver.persistedUriPermissions
            .any { it.uri == saved.treeUri && it.isReadPermission }
        return if (granted) saved else MediaSource.All
    }
    val state: StateFlow<LibraryState> = _state.asStateFlow()

    private var loadJob: Job? = null
    private var coverJob: Job? = null

    fun refresh() {
        loadJob?.cancel()
        loadJob = viewModelScope.launch {
            _state.update { it.copy(loading = true) }
            val items = runCatching { library.query(_state.value.source) }.getOrDefault(emptyMap())
            val known = items.values.flatten().mapTo(HashSet()) { it.uri }
            _state.update {
                it.copy(
                    loading = false,
                    items = items,
                    selection = it.selection.filterTo(mutableSetOf()) { uri -> uri in known },
                    coverKeys = it.coverKeys.filterKeys { uri -> uri in known },
                )
            }
            computeCoversIfNeeded()
        }
    }

    fun setKind(kind: MediaKind) {
        _state.update { it.copy(kind = kind, selection = emptySet()) }
        computeCoversIfNeeded()
    }

    fun setQuery(query: String) = _state.update { it.copy(query = query) }

    fun setSort(field: SortField) = _state.update { s ->
        // Picking the active field again flips the direction; a new field starts in its natural order.
        val order = if (s.sort.field == field) s.sort.copy(ascending = !s.sort.ascending)
        else SortOrder(field, ascending = field != SortField.DATE_MODIFIED)
        prefs.sort = order
        s.copy(sort = order)
    }

    fun setGroupBy(groupBy: GroupBy) {
        prefs.groupBy = groupBy
        _state.update { it.copy(groupBy = groupBy) }
        computeCoversIfNeeded()
    }

    fun setSource(source: MediaSource) {
        if (source is MediaSource.Tree) {
            // Keep access to the folder after restarts; write access lets saves skip consent prompts.
            val resolver = getApplication<Application>().contentResolver
            runCatching {
                resolver.takePersistableUriPermission(
                    source.treeUri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
                )
            }.recoverCatching {
                resolver.takePersistableUriPermission(source.treeUri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
        }
        prefs.source = source
        _state.update { it.copy(source = source, items = emptyMap(), selection = emptySet()) }
        refresh()
    }

    fun treeSource(treeUri: Uri) = MediaSource.Tree(treeUri, library.treeName(treeUri))

    fun loadFolders() {
        viewModelScope.launch {
            val folders = runCatching { library.folders() }.getOrDefault(emptyList())
            _state.update { it.copy(folders = folders) }
        }
    }

    fun toggleSelection(uri: Uri) = _state.update {
        it.copy(selection = if (uri in it.selection) it.selection - uri else it.selection + uri)
    }

    /** Selects the whole group, or deselects it when it's already fully selected. */
    fun toggleGroup(group: MediaGroup<MediaItem>) = _state.update { s ->
        val uris = group.items.map { it.uri }
        s.copy(selection = if (s.selection.containsAll(uris)) s.selection - uris.toSet() else s.selection + uris)
    }

    fun selectAllVisible() = _state.update { s -> s.copy(selection = s.filtered.mapTo(mutableSetOf()) { it.uri }) }

    fun clearSelection() = _state.update { it.copy(selection = emptySet()) }

    /** Called when returning from the editor so covers, titles and groups reflect the new tags. */
    fun onEdited(uris: Collection<Uri>) {
        library.invalidate(uris)
        _state.update { s -> s.copy(selection = emptySet(), coverKeys = s.coverKeys - uris.toSet()) }
        refresh()
    }

    private fun computeCoversIfNeeded() {
        val s = _state.value
        if (s.groupBy != GroupBy.COVER) return
        val missing = s.items[s.kind].orEmpty().filter { it.uri !in s.coverKeys }
        if (missing.isEmpty()) return
        coverJob?.cancel()
        coverJob = viewModelScope.launch(Dispatchers.IO) {
            val gate = Semaphore(4)
            // Publish in batches so the list fills in without recomposing per file.
            missing.chunked(24).forEach { batch ->
                val found = batch.map { item ->
                    async { gate.withPermit { item.uri to library.coverKey(item) } }
                }.awaitAll()
                _state.update { it.copy(coverKeys = it.coverKeys + found) }
            }
        }
    }
}
