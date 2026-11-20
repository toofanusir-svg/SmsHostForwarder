package com.example.smsforwarder.v2

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Starts SmsService after device boot.
 *
 * Handles three boot actions:
 *  - BOOT_COMPLETED         : standard Android
 *  - QUICKBOOT_POWERON      : Xiaomi / HyperOS fast-boot
 *  - HTC QUICKBOOT_POWERON  : older HTC devices (harmless to include)
 */
class StartupReceiver : BroadcastReceiver() {

    companion object {
        private val BOOT_ACTIONS = setOf(
            Intent.ACTION_BOOT_COMPLETED,
            "android.intent.action.QUICKBOOT_POWERON",
            "com.htc.intent.action.QUICKBOOT_POWERON"
        )
    }

    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action in BOOT_ACTIONS) {
            SmsService.start(context)
        }
    }
}
