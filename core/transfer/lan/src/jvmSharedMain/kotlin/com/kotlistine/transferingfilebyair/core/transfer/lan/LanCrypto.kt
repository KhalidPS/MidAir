package com.kotlistine.transferingfilebyair.core.transfer.lan

import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.spec.ECGenParameterSpec
import java.security.spec.X509EncodedKeySpec
import javax.crypto.Cipher
import javax.crypto.KeyAgreement
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * The link's cryptography, from what both Android (API 26+) and every desktop JVM have: ECDH on P-256 for a fresh
 * secret per connection, HKDF-SHA256 to derive keys from it, AES-256-GCM for every frame, HMAC-SHA256 for proofs.
 */
internal object LanCrypto {
    private val random = SecureRandom()

    fun random(size: Int): ByteArray = ByteArray(size).also(random::nextBytes)

    fun newKeyPair(): KeyPair = KeyPairGenerator.getInstance("EC").apply {
        initialize(ECGenParameterSpec("secp256r1"), random)
    }.generateKeyPair()

    /** The secret both ends get from their own key pair and the other's public key. */
    fun agree(own: KeyPair, peerPublicKey: ByteArray): ByteArray {
        val peer = KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(peerPublicKey))
        return KeyAgreement.getInstance("ECDH").run {
            init(own.private)
            doPhase(peer, true)
            generateSecret()
        }
    }

    fun sha256(vararg parts: ByteArray): ByteArray =
        MessageDigest.getInstance("SHA-256").run {
            parts.forEach(::update)
            digest()
        }

    fun hmac(key: ByteArray, vararg parts: ByteArray): ByteArray =
        Mac.getInstance("HmacSHA256").run {
            init(SecretKeySpec(key, "HmacSHA256"))
            parts.forEach(::update)
            doFinal()
        }

    /** HKDF (RFC 5869) for one 32-byte key: extract with [salt], expand with [info]. */
    fun deriveKey(secret: ByteArray, salt: ByteArray, info: String): ByteArray {
        val pseudoRandomKey = hmac(salt, secret)
        return hmac(pseudoRandomKey, info.encodeToByteArray(), byteArrayOf(1))
    }

    /** Compares two MACs in constant time. */
    fun same(a: ByteArray, b: ByteArray): Boolean = MessageDigest.isEqual(a, b)
}

private const val GcmTagBits = 128
private const val NonceSize = 12

/**
 * Seals or opens the frames going one way. Each direction has its own key, so a counter is a safe nonce: no frame is
 * ever sealed twice under the same key and nonce, and a frame dropped, replayed or reordered fails to open.
 */
internal class FrameCipher(key: ByteArray, private val mode: Int) {
    private val key = SecretKeySpec(key, "AES")
    private val cipher = Cipher.getInstance("AES/GCM/NoPadding")
    private var counter = 0L

    fun apply(bytes: ByteArray): ByteArray {
        val nonce = ByteArray(NonceSize)
        var value = counter++
        for (index in NonceSize - 1 downTo NonceSize - 8) {
            nonce[index] = value.toByte()
            value = value ushr 8
        }
        cipher.init(mode, key, GCMParameterSpec(GcmTagBits, nonce))
        return cipher.doFinal(bytes)
    }

    companion object {
        fun sealing(key: ByteArray) = FrameCipher(key, Cipher.ENCRYPT_MODE)
        fun opening(key: ByteArray) = FrameCipher(key, Cipher.DECRYPT_MODE)
    }
}
