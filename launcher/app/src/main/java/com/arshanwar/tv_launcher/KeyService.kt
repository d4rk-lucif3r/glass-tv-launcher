package com.arshanwar.tv_launcher

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import kotlin.concurrent.thread

/**
 * Makes the Xiaomi remote's Home and PatchWall buttons open this launcher.
 *
 * Neither button sends KEYCODE_HOME:
 *  - Home sends KEYCODE_ALL_APPS. The window policy consumes it (so accessibility never sees it on
 *    Android 10) and fires an ALL_APPS intent at Settings, which has no handler, so nothing happens.
 *  - PatchWall sends keycode 192, a "global key" broadcast straight to MiTV's partnercustomizer, which
 *    tries to launch the removed PatchWall app.
 * Both leave a line in the system log, so this service (kept alive by the system) watches the log
 * (READ_LOGS, granted over adb) and brings up the launcher when either appears. onKeyEvent stays as a
 * fallback for builds where the key does reach accessibility.
 */
class KeyService : AccessibilityService() {
    private val homeKeys = setOf(KeyEvent.KEYCODE_ALL_APPS, 192, KeyEvent.KEYCODE_HOME)
    private val main = Handler(Looper.getMainLooper())
    @Volatile private var running = false
    @Volatile private var proc: Process? = null
    private var lastHome = 0L
    private var lastWasDouble = false

    private val screenOff = object : android.content.BroadcastReceiver() {
        override fun onReceive(c: android.content.Context?, i: Intent?) = AppLock.relockAll()   // TV off → locked apps need the PIN again
    }

    override fun onServiceConnected() {
        running = true
        instance = this
        registerReceiver(screenOff, android.content.IntentFilter(Intent.ACTION_SCREEN_OFF))
        ShareHub.start(this)   // phones can send to the TV even while another app is open
        SleepTimer.arm(this); AdbTimer.arm(this); Routines.schedule(this); Routines.watch(this)   // re-schedule heads-up banners after a restart
        thread(name = "home-button-watch", isDaemon = true) { watchLog() }
    }

    override fun onDestroy() {
        running = false
        instance = null
        try { unregisterReceiver(screenOff) } catch (e: Exception) {}
        proc?.destroy()
        super.onDestroy()
    }

    private fun watchLog() {
        while (running) {
            try {
                // -T 1: start at the newest line, never replay old presses
                val p = Runtime.getRuntime().exec(arrayOf("logcat", "-v", "brief", "-T", "1", "-s", "ActivityTaskManager:I", "GlobalKey:V"))
                proc = p
                p.inputStream.bufferedReader().forEachLine { line ->
                    val homePress = line.contains("act=android.intent.action.ALL_APPS") && line.contains("START u0")
                    val patchWallPress = line.contains("ACTION_GLOBAL_BUTTON keyCode = 192")
                    if (homePress || patchWallPress) {
                        android.util.Log.i("TvHome", "remote ${if (homePress) "Home" else "PatchWall"} press seen")
                        main.post { goHome(if (homePress) 250 else 800) }
                    }
                }
            } catch (e: Exception) {
                android.util.Log.w("TvHome", "home-button log watch failed", e)
            }
            if (running) SystemClock.sleep(2000) // logcat died: restart it
        }
    }

    /** [dedupeMs]: PatchWall logs every press twice (down + up); Home logs once. Two Home presses in quick succession = app switcher. */
    private fun goHome(dedupeMs: Long = 800) {
        val now = SystemClock.uptimeMillis()
        val gap = now - lastHome
        if (gap < dedupeMs) return
        lastHome = now
        val double = dedupeMs < 800 && gap < 750 && !lastWasDouble
        lastWasDouble = double
        LockDream.current?.finish()   // the screensaver is interactive (◀ ▶ skip), so Home must end it explicitly
        android.util.Log.i("TvHome", "opening launcher" + if (double) " (app switcher)" else "")
        startActivity(
            Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME).setPackage(packageName)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
                .putExtra("switcher", double)
        )
    }

    override fun onKeyEvent(event: KeyEvent): Boolean {
        if (event.keyCode !in homeKeys) return false
        if (event.action == KeyEvent.ACTION_UP) goHome(250)
        return true
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event?.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) event.packageName?.toString()?.let { AppLock.onForeground(this, it) }
    }

    /** Phone keyboard: put [text] into whatever text box is focused on the TV. Returns false when there is none. */
    fun typeInto(text: String): Boolean {
        val node = rootInActiveWindow?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT) ?: return false
        if (!node.isEditable) return false
        val args = android.os.Bundle().apply { putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text) }
        return node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
    }

    companion object { @Volatile var instance: KeyService? = null }
    override fun onInterrupt() {}
}
