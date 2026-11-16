package com.example.smsfrowarder

import com.example.smsfrowarder.model.Destination
import com.example.smsfrowarder.model.ForwardRule
import com.google.gson.JsonObject
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * Sends a message to every destination in every matched rule.
 * Uses a single shared [OkHttpClient] with standard TLS validation.
 */
class ForwardDispatcher(private val client: OkHttpClient = OkHttpClient()) {

    companion object {
        private val JSON_MEDIA_TYPE = "application/json".toMediaTypeOrNull()
    }

    /**
     * Dispatches [text] to all destinations in [rules].
     * Each destination in each rule is attempted independently — a failure in
     * one does not prevent the others from running.
     */
    fun dispatch(text: String, rules: List<ForwardRule>) {
        rules.forEach { rule ->
            try {
                when (val dest = rule.destination) {
                    is Destination.Telegram -> sendTelegram(text, dest)
                    is Destination.Webhook  -> sendWebhook(text, dest)
                }
            } catch (_: Exception) {
                // One rule failing must not block the rest.
            }
        }
    }

    // -------------------------------------------------------------------------

    private fun sendTelegram(text: String, dest: Destination.Telegram) {
        dest.chatIds.split(",")
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .forEach { chatId ->
                val payload = JsonObject().apply {
                    addProperty("text", text)
                    addProperty("parse_mode", "Markdown")
                    addProperty("disable_web_page_preview", false)
                    addProperty("disable_notification", false)
                    addProperty("chat_id", chatId)
                }
                val request = Request.Builder()
                    .url("https://api.telegram.org/bot${dest.botToken}/sendMessage")
                    .post(payload.toString().toRequestBody(JSON_MEDIA_TYPE))
                    .addHeader("accept", "application/json")
                    .addHeader("content-type", "application/json")
                    .build()
                client.newCall(request).execute().close()
            }
    }

    private fun sendWebhook(text: String, dest: Destination.Webhook) {
        val payload = JsonObject().apply {
            addProperty("message", text)
        }
        val builder = Request.Builder()
            .url(dest.url)
            .post(payload.toString().toRequestBody(JSON_MEDIA_TYPE))
            .addHeader("content-type", "application/json")
        if (dest.authHeader.isNotBlank()) {
            builder.addHeader("Authorization", dest.authHeader)
        }
        client.newCall(builder.build()).execute().close()
    }
}
