package com.opus.airvia

import android.content.Context
import android.media.AudioAttributes
import android.media.VolumeProvider
import android.media.session.MediaSession
import android.os.Handler
import android.os.Looper

/**
 * Routes the hardware volume buttons to the casting speaker from
 * anywhere (lock screen, other apps, screen off) while a cast session
 * is active.
 *
 * Android sends volume keys to the active media session whose playback
 * is "remote" ([MediaSession.setPlaybackToRemote] with a
 * [VolumeProvider]). Airvia's playback lives in a foreground service,
 * so while casting we activate this small dedicated session with an
 * absolute VolumeProvider (0..100) whose callbacks feed [CastEngine]'s
 * volume. Inactive whenever nothing is casting, so the buttons control
 * the phone again as soon as the cast stops. (Same mechanism Outro
 * uses; AirMusic doesn't do this.)
 */
object VolumeKeys {
    private val main = Handler(Looper.getMainLooper())

    @Volatile
    private var session: MediaSession? = null

    @Volatile
    private var provider: VolumeProvider? = null

    /** Set by CastService: media-button stop/pause lands here. */
    @Volatile
    var stopHandler: (() -> Unit)? = null

    fun init(context: Context) {
        if (session != null) return
        val app = context.applicationContext
        main.post {
            if (session != null) return@post
            val vp = object : VolumeProvider(
                VolumeProvider.VOLUME_CONTROL_ABSOLUTE,
                100,
                CastEngine.volumePct,
            ) {
                override fun onSetVolumeTo(volume: Int) {
                    CastEngine.setAllVolumes(volume)
                }

                override fun onAdjustVolume(direction: Int) {
                    if (direction != 0) {
                        CastEngine.stepAllVolumes(direction * 5)
                    }
                }
            }
            val s = MediaSession(app, "airvia-cast-volume")
            s.setCallback(object : MediaSession.Callback() {
                override fun onPause() {
                    stopHandler?.invoke()
                }

                override fun onStop() {
                    stopHandler?.invoke()
                }
            })
            provider = vp
            session = s
        }
    }

    /** Activate/deactivate remote-volume routing with the cast state. */
    fun setCasting(active: Boolean) {
        main.post {
            val s = session ?: return@post
            val vp = provider ?: return@post
            try {
                if (active) {
                    vp.setCurrentVolume(CastEngine.volumePct)
                    s.setPlaybackToRemote(vp)
                    s.isActive = true
                } else {
                    s.isActive = false
                    s.setPlaybackToLocal(
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_MEDIA)
                            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                            .build(),
                    )
                }
            } catch (_: Exception) {
            }
        }
    }

    /** Keep the provider's notion of the current volume in sync. */
    fun updateVolume(percent: Int) {
        try {
            provider?.setCurrentVolume(percent.coerceIn(0, 100))
        } catch (_: Exception) {
        }
    }
}
