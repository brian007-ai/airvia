package com.opus.airvia.ap2

/**
 * Uncompressed ALAC frame builder + test-tone generator for the AirPlay 2
 * audio probe.
 *
 * The receiver hardcodes ALAC and ignores ct/audioFormat, so we send
 * uncompressed frames: MSB-first
 *   3b stereo-CPE(=1) · 4b 0 · 12b 0 · 1b hasSize=0 · 2b 0 ·
 *   1b isNotCompressed=1 · 352x{L16,R16} · 3b END(=7) · byte-align
 * which yields 1412 bytes per 352-stereo-frame packet payload.
 */
object AlacFrame {
    const val FRAMES_PER_PACKET = 352
    const val SAMPLE_RATE = 44100

    /** 352 stereo frames (704 interleaved shorts) -> 1412-byte payload. */
    fun buildUncompressed(interleaved: ShortArray): ByteArray {
        require(interleaved.size == FRAMES_PER_PACKET * 2) {
            "need ${FRAMES_PER_PACKET * 2} shorts, got ${interleaved.size}"
        }
        val w = BitWriter()
        w.writeBits(1, 3)   // stereo CPE
        w.writeBits(0, 4)
        w.writeBits(0, 12)
        w.writeBits(0, 1)   // hasSize = 0
        w.writeBits(0, 2)
        w.writeBits(1, 1)   // isNotCompressed = 1
        for (s in interleaved) w.writeBits(s.toInt() and 0xFFFF, 16)
        w.writeBits(7, 3)   // END
        return w.toByteArray()
    }

    /** MSB-first bit writer. */
    class BitWriter {
        private val out = mutableListOf<Byte>()
        private var cur = 0
        private var filled = 0 // bits in cur (0..7)

        fun writeBits(v: Int, n: Int) {
            var value = v
            var left = n
            while (left > 0) {
                val take = minOf(8 - filled, left)
                val shift = left - take
                val chunk = (value ushr shift) and ((1 shl take) - 1)
                cur = (cur shl take) or chunk
                filled += take
                left -= take
                if (filled == 8) {
                    out.add(cur.toByte())
                    cur = 0
                    filled = 0
                }
            }
        }

        fun toByteArray(): ByteArray {
            if (filled > 0) out.add((cur shl (8 - filled)).toByte())
            return out.toByteArray()
        }
    }
}

/** Generates a stereo 16-bit 44.1kHz sine tone, chunked into 352-frame packets. */
object ToneGenerator {
    /**
     * @param freqHz tone frequency
     * @param seconds duration
     * @param amplitude 0..1
     * @return list of 704-short chunks (352 stereo frames each)
     */
    fun sineChunks(freqHz: Double, seconds: Double, amplitude: Double = 0.5): List<ShortArray> {
        val total = (AlacFrame.SAMPLE_RATE * seconds).toInt()
        val chunks = ArrayList<ShortArray>()
        var buf = ShortArray(AlacFrame.FRAMES_PER_PACKET * 2)
        var bi = 0
        for (i in 0 until total) {
            val t = i.toDouble() / AlacFrame.SAMPLE_RATE
            val s = (Math.sin(2.0 * Math.PI * freqHz * t) * amplitude * 32767.0).toInt()
                .coerceIn(-32768, 32767).toShort()
            buf[bi++] = s // L
            buf[bi++] = s // R
            if (bi == buf.size) {
                chunks.add(buf)
                buf = ShortArray(AlacFrame.FRAMES_PER_PACKET * 2)
                bi = 0
            }
        }
        return chunks
    }
}
