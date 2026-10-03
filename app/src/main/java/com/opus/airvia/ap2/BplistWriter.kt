package com.opus.airvia.ap2

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Minimal binary-plist (bplist00) writer for AirPlay 2 SETUP bodies.
 *
 * Supports null, Boolean, Long/Int, Double, String (ASCII or UTF-16),
 * ByteArray (data), List<*>, and Map<String, *> (dict). Dictionary keys are
 * always encoded as strings.
 */
object BplistWriter {

    fun write(top: Any?): ByteArray {
        val enc = Encoder()
        val topRef = enc.encode(top)
        return enc.finish(topRef)
    }

    private class Encoder {
        private val objects = ArrayList<ByteArray>()

        fun encode(v: Any?): Int {
            val obj: ByteArray = when (v) {
                null -> byteArrayOf(0x00)
                is Boolean -> byteArrayOf(if (v) 0x09 else 0x08)
                is Int -> encodeInt(v.toLong())
                is Long -> encodeInt(v)
                is Double -> encodeReal(v)
                is Float -> encodeReal(v.toDouble())
                is String -> encodeString(v)
                is ByteArray -> encodeData(v)
                is List<*> -> return encodeArray(v)
                is Map<*, *> -> return encodeDict(v)
                else -> throw IllegalArgumentException("bplist: unsupported ${v::class}")
            }
            objects.add(obj)
            return objects.size - 1
        }

        private fun encodeInt(v: Long): ByteArray {
            // Match plistlib/Apple convention: negatives are always 8-byte
            // two's complement (1-byte ints read back as unsigned elsewhere).
            val size = if (v < 0) 8 else when {
                v <= 255 -> 1
                v <= 65535 -> 2
                v <= 0xFFFFFFFFL -> 4
                else -> 8
            }
            val out = ByteArrayOutputStream()
            out.write(0x10 or log2(size))
            val bb = ByteBuffer.allocate(8).order(ByteOrder.BIG_ENDIAN).putLong(v)
            out.write(bb.array(), 8 - size, size)
            return out.toByteArray()
        }

        private fun encodeReal(v: Double): ByteArray {
            val out = ByteArrayOutputStream()
            out.write(0x23) // 8-byte double
            out.write(ByteBuffer.allocate(8).order(ByteOrder.BIG_ENDIAN).putDouble(v).array())
            return out.toByteArray()
        }

        private fun encodeString(s: String): ByteArray {
            val ascii = s.all { it.code < 128 }
            val payload = if (ascii) {
                s.toByteArray(Charsets.US_ASCII)
            } else {
                val bb = ByteBuffer.allocate(s.length * 2).order(ByteOrder.BIG_ENDIAN)
                for (c in s) bb.putChar(c)
                bb.array()
            }
            val out = ByteArrayOutputStream()
            // Note: for UTF-16 strings the count is CHARACTERS, not bytes.
            out.write(marker(if (ascii) 0x50 else 0x60, if (ascii) payload.size else s.length))
            out.write(if (ascii) payload else payload.copyOf(s.length * 2))
            return out.toByteArray()
        }

        private fun encodeData(d: ByteArray): ByteArray {
            val out = ByteArrayOutputStream()
            out.write(marker(0x40, d.size))
            out.write(d)
            return out.toByteArray()
        }

        private fun encodeArray(l: List<*>): Int {
            val refs = l.map { encode(it) }
            val out = ByteArrayOutputStream()
            out.write(marker(0xA0, refs.size))
            // refSize is fixed after all objects are known; reserve 1 byte per
            // ref here and patch later — simpler: store now, fix in finish().
            out.write(ByteArray(refs.size)) // placeholder
            val idx = objects.size
            objects.add(out.toByteArray()) // placeholder, patched in finish()
            pendingArrays.add(idx to refs)
            return idx
        }

        private fun encodeDict(m: Map<*, *>): Int {
            val keys = m.keys.map { encode(it.toString()) }
            val vals = m.values.map { encode(it) }
            val out = ByteArrayOutputStream()
            out.write(marker(0xD0, m.size))
            out.write(ByteArray(m.size * 2)) // placeholder refs
            val idx = objects.size
            objects.add(out.toByteArray())
            pendingDicts.add(Triple(idx, keys, vals))
            return idx
        }

        private val pendingArrays = ArrayList<Pair<Int, List<Int>>>()
        private val pendingDicts = ArrayList<Triple<Int, List<Int>, List<Int>>>()

        private fun marker(base: Int, count: Int): ByteArray {
            val out = ByteArrayOutputStream()
            if (count < 15) {
                out.write(base or count)
            } else {
                out.write(base or 0x0F)
                out.write(encodeInt(count.toLong()))
            }
            return out.toByteArray()
        }

        private fun log2(size: Int): Int = when (size) {
            1 -> 0; 2 -> 1; 4 -> 2; 8 -> 3; else -> throw IllegalArgumentException()
        }

        fun finish(topRef: Int): ByteArray {
            // refSize: smallest size that fits all object indexes.
            val refSize = when {
                objects.size <= 256 -> 1
                objects.size <= 65536 -> 2
                else -> 4
            }
            // Patch array/dict placeholders with real refs.
            for ((idx, refs) in pendingArrays) {
                // Rebuild fully (marker + refs).
                val rebuilt = ByteArrayOutputStream()
                rebuilt.write(marker(0xA0, refs.size))
                for (r in refs) writeRef(rebuilt, r, refSize)
                objects[idx] = rebuilt.toByteArray()
            }
            for ((idx, keys, vals) in pendingDicts) {
                val rebuilt = ByteArrayOutputStream()
                rebuilt.write(marker(0xD0, keys.size))
                for (k in keys) writeRef(rebuilt, k, refSize)
                for (v in vals) writeRef(rebuilt, v, refSize)
                objects[idx] = rebuilt.toByteArray()
            }

            val out = ByteArrayOutputStream()
            out.write("bplist00".toByteArray(Charsets.US_ASCII))
            val offsets = IntArray(objects.size)
            for (i in objects.indices) {
                offsets[i] = out.size()
                out.write(objects[i])
            }
            val offsetTableOff = out.size()
            val offsetSize = when {
                offsetTableOff <= 256 -> 1
                offsetTableOff <= 65536 -> 2
                else -> 4
            }
            for (o in offsets) {
                val bb = ByteBuffer.allocate(8).order(ByteOrder.BIG_ENDIAN).putLong(o.toLong())
                out.write(bb.array(), 8 - offsetSize, offsetSize)
            }
            // Trailer: 6 unused, offsetSize, refSize, numObjects(8), top(8), offsetTableOff(8).
            out.write(ByteArray(6))
            out.write(offsetSize)
            out.write(refSize)
            out.write(ByteBuffer.allocate(8).order(ByteOrder.BIG_ENDIAN).putLong(objects.size.toLong()).array())
            out.write(ByteBuffer.allocate(8).order(ByteOrder.BIG_ENDIAN).putLong(topRef.toLong()).array())
            out.write(ByteBuffer.allocate(8).order(ByteOrder.BIG_ENDIAN).putLong(offsetTableOff.toLong()).array())
            return out.toByteArray()
        }

        private fun writeRef(out: ByteArrayOutputStream, ref: Int, refSize: Int) {
            val bb = ByteBuffer.allocate(8).order(ByteOrder.BIG_ENDIAN).putLong(ref.toLong())
            out.write(bb.array(), 8 - refSize, refSize)
        }
    }
}
