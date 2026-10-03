package com.opus.airvia

import com.opus.airvia.ap2.Ap2AudioSession
import com.opus.airvia.ap2.Ap2Pairing
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.concurrent.thread

/**
 * Owns the live cast session: transient AirPlay 2 pairing + the AP2
 * audio session fed by a live PCM source (device audio capture).
 *
 * Single speaker at a time (v1). All session logging flows into
 * [LogBus] from the AP2 stack itself; this class adds lifecycle lines.
 */
object CastEngine {
    enum class State { IDLE, CONNECTING, STREAMING, ERROR }

    @Volatile
    var state: State = State.IDLE
        private set

    @Volatile
    var speaker: Speaker? = null
        private set

    @Volatile
    var lastError: String? = null
        private set

    @Volatile
    var volumePct: Int = 100
        private set

    private val listeners = CopyOnWriteArrayList<(State) -> Unit>()
    private var castThread: Thread? = null
    private var session: Ap2AudioSession? = null
    private var established: Ap2Pairing.Established? = null
    private var generation = 0

    val isActive: Boolean
        get() = state == State.CONNECTING || state == State.STREAMING

    fun addListener(l: (State) -> Unit) {
        listeners.add(l)
    }

    fun removeListener(l: (State) -> Unit) {
        listeners.remove(l)
    }

    private fun setState(s: State) {
        state = s
        for (l in listeners) {
            try {
                l(s)
            } catch (_: Exception) {
            }
        }
    }

    /** Start casting [source] to [sp]. Any previous session is stopped. */
    fun start(sp: Speaker, source: Ap2AudioSession.PcmSource) {
        stop()
        val gen = synchronized(this) { ++generation }
        speaker = sp
        lastError = null
        setState(State.CONNECTING)
        LogBus.log("[engine] connecting to '${sp.name}' (${sp.host}:${sp.port})")
        castThread = thread(name = "airvia-cast") {
            var est: Ap2Pairing.Established? = null
            try {
                est = Ap2Pairing.connect(sp.host, sp.port)
                synchronized(this@CastEngine) {
                    if (gen == generation) established = est
                }
                val s = Ap2AudioSession(sp.host, est.localIp, est.channel, est.k64, source) {
                    if (gen == generation) {
                        setState(State.STREAMING)
                        if (volumePct != 100) applyVolume()
                    }
                }
                synchronized(this@CastEngine) {
                    if (gen == generation) session = s
                }
                s.run()
                if (gen == generation) {
                    LogBus.log("[engine] session ended")
                    setState(State.IDLE)
                }
            } catch (e: Exception) {
                if (gen == generation) {
                    lastError = e.message ?: e.javaClass.simpleName
                    LogBus.log("[engine] failed: $lastError")
                    setState(State.ERROR)
                }
            } finally {
                try {
                    est?.socket?.close()
                } catch (_: Exception) {
                }
                synchronized(this@CastEngine) {
                    if (established === est) established = null
                    if (session != null && gen == generation) session = null
                }
            }
        }
    }

    /** Stop the current session (TEARDOWN is sent by the session itself). */
    fun stop() {
        synchronized(this) { generation++ }
        val s = session
        session = null
        s?.requestStop()
        val est = established
        established = null
        // Unblock any in-flight pairing/read promptly.
        try {
            est?.socket?.close()
        } catch (_: Exception) {
        }
        if (state == State.CONNECTING || state == State.STREAMING) {
            setState(State.IDLE)
        }
    }

    /** Set the speaker volume, 0..100 (AirPlay dB mapping: -30..0). */
    fun setVolume(pct: Int) {
        volumePct = pct.coerceIn(0, 100)
        VolumeKeys.updateVolume(volumePct)
        applyVolume()
    }

    private fun applyVolume() {
        val s = session ?: return
        val db = (-30.0 + (volumePct / 100.0) * 30.0).toFloat()
        s.setVolumeDb(db)
    }
}
