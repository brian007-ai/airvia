package com.opus.airvia.ap2

import java.io.ByteArrayOutputStream
import java.math.BigInteger

/**
 * ChaCha20-Poly1305 AEAD (RFC 8439 §2.8), hand-rolled.
 *
 * minSdk is 26 and javax.crypto only gained "ChaCha20-Poly1305" on API 28,
 * so the cipher ships in-app. Pure JVM — verified against the RFC 8439
 * Appendix A.5 test vectors and Python's `cryptography` implementation.
 */
object ChaCha20Poly1305 {
    // ------------------------------------------------------------------
    // ChaCha20 block function (RFC 8439 §2.3)
    // ------------------------------------------------------------------
    private fun rotl(v: Int, n: Int): Int = (v shl n) or (v ushr (32 - n))

    private fun quarterRound(x: IntArray, a: Int, b: Int, c: Int, d: Int) {
        x[a] += x[b]; x[d] = rotl(x[d] xor x[a], 16)
        x[c] += x[d]; x[b] = rotl(x[b] xor x[c], 12)
        x[a] += x[b]; x[d] = rotl(x[d] xor x[a], 8)
        x[c] += x[d]; x[b] = rotl(x[b] xor x[c], 7)
    }

    private fun le32(b: ByteArray, off: Int): Int =
        (b[off].toInt() and 0xFF) or
            ((b[off + 1].toInt() and 0xFF) shl 8) or
            ((b[off + 2].toInt() and 0xFF) shl 16) or
            ((b[off + 3].toInt() and 0xFF) shl 24)

    private fun putLe32(out: ByteArray, off: Int, v: Int) {
        out[off] = v.toByte()
        out[off + 1] = (v ushr 8).toByte()
        out[off + 2] = (v ushr 16).toByte()
        out[off + 3] = (v ushr 24).toByte()
    }

    private fun le64(v: Long): ByteArray = ByteArray(8) { i -> (v ushr (8 * i)).toByte() }

    /** 64-byte ChaCha20 keystream block for (key, 12-byte nonce, counter). */
    fun chachaBlock(key: ByteArray, nonce12: ByteArray, counter: Int): ByteArray {
        require(key.size == 32) { "key must be 32 bytes" }
        require(nonce12.size == 12) { "nonce must be 12 bytes" }
        val st = IntArray(16)
        st[0] = 0x61707865; st[1] = 0x3320646E; st[2] = 0x79622d32; st[3] = 0x6b206574
        for (i in 0..7) st[4 + i] = le32(key, i * 4)
        st[12] = counter
        for (i in 0..2) st[13 + i] = le32(nonce12, i * 4)
        val w = st.copyOf()
        repeat(10) {
            quarterRound(w, 0, 4, 8, 12)
            quarterRound(w, 1, 5, 9, 13)
            quarterRound(w, 2, 6, 10, 14)
            quarterRound(w, 3, 7, 11, 15)
            quarterRound(w, 0, 5, 10, 15)
            quarterRound(w, 1, 6, 11, 12)
            quarterRound(w, 2, 7, 8, 13)
            quarterRound(w, 3, 4, 9, 14)
        }
        val out = ByteArray(64)
        for (i in 0..15) putLe32(out, i * 4, w[i] + st[i])
        return out
    }

    // ------------------------------------------------------------------
    // Poly1305 (RFC 8439 §2.5); BigInteger math — messages here are tiny.
    // ------------------------------------------------------------------
    private val P130 = BigInteger.ONE.shiftLeft(130).subtract(BigInteger.valueOf(5))
    private val R_MASK = BigInteger("0ffffffc0ffffffc0ffffffc0fffffff", 16)
    private val TAG_MASK = BigInteger("ffffffffffffffffffffffffffffffff", 16)

    private fun fromLe(b: ByteArray): BigInteger =
        BigInteger(1, b.reversedArray())

    private fun toLe16(x: BigInteger): ByteArray {
        val raw = x.toByteArray()
        val mag = if (raw.isNotEmpty() && raw[0] == 0.toByte()) {
            raw.copyOfRange(1, raw.size)
        } else {
            raw
        }
        require(mag.size <= 16)
        return (ByteArray(16 - mag.size) + mag).reversedArray()
    }

    fun poly1305Mac(msg: ByteArray, key32: ByteArray): ByteArray {
        require(key32.size == 32)
        val r = fromLe(key32.copyOfRange(0, 16)).and(R_MASK)
        val s = fromLe(key32.copyOfRange(16, 32))
        var acc = BigInteger.ZERO
        var off = 0
        while (off < msg.size) {
            val chunk = minOf(16, msg.size - off)
            val n = fromLe(msg.copyOfRange(off, off + chunk))
                .or(BigInteger.ONE.shiftLeft(chunk * 8))
            acc = acc.add(n).multiply(r).mod(P130)
            off += chunk
        }
        return toLe16(acc.add(s).and(TAG_MASK))
    }

    private fun macData(aad: ByteArray, ct: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        fun writePadded(b: ByteArray) {
            out.write(b)
            val r = b.size % 16
            if (r != 0) out.write(ByteArray(16 - r))
        }
        writePadded(aad)
        writePadded(ct)
        out.write(le64(aad.size.toLong()))
        out.write(le64(ct.size.toLong()))
        return out.toByteArray()
    }

    // ------------------------------------------------------------------
    // AEAD (RFC 8439 §2.8)
    // ------------------------------------------------------------------
    private fun streamXor(key: ByteArray, nonce12: ByteArray, input: ByteArray): ByteArray {
        val out = ByteArray(input.size)
        var off = 0
        var counter = 1
        while (off < input.size) {
            val ks = chachaBlock(key, nonce12, counter++)
            val n = minOf(64, input.size - off)
            for (i in 0 until n) out[off + i] = (input[off + i].toInt() xor ks[i].toInt()).toByte()
            off += n
        }
        return out
    }

    /** AEAD seal; returns ciphertext || 16-byte tag. */
    fun seal(
        key: ByteArray,
        nonce12: ByteArray,
        plaintext: ByteArray,
        aad: ByteArray = ByteArray(0),
    ): ByteArray {
        val otk = chachaBlock(key, nonce12, 0).copyOfRange(0, 32)
        val ct = streamXor(key, nonce12, plaintext)
        val tag = poly1305Mac(macData(aad, ct), otk)
        return ct + tag
    }

    /** AEAD open; returns plaintext, or null when the tag is invalid. */
    fun open(
        key: ByteArray,
        nonce12: ByteArray,
        ctAndTag: ByteArray,
        aad: ByteArray = ByteArray(0),
    ): ByteArray? {
        if (ctAndTag.size < 16) return null
        val ct = ctAndTag.copyOfRange(0, ctAndTag.size - 16)
        val tag = ctAndTag.copyOfRange(ctAndTag.size - 16, ctAndTag.size)
        val otk = chachaBlock(key, nonce12, 0).copyOfRange(0, 32)
        val calc = poly1305Mac(macData(aad, ct), otk)
        var diff = 0
        for (i in 0 until 16) diff = diff or (calc[i].toInt() xor tag[i].toInt())
        if (diff != 0) return null
        return streamXor(key, nonce12, ct)
    }
}
