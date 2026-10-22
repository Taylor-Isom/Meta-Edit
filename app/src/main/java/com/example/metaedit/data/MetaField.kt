package com.example.metaedit.data

import org.jaudiotagger.tag.FieldKey

enum class FieldInput { TEXT, NUMBER, MULTILINE }

enum class FieldSection(val title: String) {
    BASIC("Basics"),
    TRACK("Track & disc"),
    CREDITS("Credits"),
    OTHER("More"),
}

/** Editable tag fields, mapped onto jaudiotagger's format-independent [FieldKey]s. */
enum class MetaField(
    val key: FieldKey,
    val label: String,
    val section: FieldSection,
    val input: FieldInput = FieldInput.TEXT,
) {
    TITLE(FieldKey.TITLE, "Title", FieldSection.BASIC),
    ARTIST(FieldKey.ARTIST, "Artist", FieldSection.BASIC),
    ALBUM(FieldKey.ALBUM, "Album", FieldSection.BASIC),
    ALBUM_ARTIST(FieldKey.ALBUM_ARTIST, "Album artist", FieldSection.BASIC),
    GENRE(FieldKey.GENRE, "Genre", FieldSection.BASIC),
    YEAR(FieldKey.YEAR, "Year / date", FieldSection.BASIC),

    TRACK(FieldKey.TRACK, "Track #", FieldSection.TRACK, FieldInput.NUMBER),
    TRACK_TOTAL(FieldKey.TRACK_TOTAL, "Total tracks", FieldSection.TRACK, FieldInput.NUMBER),
    DISC_NO(FieldKey.DISC_NO, "Disc #", FieldSection.TRACK, FieldInput.NUMBER),
    DISC_TOTAL(FieldKey.DISC_TOTAL, "Total discs", FieldSection.TRACK, FieldInput.NUMBER),

    COMPOSER(FieldKey.COMPOSER, "Composer", FieldSection.CREDITS),
    LYRICIST(FieldKey.LYRICIST, "Lyricist", FieldSection.CREDITS),
    CONDUCTOR(FieldKey.CONDUCTOR, "Conductor", FieldSection.CREDITS),
    RECORD_LABEL(FieldKey.RECORD_LABEL, "Label / publisher", FieldSection.CREDITS),
    COPYRIGHT(FieldKey.COPYRIGHT, "Copyright", FieldSection.CREDITS),

    COMMENT(FieldKey.COMMENT, "Comment / description", FieldSection.OTHER, FieldInput.MULTILINE),
    LYRICS(FieldKey.LYRICS, "Lyrics", FieldSection.OTHER, FieldInput.MULTILINE),
    BPM(FieldKey.BPM, "BPM", FieldSection.OTHER, FieldInput.NUMBER),
    GROUPING(FieldKey.GROUPING, "Grouping", FieldSection.OTHER),
    ISRC(FieldKey.ISRC, "ISRC", FieldSection.OTHER),
    ENCODER(FieldKey.ENCODER, "Encoder", FieldSection.OTHER),
}
