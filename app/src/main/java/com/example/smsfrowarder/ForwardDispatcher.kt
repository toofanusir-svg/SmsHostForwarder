package com.example.smsfrowarder

import com.example.smsfrowarder.model.ForwardRule
import com.example.smsfrowarder.model.LogEntry
import com.example.smsfrowarder.model.LogStatus
import com.example.smsfrowarder.model.SimSlot
import com.example.smsfrowarder.model.SmsData
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
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
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

    // ── Main dispatch (from SMS) ──────────────────────────────────────────────

    suspend fun dispatch(data: SmsData, rules: List<ForwardRule>): List<LogEntry> {
        val entries = mutableListOf<LogEntry>()

        rules.forEach { rule ->
            val methods = mutableListOf<String>()
            if (rule.telegram != null) methods.add("Telegram")
            if (rule.webhook != null) methods.add("Web")
            val methodName = methods.joinToString(", ")

            rule.telegram?.let { tg ->
                val text = formatTelegram(data)
                val entry = createEntry(data.sender, text.take(80), rule, "Telegram (${tg.chatIds})", text, data)
                logRepo.add(entry)
                entries += entry
            }
            rule.webhook?.let { wh ->
                val payload = buildWebhookPayload(data, methodName, rule)
                val encrypted = if (wh.encryptionKey.isNotBlank()) Crypto.encrypt(payload, wh.encryptionKey) else payload
                val text = if (wh.encryptionKey.isNotBlank()) "(encrypted) $payload" else payload
                val entry = createEntry(data.sender, payload.take(80), rule, "Webhook (${wh.url.take(40)})", text, data)
                logRepo.add(entry)
                entries += entry
            }
        }

        entries.map { entry ->
            scope.async {
                val rule = rules.find { it.id == entry.ruleId } ?: return@async
                trySend(entry, data, rule)
            }
        }.awaitAll()

        return entries
    }

    fun retry(entry: LogEntry, rules: List<ForwardRule>) {
        val rule = rules.find { it.id == entry.ruleId } ?: run {
            logRepo.updateStatus(entry.id, LogStatus.FAILED, "Rule no longer exists")
            return
        }
        val data = entry.smsData ?: return
        trySend(entry, data, rule)
    }

    // ── OTP verification ─────────────────────────────────────────────────────

    /** Returns null on success, error message on failure. */
    fun sendOtpTelegram(otpMessage: String, dest: TelegramDest): String? = try {
        sendTelegram(otpMessage, dest)
        null
    } catch (e: Exception) { e.message ?: "Unknown error" }

    /** Returns null on success, error message on failure. */
    fun sendOtpWebhook(otpMessage: String, dest: WebhookDest): String? = try {
        val obj = JsonObject().apply {
            addProperty("SendNumber", "VERIFY")
            addProperty("SendName", "")
            addProperty("LineNumber", "any")
            addProperty("ReceiveTime", "")
            addProperty("SendTime", "")
            addProperty("Body", otpMessage)
            addProperty("MethodName", "Web")
        }
        val payload = "[$obj]"
        // Encrypt if user has set an encryption key
        val finalPayload = if (dest.encryptionKey.isNotBlank())
            Crypto.encrypt(payload, dest.encryptionKey) else payload
        sendWebhookRaw(finalPayload, dest)
        null
    } catch (e: Exception) { e.message ?: "Unknown error" }

    // ── Quick test (no OTP) ──────────────────────────────────────────────────

    fun quickTestTelegram(text: String, dest: TelegramDest): Boolean = try {
        sendTelegram(text, dest)
        true
    } catch (e: Exception) { false }

    fun quickTestWebhook(text: String, dest: WebhookDest): Boolean = try {
        val obj = JsonObject().apply {
            addProperty("SendNumber", "TEST")
            addProperty("SendName", "")
            addProperty("LineNumber", "any")
            addProperty("ReceiveTime", "")
            addProperty("SendTime", "")
            addProperty("Body", text)
            addProperty("MethodName", "Web")
        }
        sendWebhookRaw("[$obj]", dest)
        true
    } catch (_: Exception) { false }

    // ── Internal ─────────────────────────────────────────────────────────────

    private fun trySend(entry: LogEntry, data: SmsData, rule: ForwardRule) {
        try {
            if (entry.destination.startsWith("Telegram")) {
                rule.telegram?.let { sendTelegram(entry.fullMessage, it) }
            } else {
                rule.webhook?.let { wh ->
                    val payload = buildWebhookPayload(data, getMethodName(rule), rule)
                    if (wh.encryptionKey.isNotBlank()) {
                        sendWebhookRaw(Crypto.encrypt(payload, wh.encryptionKey), wh)
                    } else {
                        sendWebhookRaw(payload, wh)
                    }
                }
            }
            logRepo.updateStatus(entry.id, LogStatus.SUCCESS)
        } catch (e: Exception) {
            logRepo.updateStatus(entry.id, LogStatus.FAILED, e.message ?: "Unknown error")
        }
    }

    private fun getMethodName(rule: ForwardRule): String {
        val methods = mutableListOf<String>()
        if (rule.telegram != null) methods.add("Telegram")
        if (rule.webhook != null) methods.add("Web")
        return methods.joinToString(", ")
    }

    private fun formatTelegram(data: SmsData): String {
        val simTag = when (data.simSlot) { SimSlot.SIM_1 -> "SIM 1"; SimSlot.SIM_2 -> "SIM 2"; else -> "SIM ?" }
        val date = Date(data.receiveTime).toString()
        return "📱 *$simTag* — From *${escapeMarkdown(data.sender)}* at $date\n\n${highlightCode(data.body)}"
    }

    private fun buildWebhookPayload(data: SmsData, methodName: String, rule: ForwardRule): String {
        val sdf = SimpleDateFormat("yyyy-MM-dd  HH:mm:ss", Locale.getDefault())
        val line = when (data.simSlot) { SimSlot.SIM_1 -> "Line1"; SimSlot.SIM_2 -> "Line2"; else -> "any" }
        val obj = JsonObject().apply {
            addProperty("SendNumber", data.sender)
            addProperty("SendName", "")
            addProperty("LineNumber", line)
            addProperty("ReceiveTime", sdf.format(Date(data.receiveTime)))
            addProperty("SendTime", sdf.format(Date()))
            addProperty("Body", data.body)
            addProperty("MethodName", methodName)
        }
        return "[$obj]"
    }

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
            resp.close()
            if (!resp.isSuccessful) throw Exception("HTTP $code: $body")
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
        val success = resp.isSuccessful
        resp.close()
        if (!success) throw Exception("HTTP $code: $body")
    }

    private fun createEntry(
        sender: String,
        preview: String,
        rule: ForwardRule,
        destination: String,
        fullMessage: String,
        smsData: SmsData
    ) = LogEntry(
        sender = sender,
        messagePreview = preview,
        ruleName = rule.name,
        destination = destination,
        fullMessage = fullMessage,
        smsData = smsData,
        ruleId = rule.id
    )

    private fun highlightCode(text: String): String {
        var result = text
        "\\d{4,}|[A-Z\\d]{4,}".toRegex().findAll(result).forEach {
            result = result.replace(it.value, "`${it.value}`")
        }
        return result
    }

    private fun escapeMarkdown(input: String?): String {
        if (input.isNullOrEmpty()) return ""
        return input.replace("_", "\\_").replace("*", "\\*").replace("`", "\\`").replace("[", "\\[")
    }
}
