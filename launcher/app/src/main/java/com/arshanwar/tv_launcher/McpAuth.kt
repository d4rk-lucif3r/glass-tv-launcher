package com.arshanwar.tv_launcher

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.view.Gravity
import android.view.KeyEvent
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLDecoder
import java.security.MessageDigest
import java.security.SecureRandom

/**
 * OAuth 2.1 for the TV's MCP server, the way MCP clients expect it (RFC 9728 protected-resource metadata, RFC 8414
 * server metadata, RFC 7591 dynamic client registration, authorization code + PKCE S256, rotating refresh tokens).
 * The "login" is physical presence: /oauth/authorize asks for a 6-digit code that only appears on the TV screen.
 * AI control is off until switched on in Control Center; connections are listed and revoked there.
 */
object McpAuth {
    class Res(val code: Int, val type: String, val body: String, val headers: Map<String, String> = emptyMap())
    class Connection(val id: String, val client: String, val created: Long, val lastUsed: Long)

    private lateinit var app: Context
    private val prefs by lazy { app.getSharedPreferences("mcp_auth", Context.MODE_PRIVATE) }
    private val rnd = SecureRandom()
    private const val ACCESS_TTL = 24 * 3600_000L
    private const val REFRESH_TTL = 90 * 24 * 3600_000L
    private const val CODE_TTL = 5 * 60_000L

    fun init(ctx: Context) { app = ctx.applicationContext }

    // ---------------- switch + connections (Control Center) ----------------
    var enabled: Boolean
        get() = prefs.getBoolean("enabled", false)
        set(v) { prefs.edit().putBoolean("enabled", v).apply(); if (!v) pending = null }

    /** The address clients should use: the public https origin once a request has arrived through a tunnel, else the LAN name. */
    val publicUrl: String get() = (prefs.getString("public_base", null) ?: "http://${ShareHub.HOST}.local:${ShareHub.DROP_PORT}") + "/mcp"

    @Synchronized private fun tokens(): JSONArray = try { JSONArray(prefs.getString("tokens", "[]")) } catch (e: Exception) { JSONArray() }
    @Synchronized private fun saveTokens(a: JSONArray) = prefs.edit().putString("tokens", a.toString()).apply()

    fun connections(): List<Connection> { val a = tokens()
        return (0 until a.length()).map { a.getJSONObject(it) }.map { Connection(it.getString("id"), it.optString("client", "AI app"), it.optLong("created"), it.optLong("used")) }
            .sortedByDescending { it.lastUsed } }

    @Synchronized fun revoke(id: String) { val a = tokens(); val out = JSONArray()
        for (i in 0 until a.length()) a.getJSONObject(i).takeIf { it.getString("id") != id }?.let { out.put(it) }; saveTokens(out) }

    // ---------------- helpers ----------------
    private fun token(bytes: Int = 32): String { val b = ByteArray(bytes); rnd.nextBytes(b)
        return android.util.Base64.encodeToString(b, android.util.Base64.URL_SAFE or android.util.Base64.NO_PADDING or android.util.Base64.NO_WRAP) }
    private fun sha(s: String) = MessageDigest.getInstance("SHA-256").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }
    private fun s256(verifier: String) = android.util.Base64.encodeToString(MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(Charsets.US_ASCII)),
        android.util.Base64.URL_SAFE or android.util.Base64.NO_PADDING or android.util.Base64.NO_WRAP)
    private fun json(o: Any, code: Int = 200, extra: Map<String, String> = emptyMap()) = Res(code, "application/json", o.toString(), mapOf("Cache-Control" to "no-store") + extra)
    private fun oauthErr(err: String, desc: String, code: Int = 400) = json(JSONObject().put("error", err).put("error_description", desc), code)
    private fun form(body: String): Map<String, String> = body.split('&').filter { '=' in it }.associate {
        URLDecoder.decode(it.substringBefore('='), "UTF-8") to URLDecoder.decode(it.substringAfter('='), "UTF-8") }

    /** Origin as the client sees it: the Funnel's https host when proxied, the LAN/Tailscale address otherwise. */
    fun base(h: Map<String, String>): String {
        // forwarded headers are only believed from the tunnel relay (Tailscale 100.64.0.0/10) or this device itself; anyone else on the
        // Wi-Fi could otherwise make Control Center display a look-alike address
        val trusted = h["x-remote-addr"].orEmpty().let { a -> a.startsWith("127.") || a.split('.').let { p -> p.size == 4 && p[0] == "100" && (p[1].toIntOrNull() ?: 0) in 64..127 } }
        val host = (if (trusted) h["x-forwarded-host"] else null) ?: h["host"] ?: "${ShareHub.HOST}.local:${ShareHub.DROP_PORT}"
        val proto = (if (trusted) h["x-forwarded-proto"] else null) ?: if (trusted && host.endsWith(".ts.net")) "https" else "http"
        val b = "$proto://$host"
        if (trusted && proto == "https" && prefs.getString("public_base", null) != b) prefs.edit().putString("public_base", b).apply()
        return b
    }

    // ---------------- metadata ----------------
    private fun resourceMeta(b: String) = json(JSONObject().put("resource", "$b/mcp").put("authorization_servers", JSONArray().put(b))
        .put("bearer_methods_supported", JSONArray().put("header")).put("resource_name", "Arsh TV (living room)"))
    private fun serverMeta(b: String) = json(JSONObject().put("issuer", b)
        .put("authorization_endpoint", "$b/oauth/authorize").put("token_endpoint", "$b/oauth/token").put("registration_endpoint", "$b/oauth/register")
        .put("response_types_supported", JSONArray().put("code")).put("grant_types_supported", JSONArray().put("authorization_code").put("refresh_token"))
        .put("code_challenge_methods_supported", JSONArray().put("S256")).put("token_endpoint_auth_methods_supported", JSONArray().put("none"))
        .put("scopes_supported", JSONArray().put("tv")))

    /** Routes the .well-known metadata and the oauth endpoints; null = not ours. */
    fun route(method: String, path: String, q: Map<String, String>, h: Map<String, String>, body: String): Res? {
        val b = base(h)
        return when {
            path.startsWith("/.well-known/oauth-protected-resource") -> resourceMeta(b)
            path.startsWith("/.well-known/oauth-authorization-server") || path.startsWith("/.well-known/openid-configuration") -> serverMeta(b)
            path == "/oauth/register" && method == "POST" -> register(body)
            path == "/oauth/authorize" && method == "GET" -> authorize(q)
            path == "/oauth/authorize" && method == "POST" -> confirm(form(body))
            path == "/oauth/token" && method == "POST" -> tokenEndpoint(form(body))
            else -> null
        }
    }

    /** 401 that tells MCP clients where to start OAuth. */
    fun challenge(h: Map<String, String>) = Res(401, "application/json", """{"error":"unauthorized"}""",
        mapOf("WWW-Authenticate" to "Bearer resource_metadata=\"${base(h)}/.well-known/oauth-protected-resource\""))

    /** Bearer check for /mcp; refreshes "last used" at most once a minute. */
    @Synchronized fun check(authHeader: String?): Boolean {
        if (!enabled) return false
        val t = authHeader?.removePrefix("Bearer ")?.trim()?.takeIf { it.isNotBlank() && authHeader.startsWith("Bearer ") } ?: return false
        val hash = sha(t); val a = tokens(); val now = System.currentTimeMillis()
        for (i in 0 until a.length()) { val o = a.getJSONObject(i)
            if (o.optString("access") == hash && o.optLong("access_exp") > now) {
                if (now - o.optLong("used") > 60_000) { o.put("used", now); saveTokens(a) }
                return true } }
        return false
    }

    // ---------------- registration ----------------
    @Synchronized private fun register(body: String): Res {
        val j = try { JSONObject(body) } catch (e: Exception) { return oauthErr("invalid_client_metadata", "JSON body required") }
        val uris = j.optJSONArray("redirect_uris") ?: return oauthErr("invalid_redirect_uri", "redirect_uris required")
        for (i in 0 until uris.length()) { val u = uris.getString(i)
            if (!(u.startsWith("https://") || u.startsWith("http://localhost") || u.startsWith("http://127.0.0.1") || Regex("^[a-z][a-z0-9+.-]*:").containsMatchIn(u) && !u.startsWith("http:")))
                return oauthErr("invalid_redirect_uri", "redirect_uri must be https, localhost or an app scheme") }
        val id = token(16); val name = j.optString("client_name").ifBlank { "AI app" }.take(60)
        val clients = try { JSONObject(prefs.getString("clients", "{}")) } catch (e: Exception) { JSONObject() }
        if (clients.length() > 100) clients.keys().asSequence().toList().take(50).forEach { clients.remove(it) }   // keep the store small
        clients.put(id, JSONObject().put("name", name).put("redirect_uris", uris))
        prefs.edit().putString("clients", clients.toString()).apply()
        return json(JSONObject().put("client_id", id).put("client_id_issued_at", System.currentTimeMillis() / 1000).put("client_name", name)
            .put("redirect_uris", uris).put("token_endpoint_auth_method", "none")
            .put("grant_types", JSONArray().put("authorization_code").put("refresh_token")).put("response_types", JSONArray().put("code")), 201)
    }
    private fun client(id: String?): JSONObject? = id?.let { try { JSONObject(prefs.getString("clients", "{}")).optJSONObject(it) } catch (e: Exception) { null } }

    // ---------------- authorize: code on the TV, typed in the browser ----------------
    class Pending(val id: String, val tvCode: String, val clientId: String, val clientName: String, val redirect: String, val state: String?,
                  val challenge: String, val created: Long, var tries: Int = 0)
    @Volatile var pending: Pending? = null; private set
    private val grants = HashMap<String, Triple<Pending, Long, Boolean>>()   // auth code → (request, issued, used)

    private fun authorize(q: Map<String, String>): Res {
        if (!enabled) return page("AI control is off", "Turn it on from the TV: Control Center → AI control. Then try connecting again.")
        val c = client(q["client_id"]) ?: return page("Unknown app", "This app isn't registered with the TV. Remove it in your AI app and add the TV again.")
        val redirect = q["redirect_uri"] ?: return page("Missing redirect", "The app didn't say where to return.")
        val uris = c.getJSONArray("redirect_uris")
        if ((0 until uris.length()).none { uris.getString(it) == redirect }) return page("Wrong redirect", "That return address isn't registered for this app.")
        if (q["response_type"] != "code" || q["code_challenge_method"] != "S256" || q["code_challenge"].isNullOrBlank())
            return page("Unsupported request", "The app must use the authorization code flow with PKCE (S256).")
        val p = Pending(token(12), (0 until 6).map { rnd.nextInt(10) }.joinToString(""), q["client_id"]!!, c.optString("name", "AI app"),
            redirect, q["state"], q["code_challenge"]!!, System.currentTimeMillis())
        pending = p
        showOnTv(p)
        return codePage(p, null)
    }

    private fun confirm(f: Map<String, String>): Res {
        val p = pending?.takeIf { it.id == f["req"] } ?: return page("Request expired", "Start connecting again from your AI app.")
        if (System.currentTimeMillis() - p.created > CODE_TTL) { pending = null; McpPairActivity.close(); return page("Code expired", "Start connecting again from your AI app.") }
        if (f["code"]?.filter { it.isDigit() } != p.tvCode) {
            if (++p.tries >= 5) { pending = null; McpPairActivity.close(); return page("Too many tries", "Start connecting again from your AI app.") }
            return codePage(p, "That's not the code on the TV. ${5 - p.tries} tries left.")
        }
        pending = null
        val code = token(24)
        synchronized(grants) { grants[code] = Triple(p, System.currentTimeMillis(), false) }
        McpPairActivity.close(success = p.clientName)
        val sep = if ('?' in p.redirect) '&' else '?'
        val loc = p.redirect + sep + "code=" + java.net.URLEncoder.encode(code, "UTF-8") + (p.state?.let { "&state=" + java.net.URLEncoder.encode(it, "UTF-8") } ?: "")
        return Res(302, "text/html; charset=utf-8", "<a href=\"${esc(loc)}\">Continue</a>", mapOf("Location" to loc))
    }

    // ---------------- token endpoint ----------------
    @Synchronized private fun tokenEndpoint(f: Map<String, String>): Res {
        if (!enabled) return oauthErr("access_denied", "AI control is off on the TV", 400)
        val now = System.currentTimeMillis()
        return when (f["grant_type"]) {
            "authorization_code" -> {
                val g = synchronized(grants) { grants.remove(f["code"] ?: "") } ?: return oauthErr("invalid_grant", "unknown or used code")
                val (p, issued, _) = g
                if (now - issued > 120_000) return oauthErr("invalid_grant", "code expired")
                if (f["client_id"] != p.clientId || f["redirect_uri"] != p.redirect) return oauthErr("invalid_grant", "client or redirect mismatch")
                if (s256(f["code_verifier"] ?: "") != p.challenge) return oauthErr("invalid_grant", "PKCE verification failed")
                issue(JSONObject().put("id", token(8)).put("client", p.clientName).put("client_id", p.clientId).put("created", now).put("used", now), null)
            }
            "refresh_token" -> {
                val hash = sha(f["refresh_token"] ?: ""); val a = tokens()
                val i = (0 until a.length()).firstOrNull { a.getJSONObject(it).optString("refresh") == hash } ?: return oauthErr("invalid_grant", "unknown refresh token (revoked?)")
                val o = a.getJSONObject(i)
                if (o.optLong("refresh_exp") < now) { revoke(o.getString("id")); return oauthErr("invalid_grant", "refresh token expired") }
                if (f["client_id"] != null && f["client_id"] != o.optString("client_id")) return oauthErr("invalid_grant", "client mismatch")
                issue(o, i)
            }
            else -> oauthErr("unsupported_grant_type", "use authorization_code or refresh_token")
        }
    }

    /** New access + refresh token for connection [o] (rotating: the old refresh token stops working). */
    private fun issue(o: JSONObject, index: Int?): Res {
        val now = System.currentTimeMillis(); val access = token(); val refresh = token()
        o.put("access", sha(access)).put("access_exp", now + ACCESS_TTL).put("refresh", sha(refresh)).put("refresh_exp", now + REFRESH_TTL)
        val a = tokens(); if (index != null && index < a.length()) a.put(index, o) else a.put(o); saveTokens(a)
        return json(JSONObject().put("access_token", access).put("token_type", "Bearer").put("expires_in", ACCESS_TTL / 1000)
            .put("refresh_token", refresh).put("scope", "tv"))
    }

    // ---------------- pages ----------------
    private fun esc(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")
    private const val STYLE = """<meta name="viewport" content="width=device-width,initial-scale=1"><style>
body{margin:0;min-height:100vh;font-family:-apple-system,system-ui,Segoe UI,Roboto,sans-serif;color:#fff;display:flex;align-items:center;justify-content:center;
background:radial-gradient(circle at 15% 10%,#4f5bff 0,#8e44e6 50%,#ff375f 100%)}
.c{box-sizing:border-box;width:min(92vw,400px);padding:30px 26px;border-radius:30px;background:rgba(255,255,255,.14);-webkit-backdrop-filter:blur(30px);backdrop-filter:blur(30px);
border:1px solid rgba(255,255,255,.35);text-align:center}h1{font-size:23px;margin:6px 0 8px}p{opacity:.85;font-size:15px;margin:0 0 18px;line-height:1.4}
input{width:100%;box-sizing:border-box;padding:16px;border-radius:16px;border:0;font-size:30px;text-align:center;letter-spacing:.35em;font-weight:600}
button{width:100%;margin-top:12px;padding:15px;border-radius:16px;border:0;font-size:17px;font-weight:600;background:#fff;color:#111}.e{color:#ffd0d8;margin-top:12px;font-size:14px}</style>"""
    private fun page(title: String, msg: String) = Res(200, "text/html; charset=utf-8",
        "<!doctype html><html><head><meta charset=\"utf-8\"><title>${esc(title)}</title>$STYLE</head><body><div class=\"c\"><h1>${esc(title)}</h1><p>${esc(msg)}</p></div></body></html>")
    private fun codePage(p: Pending, err: String?) = Res(200, "text/html; charset=utf-8",
        "<!doctype html><html><head><meta charset=\"utf-8\"><title>Connect to Arsh TV</title>$STYLE</head><body><div class=\"c\">" +
        "<h1>Connect ${esc(p.clientName)} to Arsh TV</h1><p>Enter the 6-digit code now showing on the TV.</p>" +
        "<form method=\"post\" action=\"/oauth/authorize\"><input type=\"hidden\" name=\"req\" value=\"${p.id}\">" +
        "<input name=\"code\" inputmode=\"numeric\" autocomplete=\"one-time-code\" maxlength=\"6\" pattern=\"[0-9]*\" autofocus placeholder=\"000000\">" +
        "<button>Connect</button></form>${err?.let { "<div class=\"e\">${esc(it)}</div>" } ?: ""}</div></body></html>")

    fun deny() { pending = null }

    private fun showOnTv(p: Pending) {
        val pm = app.getSystemService(Context.POWER_SERVICE) as PowerManager
        @Suppress("DEPRECATION")
        if (!pm.isInteractive) pm.newWakeLock(PowerManager.SCREEN_BRIGHT_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP, "arshtv:pair").acquire(10_000)
        val i = Intent(app, McpPairActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        KeyService.instance?.startActivity(i) ?: app.startActivity(i)
    }
}

/** Full-screen pairing card: "<App> wants to control this TV" + the code. Back = deny. Closes itself on success/expiry. */
class McpPairActivity : Activity() {
    private val main = Handler(Looper.getMainLooper())
    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        setShowWhenLocked(true); setTurnScreenOn(true)
        enableOwnAnimations(); Fonts.init(this)
        val p = McpAuth.pending ?: run { finish(); return }
        current = this
        window.setBackgroundDrawable(ColorDrawable(0xF2050608.toInt()))
        val d = { v: Int -> Kit.dp(this, v) }
        val card = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL; background = Kit.card(this@McpPairActivity)
            setPadding(d(44), d(34), d(44), d(30)) }
        card.addView(TextView(this).apply { text = "AI CONTROL"; typeface = Fonts.semibold; textSize = 12f; letterSpacing = 0.1f; setTextColor(0x99FFFFFF.toInt()); gravity = Gravity.CENTER })
        card.addView(TextView(this).apply { text = "${p.clientName} wants to control this TV"; typeface = Fonts.display; textSize = 26f; setTextColor(Color.WHITE)
            gravity = Gravity.CENTER; setPadding(0, d(8), 0, d(6)) }, LinearLayout.LayoutParams(d(560), -2))
        card.addView(TextView(this).apply { text = "To allow it, type this code on the page that just opened:"; typeface = Fonts.medium; textSize = 15f
            setTextColor(0xB3FFFFFF.toInt()); gravity = Gravity.CENTER })
        card.addView(TextView(this).apply { text = p.tvCode.chunked(3).joinToString("  "); typeface = Fonts.displayBold; textSize = 64f; letterSpacing = 0.12f
            setTextColor(Color.WHITE); gravity = Gravity.CENTER; setPadding(0, d(14), 0, d(10)) })
        card.addView(TextView(this).apply { text = "Expires in 5 minutes  ·  Back to deny  ·  Manage in Control Center → AI control"; typeface = Fonts.regular
            textSize = 12.5f; setTextColor(0x80FFFFFF.toInt()); gravity = Gravity.CENTER })
        val root = FrameLayout(this); root.addView(card, FrameLayout.LayoutParams(-2, -2, Gravity.CENTER)); setContentView(root)
        card.alpha = 0f; card.scaleX = 0.92f; card.scaleY = 0.92f
        card.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(500).setInterpolator(appleOut).start()
        main.postDelayed({ finish() }, 5 * 60_000L)
    }
    override fun onKeyDown(k: Int, e: KeyEvent?): Boolean {
        if (k == KeyEvent.KEYCODE_BACK) { McpAuth.deny(); finish(); return true }
        return super.onKeyDown(k, e)
    }
    override fun onDestroy() { if (current === this) current = null; main.removeCallbacksAndMessages(null); super.onDestroy() }
    companion object {
        @Volatile private var current: McpPairActivity? = null
        fun close(success: String? = null) = Handler(Looper.getMainLooper()).post {
            current?.finish()
            val svc = KeyService.instance
            if (success != null && svc != null) Banner.show(svc, "$success connected", "Manage in Control Center → AI control")
        }
    }
}
