package io.github.saschaweiss.glyphpreview.font

import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.nio.ByteBuffer
import java.util.zip.Inflater

/**
 * Decodes a WOFF 1.0 web font into raw sfnt (TrueType/OpenType) bytes that
 * `java.awt.Font.createFont` can read. WOFF is just an sfnt whose tables are
 * (optionally) zlib-compressed, wrapped in a small header.
 *
 * Returns null if the input isn't WOFF 1.0 (e.g. WOFF2, which is a different,
 * Brotli-based format handled separately).
 */
object WoffDecoder {

    private const val WOFF_SIGNATURE = 0x774F4646 // 'wOFF'

    fun decodeToSfnt(woff: ByteArray): ByteArray? = runCatching {
        val buf = ByteBuffer.wrap(woff) // big-endian by default, as WOFF requires
        if (buf.int != WOFF_SIGNATURE) return null

        val flavor = buf.int
        buf.int                                   // length
        val numTables = buf.short.toInt() and 0xFFFF
        buf.short                                 // reserved
        buf.int                                   // totalSfntSize
        buf.short; buf.short                      // major/minor version
        buf.int; buf.int; buf.int                 // meta offset/length/origLength
        buf.int; buf.int                          // priv offset/length

        class Table(val tag: ByteArray, val offset: Int, val compLen: Int, val origLen: Int, val checksum: Int)
        val tables = ArrayList<Table>(numTables)
        repeat(numTables) {
            val tag = ByteArray(4).also { buf.get(it) }
            tables.add(Table(tag, buf.int, buf.int, buf.int, buf.int))
        }

        // Decompress (or copy) each table's data.
        val data = tables.map { t ->
            val slice = woff.copyOfRange(t.offset, t.offset + t.compLen)
            if (t.compLen < t.origLen) inflate(slice, t.origLen) else slice
        }

        // sfnt requires the table directory sorted ascending by tag.
        val order = tables.indices.sortedBy { tagValue(tables[it].tag) }

        val out = ByteArrayOutputStream()
        val dos = DataOutputStream(out)

        val maxPow2 = Integer.highestOneBit(numTables)
        dos.writeInt(flavor)
        dos.writeShort(numTables)
        dos.writeShort(maxPow2 * 16)                                  // searchRange
        dos.writeShort(Integer.numberOfTrailingZeros(maxPow2))        // entrySelector
        dos.writeShort(numTables * 16 - maxPow2 * 16)                 // rangeShift

        var offset = 12 + 16 * numTables
        val offsets = IntArray(numTables)
        order.forEachIndexed { i, idx ->
            offsets[i] = offset
            offset += align4(tables[idx].origLen)
        }

        order.forEachIndexed { i, idx ->
            val t = tables[idx]
            dos.write(t.tag)
            dos.writeInt(t.checksum)
            dos.writeInt(offsets[i])
            dos.writeInt(t.origLen)
        }

        for (idx in order) {
            val d = data[idx]
            dos.write(d)
            repeat(align4(d.size) - d.size) { dos.write(0) }
        }

        dos.flush()
        out.toByteArray()
    }.getOrNull()

    private fun inflate(compressed: ByteArray, origLen: Int): ByteArray {
        val inflater = Inflater()
        inflater.setInput(compressed)
        val out = ByteArray(origLen)
        var total = 0
        while (!inflater.finished() && total < origLen) {
            val n = inflater.inflate(out, total, origLen - total)
            if (n == 0) break
            total += n
        }
        inflater.end()
        return out
    }

    private fun align4(x: Int): Int = (x + 3) and 3.inv()

    private fun tagValue(tag: ByteArray): Long {
        var v = 0L
        for (b in tag) v = (v shl 8) or (b.toLong() and 0xFF)
        return v
    }
}
