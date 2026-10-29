package com.arshanwar.tv_launcher

import android.animation.ValueAnimator
import android.app.Activity
import android.content.*
import android.graphics.*
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.text.Editable
import android.text.InputType
import android.text.TextUtils
import android.text.TextWatcher
import android.view.*
import android.view.animation.LinearInterpolator
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.*
import java.util.concurrent.Executors
import kotlin.concurrent.thread
import kotlin.math.sin

class MainActivity : Activity() {
    private class App(
        val label: String, val pkg: String, val art: Drawable, val isBanner: Boolean,
        val intent: Intent, val tile: IntArray? = null, val builtin: Boolean = false
    )

    private class Prepared(val slide: HeroView.Slide, val logo: Bitmap?, val thumb: Drawable? = null)

    /** First-run Favourites, if installed (the user's own arrangement replaces this at once). */
    private val favorites = listOf(
        "com.netflix.ninja", "com.google.android.youtube.tv", "org.smarttube.stable", "com.amazon.amazonvideo.livingroom",
        "com.disney.disneyplus", "com.apple.atve.androidtv.appletv", "com.stremio.one", "org.videolan.vlc"
    )
    /** Apps presented under another identity: SmartTube is this TV's YouTube, so it wears YouTube's banner and name. */
    private val aliases = mapOf("org.smarttube.stable" to "YouTube")
    private val hidden = setOf(
        "com.mediatek.tv.service", "com.mstar.android.tv.disclaimercustomization",
        "com.mediatek.wwtv.webview", "com.xiaomi.mitv.tvmanager", "com.android.deskclock"
    )

    private val cols = 5
    private val d by lazy { resources.displayMetrics.density }
    private fun dp(v: Int) = (v * d).toInt()
    private fun dpf(v: Float) = v * d
    private val cardW by lazy { dp(148) }
    private val cardH by lazy { dp(89) }          // tvOS icons are 5:3
    private val gap by lazy { (resources.displayMetrics.widthPixels - dp(128) - cols * cardW) / (cols - 1) }

    private val main = Handler(Looper.getMainLooper())
    // decode/blur/network work runs at background priority so it never competes with the render thread
    private val bgThreads = java.util.concurrent.ThreadFactory { r ->
        Thread { android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND); r.run() }
    }
    private val io = Executors.newSingleThreadExecutor(bgThreads)
    private val net = Executors.newFixedThreadPool(2, bgThreads)

    private lateinit var root: FrameLayout
    private lateinit var hero: HeroView
    private lateinit var pager: Pager
    private lateinit var page: LinearLayout
    private lateinit var info: LinearLayout
    private lateinit var eyebrow: TextView
    private lateinit var logo: ImageView
    private lateinit var titleText: TextView
    private lateinit var meta: TextView
    private lateinit var desc: TextView
    private lateinit var openBtn: GlassView
    private lateinit var openLabel: TextView
    private lateinit var openIcon: ImageView
    private lateinit var dots: Dots
    private lateinit var clock: GlassView
    private lateinit var weatherPill: GlassView
    private lateinit var weatherIconView: ImageView
    private lateinit var weatherText: TextView
    private lateinit var inputsBtn: GlassView
    private lateinit var wifiBtn: GlassView
    private lateinit var netPanel: GlassView
    private var netLevel = 3
    private var netWifi = true
    private val netRun = Runnable { refreshNetIcon() }
    private lateinit var settingsBtn: GlassView
    private lateinit var lockBtn: GlassView
    private lateinit var shareBtn: GlassView
    private lateinit var ccBtn: GlassView
    private var ccPanel: GlassView? = null
    private var listPanel: GlassView? = null
    private var pinRestore: View? = null
    private lateinit var sharePanel: GlassView
    private lateinit var shareQr: ImageView
    private lateinit var shareHint: TextView
    private lateinit var shareFirst: View
    private lateinit var inputsPanel: GlassView
    private lateinit var inputsList: LinearLayout
    private val weatherRun = Runnable { loadWeather() }
    private lateinit var searchPill: GlassView
    private lateinit var bottomSpacer: View
    private lateinit var shelfArea: View
    private lateinit var mini: MiniShelf
    private lateinit var miniEyebrow: TextView
    private lateinit var miniLogo: ImageView
    private lateinit var miniTitle: TextView
    private lateinit var miniMeta: TextView
    private lateinit var miniDesc: TextView
    private lateinit var miniHint: TextView
    private lateinit var miniThumb: ImageView
    private lateinit var miniDots: Dots
    private val MINI_H = 252
    /** tvOS grid: below the top row, Top Shelf scrolls away entirely and rows start right under the navbar. */
    private val GRID_TOP = 116
    private val rows = ArrayList<LinearLayout>()
    private var apps: List<App> = emptyList()

    private val appShelves = HashMap<String, Pair<ShelfItem, Prepared>>()
    private val shelves = HashMap<String, List<ShelfItem>>()
    private var source = Shelf.STREMIO
    private var pendingSource: String? = null
    private val switchRun = Runnable { pendingSource?.let { switchSource(it) } }
    private var items: List<ShelfItem> = emptyList()
    private var index = 0
    private var busy = false
    private var nextPrepared: Triple<String, Int, Prepared>? = null
    private var dirty = true
    private var started = false

    // focus / tilt state
    private var tiltFrame: View? = null
    private var tiltGlare: Glare? = null
    private var tiltSince = 0L
    private var focusedFrame: View? = null
    private var lastInput = SystemClock.uptimeMillis()
    private fun idle() = SystemClock.uptimeMillis() - lastInput > 60_000

    /**
     * One ~25fps ticker drives everything continuous (Ken Burns drift + focused-card wiggle), so the
     * GPU redraws at most 25 times a second, and not at all once things settle.
     */
    private var tickerOn = false
    /** Restart the ticker after it parked itself (input, focus change, new slide). Cheap to call often. */
    private fun kickTicker() { if (started && !tickerOn) { tickerOn = true; main.post(ticker) } }
    private var tiltRest = false
    private val ticker = object : Runnable {
        override fun run() {
            if (!started) { tickerOn = false; return }
            val now = SystemClock.uptimeMillis()
            var next = Long.MAX_VALUE
            if (hero.needsFrame() && !idle()) { hero.frame(); next = 40 }
            moving?.let { next = 40 }
            moving?.let { m ->
                // the card being moved wiggles, like iOS edit mode
                (m as ViewGroup).getChildAt(0).rotation = 1.8f * sin(now / 1000f * 2f * Math.PI.toFloat() * 3.2f)
            }
            tiltFrame?.let { f ->
                // tvOS-style: the focused card breathes and catches the light for a few seconds, rests, then does it
                // again (gentler) for as long as you're around; after a minute without input it settles for good.
                val age = now - tiltSince
                val cyc = age % 11_000L
                val settle = {
                    f.rotationX = 0f; f.rotationY = 0f
                    tiltGlare?.let { it.gx = 0.5f; it.gy = 0.05f; it.invalidate() }
                }
                if (idle()) { settle(); tiltFrame = null; tiltRest = false; return@let }
                if (cyc > 4500) {
                    if (!tiltRest) { settle(); tiltRest = true }
                    next = minOf(next, 11_000L - cyc)   // sleep through the rest phase: zero redraws
                    return@let
                }
                tiltRest = false
                next = 40
                val k = minOf((cyc / 700f).coerceIn(0f, 1f), ((4500 - cyc) / 1000f).coerceIn(0f, 1f)) * (if (age < 11_000L) 1f else 0.7f)
                val ph = (now % 7000L) / 7000f * (2 * Math.PI).toFloat()
                val rx = 1.4f * sin(ph) * k
                val ry = 2.0f * sin(2 * ph + 1f) * k
                f.rotationX = rx; f.rotationY = ry
                // parallax: the artwork drifts against the card's tilt, so the icon reads as layered
                tiltGlare?.let { g -> g.gx = 0.5f - ry / 7f; g.gy = 0.05f + rx / 9f; g.invalidate() }
            }
            if (next == Long.MAX_VALUE) { tickerOn = false; return }   // nothing moving: park until kicked
            main.postDelayed(this, next)
        }
    }

    private val autoAdvance = Runnable { onAutoAdvance() }
    private val pkgReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context?, i: Intent?) { dirty = true; if (started) reloadApps() }
    }

    // ================= lifecycle =================
    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        enableOwnAnimations()
        Fonts.init(this)
        Fx.init(this)
        ShareHub.start(this)
        window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY

        window.setBackgroundDrawable(null)   // the hero paints every pixel; skip two full-screen fills per frame
        root = FrameLayout(this).apply { clipChildren = false; clipToPadding = false }
        hero = HeroView(this)
        root.addView(hero, FrameLayout.LayoutParams(-1, -1))

        pager = Pager(this)
        page = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; clipChildren = false; clipToPadding = false }
        pager.addView(page, FrameLayout.LayoutParams(-1, -2))
        root.addView(pager, FrameLayout.LayoutParams(-1, -1))

        shelfArea = buildShelfArea()
        page.addView(shelfArea, LinearLayout.LayoutParams(-1, dp(362)))
        bottomSpacer = View(this)
        page.addView(bottomSpacer, LinearLayout.LayoutParams(-1, dp(300)))

        buildMiniShelf()
        buildTopBar()
        buildSpotlight()
        buildInputsPanel()
        buildNetPanel()
        buildSharePanel()
        buildWeatherPanel()
        setContentView(root)

        pager.onScroll = { y -> onPageScroll(y) }
        registerReceiver(pkgReceiver, IntentFilter().apply {
            addAction(Intent.ACTION_PACKAGE_ADDED); addAction(Intent.ACTION_PACKAGE_REMOVED); addDataScheme("package")
        })
        loadShelf()
    }

    override fun onStart() {
        super.onStart()
        enableOwnAnimations()   // the system re-pushes its scale when settings change
        Fx.init(this)
        root.post { zoomReturn() }
        KeyServiceGuard.ensure(this)   // a force-stop disables the Home-button service; switch it back on
        started = true
        main.removeCallbacks(ticker); tickerOn = true; main.post(ticker)
        main.removeCallbacks(weatherRun); main.post(weatherRun)
        main.removeCallbacks(netRun); main.post(netRun)
        if (dirty) reloadApps()
        scheduleAuto()
        main.removeCallbacks(refreshRun); main.post(refreshRun)
        checkAdbExpiry()
        // phone keyboard: typing on the phone opens search here and fills it
        TypeBridge.launcherSink = { text, enter ->
            if (sOverlay.visibility != View.VISIBLE) openSpotlight()
            imm.hideSoftInputFromWindow(sField.windowToken, 0)   // typing happens on the phone: keep results visible
            sField.setText(text); sField.setSelection(text.length)
            if (enter) { main.removeCallbacks(searchRun); focusResults = true; runSearch() }
            true
        }
        if (rows.isNotEmpty()) placeRecents()   // back from an app: it's now the most recent
        main.removeCallbacks(tidyRun); main.postDelayed(tidyRun, 4000)
    }

    /**
     * Back on home: close utility apps that linger in RAM once you've left them (TV Settings alone keeps ~60 MB).
     * Streaming apps are left alone so they resume instantly; only background processes can be killed anyway.
     */
    private val tidyApps = listOf("com.android.tv.settings", "com.google.android.permissioncontroller", "com.android.documentsui",
        "com.google.android.packageinstaller", "dev.dworks.apps.anexplorer", "com.android.providers.downloads.ui")
    private val tidyRun = Runnable {
        if (!started) return@Runnable
        io.go {
            val am = getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager
            for (p in tidyApps) try { am.killBackgroundProcesses(p) } catch (e: Exception) {}
        }
    }

    override fun onStop() {
        main.removeCallbacks(tidyRun)
        started = false
        // leaving home (an app or the Drop sheet took over): never come back to a stale open panel
        if (::sharePanel.isInitialized && sharePanel.visibility == View.VISIBLE) closeShare()
        if (netPanel.visibility == View.VISIBLE) closeNet()
        if (weatherPanel.visibility == View.VISIBLE) closeWeather()
        if (inputsPanel.visibility == View.VISIBLE) closeInputs()
        if (pinPad != null) closePinPad()
        if (ccPanel != null) closeCC()
        if (listPanel != null) closeList()
        if (cardMenu != null) closeCardMenu(restore = false)
        if (moving != null) endMove(save = true)
        TypeBridge.launcherSink = null
        stopVoice()
        main.removeCallbacks(ticker); tickerOn = false
        closeSwitcher(animate = false)
        main.removeCallbacks(refreshRun)
        main.removeCallbacks(weatherRun)
        main.removeCallbacks(netRun)
        main.removeCallbacks(autoAdvance)
        super.onStop()
    }

    override fun onDestroy() { main.removeCallbacksAndMessages(null); unregisterReceiver(pkgReceiver); io.shutdownNow(); net.shutdownNow(); super.onDestroy() }

    override fun onNewIntent(i: Intent?) {
        super.onNewIntent(i)
        // test hook (adb can't hold a key): am start -n com.arshanwar.tv_launcher/com.arshanwar.tv_launcher.MainActivity --ez hold true
        if (i?.getBooleanExtra("hold", false) == true) { currentFocus?.performLongClick(); return }
        // test hook: preview the PIN pad without touching ADB or the saved PIN
        if (i?.getBooleanExtra("pinpad", false) == true) { showPinPad("Enter PIN to turn on ADB") { closePinPad() }; return }
        if (i?.getBooleanExtra("switcher", false) == true) { openSwitcher(); return }
        // test hook: am start -n .../.MainActivity --es routine_pkg com.netflix.ninja  → one-off routine at the next minute
        i?.getStringExtra("routine_pkg")?.let { p ->
            val t = java.util.Calendar.getInstance().apply { add(java.util.Calendar.SECOND, 40); add(java.util.Calendar.MINUTE, 1) }
            Routines.add(this, Routines.Routine(Routines.newId(), t.get(java.util.Calendar.HOUR_OF_DAY), t.get(java.util.Calendar.MINUTE), 0, p, i.getStringExtra("routine_url").orEmpty(), i.getStringExtra("routine_title").orEmpty()))
            Kit.toast(this, "Test routine at ${Routines.time(t.get(java.util.Calendar.HOUR_OF_DAY), t.get(java.util.Calendar.MINUTE))}"); return
        }
        if (switcher != null) { closeSwitcher(); return }
        val atHome = homeFocused && sOverlay.visibility != View.VISIBLE && pager.scrollY == 0 && ccPanel == null && listPanel == null && cardMenu == null
        if (sOverlay.visibility == View.VISIBLE) closeSpotlight()
        if (ccPanel != null) { closeCC(); return }
        if (atHome && rows.firstOrNull()?.findFocus() != null) { openCC(); return }
        goHome()
    }

    /** True while home is on screen and focused (Home pressed again then opens Control Center). */
    private var homeFocused = false
    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        homeFocused = hasFocus
        if (hasFocus && (currentFocus == null || currentFocus === window.decorView)) rows.firstOrNull()?.getChildAt(0)?.requestFocus()
    }


    override fun onBackPressed() {
        when {
            switcher != null -> closeSwitcher()
            cardMenu != null -> closeCardMenu()
            inputsPanel.visibility == View.VISIBLE -> closeInputs()
            pinPad != null -> closePinPad()
            folderSheet != null -> closeFolder()
            ccPanel != null -> closeCC()
            listPanel != null -> closeList()
            netPanel.visibility == View.VISIBLE -> closeNet()
            sharePanel.visibility == View.VISIBLE -> closeShare()
            weatherPanel.visibility == View.VISIBLE -> closeWeather()
            sOverlay.visibility == View.VISIBLE -> closeSpotlight()
            else -> goHome()
        }
    }

    private val dirs = mapOf(KeyEvent.KEYCODE_DPAD_LEFT to View.FOCUS_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT to View.FOCUS_RIGHT,
        KeyEvent.KEYCODE_DPAD_UP to View.FOCUS_UP, KeyEvent.KEYCODE_DPAD_DOWN to View.FOCUS_DOWN)

    /** End of the line: the focused card nudges toward the press and springs back, with a soft bump. */
    private fun edgeBounce(dir: Int) {
        val v = currentFocus ?: return
        val target = if (switcher != null) v else (v as? ViewGroup)?.getChildAt(0)?.takeIf { v.tag is App } ?: v
        val dx = when (dir) { View.FOCUS_LEFT -> -1f; View.FOCUS_RIGHT -> 1f; else -> 0f } * dpf(10f)
        val dy = when (dir) { View.FOCUS_UP -> -1f; View.FOCUS_DOWN -> 1f; else -> 0f } * dpf(8f)
        target.animate().translationXBy(dx).translationYBy(dy).setDuration(90).setInterpolator(appleEase).withEndAction {
            target.animate().translationX(0f).translationY(0f).setDuration(380).setInterpolator(android.view.animation.OvershootInterpolator(3f)).start()
        }.start()
        Fx.edge()
    }

    private var lastRepeatMove = 0L

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val dir = dirs[event.keyCode]
        kickTicker()
        // holding a direction: start at ~5 moves/s and speed up to ~11/s, instead of the raw 20/s key repeat that
        // outruns the focus animations and makes scrolling judder
        if (dir != null && event.action == KeyEvent.ACTION_DOWN && moving == null) {
            val now = SystemClock.uptimeMillis()
            if (event.repeatCount > 0) {
                if (now - lastRepeatMove < (210 - event.repeatCount * 9).coerceAtLeast(90)) return true
            }
            lastRepeatMove = now
        }
        if (switcher != null && event.keyCode == KeyEvent.KEYCODE_DPAD_UP) {
            if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) closeRecentApp(currentFocus)
            return true
        }
        if (dir != null && event.action == KeyEvent.ACTION_DOWN && moving == null && cardMenu == null && pinPad == null) {
            val f = currentFocus
            if (f != null && f.tag != "spinner" && f.focusSearch(dir) == null && !(dir == View.FOCUS_UP && sOverlay.visibility == View.VISIBLE && kbCollapsed)) edgeBounce(dir)
        }
        if (event.keyCode == KeyEvent.KEYCODE_DPAD_UP && event.action == KeyEvent.ACTION_DOWN && sOverlay.visibility == View.VISIBLE
            && kbCollapsed && inResults(currentFocus)) {
            // in the first results row, Up always goes back to the keyboard
            if (rowOf(currentFocus) === rowOf(firstResult())) { setKeyboardCollapsed(false); resultsScroll.smoothScrollTo(0, 0); firstKey?.requestFocus(); return true }
        }
        if (event.keyCode == KeyEvent.KEYCODE_MENU && moving == null) {
            if (event.action == KeyEvent.ACTION_UP) currentFocus?.performLongClick()
            return true
        }
        val wasIdle = idle()
        lastInput = SystemClock.uptimeMillis()
        if (wasIdle) hero.frame()
        if (moving != null) {
            val k = event.keyCode
            val handled = k in setOf(KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN,
                KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_BACK)
            if (handled) {
                if (event.action == KeyEvent.ACTION_DOWN) when (k) {
                    KeyEvent.KEYCODE_DPAD_LEFT -> moveBy(-1)
                    KeyEvent.KEYCODE_DPAD_RIGHT -> moveBy(1)
                    KeyEvent.KEYCODE_DPAD_UP -> moveBy(-cols)
                    KeyEvent.KEYCODE_DPAD_DOWN -> moveBy(cols)
                    else -> if (event.repeatCount == 0) endMove(save = true)
                }
                return true
            }
        }
        return super.dispatchKeyEvent(event)
    }

    override fun onKeyDown(keyCode: Int, e: KeyEvent?): Boolean {
        if (keyCode == KeyEvent.KEYCODE_SEARCH) { openSpotlight(); return true }
        return super.onKeyDown(keyCode, e)
    }

    private fun goHome() {
        pager.glide(0)
        rows.firstOrNull()?.getChildAt(0)?.requestFocus()
    }

    // ================= top shelf =================
    private fun buildShelfArea(): View {
        val area = FrameLayout(this).apply { clipChildren = false; clipToPadding = false }
        info = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; clipChildren = false; alpha = 0f; visibility = View.INVISIBLE }
        eyebrow = TextView(this).apply {
            typeface = Fonts.semibold; textSize = 11f; letterSpacing = 0.14f; setTextColor(0xA6FFFFFF.toInt())
            text = "TRENDING ON STREMIO"; shadow()
        }
        logo = ImageView(this).apply {
            adjustViewBounds = true; maxWidth = dp(300); maxHeight = dp(70); scaleType = ImageView.ScaleType.FIT_START
        }
        titleText = TextView(this).apply {
            typeface = Fonts.displayBold; textSize = 42f; letterSpacing = -0.02f; setTextColor(Color.WHITE); maxLines = 2; shadow()
        }
        meta = TextView(this).apply { typeface = Fonts.medium; textSize = 14f; setTextColor(0xD9FFFFFF.toInt()); shadow() }
        desc = TextView(this).apply {
            typeface = Fonts.regular; textSize = 14f; setTextColor(0xB3FFFFFF.toInt()); maxLines = 2
            ellipsize = TextUtils.TruncateAt.END; setLineSpacing(0f, 1.15f); shadow()
        }

        openBtn = GlassView(this, hero, dpf(18f)).apply { isFocusable = true; isClickable = true }
        val br = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(dp(18), 0, dp(20), 0) }
        openIcon = ImageView(this).apply { setImageDrawable(playIcon()) }
        openLabel = TextView(this).apply { typeface = Fonts.semibold; textSize = 15f; setTextColor(Color.WHITE); text = "Open in Stremio"; setPadding(dp(10), 0, 0, 0) }
        br.addView(openIcon, LinearLayout.LayoutParams(dp(13), dp(13)))
        br.addView(openLabel)
        openBtn.addView(br, FrameLayout.LayoutParams(-2, -1))
        openBtn.setOnFocusChangeListener { _, f -> onOpenFocus(f) }
        openBtn.setOnClickListener { press(openBtn) { items.getOrNull(index)?.let { openItem(it) } } }
        openBtn.setOnKeyListener { _, k, e ->
            if (e.action != KeyEvent.ACTION_DOWN) return@setOnKeyListener false
            when (k) {
                KeyEvent.KEYCODE_DPAD_RIGHT -> { advance(1); true }
                KeyEvent.KEYCODE_DPAD_LEFT -> { advance(-1); true }
                else -> false
            }
        }

        info.addView(eyebrow)
        info.addView(logo, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(8) })
        info.addView(titleText, LinearLayout.LayoutParams(dp(560), -2).apply { topMargin = dp(6) })
        info.addView(meta, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(10) })
        info.addView(desc, LinearLayout.LayoutParams(dp(430), -2).apply { topMargin = dp(4) })
        info.addView(openBtn, LinearLayout.LayoutParams(-2, dp(36)).apply { topMargin = dp(14) })
        area.addView(info, FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM or Gravity.START).apply { leftMargin = dp(64); bottomMargin = dp(26) })

        dots = Dots(this)
        area.addView(dots, FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM or Gravity.END).apply { rightMargin = dp(64); bottomMargin = dp(40) })
        return area
    }

    private fun TextView.shadow() = setShadowLayer(14f, 0f, 1f, 0x99000000.toInt())

    private fun buildTopBar() {
        // left: search (icon only) + compact weather
        searchPill = iconButton({ magnifier(Color.WHITE) }, { magnifier(0xFF111111.toInt()) }) { openSpotlight() }

        weatherPill = GlassView(this, hero, dpf(19f)).apply { visibility = View.GONE; isFocusable = true; isClickable = true }
        val wr = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(dp(12), 0, dp(15), 0) }
        weatherIconView = ImageView(this)
        weatherText = TextView(this).apply { typeface = Fonts.semibold; textSize = 16f; setTextColor(Color.WHITE); setPadding(dp(7), 0, 0, 0) }
        wr.addView(weatherIconView, LinearLayout.LayoutParams(dp(19), dp(19)))
        wr.addView(weatherText)
        weatherPill.addView(wr, FrameLayout.LayoutParams(-2, -1))
        weatherPill.setOnFocusChangeListener { _, f ->
            // weather glyphs are white/colour, so focus brightens the glass instead of going solid white
            weatherPill.animateSolid(if (f) 0.32f else 0f)
            weatherPill.animate().scaleX(if (f) 1.08f else 1f).scaleY(if (f) 1.08f else 1f).translationZ(if (f) dpf(12f) else 0f)
                .setInterpolator(appleOut).setDuration(380).start()
            if (f) { hero.spotlight(0f, 0f, false); pager.glide(0); hero.setCollapsed(false) }
        }
        weatherPill.setOnClickListener { press(weatherPill) { openWeather() } }

        val left = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; clipChildren = false }
        left.addView(searchPill, LinearLayout.LayoutParams(dp(38), dp(38)).apply { rightMargin = dp(10) })
        left.addView(weatherPill, LinearLayout.LayoutParams(-2, dp(38)))
        root.addView(left, FrameLayout.LayoutParams(-2, dp(38), Gravity.TOP or Gravity.START).apply { topMargin = dp(26); leftMargin = dp(48) })

        // right: network, inputs, settings, clock
        clock = GlassView(this, hero, dpf(19f))
        val tc = TextClock(this).apply {
            format12Hour = "h:mm"; format24Hour = "H:mm"; typeface = Fonts.semibold; textSize = 18f; setTextColor(Color.WHITE)
            gravity = Gravity.CENTER; setPadding(dp(18), 0, dp(18), 0)
        }
        clock.addView(tc, FrameLayout.LayoutParams(-2, -1))
        wifiBtn = iconButton({ wifiIcon(netLevel, netWifi, Color.WHITE) }, { wifiIcon(netLevel, netWifi, 0xFF111111.toInt()) }) { openNet() }
        inputsBtn = iconButton({ inputIcon(Color.WHITE) }, { inputIcon(0xFF111111.toInt()) }) { openInputs() }
        shareBtn = iconButton({ shareIcon(Color.WHITE) }, { shareIcon(0xFF111111.toInt()) }) { openShare() }
        lockBtn = iconButton({ lockIcon(Color.WHITE) }, { lockIcon(0xFF111111.toInt()) }) {
            startActivity(Intent(this, LockActivity::class.java))
        }
        settingsBtn = iconButton({ gearIcon(Color.WHITE) }, { gearIcon(0xFF111111.toInt()) }) {
            startActivity(Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
        val cluster = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; clipChildren = false }
        // Wi-Fi, Inputs, Share, Lock and Settings now live in Control Center; one button keeps the bar calm
        ccBtn = iconButton({ ccIcon(Color.WHITE) }, { ccIcon(0xFF111111.toInt()) }) { openCC() }
        cluster.addView(ccBtn, LinearLayout.LayoutParams(dp(38), dp(38)).apply { rightMargin = dp(10) })
        cluster.addView(clock, LinearLayout.LayoutParams(-2, dp(38)))
        root.addView(cluster, FrameLayout.LayoutParams(-2, dp(38), Gravity.TOP or Gravity.END).apply { topMargin = dp(26); rightMargin = dp(48) })

        // centre: credit ribbon, vertically aligned with the 38dp controls
        val ribbon = GlassView(this, hero, dpf(14f))
        val credit = android.text.SpannableString("Made with \u2665 by Arsh Anwar").apply {
            setSpan(android.text.style.ForegroundColorSpan(0xFFFF375F.toInt()), 10, 11, 0)
        }
        ribbon.addView(TextView(this).apply {
            text = credit; typeface = Fonts.medium; textSize = 12f; setTextColor(0xD9FFFFFF.toInt()); letterSpacing = 0.02f
            gravity = Gravity.CENTER; setPadding(dp(14), 0, dp(14), 0)
        }, FrameLayout.LayoutParams(-2, -1))
        root.addView(ribbon, FrameLayout.LayoutParams(-2, dp(28), Gravity.TOP or Gravity.CENTER_HORIZONTAL).apply { topMargin = dp(31) })
    }

    /** tvOS button focus: glass turns solid white, content turns black, gentle lift. */
    private fun buttonFocus(g: GlassView, f: Boolean, label: TextView, icon: ImageView, iconOff: Drawable, iconOn: Drawable) {
        g.animateSolid(if (f) 1f else 0f)
        label.setTextColor(if (f) 0xFF111111.toInt() else Color.WHITE)
        label.setShadowLayer(0f, 0f, 0f, 0)
        icon.setImageDrawable(if (f) iconOn else iconOff)
        g.animate().scaleX(if (f) 1.08f else 1f).scaleY(if (f) 1.08f else 1f).translationZ(if (f) dpf(12f) else 0f)
            .setInterpolator(appleOut).setDuration(380).start()
    }

    private fun onOpenFocus(f: Boolean) {
        buttonFocus(openBtn, f, openLabel, openIcon, playIcon(), playIcon(0xFF111111.toInt()))
        if (f) {
            hero.spotlight(0f, 0f, false)
            hero.setCollapsed(false)
            pager.glide(0)
            scheduleAuto()
        }
    }

    private fun loadShelf() {
        io.go {
            val list = Shelf.load(this, Shelf.STREMIO)
            main.post { shelves[Shelf.STREMIO] = list }
            if (list.isNotEmpty() && source == Shelf.STREMIO) firstSlide(list)?.let { (i, p) -> main.post { if (source == Shelf.STREMIO) { items = list; show(i, p, 0) } } }
            Shelf.load(this, Shelf.YOUTUBE).let { l ->
                main.post { shelves[Shelf.YOUTUBE] = l }
                l.firstOrNull()?.let { Images.file(this, it.background, "bg_"); Images.file(this, it.logo, "lg_") }
            }
            Images.keepBackgrounds(this, list.map { it.background })
            Images.trim(this, 90)
        }
    }

    /**
     * Keeps the Top Shelves current. Catalog caches expire after 6h (Shelf.load refetches them);
     * fresh lists are swapped into the live shelves (the slide on screen finishes, the next one is new).
     * Runs whenever home comes to the front and hourly while it stays there.
     */
    private val refreshRun = Runnable { refreshShelves() }
    private fun refreshShelves() {
        if (shelves.isNotEmpty()) io.go {
            for (src in shelves.keys.toList()) {
                val fresh = Shelf.load(this, src)
                if (fresh.isEmpty()) continue
                main.post {
                    val old = shelves[src]
                    if (old == null || old.map { it.id } != fresh.map { it.id }) {
                        shelves[src] = fresh
                        if (src == source) { items = fresh; nextPrepared = null; index = index.coerceAtMost(fresh.size - 1) }
                    }
                }
            }
        }
        main.postDelayed(refreshRun, 3600_000L)
    }

    private fun firstSlide(list: List<ShelfItem>): Pair<Int, Prepared>? {
        for (i in 0 until minOf(4, list.size)) prepare(list[i])?.let { return i to it }
        return null
    }

    /** tvOS behaviour: the Top Shelf shows content from whichever top-row app is focused. */
    private fun sourceFor(pkg: String) = when (pkg) { "com.stremio.one" -> Shelf.STREMIO; "org.smarttube.stable" -> Shelf.YOUTUBE; else -> jwSrc[pkg] ?: "app:$pkg" }

    // ---- automatic Top Shelf for any streaming app: installed app → JustWatch service ----
    /** package → "jw:<technicalName>" for installed apps that match a JustWatch service (filled after the app list loads). */
    private var jwSrc: Map<String, String> = emptyMap()
    private val noJw = setOf("com.stremio.one", "org.smarttube.stable", "org.videolan.vlc", "com.android.vending")

    private fun matchStreamingApps(list: List<App>) {
        val pkgs = JustWatch.packages(this)
        if (pkgs.isEmpty()) return
        val m = HashMap<String, String>()
        for (a in list) {
            if (a.builtin || a.pkg in noJw) continue
            JustWatch.matchApp(a.label, pkgs)?.let { m[a.pkg] = "jw:$it" }
        }
        main.post { jwSrc = m }
    }

    private fun appForSource(src: String): App? = when {
        src.startsWith("jw:") -> jwSrc.entries.firstOrNull { it.value == src }?.key?.let { p -> apps.firstOrNull { it.pkg == p } }
        else -> null
    }

    private fun switchSource(src: String) {
        if (src == source) return
        if (src.startsWith("app:")) {
            val a = apps.firstOrNull { "app:" + it.pkg == src } ?: return
            source = src; nextPrepared = null
            val cached = appShelves[a.pkg]
            if (cached != null) { items = listOf(cached.first); show(0, cached.second, 0); return }
            // rendering + blurring the banner takes ~20ms: do it off the UI thread so scrolling stays smooth
            io.go {
                val built = appShelf(a, src)
                main.post { appShelves[a.pkg] = built; if (source == src) { items = listOf(built.first); show(0, built.second, 0) } }
            }
            return
        }
        val list = shelves[src]
        if (list == null && src.startsWith("jw:")) {
            // first visit to this app's shelf: fetch its popular titles, then come back (or fall back to its banner)
            io.go {
                val l = Shelf.load(this, src)
                main.post {
                    shelves[src] = l
                    if (pendingSource == src) switchSource(if (l.isNotEmpty()) src else "app:" + (appForSource(src)?.pkg ?: return@post))
                }
            }
            return
        }
        if (list.isNullOrEmpty()) { if (src.startsWith("jw:")) appForSource(src)?.let { switchSource("app:" + it.pkg) }; return }
        source = src; nextPrepared = null; busy = true
        io.go {
            val first = firstSlide(list)
            main.post { busy = false; if (source == src && first != null) { items = list; show(first.first, first.second, 0) } }
        }
    }

    /** Shelf for apps without a catalog: their own banner, blurred into an ambient backdrop. */
    private fun appShelf(a: App, src: String): Pair<ShelfItem, Prepared> {
        val bw = 320; val bh = 180
        val bmp = Bitmap.createBitmap(bw, bh, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val dr = a.art.constantState?.newDrawable()?.mutate() ?: a.art
        if (a.isBanner) {
            dr.setBounds(0, 0, bw, bh); dr.draw(c)
        } else {
            val t = a.tile ?: intArrayOf(0xFF4A4E5A.toInt(), 0xFF25272E.toInt())
            c.drawPaint(Paint().apply { shader = LinearGradient(0f, 0f, 0f, bh.toFloat(), t[0], t[1], Shader.TileMode.CLAMP) })
            val s = (bh * 0.6f).toInt(); dr.setBounds(bw / 2 - s / 2, bh / 2 - s / 2, bw / 2 + s / 2, bh / 2 + s / 2); dr.draw(c)
        }
        val sharp = Blur.box(bmp, 160, 4, 4)
        val soft = Blur.box(bmp, 64, 4, 4)
        val item = ShelfItem(src, a.pkg, "app", a.label, "", "", "", "", "", emptyList(), "")
        return item to Prepared(HeroView.Slide(sharp, soft, soft), null, BitmapDrawable(resources, bmp))
    }

    private fun openItem(it: ShelfItem) {
        if (it.type == "app") { apps.firstOrNull { a -> a.pkg == it.id }?.let { a -> launch(a) }; return }
        if (it.source == Shelf.YOUTUBE) {
            val v = Intent(Intent.ACTION_VIEW, Uri.parse("https://www.youtube.com/watch?v=${it.trailer}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            try { startActivity(Intent(v).setPackage("org.smarttube.stable")) } catch (e: Exception) { try { startActivity(v) } catch (e2: Exception) {} }
            return
        }
        if (it.source == Shelf.STREMIO) { openStremio(it); return }
        val app = appForSource(it.source)
        if (it.url.isNotBlank() && app != null) { openUrl(it.url, app.pkg, app); return }   // straight to the title in its app
        val pkg = app?.pkg ?: return
        val i = packageManager.getLeanbackLaunchIntentForPackage(pkg) ?: packageManager.getLaunchIntentForPackage(pkg)
        if (i != null) startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    /** Dominant colour of an app's artwork, lightened, for the focus glow. Tiny bitmap, cached per app. */
    private val artColors = HashMap<String, Int>()
    private fun artColor(pkg: String, dr: Drawable?): Int = artColors.getOrPut(pkg) {
        try {
            val b = Bitmap.createBitmap(12, 7, Bitmap.Config.ARGB_8888)
            dr?.constantState?.newDrawable()?.mutate()?.apply { setBounds(0, 0, 12, 7); draw(Canvas(b)) }
            var r = 0f; var g = 0f; var bl = 0f; var wsum = 0f
            val hsv = FloatArray(3)
            for (y in 0 until 7) for (x in 0 until 12) {
                val p = b.getPixel(x, y); if (Color.alpha(p) < 128) continue
                Color.colorToHSV(p, hsv)
                val w = 0.15f + hsv[1] * hsv[2]   // vivid pixels count most: the brand colour, not the black background
                r += Color.red(p) * w; g += Color.green(p) * w; bl += Color.blue(p) * w; wsum += w
            }
            if (wsum == 0f) 0xFFFFFF else {
                val c = Color.rgb((r / wsum).toInt(), (g / wsum).toInt(), (bl / wsum).toInt())
                Color.colorToHSV(c, hsv); hsv[1] = minOf(hsv[1], 0.55f); hsv[2] = 1f
                Color.HSVToColor(hsv) and 0xFFFFFF
            }
        } catch (e: Exception) { 0xFFFFFF }
    }

    private fun sourceName(src: String) = when (src) {
        Shelf.YOUTUBE -> "YOUTUBE"; Shelf.STREMIO -> "STREMIO"; else -> appForSource(src)?.label?.uppercase() ?: "POPULAR"
    }

    private fun prepare(it: ShelfItem): Prepared? {
        // trailer thumbnails get their own prefix so the lock screen (which shows "bg_" art) never picks them up;
        // not every video has a maxres thumbnail, hq720 (also 1280x720) is the fallback
        val bf = if (it.source == Shelf.YOUTUBE)
            Images.file(this, it.background, "yt_") ?: Images.file(this, it.background.replace("maxresdefault", "hq720"), "yt_") ?: return null
        else Images.file(this, it.background, "bg_") ?: return null
        val bmp = Images.decode(bf, 1920) ?: return null
        val lg = Images.file(this, it.logo, "lg_")?.let { f -> Images.decode(f, 700) }
        val slide = HeroView.Slide(bmp, Blur.box(bmp, 160, 5, 5), Blur.box(bmp, 320, 18, 0))
        // start the GPU texture upload now, off the frame where the slide first appears (was an 18ms hitch)
        slide.sharp.prepareToDraw(); slide.soft.prepareToDraw(); slide.streak.prepareToDraw()
        return Prepared(slide, lg, BitmapDrawable(resources, bmp))
    }

    private fun show(i: Int, p: Prepared, dir: Int) {
        index = i
        hero.setSlide(p.slide, dir)
        kickTicker()
        val first = info.visibility != View.VISIBLE
        info.visibility = View.VISIBLE
        val shift = dpf(28f) * (if (dir == 0) 0 else dir)
        val bind = {
            val it = items[i]
            val isApp = it.type == "app"
            eyebrow.text = when {
                isApp -> "APP"
                it.source == Shelf.YOUTUBE -> "TRAILER  ·  YOUTUBE"
                else -> (if (it.type == "series") "TOP SERIES  ·  " else "TOP MOVIE  ·  ") + sourceName(it.source)
            }
            openLabel.text = when {
                isApp -> "Open ${it.name}"
                it.source == Shelf.YOUTUBE -> "Watch trailer"
                it.source == Shelf.STREMIO -> "Open in Stremio"
                else -> "Open " + (appForSource(it.source)?.label ?: sourceName(it.source))
            }
            desc.visibility = if (it.description.isBlank()) View.GONE else View.VISIBLE
            if (p.logo != null) {
                logo.setImageBitmap(p.logo); logo.visibility = View.VISIBLE; titleText.visibility = View.GONE
            } else {
                logo.visibility = View.GONE; titleText.visibility = View.VISIBLE; titleText.text = it.name
            }
            meta.text = if (isApp) "" else it.meta
            meta.visibility = if (isApp) View.GONE else View.VISIBLE
            miniMeta.visibility = meta.visibility
            desc.text = it.description
            miniEyebrow.text = eyebrow.text
            if (p.logo != null) {
                miniLogo.setImageBitmap(p.logo); miniLogo.visibility = View.VISIBLE; miniTitle.visibility = View.GONE
            } else {
                miniLogo.visibility = View.GONE; miniTitle.visibility = View.VISIBLE; miniTitle.text = it.name
            }
            miniMeta.text = meta.text
            miniDesc.text = it.description
            miniDesc.visibility = desc.visibility
            if (miniThumb.drawable != null && mini.visibility == View.VISIBLE) {
                miniThumb.alpha = 0f; miniThumb.animate().alpha(1f).setDuration(450).setInterpolator(appleEase).start()
            }
            miniThumb.setImageDrawable(p.thumb)
            miniHint.text = "Open " + when {
                isApp -> it.name
                it.source == Shelf.STREMIO -> "Stremio"
                it.source == Shelf.YOUTUBE -> "YouTube"
                else -> appForSource(it.source)?.label ?: sourceName(it.source)
            }
        }
        if (first) {
            bind(); info.translationY = dpf(16f)
            info.animate().alpha(1f).translationY(0f).setDuration(700).setInterpolator(appleOut).start()
        } else {
            info.animate().alpha(0f).translationX(-shift).setDuration(170).setInterpolator(appleEase).withEndAction {
                bind(); info.translationX = shift
                info.animate().alpha(1f).translationX(0f).setDuration(520).setInterpolator(appleOut).start()
            }.start()
        }
        dots.set(items.size, i)
        miniDots.set(items.size, i); miniDots.visibility = if (items.size > 1) View.VISIBLE else View.GONE
        dots.visibility = if (items.size > 1) View.VISIBLE else View.INVISIBLE
        scheduleAuto()
        // pre-build the next slide so a right press / auto-advance is instant
        val list = items; val src = source
        if (list.size < 2) return
        val n = (i + 1) % list.size
        nextPrepared = null
        // wait until the crossfade is over before decoding the next slide (decode + blur was stealing frames)
        main.postDelayed({
            if (index != i || source != src) return@postDelayed
            io.go { prepare(list[n])?.let { pr -> main.post { if (index == i && source == src) nextPrepared = Triple(src, n, pr) } } }
            io.go { val pv = (i - 1 + list.size) % list.size; Images.file(this, list[pv].background, "bg_"); Images.file(this, list[pv].logo, "lg_") }
        }, 1200)
    }

    private fun advance(step: Int, auto: Boolean = false) {
        if (items.size < 2 || busy) return
        val n = (index + step + items.size) % items.size
        val cached = nextPrepared
        if (cached != null && cached.first == source && cached.second == n) { show(n, cached.third, if (auto) 0 else step); return }
        busy = true
        val list = items; val src = source
        io.go {
            val p = prepare(list[n])
            main.post { busy = false; if (p != null && source == src) show(n, p, if (auto) 0 else step) }
        }
    }

    private fun scheduleAuto() { main.removeCallbacks(autoAdvance); main.postDelayed(autoAdvance, 9000) }

    private fun onAutoAdvance() {
        if (!started) return
        if (!openBtn.isFocused && sOverlay.visibility != View.VISIBLE && moving == null && !idle()) advance(1, auto = true)
        else scheduleAuto()
    }

    private fun openStremio(it: ShelfItem) {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(it.deepLink)).setPackage("com.stremio.one").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (e: Exception) {
            Kit.toast(this, "Stremio isn't installed")
        }
    }

    // ================= apps grid =================
    private fun loadApps(): List<App> {
        val pm = packageManager
        val seen = HashSet<String>()
        val userHidden = AppLock.hidden(this)
        val out = ArrayList<App>()
        for (cat in listOf(Intent.CATEGORY_LEANBACK_LAUNCHER, Intent.CATEGORY_LAUNCHER)) {
            for (ri in pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(cat), 0)) {
                val ai = ri.activityInfo; val p = ai.packageName
                if (p == packageName || p in hidden || p in userHidden || !seen.add(p)) continue
                val i = Intent(Intent.ACTION_MAIN).addCategory(cat).setClassName(p, ai.name).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                val alias = aliases[p]
                if (alias != null) {
                    out.add(App(alias, p, playTile(alias), true, i)); continue
                }
                val banner = sharpest(ai.applicationInfo, if (ai.banner != 0) ai.banner else ai.applicationInfo.banner)
                    ?: ai.loadBanner(pm) ?: ai.applicationInfo.loadBanner(pm)
                val icon = sharpest(ai.applicationInfo, ai.iconResource.takeIf { it != 0 } ?: ai.applicationInfo.icon) ?: ri.loadIcon(pm)
                out.add(App(ri.loadLabel(pm).toString(), p, banner ?: icon, banner != null, i))
            }
        }
        val fav = favorites.mapNotNull { f -> out.firstOrNull { it.pkg == f } }
        val defaults = fav + out.filter { it !in fav }.sortedWith(compareBy({ it.builtin }, { it.label.lowercase() }))
        // the user's own arrangement wins; apps installed since then are appended in default order
        val saved = prefs.getString("order", null)?.split('|')?.filter { it.isNotBlank() } ?: return defaults
        val byPkg = defaults.associateBy { it.pkg }
        return saved.mapNotNull { byPkg[it] } + defaults.filter { it.pkg !in saved }
    }

    /** The app's own artwork at the highest density it ships (xxxhdpi if present), so focus/zoom stay sharp. */
    private fun sharpest(ai: android.content.pm.ApplicationInfo, res: Int): Drawable? {
        if (res == 0) return null
        return try { packageManager.getResourcesForApplication(ai).getDrawableForDensity(res, android.util.DisplayMetrics.DENSITY_XXXHIGH, null) }
        catch (e: Exception) { null }
    }

    private fun reloadApps() {
        dirty = false
        io.go {
            val list = loadApps()
            main.post { apps = list; buildRows(list) }
            matchStreamingApps(list)   // network the first time (cached a week): which installed apps are streaming services
        }
    }

    /** Favourites: the top row, any length, scrolls sideways like tvOS. Stored as an ordered package list. */
    private fun favPkgs(): List<String> = prefs.getString("favs", null)?.split('|')?.filter { it.isNotBlank() }
        ?: apps.take(cols).map { it.pkg }.also { saveFavs(it) }   // first run: the current top row becomes your favourites
    private fun saveFavs(l: List<String>) = prefs.edit().putString("favs", l.joinToString("|")).apply()
    private lateinit var favScroll: HorizontalScrollView
    private val favCells = ArrayList<View>()

    private fun buildRows(list: List<App>) {
        val hadFocus = rows.isEmpty()
        if (moving != null) endMove(save = false)
        if (::favScroll.isInitialized) page.removeView(favScroll)
        rows.drop(1).forEach { page.removeView(it) }
        rows.clear(); cells.clear(); favCells.clear()
        val byPkg = list.associateBy { it.pkg }
        val favs = favPkgs().mapNotNull { byPkg[it] }.ifEmpty { list.take(1) }
        val folders = Folders.all(this)
        val inFolders = folders.flatMap { it.pkgs }.toSet()
        val grid = list.filter { it !in favs && it.pkg !in inFolders }.toMutableList()
        for (fo in folders.sortedBy { it.pos }) {
            val members = fo.pkgs.mapNotNull { byPkg[it] }
            if (members.isEmpty()) continue
            grid.add(fo.pos.coerceIn(0, grid.size), App(fo.name, "folder:" + fo.id, folderArt(members), true, Intent(), null, true))
        }

        // favourites: one horizontal row inside a sideways scroller
        val favRow = object : LinearLayout(this) { override fun hasOverlappingRendering() = false }.apply {
            orientation = LinearLayout.HORIZONTAL; clipChildren = false; clipToPadding = false; setPadding(dp(64), 0, dp(64), 0)
        }
        favs.forEachIndexed { i, a ->
            val c = card(a, 0); favCells.add(c)
            favRow.addView(c, LinearLayout.LayoutParams(cardW, -2).apply { if (i < favs.size - 1) rightMargin = gap })
        }
        // scroll ourselves in whole-tile steps (the stock auto-scroll leaves a half-cut tile at the edge)
        favScroll = object : HorizontalScrollView(this) { override fun computeScrollDeltaToGetChildRectOnScreen(r: Rect?) = 0 }.apply {
            isHorizontalScrollBarEnabled = false; overScrollMode = View.OVER_SCROLL_NEVER; clipChildren = false; clipToPadding = false
            isSmoothScrollingEnabled = true; addView(favRow)
        }
        page.addView(favScroll, page.indexOfChild(bottomSpacer), LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(26) })
        rows.add(favRow)

        grid.chunked(cols).forEach { chunk ->
            val row = object : LinearLayout(this) { override fun hasOverlappingRendering() = false }.apply {
                orientation = LinearLayout.HORIZONTAL; clipChildren = false; clipToPadding = false; setPadding(dp(64), 0, dp(64), 0)
            }
            chunk.forEachIndexed { i, a ->
                val c = card(a, rows.size); cells.add(c)
                row.addView(c, LinearLayout.LayoutParams(cardW, -2).apply { if (i < cols - 1) rightMargin = gap })
            }
            page.addView(row, page.indexOfChild(bottomSpacer), LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(26) })
            rows.add(row)
        }
        placeRecents()
        if (hadFocus || currentFocus == null) root.post { rows.firstOrNull()?.getChildAt(0)?.requestFocus() }
    }

    private fun toggleFavourite(a: App) {
        val f = favPkgs().toMutableList()
        if (a.pkg in f) {
            if (f.size == 1) { Kit.toast(this, "Keep at least one favourite"); return }
            f.remove(a.pkg); Kit.toast(this, "${a.label} removed from Favourites")
        } else { f.add(a.pkg); Kit.toast(this, "${a.label} added to Favourites") }
        saveFavs(f); reloadApps()
    }

    /** row = -1 for cards inside Spotlight results. */
    private fun card(a: App, row: Int): View {
        val r = cardH * 0.12f
        val cell = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; clipChildren = false; clipToPadding = false; isFocusable = true; isClickable = true
        }
        val frame = FrameLayout(this).apply {
            outlineProvider = object : ViewOutlineProvider() { override fun getOutline(v: View, o: Outline) { o.setRoundRect(0, 0, v.width, v.height, r) } }
            clipToOutline = true
            cameraDistance = 9000 * d
            setLayerType(View.LAYER_TYPE_HARDWARE, null)   // static cards composite as one cached quad
            background = if (a.isBanner) ColorDrawable(0xFF1B1C21.toInt())
            else GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, a.tile ?: intArrayOf(0xFF4A4E5A.toInt(), 0xFF25272E.toInt())).apply { cornerRadius = r }
        }
        val img = ImageView(this).apply { setImageDrawable(a.art) }
        if (a.isBanner) {
            img.scaleType = ImageView.ScaleType.CENTER_CROP
            frame.addView(img, FrameLayout.LayoutParams(-1, -1))
        } else {
            img.scaleType = ImageView.ScaleType.FIT_CENTER
            val s = (cardH * 0.54f).toInt()
            frame.addView(img, FrameLayout.LayoutParams(s, s, Gravity.CENTER))
        }
        val glare = Glare(this, r)
        frame.addView(glare, FrameLayout.LayoutParams(-1, -1))
        val label = TextView(this).apply {
            text = a.label; typeface = Fonts.medium; textSize = 14f; setTextColor(Color.WHITE); maxLines = 1
            ellipsize = TextUtils.TruncateAt.END; gravity = Gravity.CENTER; alpha = 0f; setPadding(0, dp(9), 0, 0)
            setShadowLayer(10f, 0f, 1f, 0xB3000000.toInt())
        }
        cell.addView(frame, LinearLayout.LayoutParams(cardW, cardH))
        cell.addView(label, LinearLayout.LayoutParams(cardW, -2))

        cell.tag = a
        cell.contentDescription = if (a.pkg.startsWith("folder:")) "${a.label} folder" else a.label   // read by AI assistants (TvMcp.read_screen)
        // the row is looked up at focus time: rearranging can move a card to another row
        cell.setOnFocusChangeListener { _, f -> if (!reflowing) cardFocus(frame, glare, label, if (row == -1) null else cell.parent as? View, f, a.pkg) }
        cell.setOnClickListener {
            when {
                a.pkg.startsWith("folder:") -> press(frame, focusedScale = 1.15f) { openFolder(a.pkg.removePrefix("folder:")) }
                row >= 0 -> zoomLaunch(frame, a)
                else -> press(frame, focusedScale = 1.15f) { launch(a) }
            }
        }
        cell.setOnLongClickListener {
            when {
                row >= 0 -> showCardMenu(a, cell, frame)   // grid: Move / App info
                !a.builtin -> openAppInfo(a)               // search results: straight to App info
            }
            true
        }
        return cell
    }

    /** [rowView] = the home-screen row holding the card (grid or Recently used); null for overlay cards (search). */
    private fun cardFocus(frame: View, glare: Glare, label: TextView, rowView: View?, f: Boolean, pkg: String? = null) {
        val art = (frame as ViewGroup).getChildAt(0)
        art.translationX = 0f; art.translationY = 0f
        // a hardware layer is cached at 1x and would be stretched (blurry) while the card is scaled up: draw live while focused
        frame.setLayerType(if (f) View.LAYER_TYPE_NONE else View.LAYER_TYPE_HARDWARE, null)
        if (f) Fx.tick()
        val s = if (f) 1.15f else 1f
        frame.animate().scaleX(s).scaleY(s).translationZ(if (f) dpf(26f) else 0f)
            .setInterpolator(appleOut).setDuration(if (f) 520 else 360).start()
        label.animate().alpha(if (f) 1f else 0f).translationY(if (f) dpf(8f) else 0f).setDuration(300).setInterpolator(appleOut).start()
        val ga = ValueAnimator.ofFloat(glare.level, if (f) 1f else 0f).setDuration(300)
        ga.addUpdateListener { glare.level = it.animatedValue as Float }; ga.start()
        if (f) {
            if (moving == null) { tiltFrame = frame; tiltGlare = glare; tiltSince = SystemClock.uptimeMillis(); tiltRest = false; kickTicker() }
            pkg?.let { hero.spotColor(artColor(it, (art as? ImageView)?.drawable)) }
            if (rowView != null) {
                val isTop = rowView === rows.firstOrNull()
                if (isTop && ::favScroll.isInitialized) {
                    // focused favourite becomes the rightmost visible tile once you're past the first screenful
                    val i = favCells.indexOfFirst { (it as ViewGroup).getChildAt(0) === frame }
                    if (i >= 0) favScroll.smoothScrollTo(((i - (cols - 1)).coerceAtLeast(0)) * (cardW + gap), 0)
                }
                focusedFrame = frame
                pager.glide(if (isTop) 0 else (pageTop(rowView) - dp(GRID_TOP)).coerceAtLeast(0))
                hero.setCollapsed(!isTop)
                center(frame)?.let { hero.spotlight(it.x, it.y, true) }
                if (pkg != null && moving == null) {
                    pendingSource = sourceFor(pkg)
                    main.removeCallbacks(switchRun); main.postDelayed(switchRun, 420)
                }
            }
        } else {
            if (tiltFrame === frame) { tiltFrame = null; tiltGlare = null }
            frame.animate().rotationX(0f).rotationY(0f).setDuration(300).start()
        }
    }

    /** tvOS "click": the focused item dips, then springs back as the action fires. */
    private fun press(v: View, focusedScale: Float = 1.08f, action: () -> Unit) {
        v.animate().scaleX(focusedScale * 0.94f).scaleY(focusedScale * 0.94f).setDuration(90).setInterpolator(appleEase).withEndAction {
            action()
            v.animate().scaleX(focusedScale).scaleY(focusedScale).setDuration(320).setInterpolator(appleOut).start()
        }.start()
    }

    // ---- tvOS launch zoom: the icon grows to fill the screen, the app opens; coming back zooms it home ----
    private var zoomOverlay: View? = null
    private var zoomFrom: Rect? = null

    private fun zoomLaunch(frame: View, a: App) {
        if (a.pkg in AppLock.locked(this) && !AppLock.isOpen(a.pkg) && AppLock.hasPin(this)) { launch(a); return }
        val loc = IntArray(2); frame.getLocationInWindow(loc)
        val w = (frame.width * frame.scaleX).toInt(); val h = (frame.height * frame.scaleY).toInt()
        val from = Rect(loc[0], loc[1], loc[0] + w, loc[1] + h)
        // panel colour: the tile's own edge colour (banners) or its gradient (icon tiles)
        val color = try {
            val b = Bitmap.createBitmap(16, 9, Bitmap.Config.ARGB_8888)
            a.art.constantState?.newDrawable()?.mutate()?.apply { setBounds(0, 0, 16, 9); draw(Canvas(b)) }
            b.getPixel(1, 4).takeIf { Color.alpha(it) > 200 } ?: (a.tile?.get(0) ?: 0xFF22252E.toInt())
        } catch (e: Exception) { 0xFF22252E.toInt() }
        val panel = View(this).apply {
            background = GradientDrawable().apply { setColor(color); cornerRadius = cardH * 0.12f }; elevation = dpf(40f)
        }
        // the logo itself: the real (high-res) artwork at tile size, never enlarged past a touch
        val logo = ImageView(this).apply {
            setImageDrawable(a.art.constantState?.newDrawable() ?: a.art)
            scaleType = if (a.isBanner) ImageView.ScaleType.CENTER_CROP else ImageView.ScaleType.FIT_CENTER; elevation = dpf(41f)
            outlineProvider = object : ViewOutlineProvider() { override fun getOutline(v: View, o: Outline) { o.setRoundRect(0, 0, v.width, v.height, cardH * 0.12f) } }
            clipToOutline = true
        }
        val ov = FrameLayout(this).apply { isClickable = true; clipChildren = false }
        ov.addView(panel, FrameLayout.LayoutParams(w, h).apply { leftMargin = from.left; topMargin = from.top })
        ov.addView(logo, FrameLayout.LayoutParams(w, h).apply { leftMargin = from.left; topMargin = from.top })
        root.addView(ov, FrameLayout.LayoutParams(-1, -1))
        zoomOverlay = ov; zoomFrom = from
        val sw = root.width.toFloat(); val sh = root.height.toFloat()
        val cx = sw / 2 - (from.left + w / 2f); val cy = sh / 2 - (from.top + h / 2f)
        // solid colour scales cleanly to any size; the logo only travels to the centre
        panel.animate().scaleX(sw / w * 1.02f).scaleY(sh / h * 1.02f).translationX(cx).translationY(cy).setDuration(420).setInterpolator(appleEase).start()
        logo.animate().translationX(cx).translationY(cy).scaleX(1.12f).scaleY(1.12f).setDuration(420).setInterpolator(appleEase).withEndAction {
            logo.animate().alpha(0f).setDuration(160).start()
            launch(a); overridePendingTransition(0, 0)
            main.postDelayed({ if (started) zoomReturn() }, 1500)   // app didn't open: never leave the cover up
        }.start()
    }

    /** Back from an app: the screen-filling icon shrinks back into its tile. */
    private fun zoomReturn() {
        val ov = zoomOverlay ?: return
        zoomOverlay = null
        val panel = (ov as ViewGroup).getChildAt(0); val logo = ov.getChildAt(1)
        logo.alpha = 1f
        panel.animate().scaleX(1f).scaleY(1f).translationX(0f).translationY(0f).setDuration(420).setInterpolator(appleOut).start()
        logo.animate().scaleX(1f).scaleY(1f).translationX(0f).translationY(0f).setDuration(420).setInterpolator(appleOut).start()
        ov.animate().alpha(0f).setStartDelay(360).setDuration(140).withEndAction { root.removeView(ov) }.start()
    }

    private fun launch(a: App) {
        if (a.pkg in AppLock.locked(this) && !AppLock.isOpen(a.pkg) && AppLock.hasPin(this)) {
            withPin("Enter PIN to open ${a.label}") { AppLock.grant(a.pkg); launch(a) }; return
        }
        try { startActivity(a.intent) } catch (e: Exception) { Kit.toast(this, "Can't open ${a.label}") }
    }

    /** Runs [action] after the launcher PIN is entered; creates the PIN first if there isn't one yet. */
    private fun withPin(title: String, action: () -> Unit) {
        if (!AppLock.hasPin(this)) {
            showPinPad("Create a 4-digit PIN") { first ->
                pinTitle?.text = "Enter the same PIN again"
                onPin = { second ->
                    if (first == second) { AppLock.setPin(this, first); closePinPad(); Kit.toast(this, "PIN saved"); action() }
                    else { pinShake(); pinTitle?.text = "PINs didn't match \u00b7 start again"; main.postDelayed({ closePinPad(); withPin(title, action) }, 700) }
                }
            }
            return
        }
        showPinPad(title) { p -> if (AppLock.check(this, p)) { closePinPad(); action() } else { pinShake(); pinTitle?.text = "Wrong PIN, try again" } }
    }

    private fun center(v: View): PointF? {
        if (!v.isAttachedToWindow) return null
        val a = IntArray(2); v.getLocationInWindow(a)
        val b = IntArray(2); hero.getLocationInWindow(b)
        return PointF(a[0] - b[0] + v.width / 2f, a[1] - b[1] + v.height / 2f)
    }

    private fun onPageScroll(y: Int) {
        val a = ((y - dpf(30f)) / dpf(150f)).coerceIn(0f, 1f)
        mini.visibility = View.GONE   // Apple TV style: no compact card, the grid gets the whole screen
        shelfArea.alpha = 1f - a      // Top Shelf text fades as it scrolls up and away
        // rows dissolve as they slide up under the navbar
        val band = dpf(GRID_TOP.toFloat())
        for (r in rows + listOfNotNull(recentBox)) {
            val cardBottom = pageTop(r) - y + cardH
            val vis = ((cardBottom - band + dpf(20f)) / dpf(70f)).coerceIn(0f, 1f)
            r.alpha = 1f - a * (1f - vis)
        }
        focusedFrame?.let { f -> center(f)?.let { hero.spotTrack(it.x, it.y) } }
    }

    private fun buildMiniShelf() {
        mini = MiniShelf(this, hero).apply { alpha = 0f; visibility = View.INVISIBLE }
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; clipChildren = false; gravity = Gravity.CENTER_VERTICAL }

        // full, uncropped artwork as a 16:9 card
        val r = dpf(12f)
        val thumbFrame = FrameLayout(this).apply {
            outlineProvider = object : ViewOutlineProvider() { override fun getOutline(v: View, o: Outline) { o.setRoundRect(0, 0, v.width, v.height, r) } }
            clipToOutline = true; elevation = dpf(14f); background = ColorDrawable(0xFF15161B.toInt())
        }
        miniThumb = ImageView(this).apply { scaleType = ImageView.ScaleType.CENTER_CROP }
        thumbFrame.addView(miniThumb, FrameLayout.LayoutParams(-1, -1))
        thumbFrame.addView(Glare(this, r), FrameLayout.LayoutParams(-1, -1))
        row.addView(thumbFrame, LinearLayout.LayoutParams(dp(272), dp(153)))

        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(26), 0, 0, 0) }
        miniEyebrow = TextView(this).apply { typeface = Fonts.semibold; textSize = 10f; letterSpacing = 0.14f; setTextColor(0xA6FFFFFF.toInt()); shadow() }
        miniLogo = ImageView(this).apply { adjustViewBounds = true; maxWidth = dp(240); maxHeight = dp(44); scaleType = ImageView.ScaleType.FIT_START }
        miniTitle = TextView(this).apply { typeface = Fonts.displayBold; textSize = 26f; setTextColor(Color.WHITE); maxLines = 1; ellipsize = TextUtils.TruncateAt.END; shadow() }
        miniMeta = TextView(this).apply { typeface = Fonts.medium; textSize = 13f; setTextColor(0xD9FFFFFF.toInt()); shadow() }
        miniDesc = TextView(this).apply {
            typeface = Fonts.regular; textSize = 13f; setTextColor(0xB3FFFFFF.toInt()); maxLines = 2; ellipsize = TextUtils.TruncateAt.END
            setLineSpacing(0f, 1.12f); shadow()
        }
        // "OK  Open Netflix": OK on the focused tile is the open action down here
        val hint = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val key = TextView(this).apply {
            text = "OK"; typeface = Fonts.semibold; textSize = 10f; setTextColor(0xFF111111.toInt()); gravity = Gravity.CENTER
            background = GradientDrawable().apply { cornerRadius = dpf(5f); setColor(0xEBFFFFFF.toInt()) }; setPadding(dp(6), dp(1), dp(6), dp(1))
        }
        miniHint = TextView(this).apply { typeface = Fonts.semibold; textSize = 13f; setTextColor(0xE6FFFFFF.toInt()); setPadding(dp(8), 0, 0, 0); shadow() }
        hint.addView(key); hint.addView(miniHint)
        col.addView(miniEyebrow)
        col.addView(miniLogo, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(6) })
        col.addView(miniTitle, LinearLayout.LayoutParams(dp(520), -2).apply { topMargin = dp(2) })
        col.addView(miniMeta, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(6) })
        col.addView(miniDesc, LinearLayout.LayoutParams(dp(470), -2).apply { topMargin = dp(4) })
        miniDots = Dots(this)
        hint.addView(miniDots, LinearLayout.LayoutParams(-2, -2).apply { leftMargin = dp(22) })   // same line as "OK Open …", inside the band
        col.addView(hint, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(10) })
        row.addView(col, LinearLayout.LayoutParams(-2, -2))
        mini.addView(row, FrameLayout.LayoutParams(-2, -2).apply { leftMargin = dp(64); topMargin = dp(80) })
        root.addView(mini, FrameLayout.LayoutParams(-1, dp(MINI_H), Gravity.TOP))
    }

    // ================= Recently used (compact glass shelf) =================
    private var recentBox: View? = null

    private fun recentEntries(): List<Pair<App, Long>> {
        val um = getSystemService(Context.USAGE_STATS_SERVICE) as? android.app.usage.UsageStatsManager ?: return emptyList()
        val now = System.currentTimeMillis()
        val stats = try { um.queryUsageStats(android.app.usage.UsageStatsManager.INTERVAL_BEST, now - 7 * 86400_000L, now) } catch (e: Exception) { null }
            ?: return emptyList()
        val byPkg = apps.associateBy { it.pkg }
        return stats.filter { it.totalTimeInForeground > 0 && it.packageName != packageName }
            .groupBy { it.packageName }.mapValues { e -> e.value.maxOf { it.lastTimeUsed } }
            .entries.sortedByDescending { it.value }
            .mapNotNull { e -> byPkg[e.key]?.let { it to e.value } }.take(4)
    }

    private fun ago(t: Long): String {
        val m = (System.currentTimeMillis() - t) / 60_000
        return when { m < 2 -> "Just now"; m < 60 -> "$m min"; m < 24 * 60 -> "${m / 60} h"; m < 48 * 60 -> "Yesterday"; else -> "${m / (24 * 60)} days" }
    }

    private fun placeRecents() {
        if (moving != null || rows.isEmpty()) return
        val list = recentEntries()
        val hadFocus = recentBox?.findFocus() != null
        recentBox?.let { page.removeView(it) }
        recentBox = null
        if (list.isEmpty()) { if (hadFocus) rows[0].getChildAt(0)?.requestFocus(); return }

        val box = GlassView(this, hero, dpf(22f)).apply { clipChildren = false }
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; clipChildren = false; clipToPadding = false; setPadding(dp(18), dp(12), dp(18), dp(14)) }
        col.addView(TextView(this).apply {
            text = "Recently used"; typeface = Fonts.semibold; textSize = 13f; letterSpacing = 0.04f; setTextColor(0xB3FFFFFF.toInt()); setPadding(dp(6), 0, 0, dp(8))
        })
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; clipChildren = false; clipToPadding = false }
        list.forEachIndexed { i, (a, t) -> row.addView(recentTile(a, t, box), LinearLayout.LayoutParams(0, dp(62), 1f).apply { if (i > 0) leftMargin = dp(10) }) }
        repeat(4 - list.size) { row.addView(View(this), LinearLayout.LayoutParams(0, dp(62), 1f).apply { leftMargin = dp(10) }) }
        col.addView(row)
        box.addView(col, FrameLayout.LayoutParams(-1, -2))
        page.addView(box, page.indexOfChild(favScroll) + 1, LinearLayout.LayoutParams(-1, -2).apply { leftMargin = dp(64); rightMargin = dp(64); bottomMargin = dp(30); topMargin = dp(4) })
        recentBox = box
        if (hadFocus) row.getChildAt(0)?.requestFocus()
    }

    /** Compact tile: small banner + name + "12 min ago". Deliberately unlike the big app cards. */
    private fun recentTile(a: App, t: Long, box: View): View {
        val tile = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; isFocusable = true; isClickable = true
            setPadding(dp(8), 0, dp(12), 0)
        }
        val bg = GradientDrawable().apply { cornerRadius = dpf(14f); setColor(0x14FFFFFF) }
        tile.background = bg
        val r = dpf(8f)
        val thumb = FrameLayout(this).apply {
            outlineProvider = object : ViewOutlineProvider() { override fun getOutline(v: View, o: Outline) { o.setRoundRect(0, 0, v.width, v.height, r) } }
            clipToOutline = true
            background = if (a.isBanner) ColorDrawable(0xFF1B1C21.toInt()) else GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                a.tile ?: intArrayOf(0xFF4A4E5A.toInt(), 0xFF25272E.toInt()))
        }
        val iv = ImageView(this).apply { setImageDrawable(a.art); scaleType = if (a.isBanner) ImageView.ScaleType.CENTER_CROP else ImageView.ScaleType.FIT_CENTER }
        thumb.addView(iv, if (a.isBanner) FrameLayout.LayoutParams(-1, -1) else FrameLayout.LayoutParams(dp(26), dp(26), Gravity.CENTER))
        tile.addView(thumb, LinearLayout.LayoutParams(dp(68), dp(38)))
        val texts = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(10), 0, 0, 0) }
        val name = TextView(this).apply { text = a.label; typeface = Fonts.semibold; textSize = 14f; setTextColor(Color.WHITE); maxLines = 1; ellipsize = TextUtils.TruncateAt.END }
        val time = TextView(this).apply { text = ago(t); typeface = Fonts.regular; textSize = 12f; setTextColor(0x99FFFFFF.toInt()); maxLines = 1 }
        texts.addView(name); texts.addView(time)
        tile.addView(texts, LinearLayout.LayoutParams(0, -2, 1f))
        tile.setOnFocusChangeListener { _, f ->
            if (f) tile.allowOverflow(4)
            bg.setColor(if (f) 0xF2FFFFFF.toInt() else 0x14FFFFFF)
            name.setTextColor(if (f) 0xFF111111.toInt() else Color.WHITE)
            time.setTextColor(if (f) 0x99111111.toInt() else 0x99FFFFFF.toInt())
            tile.animate().scaleX(if (f) 1.06f else 1f).scaleY(if (f) 1.06f else 1f).translationZ(if (f) dpf(10f) else 0f)
                .setDuration(260).setInterpolator(appleOut).start()
            if (f) {
                focusedFrame = null
                hero.spotlight(0f, 0f, false)
                pager.glide((pageTop(box) - dp(GRID_TOP)).coerceAtLeast(0))
                hero.setCollapsed(true)
                pendingSource = sourceFor(a.pkg); main.removeCallbacks(switchRun); main.postDelayed(switchRun, 420)
            }
        }
        tile.setOnClickListener { press(tile, 1.06f) { launch(a) } }
        return tile
    }

    private fun pageTop(v: View): Int { var y = 0; var c: View? = v; while (c != null && c !== page) { y += c.top; c = c.parent as? View }; return y }

    // ================= Control Center =================
    private fun ccTile(icon: Drawable, iconOn: Drawable, title: String, sub: String, onClick: (TextView) -> Unit): View {
        val t = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; isFocusable = true; isClickable = true; setPadding(dp(16), dp(14), dp(14), dp(12))
        }
        val bg = GradientDrawable().apply { cornerRadius = dpf(18f); setColor(0x1FFFFFFF) }
        t.background = bg
        val iv = ImageView(this).apply { setImageDrawable(icon) }
        val tt = TextView(this).apply { text = title; typeface = Fonts.semibold; textSize = 15f; setTextColor(Color.WHITE); maxLines = 1; ellipsize = TextUtils.TruncateAt.END }
        val st = TextView(this).apply { text = sub; typeface = Fonts.regular; textSize = 12.5f; setTextColor(0x99FFFFFF.toInt()); maxLines = 1; ellipsize = TextUtils.TruncateAt.END }
        t.addView(iv, LinearLayout.LayoutParams(dp(24), dp(24)))
        t.addView(tt, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(12) })
        t.addView(st, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(2) })
        t.setOnFocusChangeListener { _, f ->
            if (f) t.allowOverflow(4)
            bg.setColor(if (f) 0xF2FFFFFF.toInt() else 0x1FFFFFFF)
            tt.setTextColor(if (f) 0xFF111111.toInt() else Color.WHITE); st.setTextColor(if (f) 0x99111111.toInt() else 0x99FFFFFF.toInt())
            iv.setImageDrawable(if (f) iconOn else icon)
            t.animate().scaleX(if (f) 1.05f else 1f).scaleY(if (f) 1.05f else 1f).translationZ(if (f) dpf(10f) else 0f).setDuration(240).setInterpolator(appleOut).start()
        }
        t.setOnClickListener { onClick(st) }
        return t
    }

    private fun sleepLabel() = SleepTimer.minutesLeft(this).let { if (it == 0) "Off" else "TV off in $it min" }
    private fun saverMode() = prefs.getString("saver_mode", "aerials")!!
    private fun saverLabel() = when (saverMode()) { "aerials" -> "Apple aerials"; "art" -> "Movie artwork"; "photos" -> "My photos"; else -> "Artwork & my photos" }

    private fun openCC() {
        closeCC()
        // tvOS: a full-height glass panel that slides in from the right edge
        val g = GlassView(this, hero, dpf(30f)).apply { clipChildren = false }
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(22), dp(22), dp(22), dp(10)); clipChildren = false; clipToPadding = false }
        val head = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.BOTTOM; setPadding(dp(4), 0, dp(4), dp(14)) }
        head.addView(TextView(this).apply { text = "Control Center"; typeface = Fonts.display; textSize = 22f; setTextColor(Color.WHITE) }, LinearLayout.LayoutParams(0, -2, 1f))
        head.addView(TextClock(this).apply { format12Hour = "EEE d MMM  h:mm"; format24Hour = "EEE d MMM  H:mm"; typeface = Fonts.medium; textSize = 13f; setTextColor(0x99FFFFFF.toInt()) })
        col.addView(head)
        val grid = GridLayout(this).apply { columnCount = 2; clipChildren = false; clipToPadding = false; setPadding(dp(6), dp(6), dp(6), dp(30)) }
        val w = Color.WHITE; val k = 0xFF111111.toInt()
        val net = NetInfo.read(this)
        val tiles = listOf(
            ccTile(wifiIcon(netLevel, netWifi, w), wifiIcon(netLevel, netWifi, k), net.ssid ?: if (net.ethernet) "Ethernet" else "Wi-Fi", "Details \u00b7 speed test") { closeCC(); openNet() },
            ccTile(inputIcon(w), inputIcon(k), "Inputs", "TV \u00b7 HDMI \u00b7 AV") { closeCC(); openInputs() },
            ccTile(shareIcon(w), shareIcon(k), "Share to TV", "QR \u00b7 LocalSend") { closeCC(); openShare() },
            ccTile(moonIcon(w), moonIcon(k), "Sleep timer", sleepLabel()) { st ->
                val m = SleepTimer.cycle(this); st.text = sleepLabel(); Kit.toast(this, if (m == 0) "Sleep timer off" else "TV turns off in $m min")
            },
            ccTile(saverIcon(w), saverIcon(k), "Screensaver", "Start now") { closeCC(); Power.screensaverNow(this) },
            ccTile(photoIcon(w), photoIcon(k), "Screensaver shows", saverLabel()) { st ->
                val next = when (saverMode()) { "aerials" -> "both"; "both" -> "photos"; "photos" -> "art"; else -> "aerials" }
                prefs.edit().putString("saver_mode", next).apply(); st.text = saverLabel()
            },
            ccTile(lockIcon(w), lockIcon(k), "Lock screen", "Clock \u00b7 date") { closeCC(); startActivity(Intent(this, LockActivity::class.java)) },
            ccTile(eyeIcon(w), eyeIcon(k), "Hidden apps", AppLock.hidden(this).size.let { if (it == 0) "None" else "$it hidden" }) { closeCC(); openHidden() },
            ccTile(speakerIcon(w), speakerIcon(k), "UI sounds", if (Fx.enabled) "On" else "Off") { st ->
                Fx.enabled = !Fx.enabled; prefs.edit().putBoolean("sounds", Fx.enabled).apply(); st.text = if (Fx.enabled) "On" else "Off"; Fx.tick()
            },
            ccTile(aiIcon(w), aiIcon(k), "AI control", if (McpAuth.enabled) "On  \u00b7  ${McpAuth.connections().size} connected" else "Off") { closeCC(); openAiControl() },
            ccTile(clockIcon(w), clockIcon(k), "Routines", Routines.all(this).size.let { if (it == 0) "Open a show at a time" else "$it scheduled" }) { closeCC(); openRoutines() },
            ccTile(gearIcon(w), gearIcon(k), "TV settings", "Picture \u00b7 sound \u00b7 apps") {
                closeCC(); startActivity(Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            },
        )
        tiles.forEach { grid.addView(it, GridLayout.LayoutParams().apply { width = dp(184); height = dp(112); setMargins(dp(5), dp(5), dp(5), dp(5)) }) }
        val stack = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; clipChildren = false; clipToPadding = false }
        stopNowPlaying()
        stack.addView(grid)
        // something playing in the background (music, SmartTube background play): Now Playing card on top, filled in async
        io.go {
            val info = MediaDump.active(this) ?: return@go
            main.post {
                if (ccPanel !== g || npCard != null) return@post
                val c = NowPlayingCard(this, info); npCard = c; c.start()
                stack.addView(c.view, 0, LinearLayout.LayoutParams(dp(378), -2).apply { leftMargin = dp(11); topMargin = dp(6); bottomMargin = dp(4) })
                c.view.alpha = 0f; c.view.animate().alpha(1f).setDuration(300).start()
            }
        }
        // scrolled tiles must slide under the header, not over it; the grid's 6dp padding leaves room for the focus zoom
        val sc = ScrollView(this).apply { isVerticalScrollBarEnabled = false; overScrollMode = View.OVER_SCROLL_NEVER; addView(stack)
            outlineProvider = ViewOutlineProvider.BOUNDS; clipToOutline = true }   // hard clip: lifted (translationZ) tiles otherwise escape it
        col.addView(sc, LinearLayout.LayoutParams(-1, 0, 1f))
        g.addView(col, FrameLayout.LayoutParams(-1, -1))
        root.addView(g, FrameLayout.LayoutParams(dp(440), -1, Gravity.END).apply { topMargin = dp(18); bottomMargin = dp(18); rightMargin = dp(18) })
        g.translationX = dpf(480f); g.alpha = 0.6f
        g.animate().translationX(0f).alpha(1f).setDuration(420).setInterpolator(appleOut).start()
        restoreFocus = currentFocus
        page.descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
        setNavFocusable(false)
        ccPanel = g
        tiles.first().requestFocus()
    }

    private fun closeCC() {
        val g = ccPanel ?: return
        ccPanel = null
        stopNowPlaying()
        g.animate().translationX(dpf(480f)).alpha(0.4f).setDuration(260).setInterpolator(appleEase).withEndAction { root.removeView(g) }.start()
        page.descendantFocusability = ViewGroup.FOCUS_AFTER_DESCENDANTS
        setNavFocusable(true)
        ccBtn.requestFocus()
    }

    /** Hidden apps: OK opens the app, hold OK (or ☰) asks to put it back on the home screen. */
    private fun openHidden() {
        closeList()
        val hidden = AppLock.hidden(this)
        if (hidden.isEmpty()) { Kit.toast(this, "No hidden apps \u00b7 hold OK on a tile \u203a Hide"); return }
        val pm = packageManager
        val entries = hidden.mapNotNull { p ->
            try { val ai = pm.getApplicationInfo(p, 0); Triple(p, pm.getApplicationLabel(ai).toString(), ai.loadIcon(pm)) } catch (e: Exception) { null }
        }.sortedBy { it.second.lowercase() }
        showListPanel("Hidden apps", "OK  open      \u00b7      Hold OK  put back on Home") { col, rowsV ->
            for ((p, label, icon) in entries) {
                val r = iconRow(icon, label) { closeList(); openHiddenApp(p, label) }
                r.setOnLongClickListener { confirmUnhide(p, label); true }
                col.addView(r, LinearLayout.LayoutParams(-1, dp(46)).apply { topMargin = dp(6) }); rowsV.add(r)
            }
        }
    }

    private fun openHiddenApp(pkg: String, label: String) {
        val go = {
            val i = pm().getLeanbackLaunchIntentForPackage(pkg) ?: pm().getLaunchIntentForPackage(pkg)
            if (i != null) startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) else Kit.toast(this, "Can't open $label")
        }
        if (pkg in AppLock.locked(this) && !AppLock.isOpen(pkg) && AppLock.hasPin(this)) withPin("Enter PIN to open $label") { AppLock.grant(pkg); go() } else go()
    }
    private fun pm() = packageManager

    private fun confirmUnhide(pkg: String, label: String) {
        closeList()
        showListPanel("Put $label back on Home?", null) { col, rowsV ->
            val (yes, _) = panelRow("Put back") { AppLock.setHidden(this, pkg, false); reloadApps(); Kit.toast(this, "$label is back on Home"); closeList() }
            val (no, _) = panelRow("Cancel") { closeList(); openHidden() }
            col.addView(yes, LinearLayout.LayoutParams(-1, dp(42)).apply { topMargin = dp(6) }); rowsV.add(yes)
            col.addView(no, LinearLayout.LayoutParams(-1, dp(42)).apply { topMargin = dp(6) }); rowsV.add(no)
        }
    }

    /** Glass list panel under the navbar; [fill] adds the focusable rows. A Done row is appended unless it's a confirm. */
    private fun showListPanel(title: String, hint: String?, fill: (LinearLayout, MutableList<View>) -> Unit) {
        val g = GlassView(this, hero, dpf(24f)).apply { clipChildren = false }
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(18), dp(16), dp(18), dp(16)); clipChildren = false; clipToPadding = false }
        col.addView(TextView(this).apply { text = title; typeface = Fonts.display; textSize = 20f; setTextColor(Color.WHITE); setPadding(dp(4), 0, 0, dp(if (hint == null) 10 else 2)) })
        if (hint != null) col.addView(TextView(this).apply { text = hint; typeface = Fonts.medium; textSize = 12f; setTextColor(0x80FFFFFF.toInt()); setPadding(dp(4), 0, 0, dp(10)) })
        val rowsV = ArrayList<View>()
        fill(col, rowsV)
        if (hint != null) {
            val (done, _) = panelRow("Done") { closeList() }
            col.addView(done, LinearLayout.LayoutParams(-1, dp(42)).apply { topMargin = dp(12) }); rowsV.add(done)
        }
        rowsV.forEach { it.id = View.generateViewId(); it.nextFocusLeftId = it.id; it.nextFocusRightId = it.id }
        rowsV.first().nextFocusUpId = rowsV.first().id; rowsV.last().nextFocusDownId = rowsV.last().id
        g.addView(col, FrameLayout.LayoutParams(-1, -2))
        root.addView(g, FrameLayout.LayoutParams(dp(440), -2, Gravity.TOP or Gravity.END).apply { topMargin = dp(74); rightMargin = dp(48) })
        page.descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
        setNavFocusable(false)
        listPanel = g
        rowsV.first().requestFocus()
    }

    /** Row with the app's icon and name; turns white on focus like the other panel rows. */
    private fun iconRow(icon: Drawable, label: String, onClick: () -> Unit): View {
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; isFocusable = true; isClickable = true; setPadding(dp(12), 0, dp(14), 0) }
        val bg = GradientDrawable().apply { cornerRadius = dpf(14f); setColor(0x1AFFFFFF) }
        row.background = bg
        row.addView(ImageView(this).apply { setImageDrawable(icon) }, LinearLayout.LayoutParams(dp(28), dp(28)))
        val tv = TextView(this).apply { text = label; typeface = Fonts.semibold; textSize = 15f; setTextColor(Color.WHITE); setPadding(dp(12), 0, 0, 0); maxLines = 1; ellipsize = TextUtils.TruncateAt.END }
        row.addView(tv, LinearLayout.LayoutParams(0, -2, 1f))
        row.setOnFocusChangeListener { v, f ->
            if (f) v.allowOverflow(4)
            bg.setColor(if (f) 0xF2FFFFFF.toInt() else 0x1AFFFFFF); tv.setTextColor(if (f) 0xFF111111.toInt() else Color.WHITE)
            row.animate().scaleX(if (f) 1.03f else 1f).scaleY(if (f) 1.03f else 1f).setDuration(220).setInterpolator(appleOut).start()
        }
        row.setOnClickListener { onClick() }
        return row
    }

    private fun closeList() {
        val g = listPanel ?: return
        listPanel = null
        root.removeView(g)
        page.descendantFocusability = ViewGroup.FOCUS_AFTER_DESCENDANTS
        setNavFocusable(true)
        ccBtn.requestFocus()
    }

    // ---- Control Center glyphs ----
    private fun ccIcon(color: Int) = iconBitmap { s ->
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color; style = Paint.Style.STROKE; strokeWidth = s * 0.08f; strokeCap = Paint.Cap.ROUND }
        drawLine(s * 0.14f, s * 0.32f, s * 0.86f, s * 0.32f, p); drawLine(s * 0.14f, s * 0.68f, s * 0.86f, s * 0.68f, p)
        p.style = Paint.Style.FILL; drawCircle(s * 0.36f, s * 0.32f, s * 0.12f, p); drawCircle(s * 0.64f, s * 0.68f, s * 0.12f, p)
    }
    private fun speakerIcon(color: Int) = iconBitmap { s ->
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color }
        drawPath(Path().apply { moveTo(s * 0.12f, s * 0.38f); lineTo(s * 0.3f, s * 0.38f); lineTo(s * 0.52f, s * 0.18f); lineTo(s * 0.52f, s * 0.82f)
            lineTo(s * 0.3f, s * 0.62f); lineTo(s * 0.12f, s * 0.62f); close() }, p)
        p.style = Paint.Style.STROKE; p.strokeWidth = s * 0.07f; p.strokeCap = Paint.Cap.ROUND
        drawArc(RectF(s * 0.46f, s * 0.3f, s * 0.78f, s * 0.7f), -50f, 100f, false, p)
        drawArc(RectF(s * 0.44f, s * 0.16f, s * 0.92f, s * 0.84f), -50f, 100f, false, p)
    }
    /** Four-point sparkle: the usual "AI" glyph. */
    private fun aiIcon(color: Int) = iconBitmap { s ->
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color }
        fun star(cx: Float, cy: Float, r: Float) = drawPath(Path().apply {
            moveTo(cx, cy - r); quadTo(cx, cy, cx + r, cy); quadTo(cx, cy, cx, cy + r); quadTo(cx, cy, cx - r, cy); quadTo(cx, cy, cx, cy - r); close() }, p)
        star(s * 0.42f, s * 0.56f, s * 0.36f); star(s * 0.78f, s * 0.22f, s * 0.15f)
    }
    private fun clockIcon(color: Int) = iconBitmap { s ->
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color; style = Paint.Style.STROKE; strokeWidth = s * 0.08f; strokeCap = Paint.Cap.ROUND }
        drawCircle(s / 2, s / 2, s * 0.38f, p)
        drawLine(s / 2, s / 2, s / 2, s * 0.27f, p); drawLine(s / 2, s / 2, s * 0.66f, s * 0.6f, p)
    }
    private fun moonIcon(color: Int) = iconBitmap { s ->
        val path = Path().apply { addCircle(s / 2, s / 2, s * 0.36f, Path.Direction.CW) }
        path.op(Path().apply { addCircle(s * 0.68f, s * 0.36f, s * 0.3f, Path.Direction.CW) }, Path.Op.DIFFERENCE)
        drawPath(path, Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color })
    }
    private fun saverIcon(color: Int) = iconBitmap { s ->
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color; style = Paint.Style.STROKE; strokeWidth = s * 0.07f; strokeJoin = Paint.Join.ROUND }
        drawRoundRect(s * 0.1f, s * 0.2f, s * 0.9f, s * 0.72f, s * 0.06f, s * 0.06f, p); drawLine(s * 0.36f, s * 0.84f, s * 0.64f, s * 0.84f, p)
        p.style = Paint.Style.FILL
        drawPath(Path().apply { moveTo(s * 0.5f, s * 0.3f); lineTo(s * 0.55f, s * 0.42f); lineTo(s * 0.67f, s * 0.46f); lineTo(s * 0.55f, s * 0.5f)
            lineTo(s * 0.5f, s * 0.62f); lineTo(s * 0.45f, s * 0.5f); lineTo(s * 0.33f, s * 0.46f); lineTo(s * 0.45f, s * 0.42f); close() }, p)
    }
    private fun photoIcon(color: Int) = iconBitmap { s ->
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color; style = Paint.Style.STROKE; strokeWidth = s * 0.07f; strokeJoin = Paint.Join.ROUND }
        drawRoundRect(s * 0.1f, s * 0.18f, s * 0.9f, s * 0.82f, s * 0.08f, s * 0.08f, p)
        p.style = Paint.Style.FILL; drawCircle(s * 0.34f, s * 0.4f, s * 0.07f, p)
        drawPath(Path().apply { moveTo(s * 0.18f, s * 0.74f); lineTo(s * 0.42f, s * 0.52f); lineTo(s * 0.56f, s * 0.64f); lineTo(s * 0.66f, s * 0.56f); lineTo(s * 0.82f, s * 0.74f); close() }, p)
    }
    private fun eyeIcon(color: Int) = iconBitmap { s ->
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color; style = Paint.Style.STROKE; strokeWidth = s * 0.07f; strokeCap = Paint.Cap.ROUND }
        drawPath(Path().apply { moveTo(s * 0.08f, s * 0.5f); quadTo(s * 0.5f, s * 0.08f, s * 0.92f, s * 0.5f); quadTo(s * 0.5f, s * 0.92f, s * 0.08f, s * 0.5f) }, p)
        drawCircle(s / 2, s / 2, s * 0.13f, p); drawLine(s * 0.16f, s * 0.84f, s * 0.84f, s * 0.16f, p)
    }
    private fun micIcon(color: Int) = iconBitmap { s ->
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color }
        drawRoundRect(s * 0.36f, s * 0.08f, s * 0.64f, s * 0.6f, s * 0.14f, s * 0.14f, p)
        p.style = Paint.Style.STROKE; p.strokeWidth = s * 0.07f; p.strokeCap = Paint.Cap.ROUND
        drawArc(RectF(s * 0.24f, s * 0.26f, s * 0.76f, s * 0.74f), 0f, 180f, false, p)
        drawLine(s / 2, s * 0.74f, s / 2, s * 0.9f, p)
    }

    // ================= Share (Drop + LocalSend) =================
    private fun buildSharePanel() {
        sharePanel = GlassView(this, hero, dpf(26f)).apply { visibility = View.GONE; pivotX = dpf(300f); pivotY = 0f }
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(22), dp(18), dp(22), dp(18)) }
        col.addView(TextView(this).apply { text = "Share to Arsh TV"; typeface = Fonts.display; textSize = 22f; setTextColor(Color.WHITE) })
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(0, dp(14), 0, 0) }
        shareQr = ImageView(this).apply {
            background = GradientDrawable().apply { cornerRadius = dpf(16f); setColor(Color.WHITE) }
            setPadding(dp(8), dp(8), dp(8), dp(8))
        }
        row.addView(shareQr, LinearLayout.LayoutParams(dp(150), dp(150)))
        val tips = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(18), 0, 0, 0) }
        fun tip(head: String, body: String) {
            tips.addView(TextView(this).apply { text = head; typeface = Fonts.semibold; textSize = 15f; setTextColor(Color.WHITE); setPadding(0, dp(4), 0, 0) })
            tips.addView(TextView(this).apply { text = body; typeface = Fonts.regular; textSize = 13f; setTextColor(0xB3FFFFFF.toInt()); setLineSpacing(0f, 1.15f) })
        }
        tip("Any phone", "Scan the code with the camera, pick photos or files.")
        tip("LocalSend app", "iPhone & Android: Share \u203a LocalSend \u203a Arsh TV.")
        shareHint = TextView(this).apply { typeface = Fonts.medium; textSize = 12f; setTextColor(0x80FFFFFF.toInt()); setPadding(0, dp(8), 0, 0) }
        tips.addView(shareHint)
        row.addView(tips, LinearLayout.LayoutParams(0, -2, 1f))
        col.addView(row)
        val (files, _) = panelRow("Open Files") { closeShare(); startActivity(Intent(this, FilesActivity::class.java)) }
        val (done, _) = panelRow("Done") { closeShare() }
        col.addView(files, LinearLayout.LayoutParams(-1, dp(42)).apply { topMargin = dp(16) })
        col.addView(done, LinearLayout.LayoutParams(-1, dp(42)).apply { topMargin = dp(8) })
        listOf(files, done).forEach { it.id = View.generateViewId(); it.nextFocusLeftId = it.id; it.nextFocusRightId = it.id }
        files.nextFocusUpId = files.id; files.nextFocusDownId = done.id; done.nextFocusUpId = files.id; done.nextFocusDownId = done.id
        shareFirst = files
        sharePanel.addView(col, FrameLayout.LayoutParams(-1, -2))
        root.addView(sharePanel, FrameLayout.LayoutParams(dp(480), -2, Gravity.TOP or Gravity.END).apply { topMargin = dp(74); rightMargin = dp(48) })
    }

    private fun qr(text: String, size: Int): Bitmap {
        val m = com.google.zxing.qrcode.QRCodeWriter().encode(text, com.google.zxing.BarcodeFormat.QR_CODE, size, size,
            mapOf(com.google.zxing.EncodeHintType.MARGIN to 0))
        val px = IntArray(size * size) { i -> if (m[i % size, i / size]) 0xFF111111.toInt() else Color.WHITE }
        return Bitmap.createBitmap(px, size, size, Bitmap.Config.ARGB_8888)
    }

    private fun openShare() {
        val ip = NetInfo.read(this).ip
        if (ip == null) { Kit.toast(this, "Connect the TV to Wi-Fi to share"); return }
        shareQr.setImageBitmap(qr(ShareHub.dropUrl(ip), dp(134)))
        shareHint.text = "Or type  ${ShareHub.friendlyUrl}  \u00b7  same Wi-Fi"
        restoreFocus = currentFocus
        page.descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
        setNavFocusable(false)
        sharePanel.visibility = View.VISIBLE; sharePanel.alpha = 0f; sharePanel.scaleX = 0.92f; sharePanel.scaleY = 0.92f
        sharePanel.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(360).setInterpolator(appleOut).start()
        shareFirst.requestFocus()
    }

    private fun closeShare() {
        page.descendantFocusability = ViewGroup.FOCUS_AFTER_DESCENDANTS
        setNavFocusable(true)
        sharePanel.animate().alpha(0f).scaleX(0.95f).scaleY(0.95f).setDuration(200).setInterpolator(appleEase)
            .withEndAction { sharePanel.visibility = View.GONE }.start()
        (restoreFocus ?: ccBtn).requestFocus()
    }

    /** AirDrop-like glyph: dot inside two open rings. */
    private fun shareIcon(color: Int) = iconBitmap { s ->
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color; style = Paint.Style.STROKE; strokeWidth = s * 0.085f; strokeCap = Paint.Cap.ROUND }
        drawArc(RectF(s * 0.1f, s * 0.1f, s * 0.9f, s * 0.9f), 120f, 300f, false, p)
        drawArc(RectF(s * 0.29f, s * 0.29f, s * 0.71f, s * 0.71f), 120f, 300f, false, p)
        p.style = Paint.Style.FILL; drawCircle(s / 2, s / 2, s * 0.09f, p)
    }

    // ================= ADB behind a PIN =================
    // adb itself has no passwords (it uses on-screen key approval). Instead ADB stays OFF; turning it on
    // here needs the PIN and it switches itself off again after 2 hours. Turning it off never needs the PIN.
    private lateinit var adbLabel: TextView
    private val adbOffRun = Runnable { setAdb(false) }
    private val adbWindowMs = 2 * 3600_000L
    private var pinPad: GlassView? = null
    private var pinEntry = ""
    private var pinDots: List<View> = emptyList()
    private var pinTitle: TextView? = null
    private var onPin: ((String) -> Unit)? = null

    private fun adbOn() = Settings.Global.getInt(contentResolver, Settings.Global.ADB_ENABLED, 0) == 1

    private fun updateAdbLabel() {
        val until = prefs.getLong("adb_until", 0)
        adbLabel.text = when {
            !adbOn() -> "ADB access: Off"
            until > System.currentTimeMillis() -> "ADB access: On  \u00b7  off in ${((until - System.currentTimeMillis()) / 60000).coerceAtLeast(1)} min"
            else -> "ADB access: On"
        }
    }

    private fun setAdb(on: Boolean) {
        try {
            Settings.Global.putInt(contentResolver, Settings.Global.ADB_ENABLED, if (on) 1 else 0)
        } catch (e: SecurityException) { Kit.toast(this, "Needs WRITE_SECURE_SETTINGS (run install.sh)", long = true); return }
        main.removeCallbacks(adbOffRun)
        if (on) { prefs.edit().putLong("adb_until", System.currentTimeMillis() + adbWindowMs).apply(); main.postDelayed(adbOffRun, adbWindowMs) }
        else prefs.edit().remove("adb_until").apply()
        AdbTimer.arm(this)
        if (::adbLabel.isInitialized) updateAdbLabel()
    }

    /** Only enforces a window the launcher opened itself; ADB switched on in Developer options is left alone. */
    private fun checkAdbExpiry() {
        val until = prefs.getLong("adb_until", 0)
        if (until == 0L) return
        val left = until - System.currentTimeMillis()
        if (left <= 0) setAdb(false) else { main.removeCallbacks(adbOffRun); main.postDelayed(adbOffRun, left) }
    }


    private fun toggleAdb() {
        if (adbOn()) { setAdb(false); Kit.toast(this, "ADB turned off"); return }
        val saved = prefs.getString("adb_pin", null)
        closeNet()
        if (saved == null) {
            showPinPad("Create a 4-digit PIN for ADB") { first ->
                pinTitle?.text = "Enter the same PIN again"
                onPin = { second ->
                    if (first == second) {
                        AppLock.setPin(this, first)
                        closePinPad(); setAdb(true); Kit.toast(this, "PIN saved \u00b7 ADB on for 2 hours", long = true)
                    } else { pinShake(); toggleAdbRestart() }
                }
            }
        } else {
            showPinPad("Enter PIN to turn on ADB") { p ->
                val lockedUntil = prefs.getLong("pin_locked_until", 0)
                when {
                    System.currentTimeMillis() < lockedUntil -> { pinShake(); pinTitle?.text = "Too many tries \u00b7 wait a few minutes" }
                    AppLock.check(this, p) -> { prefs.edit().putInt("pin_fails", 0).apply(); closePinPad(); setAdb(true); Kit.toast(this, "ADB on for 2 hours", long = true) }
                    else -> {
                        val fails = prefs.getInt("pin_fails", 0) + 1
                        prefs.edit().putInt("pin_fails", fails).apply()
                        if (fails >= 5) prefs.edit().putLong("pin_locked_until", System.currentTimeMillis() + 5 * 60_000L).putInt("pin_fails", 0).apply()
                        pinShake(); pinTitle?.text = if (fails >= 5) "Too many tries \u00b7 wait 5 minutes" else "Wrong PIN, try again"
                    }
                }
            }
        }
    }

    private fun toggleAdbRestart() { main.postDelayed({ closePinPad(); toggleAdb() }, 500) }

    private fun showPinPad(title: String, done: (String) -> Unit) {
        closePinPad()
        pinRestore = currentFocus
        onPin = done; pinEntry = ""
        val g = GlassView(this, hero, dpf(26f))
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL; setPadding(dp(22), dp(20), dp(22), dp(20)) }
        pinTitle = TextView(this).apply { text = title; typeface = Fonts.semibold; textSize = 17f; setTextColor(Color.WHITE); gravity = Gravity.CENTER }
        col.addView(pinTitle, LinearLayout.LayoutParams(-1, -2))
        val dotsRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER; setPadding(0, dp(14), 0, dp(14)) }
        pinDots = (0 until 4).map {
            View(this).also { v -> dotsRow.addView(v, LinearLayout.LayoutParams(dp(14), dp(14)).apply { leftMargin = dp(8); rightMargin = dp(8) }) }
        }
        col.addView(dotsRow)
        val grid = GridLayout(this).apply { columnCount = 3 }
        var first: View? = null
        for (k in listOf("1", "2", "3", "4", "5", "6", "7", "8", "9", "\u232B", "0", "Cancel")) {
            val (key, _) = panelRow(k) {
                when (k) {
                    "Cancel" -> closePinPad()
                    "\u232B" -> { pinEntry = pinEntry.dropLast(1); paintDots() }
                    else -> if (pinEntry.length < 4) {
                        pinEntry += k; paintDots()
                        if (pinEntry.length == 4) { val p = pinEntry; main.postDelayed({ pinEntry = ""; paintDots(); onPin?.invoke(p) }, 150) }
                    }
                }
            }
            grid.addView(key, GridLayout.LayoutParams().apply { width = dp(72); height = dp(46); setMargins(dp(5), dp(5), dp(5), dp(5)) })
            if (first == null) first = key
        }
        col.addView(grid)
        g.addView(col, FrameLayout.LayoutParams(-2, -2))
        root.addView(g, FrameLayout.LayoutParams(-2, -2, Gravity.CENTER))
        g.alpha = 0f; g.scaleX = 0.92f; g.scaleY = 0.92f
        g.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(320).setInterpolator(appleOut).start()
        pinPad = g
        page.descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
        setNavFocusable(false)
        paintDots()
        first?.requestFocus()
    }

    private fun paintDots() = pinDots.forEachIndexed { i, v ->
        v.background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            if (i < pinEntry.length) setColor(Color.WHITE) else { setColor(Color.TRANSPARENT); setStroke(dp(2), 0xB3FFFFFF.toInt()) }
        }
    }

    private fun pinShake() {
        pinPad?.animate()?.translationX(dpf(14f))?.setDuration(60)?.withEndAction {
            pinPad?.animate()?.translationX(-dpf(14f))?.setDuration(80)?.withEndAction { pinPad?.animate()?.translationX(0f)?.setDuration(70)?.start() }?.start()
        }?.start()
    }

    private fun closePinPad() {
        val g = pinPad ?: return
        pinPad = null; onPin = null
        g.animate().alpha(0f).setDuration(160).withEndAction { root.removeView(g) }.start()
        page.descendantFocusability = ViewGroup.FOCUS_AFTER_DESCENDANTS
        setNavFocusable(true)
        (pinRestore ?: ccBtn).requestFocus(); pinRestore = null
    }

    // ================= Now Playing (Control Center) =================
    private var npCard: NowPlayingCard? = null
    private fun stopNowPlaying() { npCard?.stop(); npCard = null }

    // ================= app switcher (Home twice, like tvOS) =================
    private var switcher: View? = null
    private var switchRow: LinearLayout? = null
    private var switchScroll: HorizontalScrollView? = null
    /** Apps closed from the switcher stay out of it until they're used again. */
    private val closedAt get() = Recents.closedAt

    private fun switcherApps(): List<Pair<App, Long>> {
        val um = getSystemService(Context.USAGE_STATS_SERVICE) as? android.app.usage.UsageStatsManager ?: return emptyList()
        val now = System.currentTimeMillis()
        val stats = try { um.queryUsageStats(android.app.usage.UsageStatsManager.INTERVAL_BEST, now - 3 * 86400_000L, now) } catch (e: Exception) { null } ?: return emptyList()
        val byPkg = apps.associateBy { it.pkg }
        return stats.filter { it.totalTimeInForeground > 0 && it.packageName != packageName }
            .groupBy { it.packageName }.mapValues { e -> e.value.maxOf { it.lastTimeUsed } }
            .filter { (p, t) -> t > (closedAt[p] ?: 0L) }
            .entries.sortedByDescending { it.value }
            .mapNotNull { e -> byPkg[e.key]?.let { it to e.value } }.take(8)
    }

    private fun openSwitcher() {
        if (switcher != null) { closeSwitcher(); return }
        if (ccPanel != null) closeCC()
        if (listPanel != null) closeList()
        if (cardMenu != null) closeCardMenu(restore = false)
        if (folderSheet != null) closeFolder()
        if (sOverlay.visibility == View.VISIBLE) closeSpotlight()
        val list = switcherApps()
        if (list.isEmpty()) { Kit.toast(this, "No recent apps"); return }
        val cw = dp(420); val ch = dp(236); val gapW = dp(44)
        val sheet = FrameLayout(this).apply { isClickable = true; clipChildren = false }
        val back = object : View(this) { override fun onDraw(c: Canvas) { hero.drawBackdrop(c); c.drawColor(0xB3000000.toInt()) } }
        sheet.addView(back, FrameLayout.LayoutParams(-1, -1))
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; clipChildren = false; clipToPadding = false
            val side = (resources.displayMetrics.widthPixels - cw) / 2
            setPadding(side, dp(70), side, dp(60))
        }
        val scroll = object : HorizontalScrollView(this) { override fun computeScrollDeltaToGetChildRectOnScreen(r: Rect?) = 0 }.apply {
            isHorizontalScrollBarEnabled = false; overScrollMode = View.OVER_SCROLL_NEVER; clipChildren = false; clipToPadding = false; addView(row)
        }
        list.forEachIndexed { i, (a, t) -> row.addView(switchCard(a, t, cw, ch), LinearLayout.LayoutParams(cw, -2).apply { if (i > 0) leftMargin = gapW }) }
        sheet.addView(scroll, FrameLayout.LayoutParams(-1, -2, Gravity.CENTER_VERTICAL))
        sheet.addView(TextView(this).apply {
            text = "\u25B2  Close app        OK  Open        \u25C0 \u25B6  Browse"; typeface = Fonts.medium; textSize = 13f; setTextColor(0x8CFFFFFF.toInt()); gravity = Gravity.CENTER
        }, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM).apply { bottomMargin = dp(46) })
        root.addView(sheet, FrameLayout.LayoutParams(-1, -1))
        switcher = sheet; switchRow = row; switchScroll = scroll
        page.descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
        setNavFocusable(false)
        // tvOS: the cards rise in from below, the most recent app in the middle
        sheet.alpha = 0f; scroll.translationY = dpf(60f); scroll.scaleX = 0.94f; scroll.scaleY = 0.94f
        sheet.animate().alpha(1f).setDuration(240).setInterpolator(appleEase).start()
        scroll.animate().translationY(0f).scaleX(1f).scaleY(1f).setDuration(480).setInterpolator(appleOut).start()
        row.getChildAt(0)?.requestFocus()
    }

    private fun switchCard(a: App, t: Long, cw: Int, ch: Int): View {
        val cell = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; isFocusable = true; isClickable = true; clipChildren = false; clipToPadding = false }
        val head = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.BOTTOM; setPadding(dp(4), 0, dp(4), dp(12)) }
        head.addView(TextView(this).apply { text = a.label; typeface = Fonts.semibold; textSize = 19f; setTextColor(Color.WHITE); maxLines = 1; ellipsize = TextUtils.TruncateAt.END },
            LinearLayout.LayoutParams(0, -2, 1f))
        head.addView(TextView(this).apply { text = ago(t); typeface = Fonts.regular; textSize = 13f; setTextColor(0x99FFFFFF.toInt()) })
        cell.addView(head, LinearLayout.LayoutParams(cw, -2))
        val r = dpf(22f)
        val frame = FrameLayout(this).apply {
            outlineProvider = object : ViewOutlineProvider() { override fun getOutline(v: View, o: Outline) { o.setRoundRect(0, 0, v.width, v.height, r) } }
            clipToOutline = true; elevation = dpf(12f)
            setLayerType(View.LAYER_TYPE_HARDWARE, null)   // drawn once, then just a scaled texture while you browse
            background = if (a.isBanner) ColorDrawable(0xFF1B1C21.toInt())
            else GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, a.tile ?: intArrayOf(0xFF4A4E5A.toInt(), 0xFF25272E.toInt()))
        }
        val img = ImageView(this).apply { setImageDrawable(a.art.constantState?.newDrawable(resources) ?: a.art) }
        if (a.isBanner) { img.scaleType = ImageView.ScaleType.CENTER_CROP; frame.addView(img, FrameLayout.LayoutParams(-1, -1)) }
        else { img.scaleType = ImageView.ScaleType.FIT_CENTER; frame.addView(img, FrameLayout.LayoutParams((ch * 0.5f).toInt(), (ch * 0.5f).toInt(), Gravity.CENTER)) }
        cell.addView(frame, LinearLayout.LayoutParams(cw, ch))
        cell.tag = a
        cell.scaleX = 0.86f; cell.scaleY = 0.86f; cell.alpha = 0.55f
        cell.setOnFocusChangeListener { _, f ->
            cell.animate().scaleX(if (f) 1f else 0.86f).scaleY(if (f) 1f else 0.86f).alpha(if (f) 1f else 0.55f).setDuration(380).setInterpolator(appleOut).start()
            frame.animate().translationZ(if (f) dpf(24f) else 0f).setDuration(380).start()
            if (f) {
                Fx.tick()
                val i = switchRow?.indexOfChild(cell) ?: 0
                switchScroll?.smoothScrollTo(i * (cw + dp(44)), 0)   // the focused card always sits in the middle
            }
        }
        cell.setOnClickListener { press(cell, 1f) { launch(a); overridePendingTransition(0, 0) } }
        return cell
    }

    /** ▲ on a card: it flies up and away, and the app is closed (background processes killed). */
    private fun closeRecentApp(v: View?) {
        val row = switchRow ?: return
        val a = v?.tag as? App ?: return
        if (v.parent !== row) return
        closedAt[a.pkg] = System.currentTimeMillis()
        try { (getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager).killBackgroundProcesses(a.pkg) } catch (e: Exception) {}
        val i = row.indexOfChild(v)
        Fx.edge()
        v.animate().translationY(-dpf(420f)).alpha(0f).setDuration(320).setInterpolator(appleEase).withEndAction {
            row.removeView(v)
            if (row.childCount == 0) { closeSwitcher(); Kit.toast(this, "All recent apps closed"); return@withEndAction }
            row.getChildAt(minOf(i, row.childCount - 1))?.requestFocus()
        }.start()
    }

    private fun closeSwitcher(animate: Boolean = true) {
        val sh = switcher ?: return
        switcher = null; switchRow = null; switchScroll = null
        page.descendantFocusability = ViewGroup.FOCUS_AFTER_DESCENDANTS
        setNavFocusable(true)
        if (animate) sh.animate().alpha(0f).setDuration(200).withEndAction { root.removeView(sh) }.start() else root.removeView(sh)
        if (animate) (focusedFrame?.parent as? View ?: rows.firstOrNull()?.getChildAt(0))?.requestFocus()
    }

    // ================= AI control (MCP over OAuth: apps connect with a code shown on the TV) =================
    private fun openAiControl() {
        val on = McpAuth.enabled
        val conns = McpAuth.connections()
        showListPanel("AI control", if (on) "Add this address in Claude, ChatGPT or any MCP app. It will ask for a code that appears here on the TV."
            else "Let AI assistants (Claude, ChatGPT\u2026) control this TV. Each app must be approved with a code shown on screen.") { col, rowsV ->
            val (toggle, _) = panelRow(if (on) "Turn AI control off" else "Turn AI control on") {
                McpAuth.enabled = !on; closeList(); openAiControl()
                Kit.toast(this, if (on) "AI control off \u00b7 apps can't reach the TV" else "AI control on")
            }
            col.addView(toggle, LinearLayout.LayoutParams(-1, dp(42)).apply { topMargin = dp(4) }); rowsV.add(toggle)
            if (on) {
                col.addView(TextView(this).apply {
                    text = McpAuth.publicUrl; typeface = Fonts.semibold; textSize = 14f; setTextColor(Color.WHITE); setTextIsSelectable(false)
                    background = GradientDrawable().apply { cornerRadius = dpf(12f); setColor(0x26000000) }; setPadding(dp(14), dp(10), dp(14), dp(10))
                }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(10) })
                col.addView(TextView(this).apply {
                    text = if (conns.isEmpty()) "No apps connected yet" else "Connected apps  \u00b7  OK to disconnect"
                    typeface = Fonts.semibold; textSize = 12f; letterSpacing = 0.04f; setTextColor(0x99FFFFFF.toInt()); setPadding(dp(4), dp(14), 0, dp(2))
                })
                for (c in conns) {
                    val (row, tv) = panelRow("${c.client}   \u00b7   ${if (c.lastUsed > 0) "used " + ago(c.lastUsed).lowercase() + if (ago(c.lastUsed).first().isDigit()) " ago" else "" else "never used"}") {
                        closeList()
                        showListPanel("Disconnect ${c.client}?", null) { c2, r2 ->
                            val (yes, _) = panelRow("Disconnect") { closeList(); McpAuth.revoke(c.id); Kit.toast(this, "${c.client} disconnected"); openAiControl() }
                            val (no, _) = panelRow("Keep") { closeList(); openAiControl() }
                            for (v in listOf(yes, no)) { c2.addView(v, LinearLayout.LayoutParams(-1, dp(42)).apply { topMargin = dp(6) }); r2.add(v) }
                        }
                    }
                    tv.maxLines = 1; tv.ellipsize = TextUtils.TruncateAt.END; tv.setPadding(dp(14), 0, dp(14), 0)
                    col.addView(row, LinearLayout.LayoutParams(-1, dp(42)).apply { topMargin = dp(6) }); rowsV.add(row)
                }
            }
        }
    }

    // ================= routines (open a show at a set time, even from standby) =================
    private fun scheduleRoutine(pkg: String, url: String, title: String) {
        pickTime("Open \u201c$title\u201d at") { h, m ->
            showListPanel("Repeat", null) { col, rowsV ->
                for ((label, days) in listOf("Once" to 0, "Every day" to Routines.EVERY_DAY, "Weekdays" to Routines.WEEKDAYS, "Weekends" to Routines.WEEKENDS)) {
                    val (r, _) = panelRow(label) {
                        closeList()
                        val rt = Routines.Routine(Routines.newId(), h, m, days, pkg, url, title)
                        Routines.add(this, rt)
                        Banner.show(this, "Routine set", "$title \u00b7 ${Routines.describe(rt)}", ms = 4000)
                    }
                    col.addView(r, LinearLayout.LayoutParams(-1, dp(42)).apply { topMargin = dp(6) }); rowsV.add(r)
                }
            }
        }
    }

    private fun openRoutines() {
        val list = Routines.all(this).sortedBy { it.hour * 60 + it.minute }
        showListPanel("Routines", if (list.isEmpty()) "Hold OK on an app (or on a show in search results) \u2192 Open at a time" else "OK on a routine to delete it") { col, rowsV ->
            for (r in list) {
                val (row, tv) = panelRow("${Routines.describe(r)}   \u2014   ${r.title}") {
                    closeList()
                    showListPanel("Delete this routine?", null) { c2, r2 ->
                        val (del, _) = panelRow("Delete") { closeList(); Routines.delete(this, r.id); Kit.toast(this, "Routine deleted") }
                        val (keep, _) = panelRow("Keep") { closeList() }
                        for (v in listOf(del, keep)) { c2.addView(v, LinearLayout.LayoutParams(-1, dp(42)).apply { topMargin = dp(6) }); r2.add(v) }
                    }
                }
                tv.maxLines = 1; tv.ellipsize = TextUtils.TruncateAt.END; tv.setPadding(dp(14), 0, dp(14), 0)
                col.addView(row, LinearLayout.LayoutParams(-1, dp(42)).apply { topMargin = dp(6) }); rowsV.add(row)
            }
        }
    }

    /** tvOS-style time picker: hour, minute and AM/PM wheels; ▲ ▼ change the focused one, OK confirms. */
    private fun pickTime(title: String, done: (Int, Int) -> Unit) {
        closeList()
        val now = java.util.Calendar.getInstance()
        var mins = ((now.get(java.util.Calendar.HOUR_OF_DAY) * 60 + now.get(java.util.Calendar.MINUTE)) / 5 + 6) * 5 % (24 * 60)   // ~30 min from now
        val g = GlassView(this, hero, dpf(28f)).apply { clipChildren = false }
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL; setPadding(dp(28), dp(22), dp(28), dp(20)); clipChildren = false; clipToPadding = false }
        col.addView(TextView(this).apply { text = title; typeface = Fonts.display; textSize = 20f; setTextColor(Color.WHITE); gravity = Gravity.CENTER; maxLines = 2 }, LinearLayout.LayoutParams(dp(380), -2))
        val wheels = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER; clipChildren = false; setPadding(0, dp(18), 0, dp(6)) }
        val texts = ArrayList<TextView>()
        fun paint() {
            val h = mins / 60; val m = mins % 60
            texts[0].text = (if (h % 12 == 0) 12 else h % 12).toString(); texts[1].text = String.format(java.util.Locale.US, "%02d", m); texts[2].text = if (h < 12) "AM" else "PM"
        }
        val confirm = { closeList(); done(mins / 60, mins % 60) }
        for ((i, step) in listOf(60, 5, 12 * 60).withIndex()) {
            val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL }
            val up = TextView(this).apply { text = "\u25B2"; textSize = 11f; setTextColor(0x00FFFFFF); gravity = Gravity.CENTER }
            val down = TextView(this).apply { text = "\u25BC"; textSize = 11f; setTextColor(0x00FFFFFF); gravity = Gravity.CENTER }
            val bg = GradientDrawable().apply { cornerRadius = dpf(16f); setColor(0x1AFFFFFF) }
            val tv = TextView(this).apply {
                typeface = Fonts.displayBold; textSize = if (i == 2) 26f else 38f; setTextColor(Color.WHITE); gravity = Gravity.CENTER
                background = bg; isFocusable = true; isClickable = true; tag = "spinner"
            }
            tv.setOnFocusChangeListener { _, f ->
                bg.setColor(if (f) 0xF2FFFFFF.toInt() else 0x1AFFFFFF); tv.setTextColor(if (f) 0xFF111111.toInt() else Color.WHITE)
                up.setTextColor(if (f) 0xB3FFFFFF.toInt() else 0x00FFFFFF); down.setTextColor(up.currentTextColor)
                tv.animate().scaleX(if (f) 1.06f else 1f).scaleY(if (f) 1.06f else 1f).setDuration(200).setInterpolator(appleOut).start()
            }
            tv.setOnKeyListener { _, k, e ->
                if (e.action != KeyEvent.ACTION_DOWN) return@setOnKeyListener k == KeyEvent.KEYCODE_DPAD_UP || k == KeyEvent.KEYCODE_DPAD_DOWN
                when (k) {
                    KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN -> {
                        val dirn = if (k == KeyEvent.KEYCODE_DPAD_UP) 1 else -1
                        mins = if (i == 1) { val h = mins / 60; h * 60 + ((mins % 60 + dirn * step) % 60 + 60) % 60 } else ((mins + dirn * step) % 1440 + 1440) % 1440
                        paint(); Fx.tick()
                        tv.animate().translationY(-dirn * dpf(5f)).setDuration(70).withEndAction { tv.animate().translationY(0f).setDuration(160).setInterpolator(appleOut).start() }.start()
                        true
                    }
                    else -> false
                }
            }
            tv.setOnClickListener { confirm() }
            texts.add(tv)
            box.addView(up, LinearLayout.LayoutParams(-2, dp(18)))
            box.addView(tv, LinearLayout.LayoutParams(dp(if (i == 2) 84 else 96), dp(84)))
            box.addView(down, LinearLayout.LayoutParams(-2, dp(18)))
            wheels.addView(box, LinearLayout.LayoutParams(-2, -2).apply { leftMargin = dp(if (i == 0) 0 else if (i == 1) 4 else 14) })
            if (i == 0) wheels.addView(TextView(this).apply { text = ":"; typeface = Fonts.displayBold; textSize = 34f; setTextColor(0xB3FFFFFF.toInt()); setPadding(dp(6), 0, dp(2), dp(4)) })
        }
        paint()
        col.addView(wheels)
        val btns = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; clipChildren = false; setPadding(0, dp(12), 0, 0) }
        val (set, _) = panelRow("Next") { confirm() }
        val (cancel, _) = panelRow("Cancel") { closeList() }
        btns.addView(set, LinearLayout.LayoutParams(dp(150), dp(44)).apply { rightMargin = dp(10) }); btns.addView(cancel, LinearLayout.LayoutParams(dp(150), dp(44)))
        col.addView(btns)
        col.addView(TextView(this).apply { text = "\u25B2 \u25BC change   \u00b7   \u25C0 \u25B6 move   \u00b7   works even when the TV is off"; typeface = Fonts.medium; textSize = 11.5f
            setTextColor(0x73FFFFFF); setPadding(0, dp(14), 0, 0) })
        g.addView(col, FrameLayout.LayoutParams(-2, -2))
        root.addView(g, FrameLayout.LayoutParams(-2, -2, Gravity.CENTER))
        g.alpha = 0f; g.scaleX = 0.92f; g.scaleY = 0.92f
        g.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(380).setInterpolator(appleOut).start()
        page.descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
        setNavFocusable(false)
        listPanel = g
        texts[0].requestFocus()
    }

    // ================= folders (tvOS: glass tiles that open as a frosted sheet) =================
    private var folderSheet: View? = null
    private var folderFocus: View? = null
    private val folderNames = listOf("Movies & TV", "News", "Live TV", "Kids", "Sports", "Music", "Utilities", "Games")

    /** Glass tile art: up to four member apps in a 2×2 grid on frosted white. */
    private fun folderArt(members: List<App>): Drawable {
        val w = 640; val h = 384; val bm = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888); val c = Canvas(bm)
        c.drawRoundRect(RectF(0f, 0f, w.toFloat(), h.toFloat()), 46f, 46f, Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = LinearGradient(0f, 0f, 0f, h.toFloat(), 0x59FFFFFF, 0x26FFFFFF, Shader.TileMode.CLAMP) })
        val pad = 34f; val gap = 22f; val cw = (w - pad * 2 - gap) / 2; val ch = (h - pad * 2 - gap) / 2
        members.take(4).forEachIndexed { i, a ->
            val x = pad + (i % 2) * (cw + gap); val y = pad + (i / 2) * (ch + gap)
            val r = RectF(x, y, x + cw, y + ch)
            val save = c.save()
            c.clipPath(Path().apply { addRoundRect(r, 22f, 22f, Path.Direction.CW) })
            if (a.isBanner) { a.art.constantState?.newDrawable()?.mutate()?.apply { setBounds(r.left.toInt(), r.top.toInt(), r.right.toInt(), r.bottom.toInt()); draw(c) } }
            else {
                c.drawRect(r, Paint().apply { shader = LinearGradient(0f, r.top, 0f, r.bottom, a.tile?.get(0) ?: 0xFF4A4E5A.toInt(), a.tile?.get(1) ?: 0xFF25272E.toInt(), Shader.TileMode.CLAMP) })
                val s = (ch * 0.6f).toInt(); val cx = r.centerX().toInt(); val cy = r.centerY().toInt()
                a.art.constantState?.newDrawable()?.mutate()?.apply { setBounds(cx - s / 2, cy - s / 2, cx + s / 2, cy + s / 2); draw(c) }
            }
            c.restoreToCount(save)
        }
        return BitmapDrawable(resources, bm)
    }

    private fun chooseFolder(a: App, pos: Int) {
        val folders = Folders.all(this)
        showListPanel("Move ${a.label} to\u2026", null) { col, rowsV ->
            for (fo in folders) {
                val (r, _) = panelRow(fo.name) { closeList(); Folders.add(this, fo.id, a.pkg); reloadApps(); Kit.toast(this, "${a.label} \u2192 ${fo.name}") }
                col.addView(r, LinearLayout.LayoutParams(-1, dp(42)).apply { topMargin = dp(6) }); rowsV.add(r)
            }
            val (nf, _) = panelRow("New folder\u2026") {
                closeList()
                pickFolderName("Name the new folder") { n -> Folders.create(this, n, a.pkg, pos); reloadApps(); Kit.toast(this, "Folder \u201c$n\u201d created") }
            }
            col.addView(nf, LinearLayout.LayoutParams(-1, dp(42)).apply { topMargin = dp(6) }); rowsV.add(nf)
            val (cancel, _) = panelRow("Cancel") { closeList() }
            col.addView(cancel, LinearLayout.LayoutParams(-1, dp(42)).apply { topMargin = dp(12) }); rowsV.add(cancel)
        }
    }

    private fun pickFolderName(title: String, done: (String) -> Unit) {
        showListPanel(title, null) { col, rowsV ->
            for (n in folderNames) {
                val (r, _) = panelRow(n) { closeList(); done(n) }
                col.addView(r, LinearLayout.LayoutParams(-1, dp(40)).apply { topMargin = dp(5) }); rowsV.add(r)
            }
        }
    }

    private fun openFolder(id: String) {
        val fo = Folders.all(this).firstOrNull { it.id == id } ?: return
        val members = fo.pkgs.mapNotNull { p -> apps.firstOrNull { it.pkg == p } }
        if (members.isEmpty()) { Folders.delete(this, id); reloadApps(); return }
        folderFocus = currentFocus
        val sheet = FrameLayout(this).apply { isClickable = true }
        val back = object : View(this) { override fun onDraw(c: Canvas) { hero.drawBackdrop(c); c.drawColor(0x8C000000.toInt()) } }
        hero.glass.add(back)
        sheet.addView(back, FrameLayout.LayoutParams(-1, -1))
        val panel = GlassView(this, hero, dpf(34f)).apply { clipChildren = false }
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL; clipChildren = false; clipToPadding = false
            setPadding(dp(34), dp(26), dp(34), dp(22)) }
        col.addView(TextView(this).apply { text = fo.name; typeface = Fonts.display; textSize = 26f; setTextColor(Color.WHITE); gravity = Gravity.CENTER })
        val perRow = minOf(4, members.size)
        val grid = GridLayout(this).apply { columnCount = perRow; clipChildren = false; clipToPadding = false; setPadding(0, dp(12), 0, 0) }
        var first: View? = null
        for (a in members) {
            val c = card(a, -1)
            c.setOnLongClickListener {
                Folders.remove(this, id, a.pkg); closeFolder(); reloadApps(); Kit.toast(this, "${a.label} moved out of ${fo.name}"); true
            }
            grid.addView(c, GridLayout.LayoutParams().apply { width = cardW; setMargins(dp(14), dp(6), dp(14), dp(6)) })
            if (first == null) first = c
        }
        col.addView(grid)
        col.addView(TextView(this).apply { text = "OK  open      \u00b7      Hold OK  move out of folder"; typeface = Fonts.medium; textSize = 12f; gravity = Gravity.CENTER
            setTextColor(0x80FFFFFF.toInt()); setPadding(0, dp(34), 0, 0) })
        panel.addView(col, FrameLayout.LayoutParams(-2, -2))
        sheet.addView(panel, FrameLayout.LayoutParams(-2, -2, Gravity.CENTER))
        root.addView(sheet, FrameLayout.LayoutParams(-1, -1))
        sheet.alpha = 0f; panel.scaleX = 0.86f; panel.scaleY = 0.86f
        sheet.animate().alpha(1f).setDuration(260).setInterpolator(appleEase).start()
        panel.animate().scaleX(1f).scaleY(1f).setDuration(460).setInterpolator(appleOut).start()
        page.descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
        setNavFocusable(false)
        folderSheet = sheet
        first?.requestFocus()
    }

    private fun closeFolder() {
        val sh = folderSheet ?: return
        folderSheet = null
        (sh as ViewGroup).getChildAt(0)?.let { hero.glass.remove(it) }
        sh.animate().alpha(0f).setDuration(200).withEndAction { root.removeView(sh) }.start()
        page.descendantFocusability = ViewGroup.FOCUS_AFTER_DESCENDANTS
        setNavFocusable(true)
        (folderFocus?.takeIf { it.isAttachedToWindow } ?: rows.firstOrNull()?.getChildAt(0))?.requestFocus()
    }

    // ================= rearranging =================
    private val prefs by lazy { getSharedPreferences("launcher", Context.MODE_PRIVATE) }
    private val cells = ArrayList<View>()   // grid cards, in app order
    private var moving: View? = null
    private var reflowing = false
    private var cardMenu: GlassView? = null
    private var menuAnchor: View? = null
    private var moveHint: View? = null

    private fun openAppInfo(a: App) = startActivity(
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${a.pkg}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    )

    /** Hold OK on a tile: small glass menu anchored to the card. */
    private fun showCardMenu(a: App, cell: View, frame: View) {
        closeCardMenu(restore = false)
        val g = GlassView(this, hero, dpf(20f))
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(8), dp(8), dp(8), dp(8)) }
        val opts = ArrayList<FrameLayout>()
        val (mv, _) = panelRow("Move") { closeCardMenu(restore = false); startMove(cell) }
        opts.add(mv); col.addView(mv, LinearLayout.LayoutParams(-1, dp(40)))
        if (a.pkg.startsWith("folder:")) {
            val id = a.pkg.removePrefix("folder:")
            val (rn, _) = panelRow("Rename") { closeCardMenu(restore = false); pickFolderName("Rename \u201c${a.label}\u201d") { n -> Folders.rename(this, id, n); reloadApps() } }
            val (ug, _) = panelRow("Ungroup") { closeCardMenu(restore = false); Folders.delete(this, id); reloadApps(); Kit.toast(this, "${a.label} ungrouped") }
            for (o in listOf(rn, ug)) { opts.add(o); col.addView(o, LinearLayout.LayoutParams(-1, dp(40)).apply { topMargin = dp(6) }) }
        }
        if (!a.builtin) {
            val (rt, _) = panelRow("Open at a time\u2026") { closeCardMenu(restore = false); scheduleRoutine(a.pkg, "", a.label) }
            opts.add(rt); col.addView(rt, LinearLayout.LayoutParams(-1, dp(40)).apply { topMargin = dp(6) })
        }
        if (!a.builtin && a.pkg !in favPkgs()) {
            val (tf, _) = panelRow("Move to folder") { closeCardMenu(restore = false); chooseFolder(a, cells.indexOf(cell).coerceAtLeast(0)) }
            opts.add(tf); col.addView(tf, LinearLayout.LayoutParams(-1, dp(40)).apply { topMargin = dp(6) })
        }
        if (!a.builtin) {
            val locked = a.pkg in AppLock.locked(this)
            val isFav = a.pkg in favPkgs()
            val (fav, _) = panelRow(if (isFav) "Remove from Favourites" else "Add to Favourites") { closeCardMenu(restore = false); toggleFavourite(a) }
            opts.add(fav); col.addView(fav, LinearLayout.LayoutParams(-1, dp(40)).apply { topMargin = dp(6) })
            val (hide, _) = panelRow("Hide") {
                closeCardMenu(restore = false); AppLock.setHidden(this, a.pkg, true); reloadApps()
                Kit.toast(this, "${a.label} hidden \u00b7 Control Center \u203a Hidden apps", long = true)
            }
            val (lock, _) = panelRow(if (locked) "Remove PIN lock" else "Lock with PIN") {
                closeCardMenu()
                withPin(if (locked) "Enter PIN to unlock ${a.label}" else "Enter PIN to lock ${a.label}") {
                    AppLock.setLocked(this, a.pkg, !locked)
                    Kit.toast(this, if (locked) "${a.label} unlocked" else "${a.label} now needs your PIN")
                }
            }
            val (info, _) = panelRow("App info") { closeCardMenu(); openAppInfo(a) }
            for (o in listOf(hide, lock, info)) { opts.add(o); col.addView(o, LinearLayout.LayoutParams(-1, dp(40)).apply { topMargin = dp(6) }) }
        }
        opts.forEach { it.id = View.generateViewId(); it.nextFocusLeftId = it.id; it.nextFocusRightId = it.id }
        opts.first().nextFocusUpId = opts.first().id; opts.last().nextFocusDownId = opts.last().id
        g.addView(col, FrameLayout.LayoutParams(-1, -2))

        // below the card if it fits, otherwise above it
        val loc = IntArray(2); frame.getLocationInWindow(loc)
        val menuH = dp(16 + 40 + 46 * (opts.size - 1))
        val x = loc[0].coerceIn(dp(48), resources.displayMetrics.widthPixels - dp(48 + 200))
        val below = loc[1] + (frame.height * 1.15f).toInt() + dp(18)
        val y = if (below + menuH < resources.displayMetrics.heightPixels - dp(20)) below else loc[1] - menuH - dp(14)
        root.addView(g, FrameLayout.LayoutParams(dp(200), -2).apply { leftMargin = x; topMargin = y })
        g.pivotX = dpf(40f); g.pivotY = if (y > loc[1]) 0f else menuH.toFloat()
        g.alpha = 0f; g.scaleX = 0.9f; g.scaleY = 0.9f
        g.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(300).setInterpolator(appleOut).start()

        cardMenu = g; menuAnchor = cell
        page.descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
        setNavFocusable(false)
        mv.requestFocus()
    }

    private fun closeCardMenu(restore: Boolean = true) {
        val g = cardMenu ?: return
        cardMenu = null
        g.animate().alpha(0f).scaleX(0.95f).scaleY(0.95f).setDuration(160).withEndAction { root.removeView(g) }.start()
        page.descendantFocusability = ViewGroup.FOCUS_AFTER_DESCENDANTS
        setNavFocusable(true)
        if (restore) menuAnchor?.requestFocus()
    }

    private fun startMove(cell: View) {
        tiltFrame?.let { it.rotationX = 0f; it.rotationY = 0f }
        tiltFrame = null; tiltGlare = null
        moving = cell
        main.removeCallbacks(switchRun)
        cell.requestFocus()
        val frame = (cell as ViewGroup).getChildAt(0)
        frame.animate().scaleX(1.2f).scaleY(1.2f).translationZ(dpf(34f)).setDuration(260).setInterpolator(appleOut).start()

        val hint = GlassView(this, hero, dpf(20f))
        hint.addView(TextView(this).apply {
            text = "\u25C0 \u25B6 \u25B2 \u25BC  move     \u00b7     OK  done"; typeface = Fonts.semibold; textSize = 15f
            setTextColor(Color.WHITE); gravity = Gravity.CENTER; setPadding(dp(22), 0, dp(22), 0)
        }, FrameLayout.LayoutParams(-2, -1))
        root.addView(hint, FrameLayout.LayoutParams(-2, dp(40), Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply { bottomMargin = dp(30) })
        hint.alpha = 0f; hint.translationY = dpf(16f)
        hint.animate().alpha(1f).translationY(0f).setDuration(320).setInterpolator(appleOut).start()
        moveHint = hint
    }

    private fun moveBy(delta: Int) {
        val m = moving ?: return
        if (m in favCells) {
            if (Math.abs(delta) != 1) return
            val from = favCells.indexOf(m); val to = from + delta
            if (to < 0 || to >= favCells.size) return
            val favRow = rows[0]
            val before = favCells.associateWith { v -> IntArray(2).also { v.getLocationInWindow(it) } }
            reflowing = true
            favCells.add(to, favCells.removeAt(from))
            favRow.removeAllViews()
            favCells.forEachIndexed { i, c -> favRow.addView(c, LinearLayout.LayoutParams(cardW, -2).apply { if (i < favCells.size - 1) rightMargin = gap }) }
            reflowing = false
            saveFavs(favCells.mapNotNull { (it.tag as? App)?.pkg })
            page.viewTreeObserver.addOnPreDrawListener(object : ViewTreeObserver.OnPreDrawListener {
                override fun onPreDraw(): Boolean {
                    page.viewTreeObserver.removeOnPreDrawListener(this)
                    val now = IntArray(2)
                    for ((v, old) in before) { v.getLocationInWindow(now); val dx = (old[0] - now[0]).toFloat()
                        if (dx != 0f) { v.translationX = dx; v.animate().translationX(0f).setDuration(340).setInterpolator(appleOut).start() } }
                    return true
                }
            })
            m.requestFocus(); return
        }
        val from = cells.indexOf(m)
        var to = from + delta
        if (to < 0 || to >= cells.size) {
            if (Math.abs(delta) == cols && to >= cells.size && from / cols < (cells.size - 1) / cols) to = cells.size - 1 // down into a short last row
            else return
        }
        flip {
            val ma = cells[from].tag as? App; val ta = cells[to].tag as? App
            cells.add(to, cells.removeAt(from))
            if (ma != null && ta != null) apps = apps.toMutableList().apply { val ti = indexOf(ta); remove(ma); add(ti.coerceIn(0, size), ma) }
            reflow()
        }
        m.requestFocus()
        // keep the page scrolled to whichever row the card now sits in
        val r = rows.indexOf(m.parent)
        if (r >= 0) {
            pager.glide(if (r == 0) 0 else (rows[r].top - dp(GRID_TOP)).coerceAtLeast(0))
            hero.setCollapsed(r > 0)
            focusedFrame = (m as ViewGroup).getChildAt(0)
        }
    }

    /** Re-deal the existing card views into rows of [cols]; views are reused, never rebuilt. */
    private fun reflow() {
        reflowing = true
        // detach everything first: a card moving between rows must not still belong to its old row
        rows.drop(1).forEach { it.removeAllViews() }
        cells.chunked(cols).forEachIndexed { r, chunk ->
            chunk.forEachIndexed { i, c -> rows[r + 1].addView(c, LinearLayout.LayoutParams(cardW, -2).apply { if (i < cols - 1) rightMargin = gap }) }
        }
        reflowing = false
    }

    /** FLIP animation: every card glides from where it was to where the new layout puts it. */
    private fun flip(change: () -> Unit) {
        val before = cells.associateWith { v -> IntArray(2).also { v.getLocationInWindow(it) } }
        change()
        page.viewTreeObserver.addOnPreDrawListener(object : ViewTreeObserver.OnPreDrawListener {
            override fun onPreDraw(): Boolean {
                page.viewTreeObserver.removeOnPreDrawListener(this)
                val now = IntArray(2)
                for ((v, old) in before) {
                    v.getLocationInWindow(now)
                    val dx = (old[0] - now[0]).toFloat(); val dy = (old[1] - now[1]).toFloat()
                    if (dx != 0f || dy != 0f) {
                        v.translationX = dx; v.translationY = dy
                        v.animate().translationX(0f).translationY(0f).setDuration(340).setInterpolator(appleOut).start()
                    }
                }
                return true
            }
        })
    }

    private fun endMove(save: Boolean) {
        val m = moving ?: return
        moving = null
        val frame = (m as ViewGroup).getChildAt(0)
        frame.animate().rotation(0f).scaleX(1.15f).scaleY(1.15f).translationZ(dpf(26f)).setDuration(260).setInterpolator(appleOut).start()
        moveHint?.let { h -> h.animate().alpha(0f).translationY(dpf(16f)).setDuration(200).withEndAction { root.removeView(h) }.start() }
        moveHint = null
        if (save) prefs.edit().putString("order", apps.joinToString("|") { it.pkg }).apply()
        (m.tag as? App)?.takeIf { it.pkg.startsWith("folder:") }?.let { Folders.setPos(this, it.pkg.removePrefix("folder:"), cells.indexOf(m).coerceAtLeast(0)) }
        // let the Top Shelf follow whatever now sits under the focus
        (m.tag as? App)?.let { a -> pendingSource = sourceFor(a.pkg); main.removeCallbacks(switchRun); main.postDelayed(switchRun, 300) }
    }

    // ================= Spotlight search =================
    private lateinit var sOverlay: FrameLayout
    private lateinit var sCol: LinearLayout
    private lateinit var sField: EditText
    private lateinit var sHint: TextView
    private lateinit var sAppsLabel: TextView
    private lateinit var sAppsRow: LinearLayout
    private lateinit var sResults: LinearLayout
    private var restoreFocus: View? = null
    private val searchRun = Runnable { runSearch() }
    private var searchSeq = 0
    private var focusResults = false   // user hit Enter: move into results as soon as they land
    private val imm by lazy { getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager }

    private fun buildSpotlight() {
        sOverlay = FrameLayout(this).apply { visibility = View.GONE; isClickable = true }
        val backdrop = object : View(this) {
            override fun onDraw(c: Canvas) { hero.drawBackdrop(c); c.drawColor(0x99000000.toInt()) }
        }
        hero.glass.add(backdrop)
        sOverlay.addView(backdrop, FrameLayout.LayoutParams(-1, -1))

        sCol = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; clipChildren = false; clipToPadding = false; setPadding(dp(64), dp(48), dp(64), 0) }
        val field = GlassView(this, hero, dpf(28f))
        val fr = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(dp(24), 0, dp(24), 0) }
        val mag = ImageView(this).apply { setImageDrawable(magnifier(0xCCFFFFFF.toInt())) }
        sField = EditText(this).apply {
            background = null; setTextColor(Color.WHITE); setHintTextColor(0x80FFFFFF.toInt()); typeface = Fonts.medium; textSize = 22f
            hint = "Search apps, movies and shows"; isSingleLine = true; setPadding(dp(14), 0, 0, 0)
            inputType = InputType.TYPE_CLASS_TEXT; imeOptions = EditorInfo.IME_ACTION_SEARCH or EditorInfo.IME_FLAG_NO_EXTRACT_UI
            showSoftInputOnFocus = false; isFocusable = false   // typing happens on our keyboard strip or the phone
        }
        fr.addView(mag, LinearLayout.LayoutParams(dp(20), dp(20)))
        fr.addView(sField, LinearLayout.LayoutParams(0, -1, 1f))
        micBtn = FrameLayout(this).apply { isFocusable = true; isClickable = true }
        val micBg = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(0x26FFFFFF) }
        micBtn.background = micBg
        val micIv = ImageView(this).apply { setImageDrawable(micIcon(Color.WHITE)) }
        micBtn.addView(micIv, FrameLayout.LayoutParams(dp(20), dp(20), Gravity.CENTER))
        micBtn.setOnFocusChangeListener { _, f ->
            if (!listening) { micBg.setColor(if (f) 0xF2FFFFFF.toInt() else 0x26FFFFFF); micIv.setImageDrawable(micIcon(if (f) 0xFF111111.toInt() else Color.WHITE)) }
            micBtn.animate().scaleX(if (f) 1.1f else 1f).scaleY(if (f) 1.1f else 1f).setDuration(220).start()
        }
        micBtn.setOnClickListener { if (listening) stopVoice() else startVoice() }
        micPaint = { on -> micBg.setColor(if (on) 0xFFFF375F.toInt() else if (micBtn.isFocused) 0xF2FFFFFF.toInt() else 0x26FFFFFF)
            micIv.setImageDrawable(micIcon(if (on) Color.WHITE else if (micBtn.isFocused) 0xFF111111.toInt() else Color.WHITE)) }
        fr.addView(micBtn, LinearLayout.LayoutParams(dp(42), dp(42)).apply { leftMargin = dp(10) })
        field.addView(fr, FrameLayout.LayoutParams(-1, -1))
        sCol.addView(field, LinearLayout.LayoutParams(-1, dp(56)))
        kbWrap = buildKeyboard()
        sCol.addView(kbWrap, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(14); bottomMargin = dp(6) })

        // results scroll under the fixed search field; ScrollView keeps the focused row on screen
        // hard clip at the results area (lifted cards can't escape over the keyboard), with room inside it so a
        // focused card's growth never touches the clip edge; the area is pulled out by the same amount to stay aligned
        val scroll = ScrollView(this).apply {
            isVerticalScrollBarEnabled = false; overScrollMode = View.OVER_SCROLL_NEVER
            outlineProvider = ViewOutlineProvider.BOUNDS; clipToOutline = true
        }
        resultsScroll = scroll
        val body = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; clipChildren = false; clipToPadding = false; setPadding(dp(20), dp(10), dp(20), dp(90)) }
        sHint = TextView(this).apply {
            typeface = Fonts.medium; textSize = 16f; setTextColor(0x99FFFFFF.toInt()); setLineSpacing(0f, 1.2f)
            text = "Find your apps, and see which of your streaming apps\nhas any movie or show."
            setPadding(dp(6), dp(14), 0, 0)
        }
        body.addView(sHint)
        sAppsLabel = sectionLabel("Apps"); body.addView(sAppsLabel)
        sAppsRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; clipChildren = false; clipToPadding = false; setPadding(0, dp(4), dp(40), dp(4)) }
        body.addView(hscroll(sAppsRow))
        sAppsLabel.visibility = View.GONE
        sResults = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; clipChildren = false; clipToPadding = false }
        body.addView(sResults)
        scroll.addView(body)
        sCol.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f).apply { leftMargin = -dp(20); rightMargin = -dp(20) })

        sOverlay.addView(sCol, FrameLayout.LayoutParams(-1, -1))
        root.addView(sOverlay, FrameLayout.LayoutParams(-1, -1))

        sField.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: Editable?) {
                updateAppResults(s.toString().trim())
                main.removeCallbacks(searchRun); main.postDelayed(searchRun, 450)
            }
        })
        sField.setOnEditorActionListener { _, _, _ ->
            imm.hideSoftInputFromWindow(sField.windowToken, 0)
            main.removeCallbacks(searchRun); focusResults = true; runSearch()
            firstResult()?.let { focusResults = false; it.requestFocus() }; true
        }
    }

    // ---- Apple TV-style on-screen keyboard: a slim strip under the field, results stay visible ----
    private var firstKey: View? = null
    private lateinit var typeQr: ImageView
    private lateinit var resultsScroll: ScrollView
    private lateinit var kbWrap: View
    private var kbCollapsed = false
    private var kbAnim: ValueAnimator? = null
    private var kbFullH = 0

    /** The horizontal row (HorizontalScrollView) a results card lives in. */
    private fun rowOf(v: View?): View? { var p: View? = v; while (p != null && p !is HorizontalScrollView) p = p.parent as? View; return p }
    private fun inResults(v: View?): Boolean { var p: View? = v; while (p != null) { if (p === resultsScroll) return true; p = p.parent as? View }; return false }

    /** Results get the whole screen while browsing them; the keyboard + QR fold up and come back on Up. */
    private fun setKeyboardCollapsed(c: Boolean) {
        if (c == kbCollapsed) return
        kbCollapsed = c
        if (kbFullH == 0) kbFullH = kbWrap.height
        val lp = kbWrap.layoutParams as LinearLayout.LayoutParams
        val from = if (lp.height >= 0) lp.height else kbWrap.height
        val to = if (c) 0 else kbFullH
        kbAnim?.cancel()
        kbAnim = ValueAnimator.ofInt(from, to).apply {
            duration = 380; interpolator = appleOut
            addUpdateListener { lp.height = it.animatedValue as Int; lp.topMargin = (dp(14) * lp.height / kbFullH.coerceAtLeast(1)); kbWrap.layoutParams = lp }
            addListener(object : android.animation.AnimatorListenerAdapter() {
                override fun onAnimationEnd(a: android.animation.Animator) { if (!c) { lp.height = LinearLayout.LayoutParams.WRAP_CONTENT; kbWrap.layoutParams = lp } }
            })
            start()
        }
        kbWrap.animate().alpha(if (c) 0f else 1f).setDuration(if (c) 180 else 320).start()
    }

    /** Moving into a results row: scroll so that row's app header sits fully visible above it. */
    private fun revealSection(cell: View) {
        var v: View? = cell
        while (v != null && v.parent !== sResults && v.parent !== sAppsRow.parent) v = v.parent as? View
        val hs = v ?: return
        val parent = hs.parent as? ViewGroup ?: return
        val i = parent.indexOfChild(hs)
        val header = if (i > 0) parent.getChildAt(i - 1) else hs
        var y = header.top; var p = parent as View
        while (p !== resultsScroll && p.parent is View) { y += p.top; p = p.parent as View }
        resultsScroll.smoothScrollTo(0, (y - dp(4)).coerceAtLeast(0))
    }

    private fun key(label: String, w: Int, onPress: () -> Unit): View = TextView(this).apply {
        text = label; typeface = Fonts.semibold; textSize = if (label.length > 1) 13f else 16f; gravity = Gravity.CENTER
        setTextColor(0xE6FFFFFF.toInt()); isFocusable = true; isClickable = true
        val bg = GradientDrawable().apply { cornerRadius = dpf(10f); setColor(0x1AFFFFFF) }
        background = bg
        layoutParams = LinearLayout.LayoutParams(dp(w), dp(34)).apply { rightMargin = dp(6) }
        setOnFocusChangeListener { v, f ->
            if (f) { v.allowOverflow(3); setKeyboardCollapsed(false) }
            bg.setColor(if (f) 0xF2FFFFFF.toInt() else 0x1AFFFFFF); setTextColor(if (f) 0xFF111111.toInt() else 0xE6FFFFFF.toInt())
            animate().scaleX(if (f) 1.18f else 1f).scaleY(if (f) 1.18f else 1f).translationZ(if (f) dpf(8f) else 0f).setDuration(160).setInterpolator(appleOut).start()
        }
        setOnClickListener { onPress() }
    }

    private fun typeChar(c: String) { val t = sField.text.toString() + c; sField.setText(t); sField.setSelection(t.length) }

    private fun buildKeyboard(): View {
        val wrap = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; clipChildren = false; clipToPadding = false }
        val panel = GlassView(this, hero, dpf(20f)).apply { clipChildren = false; clipToPadding = false }
        val keys = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; clipChildren = false; clipToPadding = false; setPadding(dp(12), dp(12), dp(6), dp(6)) }
        fun row(items: List<View>) = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; clipChildren = false; clipToPadding = false; setPadding(0, 0, 0, dp(6))
            items.forEach { addView(it) }
        }
        val del = { val t = sField.text.toString().dropLast(1); sField.setText(t); sField.setSelection(t.length) }
        // every row is 13 key-widths wide: a–m · n–z · 0–9 + delete + space
        val r1 = "abcdefghijklm".map { c -> key(c.toString(), 34) { typeChar(c.toString()) } }
        val r2 = "nopqrstuvwxyz".map { c -> key(c.toString(), 34) { typeChar(c.toString()) } }
        val delKey = key("\u232B", 34) { del() }.apply { setOnLongClickListener { sField.setText(""); Kit.toast(this@MainActivity, "Cleared"); true } }
        val r3 = "1234567890".map { c -> key(c.toString(), 34) { typeChar(c.toString()) } } + delKey + key("space", 74) { typeChar(" ") }
        keys.addView(row(r1)); keys.addView(row(r2)); keys.addView(row(r3))
        firstKey = r1.first()
        panel.addView(keys, FrameLayout.LayoutParams(-2, -2))
        wrap.addView(panel, LinearLayout.LayoutParams(-2, -2))
        wrap.addView(View(this), LinearLayout.LayoutParams(0, 0, 1f))

        // "Type with your phone": QR straight into the typing box of the phone page
        val card = GlassView(this, hero, dpf(20f))
        val cr = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(dp(12), dp(12), dp(16), dp(12)) }
        typeQr = ImageView(this).apply { background = GradientDrawable().apply { cornerRadius = dpf(8f); setColor(Color.WHITE) }; setPadding(dp(5), dp(5), dp(5), dp(5)) }
        cr.addView(typeQr, LinearLayout.LayoutParams(dp(98), dp(98)))
        val ct = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(14), 0, 0, 0) }
        ct.addView(TextView(this).apply { text = "Type with\nyour phone"; typeface = Fonts.semibold; textSize = 15f; setTextColor(Color.WHITE); setLineSpacing(0f, 1.1f) })
        ct.addView(TextView(this).apply { text = "Scan with the camera"; typeface = Fonts.regular; textSize = 12f; setTextColor(0x99FFFFFF.toInt()); setPadding(0, dp(6), 0, 0) })
        cr.addView(ct)
        card.addView(cr, FrameLayout.LayoutParams(-2, -2))
        wrap.addView(card, LinearLayout.LayoutParams(-2, -2))
        return wrap
    }

    private fun refreshTypeQr() {
        val ip = NetInfo.read(this).ip ?: return
        typeQr.setImageBitmap(qr(ShareHub.dropUrl(ip) + "&t=1", dp(88)))
    }

    private fun firstResult(): View? {
        sAppsRow.getChildAt(0)?.let { if (sAppsLabel.visibility == View.VISIBLE) return it }
        for (i in 0 until sResults.childCount) {
            val hs = sResults.getChildAt(i) as? HorizontalScrollView ?: continue
            (hs.getChildAt(0) as? ViewGroup)?.getChildAt(0)?.let { return it }
        }
        return null
    }

    private fun sectionLabel(t: CharSequence) = TextView(this).apply {
        text = t; typeface = Fonts.semibold; textSize = 17f; setTextColor(0xE6FFFFFF.toInt()); setPadding(dp(4), dp(24), 0, dp(2))
    }

    private fun resultLabel(t: CharSequence) = sectionLabel(t).apply { setPadding(dp(6), dp(20), 0, dp(4)) }

    private fun statusText(t: String) = TextView(this).apply {
        text = t; typeface = Fonts.medium; textSize = 15f; setTextColor(0x99FFFFFF.toInt()); setPadding(dp(6), dp(24), 0, 0)
    }

    private fun hscroll(row: LinearLayout) = HorizontalScrollView(this).apply {
        isHorizontalScrollBarEnabled = false; overScrollMode = View.OVER_SCROLL_NEVER; clipChildren = false; clipToPadding = false
        addView(row)
    }

    private fun openSpotlight() {
        if (sOverlay.visibility == View.VISIBLE) return
        restoreFocus = currentFocus
        page.descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
        setNavFocusable(false)
        sOverlay.visibility = View.VISIBLE; sOverlay.alpha = 0f
        sCol.scaleX = 1.06f; sCol.scaleY = 1.06f; sCol.translationY = dpf(-12f)
        sOverlay.animate().alpha(1f).setDuration(320).setInterpolator(appleEase).start()
        sCol.animate().scaleX(1f).scaleY(1f).translationY(0f).setDuration(520).setInterpolator(appleOut).start()
        kbAnim?.cancel(); kbCollapsed = false; kbWrap.alpha = 1f
        (kbWrap.layoutParams as LinearLayout.LayoutParams).apply { height = LinearLayout.LayoutParams.WRAP_CONTENT; topMargin = dp(14) }; kbWrap.requestLayout()
        sField.setText("")   // every search starts fresh (phone typing fills it right after, if that's what opened it)
        firstKey?.requestFocus()
        refreshTypeQr()
    }

    // ---- voice search (Google's recognizer on the TV, fed by the remote's mic) ----
    private lateinit var micBtn: FrameLayout
    private var micPaint: (Boolean) -> Unit = {}
    private var recognizer: android.speech.SpeechRecognizer? = null
    private var listening = false
    private var micPulse: ValueAnimator? = null

    private fun startVoice() {
        if (!android.speech.SpeechRecognizer.isRecognitionAvailable(this)) { Kit.toast(this, "Voice search isn't available on this TV"); return }
        stopVoice()
        val r = android.speech.SpeechRecognizer.createSpeechRecognizer(this)
        recognizer = r
        r.setRecognitionListener(object : android.speech.RecognitionListener {
            override fun onReadyForSpeech(p: Bundle?) { sField.hint = "Listening\u2026" }
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(v: Float) { micBtn.scaleX = 1.1f + (v.coerceIn(0f, 10f) / 60f); micBtn.scaleY = micBtn.scaleX }
            override fun onBufferReceived(b: ByteArray?) {}
            override fun onEndOfSpeech() { sField.hint = "Searching\u2026" }
            override fun onError(e: Int) { stopVoice(); if (e != android.speech.SpeechRecognizer.ERROR_CLIENT) Kit.toast(this@MainActivity, "Didn't catch that \u00b7 try again") }
            override fun onPartialResults(b: Bundle?) { b?.getStringArrayList(android.speech.SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.let { sField.setText(it); sField.setSelection(it.length) } }
            override fun onResults(b: Bundle?) {
                val text = b?.getStringArrayList(android.speech.SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
                stopVoice()
                if (!text.isNullOrBlank()) { sField.setText(text); sField.setSelection(text.length); main.removeCallbacks(searchRun); focusResults = true; runSearch() }
            }
            override fun onEvent(t: Int, p: Bundle?) {}
        })
        r.startListening(Intent(android.speech.RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            .putExtra(android.speech.RecognizerIntent.EXTRA_LANGUAGE_MODEL, android.speech.RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            .putExtra(android.speech.RecognizerIntent.EXTRA_PARTIAL_RESULTS, true))
        listening = true; micPaint(true)
        imm.hideSoftInputFromWindow(sField.windowToken, 0)
    }

    private fun stopVoice() {
        listening = false
        recognizer?.let { try { it.cancel(); it.destroy() } catch (e: Exception) {} }
        recognizer = null
        if (::micBtn.isInitialized) { micBtn.scaleX = 1f; micBtn.scaleY = 1f; micPaint(false); sField.hint = "Search apps, movies and shows" }
    }

    private fun closeSpotlight() {
        stopVoice()
        imm.hideSoftInputFromWindow(sField.windowToken, 0)
        page.descendantFocusability = ViewGroup.FOCUS_AFTER_DESCENDANTS
        setNavFocusable(true)
        sOverlay.animate().alpha(0f).setDuration(240).setInterpolator(appleEase).withEndAction { sOverlay.visibility = View.GONE }.start()
        sCol.animate().scaleX(1.04f).scaleY(1.04f).setDuration(240).start()
        (restoreFocus ?: rows.firstOrNull()?.getChildAt(0))?.requestFocus()
    }

    private fun updateAppResults(q: String) {
        sAppsRow.removeAllViews()
        val m = if (q.isEmpty()) emptyList() else apps.filter { it.label.contains(q, ignoreCase = true) }.take(10)
        m.forEach { sAppsRow.addView(card(it, -1), LinearLayout.LayoutParams(cardW, -2).apply { rightMargin = dp(26) }) }
        sAppsLabel.visibility = if (m.isEmpty()) View.GONE else View.VISIBLE
        sHint.visibility = if (q.isEmpty()) View.VISIBLE else View.GONE
        if (q.length < 2) { searchSeq++; sResults.removeAllViews() }
    }

    /** Queries JustWatch (per-service availability) and Stremio in parallel; renders sections as they land. */
    private fun runSearch() {
        val q = sField.text.toString().trim()
        if (q.length < 2) return
        val seq = ++searchSeq
        var where: Map<String, List<Where.Hit>>? = null
        var stremio: List<ShelfItem>? = null
        var pending = 2
        val done = { if (seq == searchSeq) { pending--; renderResults(where, stremio, pending == 0) } }
        sResults.removeAllViews(); sResults.addView(statusText("Searching your streaming apps\u2026"))
        net.go { val w = Where.search(this, q); main.post { where = w; done() } }
        net.go { val st = Shelf.search(q); main.post { stremio = st; done() } }
    }

    private fun renderResults(where: Map<String, List<Where.Hit>>?, stremio: List<ShelfItem>?, finished: Boolean) {
        val hadFocus = sResults.findFocus() != null || focusResults
        sResults.removeAllViews()
        var any = false
        fun section(app: App?, name: String, count: Int, cards: List<View>) {
            any = true
            sResults.addView(resultHeader(app, name, count))
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; clipChildren = false; clipToPadding = false; setPadding(dp(2), dp(6), dp(40), dp(10)) }
            cards.forEach { row.addView(it, LinearLayout.LayoutParams(dp(112), -2).apply { rightMargin = dp(20) }) }
            sResults.addView(hscroll(row))
        }
        // technicalName → installed app (every streaming app matched automatically); services in JustWatch's popularity order
        val techToPkg = jwSrc.entries.associate { it.value.removePrefix("jw:") to it.key }
        val byApp = LinkedHashMap<String, MutableList<Where.Hit>>()
        for (tech in where?.keys.orEmpty()) {
            val hits = where?.get(tech) ?: continue
            val pkg = techToPkg[tech] ?: continue
            val list = byApp.getOrPut(pkg) { ArrayList() }
            hits.forEach { h -> if (list.none { it.title == h.title }) list.add(h) }
        }
        for ((pkg, hits) in byApp) {
            val app = apps.firstOrNull { it.pkg == pkg } ?: continue
            if (hits.isEmpty()) continue
            section(app, app.label, hits.size, hits.take(15).map { h -> posterCard(h.title, if (h.year > 0) "${h.year}" else "", h.poster, onLong = { closeSpotlight(); scheduleRoutine(pkg, h.url, h.title) }) { openUrl(h.url, pkg, app) } })
        }
        stremio?.takeIf { it.isNotEmpty() }?.let { list ->
            section(apps.firstOrNull { it.pkg == "com.stremio.one" }, "Stremio", list.size,
                list.map { it2 -> posterCard(it2.name, listOf(it2.year, if (it2.type == "series") "Series" else "Movie").filter { x -> x.isNotBlank() }.joinToString(" \u00b7 "), it2.poster) { openStremio(it2) } })
        }
        if (!finished) sResults.addView(statusText("Searching your streaming apps\u2026"))
        else if (!any) sResults.addView(statusText("Not streaming on any of your apps."))
        if (hadFocus) firstResult()?.let { focusResults = false; it.requestFocus() }
    }

    private fun openUrl(url: String, pkg: String, app: App) {
        try { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).setPackage(pkg).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
        catch (e: Exception) { launch(app) }   // app doesn't take the link: at least open it
    }

    /** Section header: the service's own logo chip, its name and how many titles matched. */
    private fun resultHeader(app: App?, name: String, count: Int): View {
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(dp(4), dp(18), 0, dp(16)) }
        if (app != null) {
            val r = dpf(6f)
            val chip = FrameLayout(this).apply {
                outlineProvider = object : ViewOutlineProvider() { override fun getOutline(v: View, o: Outline) { o.setRoundRect(0, 0, v.width, v.height, r) } }
                clipToOutline = true
                background = if (app.isBanner) ColorDrawable(0xFF1B1C21.toInt()) else GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, app.tile ?: intArrayOf(0xFF4A4E5A.toInt(), 0xFF25272E.toInt()))
            }
            chip.addView(ImageView(this).apply { setImageDrawable(app.art); scaleType = if (app.isBanner) ImageView.ScaleType.CENTER_CROP else ImageView.ScaleType.FIT_CENTER }, FrameLayout.LayoutParams(-1, -1))
            row.addView(chip, LinearLayout.LayoutParams(dp(48), dp(27)).apply { rightMargin = dp(12) })
        }
        row.addView(TextView(this).apply { text = name; typeface = Fonts.semibold; textSize = 17f; setTextColor(Color.WHITE) })
        row.addView(TextView(this).apply {
            text = "$count"; typeface = Fonts.semibold; textSize = 12f; setTextColor(0xCCFFFFFF.toInt()); gravity = Gravity.CENTER
            background = GradientDrawable().apply { cornerRadius = dpf(10f); setColor(0x26FFFFFF) }; setPadding(dp(8), dp(1), dp(8), dp(1))
        }, LinearLayout.LayoutParams(-2, -2).apply { leftMargin = dp(10) })
        return row
    }

    /** Poster with its title and year always visible underneath (so dark/missing art still reads), lifts on focus. */
    private fun posterCard(title: String, sub: String, poster: String, onLong: (() -> Unit)? = null, onClick: () -> Unit): View {
        val w = dp(112); val h = dp(168); val r = dpf(12f)
        val cell = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; clipChildren = false; clipToPadding = false; isFocusable = true; isClickable = true }
        val frame = FrameLayout(this).apply {
            outlineProvider = object : ViewOutlineProvider() { override fun getOutline(v: View, o: Outline) { o.setRoundRect(0, 0, v.width, v.height, r) } }
            clipToOutline = true; background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(0xFF2E3140.toInt(), 0xFF1A1B22.toInt()))
        }
        val ph = TextView(this).apply {
            text = title; typeface = Fonts.display; textSize = 14f; setTextColor(0xCCFFFFFF.toInt()); gravity = Gravity.CENTER
            setPadding(dp(10), 0, dp(10), 0); maxLines = 4; ellipsize = TextUtils.TruncateAt.END
        }
        frame.addView(ph, FrameLayout.LayoutParams(-1, -1))
        val iv = ImageView(this).apply { scaleType = ImageView.ScaleType.CENTER_CROP }
        frame.addView(iv, FrameLayout.LayoutParams(-1, -1))
        val name = TextView(this).apply {
            text = title; typeface = Fonts.medium; textSize = 13f; setTextColor(0xD9FFFFFF.toInt()); maxLines = 1; ellipsize = TextUtils.TruncateAt.END
            setPadding(dp(2), dp(10), dp(2), 0)
        }
        val meta = TextView(this).apply { text = sub; typeface = Fonts.regular; textSize = 11.5f; setTextColor(0x80FFFFFF.toInt()); maxLines = 1; setPadding(dp(2), dp(1), dp(2), 0) }
        cell.addView(frame, LinearLayout.LayoutParams(w, h)); cell.addView(name, LinearLayout.LayoutParams(w, -2))
        if (sub.isNotBlank()) cell.addView(meta, LinearLayout.LayoutParams(w, -2))
        cell.setOnFocusChangeListener { v, f ->
            if (f) { v.allowOverflow(3); setKeyboardCollapsed(true); v.post { revealSection(v) } }
            frame.animate().scaleX(if (f) 1.1f else 1f).scaleY(if (f) 1.1f else 1f).translationY(if (f) -dpf(6f) else 0f)
                .translationZ(if (f) dpf(22f) else 0f).setDuration(if (f) 420 else 280).setInterpolator(appleOut).start()
            name.animate().translationY(if (f) dpf(6f) else 0f).setDuration(280).start(); meta.animate().translationY(if (f) dpf(6f) else 0f).setDuration(280).start()
            name.setTextColor(if (f) Color.WHITE else 0xD9FFFFFF.toInt()); name.typeface = if (f) Fonts.semibold else Fonts.medium
        }
        cell.setOnClickListener { press(frame, 1.1f) { onClick() } }
        if (onLong != null) cell.setOnLongClickListener { onLong(); true }
        if (poster.isNotBlank()) net.go {
            val bmp = Images.file(this, poster, "ps_")?.let { f -> Images.decode(f, 300) }
            if (bmp != null) main.post { iv.setImageBitmap(bmp); ph.visibility = View.GONE; iv.alpha = 0f; iv.animate().alpha(1f).setDuration(250).start() }
        }
        return cell
    }

    // ================= navbar extras =================
    private fun iconButton(off: () -> Drawable, on: () -> Drawable, action: () -> Unit): GlassView {
        val g = GlassView(this, hero, dpf(19f)).apply { isFocusable = true; isClickable = true }
        val iv = ImageView(this).apply { setImageDrawable(off()) }
        g.addView(iv, FrameLayout.LayoutParams(dp(17), dp(17), Gravity.CENTER))
        g.tag = Runnable { iv.setImageDrawable(if (g.isFocused) on() else off()) }  // lets callers repaint the glyph
        g.setOnFocusChangeListener { _, f ->
            g.animateSolid(if (f) 1f else 0f); iv.setImageDrawable(if (f) on() else off())
            g.animate().scaleX(if (f) 1.1f else 1f).scaleY(if (f) 1.1f else 1f).translationZ(if (f) dpf(12f) else 0f)
                .setInterpolator(appleOut).setDuration(380).start()
            if (f) { hero.spotlight(0f, 0f, false); pager.glide(0); hero.setCollapsed(false) }
        }
        g.setOnClickListener { press(g, 1.1f) { action() } }
        return g
    }

    private fun setNavFocusable(v: Boolean) {
        searchPill.isFocusable = v; weatherPill.isFocusable = v; wifiBtn.isFocusable = v; inputsBtn.isFocusable = v
        lockBtn.isFocusable = v; settingsBtn.isFocusable = v; shareBtn.isFocusable = v; ccBtn.isFocusable = v
    }

    private fun loadWeather() {
        net.go {
            val w = Weather.load(this)
            main.post {
                if (w != null) {
                    weather = w
                    prefs.edit().putString("wx", "${w.temp}\u00b0  ${w.city}").putInt("wx_code", w.code).putBoolean("wx_day", w.day).apply()
                    weatherIconView.setImageDrawable(weatherIcon(w.code, w.day))
                    weatherText.text = "${w.temp}\u00b0"
                    weatherPill.visibility = View.VISIBLE
                    if (weatherPanel.visibility == View.VISIBLE) fillWeather(w)
                }
                main.postDelayed(weatherRun, if (w != null) 30 * 60_000L else 20_000L)   // failed (e.g. network not ready right after install): retry soon
            }
        }
    }

    // ---- Inputs popover ----
    private fun buildInputsPanel() {
        inputsPanel = GlassView(this, hero, dpf(22f)).apply { visibility = View.GONE; pivotX = dpf(240f); pivotY = 0f }
        inputsList = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(8), dp(10), dp(8), dp(8)) }
        inputsList.addView(TextView(this).apply {
            text = "Inputs"; typeface = Fonts.semibold; textSize = 13f; setTextColor(0x99FFFFFF.toInt()); setPadding(dp(14), 0, 0, dp(6))
        })
        inputsPanel.addView(inputsList, FrameLayout.LayoutParams(-1, -2))
        root.addView(inputsPanel, FrameLayout.LayoutParams(dp(250), -2, Gravity.TOP or Gravity.END).apply { topMargin = dp(74); rightMargin = dp(100) })
    }

    private class Source(val label: String, val intent: Intent)

    private fun tvSources(): List<Source> {
        val tim = getSystemService(Context.TV_INPUT_SERVICE) as? android.media.tv.TvInputManager ?: return emptyList()
        val out = ArrayList<Source>()
        val list = try { tim.tvInputList } catch (e: Exception) { emptyList<android.media.tv.TvInputInfo>() }
        val hw = list.filter { it.isPassthroughInput || it.type == android.media.tv.TvInputInfo.TYPE_TUNER }
            .filter { it.serviceInfo.packageName == "com.mediatek.tvinput" }
        hw.firstOrNull { it.type == android.media.tv.TvInputInfo.TYPE_TUNER }?.let {
            val i = packageManager.getLeanbackLaunchIntentForPackage("com.mediatek.wwtv.tvcenter")
                ?: Intent(Intent.ACTION_VIEW, android.media.tv.TvContract.buildChannelsUriForInput(it.id))
            out.add(Source("TV", i))
        }
        hw.filter { it.type == android.media.tv.TvInputInfo.TYPE_HDMI }.sortedBy { it.id }.forEachIndexed { n, it ->
            val l = it.loadLabel(this)?.toString()?.takeIf { s -> s.isNotBlank() } ?: "HDMI ${n + 1}"
            out.add(Source(l, Intent(Intent.ACTION_VIEW, android.media.tv.TvContract.buildChannelUriForPassthroughInput(it.id))))
        }
        hw.filter { it.type == android.media.tv.TvInputInfo.TYPE_COMPOSITE }.forEach {
            out.add(Source(it.loadLabel(this)?.toString()?.takeIf { s -> s.isNotBlank() } ?: "AV",
                Intent(Intent.ACTION_VIEW, android.media.tv.TvContract.buildChannelUriForPassthroughInput(it.id))))
        }
        return out
    }

    private fun openInputs() {
        while (inputsList.childCount > 1) inputsList.removeViewAt(1)
        val sources = tvSources()
        if (sources.isEmpty()) { Kit.toast(this, "No inputs found"); return }
        val rowsV = ArrayList<View>()
        for (src in sources) {
            val row = FrameLayout(this).apply { isFocusable = true; isClickable = true }
            val bg = GradientDrawable().apply { cornerRadius = dpf(14f); setColor(Color.TRANSPARENT) }
            row.background = bg
            val tv = TextView(this).apply { text = src.label; typeface = Fonts.medium; textSize = 16f; setTextColor(Color.WHITE); gravity = Gravity.CENTER_VERTICAL; setPadding(dp(14), 0, dp(14), 0) }
            row.addView(tv, FrameLayout.LayoutParams(-1, -1))
            row.setOnFocusChangeListener { _, f ->
                bg.setColor(if (f) 0xF2FFFFFF.toInt() else Color.TRANSPARENT); tv.setTextColor(if (f) 0xFF111111.toInt() else Color.WHITE)
                row.animate().scaleX(if (f) 1.03f else 1f).scaleY(if (f) 1.03f else 1f).setDuration(220).setInterpolator(appleOut).start()
            }
            row.setOnClickListener {
                closeInputs()
                try { startActivity(src.intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } catch (e: Exception) {
                    Kit.toast(this, "Can't switch to ${src.label}")
                }
            }
            inputsList.addView(row, LinearLayout.LayoutParams(-1, dp(44)))
            rowsV.add(row)
        }
        rowsV.forEachIndexed { i, v ->
            v.id = View.generateViewId()
            v.nextFocusLeftId = v.id; v.nextFocusRightId = v.id
        }
        rowsV.first().nextFocusUpId = rowsV.first().id; rowsV.last().nextFocusDownId = rowsV.last().id
        restoreFocus = currentFocus
        page.descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
        setNavFocusable(false)
        inputsPanel.visibility = View.VISIBLE; inputsPanel.alpha = 0f; inputsPanel.scaleX = 0.92f; inputsPanel.scaleY = 0.92f
        inputsPanel.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(360).setInterpolator(appleOut).start()
        rowsV.first().requestFocus()
    }

    private fun closeInputs() {
        page.descendantFocusability = ViewGroup.FOCUS_AFTER_DESCENDANTS
        setNavFocusable(true)
        inputsPanel.animate().alpha(0f).scaleX(0.95f).scaleY(0.95f).setDuration(200).setInterpolator(appleEase)
            .withEndAction { inputsPanel.visibility = View.GONE }.start()
        (restoreFocus ?: ccBtn).requestFocus()
    }

    // ---- Weather popover ----
    private var weather: Weather.Now? = null
    private lateinit var weatherPanel: GlassView
    private lateinit var wxBody: LinearLayout
    private lateinit var wxDone: FrameLayout

    private fun buildWeatherPanel() {
        weatherPanel = GlassView(this, hero, dpf(24f)).apply { visibility = View.GONE; pivotX = dpf(60f); pivotY = 0f }
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(16), dp(20), dp(16)) }
        wxBody = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        col.addView(wxBody)
        val (done, _) = panelRow("Done") { closeWeather() }
        wxDone = done; done.id = View.generateViewId()
        done.nextFocusUpId = done.id; done.nextFocusDownId = done.id; done.nextFocusLeftId = done.id; done.nextFocusRightId = done.id
        col.addView(done, LinearLayout.LayoutParams(-1, dp(40)).apply { topMargin = dp(14) })
        weatherPanel.addView(col, FrameLayout.LayoutParams(-1, -2))
        root.addView(weatherPanel, FrameLayout.LayoutParams(dp(400), -2, Gravity.TOP or Gravity.START).apply { topMargin = dp(74); leftMargin = dp(48) })
    }

    private fun fillWeather(w: Weather.Now) {
        wxBody.removeAllViews()
        wxBody.addView(TextView(this).apply { text = w.city.ifBlank { "Weather" }; typeface = Fonts.semibold; textSize = 13f; setTextColor(0xB3FFFFFF.toInt()) })
        val head = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        head.addView(TextView(this).apply { text = "${w.temp}\u00b0"; typeface = Fonts.displayBold; textSize = 46f; setTextColor(Color.WHITE); letterSpacing = -0.02f })
        head.addView(ImageView(this).apply { setImageDrawable(weatherIcon(w.code, w.day)) }, LinearLayout.LayoutParams(dp(40), dp(40)).apply { leftMargin = dp(12) })
        val hc = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(12), 0, 0, 0) }
        hc.addView(TextView(this).apply { text = Weather.describe(w.code); typeface = Fonts.semibold; textSize = 16f; setTextColor(Color.WHITE) })
        hc.addView(TextView(this).apply { text = "H ${w.hi}\u00b0  L ${w.lo}\u00b0"; typeface = Fonts.medium; textSize = 13f; setTextColor(0xB3FFFFFF.toInt()) })
        head.addView(hc)
        wxBody.addView(head)
        val stats = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(0, dp(8), 0, dp(4)) }
        stats.addView(statLine("Feels like", "${w.feels}\u00b0"))
        stats.addView(statLine("Humidity", "${w.humidity}%"))
        stats.addView(statLine("Wind", "${w.wind} km/h"))
        if (w.sunrise.isNotBlank()) stats.addView(statLine("Sunrise  \u00b7  Sunset", "${w.sunrise}  \u00b7  ${w.sunset}"))
        wxBody.addView(stats)
        val days = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; setPadding(0, dp(10), 0, dp(10))
            background = GradientDrawable().apply { cornerRadius = dpf(14f); setColor(0x14FFFFFF) }
        }
        for (d in w.days) {
            val c = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL }
            c.addView(TextView(this).apply { text = d.label; typeface = Fonts.semibold; textSize = 12f; setTextColor(0xCCFFFFFF.toInt()) })
            c.addView(ImageView(this).apply { setImageDrawable(weatherIcon(d.code, true)) }, LinearLayout.LayoutParams(dp(24), dp(24)).apply { topMargin = dp(5); bottomMargin = dp(5) })
            c.addView(TextView(this).apply {
                text = android.text.SpannableStringBuilder("${d.max}\u00b0 ").apply {
                    val st = length; append("${d.min}\u00b0"); setSpan(android.text.style.ForegroundColorSpan(0x80FFFFFF.toInt()), st, length, 0)
                }
                typeface = Fonts.semibold; textSize = 12f; setTextColor(Color.WHITE)
            })
            days.addView(c, LinearLayout.LayoutParams(0, -2, 1f))
        }
        wxBody.addView(days, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(6) })
    }

    private fun openWeather() {
        val w = weather ?: return
        fillWeather(w)
        restoreFocus = currentFocus
        page.descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
        setNavFocusable(false)
        weatherPanel.visibility = View.VISIBLE; weatherPanel.alpha = 0f; weatherPanel.scaleX = 0.92f; weatherPanel.scaleY = 0.92f
        weatherPanel.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(360).setInterpolator(appleOut).start()
        wxDone.requestFocus()
        main.removeCallbacks(weatherRun); main.post(weatherRun)   // refresh while it's open
    }

    private fun closeWeather() {
        page.descendantFocusability = ViewGroup.FOCUS_AFTER_DESCENDANTS
        setNavFocusable(true)
        weatherPanel.animate().alpha(0f).scaleX(0.95f).scaleY(0.95f).setDuration(200).setInterpolator(appleEase)
            .withEndAction { weatherPanel.visibility = View.GONE }.start()
        (restoreFocus ?: weatherPill).requestFocus()
    }

    // ---- Network popover ----
    private lateinit var netTitle: TextView
    private lateinit var netSub: TextView
    private lateinit var netStats: LinearLayout
    private lateinit var dlVal: TextView
    private lateinit var ulVal: TextView
    private lateinit var pingVal: TextView
    private lateinit var testLabel: TextView
    private lateinit var testRow: FrameLayout
    private lateinit var wifiSettingsRow: FrameLayout
    private var testing = false

    private fun refreshNetIcon() {
        net.go {
            var i = NetInfo.read(this)
            if (i.wifi && i.ssid == null) { NetInfo.resolveSsid(); i = NetInfo.read(this) }
            main.post {
                if (netPanel.visibility == View.VISIBLE) fillNet(i)
                netLevel = i.level; netWifi = i.wifi || !i.ethernet
                (wifiBtn.tag as? Runnable)?.run()
                main.postDelayed(netRun, 20_000)
            }
        }
    }

    private fun panelRow(label: String, onClick: () -> Unit): Pair<FrameLayout, TextView> {
        val row = FrameLayout(this).apply { isFocusable = true; isClickable = true }
        val bg = GradientDrawable().apply { cornerRadius = dpf(14f); setColor(0x1AFFFFFF) }
        row.background = bg
        val tv = TextView(this).apply { text = label; typeface = Fonts.semibold; textSize = 15f; setTextColor(Color.WHITE); gravity = Gravity.CENTER }
        row.addView(tv, FrameLayout.LayoutParams(-1, -1))
        row.setOnFocusChangeListener { _, f ->
            if (f) row.allowOverflow(4)
            bg.setColor(if (f) 0xF2FFFFFF.toInt() else 0x1AFFFFFF); tv.setTextColor(if (f) 0xFF111111.toInt() else Color.WHITE)
            row.animate().scaleX(if (f) 1.03f else 1f).scaleY(if (f) 1.03f else 1f).setDuration(220).setInterpolator(appleOut).start()
        }
        row.setOnClickListener { onClick() }
        return row to tv
    }

    private fun statTile(label: String): Pair<LinearLayout, TextView> {
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL; setPadding(0, dp(10), 0, dp(10))
            background = GradientDrawable().apply { cornerRadius = dpf(14f); setColor(0x14FFFFFF) }
        }
        val v = TextView(this).apply { text = "\u2014"; typeface = Fonts.displayBold; textSize = 26f; setTextColor(Color.WHITE); gravity = Gravity.CENTER }
        val l = TextView(this).apply { text = label; typeface = Fonts.medium; textSize = 11f; setTextColor(0x99FFFFFF.toInt()); letterSpacing = 0.06f; gravity = Gravity.CENTER }
        col.addView(v); col.addView(l)
        return col to v
    }

    private fun buildNetPanel() {
        netPanel = GlassView(this, hero, dpf(24f)).apply { visibility = View.GONE; pivotX = dpf(400f); pivotY = 0f }
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(18), dp(16), dp(18), dp(16)) }
        netTitle = TextView(this).apply { typeface = Fonts.display; textSize = 22f; setTextColor(Color.WHITE); maxLines = 1; ellipsize = TextUtils.TruncateAt.END }
        netSub = TextView(this).apply { typeface = Fonts.medium; textSize = 13f; setTextColor(0xB3FFFFFF.toInt()); setPadding(0, dp(3), 0, 0) }
        netStats = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(0, dp(12), 0, dp(4)) }
        col.addView(netTitle); col.addView(netSub); col.addView(netStats)

        val tiles = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val (dl, dv) = statTile("DOWNLOAD Mbps"); val (ul, uv) = statTile("UPLOAD Mbps"); val (pg, pv) = statTile("PING ms")
        dlVal = dv; ulVal = uv; pingVal = pv
        tiles.addView(dl, LinearLayout.LayoutParams(0, -2, 1f).apply { rightMargin = dp(8) })
        tiles.addView(ul, LinearLayout.LayoutParams(0, -2, 1f).apply { rightMargin = dp(8) })
        tiles.addView(pg, LinearLayout.LayoutParams(0, -2, 1f))
        col.addView(tiles, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(10) })

        val (tr, tl) = panelRow("Run speed test") { runSpeedTest() }
        testRow = tr; testLabel = tl
        col.addView(tr, LinearLayout.LayoutParams(-1, dp(42)).apply { topMargin = dp(12) })
        val (wr, _) = panelRow("Wi-Fi & network settings") {
            closeNet(); startActivity(Intent(Settings.ACTION_WIFI_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
        wifiSettingsRow = wr
        col.addView(wr, LinearLayout.LayoutParams(-1, dp(42)).apply { topMargin = dp(8) })
        val (ar, al) = panelRow("ADB access") { toggleAdb() }
        adbLabel = al
        col.addView(ar, LinearLayout.LayoutParams(-1, dp(42)).apply { topMargin = dp(8) })
        listOf(tr, wr, ar).forEach { it.id = View.generateViewId() }
        tr.nextFocusUpId = tr.id; tr.nextFocusLeftId = tr.id; tr.nextFocusRightId = tr.id; tr.nextFocusDownId = wr.id
        wr.nextFocusDownId = ar.id; wr.nextFocusLeftId = wr.id; wr.nextFocusRightId = wr.id; wr.nextFocusUpId = tr.id
        ar.nextFocusDownId = ar.id; ar.nextFocusLeftId = ar.id; ar.nextFocusRightId = ar.id; ar.nextFocusUpId = wr.id

        netPanel.addView(col, FrameLayout.LayoutParams(-1, -2))
        root.addView(netPanel, FrameLayout.LayoutParams(dp(400), -2, Gravity.TOP or Gravity.END).apply { topMargin = dp(74); rightMargin = dp(48) })
    }

    private fun statLine(k: String, v: String) = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL; setPadding(0, dp(3), 0, dp(3))
        addView(TextView(this@MainActivity).apply { text = k; typeface = Fonts.medium; textSize = 13f; setTextColor(0x99FFFFFF.toInt()) }, LinearLayout.LayoutParams(0, -2, 1f))
        addView(TextView(this@MainActivity).apply { text = v; typeface = Fonts.semibold; textSize = 13f; setTextColor(Color.WHITE) })
    }

    private fun fillNet(i: NetInfo.Info) {
        netTitle.text = when { !i.connected -> "Not connected"; i.ethernet -> "Ethernet"; else -> i.ssid ?: "Wi-Fi" }
        netSub.text = when {
            !i.connected -> "No network"
            i.wifi -> "${i.band}  \u00b7  Channel ${i.channel}  \u00b7  ${i.quality} signal (${i.rssi} dBm)"
            else -> "Wired connection"
        }
        netStats.removeAllViews()
        if (i.wifi) netStats.addView(statLine("Link speed", "${i.linkMbps} Mbps"))
        netStats.addView(statLine("IP address", i.ip ?: "\u2014"))
        netStats.addView(statLine("Router", i.gateway ?: "\u2014"))
        if (i.dns.isNotEmpty()) netStats.addView(statLine("DNS", i.dns.take(2).joinToString(", ")))
        updateAdbLabel()
    }

    private fun openNet() {
        fillNet(NetInfo.read(this))
        restoreFocus = currentFocus
        page.descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
        setNavFocusable(false)
        netPanel.visibility = View.VISIBLE; netPanel.alpha = 0f; netPanel.scaleX = 0.92f; netPanel.scaleY = 0.92f
        netPanel.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(360).setInterpolator(appleOut).start()
        testRow.requestFocus()
    }

    private fun closeNet() {
        page.descendantFocusability = ViewGroup.FOCUS_AFTER_DESCENDANTS
        setNavFocusable(true)
        netPanel.animate().alpha(0f).scaleX(0.95f).scaleY(0.95f).setDuration(200).setInterpolator(appleEase)
            .withEndAction { netPanel.visibility = View.GONE }.start()
        (restoreFocus ?: ccBtn).requestFocus()
    }

    private fun runSpeedTest() {
        if (testing) return
        testing = true
        dlVal.text = "\u2026"; ulVal.text = "\u2014"; pingVal.text = "\u2026"
        testLabel.text = "Testing\u2026"
        fun fmt(v: Double) = when { v < 0 -> "failed"; v >= 100 -> v.toInt().toString(); else -> String.format(java.util.Locale.US, "%.1f", v) }
        thread {
            val p = SpeedTest.ping()
            main.post { pingVal.text = if (p < 0) "\u2014" else "$p" }
            val d = SpeedTest.download(8, 4) { v -> main.post { dlVal.text = fmt(v) } }
            main.post { dlVal.text = fmt(d); ulVal.text = "\u2026" }
            val u = SpeedTest.upload(6, 3) { v -> main.post { ulVal.text = fmt(v) } }
            main.post { ulVal.text = fmt(u); testLabel.text = "Run again"; testing = false }
        }
    }

    // ================= icons =================
    private fun iconBitmap(draw: Canvas.(Float) -> Unit): Drawable {
        val s = 256; val bm = Bitmap.createBitmap(s, s, Bitmap.Config.ARGB_8888)
        Canvas(bm).draw(s.toFloat()); return BitmapDrawable(resources, bm)
    }

    private fun gearIcon(color: Int) = iconBitmap { s ->
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color; style = Paint.Style.STROKE; strokeWidth = s * 0.13f }
        drawCircle(s / 2, s / 2, s * 0.25f, p); p.strokeWidth = s * 0.11f; p.strokeCap = Paint.Cap.ROUND
        for (i in 0 until 8) { save(); rotate(i * 45f, s / 2, s / 2); drawLine(s / 2, s * 0.1f, s / 2, s * 0.2f, p); restore() }
    }

    private fun lockIcon(color: Int) = iconBitmap { s ->
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color }
        drawRoundRect(s * 0.24f, s * 0.46f, s * 0.76f, s * 0.84f, s * 0.08f, s * 0.08f, p)
        p.style = Paint.Style.STROKE; p.strokeWidth = s * 0.075f
        drawArc(RectF(s * 0.33f, s * 0.16f, s * 0.67f, s * 0.6f), 180f, 180f, false, p)
        drawLine(s * 0.33f, s * 0.38f, s * 0.33f, s * 0.47f, p); drawLine(s * 0.67f, s * 0.38f, s * 0.67f, s * 0.47f, p)
    }

    /** Wi-Fi fan (unlit arcs dimmed by signal level), or a plug-style glyph for Ethernet. */
    private fun wifiIcon(level: Int, wifi: Boolean, color: Int) = iconBitmap { s ->
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color; strokeCap = Paint.Cap.ROUND }
        if (!wifi) {
            p.style = Paint.Style.STROKE; p.strokeWidth = s * 0.08f
            drawRoundRect(s * 0.2f, s * 0.22f, s * 0.8f, s * 0.62f, s * 0.06f, s * 0.06f, p)
            drawLine(s * 0.5f, s * 0.62f, s * 0.5f, s * 0.86f, p); drawLine(s * 0.3f, s * 0.86f, s * 0.7f, s * 0.86f, p)
            return@iconBitmap
        }
        p.style = Paint.Style.FILL
        val cx = s / 2; val cy = s * 0.82f
        drawCircle(cx, cy, s * 0.07f, p)
        p.style = Paint.Style.STROKE; p.strokeWidth = s * 0.09f
        for (i in 1..3) {
            p.alpha = if (level >= i) 255 else 80
            val r = s * (0.2f + 0.19f * i)
            drawArc(RectF(cx - r, cy - r, cx + r, cy + r), 225f, 90f, false, p)
        }
    }

    /** TV with an arrow coming in: the Inputs glyph. */
    private fun inputIcon(color: Int) = iconBitmap { s ->
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color; style = Paint.Style.STROKE; strokeWidth = s * 0.08f; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND }
        drawRoundRect(s * 0.3f, s * 0.2f, s * 0.92f, s * 0.68f, s * 0.07f, s * 0.07f, p)
        drawLine(s * 0.5f, s * 0.82f, s * 0.72f, s * 0.82f, p)
        drawLine(s * 0.08f, s * 0.44f, s * 0.56f, s * 0.44f, p)
        drawLine(s * 0.42f, s * 0.31f, s * 0.56f, s * 0.44f, p); drawLine(s * 0.42f, s * 0.57f, s * 0.56f, s * 0.44f, p)
    }

    /** Multicolour weather glyphs in the spirit of SF Symbols, from WMO weather codes. */
    private fun weatherIcon(code: Int, day: Boolean) = iconBitmap { s ->
        val sunC = 0xFFFFD60A.toInt(); val dropC = 0xFF64D2FF.toInt()
        val fill = Paint(Paint.ANTI_ALIAS_FLAG)
        fun sun(cx: Float, cy: Float, r: Float) {
            fill.color = sunC; fill.style = Paint.Style.FILL; drawCircle(cx, cy, r, fill)
            fill.style = Paint.Style.STROKE; fill.strokeWidth = r * 0.28f; fill.strokeCap = Paint.Cap.ROUND
            for (i in 0 until 8) { val a = Math.toRadians(i * 45.0); drawLine(cx + (r * 1.45f * Math.cos(a)).toFloat(), cy + (r * 1.45f * Math.sin(a)).toFloat(), cx + (r * 1.85f * Math.cos(a)).toFloat(), cy + (r * 1.85f * Math.sin(a)).toFloat(), fill) }
        }
        fun moon(cx: Float, cy: Float, r: Float) {
            val path = Path().apply { addCircle(cx, cy, r, Path.Direction.CW) }
            val cut = Path().apply { addCircle(cx + r * 0.55f, cy - r * 0.4f, r * 0.85f, Path.Direction.CW) }
            path.op(cut, Path.Op.DIFFERENCE); fill.color = 0xFFF2F2F7.toInt(); fill.style = Paint.Style.FILL; drawPath(path, fill)
        }
        fun cloud(cx: Float, cy: Float, w: Float) {
            fill.color = Color.WHITE; fill.style = Paint.Style.FILL
            val h = w * 0.36f
            drawRoundRect(cx - w / 2, cy - h / 2, cx + w / 2, cy + h / 2, h / 2, h / 2, fill)
            drawCircle(cx - w * 0.14f, cy - h * 0.45f, w * 0.22f, fill); drawCircle(cx + w * 0.13f, cy - h * 0.6f, w * 0.28f, fill)
        }
        fun drops(cx: Float, y: Float, w: Float, snow: Boolean) {
            fill.color = if (snow) Color.WHITE else dropC; fill.style = Paint.Style.STROKE; fill.strokeWidth = w * 0.07f; fill.strokeCap = Paint.Cap.ROUND
            for (i in -1..1) { val x = cx + i * w * 0.28f; if (snow) { fill.style = Paint.Style.FILL; drawCircle(x, y + w * 0.12f, w * 0.06f, fill) } else drawLine(x + w * 0.05f, y, x - w * 0.05f, y + w * 0.22f, fill) }
        }
        when (code) {
            0 -> if (day) sun(s / 2, s / 2, s * 0.2f) else moon(s / 2, s / 2, s * 0.32f)
            1, 2 -> { if (day) sun(s * 0.38f, s * 0.36f, s * 0.15f) else moon(s * 0.38f, s * 0.34f, s * 0.22f); cloud(s * 0.56f, s * 0.64f, s * 0.62f) }
            3 -> cloud(s / 2, s * 0.56f, s * 0.8f)
            45, 48 -> { fill.color = Color.WHITE; fill.style = Paint.Style.STROKE; fill.strokeWidth = s * 0.08f; fill.strokeCap = Paint.Cap.ROUND
                for (i in 0..2) drawLine(s * (0.18f + i * 0.04f), s * (0.34f + i * 0.16f), s * (0.82f - i * 0.04f), s * (0.34f + i * 0.16f), fill) }
            in 71..77, 85, 86 -> { cloud(s / 2, s * 0.42f, s * 0.74f); drops(s / 2, s * 0.66f, s * 0.74f, true) }
            in 95..99 -> { cloud(s / 2, s * 0.4f, s * 0.74f)
                fill.color = sunC; fill.style = Paint.Style.FILL
                drawPath(Path().apply { moveTo(s * 0.52f, s * 0.55f); lineTo(s * 0.38f, s * 0.76f); lineTo(s * 0.5f, s * 0.76f); lineTo(s * 0.44f, s * 0.95f); lineTo(s * 0.64f, s * 0.68f); lineTo(s * 0.52f, s * 0.68f); close() }, fill) }
            else -> if (code >= 51) { cloud(s / 2, s * 0.42f, s * 0.74f); drops(s / 2, s * 0.64f, s * 0.74f, false) } else cloud(s / 2, s * 0.56f, s * 0.8f)
        }
    }

    private fun magnifier(color: Int) = iconBitmap { s ->
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color; style = Paint.Style.STROKE; strokeWidth = s * 0.12f; strokeCap = Paint.Cap.ROUND }
        drawCircle(s * 0.42f, s * 0.42f, s * 0.28f, p); drawLine(s * 0.63f, s * 0.63f, s * 0.88f, s * 0.88f, p)
    }

    /** Banner for apps shown under another name (SmartTube as "YouTube"): white tile, red play badge, the name. Drawn, not shipped. */
    private fun playTile(name: String): Drawable {
        val w = 640; val h = 360; val bm = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888); val c = Canvas(bm)
        c.drawColor(0xFFF4F4F4.toInt())
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 104f; typeface = Fonts.semibold; color = 0xFF111111.toInt() }
        var bw = 150f; var bh = 104f; var gapX = 26f
        while (bw + gapX + p.measureText(name) > w * 0.84f) { p.textSize *= 0.94f; bw *= 0.94f; bh *= 0.94f; gapX *= 0.94f }   // always fits the tile
        val tw = p.measureText(name)
        val x0 = (w - (bw + gapX + tw)) / 2f; val cy = h / 2f
        c.drawRoundRect(RectF(x0, cy - bh / 2, x0 + bw, cy + bh / 2), 30f, 30f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFFF0033.toInt() })
        c.drawPath(Path().apply { moveTo(x0 + bw * 0.4f, cy - bh * 0.22f); lineTo(x0 + bw * 0.66f, cy); lineTo(x0 + bw * 0.4f, cy + bh * 0.22f); close() },
            Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE })
        c.drawText(name, x0 + bw + gapX, cy + p.textSize * 0.36f, p)
        return BitmapDrawable(resources, bm)
    }

    private fun playIcon(color: Int = Color.WHITE) = iconBitmap { s ->
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color; pathEffect = CornerPathEffect(s * 0.08f) }
        drawPath(Path().apply { moveTo(s * 0.18f, s * 0.08f); lineTo(s * 0.92f, s * 0.5f); lineTo(s * 0.18f, s * 0.92f); close() }, p)
    }
}

/** Page scroller that never auto-scrolls on focus; the activity glides it with Apple's curve instead. */
class Pager(ctx: Context) : ScrollView(ctx) {
    var onScroll: ((Int) -> Unit)? = null
    private var anim: ValueAnimator? = null

    init { isVerticalScrollBarEnabled = false; overScrollMode = OVER_SCROLL_NEVER; isFocusable = false }

    override fun computeScrollDeltaToGetChildRectOnScreen(rect: Rect?) = 0
    override fun executeKeyEvent(event: KeyEvent): Boolean = false

    fun glide(y: Int) {
        if (anim?.isRunning == true && (anim?.animatedValue as? Int) == y) return
        anim?.cancel()
        if (scrollY == y) return
        anim = ValueAnimator.ofInt(scrollY, y).apply {
            duration = 560; interpolator = appleOut
            addUpdateListener { scrollTo(0, it.animatedValue as Int) }
            start()
        }
    }

    override fun onScrollChanged(l: Int, t: Int, ol: Int, ot: Int) { super.onScrollChanged(l, t, ol, ot); onScroll?.invoke(t) }
}
