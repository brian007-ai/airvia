package com.opus.airvia

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.projection.MediaProjection
import com.opus.airvia.ap2.Ap2AudioSession
import kotlin.concurrent.thread

/**
 * Live device-audio source for the AP2 session.
 *
 * Captures the phone's playback mix (AudioPlaybackCapture, Android 10+,
 * gated by the user's MediaProjection consent) at 48 kHz stereo, resamples
 * to the 44.1 kHz the AirPlay stream runs at, and hands the session
 * 352-frame chunks through a small ring buffer. When the ring runs dry
 * (capture hiccup), [nextInterleaved] returns null and the session waits
 * — the same live-source contract Outro's decoder pipeline uses.
 *
 * Platform limits (by Android design): apps that opt out of playback
 * capture and DRM-protected output are silent here; voice-call audio is
 * never captured.
 */
class CaptureSource(private val projection: MediaProjection) : Ap2AudioSession.PcmSource {
    companion object {
        const val SRC_RATE = 48000
        const val DST_RATE = 44100
        const val CHUNK_FRAMES = 352
    }

    @Volatile
    override var finished: Boolean = false
        private set

    private var record: AudioRecord? = null
    private var reader: Thread? = null
    private val resampler = StreamResampler(SRC_RATE, DST_RATE)

    // Ring of resampled interleaved samples (~3 s at 44.1 kHz stereo).
    private val ring = ShortArray(DST_RATE * 2 * 3)
    private var ringHead = 0
    private var ringCount = 0
    private val ringLock = Object()

    fun start() {
        val config = AudioPlaybackCaptureConfiguration.Builder(projection)
            .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
            .addMatchingUsage(AudioAttributes.USAGE_GAME)
            .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
            .build()
        val format = AudioFormat.Builder()
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .setSampleRate(SRC_RATE)
            .setChannelMask(AudioFormat.CHANNEL_IN_STEREO)
            .build()
        val minBuf = AudioRecord.getMinBufferSize(
            SRC_RATE, AudioFormat.CHANNEL_IN_STEREO, AudioFormat.ENCODING_PCM_16BIT,
        )
        val rec = AudioRecord.Builder()
            .setAudioFormat(format)
            .setBufferSizeInBytes(maxOf(minBuf, SRC_RATE / 5 * 4))
            .setAudioPlaybackCaptureConfig(config)
            .build()
        check(rec.state == AudioRecord.STATE_INITIALIZED) {
            "AudioRecord failed to initialize (capture unsupported?)"
        }
        record = rec
        rec.startRecording()
        reader = thread(name = "airvia-capture") { readLoop(rec) }
        LogBus.log("[capture] device audio capture live: ${SRC_RATE}Hz -> ${DST_RATE}Hz")
    }

    private fun readLoop(rec: AudioRecord) {
        val buf = ShortArray(1920) // 960 frames = 20 ms at 48 kHz
        while (!finished) {
            val n = try {
                rec.read(buf, 0, buf.size)
            } catch (_: Exception) {
                break
            }
            if (n < 0) {
                LogBus.log("[capture] read error $n")
                break
            }
            if (n == 0) continue
            val out = resampler.push(buf.copyOf(n))
            if (out.isNotEmpty()) ringPush(out)
        }
        LogBus.log("[capture] reader stopped")
    }

    private fun ringPush(samples: ShortArray) {
        synchronized(ringLock) {
            for (s in samples) {
                if (ringCount == ring.size) {
                    // Full: drop the oldest sample to stay near-live.
                    ringHead = (ringHead + 1) % ring.size
                    ringCount--
                }
                ring[(ringHead + ringCount) % ring.size] = s
                ringCount++
            }
        }
    }

    override fun nextInterleaved(): ShortArray? {
        synchronized(ringLock) {
            if (ringCount < CHUNK_FRAMES * 2) return null
            val out = ShortArray(CHUNK_FRAMES * 2)
            for (i in out.indices) {
                out[i] = ring[ringHead]
                ringHead = (ringHead + 1) % ring.size
            }
            ringCount -= out.size
            return out
        }
    }

    fun stop() {
        finished = true
        try {
            record?.stop()
        } catch (_: Exception) {
        }
        try {
            reader?.join(300)
        } catch (_: Exception) {
        }
        try {
            record?.release()
        } catch (_: Exception) {
        }
        record = null
        reader = null
    }
}
