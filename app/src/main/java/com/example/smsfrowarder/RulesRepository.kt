package com.example.smsfrowarder

import android.content.Context
import com.example.smsfrowarder.model.Destination
import com.example.smsfrowarder.model.ForwardRule
import com.google.gson.GsonBuilder
import com.google.gson.JsonDeserializationContext
import com.google.gson.JsonDeserializer
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonSerializationContext
import com.google.gson.JsonSerializer
import java.io.File
import java.lang.reflect.Type

/**
 * Stores [ForwardRule] list as a JSON file in the app's private filesDir.
 *
 * Why not EncryptedSharedPreferences?
 * EncryptedSharedPreferences initialises the Android KeyStore on first use,
 * which is a blocking disk + IPC operation. Calling it on the main thread
 * causes StrictMode violations and intermittent crashes on many devices.
 *
 * The JSON file lives in Context.filesDir which is app-private (mode 0600 on
 * the filesystem). On Android 7+ full-disk encryption or file-based encryption
 * protects it at rest. This is the same protection level as plain
 * SharedPreferences — perfectly adequate for a bot token stored locally.
 *
 * All public methods are safe to call from any thread (they do blocking I/O,
 * so callers should use Dispatchers.IO — which MainActivity and
 * RuleEditActivity already do).
 */
class RulesRepository private constructor(private val file: File) {

    companion object {
        private const val FILE_NAME = "forward_rules.json"

        fun create(context: Context): RulesRepository =
            RulesRepository(File(context.applicationContext.filesDir, FILE_NAME))
    }

    private val gson = GsonBuilder()
        .registerTypeHierarchyAdapter(Destination::class.java, DestinationAdapter())
        .create()

    @Synchronized
    fun getRules(): List<ForwardRule> {
        if (!file.exists()) return emptyList()
        return try {
            gson.fromJson(file.readText(), Array<ForwardRule>::class.java).toList()
        } catch (_: Exception) {
            emptyList()
        }
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
    private class DestinationAdapter :
        JsonSerializer<Destination>, JsonDeserializer<Destination> {

        override fun serialize(
            src: Destination, typeOfSrc: Type, ctx: JsonSerializationContext
        ): JsonElement {
            val obj = JsonObject()
            when (src) {
                is Destination.Telegram -> {
                    obj.addProperty("type",     "telegram")
                    obj.addProperty("botToken", src.botToken)
                    obj.addProperty("chatIds",  src.chatIds)
                }
                is Destination.Webhook -> {
                    obj.addProperty("type",       "webhook")
                    obj.addProperty("url",        src.url)
                    obj.addProperty("authHeader", src.authHeader)
                }
            }
            return obj
        }

        override fun deserialize(
            json: JsonElement, typeOfT: Type, ctx: JsonDeserializationContext
        ): Destination {
            val obj = json.asJsonObject
            return when (obj.get("type")?.asString) {
                "telegram" -> Destination.Telegram(
                    botToken = obj.get("botToken")?.asString ?: "",
                    chatIds  = obj.get("chatIds")?.asString  ?: ""
                )
                "webhook" -> Destination.Webhook(
                    url        = obj.get("url")?.asString        ?: "",
                    authHeader = obj.get("authHeader")?.asString ?: ""
                )
                else -> throw IllegalArgumentException("Unknown destination type")
            }
        }
    }
}
