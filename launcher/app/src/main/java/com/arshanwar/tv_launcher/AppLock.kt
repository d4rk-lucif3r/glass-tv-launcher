package com.arshanwar.tv_launcher

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.GridLayout
import android.widget.LinearLayout
import android.widget.TextView

/**
 * Hidden + PIN-locked apps. One launcher PIN (the same one that guards ADB) protects everything.
 * KeyService sees every app that comes to the front and shows PinGateActivity over locked ones.
 */
object AppLock {
    private const val GRACE_MS = 10 * 60_000L
    private val unlockedUntil = HashMap<String, Long>()
    private fun prefs(c: Context) = c.getSharedPreferences("launcher", Context.MODE_PRIVATE)

    fun hash(c: Context, p: String) = java.security.MessageDigest.getInstance("SHA-256")
        .digest("${c.packageName}:$p".toByteArray()).joinToString("") { "%02x".format(it) }
    fun hasPin(c: Context) = prefs(c).getString("adb_pin", null) != null
    fun setPin(c: Context, p: String) = prefs(c).edit().putString("adb_pin", hash(c, p)).apply()
    fun check(c: Context, p: String) = prefs(c).getString("adb_pin", null) == hash(c, p)

    fun hidden(c: Context): Set<String> = prefs(c).getStringSet("hidden", emptySet())!!.toSet()
    fun locked(c: Context): Set<String> = prefs(c).getStringSet("locked", emptySet())!!.toSet()
    fun setHidden(c: Context, pkg: String, on: Boolean) = prefs(c).edit().putStringSet("hidden", hidden(c).let { if (on) it + pkg else it - pkg }).apply()
    fun setLocked(c: Context, pkg: String, on: Boolean) = prefs(c).edit().putStringSet("locked", locked(c).let { if (on) it + pkg else it - pkg }).apply()

    fun isOpen(pkg: String) = (unlockedUntil[pkg] ?: 0) > System.currentTimeMillis()
    fun grant(pkg: String) { unlockedUntil[pkg] = System.currentTimeMillis() + GRACE_MS }
    fun relockAll() = unlockedUntil.clear()

    /** Locked app came to the front (any route: launcher, deep link, recents) → PIN gate on top. */
    fun onForeground(c: Context, pkg: String) {
        if (pkg == c.packageName || pkg !in locked(c) || isOpen(pkg)) return
        c.startActivity(Intent(c, PinGateActivity::class.java).putExtra("pkg", pkg).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP))
    }
}

/** Full-screen PIN pad shown over a locked app. Wrong / cancelled → back to the home screen. */
class PinGateActivity : Activity() {
    private val main = Handler(Looper.getMainLooper())
    private var entry = ""
    private lateinit var dots: List<View>
    private lateinit var title: TextView
    private lateinit var card: LinearLayout
    private var fails = 0
    private val pkg get() = intent.getStringExtra("pkg") ?: ""

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        enableOwnAnimations(); Fonts.init(this)
        window.setBackgroundDrawable(ColorDrawable(0xF2050608.toInt()))   // hide the app underneath
        val d = { v: Int -> Kit.dp(this, v) }
        val label = try { packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg, 0)).toString() } catch (e: Exception) { "this app" }
        card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL; background = Kit.card(this@PinGateActivity)
            setPadding(d(30), d(26), d(30), d(24)); clipChildren = false; clipToPadding = false
        }
        title = TextView(this).apply { text = "Enter PIN to open $label"; typeface = Fonts.display; textSize = 20f; setTextColor(Color.WHITE); gravity = Gravity.CENTER }
        card.addView(title, LinearLayout.LayoutParams(d(330), -2))
        val dotRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER; setPadding(0, d(16), 0, d(14)) }
        dots = (0 until 4).map { View(this).also { v -> dotRow.addView(v, LinearLayout.LayoutParams(d(14), d(14)).apply { leftMargin = d(8); rightMargin = d(8) }) } }
        card.addView(dotRow)
        val grid = GridLayout(this).apply { columnCount = 3; clipChildren = false }
        var first: View? = null
        for (k in listOf("1", "2", "3", "4", "5", "6", "7", "8", "9", "⌫", "0", "Cancel")) {
            val key = Kit.button(this, k) { press(k) }
            grid.addView(key, GridLayout.LayoutParams().apply { width = d(84); height = d(50); setMargins(d(5), d(5), d(5), d(5)) })
            if (first == null) first = key
        }
        card.addView(grid)
        val root = FrameLayout(this)
        root.addView(card, FrameLayout.LayoutParams(-2, -2, Gravity.CENTER))
        setContentView(root)
        paint(); first?.requestFocus()
    }

    private fun press(k: String) {
        when (k) {
            "Cancel" -> goHome()
            "⌫" -> { entry = entry.dropLast(1); paint() }
            else -> if (entry.length < 4) {
                entry += k; paint()
                if (entry.length == 4) main.postDelayed({
                    if (AppLock.check(this, entry)) { AppLock.grant(pkg); finish() }
                    else {
                        fails++; entry = ""; paint(); title.text = if (fails >= 5) "Too many tries" else "Wrong PIN, try again"
                        card.animate().translationX(Kit.dp(this, 14).toFloat()).setDuration(60).withEndAction {
                            card.animate().translationX(-Kit.dp(this, 14).toFloat()).setDuration(80).withEndAction { card.animate().translationX(0f).setDuration(70).start() }.start()
                        }.start()
                        if (fails >= 5) main.postDelayed({ goHome() }, 900)
                    }
                }, 150)
            }
        }
    }

    private fun paint() = dots.forEachIndexed { i, v ->
        v.background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            if (i < entry.length) setColor(Color.WHITE) else { setColor(Color.TRANSPARENT); setStroke(Kit.dp(this@PinGateActivity, 2), 0xB3FFFFFF.toInt()) }
        }
    }

    private fun goHome() {
        startActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME).setPackage(packageName).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        finish()
    }

    override fun onBackPressed() = goHome()
}

/** App folders, stored as JSON: [{id, name, pos, pkgs[]}]. pos = slot in the grid. */
object Folders {
    class Folder(val id: String, val name: String, val pos: Int, val pkgs: List<String>)
    private fun prefs(c: Context) = c.getSharedPreferences("launcher", Context.MODE_PRIVATE)
    fun all(c: Context): List<Folder> = try {
        val a = org.json.JSONArray(prefs(c).getString("folders", "[]"))
        (0 until a.length()).map { a.getJSONObject(it).let { o ->
            val p = o.getJSONArray("pkgs"); Folder(o.getString("id"), o.getString("name"), o.optInt("pos"), (0 until p.length()).map { i -> p.getString(i) }) } }
    } catch (e: Exception) { emptyList() }
    private fun save(c: Context, l: List<Folder>) = prefs(c).edit().putString("folders", org.json.JSONArray(l.filter { it.pkgs.isNotEmpty() }.map {
        org.json.JSONObject().put("id", it.id).put("name", it.name).put("pos", it.pos).put("pkgs", org.json.JSONArray(it.pkgs)) }).toString()).apply()
    private fun edit(c: Context, id: String, f: (Folder) -> Folder) = save(c, all(c).map { if (it.id == id) f(it) else it })
    /** An app lives in at most one folder. */
    private fun stripped(c: Context, pkg: String) = all(c).map { Folder(it.id, it.name, it.pos, it.pkgs - pkg) }
    fun create(c: Context, name: String, pkg: String, pos: Int) = save(c, stripped(c, pkg) + Folder(java.util.UUID.randomUUID().toString().take(8), name, pos, listOf(pkg)))
    fun add(c: Context, id: String, pkg: String) = save(c, stripped(c, pkg).map { if (it.id == id) Folder(it.id, it.name, it.pos, it.pkgs + pkg) else it })
    fun remove(c: Context, id: String, pkg: String) = edit(c, id) { Folder(it.id, it.name, it.pos, it.pkgs - pkg) }
    fun rename(c: Context, id: String, name: String) = edit(c, id) { Folder(it.id, name, it.pos, it.pkgs) }
    fun setPos(c: Context, id: String, pos: Int) = edit(c, id) { Folder(it.id, it.name, pos, it.pkgs) }
    fun delete(c: Context, id: String) = save(c, all(c).filter { it.id != id })
}
