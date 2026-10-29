package com.arshanwar.tv_launcher

import android.content.Context
import android.graphics.*
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.media.session.PlaybackState
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.text.TextUtils
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewOutlineProvider
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView

/**
 * What's playing, read from `dumpsys media_session` (DUMP is granted over adb). This TV is a low-RAM device, where
 * Android switches off notification listeners and with them the normal MediaSessionManager access, so the dump is
 * the only way in. Control goes through media keys (AudioManager routes them to the playing session).
 */
class MediaInfo(val pkg: String, val state: Int, val position: Long, val updated: Long, val speed: Float, val actions: Long,
                val title: String?, val sub: String?) {
    val playing get() = state == PlaybackState.STATE_PLAYING || state == PlaybackState.STATE_BUFFERING
    fun positionNow(): Long = if (state == PlaybackState.STATE_PLAYING) position + ((SystemClock.elapsedRealtime() - updated) * speed).toLong() else position
}

object MediaDump {
    private val ignore = setOf("com.mediatek.wwtv.tvcenter")
    /** Slow (spawns dumpsys, ~50ms): call off the UI thread. */
    fun read(ctx: Context): List<MediaInfo> = try {
        val p = Runtime.getRuntime().exec(arrayOf("dumpsys", "media_session"))
        val lines = p.inputStream.bufferedReader().readLines(); p.waitFor()
        val out = ArrayList<MediaInfo>()
        var pkg: String? = null; var st: String? = null; var meta: String? = null
        fun flush() {
            val k = pkg ?: return
            if (k != ctx.packageName && k !in ignore && st != null) {
                fun num(key: String) = Regex("\\b$key=([-0-9.]+)").find(st!!)?.groupValues?.get(1)
                // MediaDescription prints as "title, subtitle, description"; YouTube-style subtitles often contain commas, titles rarely do
                val desc = meta?.substringAfter("description=", "")?.removeSuffix(", null")?.takeIf { it.isNotBlank() && it != "null" && it != "null, null" }
                val title = desc?.substringBefore(", ")?.takeIf { it != "null" }
                val sub = desc?.substringAfter(", ", "")?.removeSuffix(", null")?.takeIf { it.isNotBlank() && it != "null" }
                out.add(MediaInfo(k, num("state")?.toIntOrNull() ?: 0, num("position")?.toLongOrNull() ?: 0, num("updated")?.toLongOrNull() ?: 0,
                    num("speed")?.toFloatOrNull() ?: 1f, num("actions")?.toLongOrNull() ?: 0, title, sub))
            }
            pkg = null; st = null; meta = null
        }
        for (raw in lines) {
            val l = raw.trim()
            when {
                l.startsWith("package=") -> { flush(); pkg = l.removePrefix("package=") }
                l.startsWith("state=PlaybackState") -> st = l
                l.startsWith("metadata:") -> meta = l
                l.startsWith("Session2Tokens") -> flush()
            }
        }
        flush(); out
    } catch (e: Exception) { emptyList() }

    /** The session worth showing: a playing one, else a paused one, preferring the app that's in front. */
    fun active(ctx: Context, front: String? = null): MediaInfo? = read(ctx)
        .filter { it.playing || it.state == PlaybackState.STATE_PAUSED || it.pkg == front }   // Prime reports state 0 even mid-film: trust the app in front
        .maxByOrNull { (if (it.playing) 4 else 0) + (if (it.pkg == front) 2 else 0) + (if (it.title != null) 1 else 0) }

    fun key(ctx: Context, code: Int) {
        val am = ctx.getSystemService(Context.AUDIO_SERVICE) as android.media.AudioManager
        am.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, code))
        am.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, code))
    }
}

/**
 * Now Playing card: app artwork, title, app, elapsed time and transport buttons. Used in the launcher's Control Center
 * when something keeps playing in the background (music, SmartTube background play). Re-reads the dump every 2s only while it's on screen.
 */
class NowPlayingCard(private val ctx: Context, private var info: MediaInfo) {
    private val main = Handler(Looper.getMainLooper())
    private val d = ctx.resources.displayMetrics.density
    private fun dp(v: Int) = (v * d).toInt()
    lateinit var view: View; private set
    lateinit var playButton: View; private set
    private var refresh: () -> Unit = {}
    private var running = false
    private var ticks = 0
    private val tick = object : Runnable {
        override fun run() {
            if (!running) return
            if (ticks++ % 2 == 0) poll() else refresh()
            main.postDelayed(this, 1000)
        }
    }
    private fun poll() {
        val pkg = info.pkg
        Thread {
            android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND)
            val fresh = MediaDump.read(ctx).firstOrNull { it.pkg == pkg }
            main.post { if (running && fresh != null) info = fresh; if (running) refresh() }
        }.start()
    }

    init { build() }

    fun start() { running = true; ticks = 1; main.removeCallbacks(tick); main.post(tick) }
    fun stop() { running = false; main.removeCallbacks(tick) }

    private fun build() {
        Fonts.init(ctx)
        val pm = ctx.packageManager
        val box = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL; clipChildren = false; clipToPadding = false; setPadding(dp(14), dp(14), dp(14), dp(12))
            background = GradientDrawable().apply { cornerRadius = 18 * d; setColor(0x1FFFFFFF) }
        }
        val top = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val r = 10 * d
        val art = FrameLayout(ctx).apply {
            outlineProvider = object : ViewOutlineProvider() { override fun getOutline(v: View, o: Outline) { o.setRoundRect(0, 0, v.width, v.height, r) } }
            clipToOutline = true; background = ColorDrawable(0xFF1B1C21.toInt())
        }
        // the app's TV banner when it has one (Netflix, YouTube...), else its icon
        val ai = try { pm.getApplicationInfo(info.pkg, 0) } catch (e: Exception) { null }
        val banner = ai?.let { try { pm.getApplicationBanner(it) } catch (e: Exception) { null } }
        val artW = if (banner != null) dp(104) else dp(62)
        art.addView(ImageView(ctx).apply {
            scaleType = if (banner != null) ImageView.ScaleType.CENTER_CROP else ImageView.ScaleType.FIT_CENTER
            setImageDrawable(banner ?: ai?.let { pm.getApplicationIcon(it) })
        }, if (banner != null) FrameLayout.LayoutParams(-1, -1) else FrameLayout.LayoutParams(dp(44), dp(44), Gravity.CENTER))
        top.addView(art, LinearLayout.LayoutParams(artW, dp(62)))
        val texts = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(12), 0, 0, 0) }
        val appName = ai?.let { pm.getApplicationLabel(it).toString() } ?: info.pkg
        val eyebrow = TextView(ctx).apply { typeface = Fonts.semibold; textSize = 10.5f; letterSpacing = 0.06f; setTextColor(0x99FFFFFF.toInt()); maxLines = 1 }
        val title = TextView(ctx).apply { typeface = Fonts.semibold; textSize = 16f; setTextColor(Color.WHITE); maxLines = 2; ellipsize = TextUtils.TruncateAt.END; setPadding(0, dp(3), 0, 0) }
        val sub = TextView(ctx).apply { typeface = Fonts.regular; textSize = 12.5f; setTextColor(0xB3FFFFFF.toInt()); maxLines = 1; ellipsize = TextUtils.TruncateAt.END }
        texts.addView(eyebrow); texts.addView(title); texts.addView(sub)
        top.addView(texts, LinearLayout.LayoutParams(0, -2, 1f))
        box.addView(top)

        val acts = info.actions
        // ⟲ ⟳ when the app supports rewind / fast-forward (video), otherwise previous / next track (music)
        val seekable = acts and (PlaybackState.ACTION_REWIND or PlaybackState.ACTION_FAST_FORWARD) != 0L || acts == 0L
        val controls = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER; clipChildren = false; setPadding(0, dp(12), 0, 0) }
        val back = button({ c -> if (seekable) Glyphs.jump(ctx, c, false) else Glyphs.skip(ctx, c, false) }) {
            MediaDump.key(ctx, if (seekable) KeyEvent.KEYCODE_MEDIA_REWIND else KeyEvent.KEYCODE_MEDIA_PREVIOUS) }
        val play = button({ c -> if (info.playing) Glyphs.pause(ctx, c) else Glyphs.play(ctx, c) }) {
            MediaDump.key(ctx, if (info.playing) KeyEvent.KEYCODE_MEDIA_PAUSE else KeyEvent.KEYCODE_MEDIA_PLAY) }
        val fwd = button({ c -> if (seekable) Glyphs.jump(ctx, c, true) else Glyphs.skip(ctx, c, true) }) {
            MediaDump.key(ctx, if (seekable) KeyEvent.KEYCODE_MEDIA_FAST_FORWARD else KeyEvent.KEYCODE_MEDIA_NEXT) }
        for ((i, b) in listOf(back, play, fwd).withIndex()) controls.addView(b, LinearLayout.LayoutParams(dp(if (i == 1) 52 else 44), dp(if (i == 1) 52 else 44)).apply { leftMargin = dp(14); rightMargin = dp(14) })
        box.addView(controls)

        fun clock(ms: Long): String { val s = ms / 1000; return if (s >= 3600) String.format(java.util.Locale.US, "%d:%02d:%02d", s / 3600, s / 60 % 60, s % 60) else String.format(java.util.Locale.US, "%d:%02d", s / 60, s % 60) }
        refresh = {
            val i = info
            eyebrow.text = (if (i.playing) "NOW PLAYING" else "PAUSED") + "  \u00b7  " + appName.uppercase()
            title.text = i.title ?: appName
            val pos = i.positionNow()
            sub.text = listOfNotNull(if (pos > 0) clock(pos) else null, i.sub).joinToString("  \u00b7  ").ifBlank { if (i.playing) "Playing" else "Paused" }
            (play.tag as Runnable).run()
        }
        refresh()
        view = box; playButton = play
    }

    private fun button(icon: (Int) -> Drawable, onClick: () -> Unit): View {
        val b = FrameLayout(ctx).apply { isFocusable = true; isClickable = true }
        val bg = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(0x26FFFFFF) }
        b.background = bg
        val iv = ImageView(ctx).apply { setImageDrawable(icon(Color.WHITE)) }
        b.addView(iv, FrameLayout.LayoutParams(dp(18), dp(18), Gravity.CENTER))
        b.tag = Runnable { iv.setImageDrawable(icon(if (b.isFocused) 0xFF111111.toInt() else Color.WHITE)) }
        b.setOnFocusChangeListener { _, f ->
            if (f) b.allowOverflow(4)
            bg.setColor(if (f) 0xF2FFFFFF.toInt() else 0x26FFFFFF); (b.tag as Runnable).run()
            b.animate().scaleX(if (f) 1.12f else 1f).scaleY(if (f) 1.12f else 1f).translationZ(if (f) 8 * d else 0f).setDuration(220).setInterpolator(appleOut).start()
        }
        b.setOnClickListener {
            b.animate().scaleX(1.0f).scaleY(1.0f).setDuration(90).setInterpolator(appleEase).withEndAction {
                onClick(); main.postDelayed({ poll() }, 450)
                b.animate().scaleX(1.12f).scaleY(1.12f).setDuration(300).setInterpolator(appleOut).start()
            }.start()
        }
        return b
    }
}

/** Transport glyphs, drawn once at 128px (no icon assets). */
object Glyphs {
    private fun icon(c: Context, draw: Canvas.(Float) -> Unit): Drawable {
        val s = 128; val bm = Bitmap.createBitmap(s, s, Bitmap.Config.ARGB_8888); Canvas(bm).draw(s.toFloat()); return BitmapDrawable(c.resources, bm)
    }
    fun play(c: Context, color: Int) = icon(c) { s ->
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color; pathEffect = CornerPathEffect(s * 0.08f) }
        drawPath(Path().apply { moveTo(s * 0.18f, s * 0.08f); lineTo(s * 0.92f, s * 0.5f); lineTo(s * 0.18f, s * 0.92f); close() }, p)
    }
    fun pause(c: Context, color: Int) = icon(c) { s ->
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color }
        drawRoundRect(RectF(s * 0.2f, s * 0.1f, s * 0.4f, s * 0.9f), s * 0.05f, s * 0.05f, p)
        drawRoundRect(RectF(s * 0.6f, s * 0.1f, s * 0.8f, s * 0.9f), s * 0.05f, s * 0.05f, p)
    }
    fun skip(c: Context, color: Int, next: Boolean) = icon(c) { s ->
        if (!next) scale(-1f, 1f, s / 2, s / 2)
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color; pathEffect = CornerPathEffect(s * 0.05f) }
        drawPath(Path().apply { moveTo(s * 0.06f, s * 0.16f); lineTo(s * 0.48f, s * 0.5f); lineTo(s * 0.06f, s * 0.84f); close() }, p)
        drawPath(Path().apply { moveTo(s * 0.44f, s * 0.16f); lineTo(s * 0.86f, s * 0.5f); lineTo(s * 0.44f, s * 0.84f); close() }, p)
        drawRoundRect(RectF(s * 0.84f, s * 0.16f, s * 0.95f, s * 0.84f), s * 0.03f, s * 0.03f, p)
    }
    /** Circular arrow with "10" inside: tvOS's 10-second skip glyph. */
    fun jump(c: Context, color: Int, fwd: Boolean) = icon(c) { s ->
        val st = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color; style = Paint.Style.STROKE; strokeWidth = s * 0.075f; strokeCap = Paint.Cap.ROUND }
        save(); if (!fwd) scale(-1f, 1f, s / 2, s / 2)
        drawArc(RectF(s * 0.1f, s * 0.1f, s * 0.9f, s * 0.9f), -60f, 300f, false, st)
        drawPath(Path().apply { moveTo(s * 0.62f, s * 0.02f); lineTo(s * 0.86f, s * 0.2f); lineTo(s * 0.6f, s * 0.32f); close() }, Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color })
        restore()
        drawText("10", s / 2, s * 0.62f, Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color; textSize = s * 0.34f; typeface = Fonts.semibold; textAlign = Paint.Align.CENTER })
    }
}
