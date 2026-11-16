package com.example.smsfrowarder

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.Intent.ACTION_BATTERY_LOW
import android.content.IntentFilter
import android.os.Build
import android.provider.Telephony.Sms.Intents.SMS_RECEIVED_ACTION
import android.telephony.TelephonyManager.ACTION_PHONE_STATE_CHANGED
import androidx.core.app.NotificationCompat

class SmsService : Service() {

    companion object {
        private const val CHANNEL_ID     = "sms_forwarder_channel"
        private const val NOTIFICATION_ID = 1001

        fun start(context: Context) {
            val intent = Intent(context, SmsService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, SmsService::class.java))
        }
    }

    private var smsReceiver: SMSReceiver? = null

    override fun onBind(intent: Intent?) = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForegroundWithNotification()
        startReceiver()
        return START_STICKY
    }

    private fun startForegroundWithNotification() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "SMS Forwarder",
                NotificationManager.IMPORTANCE_LOW  // LOW = no sound, still shows in tray
            )
            getSystemService(NotificationManager::class.java)
                .createNotificationChannel(channel)
        }

        // Tap the notification → open Rules list
        val openIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )

        val notification = androidx.core.app.NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(getString(R.string.notif_title))
            .setContentText(getString(R.string.notif_text))
            .setContentIntent(openIntent)
            .setOngoing(true)
            .build()

        startForeground(NOTIFICATION_ID, notification)
    }

    private fun startReceiver() {
        smsReceiver?.let {
            it.release()
            unregisterReceiver(it)
        }

        val rulesRepo  = RulesRepository.create(this)
        val evaluator  = RuleEvaluator(this)
        val dispatcher = ForwardDispatcher()

        smsReceiver = SMSReceiver(rulesRepo, evaluator, dispatcher)
        registerReceiver(smsReceiver, IntentFilter().apply {
            addAction(SMS_RECEIVED_ACTION)
            addAction(ACTION_PHONE_STATE_CHANGED)
            addAction(ACTION_BATTERY_LOW)
        })
    }

    override fun onDestroy() {
        super.onDestroy()
        smsReceiver?.let {
            it.release()
            unregisterReceiver(it)
        }
        smsReceiver = null
        stopForeground(true)
    }
}
