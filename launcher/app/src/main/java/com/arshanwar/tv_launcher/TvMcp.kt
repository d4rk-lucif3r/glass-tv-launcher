package com.arshanwar.tv_launcher

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.net.Uri
import android.os.PowerManager
import android.view.KeyEvent
import android.view.View
import android.view.accessibility.AccessibilityNodeInfo
import org.json.JSONArray
import org.json.JSONObject

/**
 * Real remote-key presses for the MCP server, through the TV's own ADB on localhost (the only way to press keys inside
 * apps like Netflix that ignore accessibility). ADB is off by default: it's switched on only while an AI is using the
 * TV and switched off again 10 minutes after the last request, unless the user had already turned it on themselves.
 */
object TvAdb {
    private const val IDLE_OFF_MS = 10 * 60_000L
    private lateinit var app: Context
    private val prefs by lazy { app.getSharedPreferences("launcher", Context.MODE_PRIVATE) }
    private var conn: dadb.Dadb? = null
    private val pool = java.util.concurrent.Executors.newCachedThreadPool { r -> Thread(r, "mcp-adb").apply { isDaemon = true } }
    /** Every ADB step gets a hard deadline: a stuck connection must never freeze the MCP server. */
    private fun <T> timed(ms: Long, what: String, f: () -> T): T {
        val fut = pool.submit<T> { f() }
        return try { fut.get(ms, java.util.concurrent.TimeUnit.MILLISECONDS) }
        catch (e: java.util.concurrent.TimeoutException) { fut.cancel(true); throw IllegalStateException("ADB $what timed out") }
        catch (e: java.util.concurrent.ExecutionException) { throw (e.cause as? Exception) ?: IllegalStateException(e.cause) }
    }

    fun init(ctx: Context) {
        app = ctx.applicationContext
        val at = prefs.getLong("mcp_adb_off_at", 0)
        if (at > 0) Banner.later("mcp_adb_off", at) { switchOff() }   // process restarted mid-window: still switch off
    }

    private fun adbOn() = android.provider.Settings.Global.getInt(app.contentResolver, android.provider.Settings.Global.ADB_ENABLED, 0) == 1

    private fun switchOff() {
        if (prefs.getLong("mcp_adb_off_at", 0) == 0L) return
        prefs.edit().remove("mcp_adb_off_at").apply()
        synchronized(this) { try { conn?.close() } catch (e: Exception) {}; conn = null }
        if (prefs.getLong("adb_until", 0) == 0L)   // the user didn't open their own ADB window meanwhile
            try { android.provider.Settings.Global.putInt(app.contentResolver, android.provider.Settings.Global.ADB_ENABLED, 0) } catch (e: Exception) {}
        android.util.Log.i("TvHome", "mcp: ADB switched off (idle)")
    }

    /** ADB on (if it isn't) and the off-timer pushed back; then a live connection to the TV's own adbd. */
    @Synchronized private fun connection(): dadb.Dadb {
        if (!adbOn()) {
            android.provider.Settings.Global.putInt(app.contentResolver, android.provider.Settings.Global.ADB_ENABLED, 1)
            prefs.edit().putLong("mcp_adb_off_at", System.currentTimeMillis() + IDLE_OFF_MS).apply()
            android.util.Log.i("TvHome", "mcp: ADB switched on for AI control")
        }
        if (prefs.getLong("mcp_adb_off_at", 0) > 0) {
            val at = System.currentTimeMillis() + IDLE_OFF_MS
            prefs.edit().putLong("mcp_adb_off_at", at).apply()
            Banner.later("mcp_adb_off", at) { switchOff() }
        }
        conn?.let { c -> try { timed(4000, "check") { c.shell("true") }; return c } catch (e: Exception) { try { c.close() } catch (e2: Exception) {}; conn = null } }
        val priv = java.io.File(app.filesDir, "mcp_adbkey"); val pub = java.io.File(app.filesDir, "mcp_adbkey.pub")
        if (!priv.exists() || !pub.exists()) dadb.AdbKeyPair.generate(priv, pub)
        val keys = dadb.AdbKeyPair.read(priv, pub)
        var last: Exception? = null
        for (i in 0 until 12) {   // adbd needs a moment after being switched on; first use also waits for "Allow USB debugging?"
            try { return timed(6000, "connect") { dadb.Dadb.create("127.0.0.1", 5555, keys).also { it.shell("true") } }.also { conn = it; android.util.Log.i("TvHome", "mcp: ADB connected") } }
            catch (e: Exception) { last = e; android.util.Log.w("TvHome", "mcp: ADB connect attempt ${i + 1}: ${e.message}"); Thread.sleep(1000) }
        }
        throw IllegalStateException("Can't reach the TV's ADB (first time: accept \"Allow USB debugging\" on the TV, tick Always allow). ${last?.message}")
    }

    fun shell(cmd: String): String { val c = connection(); return timed(20_000, "command") { c.shell(cmd).allOutput.trim() } }
    fun key(vararg codes: Int) { shell("input keyevent " + codes.joinToString(" ")) }
    fun available() = try { connection(); true } catch (e: Exception) { false }
}

/**
 * The TV as an MCP server (Model Context Protocol, Streamable HTTP transport, plain-JSON responses), served by ShareHub's
 * HTTP server at /mcp, behind OAuth (McpAuth: a code shown on the TV). Any MCP client (Claude, ChatGPT, Claude Code...) can
 * see what's on, open apps and shows, control playback/volume/power and drive app screens. No background work: everything
 * happens inside a request.
 */
object TvMcp {
    private lateinit var app: Context

    fun init(ctx: Context) {
        app = ctx.applicationContext; TvAdb.init(app)
        app.getSharedPreferences("launcher", Context.MODE_PRIVATE).edit().remove("mcp_token").apply()   // old secret-in-URL access: gone
    }

    /** Returns (status, contentType, body). The caller has already checked the OAuth bearer token (McpAuth). */
    fun handle(method: String, body: String): Triple<Int, String, String> {
        if (method == "GET") return Triple(405, "text/plain", "")          // no server-initiated stream
        if (method == "DELETE") return Triple(200, "text/plain", "")
        val parsed = try { if (body.trimStart().startsWith("[")) JSONArray(body) else JSONObject(body) } catch (e: Exception) {
            return Triple(400, "application/json", err(null, -32700, "Parse error").toString())
        }
        if (parsed is JSONArray) {
            val out = JSONArray()
            for (i in 0 until parsed.length()) rpc(parsed.getJSONObject(i))?.let { out.put(it) }
            return if (out.length() == 0) Triple(202, "text/plain", "") else Triple(200, "application/json", out.toString())
        }
        val res = rpc(parsed as JSONObject) ?: return Triple(202, "text/plain", "")
        return Triple(200, "application/json", res.toString())
    }

    private fun err(id: Any?, code: Int, msg: String) = JSONObject().put("jsonrpc", "2.0").put("id", id ?: JSONObject.NULL)
        .put("error", JSONObject().put("code", code).put("message", msg))

    private fun rpc(m: JSONObject): JSONObject? {
        val id = m.opt("id")
        val method = m.optString("method")
        if (id == null || method.startsWith("notifications/")) return null
        fun result(r: JSONObject) = JSONObject().put("jsonrpc", "2.0").put("id", id).put("result", r)
        return when (method) {
            "initialize" -> result(JSONObject()
                .put("protocolVersion", m.optJSONObject("params")?.optString("protocolVersion")?.takeIf { it.isNotBlank() } ?: "2025-06-18")
                .put("capabilities", JSONObject().put("tools", JSONObject().put("listChanged", false)))
                .put("serverInfo", JSONObject().put("name", "arsh-tv").put("title", "Arsh TV (living room)").put("version", "1.0"))
                .put("instructions", "Controls a living-room Android TV (Xiaomi, Android 10). Start with get_status. To watch something, prefer " +
                    "play_title (it finds which installed app streams it and opens the title page). For anything else inside an app, use " +
                    "read_screen then press/click. The TV may be in standby: power on first if screen_on is false."))
            "ping" -> result(JSONObject())
            "tools/list" -> result(JSONObject().put("tools", tools()))
            "tools/call" -> {
                val p = m.optJSONObject("params") ?: JSONObject()
                val text = try { call(p.optString("name"), p.optJSONObject("arguments") ?: JSONObject()) } catch (e: Exception) {
                    return result(JSONObject().put("isError", true).put("content", JSONArray().put(JSONObject().put("type", "text").put("text", "Error: ${e.message}"))))
                }
                result(JSONObject().put("content", JSONArray().put(JSONObject().put("type", "text").put("text", text))))
            }
            else -> err(id, -32601, "Method not found: $method")
        }
    }

    // ---------------- tool catalogue ----------------
    private fun tool(name: String, desc: String, props: JSONObject = JSONObject(), required: List<String> = emptyList()) = JSONObject()
        .put("name", name).put("description", desc)
        .put("inputSchema", JSONObject().put("type", "object").put("properties", props).apply { if (required.isNotEmpty()) put("required", JSONArray(required)) })
    private fun str(desc: String, enum: List<String>? = null) = JSONObject().put("type", "string").put("description", desc).apply { enum?.let { put("enum", JSONArray(it)) } }
    private fun int(desc: String) = JSONObject().put("type", "integer").put("description", desc)

    private fun tools() = JSONArray(listOf(
        tool("get_status", "What the TV is doing: screen on/off, app in front, what's playing, volume, sleep timer, local time."),
        tool("list_apps", "Installed apps that can be opened (label + package)."),
        tool("open_app", "Open an app by name (e.g. 'Netflix', 'YouTube', 'Prime Video'). Turns the screen on if needed.",
            JSONObject().put("app", str("App name or package")), listOf("app")),
        tool("play_title", "Open a movie or show directly in whichever installed streaming app has it (Netflix, Prime Video, Disney+, Max...). " +
            "On Netflix it also gets past 'Who's watching?' (picks the highlighted, last-used profile) and starts playback, and checks it's really playing. " +
            "Falls back to Stremio or a YouTube search.", JSONObject().put("title", str("Movie or show name")).put("app", str("Optional: preferred app name"))
                .put("autoplay", JSONObject().put("type", "boolean").put("description", "Start playing (default true). false = just open the title page")), listOf("title")),
        tool("where_to_watch", "Which installed apps stream a title, with direct links (doesn't open anything).", JSONObject().put("title", str("Movie or show name")), listOf("title")),
        tool("media", "Playback control for whatever is playing.", JSONObject().put("action", str("Action",
            listOf("play", "pause", "play_pause", "next", "previous", "rewind", "fast_forward", "stop"))), listOf("action")),
        tool("volume", "Set or change the TV volume.", JSONObject().put("level", int("Absolute level 0-100 (optional)"))
            .put("change", str("Relative change (optional)", listOf("up", "down", "mute", "unmute")))),
        tool("power", "Turn the screen on, off (standby) or start the screensaver.", JSONObject().put("action", str("Action", listOf("on", "off", "screensaver"))), listOf("action")),
        tool("press", "Press real remote buttons (works in every app, Netflix included): up/down/left/right, select (OK), back, play_pause, menu, home (Arsh TV launcher).",
            JSONObject().put("button", str("Button", listOf("up", "down", "left", "right", "select", "back", "home", "play_pause", "menu"))).put("times", int("Repeat count, default 1")), listOf("button")),
        tool("read_screen", "Text and buttons currently on screen (from the accessibility tree), with which item has focus. Use before press/click."),
        tool("click", "Activate the on-screen item whose text or label contains this text.", JSONObject().put("text", str("Visible text or label")), listOf("text")),
        tool("type_text", "Type into the focused text field on the TV (e.g. an app's search box).",
            JSONObject().put("text", str("Text to type")).put("submit", JSONObject().put("type", "boolean").put("description", "Press enter/search afterwards")), listOf("text")),
        tool("show_message", "Show a banner notification on the TV screen.", JSONObject().put("title", str("Title")).put("body", str("Optional second line")), listOf("title")),
        tool("sleep_timer", "Turn the TV off after N minutes (0, 10, 30, 60, 90 or 120; 0 cancels).", JSONObject().put("minutes", int("Minutes")), listOf("minutes")),
        tool("switch_input", "Switch to a TV input: 'TV', 'HDMI 1', 'HDMI 2', 'AV'...", JSONObject().put("input", str("Input name")), listOf("input")),
        tool("list_routines", "Scheduled routines (open an app/show at a time, even from standby)."),
        tool("add_routine", "Schedule an app or show to open at a time every day/weekdays/weekends/once. Wakes the TV from standby.",
            JSONObject().put("time", str("24h time HH:MM")).put("repeat", str("Repeat", listOf("once", "daily", "weekdays", "weekends")))
                .put("app", str("App name")).put("title", str("Optional show/movie to open in that app")), listOf("time", "app")),
        tool("delete_routine", "Delete a routine by id (from list_routines).", JSONObject().put("id", str("Routine id")), listOf("id")),
    ))

    // ---------------- tools ----------------
    private fun call(name: String, a: JSONObject): String = when (name) {
        "get_status" -> status().toString(2)
        "list_apps" -> JSONArray(apps().map { JSONObject().put("label", it.first).put("package", it.second) }).toString()
        "open_app" -> { val (label, pkg) = findApp(a.getString("app")) ?: throw IllegalArgumentException("No app matching '${a.getString("app")}'. Use list_apps.")
            wake(); launch(pkg); "Opened $label" }
        "play_title" -> playTitle(a.getString("title"), a.optString("app").takeIf { it.isNotBlank() }, a.optBoolean("autoplay", true))
        "where_to_watch" -> whereToWatch(a.getString("title")).let { m ->
            if (m.isEmpty()) "Not streaming on any installed app." else JSONArray(m.map { (label, pkg, hit) ->
                JSONObject().put("app", label).put("package", pkg).put("title", hit.title).put("year", hit.year).put("url", hit.url) }).toString(2) }
        "media" -> { MediaDump.key(app, when (a.getString("action")) {
                "play" -> KeyEvent.KEYCODE_MEDIA_PLAY; "pause" -> KeyEvent.KEYCODE_MEDIA_PAUSE; "play_pause" -> KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE
                "next" -> KeyEvent.KEYCODE_MEDIA_NEXT; "previous" -> KeyEvent.KEYCODE_MEDIA_PREVIOUS; "rewind" -> KeyEvent.KEYCODE_MEDIA_REWIND
                "fast_forward" -> KeyEvent.KEYCODE_MEDIA_FAST_FORWARD; "stop" -> KeyEvent.KEYCODE_MEDIA_STOP
                else -> throw IllegalArgumentException("unknown action") })
            "Sent ${a.getString("action")}" }
        "volume" -> volume(a)
        "power" -> power(a.getString("action"))
        "press" -> press(a.getString("button"), a.optInt("times", 1).coerceIn(1, 20))
        "read_screen" -> readScreen()
        "click" -> click(a.getString("text"))
        "type_text" -> { val ks = KeyService.instance ?: throw IllegalStateException("accessibility service not running")
            val t = a.getString("text")
            if (!onMain { ks.typeInto(t) }) "No text field is focused on the TV. Open the app's search first (read_screen / click)." else {
                if (a.optBoolean("submit")) imeEnter()
                "Typed \"$t\"" } }
        "show_message" -> { Banner.show(app, a.getString("title"), a.optString("body").takeIf { it.isNotBlank() }, ms = 6000); "Shown" }
        "sleep_timer" -> { val m = a.getInt("minutes"); val v = SleepTimer.choices.minByOrNull { Math.abs(it - m) } ?: 0
            SleepTimer.set(app, v); if (v == 0) "Sleep timer off" else "TV turns off in $v min" }
        "switch_input" -> switchInput(a.getString("input"))
        "list_routines" -> JSONArray(Routines.all(app).map { JSONObject().put("id", it.id).put("when", Routines.describe(it)).put("app", label(it.pkg)).put("title", it.title) }).toString(2)
        "add_routine" -> addRoutine(a)
        "delete_routine" -> { Routines.delete(app, a.getString("id")); "Deleted" }
        else -> throw IllegalArgumentException("Unknown tool $name")
    }

    private fun <T> onMain(f: () -> T): T {
        if (android.os.Looper.myLooper() == android.os.Looper.getMainLooper()) return f()
        val latch = java.util.concurrent.CountDownLatch(1); var r: Result<T>? = null
        android.os.Handler(android.os.Looper.getMainLooper()).post { r = runCatching(f); latch.countDown() }
        latch.await(5, java.util.concurrent.TimeUnit.SECONDS)
        return r?.getOrThrow() ?: throw IllegalStateException("timed out")
    }

    private fun pm() = app.packageManager
    private fun label(pkg: String) = try { pm().getApplicationLabel(pm().getApplicationInfo(pkg, 0)).toString() } catch (e: Exception) { pkg }
    private fun screenOn() = (app.getSystemService(Context.POWER_SERVICE) as PowerManager).isInteractive
    private fun frontPkg(): String? = try { KeyService.instance?.rootInActiveWindow?.packageName?.toString() } catch (e: Exception) { null }

    private fun status(): JSONObject {
        val am = app.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        val np = MediaDump.active(app, frontPkg())
        return JSONObject()
            .put("screen_on", screenOn())
            .put("app_in_front", frontPkg()?.let { JSONObject().put("package", it).put("label", if (it == app.packageName) "Arsh TV (home)" else label(it)) } ?: JSONObject.NULL)
            .put("now_playing", np?.let { JSONObject().put("app", label(it.pkg)).put("state", if (it.playing) "playing" else if (it.state == 2) "paused" else "unknown")
                .put("title", it.title ?: JSONObject.NULL).put("subtitle", it.sub ?: JSONObject.NULL).put("position_s", it.positionNow() / 1000) } ?: JSONObject.NULL)
            .put("volume", JSONObject().put("level", if (max > 0) am.getStreamVolume(AudioManager.STREAM_MUSIC) * 100 / max else 0)
                .put("muted", am.isStreamMute(AudioManager.STREAM_MUSIC)))
            .put("sleep_timer_min", SleepTimer.minutesLeft(app))
            .put("local_time", java.text.SimpleDateFormat("EEE d MMM yyyy, HH:mm", java.util.Locale.US).format(java.util.Date()))
    }

    // ---- apps ----
    private val aliases = mapOf("youtube" to "org.smarttube.stable", "yt" to "org.smarttube.stable", "prime" to "com.amazon.amazonvideo.livingroom",
        "prime video" to "com.amazon.amazonvideo.livingroom", "amazon" to "com.amazon.amazonvideo.livingroom",
        "settings" to "com.android.tv.settings", "tv settings" to "com.android.tv.settings", "home" to "com.arshanwar.tv_launcher", "launcher" to "com.arshanwar.tv_launcher")

    private val systemJunk = setOf("com.mediatek.tv.service", "com.mstar.android.tv.disclaimercustomization", "com.mediatek.wwtv.webview",
        "com.xiaomi.mitv.tvmanager", "com.android.deskclock")

    private fun apps(): List<Pair<String, String>> {
        val seen = HashSet<String>(); val out = ArrayList<Pair<String, String>>()
        for (cat in listOf(Intent.CATEGORY_LEANBACK_LAUNCHER, Intent.CATEGORY_LAUNCHER))
            for (ri in pm().queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(cat), 0)) {
                val p = ri.activityInfo.packageName
                if (p !in AppLock.hidden(app) && p !in systemJunk && p != app.packageName && seen.add(p)) out.add((if (p == "org.smarttube.stable") "YouTube (SmartTube)" else ri.loadLabel(pm()).toString()) to p)
            }
        return out.sortedBy { it.first.lowercase() }
    }

    private fun findApp(q: String): Pair<String, String>? {
        val n = q.trim().lowercase()
        aliases[n]?.let { p -> return label(p) to p }
        val all = apps()
        return all.firstOrNull { it.second == q } ?: all.firstOrNull { it.first.lowercase() == n }
            ?: all.firstOrNull { it.first.lowercase().startsWith(n) } ?: all.firstOrNull { it.first.lowercase().contains(n) }
    }

    private fun launch(pkg: String) {
        if (pkg == app.packageName) { startActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME).setPackage(pkg)); return }
        if (pkg == "com.android.tv.settings") { startActivity(Intent(android.provider.Settings.ACTION_SETTINGS)); return }   // the TV's main settings screen (same as Control Center)
        val i = pm().getLeanbackLaunchIntentForPackage(pkg) ?: pm().getLaunchIntentForPackage(pkg) ?: throw IllegalStateException("$pkg can't be launched")
        startActivity(i)
    }

    /** From the HTTP thread: the accessibility service lets this process start activities in the background. */
    private fun startActivity(i: Intent) {
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        KeyService.instance?.startActivity(i) ?: app.startActivity(i)
    }

    private fun wake() {
        if (screenOn()) return
        @Suppress("DEPRECATION")
        (app.getSystemService(Context.POWER_SERVICE) as PowerManager)
            .newWakeLock(PowerManager.SCREEN_BRIGHT_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP, "arshtv:mcp").acquire(10_000)
        Thread.sleep(1200)
    }

    // ---- titles ----
    /** (app label, package, hit) for every installed app streaming [title], best match first. */
    private fun whereToWatch(title: String): List<Triple<String, String, Where.Hit>> {
        val jw = JustWatch.packages(app)
        val techToPkg = HashMap<String, String>()
        for ((lbl, pkg) in apps()) JustWatch.matchApp(lbl.removeSuffix(" (SmartTube)"), jw)?.let { t -> techToPkg.putIfAbsent(t, pkg) }
        val out = ArrayList<Triple<String, String, Where.Hit>>()
        // search is fuzzy and popularity-ordered: keep only real matches ("Stranger Things" must not pull in "A Million Little Things")
        fun norm(x: String) = x.lowercase().replace(Regex("[^a-z0-9 ]"), " ").replace(Regex("\\s+"), " ").trim().removePrefix("the ")
        val q = norm(title)
        fun score(h: Where.Hit): Int { val t = norm(h.title); return when { t == q -> 0; t.startsWith("$q ") || q.startsWith("$t ") -> 1; else -> 9 } }
        for ((tech, hits) in Where.search(app, title)) {
            val pkg = techToPkg[tech] ?: continue
            hits.filter { score(it) < 9 }.minByOrNull { score(it) }?.let { h -> if (out.none { it.second == pkg }) out.add(Triple(label(pkg), pkg, h)) }
        }
        return out.sortedBy { score(it.third) }
    }

    private fun playTitle(title: String, prefer: String?, autoplay: Boolean = true): String {
        val options = whereToWatch(title)
        val wantPkg = prefer?.let { findApp(it)?.second }
        val pick = options.firstOrNull { it.second == wantPkg } ?: options.firstOrNull()
        wake()
        if (pick != null && pick.second == NETFLIX) return netflix(pick.third, autoplay) +
            if (options.size > 1) ". Also on: " + options.filter { it !== pick }.joinToString { it.first } else ""
        if (pick != null) {
            try { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(pick.third.url)).setPackage(pick.second)) }
            catch (e: Exception) { launch(pick.second); return "Opened ${pick.first} (couldn't jump straight to \"${pick.third.title}\"; search for it there)" }
            return "Opened \"${pick.third.title}\" (${pick.third.year}) in ${pick.first}" +
                if (options.size > 1) ". Also on: " + options.filter { it !== pick }.joinToString { it.first } else ""
        }
        if (wantPkg == "org.smarttube.stable" || prefer == null) {
            Shelf.search(title).firstOrNull()?.takeIf { pm().getLaunchIntentForPackage("com.stremio.one") != null || pm().getLeanbackLaunchIntentForPackage("com.stremio.one") != null }?.let {
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(it.deepLink)).setPackage("com.stremio.one"))
                return "Not on your streaming apps; opened \"${it.name}\" in Stremio"
            }
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://www.youtube.com/results?search_query=" + Uri.encode(title))).setPackage("org.smarttube.stable"))
            return "Not on your streaming apps; searched YouTube for \"$title\""
        }
        throw IllegalStateException("\"$title\" isn't on ${prefer} (or any installed app)")
    }

    // ---- Netflix: profile gate + play, verified through the system's playback list ----
    private const val NETFLIX = "com.netflix.ninja"
    private fun netflixPlaying() = MediaDump.read(app).any { it.pkg == NETFLIX && it.playing }
    private fun waitFront(pkg: String, ms: Long): Boolean { val end = System.currentTimeMillis() + ms
        while (System.currentTimeMillis() < end) { if (frontPkg() == pkg) return true; Thread.sleep(400) }; return false }
    private fun waitPlaying(ms: Long): Boolean { val end = System.currentTimeMillis() + ms
        while (System.currentTimeMillis() < end) { if (netflixPlaying()) return true; Thread.sleep(1000) }; return false }

    private fun netflix(hit: Where.Hit, autoplay: Boolean): String {
        val name = "\"${hit.title}\" (${hit.year})"
        if (!TvAdb.available()) {   // no remote keys: open the link and say honestly what may be on screen
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(hit.url)).setPackage(NETFLIX))
            return "Sent Netflix the link for $name, but couldn't press keys (ADB unavailable), so it may be waiting on 'Who's watching?'"
        }
        val link = "am start -a android.intent.action.VIEW -d '${hit.url}' -p $NETFLIX"
        val warm = frontPkg() == NETFLIX || MediaDump.read(app).any { it.pkg == NETFLIX }
        TvAdb.shell(link)
        if (!waitFront(NETFLIX, 15_000)) return "Netflix didn't come to the front (tried to open $name)"
        Thread.sleep(if (warm) 3500 else 8000)   // cold start: splash, then "Who's watching?"
        // OK here = picks the highlighted profile on the gate, or presses Play if Netflix went straight to the title
        TvAdb.key(KeyEvent.KEYCODE_DPAD_CENTER)
        if (autoplay && waitPlaying(6000)) return "Playing $name on Netflix"
        // now inside a profile: open the title again, so the link isn't lost behind the gate
        TvAdb.shell(link)
        Thread.sleep(4500)
        if (!autoplay) return "Netflix is open on $name (picked the highlighted profile if it asked)"
        TvAdb.key(KeyEvent.KEYCODE_DPAD_CENTER)        // Play / Resume is focused on a title page
        if (waitPlaying(10_000)) return "Playing $name on Netflix"
        TvAdb.key(KeyEvent.KEYCODE_DPAD_CENTER)        // one retry (slow load)
        if (waitPlaying(8000)) return "Playing $name on Netflix"
        throw IllegalStateException("Opened $name on Netflix but playback didn't start. It may need a profile PIN or a different profile; use press/get_status to continue.")
    }

    // ---- volume / power / input ----
    private fun volume(a: JSONObject): String {
        val am = app.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val s = AudioManager.STREAM_MUSIC; val max = am.getStreamMaxVolume(s)
        if (a.has("level")) am.setStreamVolume(s, (a.getInt("level").coerceIn(0, 100) * max + 50) / 100, AudioManager.FLAG_SHOW_UI)
        when (a.optString("change")) {
            "up" -> am.adjustStreamVolume(s, AudioManager.ADJUST_RAISE, AudioManager.FLAG_SHOW_UI)
            "down" -> am.adjustStreamVolume(s, AudioManager.ADJUST_LOWER, AudioManager.FLAG_SHOW_UI)
            "mute" -> am.adjustStreamVolume(s, AudioManager.ADJUST_MUTE, AudioManager.FLAG_SHOW_UI)
            "unmute" -> am.adjustStreamVolume(s, AudioManager.ADJUST_UNMUTE, AudioManager.FLAG_SHOW_UI)
        }
        return "Volume ${am.getStreamVolume(s) * 100 / maxOf(max, 1)}%" + if (am.isStreamMute(s)) " (muted)" else ""
    }

    private fun power(action: String): String = when (action) {
        "on" -> { if (screenOn()) "Already on" else { wake(); "Screen on" } }
        "off" -> {
            if (!screenOn()) "Already off" else {
                val ks = KeyService.instance ?: throw IllegalStateException("accessibility service not running")
                if (onMain { ks.performGlobalAction(AccessibilityService.GLOBAL_ACTION_LOCK_SCREEN) }) { Thread.sleep(1500); if (screenOn()) "Asked the TV to sleep, but it's still on" else "TV is now in standby" }
                else "This TV refused to go to standby; set sleep_timer instead"
            }
        }
        "screensaver" -> { wake(); startActivity(Intent(Intent.ACTION_MAIN).setClassName("com.android.systemui", "com.android.systemui.Somnambulator")); "Screensaver started" }
        else -> throw IllegalArgumentException("unknown action")
    }

    private fun switchInput(name: String): String {
        val tim = app.getSystemService(Context.TV_INPUT_SERVICE) as android.media.tv.TvInputManager
        val hw = tim.tvInputList.filter { it.serviceInfo.packageName == "com.mediatek.tvinput" }
        val n = name.trim().lowercase()
        wake()
        if (n == "tv" || n == "live tv" || n == "antenna") {
            launch("com.mediatek.wwtv.tvcenter"); return "Switched to TV"
        }
        val hdmis = hw.filter { it.type == android.media.tv.TvInputInfo.TYPE_HDMI }.sortedBy { it.id }
        val named = hdmis.mapIndexed { i, it -> (it.loadLabel(app)?.toString()?.takeIf { s -> s.isNotBlank() } ?: "HDMI ${i + 1}") to it } +
            hw.filter { it.type == android.media.tv.TvInputInfo.TYPE_COMPOSITE }.map { (it.loadLabel(app)?.toString()?.takeIf { s -> s.isNotBlank() } ?: "AV") to it }
        val hit = named.firstOrNull { it.first.lowercase() == n } ?: named.firstOrNull { it.first.lowercase().replace(" ", "") == n.replace(" ", "") }
            ?: Regex("(\\d)").find(n)?.groupValues?.get(1)?.toIntOrNull()?.let { k -> hdmis.getOrNull(k - 1)?.let { "HDMI $k" to it } }
            ?: throw IllegalArgumentException("No input '$name'. Inputs: TV, " + named.joinToString { it.first })
        startActivity(Intent(Intent.ACTION_VIEW, android.media.tv.TvContract.buildChannelUriForPassthroughInput(hit.second.id)))
        return "Switched to ${hit.first}"
    }

    // ---- screen (accessibility) ----
    private fun root(): AccessibilityNodeInfo = KeyService.instance?.rootInActiveWindow ?: throw IllegalStateException("Can't read the screen (screen off, or the app hides its UI)")

    private fun readScreen(): String {
        val r = root()
        val lines = ArrayList<String>()
        fun walk(n: AccessibilityNodeInfo?, depth: Int) {
            if (n == null || lines.size > 160 || depth > 40) return
            if (n.isVisibleToUser) {
                val t = (n.text ?: n.contentDescription)?.toString()?.trim()?.replace('\n', ' ')
                if (!t.isNullOrBlank()) {
                    val flags = listOfNotNull(if (n.isFocused || n.isAccessibilityFocused) "FOCUSED" else null, if (n.isClickable || n.isFocusable) "selectable" else null,
                        if (n.isEditable) "text field" else null, if (n.isSelected) "selected" else null, if (n.isChecked) "checked" else null)
                    lines.add((if (flags.isEmpty()) "" else "[${flags.joinToString()}] ") + t.take(140))
                } else if (n.isFocused) lines.add("[FOCUSED] (unlabelled item)")
            }
            for (i in 0 until n.childCount) walk(n.getChild(i), depth + 1)
        }
        walk(r, 0)
        return "App: ${label(r.packageName?.toString() ?: "?")}\n" + (if (lines.isEmpty()) "(nothing readable: this screen draws its own UI)" else lines.joinToString("\n"))
    }

    private fun click(text: String): String {
        val r = root()
        val hits = r.findAccessibilityNodeInfosByText(text).filter { it.isVisibleToUser }
        val node = hits.firstOrNull() ?: throw IllegalArgumentException("Nothing on screen says \"$text\". Use read_screen.")
        var c: AccessibilityNodeInfo? = node
        while (c != null && !c.isClickable && !c.isFocusable) c = c.parent
        val target = c ?: node
        target.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
        val ok = target.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        return if (ok) "Clicked \"${(node.text ?: node.contentDescription ?: text)}\"" else "Focused \"$text\" but the app ignored the click; try press select"
    }

    private val keyCodes = mapOf("up" to KeyEvent.KEYCODE_DPAD_UP, "down" to KeyEvent.KEYCODE_DPAD_DOWN, "left" to KeyEvent.KEYCODE_DPAD_LEFT,
        "right" to KeyEvent.KEYCODE_DPAD_RIGHT, "select" to KeyEvent.KEYCODE_DPAD_CENTER, "back" to KeyEvent.KEYCODE_BACK,
        "play_pause" to KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, "menu" to KeyEvent.KEYCODE_MENU)

    private fun press(button: String, times: Int): String {
        if (button == "home") { startActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME).setPackage(app.packageName)); return "Pressed home" }
        val code = keyCodes[button] ?: throw IllegalArgumentException("unknown button")
        // real remote keys (any app, including Netflix); accessibility focus moves only if ADB can't be reached
        val viaAdb = try { TvAdb.key(*IntArray(times) { code }); true } catch (e: Exception) { android.util.Log.w("TvHome", "mcp adb key", e); false }
        if (!viaAdb) pressA11y(button, times)
        Thread.sleep(350)
        val now = try { root().findFocus(AccessibilityNodeInfo.FOCUS_INPUT) } catch (e: Exception) { null }
        return "Pressed $button" + (if (times > 1) " \u00d7$times" else "") + (now?.let { n -> (n.text ?: n.contentDescription)?.let { " \u2192 focus on \"$it\"" } } ?: "") +
            if (viaAdb) "" else " (accessibility fallback: apps that draw their own UI may ignore it)"
    }

    private fun pressA11y(button: String, times: Int) {
        val ks = KeyService.instance ?: throw IllegalStateException("accessibility service not running")
        repeat(times) {
            when (button) {
                "back" -> onMain { ks.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK) }
                "select" -> root().findFocus(AccessibilityNodeInfo.FOCUS_INPUT)?.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                "play_pause" -> MediaDump.key(app, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE)
                else -> {
                    val dir = mapOf("up" to View.FOCUS_UP, "down" to View.FOCUS_DOWN, "left" to View.FOCUS_LEFT, "right" to View.FOCUS_RIGHT)[button] ?: return@repeat
                    root().findFocus(AccessibilityNodeInfo.FOCUS_INPUT)?.focusSearch(dir)?.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
                }
            }
            if (times > 1) Thread.sleep(250)
        }
    }

    private fun imeEnter() {
        val f = try { root().findFocus(AccessibilityNodeInfo.FOCUS_INPUT) } catch (e: Exception) { null } ?: return
        // API 30 has ACTION_IME_ENTER; on Android 10 the closest is clicking the field's own search/submit affordance
        if (android.os.Build.VERSION.SDK_INT >= 30) f.performAction(android.R.id.accessibilityActionImeEnter) else f.performAction(AccessibilityNodeInfo.ACTION_CLICK)
    }

    // ---- routines ----
    private fun addRoutine(a: JSONObject): String {
        val (h, m) = a.getString("time").split(":").map { it.trim().toInt() }.let { it[0] to it.getOrElse(1) { 0 } }
        require(h in 0..23 && m in 0..59) { "time must be HH:MM (24h)" }
        val days = when (a.optString("repeat", "daily")) { "once" -> 0; "weekdays" -> Routines.WEEKDAYS; "weekends" -> Routines.WEEKENDS; else -> Routines.EVERY_DAY }
        val (lbl, pkg) = findApp(a.getString("app")) ?: throw IllegalArgumentException("No app matching '${a.getString("app")}'")
        val title = a.optString("title").trim()
        val url = if (title.isNotBlank()) whereToWatch(title).firstOrNull { it.second == pkg }?.third?.url.orEmpty() else ""
        val r = Routines.Routine(Routines.newId(), h, m, days, pkg, url, title.ifBlank { lbl })
        Routines.add(app, r)
        return "Routine ${r.id}: ${Routines.describe(r)} → ${title.ifBlank { lbl }}" + (if (title.isNotBlank() && url.isBlank()) " (title not found on $lbl: it will just open the app)" else "")
    }
}
