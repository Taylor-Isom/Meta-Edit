package com.example.metaedit.data

/**
 * Guesses tags from common file naming schemes:
 * "Artist - Title", "07 - Title", "07. Artist - Title", "Artist - Album - 07 - Title".
 */
object FilenameParser {
    private val leadingTrack = Regex("""^(\d{1,3})\s*[.\-)]\s*(.+)$""")

    // "07 Title" only counts when there is no other segment, so "50 Cent - Song" keeps its artist.
    private val leadingTrackSpaceOnly = Regex("""^(\d{1,3})\s+(.+)$""")

    fun parse(fileName: String): Map<MetaField, String> {
        val base = fileName.substringBeforeLast('.').replace('_', ' ').trim()
        if (base.isEmpty()) return emptyMap()
        val result = linkedMapOf<MetaField, String>()
        val parts = base.split(" - ").map { it.trim() }.filter { it.isNotEmpty() }.toMutableList()

        // A purely numeric segment anywhere before the title is the track number.
        val numericIndex = parts.dropLast(1).indexOfFirst { it.all(Char::isDigit) && it.length <= 3 }
        if (numericIndex >= 0) {
            result[MetaField.TRACK] = parts.removeAt(numericIndex).trimStart('0').ifEmpty { "0" }
        } else if (parts.isNotEmpty()) {
            val pattern = if (parts.size == 1) leadingTrackSpaceOnly else null
            (leadingTrack.matchEntire(parts[0]) ?: pattern?.matchEntire(parts[0]))?.let { m ->
                result[MetaField.TRACK] = m.groupValues[1].trimStart('0').ifEmpty { "0" }
                parts[0] = m.groupValues[2].trim()
            }
        }

        when (parts.size) {
            0 -> Unit
            1 -> result[MetaField.TITLE] = parts[0]
            2 -> {
                result[MetaField.ARTIST] = parts[0]
                result[MetaField.TITLE] = parts[1]
            }
            else -> {
                result[MetaField.ARTIST] = parts[0]
                result[MetaField.ALBUM] = parts[1]
                result[MetaField.TITLE] = parts.drop(2).joinToString(" - ")
            }
        }
        return result
    }
}
