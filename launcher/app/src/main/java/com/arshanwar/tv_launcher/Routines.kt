package com.arshanwar.tv_launcher

import android.app.Activity
import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.view.Gravity
import android.view.KeyEvent
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import org.json.JSONArray
import org.json.JSONObject
import java.util.Calendar

/**
 * Routines: "at 8:00 PM every day, open The Office on Netflix". One exact alarm (setAlarmClock, which fires even in
 * standby/doze) is armed for the soonest routine; when it fires the TV screen is switched on and RoutineActivity counts
 * down 10s (Back cancels) before opening the show. Re-armed after boot and whenever the list changes.
 */
object Routines {
    /** days: bit 0 = Sunday … bit 6 = Saturday; 0 = once. */
    class Routine(val id: String, val hour: Int, val minute: Int, val days: Int, val pkg: String, val url: String, val title: String)

    const val EVERY_DAY = 0x7F
    const val WEEKDAYS = 0x3E
    const val WEEKENDS = 0x41

    private fun prefs(c: Context) = c.getSharedPreferences("launcher", Context.MODE_PRIVATE)

    fun all(c: Context): List<Routine> = try {
        val a = JSONArray(prefs(c).getString("routines", "[]"))
        (0 until a.length()).map { a.getJSONObject(it).let { o ->
            Routine(o.getString("id"), o.getInt("h"), o.getInt("m"), o.optInt("days"), o.getString("pkg"), o.optString("url"), o.optString("title")) } }
    } catch (e: Exception) { emptyList() }

    private fun save(c: Context, l: List<Routine>) {
        prefs(c).edit().putString("routines", JSONArray(l.map {
            JSONObject().put("id", it.id).put("h", it.hour).put("m", it.minute).put("days", it.days).put("pkg", it.pkg).put("url", it.url).put("title", it.title)
        }).toString()).apply()
        schedule(c)
    }

    fun add(c: Context, r: Routine) = save(c, all(c) + r)
    fun delete(c: Context, id: String) = save(c, all(c).filter { it.id != id })
    fun newId() = java.util.UUID.randomUUID().toString().take(8)

    /** Next time this routine fires, after [from]. */
    fun next(r: Routine, from: Long = System.currentTimeMillis()): Long {
        val cal = Calendar.getInstance().apply { timeInMillis = from; set(Calendar.HOUR_OF_DAY, r.hour); set(Calendar.MINUTE, r.minute); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0) }
        for (i in 0..7) {
            if (cal.timeInMillis > from && (r.days == 0 || r.days and (1 shl (cal.get(Calendar.DAY_OF_WEEK) - 1)) != 0)) return cal.timeInMillis
            cal.add(Calendar.DAY_OF_MONTH, 1)
        }
        return cal.timeInMillis
    }

    fun time(h: Int, m: Int): String = String.format(java.util.Locale.US, "%d:%02d %s", if (h % 12 == 0) 12 else h % 12, m, if (h < 12) "AM" else "PM")
    fun repeat(days: Int) = when (days) { 0 -> "Once"; EVERY_DAY -> "Every day"; WEEKDAYS -> "Weekdays"; WEEKENDS -> "Weekends"; else -> "Custom" }
    fun describe(r: Routine) = "${time(r.hour, r.minute)}  ·  ${repeat(r.days)}"

    private fun pending(c: Context, id: String?) = PendingIntent.getBroadcast(c, 7, Intent(c, RoutineReceiver::class.java).putExtra("id", id),
        PendingIntent.FLAG_UPDATE_CURRENT)

    /**
     * Shows the routine (screen on + countdown). Called by the alarm and by the in-process watcher; [at] = the scheduled
     * minute, so the two can't both fire the same occurrence.
     */
    @Synchronized fun fire(c: Context, r: Routine, at: Long) {
        val key = "${r.id}@$at"
        if (prefs(c).getString("routine_fired", null) == key) return
        prefs(c).edit().putString("routine_fired", key).apply()
        android.util.Log.i("TvHome", "routine ${r.title} firing")
        val pm = c.getSystemService(Context.POWER_SERVICE) as PowerManager
        @Suppress("DEPRECATION")
        pm.newWakeLock(PowerManager.SCREEN_BRIGHT_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP, "arshtv:routine").acquire(20_000)
        RoutineActivity.lastFired = r   // one-off routines are deleted below, before the activity reads them
        val start = Intent(c, RoutineActivity::class.java).putExtra("id", r.id).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        try { c.startActivity(start) } catch (e: Exception) { KeyService.instance?.startActivity(start) }
        if (r.days == 0) delete(c, r.id)
    }

    /**
     * MiTV turns every app's wake-up alarm into a "when the screen is next on" alarm while the TV is in standby, so the
     * alarm alone never fires from standby. Standby keeps the CPU running though, so KeyService's process also checks
     * every 20s (a sleeping thread: no measurable cost).
     */
    @Volatile private var watching = false
    fun watch(ctx: Context) {
        if (watching) return
        watching = true
        val c = ctx.applicationContext
        Thread {
            android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND)
            var last = System.currentTimeMillis()
            while (true) {
                try { Thread.sleep(20_000) } catch (e: InterruptedException) { break }
                val now = System.currentTimeMillis()
                for (r in all(c)) { val due = next(r, last - 1); if (due <= now && now - due < 15 * 60_000) Handler(Looper.getMainLooper()).post { fire(c, r, due) } }
                last = now
            }
        }.apply { isDaemon = true; name = "routine-watch" }.start()
    }

    /** Arms one alarm for whichever routine is due first. */
    fun schedule(c: Context) {
        val am = c.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val soonest = all(c).minByOrNull { next(it) }
        if (soonest == null) { am.cancel(pending(c, null)); return }
        val show = PendingIntent.getActivity(c, 8, Intent(c, MainActivity::class.java), 0)
        am.setAlarmClock(AlarmManager.AlarmClockInfo(next(soonest), show), pending(c, soonest.id))
    }
}

class RoutineReceiver : BroadcastReceiver() {
    override fun onReceive(c: Context, i: Intent) {
        val now = System.currentTimeMillis()
        Routines.all(c).firstOrNull { it.id == i.getStringExtra("id") }?.let { r ->
            val due = Routines.next(r, now - 5 * 60_000)
            when {
                due <= now -> Routines.fire(c, r, due)
                r.days == 0 -> Routines.delete(c, r.id)   // stale one-off (delivered late, when the screen came back on): drop it
            }
        }
        Routines.schedule(c)
    }
}

/** Full-screen "Starting soon" card: 10-second countdown, OK = now, Back = cancel. Turns the screen on by itself. */
class RoutineActivity : Activity() {
    private val main = Handler(Looper.getMainLooper())
    private var left = 10
    private lateinit var count: TextView
    private var r: Routines.Routine? = null
    private var done = false

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        setShowWhenLocked(true); setTurnScreenOn(true)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON or WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON)
        enableOwnAnimations(); Fonts.init(this)
        window.setBackgroundDrawable(ColorDrawable(0xF2050608.toInt()))
        // the id is kept in the intent; one-off routines are already deleted, so fall back to a copy passed along
        val id = intent.getStringExtra("id")
        r = Routines.all(this).firstOrNull { it.id == id } ?: lastFired?.takeIf { it.id == id }
        val rt = r ?: run { finish(); return }
        lastFired = rt
        val d = { v: Int -> Kit.dp(this, v) }
        val pm = packageManager
        val ai = try { pm.getApplicationInfo(rt.pkg, 0) } catch (e: Exception) { null }
        val appName = ai?.let { pm.getApplicationLabel(it).toString() } ?: rt.pkg
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL; background = Kit.card(this@RoutineActivity)
            setPadding(d(40), d(34), d(40), d(30)); clipChildren = false; clipToPadding = false
        }
        val banner = ai?.let { try { pm.getApplicationBanner(it) } catch (e: Exception) { null } ?: pm.getApplicationIcon(it) }
        card.addView(ImageView(this).apply { setImageDrawable(banner); scaleType = ImageView.ScaleType.FIT_CENTER }, LinearLayout.LayoutParams(d(200), d(112)))
        card.addView(TextView(this).apply { text = "IT'S ${Routines.time(rt.hour, rt.minute)}"; typeface = Fonts.semibold; textSize = 12f; letterSpacing = 0.08f
            setTextColor(0x99FFFFFF.toInt()); gravity = Gravity.CENTER; setPadding(0, d(20), 0, 0) })
        card.addView(TextView(this).apply { text = rt.title.ifBlank { appName }; typeface = Fonts.display; textSize = 28f; setTextColor(Color.WHITE); gravity = Gravity.CENTER
            setPadding(0, d(4), 0, 0); maxLines = 2 }, LinearLayout.LayoutParams(d(520), -2))
        if (rt.title.isNotBlank() && rt.title != appName) card.addView(TextView(this).apply { text = "on $appName"; typeface = Fonts.medium; textSize = 15f
            setTextColor(0xB3FFFFFF.toInt()); gravity = Gravity.CENTER })
        count = TextView(this).apply { typeface = Fonts.medium; textSize = 15f; setTextColor(0xD9FFFFFF.toInt()); gravity = Gravity.CENTER; setPadding(0, d(18), 0, d(16)) }
        card.addView(count)
        val btns = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER; clipChildren = false }
        val now = Kit.button(this, "Open now", primary = true) { open() }
        val cancel = Kit.button(this, "Cancel") { done = true; finish() }
        btns.addView(now, LinearLayout.LayoutParams(d(170), d(48)).apply { rightMargin = d(12) })
        btns.addView(cancel, LinearLayout.LayoutParams(d(150), d(48)))
        card.addView(btns)
        val root = FrameLayout(this)
        root.addView(card, FrameLayout.LayoutParams(-2, -2, Gravity.CENTER))
        setContentView(root)
        card.alpha = 0f; card.scaleX = 0.92f; card.scaleY = 0.92f
        card.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(500).setInterpolator(appleOut).start()
        now.requestFocus()
        tick()
    }

    private fun tick() {
        if (done) return
        count.text = "Opening in $left…"
        if (left-- <= 0) { open(); return }
        main.postDelayed({ tick() }, 1000)
    }

    private fun open() {
        if (done) return
        done = true
        val rt = r ?: return finish()
        val view = rt.url.takeIf { it.isNotBlank() }?.let { Intent(Intent.ACTION_VIEW, Uri.parse(it)).setPackage(rt.pkg).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
        val launch = packageManager.getLeanbackLaunchIntentForPackage(rt.pkg) ?: packageManager.getLaunchIntentForPackage(rt.pkg)
        try { startActivity(view ?: launch?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) ?: return finish()) }
        catch (e: Exception) { try { launch?.let { startActivity(it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } } catch (e2: Exception) {} }
        finish()
    }

    override fun onKeyDown(k: Int, e: KeyEvent?): Boolean {
        if (k == KeyEvent.KEYCODE_BACK) { done = true; finish(); return true }
        return super.onKeyDown(k, e)
    }

    override fun onDestroy() { main.removeCallbacksAndMessages(null); super.onDestroy() }

    companion object { var lastFired: Routines.Routine? = null }
}
