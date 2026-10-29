package com.arshanwar.tv_launcher

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

class ShelfItem(
    val source: String, val id: String, val type: String, val name: String, val background: String, val logo: String,
    val poster: String, val year: String, val rating: String, val genres: List<String>, val description: String,
    val trailer: String = "",  // YouTube id of the official trailer (Cinemeta)
    val url: String = ""       // the service's own page for this title (JustWatch), opened in its app
) {
    val meta: String
        get() = listOfNotNull(
            year.ifBlank { null },
            if (type == "series") "Series" else "Movie",
            rating.ifBlank { null }?.let { "IMDb $it" },
            genres.take(2).joinToString(", ").ifBlank { null }
        ).joinToString("  ·  ")

    val deepLink: String
        get() = if (type == "series") "stremio:///detail/series/$id" else "stremio:///detail/movie/$id/$id"
}

/**
 * Top Shelf content. "stremio" = Cinemeta trending; "yt" = their trailers; "jw:<service>" = what's popular on any
 * installed streaming app in the viewer's region (JustWatch). Title logos come from metahub (IMDb-keyed).
 */
object Shelf {
    const val STREMIO = "stremio"
    const val YOUTUBE = "yt"
    private const val TOP = "https://cinemeta-catalogs.strem.io/top/catalog"
    private const val SEARCH = "https://v3-cinemeta.strem.io/catalog"

    fun load(ctx: Context, source: String, maxAgeMs: Long = 6 * 3600_000L): List<ShelfItem> {
        // any other installed streaming app, matched automatically to its JustWatch service ("jw:<technicalName>")
        if (source.startsWith("jw:")) return JustWatch.shelf(ctx, source.removePrefix("jw:"), maxAgeMs).filter { isSafe(it) }
        // YouTube shelf = official trailers of what's trending, artwork = the trailer's full-HD thumbnail
        if (source == YOUTUBE) return load(ctx, STREMIO, maxAgeMs).filter { it.trailer.isNotBlank() }.map {
            ShelfItem(YOUTUBE, it.id, it.type, it.name, "https://i.ytimg.com/vi/${it.trailer}/maxresdefault.jpg", it.logo,
                it.poster, it.year, it.rating, it.genres, it.description, it.trailer)
        }
        if (source != STREMIO) return emptyList()
        val f = File(ctx.filesDir, "shelf_$source.json")
        val fresh = f.exists() && System.currentTimeMillis() - f.lastModified() < maxAgeMs
        if (!fresh) try {
            val series = JSONObject(get("$TOP/series/top.json")).getJSONArray("metas")
            val movies = JSONObject(get("$TOP/movie/top.json")).getJSONArray("metas")
            val out = JSONArray()
            for (i in 0 until 8) {
                if (i < series.length()) out.put(series.get(i))
                if (i < movies.length()) out.put(movies.get(i))
            }
            f.writeText(out.toString())
        } catch (e: Exception) {
            android.util.Log.w("TvHome", "shelf fetch failed", e)
        }
        if (!f.exists()) return emptyList()
        return try { parse(JSONArray(f.readText()), source).filter { it.background.isNotBlank() && isSafe(it) } } catch (e: Exception) { emptyList() }
    }

    /** Keep sexual / adult titles off the Top Shelf and lock screen (the catalogs carry no certification). */
    private val unsafe = Regex(
        "\\b(sex|sexual|sexy|sapiosexual|nude|nudes|nudity|naked|erotic|erotica|porn|porno|pornographic|swinger|swingers|" +
            "seduce|seduction|seductive|lust|lustful|steamy|kinky|kink|fetish|stripper|strippers|escort|escorts|orgy|threesome|" +
            "mastram|adult film|adult star|playboy|hentai|xxx)\\b|xxx", RegexOption.IGNORE_CASE
    )
    fun isSafe(it: ShelfItem) = !unsafe.containsMatchIn(it.name + " " + it.description + " " + it.genres.joinToString(" "))

    fun search(q: String): List<ShelfItem> {
        val enc = URLEncoder.encode(q.trim(), "UTF-8").replace("+", "%20")
        val out = ArrayList<ShelfItem>()
        for (t in listOf("movie", "series")) try {
            out += parse(JSONObject(get("$SEARCH/$t/top/search=$enc.json")).getJSONArray("metas"), STREMIO).take(10)
        } catch (e: Exception) {
        }
        return out.filter { it.poster.isNotBlank() }
    }

    private fun parse(a: JSONArray, source: String): List<ShelfItem> = (0 until a.length()).mapNotNull { i ->
        val m = a.getJSONObject(i)
        val id = m.optString("imdb_id").ifBlank { m.optString("id") }
        if (!id.startsWith("tt")) return@mapNotNull null
        val g = m.optJSONArray("genres")
        val genres = if (g == null) emptyList() else (0 until g.length()).map { g.getString(it) }
        ShelfItem(
            source, id, m.optString("type", "movie"), m.optString("name"), m.optString("background"), m.optString("logo"),
            m.optString("poster"), m.optString("releaseInfo").ifBlank { m.optString("year") },
            m.optString("imdbRating"), genres, m.optString("description"),
            m.optJSONArray("trailers")?.optJSONObject(0)?.optString("source").orEmpty()
                .ifBlank { m.optJSONArray("trailerStreams")?.optJSONObject(0)?.optString("ytId").orEmpty() }
        )
    }

    fun get(url: String): String {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 8000; c.readTimeout = 12000; c.instanceFollowRedirects = true
        try {
            if (c.responseCode != 200) throw IOException("HTTP ${c.responseCode}")
            return c.inputStream.bufferedReader().readText()
        } finally {
            c.disconnect()
        }
    }
}

/** Disk cache for artwork. Backgrounds use the "bg_" prefix so the lock screen can reuse them offline. */
object Images {
    fun dir(ctx: Context) = File(ctx.cacheDir, "img").apply { mkdirs() }

    fun file(ctx: Context, url: String, prefix: String): File? {
        if (url.isBlank()) return null
        val f = File(dir(ctx), prefix + Integer.toHexString(url.hashCode()))
        if (f.exists() && f.length() > 0) return f
        return try {
            val c = URL(url).openConnection() as HttpURLConnection
            c.connectTimeout = 8000; c.readTimeout = 15000; c.instanceFollowRedirects = true
            if (c.responseCode != 200) { c.disconnect(); return null }
            val tmp = File(f.path + ".part")
            c.inputStream.use { i -> tmp.outputStream().use { i.copyTo(it) } }
            c.disconnect()
            if (tmp.renameTo(f)) f else null
        } catch (e: Exception) {
            android.util.Log.w("TvHome", "image fetch failed $url", e)
            null
        }
    }

    fun decode(f: File, maxW: Int): Bitmap? {
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(f.path, o)
        if (o.outWidth <= 0) return null
        var s = 1
        while (o.outWidth / (s * 2) >= maxW) s *= 2
        return BitmapFactory.decodeFile(f.path, BitmapFactory.Options().apply { inSampleSize = s })
    }

    fun backgrounds(ctx: Context): List<File> =
        dir(ctx).listFiles { f -> f.name.startsWith("bg_") && !f.name.endsWith(".part") }?.toList() ?: emptyList()

    /** Drop cached backdrops that no longer belong to a (safe) shelf item, so the lock screen never shows them. */
    fun keepBackgrounds(ctx: Context, urls: Collection<String>) {
        val keep = urls.map { "bg_" + Integer.toHexString(it.hashCode()) }.toSet()
        backgrounds(ctx).filter { it.name !in keep }.forEach { it.delete() }
    }

    fun trim(ctx: Context, keep: Int = 60) {
        dir(ctx).listFiles()?.sortedByDescending { it.lastModified() }?.drop(keep)?.forEach { it.delete() }
    }
}

/** Weather: location from IP (cached a day), current conditions + 5-day forecast from Open-Meteo. Both keyless. */
object Weather {
    class Day(val label: String, val code: Int, val max: Int, val min: Int)
    class Now(
        val temp: Int, val code: Int, val day: Boolean, val city: String,
        val feels: Int, val humidity: Int, val wind: Int, val hi: Int, val lo: Int,
        val sunrise: String, val sunset: String, val days: List<Day>
    )

    fun describe(code: Int) = when (code) {
        0 -> "Clear"; 1 -> "Mostly clear"; 2 -> "Partly cloudy"; 3 -> "Cloudy"; 45, 48 -> "Fog"
        in 51..57 -> "Drizzle"; in 61..67 -> "Rain"; in 71..77 -> "Snow"; in 80..82 -> "Showers"; 85, 86 -> "Snow showers"
        in 95..99 -> "Thunderstorm"; else -> ""
    }

    private fun hm(iso: String): String = try {
        val t = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm", java.util.Locale.US).parse(iso)
        java.text.SimpleDateFormat("h:mm a", java.util.Locale.getDefault()).format(t!!)
    } catch (e: Exception) { "" }

    fun load(ctx: Context): Now? = try {
        val p = ctx.getSharedPreferences("weather", Context.MODE_PRIVATE)
        if (!p.contains("lat") || !p.contains("cc") || System.currentTimeMillis() - p.getLong("locAt", 0) > 24 * 3600_000L) {
            val g = JSONObject(Shelf.get("https://ipwho.is/"))
            if (g.optBoolean("success")) p.edit()
                .putFloat("lat", g.getDouble("latitude").toFloat()).putFloat("lon", g.getDouble("longitude").toFloat())
                .putString("city", g.optString("city")).putString("cc", g.optString("country_code")).putLong("locAt", System.currentTimeMillis()).apply()
        }
        if (!p.contains("lat")) null else {
            val j = JSONObject(Shelf.get(
                "https://api.open-meteo.com/v1/forecast?latitude=${p.getFloat("lat", 0f)}&longitude=${p.getFloat("lon", 0f)}" +
                    "&current=temperature_2m,weather_code,is_day,apparent_temperature,relative_humidity_2m,wind_speed_10m" +
                    "&daily=weather_code,temperature_2m_max,temperature_2m_min,sunrise,sunset&timezone=auto&forecast_days=5"
            ))
            val c = j.getJSONObject("current"); val d = j.getJSONObject("daily")
            val dates = d.getJSONArray("time"); val codes = d.getJSONArray("weather_code")
            val maxs = d.getJSONArray("temperature_2m_max"); val mins = d.getJSONArray("temperature_2m_min")
            val inF = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US)
            val outF = java.text.SimpleDateFormat("EEE", java.util.Locale.getDefault())
            val days = (0 until dates.length()).map { i ->
                val label = if (i == 0) "Today" else try { outF.format(inF.parse(dates.getString(i))!!) } catch (e: Exception) { "" }
                Day(label, codes.getInt(i), Math.round(maxs.getDouble(i)).toInt(), Math.round(mins.getDouble(i)).toInt())
            }
            Now(
                Math.round(c.getDouble("temperature_2m")).toInt(), c.getInt("weather_code"), c.optInt("is_day", 1) == 1,
                p.getString("city", "") ?: "", Math.round(c.optDouble("apparent_temperature")).toInt(),
                c.optInt("relative_humidity_2m"), Math.round(c.optDouble("wind_speed_10m")).toInt(),
                days.firstOrNull()?.max ?: 0, days.firstOrNull()?.min ?: 0,
                hm(d.getJSONArray("sunrise").getString(0)), hm(d.getJSONArray("sunset").getString(0)), days
            )
        }
    } catch (e: Exception) {
        null
    }
}

/**
 * "Where to watch": JustWatch search in the viewer's region, grouped by the streaming apps installed on this TV.
 * Each hit carries the provider's own title URL (netflix.com/title/..., app.primevideo.com/detail?...,
 * disneyplus.com/...), which those apps open directly on the title.
 */
object Where {
    class Hit(val title: String, val year: Int, val poster: String, val url: String)

    private const val QUERY = "query S(\$country: Country!, \$language: Language!, \$first: Int!, \$filter: TitleFilter) { " +
        "popularTitles(country: \$country, first: \$first, filter: \$filter) { edges { node { " +
        "content(country: \$country, language: \$language) { title originalReleaseYear posterUrl } " +
        "offers(country: \$country, platform: WEB) { monetizationType standardWebURL package { technicalName } } } } } }"

    /** technicalName → titles included with that service (subscription / free / ads), in popularity order. */
    fun search(ctx: Context, q: String): Map<String, List<Hit>> = try {
        val out = LinkedHashMap<String, MutableList<Hit>>()
        val edges = JustWatch.query(QUERY, JSONObject().put("country", Region.code(ctx)).put("language", "en").put("first", 24)
            .put("filter", JSONObject().put("searchQuery", q))).getJSONObject("popularTitles").getJSONArray("edges")
        for (i in 0 until edges.length()) {
            val n = edges.getJSONObject(i).getJSONObject("node")
            val ct = n.getJSONObject("content")
            val poster = JustWatch.img(ct.optString("posterUrl"), "s332")
            val title = ct.optString("title")
            val offers = n.optJSONArray("offers") ?: continue
            for (k in 0 until offers.length()) {
                val o = offers.getJSONObject(k)
                if (o.optString("monetizationType") !in setOf("FLATRATE", "FREE", "ADS")) continue
                val tech = o.getJSONObject("package").optString("technicalName")
                val list = out.getOrPut(tech) { ArrayList() }
                if (list.none { it.title == title }) list.add(Hit(title, ct.optInt("originalReleaseYear"), poster, o.optString("standardWebURL")))
            }
        }
        out
    } catch (e: Exception) {
        emptyMap()
    }
}

/**
 * JustWatch, in the viewer's region: the list of streaming services, matching installed apps to them by name, and each
 * service's popular titles with backdrops. This is what makes Top Shelf artwork appear for any streaming
 * app installed later, with no per-app code.
 */
object JustWatch {
    fun query(q: String, vars: JSONObject): JSONObject {
        val c = URL("https://apis.justwatch.com/graphql").openConnection() as HttpURLConnection
        c.requestMethod = "POST"; c.doOutput = true; c.connectTimeout = 8000; c.readTimeout = 12000
        c.setRequestProperty("content-type", "application/json"); c.setRequestProperty("user-agent", "Mozilla/5.0")
        c.outputStream.use { it.write(JSONObject().put("query", q).put("variables", vars).toString().toByteArray()) }
        try {
            if (c.responseCode != 200) throw IOException("HTTP ${c.responseCode}")
            return JSONObject(c.inputStream.bufferedReader().readText()).getJSONObject("data")
        } finally { c.disconnect() }
    }

    fun img(path: String, profile: String) =
        if (path.isBlank() || path == "null") "" else "https://images.justwatch.com" + path.replace("{profile}", profile).replace("{format}", "jpg")

    /** (clearName, technicalName) for every service JustWatch tracks in this region; cached for a week. */
    fun packages(ctx: Context): List<Pair<String, String>> = packagesFull(ctx).map { it.first to it.second }

    /** The title filter only accepts JustWatch's short code ("atp"), not the technical name ("appletvplus"). */
    fun shortName(ctx: Context, tech: String): String? = packagesFull(ctx).firstOrNull { it.second == tech }?.third

    private fun packagesFull(ctx: Context): List<Triple<String, String, String>> {
        val cc = Region.code(ctx)
        val f = File(ctx.filesDir, "jw_packages_$cc.json")
        if (!f.exists() || System.currentTimeMillis() - f.lastModified() > 7 * 86400_000L) try {
            val arr = query("query P(\$c: Country!) { packages(country: \$c, platform: WEB) { clearName technicalName shortName } }", JSONObject().put("c", cc))
                .getJSONArray("packages")
            f.writeText(arr.toString())
        } catch (e: Exception) { }
        if (!f.exists()) return emptyList()
        return try { JSONArray(f.readText()).let { a -> (0 until a.length()).map { a.getJSONObject(it).let { o -> Triple(o.getString("clearName"), o.getString("technicalName"), o.optString("shortName")) } } } }
        catch (e: Exception) { emptyList() }
    }

    private fun norm(s: String) = s.lowercase(java.util.Locale.US).replace(Regex("[^a-z0-9]"), "")

    /** "Netflix" ↔ "Netflix", "Paramount+" ↔ "Paramount Plus", "Max" ↔ "Max"… exact first, then prefix. */
    fun match(label: String, pkgs: List<Pair<String, String>>): String? {
        val l = norm(label)
        if (l.length < 3) return null
        pkgs.firstOrNull { norm(it.first) == l }?.let { return it.second }
        return pkgs.filter { val c = norm(it.first); c.length >= 4 && (l.startsWith(c) || c.startsWith(l)) }
            .maxByOrNull { it.first.length }?.second
    }

    /** Installed app → JustWatch service; also tries the brand-prefixed name ("Prime Video" → "Amazon Prime Video"). */
    fun matchApp(label: String, pkgs: List<Pair<String, String>>): String? =
        match(label, pkgs) ?: match("Amazon $label", pkgs) ?: match("Disney $label", pkgs)

    private val genres = mapOf("act" to "Action", "ani" to "Animation", "cmy" to "Comedy", "crm" to "Crime", "doc" to "Documentary",
        "drm" to "Drama", "fnt" to "Fantasy", "hst" to "History", "hrr" to "Horror", "fml" to "Family", "msc" to "Music",
        "trl" to "Thriller", "rma" to "Romance", "scf" to "Sci-Fi", "spt" to "Sport", "war" to "War", "wsn" to "Western",
        "rly" to "Reality", "eur" to "European")
    private fun genre(code: String) = genres[code] ?: code.replaceFirstChar { it.uppercase() }

    private const val SHELF_Q = "query S(\$c: Country!, \$l: Language!, \$f: TitleFilter) { popularTitles(country: \$c, first: 16, filter: \$f) { edges { node { objectType " +
        "content(country: \$c, language: \$l) { title originalReleaseYear shortDescription posterUrl backdrops { backdropUrl } externalIds { imdbId } scoring { imdbScore } genres { shortName } } " +
        "offers(country: \$c, platform: WEB) { standardWebURL package { technicalName } } } } } }"

    /** Popular titles on one service, as Top Shelf slides (backdrop + metahub title logo + the title's page in the app). */
    fun shelf(ctx: Context, tech: String, maxAgeMs: Long): List<ShelfItem> {
        val cc = Region.code(ctx)
        val f = File(ctx.filesDir, "shelf_jw3_${cc}_$tech.json")
        val short = shortName(ctx, tech) ?: return emptyList()
        if (!f.exists() || System.currentTimeMillis() - f.lastModified() > maxAgeMs) try {
            val edges = query(SHELF_Q, JSONObject().put("c", cc).put("l", "en").put("f", JSONObject().put("packages", JSONArray(listOf(short)))))
                .getJSONObject("popularTitles").getJSONArray("edges")
            f.writeText(edges.toString())
        } catch (e: Exception) { }
        if (!f.exists()) return emptyList()
        return try {
            val edges = JSONArray(f.readText())
            (0 until edges.length()).mapNotNull { i ->
                val n = edges.getJSONObject(i).getJSONObject("node")
                val c = n.getJSONObject("content")
                val back = img(c.optJSONArray("backdrops")?.optJSONObject(0)?.optString("backdropUrl").orEmpty(), "s1920")
                if (back.isBlank()) return@mapNotNull null
                val imdb = c.optJSONObject("externalIds")?.optString("imdbId").orEmpty().takeIf { it.startsWith("tt") }
                val offers = n.optJSONArray("offers")
                val url = (0 until (offers?.length() ?: 0)).map { offers!!.getJSONObject(it) }
                    .firstOrNull { it.optJSONObject("package")?.optString("technicalName") == tech }?.optString("standardWebURL").orEmpty()
                val g = c.optJSONArray("genres")
                ShelfItem("jw:$tech", imdb ?: "jw$i", if (n.optString("objectType") == "SHOW") "series" else "movie", c.optString("title"), back,
                    imdb?.let { "https://images.metahub.space/logo/medium/$it/img" }.orEmpty(), img(c.optString("posterUrl"), "s332"),
                    c.optString("originalReleaseYear"), c.optJSONObject("scoring")?.optDouble("imdbScore")?.takeIf { !it.isNaN() && it > 0 }?.let { String.format(java.util.Locale.US, "%.1f", it) }.orEmpty(),
                    (0 until (g?.length() ?: 0)).map { genre(g!!.getJSONObject(it).optString("shortName")) }.take(2),
                    c.optString("shortDescription"), url = url)
            }
        } catch (e: Exception) { emptyList() }
    }
}

/** Streaming region (ISO country code) for JustWatch: from the IP location the weather already looks up, else the TV's locale. */
object Region {
    fun code(ctx: Context): String {
        ctx.getSharedPreferences("weather", Context.MODE_PRIVATE).getString("cc", null)?.uppercase()?.takeIf { it.length == 2 }?.let { return it }
        try { Weather.load(ctx) } catch (e: Exception) {}   // first run: one IP lookup (cached for a day)
        return ctx.getSharedPreferences("weather", Context.MODE_PRIVATE).getString("cc", null)?.uppercase()?.takeIf { it.length == 2 }
            ?: java.util.Locale.getDefault().country.takeIf { it.length == 2 } ?: "US"
    }
}
