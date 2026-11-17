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
import android.util.Log
import androidx.core.app.NotificationCompat

class SmsService : Service() {

    companion object {
        private const val TAG             = "SmsService"
        private const val CHANNEL_ID      = "sms_forwarder_channel"
        private const val NOTIFICATION_ID = 1001

        const val ACTION_STATE_CHANGED = "com.example.smsfrowarder.SERVICE_STATE"
        const val EXTRA_RUNNING        = "running"

        fun start(context: Context) {
            val intent = Intent(context, SmsService::class.java)
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                    context.startForegroundService(intent)
                else
                    context.startService(intent)
            } catch (e: Exception) {
                Log.e(TAG, "startService failed: ${e.message}", e)
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
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(CHANNEL_ID, "SMS Forwarder", NotificationManager.IMPORTANCE_LOW)
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
        val pendingFlags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M)
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        else PendingIntent.FLAG_UPDATE_CURRENT

        val openIntent = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), pendingFlags)
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(getString(R.string.notif_title))
            .setContentText(getString(R.string.notif_text))
            .setContentIntent(openIntent)
            .setOngoing(true)
            .build()
        startForeground(NOTIFICATION_ID, notification)
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
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
            registerReceiver(smsReceiver, filter, RECEIVER_NOT_EXPORTED)
        else
            @Suppress("UnspecifiedRegisterReceiverFlag")
            registerReceiver(smsReceiver, filter)
    }

    private fun broadcastState(running: Boolean) {
        sendBroadcast(Intent(ACTION_STATE_CHANGED).apply {
            putExtra(EXTRA_RUNNING, running)
            setPackage(packageName)
        })
    }

    override fun onDestroy() {
        super.onDestroy()
        smsReceiver?.let { runCatching { unregisterReceiver(it) }; it.release() }
        smsReceiver = null
        @Suppress("DEPRECATION")
        stopForeground(true)
        broadcastState(false)
    }
}
