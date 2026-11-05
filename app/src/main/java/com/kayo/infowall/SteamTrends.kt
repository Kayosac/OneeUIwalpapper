package com.kayo.infowall

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import kotlin.math.max

/**
 * Строка виджета: игра, онлайн сейчас, пик за 24 ч, рейтинг (формула SteamDB), цена,
 * история онлайна за 7 дней (наша, копится сама), обложка.
 */
class TrendGame(
    val appid: Int, val name: String, val players: Int, val peak24: Int,
    val rating: Float?, val price: String?, val discount: Int,
    val hist: FloatArray, val img: Bitmap?,
)

/**
 * Виджет в духе SteamDB на официальных данных Steam (у SteamDB API нет, а сбор с сайта они запрещают):
 *  - подборка — «Лидеры продаж» магазина Steam (search/results?filter=topsellers), только игры;
 *  - ISteamChartsService/GetGamesByConcurrentPlayers — онлайн и пик за 24 ч для топ-100,
 *    для остальных — ISteamUserStats/GetNumberOfCurrentPlayers и наши замеры;
 *  - store appdetails — тип и цена, обложка capsule 231×87 — в файлы приложения.
 * График «7 дней» строится из наших замеров: копится по мере работы обоев.
 */
object SteamTrends {

    const val ROWS = 15
    private const val PREFS = "infowall_steam"
    private const val K_NAMES = "names"
    private const val K_HIST = "hist"
    private const val K_TYPES = "types"
    private const val WEEK_MS = 7 * 24 * 3600_000L
    private const val API = "https://api.steampowered.com/ISteamChartsService"

    /** [cc] — страна магазина для цен (us — доллары). */
    @Throws(IOException::class)
    fun fetch(ctx: Context, cc: String = "us"): List<TrendGame> {
        // подборка — «Популярные новинки» магазина Steam (filter=popularnew); Steam подмешивает туда и старые
        // хиты (Counter-Strike 2, Dota 2…), поэтому ниже оставляем только вышедшие за последние NEW_DAYS дней
        val list = JSONObject(get("https://store.steampowered.com/search/results/?json=1&filter=popularnew&count=100&cc=$cc&l=english"))
            .optJSONArray("items") ?: throw IOException("Steam: пустой ответ")
        val idRe = Regex("/apps/(\\d+)/")
        val sellers = ArrayList<Pair<Int, String>>()
        for (i in 0 until list.length()) {
            val o = list.optJSONObject(i) ?: continue
            val id = idRe.find(o.optString("logo"))?.groupValues?.get(1)?.toIntOrNull() ?: continue
            if (sellers.none { it.first == id }) sellers += id to o.optString("name")
        }
        val live = HashMap<Int, Int>()
        val livePeak = HashMap<Int, Int>()
        runCatching {
            JSONObject(get("$API/GetGamesByConcurrentPlayers/v1/")).optJSONObject("response")?.optJSONArray("ranks")
        }.getOrNull()?.let { arr ->
            for (i in 0 until arr.length()) arr.optJSONObject(i)?.let {
                live[it.optInt("appid")] = it.optInt("concurrent_in_game")
                livePeak[it.optInt("appid")] = it.optInt("peak_in_game")
            }
        }

        val p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val names = runCatching { JSONObject(p.getString(K_NAMES, "{}")!!) }.getOrDefault(JSONObject())
        val hist = runCatching { JSONObject(p.getString(K_HIST, "{}")!!) }.getOrDefault(JSONObject())
        val types = runCatching { JSONObject(p.getString(K_TYPES, "{}")!!) }.getOrDefault(JSONObject())
        val now = System.currentTimeMillis()
        val out = ArrayList<TrendGame>()
        for ((id, listName) in sellers) {
            if (out.size >= ROWS) break
            // тип и дата выхода кэшируются (не меняются): старые игры и не-игры отсеиваем без лишних запросов
            val meta = types.optJSONObject(id.toString())
            // «скоро выйдет» (r = -1) не кэшируем как отказ — дата появится после релиза
            if (meta != null && (meta.optString("t") != "game" || (meta.optLong("r", -1L) > 0 && !isNew(meta.optLong("r"), now)))) continue
            val info = runCatching { details(id, cc) }.getOrNull() ?: continue
            types.put(id.toString(), JSONObject().put("t", info.type).put("r", info.released))
            if (info.type != "game" || !isNew(info.released, now)) continue
            val name = listName.ifBlank { names.optString(id.toString()).ifEmpty { "App $id" } }
            names.put(id.toString(), name)
            val players = live[id] ?: runCatching { currentPlayers(id) }.getOrDefault(0)
            // история: точка не чаще раза в 50 минут, хранится неделя
            val h = hist.optJSONArray(id.toString()) ?: JSONArray()
            val kept = JSONArray()
            for (i in 0 until h.length()) {
                val pt = h.optJSONArray(i) ?: continue
                if (now - pt.optLong(0) < WEEK_MS) kept.put(pt)
            }
            val lastTs = if (kept.length() > 0) kept.optJSONArray(kept.length() - 1).optLong(0) else 0L
            if (now - lastTs > 50 * 60_000L) kept.put(JSONArray().put(now).put(players))
            hist.put(id.toString(), kept)
            val series = FloatArray(kept.length()) { kept.optJSONArray(it).optDouble(1).toFloat() }
            // пик за 24 ч: из чарта онлайна Steam; если игры нет в топ-100 — максимум наших замеров за сутки
            var ownPeak = players
            for (i in 0 until kept.length()) {
                val pt = kept.optJSONArray(i) ?: continue
                if (now - pt.optLong(0) < 24 * 3600_000L) ownPeak = max(ownPeak, pt.optInt(1))
            }
            val peak = max(livePeak[id] ?: ownPeak, players)
            out += TrendGame(id, name, players, peak, null, info.price, info.discount, series, capsule(ctx, id))
        }
        p.edit().putString(K_NAMES, names.toString()).putString(K_HIST, hist.toString()).putString(K_TYPES, types.toString()).apply()
        if (out.isEmpty()) throw IOException("Steam: нет новинок в подборке")
        return out
    }

    /** Новинка — вышла (не «скоро») не раньше NEW_DAYS дней назад; дата неизвестна (-1) — не новинка. */
    private fun isNew(released: Long, now: Long) = released > 0 && released <= now && now - released < NEW_DAYS * 86_400_000L

    private const val NEW_DAYS = 60

    private class Details(val type: String, val price: String?, val discount: Int, val released: Long)

    /** Тип (game / dlc / hardware…), дата выхода (мс, -1 — «скоро»/неизвестно) и цена в магазине страны [cc]. */
    private fun details(id: Int, cc: String): Details? {
        val o = JSONObject(get("https://store.steampowered.com/api/appdetails?appids=$id&cc=$cc&filters=basic,price_overview,release_date&l=english"))
            .optJSONObject(id.toString())?.optJSONObject("data") ?: return null
        val type = o.optString("type")
        val rd = o.optJSONObject("release_date")
        val released = if (rd == null || rd.optBoolean("coming_soon")) -1L else parseDate(rd.optString("date"))
        if (o.optBoolean("is_free")) return Details(type, "Free", 0, released)
        val p = o.optJSONObject("price_overview") ?: return Details(type, null, 0, released)
        return Details(type, p.optString("final_formatted").trim(), p.optInt("discount_percent"), released)
    }

    /** «Oct 28, 2026» или «28 Oct, 2026» → мс; не распознали — -1. */
    private fun parseDate(s: String): Long {
        for (pat in listOf("MMM d, yyyy", "d MMM, yyyy", "MMM yyyy")) {
            val d = runCatching {
                java.time.LocalDate.parse(s.trim(), java.time.format.DateTimeFormatter.ofPattern(pat, java.util.Locale.ENGLISH))
            }.getOrNull() ?: continue
            return d.atStartOfDay(java.time.ZoneOffset.UTC).toInstant().toEpochMilli()
        }
        return -1L
    }

    private fun appName(id: Int): String? {
        val o = JSONObject(get("https://store.steampowered.com/api/appdetails?appids=$id&filters=basic&l=english"))
        return o.optJSONObject(id.toString())?.optJSONObject("data")?.optString("name")?.takeIf { it.isNotBlank() }
    }

    /**
     * Рейтинг как у SteamDB: доля положительных обзоров, подтянутая к 50 % тем сильнее,
     * чем меньше обзоров: score − (score − 0.5) · 2^(−log10(total + 1)).
     */
    private fun rating(id: Int): Float? {
        val q = JSONObject(get("https://store.steampowered.com/appreviews/$id?json=1&language=all&purchase_type=all&num_per_page=0"))
            .optJSONObject("query_summary") ?: return null
        val pos = q.optInt("total_positive"); val total = q.optInt("total_reviews")
        if (total <= 0) return null
        val score = pos.toDouble() / total
        val r = score - (score - 0.5) * Math.pow(2.0, -Math.log10(total + 1.0))
        return (r * 100).toFloat()
    }

    /** Цена в магазине страны [cc]: «$19.99» и скидка в процентах; бесплатные — «Free». */
    private fun price(id: Int, cc: String): Pair<String, Int>? {
        val o = JSONObject(get("https://store.steampowered.com/api/appdetails?appids=$id&cc=$cc&filters=price_overview,basic&l=english"))
            .optJSONObject(id.toString())?.optJSONObject("data") ?: return null
        if (o.optBoolean("is_free")) return "Free" to 0
        val p = o.optJSONObject("price_overview") ?: return null
        return p.optString("final_formatted").trim() to p.optInt("discount_percent")
    }

    private fun currentPlayers(id: Int): Int =
        JSONObject(get("https://api.steampowered.com/ISteamUserStats/GetNumberOfCurrentPlayers/v1/?appid=$id"))
            .optJSONObject("response")?.optInt("player_count") ?: 0

    /** Обложка 231×87: из кэша или скачать (два известных адреса CDN Steam). */
    private fun capsule(ctx: Context, id: Int): Bitmap? {
        val dir = File(ctx.filesDir, "steam").apply { mkdirs() }
        val f = File(dir, "$id.jpg")
        if (!f.exists() || f.length() < 512) {
            val urls = listOf(
                "https://shared.akamai.steamstatic.com/store_item_assets/steam/apps/$id/capsule_231x87.jpg",
                "https://cdn.akamai.steamstatic.com/steam/apps/$id/capsule_231x87.jpg",
            )
            for (u in urls) {
                val bytes = runCatching { getBytes(u, 300 * 1024) }.getOrNull() ?: continue
                if (bytes.size > 512) { f.writeBytes(bytes); break }
            }
        }
        // чистим старые обложки (больше 40 файлов — удаляем самые давние)
        dir.listFiles()?.sortedBy { it.lastModified() }?.dropLast(40)?.forEach { it.delete() }
        return if (f.exists()) runCatching { BitmapFactory.decodeFile(f.path) }.getOrNull() else null
    }

    private fun get(url: String): String = String(getBytes(url, 2 * 1024 * 1024), Charsets.UTF_8)

    private fun getBytes(url: String, limit: Long): ByteArray {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 6_000
        c.readTimeout = 10_000
        c.setRequestProperty("User-Agent", "InfoWall (Android live wallpaper)")
        try {
            if (c.responseCode !in 200..299) throw IOException("HTTP ${c.responseCode}")
            return c.inputStream.use { Fonts.readLimited(it, limit) }
        } finally {
            c.disconnect()
        }
    }
}
