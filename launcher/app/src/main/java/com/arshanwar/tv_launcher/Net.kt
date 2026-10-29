package com.arshanwar.tv_launcher

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import android.os.SystemClock
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.Inet4Address
import java.net.URL
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicLong
import kotlin.concurrent.thread

/** Snapshot of the active connection: Wi-Fi radio details plus IP configuration. */
object NetInfo {
    class Info(
        val connected: Boolean, val wifi: Boolean, val ethernet: Boolean,
        val ssid: String?, val freq: Int, val rssi: Int, val linkMbps: Int,
        val ip: String?, val gateway: String?, val dns: List<String>
    ) {
        val band get() = if (freq >= 4900) "5 GHz" else if (freq > 0) "2.4 GHz" else ""
        val channel get() = when { freq >= 5000 -> (freq - 5000) / 5; freq >= 2412 -> (freq - 2407) / 5; else -> 0 }
        /** 0..3 */
        val level get() = if (!wifi) 3 else WifiManager.calculateSignalLevel(rssi, 4)
        val quality get() = when { rssi >= -55 -> "Excellent"; rssi >= -67 -> "Good"; rssi >= -75 -> "Fair"; else -> "Weak" }
    }

    fun read(ctx: Context): Info {
        val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val net = cm.activeNetwork
        val caps = net?.let { cm.getNetworkCapabilities(it) }
        val lp = net?.let { cm.getLinkProperties(it) }
        val wifi = caps?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
        val eth = caps?.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) == true
        val ip = lp?.linkAddresses?.firstOrNull { it.address is Inet4Address }?.address?.hostAddress
        val gw = lp?.routes?.firstOrNull { it.isDefaultRoute && it.gateway is Inet4Address }?.gateway?.hostAddress
        val dns = lp?.dnsServers?.mapNotNull { it.hostAddress } ?: emptyList()
        var ssid: String? = null; var freq = 0; var rssi = -127; var link = 0
        if (wifi) {
            val wm = ctx.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            val wi = wm.connectionInfo
            ssid = wi.ssid?.trim('"')?.takeIf { it.isNotBlank() && it != "<unknown ssid>" }
            if (ssid == null) ssid = cachedSsid
            freq = wi.frequency; rssi = wi.rssi; link = wi.linkSpeed
        }
        return Info(net != null, wifi, eth, ssid, freq, rssi, link, ip, gw, dns)
    }

    /** Last SSID read from `dumpsys wifi` (see [resolveSsid]); used when WifiInfo redacts it. */
    @Volatile var cachedSsid: String? = null
    private val ssidLine = Regex("mWifiInfo SSID: (.+?), BSSID")

    /** Slow (spawns dumpsys): call off the UI thread. Needs the DUMP permission granted over adb. */
    fun resolveSsid() {
        cachedSsid = try {
            val p = Runtime.getRuntime().exec(arrayOf("dumpsys", "wifi"))
            val found = p.inputStream.bufferedReader().useLines { lines ->
                lines.take(3000).mapNotNull { ssidLine.find(it)?.groupValues?.get(1) }.firstOrNull()
            }
            p.destroy()
            found?.trim('"')?.takeIf { it.isNotBlank() && it != "<unknown ssid>" } ?: cachedSsid
        } catch (e: Exception) {
            cachedSsid
        }
    }
}

/**
 * Real-world speed test against Cloudflare's speed endpoints. Uses parallel HTTPS streams like
 * speed.cloudflare.com does, so it measures what apps on this TV actually get (TLS cost included).
 */
object SpeedTest {
    private const val HOST = "https://speed.cloudflare.com"
    private val failures = java.util.concurrent.atomic.AtomicInteger(0)

    /** Median round trip of tiny requests over a warm connection, in ms. */
    fun ping(): Int {
        val times = ArrayList<Long>()
        repeat(6) { i ->
            val t0 = SystemClock.elapsedRealtime()
            try {
                val c = URL("$HOST/__down?bytes=0").openConnection() as HttpURLConnection
                c.connectTimeout = 4000; c.readTimeout = 4000
                c.inputStream.use { it.readBytes() }
                if (i > 0) times.add(SystemClock.elapsedRealtime() - t0) // first one pays DNS + TLS setup
            } catch (e: Exception) {
            }
        }
        return if (times.isEmpty()) -1 else times.sorted()[times.size / 2].toInt()
    }

    fun download(seconds: Int, streams: Int, progress: (Double) -> Unit): Double =
        run(seconds, streams, progress) { counter, deadline, conns ->
            while (SystemClock.elapsedRealtime() < deadline) {
                // Cloudflare rejects requests above ~50 MB with 403, so loop over 25 MB chunks
                val c = URL("$HOST/__down?bytes=25000000").openConnection() as HttpURLConnection
                c.connectTimeout = 5000; c.readTimeout = 5000; conns.add(c)
                if (c.responseCode != 200) { c.disconnect(); failures.incrementAndGet(); Thread.sleep(300); continue }
                val input: InputStream = c.inputStream
                val buf = ByteArray(64 * 1024)
                while (SystemClock.elapsedRealtime() < deadline) {
                    val n = input.read(buf); if (n < 0) break
                    counter.addAndGet(n.toLong())
                }
                c.disconnect()
            }
        }

    fun upload(seconds: Int, streams: Int, progress: (Double) -> Unit): Double =
        run(seconds, streams, progress) { counter, deadline, conns ->
            val chunk = ByteArray(64 * 1024)
            while (SystemClock.elapsedRealtime() < deadline) {
                val total = 25_000_000
                val c = URL("$HOST/__up").openConnection() as HttpURLConnection
                c.connectTimeout = 5000; c.readTimeout = 5000; c.requestMethod = "POST"; c.doOutput = true
                c.setFixedLengthStreamingMode(total); conns.add(c)
                c.outputStream.use { out ->
                    var sent = 0
                    while (sent < total && SystemClock.elapsedRealtime() < deadline) {
                        val n = minOf(chunk.size, total - sent); out.write(chunk, 0, n); sent += n
                        counter.addAndGet(n.toLong())
                    }
                }
                if (SystemClock.elapsedRealtime() < deadline && c.responseCode != 200) { failures.incrementAndGet(); Thread.sleep(300) }
                c.disconnect()
            }
        }

    /** Runs [work] on [streams] threads for [seconds]; reports Mbps (after a 1 s TCP warm-up) every 250 ms. */
    private fun run(seconds: Int, streams: Int, progress: (Double) -> Unit,
                    work: (AtomicLong, Long, MutableList<HttpURLConnection>) -> Unit): Double {
        val counter = AtomicLong(0)
        failures.set(0)
        val conns = CopyOnWriteArrayList<HttpURLConnection>()
        val start = SystemClock.elapsedRealtime()
        val deadline = start + seconds * 1000L
        val threads = (1..streams).map {
            thread { try { work(counter, deadline, conns) } catch (e: Exception) { android.util.Log.w("TvHome", "speed test stream", e) } }
        }
        var baseBytes = -1L; var baseTime = 0L; var mbps = 0.0
        while (SystemClock.elapsedRealtime() < deadline) {
            Thread.sleep(250)
            val now = SystemClock.elapsedRealtime()
            if (baseBytes < 0 && now - start >= 1000) { baseBytes = counter.get(); baseTime = now; continue }
            if (baseBytes >= 0 && now > baseTime) {
                mbps = (counter.get() - baseBytes) * 8.0 / ((now - baseTime) / 1000.0) / 1_000_000.0
                progress(mbps)
            }
        }
        conns.forEach { try { it.disconnect() } catch (e: Exception) {} }
        threads.forEach { it.join(1500) }
        return if (counter.get() == 0L) -1.0 else mbps   // -1 = nothing got through
    }
}
