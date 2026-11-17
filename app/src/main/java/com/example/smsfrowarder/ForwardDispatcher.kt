package com.example.smsfrowarder

import com.example.smsfrowarder.model.ForwardRule
import com.example.smsfrowarder.model.LogEntry
import com.example.smsfrowarder.model.LogStatus
import com.example.smsfrowarder.model.TelegramDest
import com.example.smsfrowarder.model.WebhookDest
import com.google.gson.JsonObject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

class ForwardDispatcher(private val logRepo: LogRepository) {

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .writeTimeout(10, TimeUnit.SECONDS)
        .build()

    /** Scope for parallel dispatch — one coroutine per rule. */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    companion object {
        private val JSON_TYPE = "application/json".toMediaTypeOrNull()
    }

    /**
     * Dispatches [text] to every destination in every matched rule.
     * Each rule runs in its OWN coroutine so a slow/failing rule never
     * blocks the others. All rules are checked independently.
     */
    suspend fun dispatch(
        text: String,
        sender: String,
        rules: List<ForwardRule>
    ): List<LogEntry> {
        val entries = mutableListOf<LogEntry>()

        // Create all log entries up front
        rules.forEach { rule ->
            rule.telegram?.let { tg ->
                val entry = createEntry(sender, text, rule, "Telegram (${tg.chatIds})")
                logRepo.add(entry)
                entries += entry
            }
            rule.webhook?.let { wh ->
                val entry = createEntry(sender, text, rule, "Webhook (${wh.url.take(40)})")
                logRepo.add(entry)
                entries += entry
            }
        }

        // Fire each entry in parallel — no rule blocks another
        entries.map { entry ->
            scope.async {
                val rule = rules.find { it.id == entry.ruleId } ?: return@async
                trySend(entry, text, rule)
            }
        }.awaitAll()

        return entries
    }

    /**
     * Synchronous variant for single-entry retries from the log screen.
     */
    fun retry(entry: LogEntry, rules: List<ForwardRule>) {
        val rule = rules.find { it.id == entry.ruleId } ?: run {
            logRepo.updateStatus(entry.id, LogStatus.FAILED, "Rule no longer exists")
            return
        }
        trySend(entry, entry.fullMessage, rule)
    }

    // -------------------------------------------------------------------------

    private fun trySend(entry: LogEntry, text: String, rule: ForwardRule) {
        try {
            if (entry.destination.startsWith("Telegram")) {
                rule.telegram?.let { sendTelegram(text, it) }
            } else {
                rule.webhook?.let { sendWebhook(text, it) }
            }
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
        sender         = sender,
        messagePreview = fullMessage.take(80),
        ruleName       = rule.name,
        destination    = destination,
        fullMessage    = fullMessage,
        ruleId         = rule.id
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
            val body  = resp.body?.string() ?: ""
            val code  = resp.code
            resp.close()
            if (!resp.isSuccessful) throw Exception("HTTP $code: $body")
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
        val body  = resp.body?.string() ?: ""
        val code  = resp.code
        resp.close()
        if (!resp.isSuccessful) throw Exception("HTTP $code: $body")
    }
}
