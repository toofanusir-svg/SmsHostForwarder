package com.example.smsforwarder.v2

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.Intent.ACTION_BATTERY_LOW
import android.content.IntentFilter
import android.provider.Telephony.Sms.Intents.SMS_RECEIVED_ACTION
import android.telephony.TelephonyManager.ACTION_PHONE_STATE_CHANGED
import android.util.Log
import androidx.core.app.NotificationCompat

/**
 * Android 10+ (API 29) foreground service.
 *
 * Registers SMSReceiver with RECEIVER_EXPORTED so the system can deliver
 * SMS_RECEIVED_ACTION, which is a protected broadcast. Without EXPORTED the
 * system silently drops the broadcast on API 33+.
 */
class SmsService : Service() {

    companion object {
        private const val TAG             = "SmsService"
        private const val CHANNEL_ID      = "sms_forwarder_channel"
        private const val NOTIFICATION_ID = 1001

        const val ACTION_STATE_CHANGED = "com.example.smsforwarder.v2.SERVICE_STATE"
        const val EXTRA_RUNNING        = "running"

        fun start(context: Context) {
            try {
                context.startForegroundService(Intent(context, SmsService::class.java))
            } catch (e: Exception) {
                Log.e(TAG, "startForegroundService failed: ${e.message}", e)
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, SmsService::class.java))
        }

        fun isRunning(context: Context): Boolean {
            val am = context.getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager
            @Suppress("DEPRECATION")
            return am.getRunningServices(50).any { it.service.className == SmsService::class.java.name }
        }
    }

    private var smsReceiver: SMSReceiver? = null

    override fun onBind(intent: Intent?) = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        try {
            startForegroundWithNotification()
            startReceiver()
            broadcastState(true)
        } catch (e: Exception) {
            Log.e(TAG, "onStartCommand failed: ${e.message}", e)
            stopSelf()
        }
        return START_STICKY
    }

    private fun startForegroundWithNotification() {
        getSystemService(NotificationManager::class.java)
            .createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "SMS Forwarder", NotificationManager.IMPORTANCE_LOW)
            )

        val pendingFlags = PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        val notif = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(getString(R.string.notif_title))
            .setContentText(getString(R.string.notif_text))
            .setContentIntent(
                PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), pendingFlags)
            )
            .setOngoing(true)
            .build()

        startForeground(NOTIFICATION_ID, notif)
    }

    private fun startReceiver() {
        smsReceiver?.let { runCatching { unregisterReceiver(it) }; it.release() }

        val app = App.get(this)
        smsReceiver = SMSReceiver(app.rulesRepository, RuleEvaluator(this), ForwardDispatcher(app.logRepository))

        val filter = IntentFilter().apply {
            addAction(SMS_RECEIVED_ACTION)
            addAction(ACTION_PHONE_STATE_CHANGED)
            addAction(ACTION_BATTERY_LOW)
        }

        // SMS_RECEIVED_ACTION is sent by the system; the receiver must be EXPORTED.
        // On API 33+ RECEIVER_NOT_EXPORTED silently prevents delivery.
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(smsReceiver, filter, RECEIVER_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            registerReceiver(smsReceiver, filter)
        }
    }

    private fun broadcastState(running: Boolean) {
        sendBroadcast(
            Intent(ACTION_STATE_CHANGED).apply {
                putExtra(EXTRA_RUNNING, running)
                setPackage(packageName)
            }
        )
    }

    override fun onDestroy() {
        super.onDestroy()
        smsReceiver?.let { runCatching { unregisterReceiver(it) }; it.release() }
        smsReceiver = null
        @Suppress("DEPRECATION") stopForeground(true)
        broadcastState(false)
    }
}
