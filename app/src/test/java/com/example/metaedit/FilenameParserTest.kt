package com.example.metaedit

import com.example.metaedit.data.FilenameParser
import com.example.metaedit.data.MetaField
import org.junit.Assert.assertEquals
import org.junit.Test

class FilenameParserTest {
    private fun parse(name: String) = FilenameParser.parse(name)

    @Test
    fun artistAndTitle() = assertEquals(
        mapOf(MetaField.ARTIST to "Daft Punk", MetaField.TITLE to "One More Time"),
        parse("Daft Punk - One More Time.mp3"),
    )

    @Test
    fun trackAndTitle() = assertEquals(
        mapOf(MetaField.TRACK to "7", MetaField.TITLE to "Song"),
        parse("07 - Song.flac"),
    )

    @Test
    fun dottedTrackPrefix() = assertEquals(
        mapOf(MetaField.TRACK to "3", MetaField.ARTIST to "Artist", MetaField.TITLE to "Title"),
        parse("03. Artist - Title.m4a"),
    )

    @Test
    fun artistAlbumTrackTitle() = assertEquals(
        mapOf(MetaField.TRACK to "12", MetaField.ARTIST to "A", MetaField.ALBUM to "B", MetaField.TITLE to "C"),
        parse("A - B - 12 - C.mp3"),
    )

    @Test
    fun leadingNumberInArtistIsNotATrack() = assertEquals(
        mapOf(MetaField.ARTIST to "50 Cent", MetaField.TITLE to "In Da Club"),
        parse("50 Cent - In Da Club.mp3"),
    )

    @Test
    fun spaceSeparatedTrackOnlyWhenAlone() = assertEquals(
        mapOf(MetaField.TRACK to "1", MetaField.TITLE to "Intro"),
        parse("01 Intro.mp3"),
    )

    @Test
    fun yearTitleIsKept() = assertEquals(mapOf(MetaField.TITLE to "1979"), parse("1979.mp3"))
}
