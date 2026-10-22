package com.example.metaedit.ui.editor

import android.app.Activity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.RadioButton
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.metaedit.R
import com.example.metaedit.data.ArtworkChange
import com.example.metaedit.data.ArtworkData
import com.example.metaedit.data.FieldInput
import com.example.metaedit.data.FieldSection
import com.example.metaedit.data.ImageUtils
import com.example.metaedit.data.LoadedMedia
import com.example.metaedit.data.MetaField
import com.example.metaedit.data.TagFormat
import com.example.metaedit.ui.formatDuration
import com.example.metaedit.ui.formatSize
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditorScreen(viewModel: EditorViewModel, onClose: (saved: Boolean) -> Unit) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    var savedSomething by rememberSaveable { mutableStateOf(false) }
    var confirmDiscard by remember { mutableStateOf(false) }

    val pickImage = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        uri?.let(viewModel::setCover)
    }
    val writeAccess = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) {
        viewModel.onWriteAccessResult(it.resultCode == Activity.RESULT_OK)
    }

    LaunchedEffect(viewModel) {
        viewModel.events.collect { event ->
            when (event) {
                EditorEvent.Saved -> savedSomething = true
                is EditorEvent.Message ->
                    snackbar.showSnackbar(event.text, withDismissAction = true, duration = SnackbarDuration.Long)
                is EditorEvent.RequestWriteAccess ->
                    writeAccess.launch(IntentSenderRequest.Builder(event.intentSender).build())
            }
        }
    }

    fun tryClose() {
        if (state.isDirty) confirmDiscard = true else onClose(savedSomething)
    }
    BackHandler(enabled = !state.saving) { tryClose() }

    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = {
                    IconButton(onClick = ::tryClose) { Icon(painterResource(R.drawable.ic_arrow_back), "Back") }
                },
                title = {
                    Column {
                        Text(if (state.isBatch) "Edit ${state.media.size} files" else "Edit metadata")
                        state.media.singleOrNull()?.let {
                            Text(
                                it.displayName,
                                style = MaterialTheme.typography.bodySmall,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                },
                actions = {
                    if (state.isDirty) {
                        IconButton(onClick = viewModel::revertAll) {
                            Icon(painterResource(R.drawable.ic_undo), "Undo all changes")
                        }
                    }
                    IconButton(onClick = viewModel::save, enabled = state.isDirty && !state.saving) {
                        Icon(painterResource(R.drawable.ic_save), "Save")
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        when {
            state.loading -> Box(Modifier.padding(padding).fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
            state.loadError != null -> Box(Modifier.padding(padding).fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
                Text(state.loadError!!, style = MaterialTheme.typography.bodyLarge)
            }
            else -> EditorContent(
                state = state,
                viewModel = viewModel,
                onPickCover = {
                    pickImage.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                },
                modifier = Modifier.padding(padding),
            )
        }
    }

    state.saveProgress?.let { (done, total) ->
        AlertDialog(
            onDismissRequest = {},
            confirmButton = {},
            title = { Text("Saving…") },
            text = {
                Column {
                    if (total > 1) Text("$done of $total files")
                    Spacer(Modifier.height(12.dp))
                    if (total > 1) LinearProgressIndicator(progress = { done / total.toFloat() }, Modifier.fillMaxWidth())
                    else LinearProgressIndicator(Modifier.fillMaxWidth())
                }
            },
        )
    }

    if (confirmDiscard) {
        AlertDialog(
            onDismissRequest = { confirmDiscard = false },
            title = { Text("Discard changes?") },
            text = { Text("Your edits haven't been saved.") },
            confirmButton = {
                TextButton(onClick = { confirmDiscard = false; onClose(savedSomething) }) { Text("Discard") }
            },
            dismissButton = {
                TextButton(onClick = { confirmDiscard = false }) { Text("Keep editing") }
            },
        )
    }
}

@Composable
private fun EditorContent(
    state: EditorState,
    viewModel: EditorViewModel,
    onPickCover: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val isVideo = state.media.any { it.info.hasVideo || it.mimeType?.startsWith("video/") == true }
    var showAll by rememberSaveable { mutableStateOf(false) }

    Column(
        modifier
            .fillMaxSize()
            .imePadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        CoverSection(state, isVideo, onPickCover, viewModel::removeCover, viewModel::keepCover, viewModel::useCoverForAll)

        if (state.isBatch) BatchSummary(state.media) else state.media.firstOrNull()?.let { FileInfoCard(it) }

        if (!state.canEdit) {
            ReadOnlyNotice()
        } else if (state.editableCount < state.media.size) {
            NoticeCard(
                "${state.media.size - state.editableCount} of the selected files are in a format that can't be " +
                    "edited and will be left unchanged.",
            )
        }

        if (!state.isBatch && state.canEdit) {
            AssistChip(
                onClick = viewModel::fillFromFileName,
                label = { Text("Fill from file name") },
                leadingIcon = { Icon(painterResource(R.drawable.ic_auto_fix), null, Modifier.size(18.dp)) },
            )
        }

        val readOnly = !state.canEdit
        val visibleFields = MetaField.entries.filter { field ->
            if (readOnly) state.fields[field]?.value?.isNotBlank() == true else field in state.supported
        }

        for (section in FieldSection.entries) {
            val sectionFields = visibleFields.filter { it.section == section }
            if (sectionFields.isEmpty()) continue
            val collapsible = section == FieldSection.CREDITS || section == FieldSection.OTHER
            val hasContent = sectionFields.any { state.fields[it]?.value?.isNotBlank() == true || state.fields[it]?.mixed == true }
            if (collapsible && !showAll && !hasContent) continue

            Text(
                section.title,
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(top = 8.dp),
            )
            if (section == FieldSection.TRACK) {
                sectionFields.chunked(2).forEach { pair ->
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        pair.forEach { field ->
                            FieldEditor(field, state.fields[field], readOnly, viewModel, Modifier.weight(1f))
                        }
                        if (pair.size == 1) Spacer(Modifier.weight(1f))
                    }
                }
            } else {
                sectionFields.forEach { field ->
                    FieldEditor(field, state.fields[field], readOnly, viewModel, Modifier.fillMaxWidth())
                }
            }
        }

        if (!readOnly && !showAll) {
            TextButton(onClick = { showAll = true }) { Text("Show all fields") }
        }
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun FieldEditor(
    field: MetaField,
    fieldState: FieldState?,
    readOnly: Boolean,
    viewModel: EditorViewModel,
    modifier: Modifier,
) {
    val fs = fieldState ?: return
    OutlinedTextField(
        value = fs.value,
        onValueChange = { viewModel.setField(field, it) },
        modifier = modifier,
        label = { Text(field.label) },
        placeholder = if (fs.mixed && !fs.touched) ({ Text("Multiple values") }) else null,
        supportingText = when {
            fs.mixed && !fs.touched -> ({
                Text("${fs.options.size} different values — pick one to use for all, type a new one, or leave as is")
            })
            fs.mixed && fs.value.isEmpty() -> ({ Text("Will be cleared on all files") })
            fs.mixed -> ({ Text("Will be set on all files") })
            else -> null
        },
        readOnly = readOnly,
        singleLine = field.input != FieldInput.MULTILINE,
        minLines = if (field.input == FieldInput.MULTILINE) 3 else 1,
        keyboardOptions = KeyboardOptions(
            keyboardType = if (field.input == FieldInput.NUMBER) KeyboardType.Number else KeyboardType.Text,
        ),
        trailingIcon = if (fs.dirty || (fs.options.isNotEmpty() && !readOnly)) ({
            Row {
                if (fs.dirty) {
                    IconButton(onClick = { viewModel.revertField(field) }) {
                        Icon(painterResource(R.drawable.ic_undo), "Undo ${field.label}")
                    }
                }
                if (fs.options.isNotEmpty() && !readOnly) {
                    ValuePicker(field, fs, onPick = { viewModel.setField(field, it) }, onKeepEach = { viewModel.revertField(field) })
                }
            }
        }) else null,
    )
}

/** Menu of the values already present across the selected files, to apply one to all of them. */
@Composable
private fun ValuePicker(field: MetaField, fs: FieldState, onPick: (String) -> Unit, onKeepEach: () -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { open = true }) {
            Icon(painterResource(R.drawable.ic_arrow_drop_down), "Choose an existing ${field.label.lowercase()}")
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            Text(
                "Use for all files",
                style = MaterialTheme.typography.labelMedium,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )
            fs.options.forEach { option ->
                val chosen = fs.touched && fs.value.trim() == option.value
                DropdownMenuItem(
                    text = {
                        Text(
                            option.value.ifEmpty { "(empty — clear it)" },
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    },
                    trailingIcon = {
                        Text("${option.count} file${if (option.count == 1) "" else "s"}", style = MaterialTheme.typography.labelSmall)
                    },
                    leadingIcon = { RadioButton(selected = chosen, onClick = null) },
                    onClick = { onPick(option.value); open = false },
                )
            }
            HorizontalDivider()
            DropdownMenuItem(
                text = { Text("Keep each file's own value") },
                leadingIcon = { RadioButton(selected = !fs.touched, onClick = null) },
                onClick = { onKeepEach(); open = false },
            )
        }
    }
}

@Composable
private fun CoverSection(
    state: EditorState,
    isVideo: Boolean,
    onPick: () -> Unit,
    onRemove: () -> Unit,
    onKeep: () -> Unit,
    onUseCover: (CoverOption) -> Unit,
) {
    val artwork = state.displayedArtwork
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            Modifier
                .size(220.dp)
                .clip(RoundedCornerShape(16.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant)
                .clickable(enabled = state.canEdit, onClick = onPick),
            contentAlignment = Alignment.Center,
        ) {
            val bitmap = rememberArtworkBitmap(artwork)
            if (bitmap != null) {
                Image(bitmap, contentDescription = "Cover art", contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            } else {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(
                        painterResource(R.drawable.ic_image),
                        contentDescription = null,
                        modifier = Modifier.size(48.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        if (isVideo) "No poster art" else "No cover art",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        val caption = when {
            state.artworkChange is ArtworkChange.Replace -> "New cover — will be saved${if (state.isBatch) " to all files" else ""}"
            state.artworkChange == ArtworkChange.Remove -> "Cover will be removed"
            state.artworkMixed -> "Files have different covers — pick one below or add a new one"
            artwork != null -> rememberArtworkCaption(artwork)
            else -> null
        }
        caption?.let {
            Text(it, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 6.dp))
        }
        if (state.canEdit) {
            Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilledTonalButton(onClick = onPick) {
                    Icon(painterResource(R.drawable.ic_image), null, Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(if (artwork == null) "Add cover" else "Change")
                }
                if (artwork != null) {
                    OutlinedButton(onClick = onRemove) {
                        Icon(painterResource(R.drawable.ic_delete), null, Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("Remove")
                    }
                }
                if (state.artworkChange != ArtworkChange.Keep) {
                    TextButton(onClick = onKeep) { Text(if (state.artworkMixed) "Keep each" else "Undo") }
                }
            }
            if (state.coverOptions.isNotEmpty()) {
                Text(
                    "Covers in this selection — tap one to use it for all files",
                    style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier.padding(top = 12.dp, bottom = 6.dp),
                )
                LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    items(state.coverOptions) { option ->
                        val change = state.artworkChange
                        val selected = (change is ArtworkChange.Replace && change.artwork == option.artwork) ||
                            (change == ArtworkChange.Remove && option.artwork == null)
                        CoverChoice(option, selected, isVideo) { onUseCover(option) }
                    }
                }
            }
        }
    }
}

@Composable
private fun CoverChoice(option: CoverOption, selected: Boolean, isVideo: Boolean, onClick: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        val shape = RoundedCornerShape(10.dp)
        Box(
            Modifier
                .size(76.dp)
                .clip(shape)
                .background(MaterialTheme.colorScheme.surfaceVariant)
                .border(
                    width = if (selected) 3.dp else 0.dp,
                    color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant,
                    shape = shape,
                )
                .clickable(onClick = onClick),
            contentAlignment = Alignment.Center,
        ) {
            val bitmap = rememberArtworkBitmap(option.artwork)
            if (bitmap != null) {
                Image(bitmap, contentDescription = "Cover used by ${option.count} files", contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize())
            } else {
                Text(
                    if (isVideo) "No poster" else "No cover",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (selected) {
                Icon(
                    painterResource(R.drawable.ic_check),
                    contentDescription = "Selected",
                    tint = MaterialTheme.colorScheme.onPrimary,
                    modifier = Modifier.align(Alignment.TopEnd).padding(4.dp)
                        .background(MaterialTheme.colorScheme.primary, RoundedCornerShape(50)).size(18.dp),
                )
            }
        }
        Text(
            "${option.count} file${if (option.count == 1) "" else "s"}",
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.padding(top = 2.dp),
        )
    }
}

@Composable
private fun rememberArtworkBitmap(artwork: ArtworkData?): ImageBitmap? {
    val bitmap by produceState<ImageBitmap?>(null, artwork) {
        value = artwork?.let {
            withContext(Dispatchers.Default) { ImageUtils.decodeThumbnail(it.bytes, 660)?.asImageBitmap() }
        }
    }
    return bitmap
}

@Composable
private fun rememberArtworkCaption(artwork: ArtworkData): String = remember(artwork) {
    val options = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
    android.graphics.BitmapFactory.decodeByteArray(artwork.bytes, 0, artwork.bytes.size, options)
    val type = artwork.mimeType.substringAfter('/').uppercase(Locale.US)
    "${options.outWidth}×${options.outHeight} $type · ${formatSize(artwork.bytes.size.toLong())}"
}

@Composable
private fun FileInfoCard(media: LoadedMedia) {
    val info = media.info
    val rows = buildList {
        add("Format" to (media.format?.label ?: media.mimeType ?: "Unknown"))
        formatSize(media.sizeBytes).takeIf { it.isNotEmpty() }?.let { add("Size" to it) }
        formatDuration(info.durationMs).takeIf { it.isNotEmpty() }?.let { add("Duration" to it) }
        if (info.width != null && info.height != null) add("Resolution" to "${info.width}×${info.height}")
        info.bitrate?.let { add("Bitrate" to "${it / 1000} kbps") }
        info.sampleRate?.let { add("Sample rate" to String.format(Locale.US, "%.1f kHz", it / 1000.0)) }
    }
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            rows.forEach { (label, value) ->
                Row {
                    Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.width(110.dp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(value, style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
    }
}

@Composable
private fun BatchSummary(media: List<LoadedMedia>) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("Only fields you change are written; everything else stays as it is in each file. " +
                "Where files disagree, use the ▾ menu to pick one existing value for all of them, or type a new one.",
                style = MaterialTheme.typography.bodyMedium)
            HorizontalDivider(Modifier.padding(vertical = 6.dp))
            media.take(6).forEach {
                Text("• ${it.displayName}", style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            if (media.size > 6) Text("…and ${media.size - 6} more", style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun ReadOnlyNotice() {
    val formats = TagFormat.entries.joinToString { it.ext.uppercase(Locale.US) }
    NoticeCard("Tags in this format can be viewed but not edited. Editable formats: $formats (plus M4V, M4B, AIFF, OGA, 3G2).")
}

@Composable
private fun NoticeCard(text: String) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer)) {
        Text(text, Modifier.padding(16.dp), style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onTertiaryContainer)
    }
}
