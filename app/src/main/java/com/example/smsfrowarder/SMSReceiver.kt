package com.example.smsfrowarder

import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.Intent.ACTION_BATTERY_LOW
import android.os.BatteryManager
import android.os.Build
import android.provider.CallLog
import android.provider.CallLog.Calls.MISSED_TYPE
import android.provider.Telephony
import android.telephony.*
import com.example.smsfrowarder.model.SimSlot
import com.example.smsfrowarder.model.SmsData
import kotlinx.coroutines.*
import java.io.File
import java.util.*

class SMSReceiver(
    private val rulesRepository: RulesRepository,
    private val evaluator: RuleEvaluator
) : BroadcastReceiver() {

    companion object {
        private const val EXTRA_SUBSCRIPTION_ID = "android.telephony.extra.SUBSCRIPTION_INDEX"
    }

    private val scope = CoroutineScope(Dispatchers.IO)
    private var telephonyCallback: TelephonyCallback? = null
    private var telephonyManager: TelephonyManager? = null
    private var previousCallState = -1
    private var legacyPhoneNumber: String? = null

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Telephony.Sms.Intents.SMS_RECEIVED_ACTION -> {
                val subId = intent.getIntExtra(EXTRA_SUBSCRIPTION_ID, -1)
                val slot  = evaluator.resolveSlot(subId)
                processSMS(context, Telephony.Sms.Intents.getMessagesFromIntent(intent), slot)
            }
            TelephonyManager.ACTION_PHONE_STATE_CHANGED -> registerCallStateListener(context)
            ACTION_BATTERY_LOW -> {
                val bm = context.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
                val level = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
                val rules = rulesRepository.getRules().filter { it.enabled }
                if (rules.isNotEmpty()) savePending(context, SmsData("SYSTEM", "Low battery! $level% left", null, System.currentTimeMillis()), rules)
            }
        }
    }

    fun release() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            telephonyCallback?.let { telephonyManager?.unregisterTelephonyCallback(it) }
        }
        telephonyCallback = null
        telephonyManager = null
        scope.cancel()
    }

    private fun processSMS(context: Context, messages: Array<SmsMessage>, slot: SimSlot?) {
        messages.firstOrNull()?.let { first ->
            val sender = first.displayOriginatingAddress.orEmpty()
            val body   = messages.joinToString("") { it.displayMessageBody }
            val time   = first.timestampMillis
            val matched = evaluator.matching(rulesRepository.getRules(), sender, slot)
            if (matched.isNotEmpty()) {
                val data = SmsData(sender, body, slot, time)
                savePending(context, data, matched)
            }
        }
    }

    /** Saves pending dispatch to a file and launches SendConfirmActivity. */
    private fun savePending(context: Context, data: SmsData, rules: List<com.example.smsfrowarder.model.ForwardRule>) {
        val ruleIds = rules.map { it.id }.joinToString(",")
        val json = """{"sender":"${data.sender}","body":${gsonEscape(data.body)},"simSlot":"${data.simSlot?.name ?: "ANY"}","receiveTime":${data.receiveTime},"ruleIds":"$ruleIds"}"""
        File(context.filesDir, "pending_dispatch.json").writeText(json)

        // Launch SendConfirmActivity
        val intent = Intent(context, SendConfirmActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
        context.startActivity(intent)
    }

    private fun gsonEscape(s: String): String {
        return "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "\\r") + "\""
    }

    private fun registerCallStateListener(context: Context) {
        val tm = context.getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager
        telephonyManager = tm
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            telephonyCallback?.let { tm.unregisterTelephonyCallback(it) }
            val cb = object : TelephonyCallback(), TelephonyCallback.CallStateListener {
                override fun onCallStateChanged(state: Int) {
                    if (state == TelephonyManager.CALL_STATE_IDLE && previousCallState == TelephonyManager.CALL_STATE_RINGING)
                        readCallLogs(context)
                    previousCallState = state
                }
            }
            telephonyCallback = cb
            tm.registerTelephonyCallback(context.mainExecutor, cb)
        } else {
            @Suppress("DEPRECATION")
            tm.listen(object : PhoneStateListener() {
                @Deprecated("Deprecated in API 31")
                override fun onCallStateChanged(state: Int, phoneNumber: String?) {
                    if (state == TelephonyManager.CALL_STATE_IDLE && previousCallState == TelephonyManager.CALL_STATE_RINGING) {
                        val rules = rulesRepository.getRules().filter { it.enabled }
                        if (rules.isNotEmpty()) savePending(context, SmsData(legacyPhoneNumber ?: "", "Missed call from ${legacyPhoneNumber ?: "unknown"}", null, System.currentTimeMillis()), rules)
                    }
                    legacyPhoneNumber = phoneNumber ?: legacyPhoneNumber
                    previousCallState = state
                }
            }, PhoneStateListener.LISTEN_CALL_STATE)
        }
    }

    @SuppressLint("Range", "MissingPermission")
    private fun readCallLogs(context: Context) {
        val cursor = context.contentResolver.query(CallLog.Calls.CONTENT_URI, null, null, null, CallLog.Calls.DEFAULT_SORT_ORDER)
        try {
            if (cursor != null && cursor.moveToFirst()) {
                do {
                    val number = cursor.getString(cursor.getColumnIndex(CallLog.Calls.NUMBER))
                    val name   = cursor.getString(cursor.getColumnIndex(CallLog.Calls.CACHED_NAME))
                    val type   = cursor.getInt(cursor.getColumnIndex(CallLog.Calls.TYPE))
                    val date   = cursor.getLong(cursor.getColumnIndex(CallLog.Calls.DATE))
                    if (type == MISSED_TYPE) {
                        val rules = rulesRepository.getRules().filter { it.enabled }
                        if (rules.isNotEmpty()) savePending(context, SmsData(number ?: "", "Missed call from $number ($name)", null, date), rules)
                        break
                    }
                } while (cursor.moveToNext())
                cursor.close()
            }
        } catch (_: Exception) {}
    }
}
