package com.opus.airvia

import android.content.Context
import android.media.AudioManager
import android.os.Handler
import android.os.Looper

/**
 * Keeps the PHONE silent while a cast is running, without touching the
 * level sent to the speaker.
 *
 * Why this is safe (researched 2026-10-03): MediaProjection
 * AudioPlaybackCapture is PRE-FADER — the captured PCM level does not
 * depend on the STREAM_MUSIC volume. Verified empirically by the
 * centuryplay project (identical captured level at media-volume index
 * 10, 1 and 0; their conclusion: "keep phone silent can set media
 * volume to 0 while streaming") and by headphone-for-all ("turning the
 * sender's own volume down or muting it is safe: the capture does not
 * depend on it"). So zeroing STREAM_MUSIC silences the phone speaker
 * while the cast stream keeps the full captured level; the speaker's
 * own volume stays under [CastEngine]/[VolumeKeys] control as before.
 *
 * Design:
 *  - [engage] saves the current STREAM_MUSIC index (persisted FIRST,
 *    so a process death can be healed later) and zeroes the stream.
 *  - A guard re-zeroes every 2 s while engaged, in case a volume key
 *    leaks to the phone stream (our remote-volume session not chosen
 *    by the system) or an app raises it programmatically.
 *  - [release] restores the saved index. Every shutdown path in
 *    CastService funnels through teardown, which calls release.
 *  - [healIfStale] runs on app/service start: if a previous run died
 *    while engaged (ColorOS battery kill, crash), its persisted index
 *    is restored — but only when the stream is still at 0; a volume
 *    the user has since chosen themselves is respected.
 *
 * Only STREAM_MUSIC is touched: ringer, alarm and call volumes are
 * left alone.
 */
object PhoneSilencer {
    private const val GUARD_MS = 2000L

    @Volatile
    private var engaged = false

    private var savedIndex = -1
    private var audioManager: AudioManager? = null
    private val handler = Handler(Looper.getMainLooper())

    private val guard = object : Runnable {
        override fun run() {
            if (!engaged) return
            try {
                val am = audioManager
                if (am != null &&
                    am.getStreamVolume(AudioManager.STREAM_MUSIC) != 0
                ) {
                    am.setStreamVolume(AudioManager.STREAM_MUSIC, 0, 0)
                    LogBus.log(
                        "[silencer] re-zeroed phone media volume " +
                            "(something raised it while casting)",
                    )
                }
            } catch (_: Exception) {
            }
            handler.postDelayed(this, GUARD_MS)
        }
    }

    /** Save + zero STREAM_MUSIC. Idempotent while engaged. */
    @Synchronized
    fun engage(ctx: Context) {
        if (engaged) return
        val am = ctx.getSystemService(AudioManager::class.java) ?: return
        audioManager = am
        // If a previous run died mid-cast, heal its stale save FIRST,
        // so the index we save now is the user's real pre-cast volume,
        // not the 0 the dead run left behind.
        healStaleLocked(ctx, am)
        savedIndex = try {
            am.getStreamVolume(AudioManager.STREAM_MUSIC)
        } catch (_: Exception) {
            -1
        }
        if (savedIndex < 0) return
        Prefs.setSilencerSaved(ctx, savedIndex)
        try {
            am.setStreamVolume(AudioManager.STREAM_MUSIC, 0, 0)
        } catch (_: Exception) {
        }
        engaged = true
        handler.postDelayed(guard, GUARD_MS)
        LogBus.log(
            "[silencer] phone media volume saved ($savedIndex) " +
                "and zeroed while casting",
        )
    }

    /** Restore the saved STREAM_MUSIC index. Safe to call when idle. */
    @Synchronized
    fun release(ctx: Context) {
        if (!engaged) return
        engaged = false
        handler.removeCallbacks(guard)
        val am = audioManager ?: ctx.getSystemService(AudioManager::class.java)
        if (am != null && savedIndex >= 0) {
            try {
                am.setStreamVolume(AudioManager.STREAM_MUSIC, savedIndex, 0)
            } catch (_: Exception) {
            }
            LogBus.log("[silencer] phone media volume restored to $savedIndex")
        }
        savedIndex = -1
        audioManager = null
        Prefs.clearSilencerSaved(ctx)
    }

    /** Heal a save left behind by a process that died while engaged. */
    fun healIfStale(ctx: Context) {
        if (engaged) return
        val am = ctx.getSystemService(AudioManager::class.java) ?: return
        // Re-check inside the lock: engage() may have started between
        // the check above and here — a live cast's save is not stale.
        synchronized(this) { if (!engaged) healStaleLocked(ctx, am) }
    }

    private fun healStaleLocked(ctx: Context, am: AudioManager) {
        val stale = Prefs.silencerSaved(ctx) ?: return
        try {
            if (am.getStreamVolume(AudioManager.STREAM_MUSIC) == 0 && stale > 0) {
                am.setStreamVolume(AudioManager.STREAM_MUSIC, stale, 0)
                LogBus.log("[silencer] healed stale saved volume -> $stale")
            }
        } catch (_: Exception) {
        }
        Prefs.clearSilencerSaved(ctx)
    }
}
