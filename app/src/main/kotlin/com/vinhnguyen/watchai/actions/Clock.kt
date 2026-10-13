package io.github.alisial786.watchgpt.actions

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.provider.AlarmClock
import android.provider.Settings

/** Where timers and alarms ring. Results are short sentences for the model: "ok: …" or "error: …". */
interface Clock {
    suspend fun setTimer(
        seconds: Int,
        label: String?,
    ): String

    suspend fun setAlarm(
        hour: Int,
        minute: Int,
        label: String?,
        days: List<Int> = emptyList(),
    ): String
}

/** The watch while the user talks through it: its own clock, and its battery. */
interface Watch : Clock {
    suspend fun battery(): String
}

/** Clock requests work directly while visible or via an explicit notification tap in the background. */
class PhoneClock(context: Context, private val onScreen: () -> Boolean) : Clock {
    private val appContext = context.applicationContext
    fun worksFromPocket(): Boolean = Settings.canDrawOverlays(appContext)
    override suspend fun setTimer(seconds: Int, label: String?): String = io.github.alisial786.watchgpt.organizer.NativeClock.request(appContext, onScreen() || worksFromPocket(), seconds = seconds, label = label)
    override suspend fun setAlarm(hour: Int, minute: Int, label: String?, days: List<Int>): String = io.github.alisial786.watchgpt.organizer.NativeClock.request(appContext, onScreen() || worksFromPocket(), hour = hour, minute = minute, label = label, days = days)
}
