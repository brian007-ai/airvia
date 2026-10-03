package com.opus.airvia.raop

import com.opus.airvia.LogBus
import java.io.BufferedReader
import java.io.IOException
import java.io.InputStreamReader
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.Socket
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.random.Random

/**
 * One RAOP (AirPlay 1 audio) session to a speaker: RTSP handshake
 * (OPTIONS → ANNOUNCE → SETUP → RECORD), paced RTP carrying verbatim ALAC
 * frames (AES-128-CBC encrypted in RSA/et=1 mode, plain in clear/et=0 mode),
 * timing requests/replies, 1-second sync packets,
 * 15-second OPTIONS heartbeat, volume, FLUSH-pause and TEARDOWN.
 *
 * Protocol details follow the RAOP reverse-engineering notes in
 * docs/raop-sender.md. Timing/sync byte layouts are the standard RAOP
 * reconstruction; they are marked where they must be confirmed against a
 * real HomePod.
 *
 * Threading: all RTSP exchanges are serialized on [rtspLock]. Audio
 * sending, timing, retransmit replies and heartbeat each run on their own
 * thread. Call [teardown] to stop everything.
 */
class RaopConnection(
    private val host: String,
    private val port: Int = 5000,
    private val localIp: String,
    /**
     * True = classic RSA mode (et=1): ANNOUNCE carries a=rsaaeskey/a=aesiv
     * and RTP payloads are AES-128-CBC encrypted. False = clear mode
     * (et=0): no key lines in the SDP and RTP carries plain ALAC frames.
     * Chosen by RaopTransport from the speaker's TXT `et`; offering a mode
     * the speaker didn't advertise gets the ANNOUNCE rejected with 406.
     */
    private val encryptAudio: Boolean = true,
) {
    /** Supplies decoded 44.1 kHz stereo 16-bit PCM frames. */
    interface PcmSource {
        /**
         * Fill [left]/[right]. Returns sample count (> 0), -1 on end of
         * stream, or -2 when no data is available yet (poll again).
         */
        fun readFrame(left: ShortArray, right: ShortArray): Int
    }

    var pcmSource: PcmSource? = null

    // ---- RTSP ----
    // Internal (not private) so unit tests can inject a loopback socket
    // to verify refreshLocalIpFromSocket().
    internal var rtsp: Socket? = null
    private var rtspReader: BufferedReader? = null
    private val rtspLock = Any()
    private var cseq = 0
    private val sessionId = Random.nextInt(1, Int.MAX_VALUE)
    // Sender identity headers every classic RAOP sender (iTunes, lox-airplay-sender,
    // pyatv) puts on every RTSP request. The HomePod rejects handshakes missing them.
    private val dacpId: String = buildString {
        repeat(16) { append("0123456789ABCDEF"[Random.nextInt(16)]) }
    }
    private val activeRemote: Long = Random.nextLong(1, 4294967296L)
    /**
     * The RTSP session id assigned by the receiver in its SETUP response.
     * Null until SETUP succeeds — ANNOUNCE/SETUP must NOT carry a Session header.
     */
    internal var rtspSession: String? = null

    /**
     * The local IP used in the ANNOUNCE request URI and the SDP o=/c= lines.
     * Defaults to the constructor value, but [connect] refreshes it from the
     * actual RTSP socket's local address — the constructor value comes from
     * WifiManager, which returns 0.0.0.0 on Android 10+ without location
     * permission, and an ANNOUNCE to rtsp://0.0.0.0/... is rejected by the
     * receiver. Internal for unit tests.
     */
    internal var effectiveLocalIp: String = localIp

    // ---- RTP ----
    private var audioPort = 0
    private var timingPort = 0
    private var rtpSocket: DatagramSocket? = null
    private var timingSocket: DatagramSocket? = null
    private val ssrc = Random.nextInt()
    private var seq = Random.nextInt(0, 65536)
    private var rtpTime = Random.nextInt()

    private val running = AtomicBoolean(false)
    private val streaming = AtomicBoolean(false)
    private var senderThread: Thread? = null
    private var heartbeatThread: Thread? = null
    private var timingThread: Thread? = null
    private var retransmitThread: Thread? = null

    /** Recent RTP packets by sequence number, for retransmit replies. */
    private val history = LinkedHashMap<Int, ByteArray>()
    private val historyLock = Any()

    private data class RtspResponse(
        val status: Int,
        val headers: Map<String, String>,
        val body: String? = null,
    )

    // ------------------------------------------------------------------
    // RTSP
    // ------------------------------------------------------------------

    private fun readResponse(): RtspResponse {
        val r = rtspReader ?: throw IOException("RTSP not connected")
        val statusLine = r.readLine() ?: throw IOException("RTSP closed by peer")
        val status = statusLine.split(" ").getOrNull(1)?.toIntOrNull()
            ?: throw IOException("bad RTSP status: $statusLine")
        val headers = mutableMapOf<String, String>()
        while (true) {
            val line = r.readLine() ?: break
            if (line.isEmpty()) break
            val i = line.indexOf(':')
            if (i > 0) headers[line.substring(0, i).trim().lowercase()] = line.substring(i + 1).trim()
        }
        // Capture (not just drain) any body — a 406 may carry an explanation.
        val len = headers["content-length"]?.toIntOrNull() ?: 0
        var left = len.toLong()
        val buf = CharArray(1024)
        val bodySb = StringBuilder()
        while (left > 0) {
            val n = r.read(buf, 0, minOf(buf.size.toLong(), left).toInt())
            if (n < 0) break
            bodySb.append(buf, 0, n)
            left -= n
        }
        return RtspResponse(status, headers, bodySb.toString().takeIf { it.isNotEmpty() })
    }

    /** Serialize one RTSP request (pure — no I/O, unit-testable). */
    internal fun buildRtspRequest(
        method: String,
        uri: String,
        headers: Map<String, String> = emptyMap(),
        body: String? = null,
        contentType: String? = null,
    ): String {
        cseq++
        val sb = StringBuilder()
        sb.append(method).append(' ').append(uri).append(" RTSP/1.0\r\n")
        sb.append("CSeq: ").append(cseq).append("\r\n")
        // Standard sender headers, exactly like iTunes / lox-airplay-sender.
        sb.append("User-Agent: ").append(USER_AGENT).append("\r\n")
        sb.append("DACP-ID: ").append(dacpId).append("\r\n")
        sb.append("Active-Remote: ").append(activeRemote).append("\r\n")
        sb.append("Client-Instance: ").append(dacpId).append("\r\n")
        // The receiver assigns the session id in its SETUP response; never
        // send a Session header before that (a bogus one gets ANNOUNCE rejected).
        rtspSession?.let { sb.append("Session: ").append(it).append("\r\n") }
        for ((k, v) in headers) sb.append(k).append(": ").append(v).append("\r\n")
        val bodyBytes = body?.toByteArray(Charsets.UTF_8)
        if (bodyBytes != null) {
            if (contentType != null) sb.append("Content-Type: ").append(contentType).append("\r\n")
            sb.append("Content-Length: ").append(bodyBytes.size).append("\r\n")
        }
        sb.append("\r\n")
        val out = sb.toString().toByteArray(Charsets.UTF_8)
        return String(if (bodyBytes != null) out + bodyBytes else out, Charsets.UTF_8)
    }

    /** Extract the receiver-assigned session id from a SETUP response. */
    internal fun parseSessionId(headers: Map<String, String>): String =
        headers["session"]?.substringBefore(';')?.trim()?.takeIf { it.isNotEmpty() }
            ?: throw IOException("SETUP response missing Session header")

    private fun rtspExchange(
        method: String,
        uri: String,
        headers: Map<String, String> = emptyMap(),
        body: String? = null,
        contentType: String? = null,
    ): RtspResponse {
        synchronized(rtspLock) {
            val sock = rtsp ?: throw IOException("RTSP not connected")
            val request = buildRtspRequest(method, uri, headers, body, contentType)
            LogBus.logBlock("RTSP → $method $uri", request.trimEnd())
            sock.getOutputStream().write(request.toByteArray(Charsets.UTF_8))
            sock.getOutputStream().flush()
            val resp = readResponse()
            val respDump = buildString {
                append("status=${resp.status}")
                for ((k, v) in resp.headers) append("\n  $k: $v")
                if (resp.body != null) append("\n  body: ${resp.body}")
            }
            LogBus.logBlock("RTSP ← $method", respDump)
            return resp
        }
    }

    // Session URI uses the sender's local IP, like iTunes/lox-airplay-sender/pyatv.
    // Uses effectiveLocalIp: the real source IP of the RTSP socket, refreshed
    // in connect() — the constructor value may be 0.0.0.0 (see field docs).
    internal fun sessionUri() = "rtsp://$effectiveLocalIp/$sessionId"

    internal fun buildSdp(): String {
        // Fixed stream format every classic RAOP sender uses: 44.1 kHz,
        // stereo, 16-bit, 352 samples per ALAC frame.
        // Encrypted (et=1) mode carries the well-known fixed AES key/IV
        // triple: a=rsaaeskey is the AES key RSA-OAEP-wrapped with Apple's
        // public key (precomputed once — every receiver holds the same
        // private key), a=aesiv is the fixed IV. Matches lox-airplay-sender,
        // node-airplay-sender and node_airtunes byte-for-byte.
        // Clear (et=0) mode omits BOTH key lines — a receiver treats "both
        // absent" as unencrypted and "exactly one present" as a 456 error,
        // so sending only one of them is worse than sending neither.
        val keyLines = if (encryptAudio) {
            "a=rsaaeskey:${RaopCrypto.RSA_AES_KEY_B64}\r\n" +
                "a=aesiv:${RaopCrypto.aesIvB64()}\r\n"
        } else {
            ""
        }
        return "v=0\r\n" +
            "o=iTunes $sessionId 0 IN IP4 $effectiveLocalIp\r\n" +
            "s=iTunes\r\n" +
            "c=IN IP4 $host\r\n" +
            "t=0 0\r\n" +
            "m=audio 0 RTP/AVP 96\r\n" +
            "a=rtpmap:96 AppleLossless\r\n" +
            "a=fmtp:96 352 0 16 40 10 14 2 255 0 0 44100\r\n" +
            keyLines
    }

    /**
     * Refresh [effectiveLocalIp] from the connected RTSP socket's real local
     * address. The constructor's localIp comes from WifiManager, which on
     * Android 10+ without ACCESS_FINE_LOCATION returns 0.0.0.0 — and an
     * ANNOUNCE to rtsp://0.0.0.0/... is rejected with 406. The socket's own
     * local address is always the true source IP and needs no permission.
     * Internal so unit tests can drive it with a loopback socket.
     */
    internal fun refreshLocalIpFromSocket() {
        val ip = try {
            rtsp?.localAddress?.hostAddress
        } catch (_: Exception) {
            null
        }
        effectiveLocalIp = resolveEffectiveIp(ip)
    }

    /**
     * Pick the IP to use for the ANNOUNCE URI/SDP. Only accepts IPv4: the
     * SDP uses "IN IP4" and classic RAOP is IPv4-only. Rejects blank,
     * "0.0.0.0" (WifiManager failure on Android 10+ without location
     * permission) and IPv6 (dual-stack networks) — keeping the current
     * value in those cases. Pure logic, unit-tested without sockets.
     */
    internal fun resolveEffectiveIp(candidate: String?): String {
        if (!candidate.isNullOrBlank() && candidate != "0.0.0.0" && ":" !in candidate) {
            return candidate
        }
        return effectiveLocalIp
    }

    private fun parseTransport(header: String) {
        // e.g. RTP/AVP/UDP;unicast;interleaved=0-1;mode=record;
        //      server_port=6000;control_port=6001;timing_port=6002
        for (part in header.split(";")) {
            val kv = part.split("=")
            if (kv.size != 2) continue
            when (kv[0].trim()) {
                "server_port" -> audioPort = kv[1].trim().toIntOrNull() ?: 0
                "timing_port" -> timingPort = kv[1].trim().toIntOrNull() ?: 0
            }
        }
        if (audioPort == 0) throw IOException("SETUP response missing server_port")
        // timing_port is optional on some receivers; fall back to audio+1.
        if (timingPort == 0) timingPort = audioPort + 1
    }

    /** Run the RTSP handshake through RECORD. Throws on failure. */
    fun connect() {
        LogBus.log("connect: TCP ${host}:${port}")
        rtsp = Socket(host, port).apply { soTimeout = 12_000 }
        rtspReader = BufferedReader(InputStreamReader(rtsp!!.getInputStream(), Charsets.UTF_8))
        // Use the socket's real source IP for the ANNOUNCE URI/SDP — the
        // constructor value may be 0.0.0.0 (WifiManager on Android 10+
        // without location permission), which the receiver rejects.
        refreshLocalIpFromSocket()
        LogBus.log("connect: localIp=$localIp effectiveLocalIp=$effectiveLocalIp")

        // OPTIONS carries the static Apple-Challenge like iTunes/lox; no Session yet.
        var resp = rtspExchange("OPTIONS", "*", mapOf("Apple-Challenge" to APPLE_CHALLENGE))
        check(resp.status == 200) { "OPTIONS failed: ${resp.status}" }

        // ANNOUNCE: standard sender headers only — no Session (none exists yet)
        // and no Apple-Challenge (lox/pyatv don't send one here).
        resp = rtspExchange(
            "ANNOUNCE", sessionUri(),
            body = buildSdp(), contentType = "application/sdp",
        )
        check(resp.status == 200) { "ANNOUNCE failed: ${resp.status}" }

        // Bind our UDP sockets before SETUP so we can advertise the control
        // and timing ports — the receiver needs them to reach us for timing
        // requests and retransmit requests.
        rtpSocket = DatagramSocket().apply { soTimeout = 500 }
        timingSocket = DatagramSocket().apply { soTimeout = 500 }

        // SETUP: still no Session header — the response assigns it.
        resp = rtspExchange(
            "SETUP", sessionUri(),
            mapOf(
                "Transport" to "RTP/AVP/UDP;unicast;interleaved=0-1;mode=record" +
                    ";control_port=${rtpSocket!!.localPort};timing_port=${timingSocket!!.localPort}",
            ),
        )
        check(resp.status == 200) { "SETUP failed: ${resp.status}" }
        rtspSession = parseSessionId(resp.headers)
        parseTransport(resp.headers["transport"] ?: "")

        // First packet plays 2 s in the future so the receiver can buffer.
        // From here on rtspExchange automatically attaches Session: <id>.
        rtpTime += 88200
        resp = rtspExchange(
            "RECORD", sessionUri(),
            mapOf(
                "Range" to "npt=0-",
                "RTP-Info" to "seq=$seq;rtptime=$rtpTime",
            ),
        )
        check(resp.status == 200) { "RECORD failed: ${resp.status}" }

        running.set(true)
        heartbeatThread = thread("raop-heartbeat") { heartbeatLoop() }
        timingThread = thread("raop-timing") { timingLoop() }
        retransmitThread = thread("raop-retransmit") { retransmitLoop() }
    }

    // ------------------------------------------------------------------
    // Streaming
    // ------------------------------------------------------------------

    /** Start (or restart) the paced RTP sender thread. */
    fun startSender() {
        if (streaming.getAndSet(true)) return
        senderThread = thread("raop-sender") { senderLoop() }
    }

    fun stopSender() {
        streaming.set(false)
        try {
            senderThread?.join(2500)
        } catch (_: InterruptedException) {
        }
        senderThread = null
    }

    private fun senderLoop() {
        // Small preroll so the first packets arrive before their play time.
        var nextNs = System.nanoTime() + 250_000_000L
        val left = ShortArray(AlacEncoder.FRAME_SAMPLES)
        val right = ShortArray(AlacEncoder.FRAME_SAMPLES)
        val addr = try {
            InetAddress.getByName(host)
        } catch (e: Exception) {
            return
        }
        try {
            while (streaming.get()) {
                val n = try {
                    pcmSource?.readFrame(left, right) ?: -1
                } catch (e: Exception) {
                    -1
                }
                if (n == -1) break // end of stream
                if (n == -2) {
                    Thread.sleep(50)
                    continue
                }
                if (n <= 0 || n > AlacEncoder.FRAME_SAMPLES) continue

                val frame = AlacEncoder.encodeFrame(left, right, n)
                // Clear (et=0) mode sends the ALAC frame as-is; RSA (et=1)
                // mode AES-encrypts it with the fixed key/IV from the SDP.
                val enc = if (encryptAudio) RaopCrypto.encryptPayload(frame) else frame
                val pkt = ByteArray(12 + enc.size)
                pkt[0] = 0x80.toByte()
                pkt[1] = 0x60.toByte() // PT 96, no marker
                pkt[2] = (seq ushr 8).toByte()
                pkt[3] = seq.toByte()
                writeU32(pkt, 4, rtpTime)
                writeU32(pkt, 8, ssrc)
                System.arraycopy(enc, 0, pkt, 12, enc.size)
                try {
                    rtpSocket?.send(DatagramPacket(pkt, pkt.size, addr, audioPort))
                } catch (_: Exception) {
                    break
                }

                synchronized(historyLock) {
                    history[seq] = pkt
                    while (history.size > 256) {
                        val it = history.keys.iterator()
                        it.next()
                        it.remove()
                    }
                }
                seq = (seq + 1) and 0xFFFF
                rtpTime += n

                // Pace to real time; never let a stall spiral the schedule.
                nextNs += (n * 1_000_000_000L) / AlacEncoder.SAMPLE_RATE
                val waitMs = (nextNs - System.nanoTime()) / 1_000_000L
                if (waitMs > 0) {
                    Thread.sleep(waitMs)
                } else if (waitMs < -2000) {
                    nextNs = System.nanoTime()
                }
            }
        } catch (_: InterruptedException) {
        } finally {
            streaming.set(false)
        }
    }

    // ------------------------------------------------------------------
    // Timing / sync / heartbeat
    // ------------------------------------------------------------------

    private fun ntpNow(): Long {
        val ms = System.currentTimeMillis()
        val secs = ms / 1000 + 2208988800L // 1900 epoch
        val frac = ((ms % 1000) * 4294967L)
        return (secs shl 32) or frac
    }

    private fun writeU64(buf: ByteArray, off: Int, v: Long) {
        for (i in 0 until 8) buf[off + i] = (v ushr ((7 - i) * 8)).toByte()
    }

    /** Best-effort RAOP timing/sync layout; confirm against a real HomePod. */
    private fun timingLoop() {
        val addr = try {
            InetAddress.getByName(host)
        } catch (e: Exception) {
            return
        }
        val sock = timingSocket ?: return
        var lastSync = 0L
        var lastTimingReq = 0L
        val buf = ByteArray(64)
        try {
            while (running.get()) {
                val now = System.currentTimeMillis()
                if (now - lastSync >= 1000) {
                    lastSync = now
                    // Sync packet: RTP<->NTP mapping, every second.
                    val p = ByteArray(20)
                    p[0] = 0x80.toByte()
                    p[1] = 0xD6.toByte()
                    p[2] = 0x00
                    p[3] = 0x07
                    writeU32(p, 4, rtpTime)
                    writeU64(p, 8, ntpNow())
                    writeU32(p, 16, rtpTime)
                    try {
                        sock.send(DatagramPacket(p, p.size, addr, timingPort))
                    } catch (_: Exception) {
                    }
                }
                if (now - lastTimingReq >= 3000) {
                    lastTimingReq = now
                    // Timing request: originate timestamp at bytes 24-31.
                    val p = ByteArray(32)
                    p[0] = 0x80.toByte()
                    p[1] = 0xD2.toByte()
                    p[2] = 0x00
                    p[3] = 0x07
                    writeU64(p, 24, ntpNow())
                    try {
                        sock.send(DatagramPacket(p, p.size, addr, timingPort))
                    } catch (_: Exception) {
                    }
                }
                // Reply to any timing requests the receiver sends us.
                try {
                    val dp = DatagramPacket(buf, buf.size)
                    sock.receive(dp)
                    if (dp.length >= 32 && buf[0] == 0x80.toByte() && buf[1] == 0xD2.toByte()) {
                        val reply = ByteArray(32)
                        reply[0] = 0x80.toByte()
                        reply[1] = 0xD3.toByte()
                        reply[2] = 0x00
                        reply[3] = 0x07
                        val rx = ntpNow()
                        writeU64(reply, 8, rx) // receive time
                        writeU64(reply, 16, ntpNow()) // transmit time
                        System.arraycopy(buf, 24, reply, 24, 8) // echo originate
                        try {
                            sock.send(DatagramPacket(reply, reply.size, dp.address, dp.port))
                        } catch (_: Exception) {
                        }
                    }
                } catch (_: java.net.SocketTimeoutException) {
                    // poll again
                } catch (_: Exception) {
                    if (!running.get()) break
                }
            }
        } catch (_: InterruptedException) {
        }
    }

    private fun retransmitLoop() {
        val sock = rtpSocket ?: return
        val buf = ByteArray(64)
        try {
            while (running.get()) {
                try {
                    val dp = DatagramPacket(buf, buf.size)
                    sock.receive(dp)
                    // Retransmit request: 0x80 0xD5, first seq + count.
                    if (dp.length >= 8 && buf[0] == 0x80.toByte() && buf[1] == 0xD5.toByte()) {
                        val first = ((buf[4].toInt() and 0xFF) shl 8) or (buf[5].toInt() and 0xFF)
                        val count = ((buf[6].toInt() and 0xFF) shl 8) or (buf[7].toInt() and 0xFF)
                        val addr = dp.address
                        val port = dp.port
                        synchronized(historyLock) {
                            for (i in 0 until minOf(count, 32)) {
                                val pkt = history[(first + i) and 0xFFFF] ?: continue
                                try {
                                    sock.send(DatagramPacket(pkt, pkt.size, addr, port))
                                } catch (_: Exception) {
                                    break
                                }
                            }
                        }
                    }
                } catch (_: java.net.SocketTimeoutException) {
                } catch (_: Exception) {
                    if (!running.get()) break
                }
            }
        } catch (_: InterruptedException) {
        }
    }

    private fun heartbeatLoop() {
        try {
            while (running.get()) {
                Thread.sleep(15_000)
                if (!running.get()) break
                try {
                    // HomePods drop the session without a periodic OPTIONS.
                    // rtspExchange attaches the real Session id automatically.
                    val resp = rtspExchange("OPTIONS", sessionUri())
                    if (resp.status != 200) break
                } catch (_: Exception) {
                    break
                }
            }
        } catch (_: InterruptedException) {
        }
    }

    // ------------------------------------------------------------------
    // Transport control
    // ------------------------------------------------------------------

    /** Pause: stop the sender and flush the receiver's buffer. */
    fun pause() {
        stopSender()
        try {
            rtspExchange(
                "FLUSH", sessionUri(),
                mapOf("RTP-Info" to "seq=$seq;rtptime=$rtpTime"),
            )
        } catch (_: Exception) {
        }
    }

    /** Resume after [pause]: re-RECORD continuing the same seq/rtptime. */
    fun resume() {
        if (!running.get()) return
        try {
            val resp = rtspExchange(
                "RECORD", sessionUri(),
                mapOf(
                    "Range" to "npt=0-",
                    "RTP-Info" to "seq=$seq;rtptime=$rtpTime",
                ),
            )
            check(resp.status == 200) { "RECORD (resume) failed: ${resp.status}" }
            startSender()
        } catch (e: Exception) {
            throw IOException("RAOP resume failed", e)
        }
    }

    /**
     * Set device volume. [percent] 0..100 maps to RAOP's -30 dB..0 dB
     * (0 dB = full volume, -144 dB = mute).
     */
    fun setVolume(percent: Int) {
        if (!running.get()) return
        val p = percent.coerceIn(0, 100)
        val db = -30.0 + (p / 100.0) * 30.0
        try {
            rtspExchange(
                "SET_PARAMETER", sessionUri(),
                body = "volume: %.6f".format(db),
                contentType = "text/parameters",
            )
        } catch (_: Exception) {
        }
    }

    /**
     * Push now-playing text metadata (DMAP). Fields are capped under
     * 128 UTF-8 bytes by [com.opus.airvia.Dmap], so the body survives
     * this connection's UTF-8 String request path byte-exactly.
     * Artwork is not sent on the classic path.
     */
    fun setMetadata(title: String, artist: String, album: String) {
        if (!running.get()) return
        val body = com.opus.airvia.Dmap.metadataBody(title, artist, album)
        if (body.isEmpty()) return
        try {
            rtspExchange(
                "SET_PARAMETER", sessionUri(),
                body = String(body, Charsets.UTF_8),
                contentType = "application/x-dmap-tagged",
            )
        } catch (_: Exception) {
        }
    }

    fun teardown() {
        running.set(false)
        stopSender()
        try {
            rtspExchange("TEARDOWN", sessionUri())
        } catch (_: Exception) {
        }
        heartbeatThread?.interrupt()
        timingThread?.interrupt()
        retransmitThread?.interrupt()
        try {
            heartbeatThread?.join(1500)
            timingThread?.join(1500)
            retransmitThread?.join(1500)
        } catch (_: InterruptedException) {
        }
        try {
            rtsp?.close()
        } catch (_: Exception) {
        }
        try {
            rtpSocket?.close()
        } catch (_: Exception) {
        }
        try {
            timingSocket?.close()
        } catch (_: Exception) {
        }
        rtsp = null
        rtpSocket = null
        timingSocket = null
    }

    private fun thread(name: String, block: () -> Unit): Thread =
        Thread(block, name).apply { isDaemon = true; start() }

    companion object {
        /** Sender User-Agent, byte-identical to lox-airplay-sender's (iTunes 11.3.1). */
        const val USER_AGENT =
            "iTunes/11.3.1 (Windows; Microsoft Windows 10 x64 (Build 19044); x64) (dt:2)"
        /** Static Apple-Challenge sent on OPTIONS, like iTunes/lox (never verified). */
        const val APPLE_CHALLENGE = "SdX9kFJVxgKVMFof/Znj4Q"

        private fun writeU32(buf: ByteArray, off: Int, v: Int) {
            for (i in 0 until 4) buf[off + i] = (v ushr ((3 - i) * 8)).toByte()
        }
    }
}
