package com.arshanwar.tv_launcher

import android.content.Context
import android.graphics.*
import android.os.SystemClock
import android.view.View
import java.io.File
import java.text.SimpleDateFormat
import java.util.*
import kotlin.concurrent.thread

/**
 * iOS-style lock screen: full-bleed artwork (cached Top Shelf backgrounds) drifting slowly and
 * crossfading, with the date above a large bold clock. Falls back to a soft aurora when offline.
 * Draws at ~15fps (the drift is slow enough that more is wasted work); text nudges a few px each minute to avoid burn-in.
 */
class LockView(context: Context, private val showHint: Boolean, private val overlay: Boolean = false) : View(context) {
    /** Aerial mode: the place being shown ("Greenland"), drawn bottom-left like tvOS. */
    var caption: String? = null
        set(v) { field = v; captionAt = SystemClock.uptimeMillis(); removeCallbacks(tick); if (running) post(tick) }
    private var captionAt = 0L
    /** tvOS: the place name fades in as a clip starts, stays a few seconds, then fades out. */
    private fun captionAlpha(): Float {
        val t = SystemClock.uptimeMillis() - captionAt
        return when { t < 600 -> t / 600f; t < 6500 -> 1f; t < 7500 -> 1f - (t - 6500) / 1000f; else -> 0f }
    }
    private val capPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xB3FFFFFF.toInt(); setShadowLayer(12f, 0f, 1f, 0x80000000.toInt()) }
    private class Pic(val bmp: Bitmap, val start: Long, val left: Boolean, val backdrop: Bitmap? = null)   // backdrop set = personal photo

    private val d = resources.displayMetrics.density
    private val prefs = context.getSharedPreferences("launcher", Context.MODE_PRIVATE)
    private val photos: List<File> by lazy { findPhotos() }
    /** Screensaver content per Control Center › "Screensaver shows": movie artwork, your photos, or both interleaved. */
    private val files: List<File> by lazy {
        val art = Images.backgrounds(context).shuffled()
        val mine = photos.shuffled()
        when (prefs.getString("saver_mode", "both")) {
            "art" -> art
            "photos" -> mine.ifEmpty { art }
            else -> if (mine.isEmpty()) art else (0 until maxOf(art.size, mine.size)).flatMap { listOfNotNull(mine.getOrNull(it), art.getOrNull(it)) }
        }
    }
    private fun findPhotos(): List<File> {
        val dirs = listOf(ShareHub.dir, android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_PICTURES),
            android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_DCIM))
        val exts = setOf("jpg", "jpeg", "png", "webp", "heic", "heif")
        return dirs.flatMap { d -> d.walkTopDown().maxDepth(3).filter { it.isFile && it.extension.lowercase() in exts && it.length() > 20_000 }.toList() }
    }
    private val photoSet by lazy { photos.toSet() }
    private val wxPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xD9FFFFFF.toInt(); textAlign = Paint.Align.CENTER; setShadowLayer(14f, 0f, 2f, 0x59000000) }
    private var fileIdx = 0
    private var cur: Pic? = null
    private var prev: Pic? = null
    private var loading = false
    private val slideMs = 18000L
    private val fadeMs = 2200L
    private val zoomMs = 26000L

    private val bmpPaint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.DITHER_FLAG)
    private val m = Matrix()
    private val topShade = Paint(Paint.DITHER_FLAG)
    private val bottomShade = Paint(Paint.DITHER_FLAG)
    private val glowColors = intArrayOf(0xFF5E5CE6.toInt(), 0xFFFF375F.toInt(), 0xFF32ADE6.toInt())
    private val glowPaints = glowColors.map { Paint(Paint.ANTI_ALIAS_FLAG) }

    private val datePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xE6FFFFFF.toInt(); textAlign = Paint.Align.CENTER; setShadowLayer(18f, 0f, 2f, 0x59000000)
    }
    private val timePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xF2FFFFFF.toInt(); textAlign = Paint.Align.CENTER; letterSpacing = -0.035f; setShadowLayer(30f, 0f, 4f, 0x4D000000)
    }
    private val hintPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xB3FFFFFF.toInt(); textAlign = Paint.Align.CENTER; setShadowLayer(10f, 0f, 1f, 0x66000000)
    }
    private val barPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xD9FFFFFF.toInt() }
    private val timeFmt = SimpleDateFormat("h:mm", Locale.getDefault())
    private val dateFmt = SimpleDateFormat("EEEE d MMMM", Locale.getDefault())
    private val start = SystemClock.uptimeMillis()
    private var running = false

    private val tick = object : Runnable {
        override fun run() {
            if (!running) return
            val c = cur
            if (!overlay && !loading && files.isNotEmpty() && (c == null || SystemClock.uptimeMillis() - c.start > slideMs)) loadNext()
            invalidate()
            // over a video only the clock (and a fading caption) changes: redraw once a minute instead of 15x a second
            val captionLive = caption != null && SystemClock.uptimeMillis() - captionAt < 7600
            postDelayed(this, if (!overlay || captionLive) 66 else 60_000 - System.currentTimeMillis() % 60_000 + 20)
        }
    }

    init {
        Fonts.init(context)
        datePaint.typeface = Fonts.semibold
        timePaint.typeface = Fonts.displayBold
        hintPaint.typeface = Fonts.medium
    }

    private fun loadNext() {
        loading = true
        val f = files[fileIdx++ % files.size]
        val left = fileIdx % 2 == 0
        thread {
            val isPhoto = f in photoSet
            val b = if (isPhoto) Kit.decode(f, 1920) else Images.decode(f, 1920)
            // personal photos keep their whole frame: shown fitted over a blurred, screen-filling copy of themselves
            val back = if (isPhoto && b != null) Blur.box(b, 96, 6, 6) else null
            post {
                loading = false
                if (b != null && running) { prev = cur; cur = Pic(b, SystemClock.uptimeMillis(), left, back) }
            }
        }
    }

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
        val fh = h.toFloat()
        topShade.shader = LinearGradient(0f, 0f, 0f, fh * 0.55f, intArrayOf(0x8C000000.toInt(), 0x26000000, 0x00000000), floatArrayOf(0f, 0.6f, 1f), Shader.TileMode.CLAMP)
        bottomShade.shader = LinearGradient(0f, fh * 0.7f, 0f, fh, intArrayOf(0x00000000, 0x80000000.toInt()), null, Shader.TileMode.CLAMP)
        val r = maxOf(w, h) * 0.55f
        glowPaints.forEachIndexed { i, p ->
            val c = glowColors[i]
            p.shader = RadialGradient(0f, 0f, r, (c and 0x00FFFFFF) or 0x99000000.toInt(), c and 0x00FFFFFF, Shader.TileMode.CLAMP)
        }
        datePaint.textSize = fh * 0.036f
        timePaint.textSize = fh * 0.25f
        hintPaint.textSize = fh * 0.022f
        wxPaint.textSize = fh * 0.03f
    }

    override fun onAttachedToWindow() { super.onAttachedToWindow(); running = true; post(tick) }
    override fun onDetachedFromWindow() { running = false; removeCallbacks(tick); super.onDetachedFromWindow() }

    private fun drawPic(c: Canvas, p: Pic, alpha: Int) {
        val w = width.toFloat(); val h = height.toFloat(); val b = p.bmp
        p.backdrop?.let { bd ->
            val cs = maxOf(w / bd.width, h / bd.height)
            m.setScale(cs, cs); m.postTranslate((w - bd.width * cs) / 2f, (h - bd.height * cs) / 2f)
            bmpPaint.alpha = alpha; c.drawBitmap(bd, m, bmpPaint)
            val fs = minOf(w / b.width, h * 0.9f / b.height)
            val k = ((SystemClock.uptimeMillis() - p.start) / zoomMs.toFloat()).coerceIn(0f, 1f)
            val z = fs * (1f + 0.04f * k)
            m.setScale(z, z); m.postTranslate((w - b.width * z) / 2f, (h - b.height * z) / 2f)
            c.drawBitmap(b, m, bmpPaint)
            return
        }
        val sc = maxOf(w / b.width, h / b.height)
        m.setScale(sc, sc); m.postTranslate((w - b.width * sc) / 2f, (h - b.height * sc) / 2f)
        val k = ((SystemClock.uptimeMillis() - p.start) / zoomMs.toFloat()).coerceIn(0f, 1f)
        val z = 1.03f + 0.09f * k
        m.postScale(z, z, if (p.left) w * 0.3f else w * 0.7f, h * 0.45f)
        bmpPaint.alpha = alpha
        c.drawBitmap(b, m, bmpPaint)
    }

    override fun onDraw(c: Canvas) {
        val w = width.toFloat(); val h = height.toFloat()
        if (!overlay) c.drawColor(0xFF05060D.toInt())
        val cp = cur
        if (overlay) { /* video underneath */ } else if (cp == null) {
            val t = (SystemClock.uptimeMillis() - start) / 1000.0
            glowPaints.forEachIndexed { i, p ->
                val ph = i * 2.1
                val x = w * (0.5 + 0.38 * Math.sin(t / (23.0 + i * 7) + ph)).toFloat()
                val y = h * (0.5 + 0.34 * Math.cos(t / (29.0 + i * 5) + ph * 1.3)).toFloat()
                c.save(); c.translate(x, y); c.drawPaint(p); c.restore()
            }
        } else {
            val fade = ((SystemClock.uptimeMillis() - cp.start) / fadeMs.toFloat()).coerceIn(0f, 1f)
            prev?.let { if (fade < 1f) drawPic(c, it, 255) }
            drawPic(c, cp, (255 * appleEase.getInterpolation(fade)).toInt())
            if (fade >= 1f) prev = null
        }
        c.drawRect(0f, 0f, w, h, topShade)
        c.drawRect(0f, 0f, w, h, bottomShade)

        val now = Date()
        val mnt = (now.time / 60000L).toInt()
        val dx = ((mnt * 37) % 41 - 20).toFloat() * d / 2f
        val dy = ((mnt * 53) % 31 - 15).toFloat() * d / 2f
        c.drawText(dateFmt.format(now), w / 2 + dx, h * 0.165f + dy, datePaint)
        c.drawText(timeFmt.format(now), w / 2 + dx, h * 0.395f + dy, timePaint)
        prefs.getString("wx", null)?.let { c.drawText(it, w / 2 + dx, h * 0.47f + dy, wxPaint) }   // weather saved by the launcher

        caption?.let {
            val a = captionAlpha()
            if (a > 0f) { capPaint.typeface = Fonts.semibold; capPaint.textSize = h * 0.03f; capPaint.alpha = (230 * a).toInt(); c.drawText(it, 64 * d, h - 56 * d, capPaint) }
        }
        if (showHint) {
            c.drawText(if (overlay) "\u25C0 \u25B6  another place   \u00b7   any other button to unlock" else "Press any button to unlock", w / 2, h - 46 * d, hintPaint)
            val bw = w * 0.075f
            c.drawRoundRect(w / 2 - bw / 2, h - 18 * d, w / 2 + bw / 2, h - 15 * d, 2 * d, 2 * d, barPaint)
        }
    }
}

/**
 * Screensaver/lock content. "aerials" (default) streams Apple's public tvOS aerial videos (1080p HEVC, hardware-decoded)
 * behind the clock, matched to the time of day: city/land flights by day; night cities, aurora, space and underwater
 * after dark. ◀ ▶ skips to another place. Any failure falls back to the artwork slideshow.
 */
object Saver {
    fun build(ctx: Context, showHint: Boolean, forceMode: String? = null): View {
        val mode = forceMode ?: ctx.getSharedPreferences("launcher", Context.MODE_PRIVATE).getString("saver_mode", "aerials")
        if (mode != "aerials") return LockView(ctx, showHint)
        val all = try {
            val a = org.json.JSONArray(ctx.assets.open("aerials.json").bufferedReader().readText())
            (0 until a.length()).map { a.getJSONObject(it).let { o -> o.getString("label") to o.getString("url") } }
        } catch (e: Exception) { emptyList() }
        if (all.isEmpty()) return LockView(ctx, showHint)
        return AerialView(ctx, showHint, forTimeOfDay(all))
    }

    private val sea = setOf("Seals", "Palau Jellies", "Palau Coral", "Kelp", "Barracuda", "Bumpheads", "Jacks", "Sea Stars", "Humpback Whale",
        "California Dolphins", "Red Sea Coral", "Cownose Rays", "California Kelp Forest", "Alaskan Jellies", "Gray Reef Sharks", "Costa Rica Dolphins")
    /** Earth seen from the ISS (labels like "Italy to Asia", "Africa and the Middle East"). */
    private fun space(l: String) = " to " in l || l.startsWith("Africa") || l in setOf("Antarctica", "Nile Delta", "Iran and Afghanistan", "Korea and Japan Night")

    private fun forTimeOfDay(all: List<Pair<String, String>>): List<Pair<String, String>> {
        val h = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
        val night = h >= 19 || h < 6
        val pool = all.filter { (l, _) ->
            val dark = "Night" in l || "Aurora" in l
            if (night) dark || space(l) || l in sea else !dark
        }
        return pool.ifEmpty { all }.shuffled()
    }
}

/** Video + clock overlay + a black curtain for clean cuts between clips (SurfaceView ignores view alpha). */
class AerialView(ctx: Context, showHint: Boolean, private val list: List<Pair<String, String>>) : android.widget.FrameLayout(ctx) {
    private val video = android.widget.VideoView(ctx)
    private val curtain = View(ctx).apply { setBackgroundColor(Color.BLACK) }
    private val clock = LockView(ctx, showHint, overlay = true)
    private var i = 0
    private var fails = 0
    private var switching = false

    init {
        setBackgroundColor(Color.BLACK)
        addView(video, LayoutParams(-1, -1, android.view.Gravity.CENTER))
        addView(curtain, LayoutParams(-1, -1))
        addView(clock, LayoutParams(-1, -1))
        video.setOnPreparedListener { mp ->
            mp.setVolume(0f, 0f); fails = 0; switching = false; video.start()
            curtain.animate().alpha(0f).setStartDelay(250).setDuration(1400).start()
            clock.caption = list[(i - 1 + list.size) % list.size].first
        }
        video.setOnCompletionListener { skip(1) }
        video.setOnErrorListener { _, _, _ ->
            if (++fails >= 3) {   // offline or decoder trouble: artwork slideshow instead
                removeAllViews(); addView(LockView(context, showHint), LayoutParams(-1, -1))
            } else play()
            true
        }
        play()
    }

    private fun play() {
        val (_, url) = list[(i % list.size + list.size) % list.size]; i++
        video.setVideoURI(android.net.Uri.parse(url.replaceFirst("https://", "http://")))   // see network_security_config.xml
    }

    /** ◀ / ▶ on the remote: dip to black, then the previous / next place. */
    fun skip(dir: Int) {
        if (switching) return
        switching = true
        if (dir < 0) i -= 2
        curtain.animate().alpha(1f).setStartDelay(0).setDuration(260).withEndAction { video.stopPlayback(); play() }.start()
    }
}
