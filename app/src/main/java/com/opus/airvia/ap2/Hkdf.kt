package com.opus.airvia.ap2

import java.io.ByteArrayOutputStream
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * HKDF-SHA-512 (RFC 5869). Pure JVM — exercised by the JVM unit tests
 * against Python's `cryptography` reference vectors.
 */
object Hkdf {
    private fun hmacSha512(key: ByteArray, data: ByteArray): ByteArray {
        val mac = Mac.getInstance("HmacSHA512")
        mac.init(SecretKeySpec(key, "HmacSHA512"))
        return mac.doFinal(data)
    }

    /** Extract: PRK = HMAC-SHA512(salt, IKM); empty salt -> 64 zero bytes. */
    fun extract(salt: ByteArray, ikm: ByteArray): ByteArray {
        val s = if (salt.isEmpty()) ByteArray(64) else salt
        return hmacSha512(s, ikm)
    }

    /** Expand: OKM = first [length] bytes of T(1) | T(2) | … */
    fun expand(prk: ByteArray, info: ByteArray, length: Int): ByteArray {
        require(length in 1..255 * 64) { "HKDF length out of range: $length" }
        val out = ByteArrayOutputStream()
        var t = ByteArray(0)
        var counter = 1
        while (out.size() < length) {
            val mac = Mac.getInstance("HmacSHA512")
            mac.init(SecretKeySpec(prk, "HmacSHA512"))
            mac.update(t)
            mac.update(info)
            mac.update(counter.toByte())
            t = mac.doFinal()
            out.write(t)
            counter++
        }
        return out.toByteArray().copyOf(length)
    }

    fun derive(ikm: ByteArray, salt: ByteArray, info: ByteArray, length: Int): ByteArray =
        expand(extract(salt, ikm), info, length)
}
