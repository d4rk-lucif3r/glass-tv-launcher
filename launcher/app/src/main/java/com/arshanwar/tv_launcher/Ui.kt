package com.arshanwar.tv_launcher

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Typeface
import android.view.animation.PathInterpolator

/** Inter is the closest open-source match to Apple's SF Pro (bundled under the OFL, see assets/fonts). */
object Fonts {
    lateinit var regular: Typeface
    lateinit var medium: Typeface
    lateinit var semibold: Typeface
    lateinit var display: Typeface
    lateinit var displayBold: Typeface

    fun init(c: Context) {
        if (::regular.isInitialized) return
        val a = c.assets
        regular = Typeface.createFromAsset(a, "fonts/Inter-Regular.ttf")
        medium = Typeface.createFromAsset(a, "fonts/Inter-Medium.ttf")
        semibold = Typeface.createFromAsset(a, "fonts/Inter-SemiBold.ttf")
        display = Typeface.createFromAsset(a, "fonts/InterDisplay-SemiBold.ttf")
        displayBold = Typeface.createFromAsset(a, "fonts/InterDisplay-Bold.ttf")
    }
}

/**
 * The user's TV has animator_duration_scale = 0 (animations off system-wide, a common speed tweak),
 * which would make every launcher animation snap to its end. Re-enable animators for this process
 * only via ValueAnimator's hidden setDurationScale; the rest of the system stays unanimated.
 */
fun enableOwnAnimations() {
    try {
        android.animation.ValueAnimator::class.java.getMethod("setDurationScale", Float::class.javaPrimitiveType).invoke(null, 1f)
    } catch (e: Throwable) {
        android.util.Log.w("TvHome", "could not override animator scale", e)
    }
}

/** UIKit's default ease-in-out curve. */
val appleEase = PathInterpolator(0.25f, 0.1f, 0.25f, 1f)
/** Fast-out, long settle: the feel of tvOS focus moves. */
val appleOut = PathInterpolator(0.16f, 1f, 0.3f, 1f)

object Blur {
    /**
     * Real blur: downscale to [w] px wide, then 3-pass box blur (~gaussian) per axis.
     * The small result is drawn upscaled with bilinear filtering, which stays smooth because
     * the blurred image has no high frequencies left.
     */
    fun box(src: Bitmap, w: Int, rx: Int, ry: Int, passes: Int = 3): Bitmap {
        val h = maxOf(1, w * src.height / src.width)
        val s = Bitmap.createScaledBitmap(src, w, h, true)
        val px = IntArray(w * h)
        s.getPixels(px, 0, w, 0, 0, w, h)
        if (s !== src) s.recycle()
        val tmp = IntArray(maxOf(w, h))
        repeat(passes) {
            if (rx > 0) horiz(px, w, h, rx, tmp)
            if (ry > 0) vert(px, w, h, ry, tmp)
        }
        return Bitmap.createBitmap(px, w, h, Bitmap.Config.ARGB_8888)
    }

    private fun horiz(p: IntArray, w: Int, h: Int, r: Int, out: IntArray) {
        val div = 2 * r + 1
        for (y in 0 until h) {
            val o = y * w
            var sr = 0; var sg = 0; var sb = 0
            for (i in -r..r) {
                val c = p[o + i.coerceIn(0, w - 1)]
                sr += (c shr 16) and 255; sg += (c shr 8) and 255; sb += c and 255
            }
            for (x in 0 until w) {
                out[x] = -0x1000000 or ((sr / div) shl 16) or ((sg / div) shl 8) or (sb / div)
                val a = p[o + minOf(x + r + 1, w - 1)]
                val b = p[o + maxOf(x - r, 0)]
                sr += ((a shr 16) and 255) - ((b shr 16) and 255)
                sg += ((a shr 8) and 255) - ((b shr 8) and 255)
                sb += (a and 255) - (b and 255)
            }
            System.arraycopy(out, 0, p, o, w)
        }
    }

    private fun vert(p: IntArray, w: Int, h: Int, r: Int, out: IntArray) {
        val div = 2 * r + 1
        for (x in 0 until w) {
            var sr = 0; var sg = 0; var sb = 0
            for (i in -r..r) {
                val c = p[x + i.coerceIn(0, h - 1) * w]
                sr += (c shr 16) and 255; sg += (c shr 8) and 255; sb += c and 255
            }
            for (y in 0 until h) {
                out[y] = -0x1000000 or ((sr / div) shl 16) or ((sg / div) shl 8) or (sb / div)
                val a = p[x + minOf(y + r + 1, h - 1) * w]
                val b = p[x + maxOf(y - r, 0) * w]
                sr += ((a shr 16) and 255) - ((b shr 16) and 255)
                sg += ((a shr 8) and 255) - ((b shr 8) and 255)
                sb += (a and 255) - (b and 255)
            }
            for (y in 0 until h) p[x + y * w] = out[y]
        }
    }
}

/** Background work that quietly does nothing once its screen has been destroyed (instead of crashing). */
fun java.util.concurrent.ExecutorService.go(task: () -> Unit) {
    if (isShutdown) return
    try { execute(task) } catch (e: java.util.concurrent.RejectedExecutionException) { }
}

/** Call when a control scales up on focus: lets its parents draw the overflow instead of clipping the corners. */
fun android.view.View.allowOverflow(levels: Int = 3) {
    var p = parent
    repeat(levels) {
        val g = p as? android.view.ViewGroup ?: return
        if (g is android.widget.ScrollView) return   // a vertical scroller must keep clipping, or rows slide over what's above it
        g.clipChildren = false; g.clipToPadding = false
        p = g.parent
    }
}

/** tvOS-style UI sounds: a soft tick on focus moves, a muted bump at the end of a row. Can be switched off. */
object Fx {
    private var pool: android.media.SoundPool? = null
    private var tick = 0; private var edge = 0
    private var last = 0L
    var enabled = true
    fun init(c: android.content.Context) {
        enabled = c.getSharedPreferences("launcher", android.content.Context.MODE_PRIVATE).getBoolean("sounds", true)
        if (pool != null) return
        pool = android.media.SoundPool.Builder().setMaxStreams(2).setAudioAttributes(
            android.media.AudioAttributes.Builder().setUsage(android.media.AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                .setContentType(android.media.AudioAttributes.CONTENT_TYPE_SONIFICATION).build()).build().also {
            tick = it.load(c, R.raw.fx_tick, 1); edge = it.load(c, R.raw.fx_edge, 1)
        }
    }
    fun tick() { val n = android.os.SystemClock.uptimeMillis(); if (!enabled || n - last < 45) return; last = n; pool?.play(tick, 0.35f, 0.35f, 1, 0, 1f) }
    fun edge() { if (enabled) pool?.play(edge, 0.5f, 0.5f, 1, 0, 1f) }
}
