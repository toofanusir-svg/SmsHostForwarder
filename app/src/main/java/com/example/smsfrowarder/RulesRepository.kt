package com.example.smsfrowarder

import android.content.Context
import com.example.smsfrowarder.model.ForwardRule
import com.example.smsfrowarder.model.TelegramDest
import com.example.smsfrowarder.model.WebhookDest
import com.example.smsfrowarder.model.SimSlot
import com.google.gson.GsonBuilder
import com.google.gson.JsonDeserializationContext
import com.google.gson.JsonDeserializer
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonSerializationContext
import com.google.gson.JsonSerializer
import java.io.File
import java.lang.reflect.Type

class RulesRepository private constructor(private val file: File) {

    companion object {
        private const val FILE_NAME = "forward_rules.json"

        fun create(context: Context): RulesRepository =
            RulesRepository(File(context.applicationContext.filesDir, FILE_NAME))
    }

    private val gson = GsonBuilder()
        .registerTypeAdapter(ForwardRule::class.java, RuleAdapter())
        .create()

    @Synchronized
    fun getRules(): List<ForwardRule> {
        if (!file.exists()) return emptyList()
        return try {
            gson.fromJson(file.readText(), Array<ForwardRule>::class.java).toList()
        } catch (_: Exception) { emptyList() }
    }

    @Synchronized
    fun saveRules(rules: List<ForwardRule>) {
        file.writeText(gson.toJson(rules))
    }

    fun addRule(rule: ForwardRule)    = saveRules(getRules() + rule)
    fun updateRule(rule: ForwardRule) = saveRules(getRules().map { if (it.id == rule.id) rule else it })
    fun deleteRule(id: String)        = saveRules(getRules().filter { it.id != id })
    fun setEnabled(id: String, on: Boolean) =
        saveRules(getRules().map { if (it.id == id) it.copy(enabled = on) else it })

    // -------------------------------------------------------------------------
    // Custom adapter handles new flat schema + backward compat with old "destination" wrapper
    // -------------------------------------------------------------------------
    private class RuleAdapter : JsonSerializer<ForwardRule>, JsonDeserializer<ForwardRule> {

        override fun serialize(src: ForwardRule, typeOfSrc: Type, ctx: JsonSerializationContext): JsonElement {
            return JsonObject().apply {
                addProperty("id",           src.id)
                addProperty("name",         src.name)
                addProperty("simSlot",      src.simSlot.name)
                addProperty("senderFilter", src.senderFilter)
                addProperty("enabled",      src.enabled)
                src.telegram?.let { tg ->
                    add("telegram", JsonObject().apply {
                        addProperty("botToken", tg.botToken)
                        addProperty("chatIds",  tg.chatIds)
                    })
                }
                src.webhook?.let { wh ->
                    add("webhook", JsonObject().apply {
                        addProperty("url",        wh.url)
                        addProperty("authHeader", wh.authHeader)
                    })
                }
            }
        }

        override fun deserialize(json: JsonElement, typeOfT: Type, ctx: JsonDeserializationContext): ForwardRule {
            val obj = json.asJsonObject
            val id           = obj.get("id")?.asString           ?: java.util.UUID.randomUUID().toString()
            val name         = obj.get("name")?.asString          ?: ""
            val senderFilter = obj.get("senderFilter")?.asString   ?: "*"
            val enabled      = obj.get("enabled")?.asBoolean       ?: true
            val simSlot      = try { SimSlot.valueOf(obj.get("simSlot")?.asString ?: "ANY") } catch (_: Exception) { SimSlot.ANY }

            // New schema: flat telegram / webhook objects
            var telegram: TelegramDest? = null
            var webhook:  WebhookDest?  = null

            if (obj.has("telegram") && !obj.get("telegram").isJsonNull) {
                val tg = obj.getAsJsonObject("telegram")
                telegram = TelegramDest(
                    botToken = tg.get("botToken")?.asString ?: "",
                    chatIds  = tg.get("chatIds")?.asString  ?: ""
                )
            }
            if (obj.has("webhook") && !obj.get("webhook").isJsonNull) {
                val wh = obj.getAsJsonObject("webhook")
                webhook = WebhookDest(
                    url        = wh.get("url")?.asString        ?: "",
                    authHeader = wh.get("authHeader")?.asString ?: ""
                )
            }

            // Backward compat: old format had a "destination" wrapper with a "type" field
            if (telegram == null && webhook == null && obj.has("destination")) {
                val dest = obj.getAsJsonObject("destination")
                when (dest.get("type")?.asString) {
                    "telegram" -> telegram = TelegramDest(
                        botToken = dest.get("botToken")?.asString ?: "",
                        chatIds  = dest.get("chatIds")?.asString  ?: ""
                    )
                    "webhook"  -> webhook = WebhookDest(
                        url        = dest.get("url")?.asString        ?: "",
                        authHeader = dest.get("authHeader")?.asString ?: ""
                    )
                }
            }

            return ForwardRule(id, name, simSlot, senderFilter, telegram, webhook, enabled)
        }
    }
}
