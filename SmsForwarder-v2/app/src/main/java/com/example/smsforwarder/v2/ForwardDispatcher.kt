package com.example.smsforwarder.v2

import com.example.smsforwarder.v2.model.ForwardRule
import com.example.smsforwarder.v2.model.LogEntry
import com.example.smsforwarder.v2.model.LogStatus
import com.example.smsforwarder.v2.model.TelegramDest
import com.example.smsforwarder.v2.model.WebhookDest
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

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    companion object {
        private val JSON_TYPE = "application/json".toMediaTypeOrNull()
    }

    // ── Main dispatch ─────────────────────────────────────────────────────────

    suspend fun dispatch(
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
            }
            rule.webhook?.let { wh ->
                val entry = createEntry(sender, text, rule, "Webhook (${wh.url.take(40)})")
                logRepo.add(entry)
                entries += entry
            }
        }

        // Each entry fires in its own coroutine — no rule blocks another
        entries.map { entry ->
            scope.async {
                val rule = rules.find { it.id == entry.ruleId } ?: return@async
                trySend(entry, text, rule)
            }
        }.awaitAll()

        return entries
    }

    fun retry(entry: LogEntry, rules: List<ForwardRule>) {
        val rule = rules.find { it.id == entry.ruleId } ?: run {
            logRepo.updateStatus(entry.id, LogStatus.FAILED, "Rule no longer exists")
            return
        }
        trySend(entry, entry.fullMessage, rule)
    }

    // ── OTP ───────────────────────────────────────────────────────────────────

    /** Returns null on success, error message string on failure. */
    fun sendOtpTelegram(otpMessage: String, dest: TelegramDest): String? = try {
        sendTelegram(otpMessage, dest)
        null
    } catch (e: Exception) { e.message ?: "Unknown error" }

    /** Returns null on success, error message string on failure. */
    fun sendOtpWebhook(otpMessage: String, dest: WebhookDest): String? = try {
        val body = if (dest.encryptionKey.isNotBlank())
            JsonObject().apply { addProperty("message", Crypto.encrypt(otpMessage, dest.encryptionKey)) }.toString()
        else
            JsonObject().apply { addProperty("message", otpMessage) }.toString()
        sendWebhookRaw(body, dest)
        null
    } catch (e: Exception) { e.message ?: "Unknown error" }

    // ── Internal ─────────────────────────────────────────────────────────────

    private fun trySend(entry: LogEntry, text: String, rule: ForwardRule) {
        try {
            if (entry.destination.startsWith("Telegram")) {
                rule.telegram?.let { sendTelegram(text, it) }
            } else {
                rule.webhook?.let { wh ->
                    // Always wrap in {"message":"..."} — server expects a JSON object
                    val value = if (wh.encryptionKey.isNotBlank()) Crypto.encrypt(text, wh.encryptionKey) else text
                    val body  = JsonObject().apply { addProperty("message", value) }.toString()
                    sendWebhookRaw(body, wh)
                }
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
        sender = sender,
        messagePreview = fullMessage.take(80),
        ruleName = rule.name,
        destination = destination,
        fullMessage = fullMessage,
        ruleId = rule.id
    )

    // ── Network ───────────────────────────────────────────────────────────────

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
            val code = resp.code
            val ok = resp.isSuccessful
            resp.close()
            if (!ok) throw Exception("HTTP $code: $body")
        }
    }

    private fun sendWebhookRaw(payload: String, dest: WebhookDest) {
        val builder = Request.Builder()
            .url(dest.url)
            .post(payload.toRequestBody(JSON_TYPE))
            .addHeader("content-type", "application/json")
        if (dest.authHeader.isNotBlank()) builder.addHeader("Authorization", dest.authHeader)
        val resp = client.newCall(builder.build()).execute()
        val body = resp.body?.string() ?: ""
        val code = resp.code
        val ok = resp.isSuccessful
        resp.close()
        if (!ok) throw Exception("HTTP $code: $body")
    }
}
