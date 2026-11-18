package com.example.smsdecoder

import android.util.Base64
import com.google.gson.JsonParser
import java.net.URLDecoder
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

object DecoderCrypto {

    private const val TRANSFORMATION = "AES/CBC/PKCS5Padding"
    private const val ALGORITHM      = "AES"

    /**
     * Accepts ANY of these real-world input formats and decrypts:
     *
     * 1. Full JSON object:
     *    {"encrypted":true,"iv":"...","data":"..."}
     *
     * 2. JSON without opening brace (cut off in chat):
     *    "encrypted":true,"iv":"...","data":"..."}
     *    or
     *    "encrypted":true,"iv":"...","data":"..."
     *
     * 3. Wrapped in {"message":"..."}:
     *    {"message":"{\"encrypted\":true,\"iv\":\"...\",\"data\":\"...\"}"}
     *
     * 4. URL-encoded anywhere in the input (e.g. from a web link):
     *    ...%22encrypted%22%3Atrue%2C%22iv%22%3A%22...
     *
     * 5. Any of the above embedded inside a larger string (URL, chat message, etc.)
     *    — the JSON structure is extracted automatically.
     */
    fun extractAndDecrypt(rawInput: String, key: String): String {
        val cleaned = extractJson(rawInput)
        return decryptJson(cleaned, key)
    }

    // ── Step 1: Extract the JSON from whatever the user pasted ───────────────

    private fun extractJson(raw: String): String {
        // Try URL-decode first (handles %22, %3A, etc.)
        val decoded = try { URLDecoder.decode(raw, "UTF-8") } catch (_: Exception) { raw }

        // Work on decoded text
        for (candidate in listOf(decoded, raw)) {
            val result = tryExtract(candidate)
            if (result != null) return result
        }
        throw IllegalArgumentException("No encrypted payload found in the input text.")
    }

    private fun tryExtract(text: String): String? {
        // Strategy 1: find {"encrypted":true,...} as a substring
        val full = findJsonObject(text, "encrypted")
        if (full != null) return full

        // Strategy 2: starts with partial content — missing opening brace
        // Pattern: "encrypted":true,"iv":"...","data":"..."
        val partial = Regex(""""encrypted"\s*:\s*true\s*,\s*"iv"\s*:\s*"([^"]+)"\s*,\s*"data"\s*:\s*"([^"]+)"""")
            .find(text)
        if (partial != null) {
            return """{"encrypted":true,"iv":"${partial.groupValues[1]}","data":"${partial.groupValues[2]}"}"""
        }

        // Strategy 3: {"message":"..."} wrapper containing JSON
        val msgWrapper = findJsonObject(text, "message")
        if (msgWrapper != null) {
            return try {
                val obj = JsonParser.parseString(msgWrapper).asJsonObject
                val inner = obj.get("message")?.asString ?: return null
                tryExtract(inner)
            } catch (_: Exception) { null }
        }

        return null
    }

    /**
     * Finds the first JSON object in [text] that contains [requiredKey].
     * Handles nested braces by counting depth.
     */
    private fun findJsonObject(text: String, requiredKey: String): String? {
        var i = 0
        while (i < text.length) {
            val start = text.indexOf('{', i)
            if (start == -1) break
            var depth = 0
            var end   = start
            var j     = start
            while (j < text.length) {
                when (text[j]) {
                    '{' -> depth++
                    '}' -> { depth--; if (depth == 0) { end = j; break } }
                }
                j++
            }
            if (depth == 0 && end > start) {
                val candidate = text.substring(start, end + 1)
                if (candidate.contains("\"$requiredKey\"")) return candidate
            }
            i = start + 1
        }
        return null
    }

    // ── Step 2: Decrypt the extracted JSON ───────────────────────────────────

    private fun decryptJson(jsonStr: String, key: String): String {
        val envelope = try {
            JsonParser.parseString(jsonStr).asJsonObject
        } catch (e: Exception) {
            throw IllegalArgumentException("Could not parse encrypted payload: ${e.message}")
        }

        val iv   = Base64.decode(
            envelope.get("iv")?.asString   ?: error("Missing 'iv' field"),
            Base64.NO_WRAP
        )
        val data = Base64.decode(
            envelope.get("data")?.asString  ?: error("Missing 'data' field"),
            Base64.NO_WRAP
        )

        val keyBytes = MessageDigest.getInstance("SHA-256").digest(key.toByteArray(Charsets.UTF_8))
        val cipher   = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(keyBytes, ALGORITHM), IvParameterSpec(iv))
        return String(cipher.doFinal(data), Charsets.UTF_8)
    }
}
