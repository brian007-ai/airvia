package com.opus.airvia

/**
 * Streaming linear resampler for interleaved stereo PCM16.
 *
 * Fed arbitrary-sized input chunks via [push]; returns every output
 * frame that can be produced so far, keeping the fractional phase and
 * the last unconsumed input frame for the next chunk. Pure JVM (unit
 * tested) — no Android types.
 */
class StreamResampler(srcRate: Int, dstRate: Int) {
    private val step = srcRate.toDouble() / dstRate.toDouble()
    private var pending = ShortArray(0)
    private var phase = 0.0

    /** Push interleaved input samples; returns resampled interleaved output. */
    fun push(input: ShortArray): ShortArray {
        if (input.isEmpty()) return ShortArray(0)
        val all = pending + input
        val frames = all.size / 2
        val out = ArrayList<Short>(frames * 2)
        while (true) {
            val i0 = phase.toInt()
            if (i0 + 1 >= frames) break
            val frac = phase - i0
            val a0 = i0 * 2
            val a1 = a0 + 2
            for (ch in 0..1) {
                val s0 = all[a0 + ch].toDouble()
                val s1 = all[a1 + ch].toDouble()
                out.add((s0 + (s1 - s0) * frac).toInt().toShort())
            }
            phase += step
        }
        val consumed = phase.toInt()
        pending = if (consumed > 0) {
            all.copyOfRange(consumed * 2, frames * 2)
        } else {
            all
        }
        phase -= consumed
        return out.toShortArray()
    }
}
