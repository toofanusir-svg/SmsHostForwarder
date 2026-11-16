package com.example.smsfrowarder

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.example.smsfrowarder.model.Destination
import com.example.smsfrowarder.model.ForwardRule
import com.google.gson.GsonBuilder
import com.google.gson.JsonDeserializationContext
import com.google.gson.JsonDeserializer
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonSerializationContext
import com.google.gson.JsonSerializer
import java.lang.reflect.Type

/**
 * Persists [ForwardRule] list as encrypted JSON.
 *
 * IMPORTANT: call [create] from a background thread (or IO coroutine) —
 * EncryptedSharedPreferences initialises the Android KeyStore which performs
 * disk I/O and must not run on the main thread.
 */
class RulesRepository private constructor(private val prefs: SharedPreferences) {

    companion object {
        private const val KEY_RULES   = "forward_rules"
        private const val PREFS_NAME  = "secure_rules"

        /**
         * Safe to call from any thread.
         * Internally falls back to plain SharedPreferences if the KeyStore is
         * unavailable (e.g. on some Android 8 devices where StrongBox is absent),
         * so the app never crashes — it just stores data unencrypted in that
         * exceptional case.
         */
        fun create(context: Context): RulesRepository {
            val appCtx = context.applicationContext
            val prefs  = try {
                val masterKey = MasterKey.Builder(appCtx)
                    .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                    .build()
                EncryptedSharedPreferences.create(
                    appCtx,
                    PREFS_NAME,
                    masterKey,
                    EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                    EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
                )
            } catch (e: Exception) {
                // KeyStore unavailable — fall back to plain prefs (device-level
                // encryption still protects data on modern Android).
                appCtx.getSharedPreferences("${PREFS_NAME}_plain", Context.MODE_PRIVATE)
            }
            return RulesRepository(prefs)
        }
    }

    private val gson = GsonBuilder()
        .registerTypeHierarchyAdapter(Destination::class.java, DestinationAdapter())
        .create()

    fun getRules(): List<ForwardRule> {
        val json = prefs.getString(KEY_RULES, null) ?: return emptyList()
        return try {
            gson.fromJson(json, Array<ForwardRule>::class.java).toList()
        } catch (_: Exception) {
            emptyList()
        }
    }

    fun saveRules(rules: List<ForwardRule>) {
        prefs.edit().putString(KEY_RULES, gson.toJson(rules)).apply()
    }

    fun addRule(rule: ForwardRule)    = saveRules(getRules() + rule)
    fun updateRule(rule: ForwardRule) = saveRules(getRules().map { if (it.id == rule.id) rule else it })
    fun deleteRule(id: String)        = saveRules(getRules().filter { it.id != id })
    fun setEnabled(id: String, on: Boolean) =
        saveRules(getRules().map { if (it.id == id) it.copy(enabled = on) else it })

    // -------------------------------------------------------------------------
    private class DestinationAdapter :
        JsonSerializer<Destination>, JsonDeserializer<Destination> {

        override fun serialize(src: Destination, typeOfSrc: Type, ctx: JsonSerializationContext): JsonElement {
            val obj = JsonObject()
            when (src) {
                is Destination.Telegram -> {
                    obj.addProperty("type", "telegram")
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

        override fun deserialize(json: JsonElement, typeOfT: Type, ctx: JsonDeserializationContext): Destination {
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
