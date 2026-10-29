package com.arshanwar.tv_launcher

import android.app.Activity
import android.content.Context
import android.content.Intent

object Power {
    /** Start the screensaver right away (falls back to our lock screen if the system dream launcher isn't there). */
    fun screensaverNow(a: Activity) {
        val dream = Intent(Intent.ACTION_MAIN).setClassName("com.android.systemui", "com.android.systemui.Somnambulator")
        try { a.startActivity(dream) } catch (e: Exception) { a.startActivity(Intent(a, LockActivity::class.java)) }
    }
}

/**
 * Drives the TV's own hardware sleep timer (the one in Xiaomi's power menu): MiTV Settings listens for this
 * broadcast without requiring a permission. value = minutes / 10 (1, 3, 6, 9, 12); 0 cancels. Because it's the
 * TV's native timer, it powers off properly even while Netflix etc. hold the screen awake.
 */
object SleepTimer {
    val choices = listOf(0, 10, 30, 60, 90, 120)
    private fun prefs(c: Context) = c.getSharedPreferences("launcher", Context.MODE_PRIVATE)

    fun minutesLeft(c: Context): Int {
        val at = prefs(c).getLong("sleep_at", 0)
        return if (at > System.currentTimeMillis()) ((at - System.currentTimeMillis() + 59_999) / 60_000).toInt() else 0
    }

    fun set(c: Context, minutes: Int) {
        c.sendBroadcast(Intent("com.android.systemui.globalactions.timer").putExtra("value", minutes / 10))
        prefs(c).edit().putLong("sleep_at", if (minutes > 0) System.currentTimeMillis() + minutes * 60_000L else 0).apply()
        arm(c)
    }

    /** Heads-up banner 5 minutes before the TV switches itself off (shown over whatever is playing). */
    fun arm(c: Context) {
        val at = prefs(c).getLong("sleep_at", 0)
        if (at - System.currentTimeMillis() > 5 * 60_000) Banner.at(c, at - 5 * 60_000, "sleep", "TV turns off in 5 minutes", "Sleep timer \u00b7 change it in Control Center")
        else Banner.cancel("sleep")
    }

    /** Next value when the Control Center tile is pressed: Off → 10 → 30 → 60 → 90 → 120 → Off. */
    fun cycle(c: Context): Int {
        val left = minutesLeft(c)
        val next = choices.firstOrNull { it > left } ?: 0
        set(c, next); return next
    }
}
