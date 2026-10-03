import com.opus.airvia.StreamResampler
import kotlin.math.abs
import kotlin.math.sin

/**
 * JVM self-tests for the 48 kHz -> 44.1 kHz streaming resampler that
 * feeds captured device audio into the AirPlay stream:
 *  - DC signal passes through unchanged, with the right frame count
 *  - a 1 kHz sine stays continuous across awkward chunk boundaries
 *    (a dropped or repeated input frame would show up as a step
 *    roughly twice the sine's maximum legitimate step)
 */
var failures = 0

fun check(name: String, cond: Boolean) {
    if (cond) println("PASS: $name") else {
        println("FAIL: $name")
        failures++
    }
}

fun main() {
    // DC exactness + total duration (0.2 s in -> ~8820 frames out).
    val r1 = StreamResampler(48000, 44100)
    var out1 = ShortArray(0)
    repeat(10) { out1 += r1.push(ShortArray(960 * 2) { 1000 }) }
    check("dc level", out1.all { it == 1000.toShort() })
    check("dc count ~8820 (got ${out1.size / 2})", abs(out1.size / 2 - 8820) <= 3)

    // Sine continuity over odd-sized chunks (1 s of 48 kHz input).
    val r2 = StreamResampler(48000, 44100)
    val totalIn = 48000
    var produced = 0
    var maxJump = 0
    var prev: Short? = null
    var pos = 0
    val chunkSizes = intArrayOf(7, 1920, 333, 4000, 960, 12345)
    var ci = 0
    while (pos < totalIn) {
        val n = minOf(chunkSizes[ci++ % chunkSizes.size], totalIn - pos)
        val buf = ShortArray(n * 2) { i ->
            val frame = pos + i / 2
            (sin(2 * Math.PI * 1000.0 * frame / 48000.0) * 12000).toInt().toShort()
        }
        pos += n
        val o = r2.push(buf)
        produced += o.size / 2
        for (i in o.indices step 2) {
            val v = o[i]
            prev?.let { maxJump = maxOf(maxJump, abs(v - it)) }
            prev = v
        }
    }
    check("sine count ~44100 (got $produced)", abs(produced - 44100) <= 4)
    check("sine continuity (maxJump $maxJump)", maxJump in 1..2000)

    if (failures > 0) {
        println("RESAMPLER TESTS FAILED: $failures")
        kotlin.system.exitProcess(1)
    }
    println("ALL RESAMPLER CHECKS PASSED")
}
