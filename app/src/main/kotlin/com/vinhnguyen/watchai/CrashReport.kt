package io.github.alisial786.watchgpt

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.Context
import android.os.Build
import android.util.Log
import androidx.core.content.edit
import java.io.File
import java.time.Instant

/**
 * What made WatchGPT close last time, kept on the phone only, so the owner can copy it into a bug report. Nothing is
 * sent anywhere. Covers Kotlin/Java crashes (caught here) and, from Android's own record, native crashes and
 * "not responding" closes.
 */
object CrashReport {
    private const val FILE = "last_crash.txt"
    private const val SEEN = "crash_report_seen"
    private const val MAX_CHARS = 12_000

    fun install(context: Context) {
        val app = context.applicationContext
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            runCatching { write(app, "Crash in thread ${thread.name}\n${Log.getStackTraceString(error)}") }
            previous?.uncaughtException(thread, error)
        }
    }

    /** The report waiting to be shown, if WatchGPT closed unexpectedly since the last one was dismissed. */
    fun pending(context: Context): String? {
        file(context).takeIf { it.exists() }?.let { return runCatching { it.readText() }.getOrNull() }
        return runCatching { fromAndroid(context) }.getOrNull()
    }

    fun dismiss(context: Context) {
        file(context).delete()
        prefs(context).edit { putLong(SEEN, System.currentTimeMillis()) }
    }

    private fun write(context: Context, body: String) {
        file(context).writeText(header(context) + body.take(MAX_CHARS))
    }

    // Native crashes and freezes never reach the handler above; Android keeps a record of them.
    private fun fromAndroid(context: Context): String? {
        val seen = prefs(context).getLong(SEEN, 0)
        val exit = context.getSystemService(ActivityManager::class.java).getHistoricalProcessExitReasons(context.packageName, 0, 1).firstOrNull() ?: return null
        if (exit.timestamp <= seen) return null
        val kind = when (exit.reason) {
            ApplicationExitInfo.REASON_CRASH_NATIVE -> "Native crash"
            ApplicationExitInfo.REASON_ANR -> "Not responding"
            else -> return null
        }
        val trace = if (exit.reason == ApplicationExitInfo.REASON_ANR) runCatching { exit.traceInputStream?.bufferedReader()?.use { it.readText() } }.getOrNull() else null
        return header(context) + "$kind at ${Instant.ofEpochMilli(exit.timestamp)}: ${exit.description.orEmpty()}\n" + trace.orEmpty().take(MAX_CHARS)
    }

    private fun header(context: Context): String {
        val version = runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull()
        return "WatchGPT $version · Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT}) · ${Build.MANUFACTURER} ${Build.MODEL} · ${Instant.now()}\n"
    }

    private fun file(context: Context) = File(context.noBackupFilesDir, FILE)

    private fun prefs(context: Context) = context.getSharedPreferences("crash_report", Context.MODE_PRIVATE)
}
