package com.example.smsfrowarder

import com.example.smsfrowarder.model.ForwardRule
import com.example.smsfrowarder.model.LogEntry
import com.example.smsfrowarder.model.LogStatus
import com.example.smsfrowarder.model.TelegramDest
import com.example.smsfrowarder.model.WebhookDest
import com.google.gson.JsonObject
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

class ForwardDispatcher(private val logRepo: LogRepository) {

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .writeTimeout(15, TimeUnit.SECONDS)
        .build()

    companion object {
        private val JSON_TYPE = "application/json".toMediaTypeOrNull()
    }

    /**
     * Dispatches [text] to every destination in every matched rule.
     * Creates a [LogEntry] per (rule × destination) and updates status after each attempt.
     * Returns all created log entries.
     */
    fun dispatch(
        text: String,
        sender: String,
        rules: List<ForwardRule>
    ): List<LogEntry> {
        val entries = mutableListOf<LogEntry>()

        rules.forEach { rule ->
            rule.telegram?.let { tg ->
                val entry = createEntry(sender, text, rule, "Telegram (${tg.chatIds})")
                logRepo.add(entry)
                entries += entry
                trySend(entry) { sendTelegram(text, tg) }
            }
            rule.webhook?.let { wh ->
                val entry = createEntry(sender, text, rule, "Webhook (${wh.url.take(40)})")
                logRepo.add(entry)
                entries += entry
                trySend(entry) { sendWebhook(text, wh) }
            }
        }
        return entries
    }

    /**
     * Retries a single log entry. Used by the log screen's retry button.
     */
    fun retry(entry: LogEntry, rules: List<ForwardRule>) {
        val rule = rules.find { it.id == entry.ruleId } ?: run {
            logRepo.updateStatus(entry.id, LogStatus.FAILED, "Rule no longer exists")
            return
        }
        // Re-attempt the right destination based on the label stored in the entry
        if (entry.destination.startsWith("Telegram")) {
            rule.telegram?.let { trySend(entry) { sendTelegram(entry.fullMessage, it) } }
        } else {
            rule.webhook?.let { trySend(entry) { sendWebhook(entry.fullMessage, it) } }
        }
    }

    // -------------------------------------------------------------------------

    private fun trySend(entry: LogEntry, block: () -> Unit) {
        try {
            block()
            logRepo.updateStatus(entry.id, LogStatus.SUCCESS)
        } catch (e: Exception) {
            logRepo.updateStatus(entry.id, LogStatus.FAILED, e.message ?: "Unknown error")
        }
    }

    private fun createEntry(
        sender: String,
        fullMessage: String,
        rule: ForwardRule,
        destination: String
    ) = LogEntry(
        sender        = sender,
        messagePreview = fullMessage.take(80),
        ruleName      = rule.name,
        destination   = destination,
        fullMessage   = fullMessage,
        ruleId        = rule.id
    )

    // -------------------------------------------------------------------------

    private fun sendTelegram(text: String, dest: TelegramDest) {
        dest.chatIds.split(",").map { it.trim() }.filter { it.isNotEmpty() }.forEach { chatId ->
            val payload = JsonObject().apply {
                addProperty("text", text)
                addProperty("parse_mode", "Markdown")
                addProperty("disable_web_page_preview", false)
                addProperty("disable_notification", false)
                addProperty("chat_id", chatId)
            }
            val req = Request.Builder()
                .url("https://api.telegram.org/bot${dest.botToken}/sendMessage")
                .post(payload.toString().toRequestBody(JSON_TYPE))
                .addHeader("accept", "application/json")
                .addHeader("content-type", "application/json")
                .build()
            val resp = client.newCall(req).execute()
            val body = resp.body?.string() ?: ""
            resp.close()
            if (!resp.isSuccessful) throw Exception("HTTP ${resp.code}: $body")
        }
    }

    private fun sendWebhook(text: String, dest: WebhookDest) {
        val payload = JsonObject().apply { addProperty("message", text) }
        val builder = Request.Builder()
            .url(dest.url)
            .post(payload.toString().toRequestBody(JSON_TYPE))
            .addHeader("content-type", "application/json")
        if (dest.authHeader.isNotBlank()) builder.addHeader("Authorization", dest.authHeader)
        val resp = client.newCall(builder.build()).execute()
        val body = resp.body?.string() ?: ""
        resp.close()
        if (!resp.isSuccessful) throw Exception("HTTP ${resp.code}: $body")
    }
}
