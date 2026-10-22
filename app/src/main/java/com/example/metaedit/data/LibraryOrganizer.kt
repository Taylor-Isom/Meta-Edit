package com.example.metaedit.data

enum class SortField(val label: String) {
    DATE_MODIFIED("Recently modified"),
    TITLE("Title"),
    ARTIST("Artist"),
    ALBUM("Album"),
    FILENAME("File name"),
}

enum class GroupBy(val label: String) {
    NONE("No grouping"),
    ALBUM("Album"),
    ARTIST("Artist"),
    COVER("Cover art"),
}

data class SortOrder(val field: SortField = SortField.DATE_MODIFIED, val ascending: Boolean = false)

/** A set of items that share an album, artist or identical cover art. */
data class MediaGroup<T : Sortable>(
    val key: String,
    val label: String,
    /** True for the "Unknown artist" / "No cover art" bucket. */
    val isUnknown: Boolean,
    val items: List<T>,
)

object LibraryOrganizer {
    fun displayTitle(item: Sortable) = item.title?.takeIf { it.isNotBlank() } ?: item.displayName

    fun <T : Sortable> sort(items: List<T>, order: SortOrder): List<T> {
        if (order.field == SortField.DATE_MODIFIED) {
            return if (order.ascending) items.sortedBy { it.dateModified } else items.sortedByDescending { it.dateModified }
        }
        val primary: (Sortable) -> String? = when (order.field) {
            SortField.TITLE -> ::displayTitle
            SortField.ARTIST -> { it -> it.artist }
            SortField.ALBUM -> { it -> it.album }
            else -> { it -> it.displayName }
        }
        val byPrimary = Comparator<Sortable> { a, b ->
            val x = primary(a)?.trim().orEmpty()
            val y = primary(b)?.trim().orEmpty()
            when {
                // Missing values sit at the end whichever way the list is sorted.
                x.isEmpty() && y.isEmpty() -> 0
                x.isEmpty() -> 1
                y.isEmpty() -> -1
                else -> (if (order.ascending) 1 else -1) * x.compareTo(y, ignoreCase = true)
            }
        }
        val tieBreak = compareBy<Sortable, String>(String.CASE_INSENSITIVE_ORDER) { displayTitle(it) }
            .thenBy(String.CASE_INSENSITIVE_ORDER) { it.displayName }
        return items.sortedWith(byPrimary.then(tieBreak))
    }

    /**
     * Groups [items] (already sorted) keeping their order inside each group. Album and artist
     * names are matched ignoring case and surrounding spaces. [coverKey] supplies cover
     * fingerprints for [GroupBy.COVER]; items whose key isn't known yet are left out.
     */
    fun <T : Sortable> group(
        items: List<T>,
        by: GroupBy,
        coverKey: (T) -> String? = { null },
    ): List<MediaGroup<T>> {
        if (by == GroupBy.NONE) return listOf(MediaGroup("", "", false, items))
        val buckets = LinkedHashMap<String, MutableList<T>>()
        for (item in items) {
            val key = when (by) {
                GroupBy.ALBUM -> item.album?.trim()?.lowercase().orEmpty()
                GroupBy.ARTIST -> item.artist?.trim()?.lowercase().orEmpty()
                GroupBy.COVER -> coverKey(item)?.let { if (it == MediaLibrary.NO_COVER) "" else it } ?: continue
                GroupBy.NONE -> ""
            }
            buckets.getOrPut(key) { mutableListOf() } += item
        }
        return buckets.map { (key, members) ->
            val label = when {
                key.isEmpty() -> when (by) {
                    GroupBy.ALBUM -> "Unknown album"
                    GroupBy.ARTIST -> "Unknown artist"
                    else -> "No cover art"
                }
                by == GroupBy.ALBUM -> mostCommon(members.mapNotNull { it.album?.trim() })
                by == GroupBy.ARTIST -> mostCommon(members.mapNotNull { it.artist?.trim() })
                // Name a cover group after the album most of its files belong to.
                else -> mostCommon(members.mapNotNull { it.album?.trim()?.takeIf(String::isNotEmpty) })
                    .ifEmpty { "Shared cover" }
            }
            MediaGroup(key, label, key.isEmpty(), members)
        }.sortedWith(compareBy<MediaGroup<T>> { it.isUnknown }.thenBy(String.CASE_INSENSITIVE_ORDER) { it.label })
    }

    /** The most frequent spelling, so "the beatles"/"The Beatles" shows as the majority form. */
    private fun mostCommon(values: List<String>): String =
        values.groupingBy { it }.eachCount().maxByOrNull { it.value }?.key.orEmpty()
}
