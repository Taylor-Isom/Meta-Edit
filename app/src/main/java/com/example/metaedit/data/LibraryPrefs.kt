package com.example.metaedit.data

import android.content.Context
import android.net.Uri
import androidx.core.content.edit

/** Remembers the chosen folder, sort order and grouping between launches. */
class LibraryPrefs(context: Context) {
    private val prefs = context.getSharedPreferences("library", Context.MODE_PRIVATE)

    var source: MediaSource
        get() {
            val a = prefs.getString(KEY_SOURCE_A, null) ?: return MediaSource.All
            val b = prefs.getString(KEY_SOURCE_B, null).orEmpty()
            return when (prefs.getString(KEY_SOURCE_TYPE, null)) {
                "folder" -> MediaSource.Folder(a, b)
                "tree" -> MediaSource.Tree(Uri.parse(a), b)
                else -> MediaSource.All
            }
        }
        set(value) = prefs.edit {
            when (value) {
                MediaSource.All -> putString(KEY_SOURCE_TYPE, "all")
                is MediaSource.Folder -> putString(KEY_SOURCE_TYPE, "folder")
                    .putString(KEY_SOURCE_A, value.volume).putString(KEY_SOURCE_B, value.relativePath)
                is MediaSource.Tree -> putString(KEY_SOURCE_TYPE, "tree")
                    .putString(KEY_SOURCE_A, value.treeUri.toString()).putString(KEY_SOURCE_B, value.name)
            }
        }

    var sort: SortOrder
        get() = SortOrder(
            enumOrDefault(prefs.getString(KEY_SORT, null), SortField.DATE_MODIFIED),
            prefs.getBoolean(KEY_ASCENDING, false),
        )
        set(value) = prefs.edit { putString(KEY_SORT, value.field.name).putBoolean(KEY_ASCENDING, value.ascending) }

    var groupBy: GroupBy
        get() = enumOrDefault(prefs.getString(KEY_GROUP, null), GroupBy.NONE)
        set(value) = prefs.edit { putString(KEY_GROUP, value.name) }

    private inline fun <reified E : Enum<E>> enumOrDefault(name: String?, default: E): E =
        enumValues<E>().firstOrNull { it.name == name } ?: default

    private companion object {
        const val KEY_SOURCE_TYPE = "source_type"
        const val KEY_SOURCE_A = "source_a"
        const val KEY_SOURCE_B = "source_b"
        const val KEY_SORT = "sort"
        const val KEY_ASCENDING = "ascending"
        const val KEY_GROUP = "group"
    }
}
