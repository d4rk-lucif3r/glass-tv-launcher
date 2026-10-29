package com.arshanwar.tv_launcher

import android.app.Activity
import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView

/**
 * tvOS-style notification banner: a dark rounded card that slides in at the top-right, waits, and slides away.
 * Inside our own activities it's a plain view; over other apps (Netflix etc.) it goes in KeyService's
 * accessibility overlay window, which needs no extra permission. One banner at a time; no work when idle.
 */
object Banner {
    private val main = Handler(Looper.getMainLooper())
    private var current: Pair<View, () -> Unit>? = null

    fun show(ctx: Context, title: String, sub: String? = null, icon: Drawable? = null, ms: Long = 3800) = main.post {
        current?.second?.invoke(); current = null
        val a = ctx as? Activity
        val svc = KeyService.instance
        when {
            a != null && !a.isFinishing -> {
                val decor = a.window.decorView as ViewGroup
                val v = view(a, title, sub, icon)
                decor.addView(v, FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.END).apply { topMargin = Kit.dp(a, 30); rightMargin = Kit.dp(a, 34) })
                present(v, ms) { decor.removeView(v) }
            }
            svc != null -> {
                val wm = svc.getSystemService(Context.WINDOW_SERVICE) as WindowManager
                val v = view(svc, title, sub, icon)
                val lp = WindowManager.LayoutParams(-2, -2, WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                    PixelFormat.TRANSLUCENT).apply { gravity = Gravity.TOP or Gravity.END; x = Kit.dp(svc, 34); y = Kit.dp(svc, 30) }
                try { wm.addView(v, lp); present(v, ms) { try { wm.removeView(v) } catch (e: Exception) {} } } catch (e: Exception) {}
            }
            else -> android.widget.Toast.makeText(ctx.applicationContext, listOfNotNull(title, sub).joinToString("\n"), android.widget.Toast.LENGTH_LONG).show()
        }
    }

    private fun present(v: View, ms: Long, remove: () -> Unit) {
        val dp = v.resources.displayMetrics.density
        current = v to remove
        v.alpha = 0f; v.translationX = 46 * dp
        v.animate().alpha(1f).translationX(0f).setDuration(460).setInterpolator(appleOut).start()
        v.postDelayed({
            v.animate().alpha(0f).translationX(30 * dp).setDuration(280).setInterpolator(appleEase).withEndAction {
                remove(); if (current?.first === v) current = null
            }.start()
        }, ms)
    }

    private fun view(c: Context, title: String, sub: String?, icon: Drawable?): View {
        Fonts.init(c)
        val d = { v: Int -> Kit.dp(c, v) }
        val row = LinearLayout(c).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(0xF5303240.toInt(), 0xF51D1E26.toInt())).apply {
                cornerRadius = d(20).toFloat(); setStroke(d(1), 0x2EFFFFFF)
            }
            setPadding(d(16), d(13), d(22), d(13)); elevation = d(18).toFloat(); minimumWidth = d(250)
        }
        if (icon != null) row.addView(ImageView(c).apply { setImageDrawable(icon); scaleType = ImageView.ScaleType.FIT_CENTER },
            LinearLayout.LayoutParams(d(34), d(34)).apply { rightMargin = d(13) })
        val col = LinearLayout(c).apply { orientation = LinearLayout.VERTICAL }
        col.addView(TextView(c).apply { text = title; typeface = Fonts.semibold; textSize = 15f; setTextColor(Color.WHITE); maxLines = 2 })
        if (!sub.isNullOrBlank()) col.addView(TextView(c).apply { text = sub; typeface = Fonts.regular; textSize = 12.5f; setTextColor(0xA6FFFFFF.toInt()); maxLines = 2; setPadding(0, d(2), 0, 0) })
        row.addView(col, LinearLayout.LayoutParams(-2, -2))
        row.maxWidth(d(420))
        return row
    }
    private fun LinearLayout.maxWidth(w: Int) { (getChildAt(childCount - 1) as LinearLayout).let { col -> for (i in 0 until col.childCount) (col.getChildAt(i) as TextView).maxWidth = w } }

    // ---- banners at a future time (process stays alive: KeyService is a bound accessibility service) ----
    fun at(ctx: Context, wallTime: Long, key: String, title: String, sub: String? = null) {
        cancel(key)
        val delay = wallTime - System.currentTimeMillis()
        if (delay <= 0) return
        val app = ctx.applicationContext
        main.postAtTime({ show(app, title, sub, ms = 6000) }, key, SystemClock.uptimeMillis() + delay)
    }
    fun cancel(key: String) = main.removeCallbacksAndMessages(key)
    fun later(key: String, wallTime: Long, r: () -> Unit) {
        main.removeCallbacksAndMessages(key)
        main.postAtTime({ r() }, key, SystemClock.uptimeMillis() + (wallTime - System.currentTimeMillis()).coerceAtLeast(0))
    }
}

/** ADB access window opened from the launcher: warns 5 min before, then switches ADB off even if home isn't open. */
object AdbTimer {
    private fun prefs(c: Context) = c.getSharedPreferences("launcher", Context.MODE_PRIVATE)
    fun arm(ctx: Context) {
        val app = ctx.applicationContext
        val until = prefs(app).getLong("adb_until", 0)
        if (until == 0L) { Banner.cancel("adb_warn"); Banner.cancel("adb_off"); return }
        Banner.at(app, until - 5 * 60_000, "adb_warn", "ADB turns off in 5 minutes", "Turn it on again from the Wi-Fi panel if you still need it")
        Banner.later("adb_off", until) {
            if (prefs(app).getLong("adb_until", 0) == 0L) return@later
            try { Settings.Global.putInt(app.contentResolver, Settings.Global.ADB_ENABLED, 0) } catch (e: Exception) {}
            prefs(app).edit().remove("adb_until").apply()
            Banner.show(app, "ADB access turned off")
        }
    }
}


/** Process-wide state for the app switcher (outlives the activity). */
object Recents { val closedAt = HashMap<String, Long>() }
