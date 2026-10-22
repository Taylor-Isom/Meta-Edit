package com.example.metaedit.data.mp4

import com.example.metaedit.data.ArtworkChange
import com.example.metaedit.data.ArtworkData
import com.example.metaedit.data.MetaField
import com.example.metaedit.data.TagEdits
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer

class Mp4MetadataTest {
    @get:Rule val tmp = TemporaryFolder()

    private val chunkA = "CHUNK-A".toByteArray() + ByteArray(1000) { 1 }
    private val chunkB = "CHUNK-B".toByteArray() + ByteArray(1000) { 2 }

    private fun ftyp() = Box.leaf("ftyp", "isom".toByteArray() + ByteArray(4) + "isommp41".toByteArray())

    private fun moov(offsetA: Long, offsetB: Long): Box {
        val stco = ByteBuffer.allocate(12).putInt(0).putInt(1).putInt(offsetA.toInt()).array()
        val co64 = ByteBuffer.allocate(16).putInt(0).putInt(1).putLong(offsetB).array()
        fun trak(table: Box) = Box.container("trak", Box.container("mdia", Box.container("minf", Box.container("stbl", table))))
        return Box.container("moov", Box.leaf("mvhd", ByteArray(100)), trak(Box.leaf("stco", stco)), trak(Box.leaf("co64", co64)))
    }

    /** Builds a file with the movie header either before (faststart) or after the media data. */
    private fun build(moovFirst: Boolean): File {
        val ftyp = ftyp().toBytes()
        val mdat = Box.leaf("mdat", chunkA + chunkB).toBytes()
        val moovSize = moov(0, 0).toBytes().size
        val out = ByteArrayOutputStream()
        out.write(ftyp)
        if (moovFirst) {
            val dataStart = (ftyp.size + moovSize + 8).toLong()
            out.write(moov(dataStart, dataStart + chunkA.size).toBytes())
            out.write(mdat)
        } else {
            val dataStart = (ftyp.size + 8).toLong()
            out.write(mdat)
            out.write(moov(dataStart, dataStart + chunkA.size).toBytes())
        }
        return tmp.newFile().apply { writeBytes(out.toByteArray()) }
    }

    /** Every chunk offset must still point at the chunk it pointed at before the edit. */
    private fun assertChunksIntact(file: File) {
        val bytes = file.readBytes()
        val top = Box.parseChildren(bytes, 0, bytes.size, descend = false)
        val moovOffset = top.takeWhile { it.type != "moov" }.sumOf { it.size }.toInt()
        val moovEnd = moovOffset + top.first { it.type == "moov" }.size.toInt()
        val offsets = mutableListOf<Pair<String, Long>>()
        fun walk(b: Box) {
            b.children?.forEach(::walk)
            val buf = ByteBuffer.wrap(b.data)
            if (b.type == "stco") offsets += "CHUNK-A" to (buf.getInt(8).toLong() and 0xFFFFFFFFL)
            if (b.type == "co64") offsets += "CHUNK-B" to buf.getLong(8)
        }
        Box.parseChildren(bytes, moovOffset + 8, moovEnd).forEach(::walk)
        assertEquals(2, offsets.size)
        for ((label, offset) in offsets) {
            assertEquals(label, String(bytes, offset.toInt(), 7))
        }
    }

    private fun roundTrip(moovFirst: Boolean) {
        val file = build(moovFirst)
        val cover = ArtworkData(byteArrayOf(0xFF.toByte(), 0xD8.toByte()) + ByteArray(30_000), "image/jpeg")
        val report = Mp4Metadata.write(
            file,
            TagEdits(
                mapOf(
                    MetaField.TITLE to "Título ✓",
                    MetaField.ARTIST to "Artist",
                    MetaField.TRACK to "3/12",
                    MetaField.DISC_NO to "1",
                    MetaField.BPM to "120",
                    MetaField.RECORD_LABEL to "Label Co",
                ),
                ArtworkChange.Replace(cover),
            ),
        )
        assertTrue(report.isClean)
        assertChunksIntact(file)

        val read = Mp4Metadata.read(file)
        assertEquals("Título ✓", read.values[MetaField.TITLE])
        assertEquals("Artist", read.values[MetaField.ARTIST])
        assertEquals("3", read.values[MetaField.TRACK])
        assertEquals("12", read.values[MetaField.TRACK_TOTAL])
        assertEquals("1", read.values[MetaField.DISC_NO])
        assertEquals("120", read.values[MetaField.BPM])
        assertEquals("Label Co", read.values[MetaField.RECORD_LABEL])
        assertEquals(cover, read.artwork)

        // A smaller second edit must fit in the padding left behind and not move the media.
        val sizeAfterFirst = file.length()
        Mp4Metadata.write(file, TagEdits(mapOf(MetaField.ARTIST to "", MetaField.TRACK_TOTAL to "13"), ArtworkChange.Remove))
        assertEquals(sizeAfterFirst, file.length())
        assertChunksIntact(file)
        val again = Mp4Metadata.read(file)
        assertEquals("", again.values[MetaField.ARTIST])
        assertEquals("3", again.values[MetaField.TRACK])
        assertEquals("13", again.values[MetaField.TRACK_TOTAL])
        assertEquals("Título ✓", again.values[MetaField.TITLE])
        assertNull(again.artwork)
    }

    @Test fun faststartLayoutShiftsChunkOffsets() = roundTrip(moovFirst = true)

    @Test fun moovAtEndLayout() = roundTrip(moovFirst = false)

    @Test fun invalidNumberIsReportedNotWritten() {
        val file = build(moovFirst = true)
        val report = Mp4Metadata.write(file, TagEdits(mapOf(MetaField.TRACK to "three", MetaField.TITLE to "T"), ArtworkChange.Keep))
        assertEquals(setOf(MetaField.TRACK), report.skipped.keys)
        assertEquals("T", Mp4Metadata.read(file).values[MetaField.TITLE])
        assertChunksIntact(file)
    }
}
