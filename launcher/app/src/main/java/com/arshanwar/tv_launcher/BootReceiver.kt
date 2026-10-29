package com.arshanwar.tv_launcher

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Settings

/**
 * Android removes an accessibility service from the enabled list whenever its app is force-stopped
 * (by adb, or by MiTV's TV Manager cleaner), which silently kills the remote's Home button.
 * The launcher re-enables KeyService itself; this needs WRITE_SECURE_SETTINGS, granted over adb.
 */
object KeyServiceGuard {
    fun ensure(ctx: Context) {
        val me = "${ctx.packageName}/${KeyService::class.java.name}"
        val cr = ctx.contentResolver
        try {
            val cur = Settings.Secure.getString(cr, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES).orEmpty()
            if (cur.split(':').none { it.equals(me, ignoreCase = true) }) {
                Settings.Secure.putString(cr, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, if (cur.isBlank()) me else "$cur:$me")
            }
            if (Settings.Secure.getInt(cr, Settings.Secure.ACCESSIBILITY_ENABLED, 0) != 1) {
                Settings.Secure.putInt(cr, Settings.Secure.ACCESSIBILITY_ENABLED, 1)
            }
        } catch (e: SecurityException) {
            android.util.Log.w("TvHome", "WRITE_SECURE_SETTINGS not granted; can't re-enable the Home button service", e)
        }
    }
}

/** Re-arms the Home button service after every boot and after the launcher is updated. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent?) { KeyServiceGuard.ensure(ctx); Routines.schedule(ctx) }   // alarms don't survive a reboot
}
