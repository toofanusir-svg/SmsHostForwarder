package com.example.smsfrowarder

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.example.smsfrowarder.model.Destination
import com.example.smsfrowarder.model.ForwardRule
import com.example.smsfrowarder.model.SimSlot
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.JsonDeserializationContext
import com.google.gson.JsonDeserializer
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonSerializationContext
import com.google.gson.JsonSerializer
import java.lang.reflect.Type

/**
 * Persists the list of [ForwardRule]s in EncryptedSharedPreferences as a JSON array.
 * Rules contain bot tokens and webhook auth headers, so encryption is mandatory.
 */
class RulesRepository(private val prefs: SharedPreferences) {

    companion object {
        private const val KEY_RULES = "forward_rules"

        fun create(context: Context): RulesRepository {
            val masterKey = MasterKey.Builder(context)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build()
            val encryptedPrefs = EncryptedSharedPreferences.create(
                context,
                "secure_rules",
                masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            )
            return RulesRepository(encryptedPrefs)
        }
    }

    private val gson: Gson = GsonBuilder()
        .registerTypeHierarchyAdapter(Destination::class.java, DestinationAdapter())
        .create()

    fun getRules(): List<ForwardRule> {
        val json = prefs.getString(KEY_RULES, null) ?: return emptyList()
        return try {
            gson.fromJson(json, Array<ForwardRule>::class.java).toList()
        } catch (e: Exception) {
            emptyList()
        }
    }

    fun saveRules(rules: List<ForwardRule>) {
        prefs.edit().putString(KEY_RULES, gson.toJson(rules)).apply()
    }

    fun addRule(rule: ForwardRule) {
        saveRules(getRules() + rule)
    }

    fun updateRule(rule: ForwardRule) {
        saveRules(getRules().map { if (it.id == rule.id) rule else it })
    }

    fun deleteRule(id: String) {
        saveRules(getRules().filter { it.id != id })
    }

    fun setEnabled(id: String, enabled: Boolean) {
        saveRules(getRules().map { if (it.id == id) it.copy(enabled = enabled) else it })
    }

    // ---------------------------------------------------------------------------
    // Gson adapter: Destination is a sealed class, we store a "type" discriminator
    // ---------------------------------------------------------------------------
    private class DestinationAdapter :
        JsonSerializer<Destination>, JsonDeserializer<Destination> {

        override fun serialize(
            src: Destination, typeOfSrc: Type, context: JsonSerializationContext
        ): JsonElement {
            val obj = JsonObject()
            when (src) {
                is Destination.Telegram -> {
                    obj.addProperty("type", "telegram")
                    obj.addProperty("botToken", src.botToken)
                    obj.addProperty("chatIds", src.chatIds)
                }
                is Destination.Webhook -> {
                    obj.addProperty("type", "webhook")
                    obj.addProperty("url", src.url)
                    obj.addProperty("authHeader", src.authHeader)
                }
            }
            return obj
        }

        override fun deserialize(
            json: JsonElement, typeOfT: Type, context: JsonDeserializationContext
        ): Destination {
            val obj = json.asJsonObject
            return when (obj.get("type")?.asString) {
                "telegram" -> Destination.Telegram(
                    botToken = obj.get("botToken")?.asString ?: "",
                    chatIds  = obj.get("chatIds")?.asString  ?: ""
                )
                "webhook"  -> Destination.Webhook(
                    url        = obj.get("url")?.asString        ?: "",
                    authHeader = obj.get("authHeader")?.asString ?: ""
                )
                else -> throw IllegalArgumentException("Unknown destination type")
            }
        }
    }
}
