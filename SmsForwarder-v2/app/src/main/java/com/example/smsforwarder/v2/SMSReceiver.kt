package com.example.smsforwarder.v2

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.Intent.ACTION_BATTERY_LOW
import android.os.BatteryManager
import android.provider.CallLog
import android.provider.CallLog.Calls.MISSED_TYPE
import android.provider.Telephony
import android.telephony.SmsMessage
import android.telephony.SubscriptionManager
import android.telephony.TelephonyCallback
import android.telephony.TelephonyManager
import com.example.smsforwarder.v2.model.SimSlot
import kotlinx.coroutines.*
import java.util.*

/**
 * Android 10+ (API 29) only.
 *
 * Key changes vs the old version:
 *  - minSdk 29 so all legacy API < 29 branches are removed.
 *  - TelephonyCallback is used for call-state (PhoneStateListener deprecated API 31).
 *  - RECEIVER_EXPORTED is set by the caller (SmsService) so system broadcasts arrive.
 *  - Battery-optimisation prompt is handled in MainActivity / SettingsActivity,
 *    not here.
 */
class SMSReceiver(
    private val rulesRepository: RulesRepository,
    private val evaluator: RuleEvaluator,
    private val dispatcher: ForwardDispatcher
) : BroadcastReceiver() {

    companion object {
        // The subscription index extra added in API 22; always present on Android 10+.
        private const val EXTRA_SUBSCRIPTION_ID = "android.telephony.extra.SUBSCRIPTION_INDEX"
    }

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    // Call-state tracking (API 31+ TelephonyCallback path only, since minSdk = 29
    // we keep a small shim for API 29-30).
    private var telephonyCallback: TelephonyCallback? = null
    private var telephonyManager: TelephonyManager? = null
    private var previousCallState = TelephonyManager.CALL_STATE_IDLE

    // ── BroadcastReceiver ────────────────────────────────────────────────────

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Telephony.Sms.Intents.SMS_RECEIVED_ACTION -> handleSms(context, intent)
            TelephonyManager.ACTION_PHONE_STATE_CHANGED -> registerCallStateListener(context)
            ACTION_BATTERY_LOW -> handleBatteryLow(context)
        }
    }

    // ── SMS ──────────────────────────────────────────────────────────────────

    private fun handleSms(context: Context, intent: Intent) {
        val messages: Array<SmsMessage> =
            Telephony.Sms.Intents.getMessagesFromIntent(intent) ?: return
        if (messages.isEmpty()) return

        val subId = intent.getIntExtra(EXTRA_SUBSCRIPTION_ID, SubscriptionManager.INVALID_SUBSCRIPTION_ID)
        val slot  = evaluator.resolveSlot(subId)

        val first   = messages.first()
        val sender  = first.displayOriginatingAddress.orEmpty()
        val body    = messages.joinToString("") { it.displayMessageBody.orEmpty() }.highlightCode()
        val date    = Date(first.timestampMillis)
        val simTag  = when (slot) { SimSlot.SIM_1 -> "SIM 1"; SimSlot.SIM_2 -> "SIM 2"; else -> "SIM ?" }
        val text    = "📱 *$simTag* — From *${escapeMarkdown(sender)}* at $date\n\n$body"
        val preview = "From: $sender\n${body.take(80)}"

        val matched = evaluator.matching(rulesRepository.getRules(), sender, slot)
        if (matched.isEmpty()) return

        val ruleIds = matched.map { it.id }.joinToString(",")
        val confirmIntent = Intent(context, SendConfirmActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
            putExtra(SendConfirmActivity.EXTRA_TEXT,     text)
            putExtra(SendConfirmActivity.EXTRA_SENDER,   sender)
            putExtra(SendConfirmActivity.EXTRA_RULE_IDS, ruleIds)
            putExtra(SendConfirmActivity.EXTRA_PREVIEW,  preview)
        }
        context.startActivity(confirmIntent)
    }

    // ── Battery ──────────────────────────────────────────────────────────────

    private fun handleBatteryLow(context: Context) {
        val bm    = context.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
        val level = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        val rules = rulesRepository.getRules().filter { it.enabled }
        scope.launch { dispatcher.dispatch("🔋 Low battery! $level% left", "SYSTEM", rules) }
    }

    // ── Call state ───────────────────────────────────────────────────────────

    private fun registerCallStateListener(context: Context) {
        val tm = context.getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager
        telephonyManager = tm

        // TelephonyCallback arrived in API 31. For API 29-30 we fall back to the
        // deprecated PhoneStateListener (still works on those versions).
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
            telephonyCallback?.let { tm.unregisterTelephonyCallback(it) }
            val cb = object : TelephonyCallback(), TelephonyCallback.CallStateListener {
                override fun onCallStateChanged(state: Int) {
                    if (state == TelephonyManager.CALL_STATE_IDLE &&
                        previousCallState == TelephonyManager.CALL_STATE_RINGING
                    ) {
                        readMissedCallLog(context)
                    }
                    previousCallState = state
                }
            }
            telephonyCallback = cb
            tm.registerTelephonyCallback(context.mainExecutor, cb)
        } else {
            @Suppress("DEPRECATION")
            tm.listen(object : android.telephony.PhoneStateListener() {
                @Deprecated("Deprecated in API 31")
                override fun onCallStateChanged(state: Int, phoneNumber: String?) {
                    if (state == TelephonyManager.CALL_STATE_IDLE &&
                        previousCallState == TelephonyManager.CALL_STATE_RINGING
                    ) {
                        readMissedCallLog(context)
                    }
                    previousCallState = state
                }
            }, android.telephony.PhoneStateListener.LISTEN_CALL_STATE)
        }
    }

    @Suppress("MissingPermission")
    private fun readMissedCallLog(context: Context) {
        try {
            context.contentResolver.query(
                CallLog.Calls.CONTENT_URI,
                arrayOf(CallLog.Calls.NUMBER, CallLog.Calls.CACHED_NAME, CallLog.Calls.TYPE, CallLog.Calls.DATE),
                "${CallLog.Calls.TYPE} = ?",
                arrayOf(MISSED_TYPE.toString()),
                CallLog.Calls.DEFAULT_SORT_ORDER
            )?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val number = cursor.getString(cursor.getColumnIndexOrThrow(CallLog.Calls.NUMBER))
                    val name   = cursor.getString(cursor.getColumnIndexOrThrow(CallLog.Calls.CACHED_NAME))
                    val date   = cursor.getLong(cursor.getColumnIndexOrThrow(CallLog.Calls.DATE))
                    val rules  = rulesRepository.getRules().filter { it.enabled }
                    scope.launch {
                        dispatcher.dispatch(
                            "📞 Missed call from `${escapeMarkdown(number)}` ($name). ${Date(date)}",
                            number ?: "",
                            rules
                        )
                    }
                }
            }
        } catch (_: Exception) { }
    }

    // ── Cleanup ──────────────────────────────────────────────────────────────

    fun release() {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
            telephonyCallback?.let { telephonyManager?.unregisterTelephonyCallback(it) }
        }
        telephonyCallback = null
        telephonyManager  = null
        scope.cancel()
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    /** Wraps numeric codes in backticks so Telegram renders them as monospace. */
    private fun String.highlightCode(): String {
        var result = this
        "\\d{4,}|[A-Z\\d]{4,}".toRegex()
            .findAll(result)
            .forEach { result = result.replace(it.value, "`${it.value}`") }
        return result
    }

    private fun escapeMarkdown(input: String?): String {
        if (input.isNullOrEmpty()) return ""
        return input
            .replace("_", "\\_")
            .replace("*", "\\*")
            .replace("`", "\\`")
            .replace("[", "\\[")
    }
}
