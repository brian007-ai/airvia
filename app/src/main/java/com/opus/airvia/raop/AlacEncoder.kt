package com.opus.airvia.raop

/**
 * Verbatim (uncompressed) ALAC frame encoder.
 *
 * Clean-room Kotlin port of the framing algorithm from technicallyalac
 * (https://github.com/Aritile/technicallyalac) by John Regan, released
 * under the BSD Zero Clause license (0BSD). Only verbatim frames are
 * produced — that is all RAOP/AirPlay 1 senders need, and it keeps the
 * encoder tiny and dependency-free.
 *
 * A full 352-sample stereo 16-bit frame encodes to exactly 1415 bytes.
 * Pure Kotlin/JVM — no Android APIs, so it is unit-testable on the JVM.
 */
object AlacEncoder {
    const val FRAME_SAMPLES = 352
    const val CHANNELS = 2
    const val BIT_DEPTH = 16
    const val SAMPLE_RATE = 44100

    /** Encoded byte size of one full 352-sample verbatim stereo frame. */
    const val FRAME_BYTES = 1415

    private class BitWriter(val out: ByteArray) {
        var acc = 0L
        var bits = 0
        var pos = 0

        /** Append [n] bits of [value], most significant bit first. */
        fun add(n: Int, value: Long) {
            require(n in 1..32) { "bit count out of range: $n" }
            val mask = (1L shl n) - 1L
            acc = (acc shl n) or (value and mask)
            bits += n
            flush()
        }

        fun flush() {
            while (bits >= 8 && pos < out.size) {
                bits -= 8
                out[pos++] = ((acc ushr bits) and 0xFF).toByte()
            }
            acc = if (bits == 0) 0L else acc and ((1L shl bits) - 1L)
        }

        fun align() {
            val r = bits % 8
            if (r != 0) add(8 - r, 0)
        }
    }

    private fun encodeChannel(w: BitWriter, samples: ShortArray, count: Int, channel: Int) {
        w.add(3, 0)                                    // channel tag
        w.add(4, channel.toLong())                      // channel number
        w.add(12, 0)                                   // header bits (unused)
        w.add(1, if (count != FRAME_SAMPLES) 1 else 0)  // sample-count flag
        w.add(2, 0)                                    // extra bits (unused)
        w.add(1, 1)                                    // escape: verbatim samples follow
        if (count != FRAME_SAMPLES) w.add(32, count.toLong())
        for (i in 0 until count) {
            w.add(BIT_DEPTH, (samples[i].toInt() and 0xFFFF).toLong())
        }
    }

    /**
     * Encode one stereo frame. [left]/[right] hold [count] samples each
     * ([count] in 1..FRAME_SAMPLES; short final frames are flagged in-band
     * per the ALAC verbatim format).
     */
    fun encodeFrame(left: ShortArray, right: ShortArray, count: Int = FRAME_SAMPLES): ByteArray {
        require(count in 1..FRAME_SAMPLES) { "count out of range: $count" }
        // Short frames add a 32-bit sample count per channel; 8 bytes of
        // slack covers it.
        val w = BitWriter(ByteArray(FRAME_BYTES + 8))
        encodeChannel(w, left, count, 0)
        encodeChannel(w, right, count, 1)
        w.add(3, 7) // ID_END
        w.align()
        w.flush()
        return w.out.copyOf(w.pos)
    }
}
