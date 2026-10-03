package com.opus.airvia.raop

import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * RAOP session cryptography (AirPlay 1 / classic RAOP).
 *
 * Classic RAOP uses a FIXED, well-known AES-128 key/IV pair whose RSA-OAEP
 * wrapping is precomputed once and hard-coded. This is the standard sender
 * trick (used by lox-airplay-sender, node-airplay-sender, node_airtunes and
 * others): every RAOP receiver on earth holds the same AirPort Express
 * private key, so one fixed blob decrypts correctly everywhere. No RSA is
 * performed at runtime.
 *
 * The triple below was verified 2026-09-23: the RSA blob was decrypted with
 * the AirPort Express private key (extracted from shairport's common.c for
 * verification only — never shipped) using RSA-OAEP-SHA1 and recovered
 * exactly the AES key below, which is byte-for-byte identical to the
 * hard-coded aes_key in lox-airplay-sender / node-airplay-sender's
 * encryptAES(). Two independent confirmations.
 *
 * Audio payloads are encrypted with AES-128-CBC/NoPadding. Per the RAOP
 * implementations (shairport player.c / uxplay raop_buffer.c): only
 * floor(len/16)*16 bytes are encrypted, the trailing partial block stays
 * in the clear, the 12-byte RTP header is never encrypted, and the IV is
 * re-initialised from a=aesiv for EVERY packet (CBC state does not chain
 * across packets).
 *
 * Pure JVM (java.util.Base64 / javax.crypto) — no Android APIs.
 */
object RaopCrypto {

    /** Fixed AES-128 session key (hex). */
    private const val AES_KEY_HEX = "14497dcc98e137a855c1455a6bc0c979"

    /** Fixed IV (hex); base64 form is ePRBLI0XN5ArFaaz7ncNZw. */
    private const val AES_IV_HEX = "78f4412c8d1737902b15a6b3ee770d67"

    /**
     * The AES key above, RSA-OAEP-SHA1 wrapped with Apple's well-known
     * AirPort Express 2048-bit public key. Sent verbatim as the SDP
     * a=rsaaeskey line (344 chars = 256-byte ciphertext).
     */
    const val RSA_AES_KEY_B64 =
        "VjVbxWcmYgbBbhwBNlCh3K0CMNtWoB844BuiHGUJT51zQS7SDpMnlbBIobsKbfEJ3SCgWHRXjYWf7VQWRYtEcfx7ejA8xDIk5PSBYTvXP5dU2QoGrSBv0leDS6uxlEWuxBq3lIxCxpWO2YswHYKJBt06Uz9P2Fq2hDUwl3qOQ8oXb0OateTKtfXEwHJMprkhsJsGDrIc5W5NJFMAo6zCiM9bGSDeH2nvTlyW6bfI/Q0v0cDGUNeY3ut6fsoafRkfpCwYId+bg3diJh+uzw5htHDyZ2sN+BFYHzEfo8iv4KDxzeya9llqg6fRNQ8d5YjpvTnoeEQ9ye9ivjkBjcAfVw=="

    /** AES-128 key bytes. */
    fun aesKeyBytes(): ByteArray = hexToBytes(AES_KEY_HEX)

    /** IV bytes. */
    fun aesIvBytes(): ByteArray = hexToBytes(AES_IV_HEX)

    /** IV as base64 (no padding), the form used for a=aesiv. */
    fun aesIvB64(): String = b64(aesIvBytes())

    /** Base64 without padding. */
    fun b64(bytes: ByteArray): String =
        Base64.getEncoder().withoutPadding().encodeToString(bytes)

    /**
     * Encrypt [payload] with AES-128-CBC and no padding using the fixed
     * session key/IV, applied to each complete 16-byte block; any trailing
     * partial block is left in the clear. A fresh cipher is created per
     * call so the IV is re-initialised for every packet — CBC state never
     * chains across packets.
     *
     * The 12-byte RTP header is NOT passed here: it travels in the clear
     * so the receiver can read sequence numbers before decrypting.
     */
    fun encryptPayload(payload: ByteArray): ByteArray {
        val key = aesKeyBytes()
        val iv = aesIvBytes()
        val out = payload.copyOf()
        val full = (out.size / 16) * 16
        if (full == 0) return out
        val cipher = Cipher.getInstance("AES/CBC/NoPadding")
        cipher.init(
            Cipher.ENCRYPT_MODE,
            SecretKeySpec(key, "AES"),
            IvParameterSpec(iv),
        )
        val enc = cipher.doFinal(out, 0, full)
        System.arraycopy(enc, 0, out, 0, enc.size)
        return out
    }

    private fun hexToBytes(hex: String): ByteArray {
        require(hex.length % 2 == 0) { "odd hex length" }
        return ByteArray(hex.length / 2) { i ->
            hex.substring(i * 2, i * 2 + 2).toInt(16).toByte()
        }
    }
}
