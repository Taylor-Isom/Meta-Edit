package com.example.metaedit.ui.library

import android.Manifest
import android.content.pm.PackageManager
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.metaedit.R
import com.example.metaedit.data.MediaItem
import com.example.metaedit.data.GroupBy
import com.example.metaedit.data.MediaKind
import com.example.metaedit.data.MediaSource
import com.example.metaedit.data.MediaLibrary
import com.example.metaedit.ui.formatDuration

private fun permissionsFor(kind: MediaKind) = when (kind) {
    MediaKind.AUDIO -> listOf(Manifest.permission.READ_MEDIA_AUDIO)
    MediaKind.VIDEO -> listOf(
        Manifest.permission.READ_MEDIA_VIDEO,
        Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED,
    )
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun LibraryScreen(
    viewModel: LibraryViewModel,
    onOpenFiles: () -> Unit,
    onEdit: (List<Uri>) -> Unit,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current

    fun hasAccess(kind: MediaKind) = permissionsFor(kind).any {
        ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
    }

    var access by remember { mutableStateOf(MediaKind.entries.associateWith(::hasAccess)) }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) {
        access = MediaKind.entries.associateWith(::hasAccess)
        viewModel.refresh()
    }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        access = MediaKind.entries.associateWith(::hasAccess)
        viewModel.refresh()
    }

    var showFolders by remember { mutableStateOf(false) }
    val pickTree = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            viewModel.setSource(viewModel.treeSource(uri))
            showFolders = false
        }
    }

    val selecting = state.selection.isNotEmpty()
    // A picked folder is read directly, so it doesn't need the media permissions.
    val needsPermission = state.source !is MediaSource.Tree && access[state.kind] != true

    Scaffold(
        topBar = {
            if (selecting) {
                TopAppBar(
                    navigationIcon = {
                        IconButton(onClick = viewModel::clearSelection) {
                            Icon(painterResource(R.drawable.ic_close), "Clear selection")
                        }
                    },
                    title = { Text("${state.selection.size} selected") },
                    actions = {
                        TextButton(onClick = viewModel::selectAllVisible) { Text("Select all") }
                        IconButton(onClick = { onEdit(state.selection.toList()) }) {
                            Icon(painterResource(R.drawable.ic_edit), "Edit selected")
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.secondaryContainer,
                    ),
                )
            } else {
                TopAppBar(
                    title = { Text("MetaEdit") },
                    actions = {
                        IconButton(onClick = viewModel::refresh) {
                            Icon(painterResource(R.drawable.ic_refresh), "Refresh")
                        }
                    },
                )
            }
        },
        floatingActionButton = {
            if (selecting) {
                ExtendedFloatingActionButton(
                    onClick = { onEdit(state.selection.toList()) },
                    icon = { Icon(painterResource(R.drawable.ic_edit), null) },
                    text = { Text("Edit ${state.selection.size}") },
                )
            } else {
                ExtendedFloatingActionButton(
                    onClick = onOpenFiles,
                    icon = { Icon(painterResource(R.drawable.ic_folder_open), null) },
                    text = { Text("Open files") },
                )
            }
        },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            PrimaryTabRow(selectedTabIndex = state.kind.ordinal) {
                Tab(
                    selected = state.kind == MediaKind.AUDIO,
                    onClick = { viewModel.setKind(MediaKind.AUDIO) },
                    text = { Text("Music") },
                    icon = { Icon(painterResource(R.drawable.ic_music_note), null) },
                )
                Tab(
                    selected = state.kind == MediaKind.VIDEO,
                    onClick = { viewModel.setKind(MediaKind.VIDEO) },
                    text = { Text("Videos") },
                    icon = { Icon(painterResource(R.drawable.ic_movie), null) },
                )
            }

            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                SourceChip(state.source) {
                    if (access.values.any { it }) viewModel.loadFolders()
                    showFolders = true
                }
                SortChip(state.sort, viewModel::setSort)
                GroupChip(state.groupBy, viewModel::setGroupBy)
            }

            if (needsPermission) {
                PermissionPrompt(
                    kind = state.kind,
                    onGrant = { permissionLauncher.launch(permissionsFor(state.kind).toTypedArray()) },
                    onOpenFiles = onOpenFiles,
                    onPickFolder = { pickTree.launch(null) },
                )
                return@Column
            }

            OutlinedTextField(
                value = state.query,
                onValueChange = viewModel::setQuery,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                placeholder = { Text("Search title, artist, album…", maxLines = 1) },
                leadingIcon = { Icon(painterResource(R.drawable.ic_search), null) },
                trailingIcon = {
                    if (state.query.isNotEmpty()) {
                        IconButton(onClick = { viewModel.setQuery("") }) {
                            Icon(painterResource(R.drawable.ic_close), "Clear search")
                        }
                    }
                },
                singleLine = true,
            )
            if (state.loading) LinearProgressIndicator(Modifier.fillMaxWidth())
            if (state.coversPending > 0) {
                Text(
                    "Comparing cover art… ${state.coversPending} left",
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                )
                LinearProgressIndicator(Modifier.fillMaxWidth().padding(horizontal = 16.dp))
            }

            if (state.filtered.isEmpty() && !state.loading) {
                EmptyState(
                    when {
                        state.query.isNotBlank() -> "No matches for \"${state.query}\""
                        state.source != MediaSource.All ->
                            "No ${if (state.kind == MediaKind.AUDIO) "music" else "videos"} in ${state.source.label()}"
                        state.kind == MediaKind.AUDIO -> "No music found on this device"
                        else -> "No videos found on this device"
                    },
                )
            } else {
                val grouped = state.groupBy != GroupBy.NONE
                LazyColumn(contentPadding = PaddingValues(bottom = 96.dp)) {
                    for (group in state.groups) {
                        if (grouped) {
                            stickyHeader(key = "group:${group.key}") {
                                GroupHeader(
                                    group = group,
                                    groupBy = state.groupBy,
                                    selectedCount = group.items.count { it.uri in state.selection },
                                    thumbnail = { Thumbnail(group.items.first(), viewModel.library, 40.dp) },
                                    onToggleSelect = { viewModel.toggleGroup(group) },
                                    onEdit = { onEdit(group.items.map { it.uri }) },
                                )
                            }
                        }
                        items(group.items, key = { it.uri.toString() }) { item ->
                            MediaRow(
                                item = item,
                                library = viewModel.library,
                                selected = item.uri in state.selection,
                                onClick = {
                                    if (selecting) viewModel.toggleSelection(item.uri) else onEdit(listOf(item.uri))
                                },
                                onLongClick = { viewModel.toggleSelection(item.uri) },
                            )
                        }
                    }
                }
            }
        }
    }

    if (showFolders) {
        FolderDialog(
            current = state.source,
            folders = state.folders,
            hasMediaAccess = access.values.any { it },
            onSelect = { source ->
                viewModel.setSource(source)
                showFolders = false
            },
            onBrowse = { pickTree.launch(null) },
            onDismiss = { showFolders = false },
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun MediaRow(
    item: MediaItem,
    library: MediaLibrary,
    selected: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    val subtitle = listOfNotNull(item.artist, item.album?.takeIf { item.kind == MediaKind.AUDIO })
        .filter { it.isNotBlank() }
        .joinToString(" • ")
        .ifEmpty { item.displayName }
    ListItem(
        modifier = Modifier.combinedClickable(onClick = onClick, onLongClick = onLongClick),
        colors = ListItemDefaults.colors(
            containerColor = if (selected) MaterialTheme.colorScheme.secondaryContainer
            else MaterialTheme.colorScheme.surface,
        ),
        leadingContent = {
            Box(contentAlignment = Alignment.Center) {
                Thumbnail(item, library)
                if (selected) {
                    Box(
                        Modifier.size(52.dp).clip(RoundedCornerShape(8.dp))
                            .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.7f)),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(painterResource(R.drawable.ic_check), "Selected", tint = MaterialTheme.colorScheme.onPrimary)
                    }
                }
            }
        },
        headlineContent = {
            Text(item.title?.takeIf { it.isNotBlank() } ?: item.displayName, maxLines = 1, overflow = TextOverflow.Ellipsis)
        },
        supportingContent = { Text(subtitle, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        trailingContent = { Text(formatDuration(item.durationMs), style = MaterialTheme.typography.labelMedium) },
    )
}

@Composable
private fun Thumbnail(item: MediaItem, library: MediaLibrary, size: Dp = 52.dp) {
    val bitmap by produceState(library.cachedThumbnail(item.uri), item.uri) {
        value = library.thumbnail(item.uri)
    }
    val shape = RoundedCornerShape(8.dp)
    val current = bitmap
    if (current != null) {
        Image(
            bitmap = current.asImageBitmap(),
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.size(size).clip(shape),
        )
    } else {
        Box(
            Modifier.size(size).clip(shape).background(MaterialTheme.colorScheme.surfaceVariant),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                painterResource(if (item.kind == MediaKind.AUDIO) R.drawable.ic_music_note else R.drawable.ic_movie),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun PermissionPrompt(kind: MediaKind, onGrant: () -> Unit, onOpenFiles: () -> Unit, onPickFolder: () -> Unit) {
    Column(
        Modifier.fillMaxSize().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(
            painterResource(if (kind == MediaKind.AUDIO) R.drawable.ic_music_note else R.drawable.ic_movie),
            contentDescription = null,
            modifier = Modifier.size(48.dp),
            tint = MaterialTheme.colorScheme.primary,
        )
        Spacer(Modifier.height(16.dp))
        Text(
            "Allow access to your ${if (kind == MediaKind.AUDIO) "music" else "videos"} to browse and edit them here.",
            textAlign = TextAlign.Center,
            style = MaterialTheme.typography.bodyLarge,
        )
        Spacer(Modifier.height(16.dp))
        Button(onClick = onGrant) { Text("Allow access") }
        TextButton(onClick = onPickFolder) { Text("Or choose a folder to show") }
        TextButton(onClick = onOpenFiles) { Text("Or pick individual files") }
    }
}

@Composable
private fun EmptyState(message: String) {
    Box(Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
        Text(message, style = MaterialTheme.typography.bodyLarge, textAlign = TextAlign.Center)
    }
}
