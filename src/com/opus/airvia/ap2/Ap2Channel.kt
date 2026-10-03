package com.opus.airvia.ap2

import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

/**
 * Encrypted AirPlay 2 control channel over an already HAP-paired socket.
 *
 * Wire framing (HAP spec §5.2.2, as used by pyatv's HAPSession and verified
 * senders): u16le(length N) | ciphertext(N) | tag(16 bytes), where N is the
 * plaintext length (max 1024 per frame; larger payloads are chunked into
 * consecutive frames). The 2 length bytes are the AEAD additional data.
 * Nonce = 4 zero bytes followed by the u64 little-endian frame counter
 * starting at 0; each direction has its own independent counter.
 *
 * Takes plain streams (not sockets) so the JVM unit tests can exercise the
 * framing over byte arrays.
 */
class Ap2Channel(
    private val input: InputStream,
    private val output: OutputStream,
    writeKey: ByteArray,
    readKey: ByteArray,
) {
    companion object {
        /** Max plaintext bytes per AEAD frame (HAP spec §5.2.2). */
        const val MAX_FRAME = 1024
    }

    private val wKey = writeKey.copyOf()
    private val rKey = readKey.copyOf()
    private var wSeq = 0L
    private var rSeq = 0L

    private fun nonce(seq: Long): ByteArray {
        val n = ByteArray(12) // first 4 bytes stay zero
        for (i in 0..7) n[4 + i] = (seq ushr (8 * i)).toByte()
        return n
    }

    private fun lenBytes(n: Int): ByteArray =
        byteArrayOf((n and 0xFF).toByte(), ((n ushr 8) and 0xFF).toByte())

    @Synchronized
    fun sendFrame(plain: ByteArray) {
        var off = 0
        do {
            val end = minOf(off + MAX_FRAME, plain.size)
            val chunk = plain.copyOfRange(off, end)
            val lb = lenBytes(chunk.size)
            val sealed = ChaCha20Poly1305.seal(wKey, nonce(wSeq++), chunk, lb)
            output.write(lb)
            output.write(sealed)
            off = end
        } while (off < plain.size)
        output.flush()
    }

    /** Reads one frame (probe responses fit in a single frame). */
    fun readFrame(): ByteArray {
        val lb = byteArrayOf(readByte().toByte(), readByte().toByte())
        val n = (lb[0].toInt() and 0xFF) or ((lb[1].toInt() and 0xFF) shl 8)
        require(n <= MAX_FRAME) { "AP2 frame too large: $n" }
        val buf = ByteArray(n + 16)
        readFully(buf)
        val seq = rSeq++
        return ChaCha20Poly1305.open(rKey, nonce(seq), buf, lb)
            ?: throw IOException("AP2 frame tag mismatch (read seq=$seq, len=$n)")
    }

    private fun readByte(): Int {
        val b = input.read()
        if (b < 0) throw EOFException("AP2 channel closed while reading frame")
        return b
    }

    private fun readFully(buf: ByteArray) {
        var off = 0
        while (off < buf.size) {
            val r = input.read(buf, off, buf.size - off)
            if (r < 0) throw EOFException("AP2 channel closed mid-frame")
            off += r
        }
    }
}
