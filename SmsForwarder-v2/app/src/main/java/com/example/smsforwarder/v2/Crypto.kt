package com.example.smsforwarder.v2

import android.util.Base64
import com.google.gson.JsonObject
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/** AES-256-CBC encryption for webhook payloads. */
object Crypto {

    private const val ALGORITHM = "AES"
    private const val TRANSFORMATION = "AES/CBC/PKCS5Padding"
    private const val IV_LENGTH = 16

    /** Returns a JSON object: {"encrypted":true,"iv":"...","data":"..."} */
    fun encrypt(plainText: String, key: String): String {
        val keyBytes = MessageDigest.getInstance("SHA-256").digest(key.toByteArray())
        val iv = ByteArray(IV_LENGTH).also { SecureRandom().nextBytes(it) }

        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(keyBytes, ALGORITHM), IvParameterSpec(iv))
        val encrypted = cipher.doFinal(plainText.toByteArray(Charsets.UTF_8))

        val json = JsonObject().apply {
            addProperty("encrypted", true)
            addProperty("iv", Base64.encodeToString(iv, Base64.NO_WRAP))
            addProperty("data", Base64.encodeToString(encrypted, Base64.NO_WRAP))
        }
        return json.toString()
    }
}
