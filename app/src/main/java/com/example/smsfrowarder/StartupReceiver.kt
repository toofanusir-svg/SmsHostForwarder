package com.example.smsfrowarder

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Starts the service automatically after device boot. */
class StartupReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action == Intent.ACTION_BOOT_COMPLETED) {
            SmsService.start(context)
        }
    }
}
