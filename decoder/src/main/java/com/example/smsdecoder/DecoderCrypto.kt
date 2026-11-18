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
     * Accepts ANY of these real-world input formats and decrypts.
     *
     * Formats handled:
     *  1. Full JSON: {"encrypted":true,"iv":"...","data":"..."}
     *  2. Partial (missing opening brace): "encrypted":true,"iv":"...","data":"..."}
     *  3. Wrapped: {"message":"{\"encrypted\":true,...}"}
     *  4. URL-encoded anywhere in input
     *  5. JSON embedded inside a larger string (URL, chat message, etc.)
     *  6. Base64 strings with line-breaks / spaces introduced by copy-paste or share
     */
    fun extractAndDecrypt(rawInput: String, key: String): String {
        val jsonStr = extractEncryptedJson(rawInput)
        return decryptJson(jsonStr, key)
    }

    // ── Extraction ────────────────────────────────────────────────────────────

    private fun extractEncryptedJson(raw: String): String {
        // Build a list of candidate strings to try in priority order
        val candidates = mutableListOf<String>()

        // 1. Original input
        candidates += raw.trim()

        // 2. URL-decoded version
        try { candidates += URLDecoder.decode(raw.trim(), "UTF-8") } catch (_: Exception) {}

        // 3. Collapse whitespace/newlines (share can break base64 across lines)
        candidates += raw.replace(Regex("\\s+"), "")
        try { candidates += URLDecoder.decode(raw.replace(Regex("\\s+"), ""), "UTF-8") } catch (_: Exception) {}

        for (candidate in candidates.distinct()) {
            val result = tryExtractFrom(candidate)
            if (result != null) return result
        }

        throw IllegalArgumentException(
            "No encrypted payload found.\n" +
            "Make sure the shared text contains the full encrypted output from SMS Forwarder."
        )
    }

    private fun tryExtractFrom(text: String): String? {
        // Strategy A: find a balanced JSON object containing "encrypted" key
        val fullJson = findJsonObjectContaining(text, "encrypted")
        if (fullJson != null) {
            // Could be the inner envelope or the outer {"message":"..."} wrapper
            return try {
                val obj = JsonParser.parseString(fullJson).asJsonObject
                when {
                    obj.has("data") && obj.has("iv") -> fullJson   // it's the envelope
                    obj.has("message") -> {
                        // unwrap and recurse
                        val inner = obj.get("message").asString
                        tryExtractFrom(inner)
                    }
                    else -> null
                }
            } catch (_: Exception) { null }
        }

        // Strategy B: text missing opening { — reconstruct from key-value pairs
        val partial = extractFromPartialText(text)
        if (partial != null) return partial

        return null
    }

    /**
     * Extracts iv and data values directly from a text that may be missing
     * the outer braces. Handles both unescaped and escaped quotes.
     */
    private fun extractFromPartialText(text: String): String? {
        // Normalise escaped quotes and clean whitespace
        val t = text.replace("\\\"", "\"").replace(Regex("\\s+"), "")

        val ivMatch   = Regex(""""iv"\s*:\s*"([A-Za-z0-9+/=]+)"""").find(t)
        val dataMatch = Regex(""""data"\s*:\s*"([A-Za-z0-9+/=]+)"""").find(t)

        if (ivMatch != null && dataMatch != null) {
            return """{"encrypted":true,"iv":"${ivMatch.groupValues[1]}","data":"${dataMatch.groupValues[1]}"}"""
        }
        return null
    }

    /**
     * Scans [text] for a balanced JSON object that contains [requiredKey].
     * Returns the first match, or null.
     */
    private fun findJsonObjectContaining(text: String, requiredKey: String): String? {
        var i = 0
        while (i < text.length) {
            val start = text.indexOf('{', i)
            if (start == -1) break
            var depth = 0
            var j     = start
            var end   = -1
            while (j < text.length) {
                when (text[j]) {
                    '{' -> depth++
                    '}' -> { depth--; if (depth == 0) { end = j; break } }
                }
                j++
            }
            if (end != -1) {
                val candidate = text.substring(start, end + 1)
                if (candidate.contains("\"$requiredKey\"") || candidate.contains("\\'$requiredKey\\'")) {
                    return candidate
                }
            }
            i = start + 1
        }
        return null
    }

    // ── Decryption ────────────────────────────────────────────────────────────

    private fun decryptJson(jsonStr: String, key: String): String {
        val envelope = try {
            JsonParser.parseString(jsonStr).asJsonObject
        } catch (e: Exception) {
            throw IllegalArgumentException("Could not parse encrypted payload: ${e.message}")
        }

        val ivRaw   = envelope.get("iv")?.asString
            ?: throw IllegalArgumentException("Missing 'iv' field in encrypted payload")
        val dataRaw = envelope.get("data")?.asString
            ?: throw IllegalArgumentException("Missing 'data' field in encrypted payload")

        // Clean base64 strings — remove any whitespace/newlines introduced by copy-paste
        val ivBytes   = Base64.decode(ivRaw.trim().replace(Regex("\\s+"), ""),   Base64.DEFAULT)
        val dataBytes = Base64.decode(dataRaw.trim().replace(Regex("\\s+"), ""), Base64.DEFAULT)

        val keyBytes = MessageDigest.getInstance("SHA-256").digest(key.toByteArray(Charsets.UTF_8))
        val cipher   = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(keyBytes, ALGORITHM), IvParameterSpec(ivBytes))
        return String(cipher.doFinal(dataBytes), Charsets.UTF_8)
    }
}
