package com.opus.airvia.ap2

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.Charset

/**
 * Minimal binary-plist (bplist00) reader for AirPlay 2 SETUP responses.
 * Reads dict/array/int/real/string/bool/data/date/uid into a small Val tree.
 */
object BplistReader {
    sealed interface Val {
        data class Dict(val map: Map<String, Val>) : Val {
            fun int(key: String): Long? = (map[key] as? IntV)?.v
            fun str(key: String): String? = (map[key] as? Str)?.s
            fun dict(key: String): Dict? = map[key] as? Dict
            fun arr(key: String): Arr? = map[key] as? Arr
            fun bytes(key: String): ByteArray? = (map[key] as? DataV)?.bytes
        }
        data class Arr(val list: List<Val>) : Val
        data class IntV(val v: Long) : Val
        data class RealV(val d: Double) : Val
        data class Str(val s: String) : Val
        data class BoolV(val b: Boolean) : Val
        data class DataV(val bytes: ByteArray) : Val
        object Null : Val
    }

    fun read(bytes: ByteArray): Val {
        require(bytes.size >= 42) { "too short for bplist" }
        require(bytes.copyOfRange(0, 8).toString(Charsets.US_ASCII) == "bplist00") {
            "bad bplist header"
        }
        val bb = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN)
        val trailerOff = bytes.size - 32
        val offsetSize = bytes[trailerOff + 6].toInt() and 0xFF
        val refSize = bytes[trailerOff + 7].toInt() and 0xFF
        bb.position(trailerOff + 8)
        val numObjects = bb.long
        val topRef = bb.long
        val offsetTableOff = bb.long
        require(numObjects in 1..100000) { "implausible object count $numObjects" }

        fun readUInt(off: Int, size: Int): Long {
            var v = 0L
            for (i in 0 until size) v = (v shl 8) or (bytes[off + i].toLong() and 0xFF)
            return v
        }

        fun objOffset(ref: Int): Int =
            readUInt((offsetTableOff + ref * offsetSize).toInt(), offsetSize).toInt()

        fun readRef(at: Int): Int = readUInt(at, refSize).toInt()

        fun readObject(at: Int): Val {
            val marker = bytes[at].toInt() and 0xFF
            val type = marker ushr 4
            var info = marker and 0x0F
            var pos = at + 1
            if (info == 0x0F) {
                val lenObj = readObject(pos)
                info = (lenObj as Val.IntV).v.toInt()
                pos += intObjSize(bytes[pos].toInt() and 0xFF)
            }
            return when (type) {
                0x0 -> when (marker) {
                    0x00, 0x0F -> Val.Null
                    0x08 -> Val.BoolV(false)
                    0x09 -> Val.BoolV(true)
                    else -> throw IllegalArgumentException("bad simple ${marker.toString(16)}")
                }
                0x1 -> { // int: info = log2(size)
                    val size = 1 shl info
                    var v = 0L
                    for (i in 0 until size) v = (v shl 8) or (bytes[pos + i].toLong() and 0xFF)
                    // sign-extend
                    val bits = size * 8
                    Val.IntV(if (bits < 64) (v shl (64 - bits)) shr (64 - bits) else v)
                }
                0x2 -> { // real
                    val size = 1 shl info
                    val buf = ByteBuffer.wrap(bytes, pos, size).order(ByteOrder.BIG_ENDIAN)
                    Val.RealV(if (size == 4) buf.float.toDouble() else buf.double)
                }
                0x3 -> { // date: 8-byte double, seconds since 2001-01-01
                    Val.RealV(ByteBuffer.wrap(bytes, pos, 8).order(ByteOrder.BIG_ENDIAN).double)
                }
                0x4 -> Val.DataV(bytes.copyOfRange(pos, pos + info))
                0x5 -> Val.Str(bytes.copyOfRange(pos, pos + info).toString(Charsets.US_ASCII))
                0x6 -> { // UTF-16BE, info = char count
                    val chars = CharArray(info)
                    val buf = ByteBuffer.wrap(bytes, pos, info * 2).order(ByteOrder.BIG_ENDIAN)
                    for (i in 0 until info) chars[i] = buf.char
                    Val.Str(String(chars))
                }
                0x8 -> { // UID: info+1 bytes
                    Val.DataV(bytes.copyOfRange(pos, pos + info + 1))
                }
                0xA -> {
                    val items = ArrayList<Val>(info)
                    for (i in 0 until info) items.add(readObject(objOffset(readRef(pos + i * refSize))))
                    Val.Arr(items)
                }
                0xD -> {
                    val map = LinkedHashMap<String, Val>(info)
                    for (i in 0 until info) {
                        val k = readObject(objOffset(readRef(pos + i * refSize))) as Val.Str
                        val v = readObject(objOffset(readRef(pos + (info + i) * refSize)))
                        map[k.s] = v
                    }
                    Val.Dict(map)
                }
                else -> throw IllegalArgumentException("bad type ${type.toString(16)}")
            }
        }

        return readObject(objOffset(topRef.toInt()))
    }

    private fun intObjSize(marker: Int): Int {
        require(marker ushr 4 == 0x1) { "length object not an int" }
        return 1 + (1 shl (marker and 0x0F))
    }
}
