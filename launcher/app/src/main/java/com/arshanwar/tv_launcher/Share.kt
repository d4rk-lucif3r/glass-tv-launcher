package com.arshanwar.tv_launcher

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.database.Cursor
import android.database.MatrixCursor
import android.media.MediaScannerConnection
import android.net.Uri
import android.net.wifi.WifiManager
import android.os.Environment
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import android.webkit.MimeTypeMap
import org.json.JSONArray
import org.json.JSONObject
import java.io.*
import java.net.*
import java.util.*
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import kotlin.concurrent.thread

/**
 * Receives files from phones, AirDrop-style, two ways on one HTTP port (53317):
 *  - LocalSend v2 (free app for iPhone/Android): the TV announces itself as "Arsh TV" over UDP multicast
 *    and answers the LocalSend REST API. Every LocalSend transfer must be accepted on the TV.
 *  - Drop: a web page for any phone browser, reached via the QR code in the launcher. The QR carries a
 *    secret key, so only someone who can see the TV screen can send this way (no prompt needed).
 * Files land in Download/Arsh TV; DropActivity shows the prompt, progress and result.
 */
object ShareHub {
    const val PORT = 53317        // LocalSend: HTTPS + UDP multicast, like every LocalSend device
    const val DROP_PORT = 53318   // browser Drop page: plain HTTP so phones don't hit a certificate warning
    private const val MCAST = "224.0.0.167"
    const val ALIAS = "Arsh TV"
    /** mDNS name: phones and laptops on the Wi-Fi can open http://living-room-tv.local:53318 instead of the IP. */
    const val HOST = "living-room-tv"
    val friendlyUrl get() = "$HOST.local:$DROP_PORT"

    class FileMeta(val id: String, val name: String, val size: Long, val type: String)

    class Incoming(val id: String, val sender: String, val files: List<FileMeta>, val viaBrowser: Boolean) {
        @Volatile var decision: Boolean? = if (viaBrowser) true else null
        val latch = CountDownLatch(1)
        val tokens = HashMap<String, String>()
        val received = AtomicLong(0)
        val total = files.sumOf { it.size }
        val saved: MutableList<File> = Collections.synchronizedList(ArrayList())
        @Volatile var done = false
        @Volatile var cancelled = false
        val active get() = !done && !cancelled && decision != false
    }

    @Volatile var current: Incoming? = null
    private var started = false
    private lateinit var app: Context
    private val identity by lazy { TlsIdentity.load(app) }
    // LocalSend over HTTPS: fingerprint = SHA-256 of our certificate
    private val fingerprint get() = identity.fingerprint
    val dropKey: String by lazy {
        prefs.getString("drop_key", null) ?: UUID.randomUUID().toString().replace("-", "").take(10).also { prefs.edit().putString("drop_key", it).apply() }
    }
    private val prefs by lazy { app.getSharedPreferences("share", Context.MODE_PRIVATE) }

    val dir: File get() = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "Arsh TV").apply { mkdirs() }

    fun dropUrl(ip: String) = "http://$ip:$DROP_PORT/?k=$dropKey"

    @Synchronized fun start(ctx: Context) {
        if (started) return
        started = true
        app = ctx.applicationContext
        thread(name = "share-https", isDaemon = true) { serveForever(PORT, tls = true) }
        thread(name = "share-http", isDaemon = true) { serveForever(DROP_PORT, tls = false) }
        thread(name = "share-mcast", isDaemon = true) { multicastForever() }
        thread(name = "share-mdns", isDaemon = true) { mdnsForever() }
        TvMcp.init(app); McpAuth.init(app)
    }

    // ---------------- living-room-tv.local (minimal mDNS responder: answers A queries for our name) ----------------
    private fun myIPv4(): ByteArray? = try {
        java.net.NetworkInterface.getNetworkInterfaces().toList().filter { it.isUp && !it.isLoopback }
            .sortedBy { if (it.name.startsWith("eth")) 0 else if (it.name.startsWith("wlan")) 1 else 2 }
            .flatMap { it.inetAddresses.toList() }.firstOrNull { it is java.net.Inet4Address && it.isSiteLocalAddress }?.address
    } catch (e: Exception) { null }

    private fun mdnsName(): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        for (l in listOf(HOST, "local")) { out.write(l.length); out.write(l.toByteArray()) }
        out.write(0); return out.toByteArray()
    }

    /** Response carrying our A record (and the question, for legacy unicast queries that need it echoed). */
    private fun mdnsAnswer(id: Int, echoQuestion: Boolean, ip: ByteArray, qtype: Int = 1): ByteArray {
        val name = mdnsName()
        val b = java.nio.ByteBuffer.allocate(512)
        b.putShort(id.toShort()); b.putShort(0x8400.toShort()); b.putShort(if (echoQuestion) 1 else 0); b.putShort(1); b.putShort(0); b.putShort(1)
        if (echoQuestion) { b.put(name); b.putShort(qtype.toShort()); b.putShort(1) }
        b.put(name); b.putShort(1); b.putShort((if (echoQuestion) 0x0001 else 0x8001).toShort())   // A, IN (+cache-flush on multicast)
        b.putInt(120); b.putShort(4); b.put(ip)
        // NSEC "this name has only an A record": Macs/iPhones stop waiting ~5s for an IPv6 (AAAA) answer
        b.put(name); b.putShort(47); b.putShort(0x8001.toShort()); b.putInt(120); b.putShort((name.size + 3).toShort()); b.put(name); b.put(0); b.put(1); b.put(0x40)
        return b.array().copyOf(b.position())
    }

    /** Reads a (possibly compressed) DNS name at [start]; returns the name and the offset just past it. */
    private fun readName(d: ByteArray, start: Int, len: Int): Pair<String, Int> {
        val parts = ArrayList<String>(); var i = start; var end = -1; var hops = 0
        while (i < len && hops < 16) {
            val n = d[i].toInt() and 0xFF
            when {
                n == 0 -> { i++; break }
                n and 0xC0 == 0xC0 -> { if (end < 0) end = i + 2; i = ((n and 0x3F) shl 8) or (d[i + 1].toInt() and 0xFF); hops++ }
                else -> { parts.add(String(d, i + 1, n)); i += n + 1 }
            }
        }
        return parts.joinToString(".") to (if (end >= 0) end else i)
    }

    private fun mdnsForever() {
        val group = InetAddress.getByName("224.0.0.251")
        val me = "$HOST.local"
        while (true) {
            try {
                MulticastSocket(5353).use { sock ->
                    @Suppress("DEPRECATION") sock.joinGroup(group)
                    myIPv4()?.let { ip -> repeat(2) { val a = mdnsAnswer(0, false, ip); sock.send(DatagramPacket(a, a.size, group, 5353)) } }   // announce
                    val buf = ByteArray(1500)
                    while (true) {
                        val p = DatagramPacket(buf, buf.size)
                        sock.receive(p)
                        val d = p.data; val len = p.length
                        if (len < 12 || d[2].toInt() and 0x80 != 0) continue           // responses: ignore
                        val qd = ((d[4].toInt() and 0xFF) shl 8) or (d[5].toInt() and 0xFF)
                        var off = 12; var asked = false; var unicastBit = false; var qtype = 1
                        repeat(qd) {
                            val (name, next) = readName(d, off, len)
                            if (next + 4 > len) return@repeat
                            val type = ((d[next].toInt() and 0xFF) shl 8) or (d[next + 1].toInt() and 0xFF)
                            if (name.equals(me, true) && (type == 1 || type == 28 || type == 255)) { asked = true; qtype = type; unicastBit = unicastBit || (d[next + 2].toInt() and 0x80) != 0 }
                            off = next + 4
                        }
                        if (!asked) continue
                        val ip = myIPv4() ?: continue
                        val legacy = p.port != 5353   // one-shot resolver (e.g. some phones): reply straight back, echoing id + question
                        val id = ((d[0].toInt() and 0xFF) shl 8) or (d[1].toInt() and 0xFF)
                        val a = mdnsAnswer(if (legacy) id else 0, legacy, ip, qtype)
                        if (legacy || unicastBit) sock.send(DatagramPacket(a, a.size, p.address, p.port))
                        if (!legacy) sock.send(DatagramPacket(a, a.size, group, 5353))
                    }
                }
            } catch (e: Exception) {
                android.util.Log.w("TvHome", "mdns", e)
                Thread.sleep(5000)
            }
        }
    }

    // ---------------- discovery (LocalSend multicast) ----------------
    private fun info(announce: Boolean? = null) = JSONObject()
        .put("alias", ALIAS).put("version", "2.1").put("deviceModel", "Smart TV").put("deviceType", "desktop")
        .put("fingerprint", fingerprint).put("port", PORT).put("protocol", "https").put("download", false)
        .apply { if (announce != null) put("announce", announce) }

    private fun multicastForever() {
        // Wi-Fi drivers drop multicast unless someone holds this lock; held for the life of the process
        val wm = app.getSystemService(Context.WIFI_SERVICE) as WifiManager
        wm.createMulticastLock("arshtv-share").apply { setReferenceCounted(false); acquire() }
        while (true) {
            try {
                val group = InetAddress.getByName(MCAST)
                MulticastSocket(PORT).use { sock ->
                    @Suppress("DEPRECATION") sock.joinGroup(group)
                    fun send(announce: Boolean) {
                        val b = info(announce).toString().toByteArray()
                        sock.send(DatagramPacket(b, b.size, group, PORT))
                    }
                    send(true)
                    val buf = ByteArray(8192)
                    while (true) {
                        val p = DatagramPacket(buf, buf.size)
                        sock.receive(p)
                        val j = try { JSONObject(String(p.data, 0, p.length)) } catch (e: Exception) { continue }
                        if (j.optString("fingerprint") == fingerprint) continue
                        // a phone opened LocalSend's send screen: tell it we're here
                        if (j.optBoolean("announce", j.optBoolean("announcement", false))) send(false)
                    }
                }
            } catch (e: Exception) {
                android.util.Log.w("TvHome", "share multicast", e)
                Thread.sleep(5000)
            }
        }
    }

    // ---------------- tiny HTTP server ----------------
    private class Res(val code: Int, val type: String, val body: ByteArray, val headers: Map<String, String> = emptyMap())
    private fun json(o: Any, code: Int = 200) = Res(code, "application/json", o.toString().toByteArray())
    private fun empty(code: Int) = Res(code, "text/plain", ByteArray(0))

    private fun serveForever(port: Int, tls: Boolean) {
        while (true) {
            try {
                val server: ServerSocket = if (tls) identity.context.serverSocketFactory.createServerSocket() else ServerSocket()
                server.use { ss ->
                    ss.reuseAddress = true
                    ss.bind(InetSocketAddress(port))
                    while (true) {
                        val s = ss.accept()
                        thread(isDaemon = true) { try { handle(s) } catch (e: Exception) {} finally { try { s.close() } catch (e: Exception) {} } }
                    }
                }
            } catch (e: Exception) {
                android.util.Log.w("TvHome", "share http", e)
                Thread.sleep(5000)
            }
        }
    }

    private fun readLine(i: InputStream): String? {
        val sb = StringBuilder()
        while (true) {
            val c = i.read()
            if (c < 0) return if (sb.isEmpty()) null else sb.toString()
            if (c == '\n'.code) return sb.toString().trimEnd('\r')
            sb.append(c.toChar())
            if (sb.length > 16384) return null
        }
    }

    private fun handle(s: Socket) {
        s.soTimeout = 60_000
        if (s is javax.net.ssl.SSLSocket) try { s.startHandshake() } catch (e: Exception) {
            android.util.Log.w("TvShare", "TLS handshake failed from ${s.inetAddress.hostAddress}: $e"); return
        }
        val inp = BufferedInputStream(s.getInputStream(), 64 * 1024)
        val first = readLine(inp) ?: return
        val parts = first.split(' ')
        if (parts.size < 2) return
        val headers = HashMap<String, String>()
        while (true) {
            val l = readLine(inp) ?: break
            if (l.isEmpty()) break
            val c = l.indexOf(':')
            if (c > 0) headers[l.substring(0, c).trim().lowercase(Locale.US)] = l.substring(c + 1).trim()
        }
        val path = parts[1].substringBefore('?')
        val query = parts[1].substringAfter('?', "").split('&').filter { it.contains('=') }.associate {
            URLDecoder.decode(it.substringBefore('='), "UTF-8") to URLDecoder.decode(it.substringAfter('='), "UTF-8")
        }
        val body: InputStream = if (headers["transfer-encoding"]?.contains("chunked", true) == true) ChunkedIn(inp)
        else LimitedIn(inp, headers["content-length"]?.toLongOrNull() ?: 0L)

        headers["x-remote-addr"] = s.inetAddress?.hostAddress ?: ""   // set by us, never by the client (overwrites any such header)
        val res = try { route(parts[0], path, query, body, headers) } catch (e: Exception) {
            android.util.Log.w("TvHome", "share route $path", e); empty(500)
        }
        val out = BufferedOutputStream(s.getOutputStream())
        val reason = when (res.code) { 200 -> "OK"; 201 -> "Created"; 202 -> "Accepted"; 204 -> "No Content"; 302 -> "Found"; 400 -> "Bad Request"
            401 -> "Unauthorized"; 403 -> "Forbidden"; 404 -> "Not Found"; 405 -> "Method Not Allowed"; else -> "Status" }
        out.write(("HTTP/1.1 ${res.code} $reason\r\nContent-Type: ${res.type}\r\nContent-Length: ${res.body.size}\r\n" +
            res.headers.entries.joinToString("") { "${it.key}: ${it.value}\r\n" } +
            "Access-Control-Allow-Origin: *\r\nAccess-Control-Allow-Headers: *\r\nAccess-Control-Allow-Methods: GET, POST, DELETE, OPTIONS\r\nConnection: close\r\n\r\n").toByteArray())
        out.write(res.body)
        out.flush()
    }

    private fun route(method: String, path: String, q: Map<String, String>, body: InputStream, headers: Map<String, String> = emptyMap()): Res = when {
        // ---- MCP: the TV as a tool server for AI assistants (token-protected; see TvMcp) ----
        method == "OPTIONS" -> empty(204)
        // OAuth (code shown on the TV) + metadata that MCP clients discover; then the MCP endpoint itself
        path.startsWith("/.well-known/") || path.startsWith("/oauth/") ->
            McpAuth.route(method, path, q, headers, String(body.readBytes()))?.let { Res(it.code, it.type, it.body.toByteArray(), it.headers) } ?: empty(404)
        path == "/mcp" || path.startsWith("/mcp/") -> when {
            !McpAuth.enabled -> json(JSONObject().put("error", "AI control is off. Turn it on from the TV: Control Center \u2192 AI control."), 403)
            !McpAuth.check(headers["authorization"]) -> McpAuth.challenge(headers).let { Res(it.code, it.type, it.body.toByteArray(), it.headers) }
            else -> TvMcp.handle(method, String(body.readBytes())).let { (c, t, b) -> Res(c, t, b.toByteArray()) }
        }
        // ---- LocalSend v2 ----
        path == "/api/localsend/v2/info" || path == "/api/localsend/v1/info" -> json(info())
        path == "/api/localsend/v2/register" -> { body.readBytes(); json(info()) }
        path == "/api/localsend/v2/prepare-upload" -> prepare(JSONObject(String(body.readBytes())))
        path == "/api/localsend/v2/upload" -> upload(q["sessionId"], q["fileId"], q["token"], body)
        path == "/api/localsend/v2/cancel" -> { current?.takeIf { it.id == q["sessionId"] }?.cancelled = true; empty(200) }
        // ---- Drop (browser) ----
        // with the QR's key the upload starts straight away; a typed address must be accepted on the TV
        method == "GET" && (path == "/" || path == "/drop") -> Res(200, "text/html; charset=utf-8", DROP_PAGE.toByteArray())
        // ---- Remote: switch ADB on (PIN) / off from a phone or laptop, e.g. over Tailscale ----
        method == "GET" && path == "/remote" -> Res(200, "text/html; charset=utf-8", REMOTE_PAGE.toByteArray())
        method == "GET" && path == "/adb" -> json(adbStatus())
        method == "POST" && path == "/adb" -> adbSet(JSONObject(String(body.readBytes())))
        path == "/drop/begin" -> beginBrowser(JSONObject(String(body.readBytes())), trusted = q["k"] == dropKey)
        // phone keyboard (QR key required): mirrors the phone's text box into the focused field on the TV
        path == "/type" -> if (q["k"] != dropKey) empty(403) else {
            val text = String(body.readBytes())
            val ok = TypeBridge.type(text, enter = q["enter"] == "1")
            json(JSONObject().put("ok", ok))
        }
        path == "/drop/upload" -> {
            val inc = current
            upload(q["s"], q["f"], inc?.tokens?.get(q["f"] ?: ""), body)
        }
        else -> empty(404)
    }

    private fun busy() = current?.active == true

    private fun prepare(req: JSONObject): Res {
        if (busy()) return empty(409)
        val sender = req.optJSONObject("info")?.let { i -> i.optString("alias").ifBlank { i.optString("deviceModel") } }.orEmpty().ifBlank { "A phone" }
        val filesJ = req.getJSONObject("files")
        val files = filesJ.keys().asSequence().map { k ->
            val f = filesJ.getJSONObject(k)
            FileMeta(f.optString("id", k), f.optString("fileName", "file"), f.optLong("size"), f.optString("fileType"))
        }.toList()
        if (files.isEmpty()) return empty(204)
        val inc = Incoming(UUID.randomUUID().toString(), sender, files, viaBrowser = false)
        files.forEach { inc.tokens[it.id] = UUID.randomUUID().toString() }
        current = inc
        showUi()
        inc.latch.await(60, TimeUnit.SECONDS)   // LocalSend waits while the TV shows Accept / Decline
        if (inc.decision != true) { inc.decision = false; return empty(403) }
        return json(JSONObject().put("sessionId", inc.id).put("files", JSONObject(inc.tokens as Map<*, *>)))
    }

    private fun beginBrowser(req: JSONObject, trusted: Boolean): Res {
        if (busy()) return empty(409)
        val arr = req.optJSONArray("files") ?: JSONArray()
        val files = (0 until arr.length()).map { i ->
            val f = arr.getJSONObject(i)
            FileMeta("f$i", f.optString("name", "file"), f.optLong("size"), f.optString("type"))
        }
        if (files.isEmpty()) return empty(400)
        val inc = Incoming(UUID.randomUUID().toString().take(12), "A phone (browser)", files, viaBrowser = trusted)
        files.forEach { inc.tokens[it.id] = UUID.randomUUID().toString() }
        current = inc
        showUi()
        if (!trusted) {
            inc.latch.await(60, TimeUnit.SECONDS)
            if (inc.decision != true) { inc.decision = false; return empty(403) }
        }
        return json(JSONObject().put("session", inc.id).put("ids", JSONArray(files.map { it.id })))
    }

    private fun upload(session: String?, fileId: String?, token: String?, body: InputStream): Res {
        val inc = current ?: return empty(404)
        if (inc.id != session || inc.decision != true || inc.cancelled) return empty(403)
        val meta = inc.files.firstOrNull { it.id == fileId } ?: return empty(404)
        if (token == null || inc.tokens[meta.id] != token) return empty(403)
        val f = uniqueFile(meta.name)
        try {
            f.outputStream().use { o ->
                val buf = ByteArray(64 * 1024)
                while (true) {
                    if (inc.cancelled) throw IOException("cancelled")
                    val n = body.read(buf)
                    if (n < 0) break
                    o.write(buf, 0, n)
                    inc.received.addAndGet(n.toLong())
                }
            }
        } catch (e: Exception) {
            f.delete(); return empty(500)
        }
        inc.saved.add(f)
        MediaScannerConnection.scanFile(app, arrayOf(f.path), null, null)
        if (inc.saved.size >= inc.files.size) inc.done = true
        return empty(200)
    }

    private fun uniqueFile(name: String): File {
        val clean = name.substringAfterLast('/').substringAfterLast('\\').replace(Regex("[\\u0000-\\u001f:*?\"<>|]"), "_").ifBlank { "file" }
        var f = File(dir, clean)
        val base = clean.substringBeforeLast('.'); val ext = clean.substringAfterLast('.', "")
        var n = 1
        while (f.exists()) f = File(dir, if (ext.isEmpty()) "$base ($n)" else "$base ($n).$ext").also { n++ }
        return f
    }

    private fun showUi() {
        app.startActivity(Intent(app, DropActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP))
    }

    fun mimeOf(f: File): String = when (val ext = f.extension.lowercase(Locale.US)) {
        "heic", "heif" -> "image/heic"   // iPhone photos; Android's table doesn't list them
        else -> MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) ?: "application/octet-stream"
    }

    fun uriFor(f: File): Uri = Uri.Builder().scheme("content").authority("${app.packageName}.files").path(f.absolutePath).build()

    private class LimitedIn(val src: InputStream, var left: Long) : InputStream() {
        override fun read(): Int { if (left <= 0) return -1; val c = src.read(); if (c >= 0) left--; return c }
        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (left <= 0) return -1
            val n = src.read(b, off, minOf(len.toLong(), left).toInt())
            if (n > 0) left -= n
            return n
        }
    }

    private class ChunkedIn(val src: InputStream) : InputStream() {
        private var left = 0L; private var eof = false
        private fun next(): Boolean {
            if (eof) return false
            if (left == 0L) {
                var line = readLine(src) ?: return false.also { eof = true }
                if (line.isEmpty()) line = readLine(src) ?: return false.also { eof = true }
                left = line.substringBefore(';').trim().toLong(16)
                if (left == 0L) { eof = true; return false }
            }
            return true
        }
        override fun read(): Int { if (!next()) return -1; val c = src.read(); if (c >= 0) left--; return c }
        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (!next()) return -1
            val n = src.read(b, off, minOf(len.toLong(), left).toInt())
            if (n > 0) left -= n
            return n
        }
    }

    // ---------------- remote ADB switch ----------------
    private var pinFails = 0
    private var pinLockedUntil = 0L
    private fun launcherPrefs() = app.getSharedPreferences("launcher", Context.MODE_PRIVATE)
    private fun adbOn() = android.provider.Settings.Global.getInt(app.contentResolver, android.provider.Settings.Global.ADB_ENABLED, 0) == 1

    /** The TV's Tailscale address (100.64.0.0/10 on the VPN interface), if connected. */
    fun tailscaleIp(): String? = try {
        java.net.NetworkInterface.getNetworkInterfaces().toList().filter { it.isUp }.flatMap { it.inetAddresses.toList() }
            .firstOrNull { it is java.net.Inet4Address && it.address[0].toInt() and 0xFF == 100 && it.address[1].toInt() and 0xC0 == 64 }?.hostAddress
    } catch (e: Exception) { null }

    private fun adbStatus() = JSONObject().put("on", adbOn()).put("until", launcherPrefs().getLong("adb_until", 0))
        .put("tailscale", tailscaleIp() ?: JSONObject.NULL).put("lan", myIPv4()?.let { InetAddress.getByAddress(it).hostAddress } ?: JSONObject.NULL)

    @Synchronized private fun adbSet(j: JSONObject): Res {
        val on = j.optBoolean("on")
        val now = System.currentTimeMillis()
        if (on) {
            // 4-digit PIN over the network: 5 wrong tries lock it for 15 minutes
            if (now < pinLockedUntil) return json(JSONObject().put("ok", false).put("error", "Too many wrong PINs. Try again in ${(pinLockedUntil - now) / 60_000 + 1} min."), 429)
            if (!AppLock.hasPin(app)) return json(JSONObject().put("ok", false).put("error", "Set a PIN on the TV first (Wi-Fi panel \u2192 ADB access)."), 403)
            if (!AppLock.check(app, j.optString("pin"))) {
                if (++pinFails >= 5) { pinFails = 0; pinLockedUntil = now + 15 * 60_000; Banner.show(app, "Remote ADB locked for 15 min", "Too many wrong PINs") }
                return json(JSONObject().put("ok", false).put("error", "Wrong PIN"), 403)
            }
            pinFails = 0
        }
        try { android.provider.Settings.Global.putInt(app.contentResolver, android.provider.Settings.Global.ADB_ENABLED, if (on) 1 else 0) }
        catch (e: SecurityException) { return json(JSONObject().put("ok", false).put("error", "TV is missing WRITE_SECURE_SETTINGS (run install.sh)"), 500) }
        if (on) launcherPrefs().edit().putLong("adb_until", now + 2 * 3600_000L).apply() else launcherPrefs().edit().remove("adb_until").apply()
        AdbTimer.arm(app)
        Banner.show(app, if (on) "ADB turned on remotely" else "ADB turned off remotely", if (on) "Turns off by itself in 2 hours" else null)
        return json(adbStatus().put("ok", true))
    }

    private val REMOTE_PAGE = """<!doctype html><html><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<title>Arsh TV remote access</title><style>
body{margin:0;min-height:100vh;font-family:-apple-system,system-ui,Segoe UI,Roboto,sans-serif;color:#fff;display:flex;align-items:center;justify-content:center;
background:radial-gradient(circle at 15% 10%,#1e2a5a 0,#2a1f4a 50%,#4a1f33 100%)}
.c{box-sizing:border-box;width:min(92vw,420px);padding:30px 26px;border-radius:30px;background:rgba(255,255,255,.1);-webkit-backdrop-filter:blur(30px);backdrop-filter:blur(30px);
border:1px solid rgba(255,255,255,.25);text-align:center}h1{font-size:24px;margin:0 0 6px}p{opacity:.75;margin:0 0 20px;font-size:14px}
.s{font-size:17px;font-weight:600;margin:6px 0 18px}input{width:100%;box-sizing:border-box;padding:15px;border-radius:14px;border:0;font-size:22px;text-align:center;letter-spacing:.5em}
button{width:100%;margin-top:12px;padding:15px;border-radius:14px;border:0;font-size:16px;font-weight:600;background:#fff;color:#111}button.o{background:rgba(255,255,255,.16);color:#fff}
code{display:block;margin-top:18px;padding:12px;border-radius:12px;background:rgba(0,0,0,.3);font-size:13px;text-align:left;white-space:pre-wrap;user-select:all}
.e{color:#ff8fa3;margin-top:10px;font-size:14px;min-height:18px}</style></head><body><div class="c">
<h1>Remote access</h1><p>Switch on ADB to control the TV from your laptop (scrcpy, adb). It turns itself off after 2 hours.</p>
<div class="s" id="st">…</div>
<input id="pin" type="password" inputmode="numeric" maxlength="4" placeholder="PIN"><button id="on">Turn on ADB</button><button class="o" id="off">Turn off</button>
<div class="e" id="e"></div><code id="cmd"></code></div><script>
function show(j){var st=document.getElementById('st');st.textContent=j.on?('ADB is on'+(j.until?(' \u00b7 off at '+new Date(j.until).toLocaleTimeString([], {hour:'numeric',minute:'2-digit'})):'')):'ADB is off';
var ip=j.tailscale||j.lan;document.getElementById('cmd').textContent=ip?('adb connect '+ip+':5555\nscrcpy -s '+ip+':5555 --max-size 1280 --video-bit-rate 4M'):''}
function load(){fetch('/adb').then(function(r){return r.json()}).then(show)}
function set(on){var e=document.getElementById('e');e.textContent='';fetch('/adb',{method:'POST',body:JSON.stringify({on:on,pin:document.getElementById('pin').value})})
.then(function(r){return r.json()}).then(function(j){if(!j.ok){e.textContent=j.error||'Failed';return}document.getElementById('pin').value='';show(j)}).catch(function(){e.textContent='TV unreachable'})}
document.getElementById('on').onclick=function(){set(true)};document.getElementById('off').onclick=function(){set(false)};load()
</script></body></html>"""

    private val DROP_PAGE = """<!doctype html><html><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<title>Send to Arsh TV</title><style>
body{margin:0;min-height:100vh;font-family:-apple-system,system-ui,Segoe UI,Roboto,sans-serif;color:#fff;display:flex;align-items:center;justify-content:center;
background:radial-gradient(circle at 15% 10%,#4f5bff 0,#8e44e6 50%,#ff375f 100%)}
.c{box-sizing:border-box;width:min(92vw,420px);padding:30px 26px;border-radius:30px;background:rgba(255,255,255,.14);-webkit-backdrop-filter:blur(30px);backdrop-filter:blur(30px);
border:1px solid rgba(255,255,255,.35);text-align:center;box-shadow:0 30px 80px rgba(0,0,0,.25)}
h1{font-size:25px;margin:14px 0 6px;letter-spacing:-.02em}p{opacity:.82;margin:0 0 22px;font-size:15px}
label{display:block;padding:16px;border-radius:18px;background:#fff;color:#111;font-weight:600;font-size:17px;cursor:pointer}input{display:none}
.f{text-align:left;margin-top:14px;font-size:14px;white-space:nowrap;overflow:hidden;text-overflow:ellipsis}
.b{height:6px;border-radius:3px;background:rgba(255,255,255,.25);margin-top:6px;overflow:hidden}.b i{display:block;height:100%;width:0;background:#fff;transition:width .2s}
.ok{margin-top:18px;font-weight:600}</style></head><body><div class="c">
<svg width="64" height="64" viewBox="0 0 108 108"><rect width="108" height="108" rx="26" fill="rgba(255,255,255,.18)"/>
<path d="M35 79 L54 30 L73 79" stroke="#fff" stroke-width="8.5" stroke-linecap="round" stroke-linejoin="round" fill="none"/>
<path d="M49 57.5 L49 71.5 L61 64.5 Z" fill="#fff"/></svg>
<h1>Send to Arsh TV</h1><p>Photos, videos and files appear on the TV instantly.</p>
<label>Choose photos or files<input id="i" type="file" multiple></label><div id="l"></div>
<div id="kb" style="display:none;margin-top:26px;text-align:left"><div style="font-weight:600;margin-bottom:8px">Type on TV</div>
<input id="t" autocomplete="off" autocapitalize="off" placeholder="Select a text box on the TV, then type here" style="display:block;width:100%;box-sizing:border-box;padding:14px;border-radius:14px;border:0;font-size:16px">
<div id="ts" style="font-size:13px;opacity:.8;margin-top:8px"></div></div></div>
<script>
var K=new URLSearchParams(location.search).get('k')||'';
document.getElementById('i').onchange=function(){var fs=[].slice.call(this.files);if(!fs.length)return;var l=document.getElementById('l');l.innerHTML='';
var bars=fs.map(function(f){var d=document.createElement('div');d.className='f';d.innerHTML='<span></span><div class="b"><i></i></div>';d.firstChild.textContent=f.name;l.appendChild(d);return d.querySelector('i')});
fetch('/drop/begin?k='+K,{method:'POST',body:JSON.stringify({files:fs.map(function(f){return{name:f.name,size:f.size,type:f.type}})})})
.then(function(r){if(!r.ok)throw r.status;return r.json()}).then(function(j){var n=0;(function next(){
if(n>=fs.length){l.insertAdjacentHTML('beforeend','<div class="ok">✓ Sent to Arsh TV</div>');return}
var x=new XMLHttpRequest(),bar=bars[n];x.open('POST','/drop/upload?s='+j.session+'&f='+j.ids[n]);
x.upload.onprogress=function(e){if(e.lengthComputable)bar.style.width=(e.loaded/e.total*100)+'%'};
x.onload=function(){if(x.status!=200){bar.style.background='#ff375f';return}bar.style.width='100%';n++;next()};
x.onerror=function(){bar.style.background='#ff375f'};x.send(fs[n])})()})
.catch(function(){l.innerHTML='<p style="margin-top:16px">Declined on the TV, or it\u2019s busy. Try again.</p>'})};
if(K){var t=document.getElementById('t'),ts=document.getElementById('ts'),tm;document.getElementById('kb').style.display='block';
if(new URLSearchParams(location.search).get('t')){document.querySelector('h1').textContent='Type on Arsh TV';document.querySelector('p').textContent='Whatever you type here appears in the TV\u2019s search.';
document.querySelector('label').style.display='none';document.getElementById('kb').style.marginTop='6px';t.placeholder='Start typing\u2026';t.focus()}
function send(enter){fetch('/type?k='+K+(enter?'&enter=1':''),{method:'POST',body:t.value}).then(function(r){return r.json()}).then(function(j){ts.textContent=j.ok?'\u2713 Typing on TV':'Select a text box on the TV first'}).catch(function(){})}
t.oninput=function(){clearTimeout(tm);tm=setTimeout(function(){send(false)},150)};
t.onkeydown=function(e){if(e.key==='Enter'){e.preventDefault();send(true)}}}
if(!K)document.querySelector('p').textContent='You\u2019ll need to accept on the TV. Scan the QR code to skip this.';
document.querySelector('.c').insertAdjacentHTML('beforeend','<a href="/remote" style="display:block;margin-top:22px;color:#fff;opacity:.75;font-size:14px">Remote access \u203a</a>');
</script></body></html>"""
}

/** Serves received/browsed files to players (VLC etc.) as content:// URIs. Read-only, storage paths only. */
class FileProvider : ContentProvider() {
    private fun file(uri: Uri): File? {
        val f = File(uri.path ?: return null).canonicalFile
        val ok = f.path.startsWith("/storage/") || f.path.startsWith("/sdcard/") || f.path.startsWith("/mnt/")
        return if (ok && f.isFile) f else null
    }
    override fun onCreate() = true
    override fun getType(uri: Uri) = file(uri)?.let { ShareHub.mimeOf(it) }
    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor? =
        file(uri)?.let { ParcelFileDescriptor.open(it, ParcelFileDescriptor.MODE_READ_ONLY) }
    override fun query(uri: Uri, projection: Array<out String>?, sel: String?, args: Array<out String>?, sort: String?): Cursor? {
        val f = file(uri) ?: return null
        val cols = projection ?: arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE)
        return MatrixCursor(cols).apply {
            addRow(cols.map { when (it) { OpenableColumns.DISPLAY_NAME -> f.name; OpenableColumns.SIZE -> f.length(); else -> null } })
        }
    }
    override fun insert(uri: Uri, values: ContentValues?) = null
    override fun delete(uri: Uri, sel: String?, args: Array<out String>?) = 0
    override fun update(uri: Uri, values: ContentValues?, sel: String?, args: Array<out String>?) = 0
}

/**
 * Self-signed RSA certificate for the LocalSend HTTPS server, generated once on the TV and kept in app storage.
 * (LocalSend peers trust any certificate and pin it by its SHA-256 fingerprint, like between phones.)
 */
class TlsIdentity private constructor(val context: javax.net.ssl.SSLContext, val fingerprint: String) {
    companion object {
        fun load(ctx: Context): TlsIdentity {
            // v2 files = X.509 v3 certificate (iOS rejected the earlier bare v1 one during the TLS handshake)
            val keyF = File(ctx.filesDir, "tls2.key"); val certF = File(ctx.filesDir, "tls2.crt")
            File(ctx.filesDir, "tls.key").delete(); File(ctx.filesDir, "tls.crt").delete()
            if (!keyF.exists() || !certF.exists()) {
                val kp = java.security.KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
                certF.writeBytes(selfSign(kp)); keyF.writeBytes(kp.private.encoded)
            }
            val key = java.security.KeyFactory.getInstance("RSA").generatePrivate(java.security.spec.PKCS8EncodedKeySpec(keyF.readBytes()))
            val cert = java.security.cert.CertificateFactory.getInstance("X.509").generateCertificate(certF.readBytes().inputStream())
            val ks = java.security.KeyStore.getInstance("PKCS12").apply { load(null, null); setKeyEntry("tv", key, CharArray(0), arrayOf(cert)) }
            val kmf = javax.net.ssl.KeyManagerFactory.getInstance(javax.net.ssl.KeyManagerFactory.getDefaultAlgorithm()).apply { init(ks, CharArray(0)) }
            val sc = javax.net.ssl.SSLContext.getInstance("TLS").apply { init(kmf.keyManagers, null, null) }
            val fp = java.security.MessageDigest.getInstance("SHA-256").digest(cert.encoded).joinToString("") { "%02X".format(it) }
            return TlsIdentity(sc, fp)
        }

        // ---- minimal DER encoder for a v1 X.509 certificate ----
        private fun len(n: Int): ByteArray = when {
            n < 0x80 -> byteArrayOf(n.toByte())
            n < 0x100 -> byteArrayOf(0x81.toByte(), n.toByte())
            else -> byteArrayOf(0x82.toByte(), (n shr 8).toByte(), n.toByte())
        }
        private fun tlv(tag: Int, v: ByteArray) = byteArrayOf(tag.toByte()) + len(v.size) + v
        private fun seq(vararg p: ByteArray) = tlv(0x30, p.fold(ByteArray(0)) { a, b -> a + b })
        private fun oid(vararg parts: Int): ByteArray {
            val out = ArrayList<Byte>(); out.add((parts[0] * 40 + parts[1]).toByte())
            for (p in parts.drop(2)) {
                val stack = ArrayList<Byte>(); var v = p; stack.add((v and 0x7f).toByte()); v = v shr 7
                while (v > 0) { stack.add(((v and 0x7f) or 0x80).toByte()); v = v shr 7 }
                out.addAll(stack.reversed())
            }
            return tlv(0x06, out.toByteArray())
        }
        private fun name(cn: String) = seq(
            tlv(0x31, seq(oid(2, 5, 4, 10), tlv(0x0C, "Arsh TV".toByteArray()))),
            tlv(0x31, seq(oid(2, 5, 4, 3), tlv(0x0C, cn.toByteArray()))))
        private fun time(d: java.util.Date) = tlv(0x17, java.text.SimpleDateFormat("yyMMddHHmmss'Z'", java.util.Locale.US)
            .apply { timeZone = java.util.TimeZone.getTimeZone("UTC") }.format(d).toByteArray())
        private fun ext(oidBytes: ByteArray, critical: Boolean, value: ByteArray) =
            if (critical) seq(oidBytes, byteArrayOf(0x01, 0x01, 0xFF.toByte()), tlv(0x04, value)) else seq(oidBytes, tlv(0x04, value))

        private fun selfSign(kp: java.security.KeyPair): ByteArray {
            val alg = seq(oid(1, 2, 840, 113549, 1, 1, 11), byteArrayOf(0x05, 0x00))   // sha256WithRSAEncryption
            val serial = tlv(0x02, byteArrayOf(0x01) + ByteArray(7).also { java.security.SecureRandom().nextBytes(it) })
            val now = System.currentTimeMillis()
            val validity = seq(time(java.util.Date(now - 86400_000L)), time(java.util.Date(now + 3650L * 86400_000L)))   // 10 years, like LocalSend
            val extensions = tlv(0xA3, seq(
                ext(oid(2, 5, 29, 19), true, seq()),                                              // basicConstraints: not a CA
                ext(oid(2, 5, 29, 15), true, byteArrayOf(0x03, 0x02, 0x05, 0xA0.toByte())),       // keyUsage: digitalSignature, keyEncipherment
                ext(oid(2, 5, 29, 37), false, seq(oid(1, 3, 6, 1, 5, 5, 7, 3, 1), oid(1, 3, 6, 1, 5, 5, 7, 3, 2))), // serverAuth, clientAuth
                ext(oid(2, 5, 29, 17), false, seq(tlv(0x82, "arsh-tv.local".toByteArray())))     // subjectAltName
            ))
            val version = tlv(0xA0, tlv(0x02, byteArrayOf(0x02)))   // v3
            val tbs = seq(version, serial, alg, name("Arsh TV"), validity, name("Arsh TV"), kp.public.encoded, extensions)
            val sig = java.security.Signature.getInstance("SHA256withRSA").apply { initSign(kp.private); update(tbs) }.sign()
            return seq(tbs, alg, tlv(0x03, byteArrayOf(0) + sig))
        }
    }
}

/** Routes phone typing: the launcher's own search box when it's open, otherwise the focused field via accessibility. */
object TypeBridge {
    /** Set by MainActivity while its search overlay is open; returns true if it took the text. */
    @Volatile var launcherSink: ((String, Boolean) -> Boolean)? = null
    fun type(text: String, enter: Boolean): Boolean {
        val latch = java.util.concurrent.CountDownLatch(1); var ok = false
        android.os.Handler(android.os.Looper.getMainLooper()).post {
            ok = launcherSink?.invoke(text, enter) ?: (KeyService.instance?.typeInto(text) ?: false)
            latch.countDown()
        }
        latch.await(2, java.util.concurrent.TimeUnit.SECONDS)
        return ok
    }
}
