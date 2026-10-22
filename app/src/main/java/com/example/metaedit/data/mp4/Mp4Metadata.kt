package com.example.metaedit.data.mp4

import com.example.metaedit.data.ArtworkChange
import com.example.metaedit.data.ArtworkData
import com.example.metaedit.data.ImageUtils
import com.example.metaedit.data.MetaField
import com.example.metaedit.data.SaveReport
import com.example.metaedit.data.TagEdits
import com.example.metaedit.data.TagSnapshot
import org.jaudiotagger.tag.reference.GenreTypes
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.ByteBuffer

/**
 * Reads and writes iTunes-style metadata (`moov/udta/meta/ilst`) in MP4, M4A, MOV and 3GP files,
 * including files with video tracks and 64-bit chunk offsets.
 *
 * Writes keep media data in place whenever the new `moov` fits in the old one plus any `free`
 * padding after it. Otherwise the data after `moov` is shifted and every `stco`/`co64` chunk
 * offset is adjusted, and some padding is left behind so the next edit is cheap.
 */
object Mp4Metadata {
    private const val PADDING = 4096
    private const val MAX_MOOV = 256 * 1024 * 1024
    private const val TYPE_UTF8 = 1
    private const val TYPE_JPEG = 13
    private const val TYPE_PNG = 14
    private const val TYPE_INT = 21

    private sealed interface Spec {
        data class Text(val atom: String) : Spec
        data class Freeform(val name: String) : Spec
        data class Pair(val atom: String, val second: Boolean) : Spec
        data object Bpm : Spec
    }

    private val specs: Map<MetaField, Spec> = mapOf(
        MetaField.TITLE to Spec.Text("©nam"),
        MetaField.ARTIST to Spec.Text("©ART"),
        MetaField.ALBUM to Spec.Text("©alb"),
        MetaField.ALBUM_ARTIST to Spec.Text("aART"),
        MetaField.GENRE to Spec.Text("©gen"),
        MetaField.YEAR to Spec.Text("©day"),
        MetaField.TRACK to Spec.Pair("trkn", second = false),
        MetaField.TRACK_TOTAL to Spec.Pair("trkn", second = true),
        MetaField.DISC_NO to Spec.Pair("disk", second = false),
        MetaField.DISC_TOTAL to Spec.Pair("disk", second = true),
        MetaField.COMPOSER to Spec.Text("©wrt"),
        MetaField.LYRICIST to Spec.Freeform("LYRICIST"),
        MetaField.CONDUCTOR to Spec.Freeform("CONDUCTOR"),
        MetaField.RECORD_LABEL to Spec.Freeform("LABEL"),
        MetaField.COPYRIGHT to Spec.Text("cprt"),
        MetaField.COMMENT to Spec.Text("©cmt"),
        MetaField.LYRICS to Spec.Text("©lyr"),
        MetaField.BPM to Spec.Bpm,
        MetaField.GROUPING to Spec.Text("©grp"),
        MetaField.ISRC to Spec.Freeform("ISRC"),
        MetaField.ENCODER to Spec.Text("©too"),
    )

    // ---------------------------------------------------------------- reading

    fun read(file: File): TagSnapshot = RandomAccessFile(file, "r").use { raf ->
        val moov = loadMoov(raf, scanTopLevel(raf))
        val ilst = findIlst(moov)
        val legacy = moov.child("udta")
        val values = MetaField.entries.associateWith { field ->
            ilst?.let { readField(it, field) }?.takeIf { it.isNotEmpty() }
                ?: legacyText(legacy, field).orEmpty()
        }
        TagSnapshot(values, specs.keys, ilst?.let(::readCover))
    }

    private fun readField(ilst: Box, field: MetaField): String? = when (val spec = specs.getValue(field)) {
        is Spec.Text -> textOf(ilst.child(spec.atom))
            ?: if (field == MetaField.GENRE) legacyGenre(ilst.child("gnre")) else null
        is Spec.Freeform -> textOf(findFreeform(ilst, spec.name))
        is Spec.Pair -> dataPayload(ilst.child(spec.atom))?.let { p ->
            if (p.size < 6) null
            else u16(p, if (spec.second) 4 else 2).takeIf { it > 0 }?.toString()
        }
        Spec.Bpm -> dataPayload(ilst.child("tmpo"))?.let { p ->
            when (p.size) {
                0 -> null
                1 -> (p[0].toInt() and 0xFF).toString()
                else -> u16(p, 0).toString()
            }
        }
    }

    private fun readCover(ilst: Box): ArtworkData? {
        val item = ilst.child("covr") ?: return null
        val data = itemChildren(item).firstOrNull { it.type == "data" && it.data.size > 8 } ?: return null
        val bytes = data.data.copyOfRange(8, data.data.size)
        val mime = when (ByteBuffer.wrap(data.data).int and 0xFFFFFF) {
            TYPE_PNG -> "image/png"
            TYPE_JPEG -> "image/jpeg"
            else -> ImageUtils.sniffMime(bytes)
        }
        return ArtworkData(bytes, mime)
    }

    /** Pre-iTunes QuickTime user data: `udta/©nam` = [u16 length][u16 language][text]. */
    private fun legacyText(udta: Box?, field: MetaField): String? {
        val atom = (specs[field] as? Spec.Text)?.atom ?: return null
        val box = udta?.child(atom) ?: return null
        val d = box.data
        if (d.size < 4) return null
        val len = u16(d, 0).coerceAtMost(d.size - 4)
        return String(d, 4, len, Charsets.UTF_8).trimEnd('\u0000')
    }

    private fun legacyGenre(item: Box?): String? {
        val p = dataPayload(item) ?: return null
        if (p.size < 2) return null
        return GenreTypes.getInstanceOf().getValueForId(u16(p, 0) - 1)
    }

    // ---------------------------------------------------------------- writing

    fun write(file: File, edits: TagEdits): SaveReport {
        val skipped = mutableMapOf<MetaField, String>()
        RandomAccessFile(file, "rw").use { raf ->
            val top = scanTopLevel(raf)
            val moovEntry = top.single { it.type == "moov" }
            val moov = loadMoov(raf, top)
            val ilst = findOrCreateIlst(moov)
            val udta = moov.child("udta")

            for ((field, raw) in edits.fields) {
                val error = applyField(ilst, field, raw.trim())
                if (error != null) {
                    skipped[field] = error
                } else {
                    // Drop a conflicting pre-iTunes value so players don't show the stale one.
                    (specs[field] as? Spec.Text)?.let { spec -> udta?.children?.removeAll { it.type == spec.atom } }
                }
            }
            when (val art = edits.artwork) {
                ArtworkChange.Keep -> Unit
                ArtworkChange.Remove -> ilst.children!!.removeAll { it.type == "covr" }
                is ArtworkChange.Replace -> {
                    val type = if (art.artwork.mimeType == "image/png") TYPE_PNG else TYPE_JPEG
                    setItem(ilst, Box.leaf("covr", dataBox(type, art.artwork.bytes)))
                }
            }
            writeMoov(raf, top, moovEntry, moov)
        }
        return SaveReport(skipped)
    }

    private fun applyField(ilst: Box, field: MetaField, value: String): String? {
        when (val spec = specs.getValue(field)) {
            is Spec.Text -> {
                if (field == MetaField.GENRE) ilst.children!!.removeAll { it.type == "gnre" }
                if (value.isEmpty()) removeItem(ilst, spec.atom)
                else setItem(ilst, Box.leaf(spec.atom, dataBox(TYPE_UTF8, value.toByteArray())))
            }
            is Spec.Freeform -> {
                ilst.children!!.removeAll { isFreeform(it, spec.name) }
                if (value.isNotEmpty()) ilst.children!!.add(freeformItem(spec.name, value))
            }
            is Spec.Pair -> {
                // "3/12" in the track field sets both halves.
                val parts = value.split('/').map { it.trim() }
                val number = if (value.isEmpty()) 0 else parts[0].toIntOrNull() ?: return "must be a number"
                val total = if (!spec.second && parts.size > 1) parts[1].toIntOrNull() else null
                if (number !in 0..0xFFFF || (total != null && total !in 0..0xFFFF)) return "number too large"
                val existing = dataPayload(ilst.child(spec.atom))?.takeIf { it.size >= 6 }
                var first = existing?.let { u16(it, 2) } ?: 0
                var second = existing?.let { u16(it, 4) } ?: 0
                if (spec.second) second = number else first = number
                if (total != null) second = total
                if (first == 0 && second == 0) {
                    removeItem(ilst, spec.atom)
                } else {
                    // trkn has two trailing pad bytes, disk doesn't.
                    val payload = ByteBuffer.allocate(if (spec.atom == "trkn") 8 else 6)
                        .putShort(0).putShort(first.toShort()).putShort(second.toShort()).array()
                    setItem(ilst, Box.leaf(spec.atom, dataBox(0, payload)))
                }
            }
            Spec.Bpm -> {
                if (value.isEmpty()) {
                    removeItem(ilst, "tmpo")
                } else {
                    val bpm = value.toDoubleOrNull()?.let { Math.round(it).toInt() } ?: return "must be a number"
                    if (bpm !in 0..0xFFFF) return "number too large"
                    setItem(ilst, Box.leaf("tmpo", dataBox(TYPE_INT, ByteBuffer.allocate(2).putShort(bpm.toShort()).array())))
                }
            }
        }
        return null
    }

    private fun writeMoov(raf: RandomAccessFile, top: List<TopBox>, moovEntry: TopBox, moov: Box) {
        // Free/skip boxes directly after moov are ours to reuse.
        var regionEnd = moovEntry.end
        for (box in top.drop(top.indexOf(moovEntry) + 1)) {
            if (box.type != "free" && box.type != "skip") break
            regionEnd = box.end
        }
        val budget = regionEnd - moovEntry.offset
        val core = moov.size

        if (core == budget || core + 8 <= budget) {
            writeRegion(raf, moovEntry.offset, moov.toBytes(), budget - core)
            return
        }

        // Doesn't fit: everything after the region moves forward by delta.
        val delta = core + PADDING - budget
        val dataAfter = top.any { it.offset >= regionEnd && it.type != "free" && it.type != "skip" }
        if (dataAfter) {
            if (top.any { it.type == "moof" }) throw IOException("Fragmented MP4 files aren't supported")
            if (hasBox(moov, "saio")) throw IOException("Encrypted MP4 files aren't supported")
            adjustChunkOffsets(moov, regionEnd, delta)
        }
        shiftForward(raf, regionEnd, delta)
        writeRegion(raf, moovEntry.offset, moov.toBytes(), PADDING.toLong())
    }

    private fun writeRegion(raf: RandomAccessFile, offset: Long, moovBytes: ByteArray, padding: Long) {
        raf.seek(offset)
        raf.write(moovBytes)
        if (padding >= 8) {
            raf.write(ByteBuffer.allocate(8).putInt(padding.toInt()).put("free".toByteArray(LATIN1)).array())
            // The padding body is ignored by readers; zero it only when it's small enough to be cheap.
            if (padding <= PADDING * 4) raf.write(ByteArray((padding - 8).toInt()))
        }
    }

    private fun shiftForward(raf: RandomAccessFile, from: Long, delta: Long) {
        val oldLength = raf.length()
        raf.setLength(oldLength + delta)
        val buffer = ByteArray(1 shl 20)
        var end = oldLength
        while (end > from) {
            val n = minOf(buffer.size.toLong(), end - from).toInt()
            val start = end - n
            raf.seek(start)
            raf.readFully(buffer, 0, n)
            raf.seek(start + delta)
            raf.write(buffer, 0, n)
            end = start
        }
    }

    private fun adjustChunkOffsets(box: Box, threshold: Long, delta: Long) {
        box.children?.forEach { adjustChunkOffsets(it, threshold, delta) }
        if (box.type != "stco" && box.type != "co64") return
        val buf = ByteBuffer.wrap(box.data)
        val count = buf.getInt(4)
        for (i in 0 until count) {
            if (box.type == "stco") {
                val at = 8 + i * 4
                val offset = buf.getInt(at).toLong() and 0xFFFFFFFFL
                if (offset >= threshold) {
                    val moved = offset + delta
                    if (moved > 0xFFFFFFFFL) throw IOException("File is too large to edit in place")
                    buf.putInt(at, moved.toInt())
                }
            } else {
                val at = 8 + i * 8
                val offset = buf.getLong(at)
                if (offset >= threshold) buf.putLong(at, offset + delta)
            }
        }
    }

    private fun hasBox(box: Box, type: String): Boolean =
        box.type == type || box.children?.any { hasBox(it, type) } == true

    // ---------------------------------------------------------------- structure helpers

    private class TopBox(val type: String, val offset: Long, val size: Long, val header: Int) {
        val end get() = offset + size
    }

    private fun scanTopLevel(raf: RandomAccessFile): List<TopBox> {
        val result = mutableListOf<TopBox>()
        val length = raf.length()
        var pos = 0L
        val header = ByteArray(16)
        while (pos + 8 <= length) {
            raf.seek(pos)
            raf.readFully(header, 0, 8)
            var size = ByteBuffer.wrap(header).int.toLong() and 0xFFFFFFFFL
            val type = String(header, 4, 4, LATIN1)
            var headerSize = 8
            if (size == 1L) {
                raf.readFully(header, 8, 8)
                size = ByteBuffer.wrap(header, 8, 8).long
                headerSize = 16
            } else if (size == 0L) {
                size = length - pos
            }
            if (size < headerSize || pos + size > length) {
                // Tolerate trailing garbage after a complete moov, otherwise bail out.
                if (result.any { it.type == "moov" }) break
                throw IOException("Not a valid MP4/QuickTime file")
            }
            result += TopBox(type, pos, size, headerSize)
            pos += size
        }
        if (result.count { it.type == "moov" } != 1) throw IOException("Not a valid MP4/QuickTime file")
        return result
    }

    private fun loadMoov(raf: RandomAccessFile, top: List<TopBox>): Box {
        val entry = top.single { it.type == "moov" }
        if (entry.size > MAX_MOOV) throw IOException("Movie header too large")
        val bytes = ByteArray(entry.size.toInt())
        raf.seek(entry.offset)
        raf.readFully(bytes)
        return Box("moov").apply { children = Box.parseChildren(bytes, entry.header, bytes.size) }
    }

    private fun isItunesMeta(meta: Box): Boolean {
        val hdlr = meta.child("hdlr")?.data ?: return meta.child("ilst") != null
        return hdlr.size >= 12 && String(hdlr, 8, 4, LATIN1) == "mdir"
    }

    private fun findIlst(moov: Box): Box? =
        moov.child("udta")?.child("meta")?.takeIf(::isItunesMeta)?.child("ilst")
            ?: moov.child("meta")?.takeIf(::isItunesMeta)?.child("ilst")

    private fun findOrCreateIlst(moov: Box): Box {
        findIlst(moov)?.let { return it }
        val udta = moov.child("udta") ?: Box.container("udta").also { moov.children!!.add(it) }
        // A udta/meta that isn't iTunes metadata (rare) is replaced rather than merged into.
        udta.children!!.removeAll { it.type == "meta" }
        val hdlr = ByteBuffer.allocate(25)
            .putInt(0).putInt(0).put("mdir".toByteArray(LATIN1)).put("appl".toByteArray(LATIN1))
            .putInt(0).putInt(0).put(0).array()
        val ilst = Box.container("ilst")
        udta.children!!.add(Box.container("meta", Box.leaf("hdlr", hdlr), ilst).apply { prefix = ByteArray(4) })
        return ilst
    }

    private fun itemChildren(item: Box): List<Box> =
        runCatching { Box.parseChildren(item.data, 0, item.data.size, descend = false) }.getOrDefault(emptyList())

    /** Payload of the first `data` child, minus its type and locale words. */
    private fun dataPayload(item: Box?): ByteArray? {
        val data = item?.let(::itemChildren)?.firstOrNull { it.type == "data" } ?: return null
        return if (data.data.size < 8) null else data.data.copyOfRange(8, data.data.size)
    }

    private fun textOf(item: Box?): String? = dataPayload(item)?.toString(Charsets.UTF_8)

    private fun isFreeform(item: Box, name: String): Boolean {
        if (item.type != "----") return false
        val kids = itemChildren(item)
        val n = kids.firstOrNull { it.type == "name" }?.data ?: return false
        return n.size >= 4 && String(n, 4, n.size - 4, Charsets.UTF_8).equals(name, ignoreCase = true)
    }

    private fun findFreeform(ilst: Box, name: String) = ilst.children?.firstOrNull { isFreeform(it, name) }

    private fun freeformItem(name: String, value: String): Box {
        val out = ByteArrayOutputStream()
        Box.leaf("mean", ByteArray(4) + "com.apple.iTunes".toByteArray()).writeTo(out)
        Box.leaf("name", ByteArray(4) + name.toByteArray()).writeTo(out)
        out.write(dataBox(TYPE_UTF8, value.toByteArray()))
        return Box.leaf("----", out.toByteArray())
    }

    /** Serialized `data` box: [u32 type][u32 locale][payload]. */
    private fun dataBox(type: Int, payload: ByteArray): ByteArray =
        Box.leaf("data", ByteBuffer.allocate(8 + payload.size).putInt(type).putInt(0).put(payload).array()).toBytes()

    private fun setItem(ilst: Box, item: Box) {
        val kids = ilst.children!!
        val index = kids.indexOfFirst { it.type == item.type }
        kids.removeAll { it.type == item.type }
        if (index >= 0) kids.add(index.coerceAtMost(kids.size), item) else kids.add(item)
    }

    private fun removeItem(ilst: Box, type: String) {
        ilst.children!!.removeAll { it.type == type }
    }

    private fun u16(bytes: ByteArray, at: Int) = ((bytes[at].toInt() and 0xFF) shl 8) or (bytes[at + 1].toInt() and 0xFF)
}
