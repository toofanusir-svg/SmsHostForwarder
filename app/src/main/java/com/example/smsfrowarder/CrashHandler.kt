package com.example.smsfrowarder

import android.content.Context
import android.content.Intent
import android.os.Build
import kotlin.system.exitProcess

/**
 * Catches every uncaught exception, saves it to a file, then opens
 * CrashActivity so the user (or developer) can see and copy the stack trace.
 */
class CrashHandler(private val appContext: Context) : Thread.UncaughtExceptionHandler {

    private val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()

    override fun uncaughtException(thread: Thread, throwable: Throwable) {
        try {
            val trace = buildString {
                appendLine("=== CRASH REPORT ===")
                appendLine("Android: ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
                appendLine("Device : ${Build.MANUFACTURER} ${Build.MODEL}")
                appendLine("Thread : ${thread.name}")
                appendLine()
                appendLine(throwable.stackTraceToString())
            }

            // Save to file so CrashActivity can read it
            appContext.openFileOutput("last_crash.txt", Context.MODE_PRIVATE)
                .bufferedWriter().use { it.write(trace) }

            // Launch CrashActivity (new task — current activity stack is dead)
            val intent = Intent(appContext, CrashActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
                putExtra(CrashActivity.EXTRA_TRACE, trace)
            }
            appContext.startActivity(intent)

        } catch (_: Exception) {
            // If crash handler itself crashes, fall through to default
        }

        defaultHandler?.uncaughtException(thread, throwable)
        exitProcess(1)
    }
}
