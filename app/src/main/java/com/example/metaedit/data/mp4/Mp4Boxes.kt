package com.example.metaedit.data.mp4

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.nio.ByteBuffer

internal val LATIN1 = Charsets.ISO_8859_1

/**
 * In-memory ISO-BMFF box. Containers we need to walk keep [children]; everything else keeps its
 * raw payload in [data] and is written back byte-for-byte.
 */
internal class Box(val type: String) {
    var data: ByteArray = ByteArray(0)

    /** Version/flags of a full-box container (only `meta`, and only in its ISO form). */
    var prefix: ByteArray = ByteArray(0)
    var children: MutableList<Box>? = null

    val size: Long get() = 8L + prefix.size + (children?.sumOf { it.size } ?: data.size.toLong())

    fun child(type: String) = children?.firstOrNull { it.type == type }

    fun writeTo(out: ByteArrayOutputStream) {
        val total = size
        if (total > 0xFFFFFFFFL) throw IOException("Box $type too large")
        out.write(ByteBuffer.allocate(8).putInt(total.toInt()).put(type.toByteArray(LATIN1)).array())
        out.write(prefix)
        children?.forEach { it.writeTo(out) } ?: out.write(data)
    }

    fun toBytes(): ByteArray = ByteArrayOutputStream(size.toInt()).also { writeTo(it) }.toByteArray()

    companion object {
        private val CONTAINERS = setOf("moov", "trak", "mdia", "minf", "stbl", "udta", "meta", "ilst", "edts", "dinf")

        fun leaf(type: String, data: ByteArray) = Box(type).apply { this.data = data }

        fun container(type: String, vararg kids: Box) = Box(type).apply { children = kids.toMutableList() }

        /** Parses the boxes in [bytes] between [start] and [end], descending into known containers. */
        fun parseChildren(bytes: ByteArray, start: Int, end: Int, descend: Boolean = true): MutableList<Box> {
            val result = mutableListOf<Box>()
            var pos = start
            while (pos + 8 <= end) {
                val buf = ByteBuffer.wrap(bytes, pos, end - pos)
                var size = buf.int.toLong() and 0xFFFFFFFFL
                val type = String(bytes, pos + 4, 4, LATIN1)
                var header = 8
                if (size == 1L) {
                    size = buf.getLong(pos + 8)
                    header = 16
                } else if (size == 0L) {
                    size = (end - pos).toLong()
                }
                if (size < header || pos + size > end) {
                    // Some writers terminate lists with a zero word; anything else is corruption.
                    if (bytes.sliceAllZero(pos, end)) break
                    throw IOException("Malformed '$type' box")
                }
                val boxEnd = (pos + size).toInt()
                val box = Box(type)
                val payloadStart = pos + header
                if (descend && type in CONTAINERS) {
                    var childStart = payloadStart
                    if (type == "meta" && !isQuickTimeMeta(bytes, payloadStart, boxEnd)) {
                        box.prefix = bytes.copyOfRange(payloadStart, payloadStart + 4)
                        childStart += 4
                    }
                    // ilst items are parsed on demand; their children are only data/mean/name.
                    box.children = parseChildren(bytes, childStart, boxEnd, descend = type != "ilst")
                } else {
                    box.data = bytes.copyOfRange(payloadStart, boxEnd)
                }
                result += box
                pos = boxEnd
            }
            return result
        }

        /** QuickTime's `meta` has no version/flags; detect it by a box type right at the start. */
        private fun isQuickTimeMeta(bytes: ByteArray, start: Int, end: Int): Boolean {
            if (end - start < 8) return false
            val firstType = String(bytes, start + 4, 4, LATIN1)
            return firstType == "hdlr" || firstType == "keys" || firstType == "ilst"
        }

        private fun ByteArray.sliceAllZero(from: Int, to: Int) = (from until to).all { this[it] == 0.toByte() }
    }
}
