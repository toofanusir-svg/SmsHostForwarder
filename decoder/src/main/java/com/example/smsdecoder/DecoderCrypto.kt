package com.example.smsdecoder

import android.util.Base64
import com.google.gson.JsonParser
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

object DecoderCrypto {

    private const val TRANSFORMATION = "AES/CBC/PKCS5Padding"
    private const val ALGORITHM      = "AES"

    /**
     * Decrypts a JSON string produced by the SMS Forwarder app.
     * Expected format: {"encrypted":true,"iv":"<base64>","data":"<base64>"}
     * The outer {"message":"..."} wrapper is handled transparently.
     */
    fun decrypt(input: String, key: String): String {
        val trimmed = input.trim()

        // Unwrap {"message":"..."} if present
        val jsonStr = if (trimmed.startsWith("{")) {
            val obj = JsonParser.parseString(trimmed).asJsonObject
            when {
                obj.has("data") -> trimmed            // already the inner encrypted object
                obj.has("message") -> obj.get("message").asString
                else -> trimmed
            }
        } else trimmed

        // Parse encrypted envelope
        val envelope = JsonParser.parseString(jsonStr).asJsonObject
        val iv       = Base64.decode(envelope.get("iv").asString, Base64.NO_WRAP)
        val data     = Base64.decode(envelope.get("data").asString, Base64.NO_WRAP)

        val keyBytes = MessageDigest.getInstance("SHA-256").digest(key.toByteArray())
        val cipher   = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(keyBytes, ALGORITHM), IvParameterSpec(iv))
        return String(cipher.doFinal(data), Charsets.UTF_8)
    }
}
