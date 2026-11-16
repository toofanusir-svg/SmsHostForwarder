package com.example.smsfrowarder

import android.content.Context
import android.os.Build
import android.telephony.SubscriptionInfo
import android.telephony.SubscriptionManager
import com.example.smsfrowarder.model.ForwardRule
import com.example.smsfrowarder.model.SimSlot

/**
 * Decides which rules match an incoming SMS.
 *
 * SIM detection relies on [SubscriptionManager] which requires READ_PHONE_STATE.
 * On devices where the slot cannot be determined (single-SIM, no permission, etc.)
 * [resolveSlot] returns null and rules with [SimSlot.ANY] still fire, but
 * SIM_1/SIM_2-specific rules are skipped — the caller is expected to surface
 * a one-time warning to the user via [isMultiSimDetectionAvailable].
 */
class RuleEvaluator(context: Context) {

    private val subscriptionManager =
        context.getSystemService(Context.TELEPHONY_SUBSCRIPTION_SERVICE) as SubscriptionManager

    /**
     * Returns true if the device has more than one active SIM slot that we can read.
     * Shows in the UI so the user knows SIM filtering is or isn't working.
     */
    @Suppress("MissingPermission")
    fun isMultiSimDetectionAvailable(): Boolean {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP_MR1) {
                val subs: List<SubscriptionInfo>? = subscriptionManager.activeSubscriptionInfoList
                (subs?.size ?: 0) > 1
            } else {
                false
            }
        } catch (e: SecurityException) {
            false
        }
    }

    /**
     * Resolves a subscription ID (from the SMS intent extra) to a [SimSlot].
     * Returns null when it cannot be determined.
     */
    @Suppress("MissingPermission")
    fun resolveSlot(subscriptionId: Int): SimSlot? {
        if (subscriptionId < 0) return null
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP_MR1) {
                val info = subscriptionManager.getActiveSubscriptionInfo(subscriptionId)
                    ?: return null
                when (info.simSlotIndex) {
                    0    -> SimSlot.SIM_1
                    1    -> SimSlot.SIM_2
                    else -> null
                }
            } else null
        } catch (e: SecurityException) {
            null
        }
    }

    /**
     * Returns all enabled rules that match [senderNumber] coming from [slot].
     * When [slot] is null (undetermined) only rules with [SimSlot.ANY] match.
     * Every matching rule is returned — the caller fires all of them.
     */
    fun matching(
        rules: List<ForwardRule>,
        senderNumber: String,
        slot: SimSlot?
    ): List<ForwardRule> {
        return rules.filter { rule ->
            if (!rule.enabled) return@filter false

            val slotMatches = when (rule.simSlot) {
                SimSlot.ANY   -> true
                SimSlot.SIM_1 -> slot == SimSlot.SIM_1
                SimSlot.SIM_2 -> slot == SimSlot.SIM_2
            }

            val senderMatches = rule.senderFilter.isBlank() ||
                rule.senderFilter.trim() == "*" ||
                normalizeNumber(rule.senderFilter) == normalizeNumber(senderNumber)

            slotMatches && senderMatches
        }
    }

    /** Strip spaces and leading '+' for a loose but good-enough match. */
    private fun normalizeNumber(number: String): String =
        number.trim().trimStart('+').replace(" ", "").replace("-", "")
}
