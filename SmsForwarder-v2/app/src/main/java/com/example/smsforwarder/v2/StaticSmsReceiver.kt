package com.example.smsforwarder.v2

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Telephony
import android.telephony.SmsMessage
import android.telephony.SubscriptionManager

/**
 * Static receiver declared in AndroidManifest — required for Xiaomi HyperOS / MIUI.
 *
 * On stock Android a dynamic receiver (registered in SmsService) is enough.
 * But Xiaomi HyperOS blocks broadcasts to dynamic receivers when the app is
 * in the background, even with RECEIVER_EXPORTED. A static receiver in the
 * manifest bypasses this restriction because the system launches the process
 * itself to deliver the broadcast.
 *
 * Flow:
 *   1. SMS arrives → system wakes this receiver.
 *   2. If SmsService is already running: delegate to its SMSReceiver (avoid duplicate send).
 *   3. If service is NOT running: start it, then process the SMS directly here
 *      using a goAsync() PendingResult so the work survives past onReceive().
 */
class StaticSmsReceiver : BroadcastReceiver() {

    companion object {
        private const val EXTRA_SUBSCRIPTION_ID = "android.telephony.extra.SUBSCRIPTION_INDEX"
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Telephony.Sms.Intents.SMS_RECEIVED_ACTION) return

        val messages: Array<SmsMessage> =
            Telephony.Sms.Intents.getMessagesFromIntent(intent) ?: return
        if (messages.isEmpty()) return

        // Make sure the service is running so its dynamic receiver handles future SMS.
        if (!SmsService.isRunning(context)) {
            SmsService.start(context)
        }

        // Process this SMS regardless — the service may have just started and its
        // dynamic receiver is not yet registered.
        val subId = intent.getIntExtra(
            EXTRA_SUBSCRIPTION_ID,
            SubscriptionManager.INVALID_SUBSCRIPTION_ID
        )

        val app        = App.get(context)
        val evaluator  = RuleEvaluator(context)
        val dispatcher = ForwardDispatcher(app.logRepository)
        val slot       = evaluator.resolveSlot(subId)

        val first   = messages.first()
        val sender  = first.displayOriginatingAddress.orEmpty()
        val body    = messages.joinToString("") { it.displayMessageBody.orEmpty() }
            .highlightCode()
        val date    = java.util.Date(first.timestampMillis)
        val simTag  = when (slot) {
            com.example.smsforwarder.v2.model.SimSlot.SIM_1 -> "SIM 1"
            com.example.smsforwarder.v2.model.SimSlot.SIM_2 -> "SIM 2"
            else -> "SIM ?"
        }
        val text    = "📱 *$simTag* — From *${escapeMarkdown(sender)}* at $date\n\n$body"
        val preview = "From: $sender\n${body.take(80)}"

        val matched = evaluator.matching(app.rulesRepository.getRules(), sender, slot)
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
