package com.example.metaedit.ui.library

import android.provider.MediaStore
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TriStateCheckbox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.metaedit.R
import com.example.metaedit.data.GroupBy
import com.example.metaedit.data.MediaFolder
import com.example.metaedit.data.MediaGroup
import com.example.metaedit.data.MediaSource
import com.example.metaedit.data.SortField
import com.example.metaedit.data.SortOrder

fun MediaSource.label(): String = when (this) {
    MediaSource.All -> "All folders"
    is MediaSource.Folder -> folderLabel(volume, relativePath)
    is MediaSource.Tree -> name
}

private fun folderLabel(volume: String, relativePath: String): String {
    val path = relativePath.trimEnd('/')
    return if (volume == MediaStore.VOLUME_EXTERNAL_PRIMARY) path else "$path ($volume)"
}

@Composable
fun SourceChip(source: MediaSource, onClick: () -> Unit) {
    AssistChip(
        onClick = onClick,
        label = { Text(source.label(), maxLines = 1, overflow = TextOverflow.Ellipsis) },
        leadingIcon = { Icon(painterResource(R.drawable.ic_folder), null, Modifier.size(18.dp)) },
        trailingIcon = { Icon(painterResource(R.drawable.ic_arrow_drop_down), null, Modifier.size(18.dp)) },
    )
}

@Composable
fun SortChip(order: SortOrder, onSelect: (SortField) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        AssistChip(
            onClick = { open = true },
            label = { Text(order.field.label) },
            leadingIcon = { Icon(painterResource(R.drawable.ic_sort), null, Modifier.size(18.dp)) },
            trailingIcon = {
                Icon(
                    painterResource(if (order.ascending) R.drawable.ic_arrow_upward else R.drawable.ic_arrow_downward),
                    if (order.ascending) "Ascending" else "Descending",
                    Modifier.size(16.dp),
                )
            },
        )
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            Text(
                "Sort by — tap again to reverse",
                style = MaterialTheme.typography.labelMedium,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )
            SortField.entries.forEach { field ->
                val active = field == order.field
                DropdownMenuItem(
                    text = { Text(field.label) },
                    onClick = { onSelect(field); open = false },
                    leadingIcon = { RadioButton(selected = active, onClick = null) },
                    trailingIcon = if (active) ({
                        Icon(
                            painterResource(if (order.ascending) R.drawable.ic_arrow_upward else R.drawable.ic_arrow_downward),
                            null,
                        )
                    }) else null,
                )
            }
        }
    }
}

@Composable
fun GroupChip(groupBy: GroupBy, onSelect: (GroupBy) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        AssistChip(
            onClick = { open = true },
            label = { Text(if (groupBy == GroupBy.NONE) "Group" else "By ${groupBy.label.lowercase()}") },
            leadingIcon = { Icon(painterResource(R.drawable.ic_view_agenda), null, Modifier.size(18.dp)) },
            trailingIcon = { Icon(painterResource(R.drawable.ic_arrow_drop_down), null, Modifier.size(18.dp)) },
        )
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            GroupBy.entries.forEach { option ->
                DropdownMenuItem(
                    text = { Text(option.label) },
                    onClick = { onSelect(option); open = false },
                    leadingIcon = { RadioButton(selected = option == groupBy, onClick = null) },
                )
            }
        }
    }
}

@Composable
fun FolderDialog(
    current: MediaSource,
    folders: List<MediaFolder>?,
    hasMediaAccess: Boolean,
    onSelect: (MediaSource) -> Unit,
    onBrowse: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Show media from") },
        text = {
            Column {
                if (!hasMediaAccess) {
                    Text(
                        "Allow media access to choose from indexed folders, or browse to any folder.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                } else if (folders == null) {
                    Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator()
                    }
                } else {
                    LazyColumn(Modifier.heightIn(max = 420.dp)) {
                        item {
                            FolderOption("All folders", null, 0, current == MediaSource.All) { onSelect(MediaSource.All) }
                        }
                        (current as? MediaSource.Tree)?.let { tree ->
                            item { FolderOption(tree.name, "Picked folder", 0, true) { onSelect(tree) } }
                        }
                        items(folders, key = { it.volume + it.relativePath }) { folder ->
                            val source = MediaSource.Folder(folder.volume, folder.relativePath)
                            val segments = folder.relativePath.trimEnd('/').split('/')
                            FolderOption(
                                name = segments.last(),
                                detail = "${folder.count} file${if (folder.count == 1) "" else "s"}" +
                                    if (folder.volume != MediaStore.VOLUME_EXTERNAL_PRIMARY) " · ${folder.volume}" else "",
                                depth = segments.size - 1,
                                selected = current == source,
                            ) { onSelect(source) }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onBrowse) { Text("Browse other folder…") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun FolderOption(name: String, detail: String?, depth: Int, selected: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(start = (depth * 16).dp, top = 4.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = null)
        Column(Modifier.padding(start = 12.dp)) {
            Text(name, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            detail?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
    }
}

@Composable
fun GroupHeader(
    group: MediaGroup<*>,
    groupBy: GroupBy,
    selectedCount: Int,
    thumbnail: @Composable () -> Unit,
    onToggleSelect: () -> Unit,
    onEdit: () -> Unit,
) {
    Surface(color = MaterialTheme.colorScheme.surfaceContainerHigh) {
        Row(
            Modifier.fillMaxWidth().clickable(onClick = onToggleSelect).padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            TriStateCheckbox(
                state = when (selectedCount) {
                    0 -> ToggleableState.Off
                    group.items.size -> ToggleableState.On
                    else -> ToggleableState.Indeterminate
                },
                onClick = onToggleSelect,
            )
            if (groupBy == GroupBy.COVER && !group.isUnknown) thumbnail()
            Column(Modifier.weight(1f)) {
                Text(group.label, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    "${group.items.size} item${if (group.items.size == 1) "" else "s"}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            TextButton(onClick = onEdit) {
                Icon(painterResource(R.drawable.ic_edit), null, Modifier.size(18.dp))
                Text(if (group.items.size > 1) "Edit all" else "Edit", Modifier.padding(start = 6.dp))
            }
        }
    }
}
