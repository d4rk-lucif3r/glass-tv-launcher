package com.arshanwar.tv_launcher

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.*
import android.os.SystemClock
import android.view.View
import android.view.animation.LinearInterpolator
import android.widget.FrameLayout

/**
 * Top Shelf backdrop. Draws the current artwork with a slow Ken Burns drift, transitions between
 * slides (defocus crossfade when auto-advancing, velocity-based motion blur when sliding), collapses
 * into a blurred, dimmed wallpaper when the user scrolls down, and a soft spotlight under the focus.
 */
class HeroView(ctx: Context) : View(ctx) {
    class Slide(val sharp: Bitmap, val soft: Bitmap, val streak: Bitmap) {
        var start = SystemClock.uptimeMillis()
    }

    companion object { const val KB_MS = 22000L }

    private var cur: Slide? = null
    private var prev: Slide? = null
    private var t = 1f
    private var dir = 0
    private var trans: ValueAnimator? = null
    var collapse = 0f; private set
    private var collapseAnim: ValueAnimator? = null

    /** Views that sample this backdrop (glass) and must redraw with it. */
    val glass = ArrayList<View>()

    private val d = resources.displayMetrics.density
    private val paint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.DITHER_FLAG)
    private val bottomShade = Paint(Paint.DITHER_FLAG)
    private val leftShade = Paint(Paint.DITHER_FLAG)
    private val topShade = Paint(Paint.DITHER_FLAG)
    private val dim = Paint().apply { color = Color.BLACK }
    private val fallback = Paint(Paint.DITHER_FLAG)
    private val spot = Paint(Paint.ANTI_ALIAS_FLAG or Paint.DITHER_FLAG)
    private val m = Matrix()

    private var spotX = -1f; private var spotY = -1f
    private var spotFX = 0f; private var spotFY = 0f
    private var spotTX = 0f; private var spotTY = 0f
    private var spotA = 0f; private var spotFromA = 0f; private var spotToA = 0f
    private var spotAnim: ValueAnimator? = null

    /** True while the Ken Burns drift is still moving (the activity's frame ticker polls this). */
    fun needsFrame(): Boolean {
        val cs = cur ?: return false
        return SystemClock.uptimeMillis() - cs.start < KB_MS && collapse < 0.999f
    }
    fun frame() = invalidate()   // Ken Burns tick: glass samples the blurred layer, which doesn't visibly move
    private fun kick() {}
    private fun invalidateAll() { invalidate(); for (g in glass) g.invalidate() }
    private var glassTurn = 0
    /** During transitions, refresh one glass element per frame in rotation: the re-render cost is spread
     *  thin instead of landing on a single frame (which showed up as periodic ~30ms GPU spikes). */
    private fun invalidateThrottled() {
        invalidate()
        if (glass.isNotEmpty()) glass[glassTurn++ % glass.size].invalidate()
    }

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
        val fw = w.toFloat(); val fh = h.toFloat()
        bottomShade.shader = LinearGradient(0f, fh * 0.36f, 0f, fh, intArrayOf(0x00000000, 0xB8000000.toInt(), 0xF2000000.toInt()), floatArrayOf(0f, 0.5f, 1f), Shader.TileMode.CLAMP)
        leftShade.shader = LinearGradient(0f, 0f, fw * 0.64f, 0f, intArrayOf(0xB3000000.toInt(), 0x4D000000, 0x00000000), floatArrayOf(0f, 0.5f, 1f), Shader.TileMode.CLAMP)
        topShade.shader = LinearGradient(0f, 0f, 0f, fh * 0.22f, intArrayOf(0x73000000, 0x00000000), null, Shader.TileMode.CLAMP)
        fallback.shader = RadialGradient(fw * 0.22f, fh * 0.12f, fw * 0.95f, intArrayOf(0xFF26304F.toInt(), 0xFF10131D.toInt(), 0xFF050608.toInt()), floatArrayOf(0f, 0.5f, 1f), Shader.TileMode.CLAMP)
        spot.shader = RadialGradient(0f, 0f, 230f * d, intArrayOf(0x42FFFFFF, 0x14FFFFFF, 0x00FFFFFF), floatArrayOf(0f, 0.45f, 1f), Shader.TileMode.CLAMP)
    }

    // ---------- slides ----------
    /** [direction] 0 = defocus crossfade, ±1 = slide in from the right / left with motion blur. */
    fun setSlide(s: Slide, direction: Int) {
        s.start = SystemClock.uptimeMillis()
        if (cur == null) { cur = s; invalidateAll(); kick(); return }
        trans?.end()
        prev = cur; cur = s; dir = direction; t = 0f
        trans = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = if (direction == 0) 650 else 700
            interpolator = LinearInterpolator()
            addUpdateListener { t = it.animatedValue as Float; invalidateThrottled() }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(a: Animator) { prev = null; invalidateAll() }
            })
            start()
        }
        kick()
    }

    fun setCollapsed(v: Boolean) {
        val target = if (v) 1f else 0f
        if (collapse == target && collapseAnim?.isRunning != true) return
        collapseAnim?.cancel()
        collapseAnim = ValueAnimator.ofFloat(collapse, target).apply {
            duration = 550; interpolator = appleEase
            addUpdateListener { collapse = it.animatedValue as Float; invalidateThrottled() }
            addListener(object : AnimatorListenerAdapter() { override fun onAnimationEnd(a: Animator) { invalidateAll() } })
            start()
        }
        if (!v) kick()
    }

    // ---------- spotlight ----------
    fun spotlight(x: Float, y: Float, on: Boolean) {
        spotAnim?.cancel()
        if (spotX < 0) { spotX = x; spotY = y }
        spotFX = spotX; spotFY = spotY; spotTX = x; spotTY = y
        spotFromA = spotA; spotToA = if (on) 1f else 0f
        spotAnim = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 520; interpolator = appleOut
            addUpdateListener {
                val p = it.animatedValue as Float
                spotX = spotFX + (spotTX - spotFX) * p; spotY = spotFY + (spotTY - spotFY) * p
                spotA = spotFromA + (spotToA - spotFromA) * p
                invalidate()
            }
            start()
        }
    }

    /** The glow under the focused card takes on that app's colour (lightened), like light bouncing off it. */
    private var spotRgb = 0xFFFFFF
    fun spotColor(rgb: Int) {
        val c = rgb and 0xFFFFFF
        if (c == spotRgb || width == 0) return
        spotRgb = c
        spot.shader = RadialGradient(0f, 0f, 230f * d, intArrayOf(0x4C000000 or c, 0x18000000 or c, c), floatArrayOf(0f, 0.45f, 1f), Shader.TileMode.CLAMP)
        invalidate()
    }

    /** Keep the spotlight glued to the focused card while the page scrolls. */
    fun spotTrack(x: Float, y: Float) {
        spotTX = x; spotTY = y
        if (spotAnim?.isRunning != true) { spotX = x; spotY = y; invalidate() }
    }

    // ---------- drawing ----------
    private fun matrix(b: Bitmap, start: Long, dx: Float): Matrix {
        val w = width.toFloat(); val h = height.toFloat()
        val sc = maxOf(w / b.width, h / b.height)
        m.setScale(sc, sc)
        m.postTranslate((w - b.width * sc) / 2f, (h - b.height * sc) / 2f)
        val k = ((SystemClock.uptimeMillis() - start) / KB_MS.toFloat()).coerceIn(0f, 1f)
        val z = 1.02f + 0.07f * appleEase.getInterpolation(k)
        m.postScale(z, z, w * 0.64f, h * 0.36f)
        m.postTranslate(dx - w * 0.012f * k, 0f)
        return m
    }

    private fun drawSlide(c: Canvas, s: Slide, a: Float, dx: Float, defocus: Float, motion: Float, softOnly: Boolean) {
        if (a <= 0.004f) return
        if (softOnly) {
            paint.alpha = (255 * a).toInt(); c.drawBitmap(s.soft, matrix(s.soft, s.start, dx), paint); return
        }
        paint.alpha = (255 * a).toInt(); c.drawBitmap(s.sharp, matrix(s.sharp, s.start, dx), paint)
        if (defocus > 0.01f) { paint.alpha = (255 * a * defocus).toInt(); c.drawBitmap(s.soft, matrix(s.soft, s.start, dx), paint) }
        if (motion > 0.01f) { paint.alpha = (255 * a * motion).toInt(); c.drawBitmap(s.streak, matrix(s.streak, s.start, dx), paint) }
    }

    /** At peak slide speed the streak alone is drawn (one fill per image instead of two). */
    private fun drawFast(c: Canvas, s: Slide, a: Float, dx: Float, motion: Float, softOnly: Boolean) {
        if (softOnly || motion < 0.55f) { drawSlide(c, s, a, dx, 0f, motion, softOnly); return }
        paint.alpha = (255 * a).toInt(); c.drawBitmap(s.streak, matrix(s.streak, s.start, dx), paint)
    }

    private fun layers(c: Canvas, softOnly: Boolean, mul: Float = 1f) {
        val cs = cur ?: return
        val p = prev
        if (p == null || trans?.isRunning != true) { drawSlide(c, cs, mul, 0f, 0f, 0f, softOnly); return }
        val w = width.toFloat()
        if (dir == 0) {
            // plain two-layer crossfade: the extra defocus layers doubled GPU fill and dropped frames
            val e = appleEase.getInterpolation(t)
            drawSlide(c, p, mul, 0f, 0f, 0f, softOnly)
            drawSlide(c, cs, mul * e, 0f, 0f, 0f, softOnly)
        } else {
            val e = appleOut.getInterpolation(t)
            // streak strength follows the actual on-screen velocity of the slide
            val v = (appleOut.getInterpolation(minOf(1f, t + 0.02f)) - e) / 0.02f
            val mb = (v / 2.2f).coerceIn(0f, 1f)
            drawFast(c, p, mul, -dir * w * e, mb, softOnly)
            drawFast(c, cs, mul, dir * w * (1f - e), mb, softOnly)
        }
    }

    /** Each gradient is drawn only over the band it actually covers: ~1.5 screens of fill instead of 3. */
    private fun shades(c: Canvas) {
        val w = width.toFloat(); val h = height.toFloat()
        if (collapse < 0.999f) { leftShade.alpha = (255 * (1f - collapse)).toInt(); c.drawRect(0f, 0f, w * 0.64f, h, leftShade) }
        c.drawRect(0f, 0f, w, h * 0.22f, topShade)
        c.drawRect(0f, h * 0.36f, w, h, bottomShade)
        if (collapse > 0f) { dim.alpha = (110 * collapse).toInt(); c.drawRect(0f, 0f, w, h, dim) }
    }

    override fun onDraw(c: Canvas) {
        // the artwork always covers the whole view (cover-scaled), so no clear is needed underneath it
        when {
            cur == null -> c.drawRect(0f, 0f, width.toFloat(), height.toFloat(), fallback)
            collapse >= 0.999f -> layers(c, true)          // fully collapsed: the blur covers the sharp image
            else -> { layers(c, false); if (collapse > 0.003f) layers(c, true, collapse) }
        }
        shades(c)
        if (spotX >= 0 && spotA > 0.01f) {
            spot.alpha = (255 * spotA).toInt()
            c.save(); c.translate(spotX, spotY); c.scale(1.75f, 1f)
            c.drawCircle(0f, 0f, 230f * d, spot)
            c.restore()
        }
    }

    /** Sharp artwork only, same transform as full screen: used by the collapsed mini shelf. */
    fun drawBand(c: Canvas) {
        c.drawColor(Color.BLACK)
        if (cur == null) c.drawRect(0f, 0f, width.toFloat(), height.toFloat(), fallback) else layers(c, false)
    }

    /** What glass sees: the same scene, fully blurred. */
    fun drawBackdrop(c: Canvas) {
        c.drawColor(Color.BLACK)
        if (cur == null) c.drawRect(0f, 0f, width.toFloat(), height.toFloat(), fallback) else layers(c, true)
        shades(c)
    }
}

/**
 * Liquid-glass surface: clips to a rounded rect, draws the hero's blurred backdrop at exactly the
 * position it covers on screen (so it really is a blur of what's behind), then a light tint, a top
 * sheen and a specular rim. [solid] blends to an opaque white fill (focused buttons).
 */
open class GlassView(ctx: Context, private val hero: HeroView, private val radius: Float) : FrameLayout(ctx) {
    private val rect = RectF()
    private val tint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x2EFFFFFF }
    private val sheen = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rim = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = resources.displayMetrics.density * 1.2f }
    private val white = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
    private val loc = IntArray(2)
    private val hloc = IntArray(2)
    private var solidAnim: ValueAnimator? = null
    var solid = 0f
        set(v) { field = v; invalidate() }

    init {
        setWillNotDraw(false)
        outlineProvider = object : android.view.ViewOutlineProvider() {
            override fun getOutline(v: View, o: Outline) { o.setRoundRect(0, 0, v.width, v.height, radius) }
        }
        clipToOutline = true   // GPU rounded-rect clip: far cheaper than clipPath's stencil on Mali
        // Render once into a GPU texture; while the backdrop is unchanged each frame composites one quad
        // instead of re-issuing ~7 draws per glass element (the render thread was the bottleneck).
        setLayerType(View.LAYER_TYPE_HARDWARE, null)
    }

    override fun onAttachedToWindow() { super.onAttachedToWindow(); if (this !in hero.glass) hero.glass.add(this) }
    override fun onDetachedFromWindow() { hero.glass.remove(this); super.onDetachedFromWindow() }

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
        val s = rim.strokeWidth / 2
        rect.set(s, s, w - s, h - s)
        sheen.shader = LinearGradient(0f, 0f, 0f, h.toFloat(), intArrayOf(0x33FFFFFF, 0x0AFFFFFF, 0x00FFFFFF), floatArrayOf(0f, 0.5f, 1f), Shader.TileMode.CLAMP)
        rim.shader = LinearGradient(0f, 0f, w * 0.35f, h.toFloat(), intArrayOf(0xB3FFFFFF.toInt(), 0x1FFFFFFF, 0x4DFFFFFF), floatArrayOf(0f, 0.55f, 1f), Shader.TileMode.CLAMP)
    }

    fun animateSolid(target: Float) {
        solidAnim?.cancel()
        solidAnim = ValueAnimator.ofFloat(solid, target).apply {
            duration = 260; interpolator = appleEase
            addUpdateListener { solid = it.animatedValue as Float }
            start()
        }
    }

    override fun onDraw(c: Canvas) {
        getLocationInWindow(loc); hero.getLocationInWindow(hloc)
        c.save()
        c.scale(1f / scaleX, 1f / scaleY)
        c.translate((hloc[0] - loc[0]).toFloat(), (hloc[1] - loc[1]).toFloat())
        hero.drawBackdrop(c)
        c.restore()
        c.drawRoundRect(rect, radius, radius, tint)
        c.drawRoundRect(rect, radius, radius, sheen)
        if (solid > 0.01f) { white.alpha = (245 * solid).toInt(); c.drawRoundRect(rect, radius, radius, white) }
        c.drawRoundRect(rect, radius, radius, rim)
    }
}

/** Specular layer for app cards: rim light always, plus a glare that follows the card's tilt when focused. */
class Glare(ctx: Context, private val radius: Float) : View(ctx) {
    var gx = 0.3f; var gy = 0f
    var level = 0f
        set(v) { field = v; invalidate() }
    private val p = Paint(Paint.ANTI_ALIAS_FLAG or Paint.DITHER_FLAG)
    private val rim = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = resources.displayMetrics.density * 1.1f }
    private val rect = RectF()

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
        val s = rim.strokeWidth / 2
        rect.set(s, s, w - s, h - s)
        rim.shader = LinearGradient(0f, 0f, 0f, h.toFloat(), intArrayOf(0x8CFFFFFF.toInt(), 0x12FFFFFF, 0x2EFFFFFF), floatArrayOf(0f, 0.5f, 1f), Shader.TileMode.CLAMP)
    }

    override fun onDraw(c: Canvas) {
        val w = width.toFloat(); val h = height.toFloat()
        if (level > 0.01f) {
            val a = (0x66 * level).toInt()
            p.shader = RadialGradient(gx * w, gy * h, w * 0.85f, intArrayOf(Color.argb(a, 255, 255, 255), Color.argb(a / 4, 255, 255, 255), 0), floatArrayOf(0f, 0.45f, 1f), Shader.TileMode.CLAMP)
            c.drawRect(0f, 0f, w, h, p)
        }
        rim.alpha = (110 + 145 * level).toInt()
        c.drawRoundRect(rect, radius, radius, rim)
    }
}

/** Carousel position indicator: dots, the active one stretched into a capsule. */
class Dots(ctx: Context) : View(ctx) {
    private var n = 0; private var active = 0
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val d = resources.displayMetrics.density
    fun set(count: Int, index: Int) { n = count; active = index; requestLayout(); invalidate() }
    override fun onMeasure(wm: Int, hm: Int) = setMeasuredDimension(((n - 1) * 11 * d + 18 * d).toInt().coerceAtLeast(1), (8 * d).toInt())
    override fun onDraw(c: Canvas) {
        var x = 0f; val cy = height / 2f; val r = 2.6f * d
        for (i in 0 until n) {
            if (i == active) {
                p.color = 0xEBFFFFFF.toInt(); c.drawRoundRect(x, cy - r, x + 16 * d, cy + r, r, r, p); x += 16 * d + 6 * d
            } else {
                p.color = 0x59FFFFFF; c.drawCircle(x + r, cy, r, p); x += 2 * r + 6 * d
            }
        }
    }
}

/**
 * Collapsed Top Shelf: the current item as a compact card (full artwork + details) under the nav bar,
 * shown while the user is down in the app grid. Everything inside fades out at the bottom so rows dissolve beneath it.
 */
class MiniShelf(ctx: Context, private val hero: HeroView) : FrameLayout(ctx) {
    // Transparent on purpose: the collapsed hero behind it already shows the blurred backdrop, and rows
    // fade themselves out as they slide under (see onPageScroll). Only a legibility gradient is drawn,
    // so there's no offscreen buffer or per-frame backdrop re-render (that cost ~25ms of GPU per refresh).
    private val left = Paint(Paint.DITHER_FLAG)

    init { setWillNotDraw(false); clipChildren = false }

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
        // fades out to the right AND toward the bottom, so it never leaves a hard edge above the app rows
        val across = LinearGradient(0f, 0f, w * 0.6f, 0f, intArrayOf(0x8C000000.toInt(), 0x00000000), null, Shader.TileMode.CLAMP)
        val down = LinearGradient(0f, h * 0.55f, 0f, h.toFloat(), intArrayOf(Color.BLACK, Color.TRANSPARENT), null, Shader.TileMode.CLAMP)
        left.shader = ComposeShader(across, down, PorterDuff.Mode.DST_IN)
    }

    override fun dispatchDraw(c: Canvas) {
        c.drawRect(0f, 0f, width * 0.6f, height.toFloat(), left)
        super.dispatchDraw(c)
    }
}
