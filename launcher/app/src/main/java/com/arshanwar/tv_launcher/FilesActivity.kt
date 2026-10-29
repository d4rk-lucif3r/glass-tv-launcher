package com.arshanwar.tv_launcher

import android.app.Activity
import android.graphics.*
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.os.storage.StorageManager
import android.text.TextUtils
import android.text.format.DateFormat
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.*
import java.io.File
import java.util.Date
import java.util.concurrent.Executors

/** Files: received drops, Downloads, Movies, Pictures and USB drives. Photos open in a full-screen viewer. */
class FilesActivity : Activity() {
    private class Place(val label: String, val dir: File)

    private val main = Handler(Looper.getMainLooper())
    private val io = Executors.newSingleThreadExecutor()
    private fun dp(v: Int) = Kit.dp(this, v)

    private lateinit var placesCol: LinearLayout
    private lateinit var list: LinearLayout
    private lateinit var scroll: ScrollView
    private lateinit var pathView: TextView
    private lateinit var empty: TextView
    private lateinit var viewer: FrameLayout
    private lateinit var viewerImg: ImageView
    private lateinit var viewerName: TextView
    private var places: List<Place> = emptyList()
    private var root: File? = null
    private var dir: File? = null
    private var images: List<File> = emptyList()
    private var imgIndex = 0
    private var dialog: View? = null
    private var firstShow = true
    private lateinit var content: ViewGroup

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        enableOwnAnimations(); Fonts.init(this)
        val bg = FrameLayout(this).apply {
            background = GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(0xFF1E2140.toInt(), 0xFF0D0E16.toInt(), 0xFF07070B.toInt()))
        }
        val page = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(56), dp(34), dp(56), 0) }
        val head = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.BOTTOM }
        head.addView(TextView(this).apply { text = "Files"; typeface = Fonts.displayBold; textSize = 34f; setTextColor(Color.WHITE) })
        pathView = TextView(this).apply {
            typeface = Fonts.medium; textSize = 14f; setTextColor(0x99FFFFFF.toInt()); setPadding(dp(18), 0, 0, dp(7))
            maxLines = 1; ellipsize = TextUtils.TruncateAt.START
        }
        head.addView(pathView, LinearLayout.LayoutParams(0, -2, 1f))
        page.addView(head)

        val body = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, dp(22), 0, 0) }
        placesCol = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        body.addView(placesCol, LinearLayout.LayoutParams(dp(230), -1))
        val right = FrameLayout(this).apply { background = Kit.card(this@FilesActivity, 24) }
        scroll = ScrollView(this).apply { isVerticalScrollBarEnabled = false; clipToPadding = false; setPadding(dp(10), dp(10), dp(10), dp(10)) }
        list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        scroll.addView(list)
        right.addView(scroll, FrameLayout.LayoutParams(-1, -1))
        empty = TextView(this).apply { typeface = Fonts.medium; textSize = 16f; setTextColor(0x80FFFFFF.toInt()); gravity = Gravity.CENTER; visibility = View.GONE }
        right.addView(empty, FrameLayout.LayoutParams(-1, -1))
        body.addView(right, LinearLayout.LayoutParams(0, -1, 1f).apply { leftMargin = dp(18); bottomMargin = dp(14) })
        page.addView(body, LinearLayout.LayoutParams(-1, 0, 1f))
        page.addView(TextView(this).apply {
            text = "OK  open      \u00b7      Hold OK  more options      \u00b7      Back  go up"
            typeface = Fonts.medium; textSize = 13f; setTextColor(0x80FFFFFF.toInt()); gravity = Gravity.CENTER; setPadding(0, 0, 0, dp(18))
        }, LinearLayout.LayoutParams(-1, -2))
        bg.addView(page, FrameLayout.LayoutParams(-1, -1))

        viewer = FrameLayout(this).apply { setBackgroundColor(Color.BLACK); visibility = View.GONE; isFocusable = true }
        viewerImg = ImageView(this).apply { scaleType = ImageView.ScaleType.FIT_CENTER }
        // inset so the photo never looks like the live screen; the pill below says how to get out
        viewer.addView(viewerImg, FrameLayout.LayoutParams(-1, -1).apply { setMargins(dp(60), dp(40), dp(60), dp(96)) })
        viewerName = TextView(this).apply {
            typeface = Fonts.medium; textSize = 15f; setTextColor(0xE6FFFFFF.toInt()); gravity = Gravity.CENTER
            background = GradientDrawable().apply { cornerRadius = dp(20).toFloat(); setColor(0x33FFFFFF) }
            setPadding(dp(22), dp(9), dp(22), dp(9)); maxLines = 1; ellipsize = TextUtils.TruncateAt.MIDDLE
        }
        viewer.addView(viewerName, FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply { bottomMargin = dp(30) })
        bg.addView(viewer, FrameLayout.LayoutParams(-1, -1))
        setContentView(bg)
        content = bg

        places = findPlaces()
        buildPlaces()
        val view = intent.getStringExtra("view")?.let { File(it) }
        val start = intent.getStringExtra("dir")?.let { File(it) } ?: view?.parentFile ?: places.first().dir
        root = places.firstOrNull { start.path.startsWith(it.dir.path) }?.dir ?: start
        show(start)
        if (view != null) openViewer(view)
    }

    override fun onDestroy() { main.removeCallbacksAndMessages(null); io.shutdownNow(); super.onDestroy() }

    private fun findPlaces(): List<Place> {
        val out = arrayListOf(
            Place("Received", ShareHub.dir),
            Place("Downloads", Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)),
            Place("Movies", Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES)),
            Place("Pictures", Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES)),
        )
        val sm = getSystemService(StorageManager::class.java)
        val usb = sm?.storageVolumes?.filter { it.isRemovable }?.mapNotNull { v ->
            try { Place(v.getDescription(this), v.javaClass.getMethod("getPathFile").invoke(v) as File) } catch (e: Exception) { null }
        }.orEmpty().ifEmpty {
            File("/storage").listFiles()?.filter { it.name != "emulated" && it.name != "self" && it.canRead() }?.map { Place("USB drive", it) }.orEmpty()
        }
        return out + usb
    }

    private fun row(icon: Drawable, label: String, detail: String?, h: Int, onClick: () -> Unit, onLong: (() -> Unit)? = null): View {
        val r = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; isFocusable = true; isClickable = true
            setPadding(dp(14), 0, dp(16), 0)
        }
        val bg = GradientDrawable().apply { cornerRadius = dp(14).toFloat(); setColor(Color.TRANSPARENT) }
        r.background = bg
        val iv = ImageView(this).apply { setImageDrawable(icon.constantState?.newDrawable()?.mutate() ?: icon) }   // own copy: tinting must not leak to other rows
        val name = TextView(this).apply { text = label; typeface = Fonts.medium; textSize = 16f; setTextColor(Color.WHITE); maxLines = 1; ellipsize = TextUtils.TruncateAt.MIDDLE }
        val det = TextView(this).apply { text = detail; typeface = Fonts.regular; textSize = 13f; setTextColor(0x8CFFFFFF.toInt()); gravity = Gravity.END }
        r.addView(iv, LinearLayout.LayoutParams(dp(24), dp(24)))
        r.addView(name, LinearLayout.LayoutParams(0, -2, 1f).apply { leftMargin = dp(14) })
        if (detail != null) r.addView(det)
        r.setOnFocusChangeListener { _, f ->
            bg.setColor(if (f) 0xF2FFFFFF.toInt() else Color.TRANSPARENT)
            name.setTextColor(if (f) 0xFF111111.toInt() else Color.WHITE)
            det.setTextColor(if (f) 0x99111111.toInt() else 0x8CFFFFFF.toInt())
            if (f) iv.setColorFilter(0xFF111111.toInt(), PorterDuff.Mode.SRC_IN) else iv.clearColorFilter()
        }
        r.setOnClickListener { onClick() }
        if (onLong != null) r.setOnLongClickListener { onLong(); true }
        r.layoutParams = LinearLayout.LayoutParams(-1, dp(h))
        return r
    }

    private fun buildPlaces() {
        placesCol.removeAllViews()
        for (p in places) placesCol.addView(row(glyph(if (p.label.startsWith("USB") || p.dir.path.startsWith("/storage/") && !p.dir.path.startsWith("/storage/emulated")) "usb" else "folder"),
            p.label, null, 50, { root = p.dir; show(p.dir); list.getChildAt(0)?.requestFocus() }))
    }

    private fun show(d: File) {
        dir = d
        pathView.text = d.path.replace("/storage/emulated/0", "TV storage")
        list.removeAllViews()
        empty.visibility = View.GONE
        io.go {
            val items = (d.listFiles()?.filter { !it.isHidden } ?: emptyList())
                .sortedWith(compareBy({ !it.isDirectory }, { -it.lastModified() }))
            val rows = items.map { it to (if (Kit.isApk(it)) Kit.apkInfo(this, it) else null) }   // APK labels/icons are slow: off the UI thread
            main.post {
                if (dir != d) return@post
                images = items.filter { ShareHub.mimeOf(it).startsWith("image/") }
                if (items.isEmpty()) {
                    empty.text = if (d == ShareHub.dir) "Nothing here yet.\nUse the share button on the home screen to send files from your phone." else "This folder is empty"
                    empty.visibility = View.VISIBLE
                }
                for ((f, apk) in rows) {
                    val date = DateFormat.format("d MMM", Date(f.lastModified()))
                    val detail = when {
                        f.isDirectory -> "${f.list()?.size ?: 0} items"
                        apk != null -> "App  \u00b7  ${apk.version.ifBlank { "" }}  \u00b7  ${Kit.human(f.length())}".replace("  \u00b7    \u00b7  ", "  \u00b7  ")
                        else -> "${Kit.human(f.length())}   $date"
                    }
                    list.addView(row(apk?.icon ?: glyph(kind(f)), apk?.label ?: f.name, detail, 54,
                        { if (f.isDirectory) { show(f); list.getChildAt(0)?.requestFocus() } else if (kind(f) == "image") openViewer(f) else Kit.open(this, f) },
                        { options(f, apk) }))
                }
                // first open lands on the first file; later folder switches from the places column keep focus there
                if (firstShow || currentFocus == null || currentFocus?.parent !== placesCol) list.getChildAt(0)?.requestFocus()
                firstShow = false
                if (list.childCount == 0) placesCol.getChildAt(places.indexOfFirst { it.dir == root }.coerceAtLeast(0))?.requestFocus()
            }
        }
    }

    private fun kind(f: File): String {
        if (f.isDirectory) return "folder"
        val m = ShareHub.mimeOf(f)
        return when { m.startsWith("image/") -> "image"; m.startsWith("video/") -> "video"; m.startsWith("audio/") -> "audio"; else -> "file" }
    }

    // ---- photo viewer ----
    private fun openViewer(f: File) {
        imgIndex = images.indexOfFirst { it.path == f.path }.coerceAtLeast(0)
        if (images.isEmpty()) images = listOf(f)
        viewer.visibility = View.VISIBLE; viewer.alpha = 0f
        viewer.animate().alpha(1f).setDuration(250).start()
        viewer.requestFocus()
        loadImage()
    }

    private fun loadImage() {
        val f = images.getOrNull(imgIndex) ?: return
        viewerName.text = (if (images.size > 1) "${f.name}   ·   ${imgIndex + 1} of ${images.size}   ·   ◀ ▶" else f.name) + "   \u00b7   Back to close"
        io.go {
            val bmp = Kit.decode(f, 1920)
            main.post { if (images.getOrNull(imgIndex) == f) { viewerImg.setImageBitmap(bmp); viewerImg.alpha = 0f; viewerImg.animate().alpha(1f).setDuration(200).start() } }
        }
    }

    private fun closeViewer() {
        viewer.animate().alpha(0f).setDuration(200).withEndAction { viewer.visibility = View.GONE; viewerImg.setImageDrawable(null) }.start()
        (0 until list.childCount).map { list.getChildAt(it) }.getOrNull(0)?.requestFocus()
    }

    override fun dispatchKeyEvent(e: KeyEvent): Boolean {
        if (e.keyCode == KeyEvent.KEYCODE_MENU && dialog == null && viewer.visibility != View.VISIBLE) {
            if (e.action == KeyEvent.ACTION_UP) currentFocus?.takeIf { it.parent === list }?.performLongClick()
            return true
        }
        if (viewer.visibility == View.VISIBLE && e.action == KeyEvent.ACTION_DOWN) {
            when (e.keyCode) {
                KeyEvent.KEYCODE_DPAD_RIGHT -> { if (imgIndex < images.size - 1) { imgIndex++; loadImage() }; return true }
                KeyEvent.KEYCODE_DPAD_LEFT -> { if (imgIndex > 0) { imgIndex--; loadImage() }; return true }
            }
        }
        return super.dispatchKeyEvent(e)
    }

    override fun onBackPressed() {
        when {
            dialog != null -> closeDialog()
            viewer.visibility == View.VISIBLE -> { if (intent.hasExtra("view") && list.childCount == 0) finish() else closeViewer() }
            dir != null && root != null && dir != root && dir!!.path.startsWith(root!!.path) -> { val up = dir!!.parentFile!!; show(up) }
            else -> finish()
        }
    }

    // ---- hold-OK options ----
    private fun options(f: File, apk: Kit.ApkInfo?) {
        val box = sheet(apk?.label ?: f.name)
        val btns = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(0, dp(16), 0, 0) }
        fun add(label: String, primary: Boolean = false, act: () -> Unit) =
            Kit.button(this, label, primary) { closeDialog(); act() }.also { btns.addView(it, LinearLayout.LayoutParams(dp(300), dp(46)).apply { topMargin = dp(8) }) }
        val first = when {
            f.isDirectory -> add("Open folder", true) { show(f) }
            apk != null -> add("Install", true) { Kit.install(this, f) }
            kind(f) == "image" -> add("View", true) { openViewer(f) }
            else -> add("Open", true) { Kit.open(this, f) }
        }
        add("Delete") { confirmDelete(f) }
        add("Cancel") {}
        box.addView(btns)
        (0 until btns.childCount).map { btns.getChildAt(it) }.also { bs ->
            bs.forEach { it.id = View.generateViewId(); it.nextFocusLeftId = it.id; it.nextFocusRightId = it.id }
            bs.first().nextFocusUpId = bs.first().id; bs.last().nextFocusDownId = bs.last().id
        }
        first.requestFocus()
    }

    private fun sheet(title: String): LinearLayout {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL; background = Kit.card(this@FilesActivity)
            setPadding(dp(28), dp(24), dp(28), dp(22)); elevation = dp(30).toFloat(); clipChildren = false; clipToPadding = false
        }
        box.addView(TextView(this).apply { text = title; typeface = Fonts.display; textSize = 20f; setTextColor(Color.WHITE); gravity = Gravity.CENTER; maxLines = 2; ellipsize = TextUtils.TruncateAt.MIDDLE },
            LinearLayout.LayoutParams(dp(380), -2))
        val scrim = FrameLayout(this).apply { setBackgroundColor(0x99000000.toInt()); isClickable = true }
        scrim.addView(box, FrameLayout.LayoutParams(-2, -2, Gravity.CENTER))
        openDialog(scrim)
        return box
    }

    // ---- delete ----
    private fun confirmDelete(f: File) {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL; background = Kit.card(this@FilesActivity)
            setPadding(dp(28), dp(24), dp(28), dp(22)); elevation = dp(30).toFloat()
        }
        box.addView(TextView(this).apply { text = "Delete “${f.name}”?"; typeface = Fonts.display; textSize = 20f; setTextColor(Color.WHITE); gravity = Gravity.CENTER; maxLines = 2 },
            LinearLayout.LayoutParams(dp(380), -2))
        val btns = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, dp(18), 0, 0) }
        val cancel = Kit.button(this, "Cancel") { closeDialog() }
        btns.addView(cancel, LinearLayout.LayoutParams(dp(150), dp(46)))
        btns.addView(Kit.button(this, "Delete") {
            val ok = if (f.isDirectory) f.deleteRecursively() else f.delete()
            closeDialog(); Kit.toast(this, if (ok) "Deleted ${f.name}" else "Couldn't delete ${f.name}")
            dir?.let { show(it) }
        }, LinearLayout.LayoutParams(dp(150), dp(46)).apply { leftMargin = dp(12) })
        box.addView(btns)
        val scrim = FrameLayout(this).apply { setBackgroundColor(0x99000000.toInt()); isClickable = true }
        scrim.addView(box, FrameLayout.LayoutParams(-2, -2, Gravity.CENTER))
        openDialog(scrim)
        cancel.requestFocus()
    }

    /** While a sheet is up, nothing behind it can take focus (pressing down used to jump into the file list). */
    private fun openDialog(scrim: View) {
        dialog?.let { (window.decorView as ViewGroup).removeView(it) }
        lastFocus = currentFocus?.takeIf { it.parent === list } ?: lastFocus
        content.descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
        (window.decorView as ViewGroup).addView(scrim, ViewGroup.LayoutParams(-1, -1))
        dialog = scrim
    }

    private var lastFocus: View? = null
    private fun closeDialog() {
        dialog?.let { (window.decorView as ViewGroup).removeView(it) }; dialog = null
        content.descendantFocusability = ViewGroup.FOCUS_AFTER_DESCENDANTS
        (lastFocus?.takeIf { it.isAttachedToWindow } ?: list.getChildAt(0))?.requestFocus()
    }

    // ---- glyphs ----
    private val glyphCache = HashMap<String, Drawable>()
    private fun glyph(kind: String): Drawable = glyphCache.getOrPut(kind) {
        val s = 96; val bm = Bitmap.createBitmap(s, s, Bitmap.Config.ARGB_8888); val c = Canvas(bm)
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        when (kind) {
            "folder" -> { p.color = 0xFF64D2FF.toInt(); c.drawRoundRect(8f, 22f, 88f, 80f, 10f, 10f, p); c.drawRoundRect(8f, 14f, 44f, 34f, 8f, 8f, p) }
            "image" -> { p.color = 0xFF30D158.toInt(); c.drawRoundRect(10f, 16f, 86f, 80f, 10f, 10f, p)
                p.color = Color.WHITE; c.drawCircle(32f, 36f, 7f, p)
                c.drawPath(Path().apply { moveTo(18f, 72f); lineTo(42f, 48f); lineTo(56f, 62f); lineTo(66f, 52f); lineTo(80f, 72f); close() }, p) }
            "video" -> { p.color = 0xFFFF9F0A.toInt(); c.drawRoundRect(8f, 18f, 88f, 78f, 12f, 12f, p)
                p.color = Color.WHITE; c.drawPath(Path().apply { moveTo(40f, 34f); lineTo(64f, 48f); lineTo(40f, 62f); close() }, p) }
            "audio" -> { p.color = 0xFFFF375F.toInt(); c.drawCircle(34f, 70f, 14f, p); c.drawRect(44f, 18f, 52f, 70f, p); c.drawRect(44f, 18f, 74f, 28f, p) }
            "usb" -> { p.color = 0xFFBF5AF2.toInt(); c.drawRoundRect(26f, 30f, 70f, 88f, 10f, 10f, p); c.drawRect(34f, 8f, 62f, 32f, p) }
            else -> { p.color = 0xFF98989D.toInt(); c.drawRoundRect(20f, 8f, 76f, 88f, 10f, 10f, p) }
        }
        BitmapDrawable(resources, bm)
    }
}
