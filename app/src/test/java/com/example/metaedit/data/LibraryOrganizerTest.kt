package com.example.metaedit.data

import org.junit.Assert.assertEquals
import org.junit.Test

class LibraryOrganizerTest {
    private data class Item(
        override val displayName: String,
        override val title: String?,
        override val artist: String?,
        override val album: String?,
        override val dateModified: Long,
    ) : Sortable

    private fun item(name: String, title: String? = null, artist: String? = null, album: String? = null, modified: Long = 0) =
        Item(name, title, artist, album, modified)

    private val a = item("c.mp3", title = "Alpha", artist = "Zed", album = "Second", modified = 3)
    private val b = item("a.mp3", title = "beta", artist = "abba", album = "First", modified = 1)
    private val c = item("b.mp3", title = null, artist = null, album = "first ", modified = 2)

    private fun names(items: List<Item>) = items.map { it.displayName }

    @Test
    fun sortsByTitleFallingBackToFileName() {
        // c has no title, so its file name "b.mp3" is its title.
        assertEquals(listOf("c.mp3", "b.mp3", "a.mp3"), names(LibraryOrganizer.sort(listOf(b, c, a), SortOrder(SortField.TITLE, true))))
    }

    @Test
    fun missingArtistsStayLastInBothDirections() {
        assertEquals(listOf("a.mp3", "c.mp3", "b.mp3"), names(LibraryOrganizer.sort(listOf(a, b, c), SortOrder(SortField.ARTIST, true))))
        assertEquals(listOf("c.mp3", "a.mp3", "b.mp3"), names(LibraryOrganizer.sort(listOf(a, b, c), SortOrder(SortField.ARTIST, false))))
    }

    @Test
    fun sortsByFileNameAndDate() {
        assertEquals(listOf("a.mp3", "b.mp3", "c.mp3"), names(LibraryOrganizer.sort(listOf(a, b, c), SortOrder(SortField.FILENAME, true))))
        assertEquals(listOf("c.mp3", "b.mp3", "a.mp3"), names(LibraryOrganizer.sort(listOf(b, c, a), SortOrder(SortField.DATE_MODIFIED, false))))
    }

    @Test
    fun groupsAlbumsIgnoringCaseAndSpaces() {
        val groups = LibraryOrganizer.group(listOf(a, b, c), GroupBy.ALBUM)
        assertEquals(listOf("First", "Second"), groups.map { it.label })
        assertEquals(listOf("a.mp3", "b.mp3"), names(groups[0].items))
    }

    @Test
    fun unknownArtistGroupComesLast() {
        val groups = LibraryOrganizer.group(listOf(c, a, b), GroupBy.ARTIST)
        assertEquals(listOf("abba", "Zed", "Unknown artist"), groups.map { it.label })
    }

    @Test
    fun groupsByCoverFingerprintAndSkipsUnknownOnes() {
        val keys = mapOf(a to "x", b to "x", c to MediaLibrary.NO_COVER)
        val groups = LibraryOrganizer.group(listOf(a, b, c), GroupBy.COVER) { keys[it] }
        assertEquals(2, groups.size)
        assertEquals(listOf("c.mp3", "a.mp3"), names(groups[0].items))
        assertEquals("No cover art", groups[1].label)

        val partial = LibraryOrganizer.group(listOf(a, b, c), GroupBy.COVER) { if (it === a) "x" else null }
        assertEquals(listOf("c.mp3"), partial.flatMap { names(it.items) })
    }
}
