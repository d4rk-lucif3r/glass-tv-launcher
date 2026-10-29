package com.arshanwar.tv_launcher

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.*
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.widget.*
import java.io.File

/** Shared look for the share/file screens: dark frosted cards and tvOS-style buttons. */
object Kit {
    fun dp(c: Context, v: Int) = (v * c.resources.displayMetrics.density).toInt()

    fun card(c: Context, radiusDp: Int = 28) = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
        intArrayOf(0xF22A2C38.toInt(), 0xF2181920.toInt())).apply {
        cornerRadius = dp(c, radiusDp).toFloat(); setStroke(dp(c, 1), 0x33FFFFFF)
    }

    fun button(c: Context, label: String, primary: Boolean = false, onClick: () -> Unit): TextView = TextView(c).apply {
        text = label; typeface = Fonts.semibold; textSize = 16f; gravity = Gravity.CENTER
        isFocusable = true; isClickable = true
        val bg = GradientDrawable().apply { cornerRadius = dp(c, 16).toFloat() }
        fun paint(f: Boolean) {
            bg.setColor(if (f) 0xF2FFFFFF.toInt() else if (primary) 0x40FFFFFF else 0x1FFFFFFF)
            setTextColor(if (f) 0xFF111111.toInt() else Color.WHITE)
        }
        background = bg; paint(false)
        setOnFocusChangeListener { _, f ->
            if (f) allowOverflow()
            paint(f); animate().scaleX(if (f) 1.05f else 1f).scaleY(if (f) 1.05f else 1f).setDuration(220).setInterpolator(appleOut).start()
        }
        setOnClickListener { onClick() }
    }

    fun human(bytes: Long): String = when {
        bytes >= 1L shl 30 -> String.format("%.1f GB", bytes / 1073741824.0)
        bytes >= 1L shl 20 -> String.format("%.1f MB", bytes / 1048576.0)
        bytes >= 1L shl 10 -> String.format("%.0f KB", bytes / 1024.0)
        else -> "$bytes B"
    }

    fun decode(f: File, maxW: Int): Bitmap? {
        // HEIC (iPhone) and anything else BitmapFactory can't read: ImageDecoder handles it on Android 9+
        if (f.extension.lowercase() in setOf("heic", "heif")) return try {
            android.graphics.ImageDecoder.decodeBitmap(android.graphics.ImageDecoder.createSource(f)) { d, info, _ ->
                val w = info.size.width; if (w > maxW) d.setTargetSize(maxW, info.size.height * maxW / w)
                d.allocator = android.graphics.ImageDecoder.ALLOCATOR_SOFTWARE
            }
        } catch (e: Exception) { null }
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(f.path, o)
        if (o.outWidth <= 0) return null
        var s = 1; while (o.outWidth / (s * 2) >= maxW) s *= 2
        return BitmapFactory.decodeFile(f.path, BitmapFactory.Options().apply { inSampleSize = s })
    }

    // ---- glass toast (replaces Android's grey toasts everywhere) ----
    private var lastToast: View? = null
    /** Now a tvOS banner (top-right), see Banner. */
    fun toast(a: Activity, msg: String, long: Boolean = false) { Banner.show(a, msg, ms = if (long) 4500 else 2800) }

    // ---- APKs ----
    class ApkInfo(val label: String, val version: String, val icon: android.graphics.drawable.Drawable?, val pkg: String)
    fun isApk(f: File) = f.isFile && f.extension.equals("apk", true)
    fun apkInfo(c: Context, f: File): ApkInfo? = try {
        c.packageManager.getPackageArchiveInfo(f.path, 0)?.let { pi ->
            val ai = pi.applicationInfo ?: return@let null
            ai.sourceDir = f.path; ai.publicSourceDir = f.path   // needed to load label/icon from an uninstalled APK
            ApkInfo(ai.loadLabel(c.packageManager).toString(), pi.versionName ?: "", ai.loadIcon(c.packageManager), pi.packageName)
        }
    } catch (e: Exception) { null }

    /** Hands the APK to Android's installer, which shows its own confirm screen. */
    fun install(a: Activity, f: File) {
        val i = Intent(Intent.ACTION_VIEW).setDataAndType(ShareHub.uriFor(f), "application/vnd.android.package-archive")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        // straight to Android's installer (otherwise any file manager offering "install" turns this into a chooser)
        val direct = Intent(i).setPackage("com.google.android.packageinstaller")
        try { a.startActivity(if (direct.resolveActivity(a.packageManager) != null) direct else i) } catch (e: Exception) { toast(a, "Couldn't start the installer") }
    }

    /** Open a file the TV way: photos in our viewer, apps in the installer, media in VLC. */
    fun open(a: Activity, f: File) {
        val mime = ShareHub.mimeOf(f)
        when {
            mime.startsWith("image/") -> a.startActivity(Intent(a, FilesActivity::class.java).putExtra("view", f.path))
            isApk(f) -> install(a, f)
            else -> {
                val i = Intent(Intent.ACTION_VIEW).setDataAndType(ShareHub.uriFor(f), mime)
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
                val vlc = Intent(i).setPackage("org.videolan.vlc")
                try {
                    if ((mime.startsWith("video/") || mime.startsWith("audio/")) && vlc.resolveActivity(a.packageManager) != null) a.startActivity(vlc)
                    else a.startActivity(Intent.createChooser(i, f.name).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                } catch (e: Exception) { toast(a, "No app can open ${f.name}") }
            }
        }
    }
}

/** AirDrop-style sheet over whatever is on screen: Accept/Decline (LocalSend) → progress → preview. */
class DropActivity : Activity() {
    private val main = Handler(Looper.getMainLooper())
    private lateinit var title: TextView
    private lateinit var sub: TextView
    private lateinit var preview: ImageView
    private lateinit var barFill: View
    private lateinit var barBox: FrameLayout
    private lateinit var buttons: LinearLayout
    private var state = ""
    private var inc: ShareHub.Incoming? = null

    private val poll = object : Runnable {
        override fun run() { render(); main.postDelayed(this, 200) }
    }

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        enableOwnAnimations(); Fonts.init(this)
        window.setBackgroundDrawable(ColorDrawable(0x8C000000.toInt()))
        val d = { v: Int -> Kit.dp(this, v) }
        val root = FrameLayout(this)
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL
            background = Kit.card(this@DropActivity); setPadding(d(30), d(26), d(30), d(24)); elevation = d(30).toFloat()
        }
        preview = ImageView(this).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP; visibility = View.GONE
            outlineProvider = object : android.view.ViewOutlineProvider() {
                override fun getOutline(v: View, o: Outline) { o.setRoundRect(0, 0, v.width, v.height, d(16).toFloat()) }
            }
            clipToOutline = true
        }
        card.addView(preview, LinearLayout.LayoutParams(d(400), d(225)).apply { bottomMargin = d(18) })
        card.addView(ImageView(this).apply { setImageDrawable(dropGlyph()) }, LinearLayout.LayoutParams(d(46), d(46)))
        title = TextView(this).apply { typeface = Fonts.display; textSize = 22f; setTextColor(Color.WHITE); gravity = Gravity.CENTER; setPadding(0, d(10), 0, 0) }
        sub = TextView(this).apply {
            typeface = Fonts.medium; textSize = 14f; setTextColor(0xB3FFFFFF.toInt()); gravity = Gravity.CENTER
            setPadding(0, d(6), 0, 0); maxLines = 3; ellipsize = TextUtils.TruncateAt.END
        }
        card.addView(title, LinearLayout.LayoutParams(d(420), -2)); card.addView(sub, LinearLayout.LayoutParams(d(420), -2))
        barBox = FrameLayout(this).apply { background = GradientDrawable().apply { cornerRadius = d(4).toFloat(); setColor(0x33FFFFFF) }; visibility = View.GONE }
        barFill = View(this).apply { background = GradientDrawable().apply { cornerRadius = d(4).toFloat(); setColor(Color.WHITE) }; pivotX = 0f; scaleX = 0f }
        barBox.addView(barFill, FrameLayout.LayoutParams(-1, -1))
        card.addView(barBox, LinearLayout.LayoutParams(d(400), d(8)).apply { topMargin = d(18) })
        buttons = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER; clipChildren = false; clipToPadding = false; setPadding(d(8), d(6), d(8), d(6))
        }
        card.clipChildren = false; card.clipToPadding = false
        card.addView(buttons, LinearLayout.LayoutParams(-2, -2).apply { topMargin = d(16) })
        root.addView(card, FrameLayout.LayoutParams(-2, -2, Gravity.CENTER))
        setContentView(root)
        card.alpha = 0f; card.scaleX = 0.9f; card.scaleY = 0.9f
        card.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(380).setInterpolator(appleOut).start()
    }

    override fun onResume() { super.onResume(); main.post(poll) }
    override fun onPause() { main.removeCallbacks(poll); super.onPause() }

    override fun onBackPressed() {
        inc?.let { if (it.decision == null) { it.decision = false; it.latch.countDown() } }
        finish()
    }

    private fun setButtons(vararg bs: TextView) {
        buttons.removeAllViews()
        bs.forEachIndexed { i, b -> buttons.addView(b, LinearLayout.LayoutParams(Kit.dp(this, if (bs.size > 2) 132 else 150), Kit.dp(this, 46)).apply { if (i > 0) leftMargin = Kit.dp(this@DropActivity, 12) }) }
        (bs.firstOrNull { it.tag == "focus" } ?: bs.last()).requestFocus()
    }

    private fun summary(i: ShareHub.Incoming): String {
        val n = i.files.size
        val first = i.files.first().name
        return if (n == 1) "$first  ·  ${Kit.human(i.total)}" else "$first and ${n - 1} more  ·  ${Kit.human(i.total)}"
    }

    private fun render() {
        val i = ShareHub.current ?: run { if (state == "") finish(); return }
        if (inc !== i) { inc = i; state = "" }
        val s = when { i.cancelled -> "cancelled"; i.decision == null -> "ask"; i.decision == false -> "declined"; i.done -> "done"; else -> "progress" }
        if (s == "progress") {
            barFill.scaleX = if (i.total > 0) (i.received.get().toFloat() / i.total).coerceIn(0f, 1f) else 0f
            sub.text = "${Kit.human(i.received.get())} of ${Kit.human(i.total)}"
        }
        if (s == state) return
        state = s
        barBox.visibility = if (s == "progress") View.VISIBLE else View.GONE
        when (s) {
            "ask" -> {
                val n = i.files.size
                title.text = "${i.sender} wants to share ${if (n == 1) "a file" else "$n items"}"
                sub.text = summary(i)
                setButtons(Kit.button(this, "Decline") { i.decision = false; i.latch.countDown(); finish() },
                    Kit.button(this, "Accept", primary = true) { i.decision = true; i.latch.countDown() }.apply { tag = "focus" })
            }
            "progress" -> {
                title.text = "Receiving from ${i.sender}"
                setButtons(Kit.button(this, "Hide") { finish() })
            }
            "done" -> {
                val files = i.saved.toList()
                val apk = files.singleOrNull()?.takeIf { Kit.isApk(it) }?.let { Kit.apkInfo(this, it) }
                title.text = when { apk != null -> "${apk.label} ${apk.version}".trim(); files.size == 1 -> files[0].name; else -> "Received ${files.size} items" }
                if (apk?.icon != null) { preview.setImageDrawable(apk.icon); preview.scaleType = ImageView.ScaleType.FIT_CENTER
                    preview.layoutParams = preview.layoutParams.apply { width = Kit.dp(this@DropActivity, 96); height = Kit.dp(this@DropActivity, 96) }
                    preview.visibility = View.VISIBLE }
                sub.text = "Saved to Downloads › Arsh TV"
                if (apk == null) files.firstOrNull { ShareHub.mimeOf(it).startsWith("image/") }?.let { img ->
                    Thread {
                        val bmp = Kit.decode(img, 800)
                        main.post { if (bmp != null) { preview.setImageBitmap(bmp); preview.visibility = View.VISIBLE } }
                    }.start()
                }
                val first = files.firstOrNull()
                setButtons(
                    Kit.button(this, "Done") { finish() },
                    Kit.button(this, "Show in Files") {
                        startActivity(Intent(this, FilesActivity::class.java).putExtra("dir", ShareHub.dir.path)); finish()
                    },
                    Kit.button(this, if (apk != null) "Install" else "Open", primary = true) { first?.let { Kit.open(this, it) }; finish() }.apply { tag = "focus" })
            }
            "cancelled" -> { title.text = "Transfer cancelled"; sub.text = i.sender; setButtons(Kit.button(this, "OK") { finish() }) }
            "declined" -> finish()
        }
    }

    /** AirDrop-like glyph: a dot inside two open rings. */
    private fun dropGlyph(): android.graphics.drawable.Drawable {
        val s = 128; val bm = Bitmap.createBitmap(s, s, Bitmap.Config.ARGB_8888); val c = Canvas(bm)
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; style = Paint.Style.STROKE; strokeWidth = 9f; strokeCap = Paint.Cap.ROUND }
        c.drawArc(RectF(14f, 14f, 114f, 114f), 120f, 300f, false, p)
        c.drawArc(RectF(36f, 36f, 92f, 92f), 120f, 300f, false, p)
        p.style = Paint.Style.FILL; c.drawCircle(64f, 64f, 11f, p)
        return android.graphics.drawable.BitmapDrawable(resources, bm)
    }
}
