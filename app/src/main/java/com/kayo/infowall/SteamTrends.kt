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

/** Строка виджета: игра, онлайн сейчас, история онлайна за 7 дней (наша, копится сама), обложка. */
class TrendGame(val appid: Int, val name: String, val players: Int, val hist: FloatArray, val img: Bitmap?)

/**
 * «Trending Games» в духе SteamDB, но на официальном открытом API Steam
 * (у SteamDB API нет, а сбор с сайта они запрещают):
 *  - ISteamChartsService/GetMostPlayedGames — топ-100 по онлайну и место неделю назад;
 *  - ISteamChartsService/GetGamesByConcurrentPlayers — онлайн прямо сейчас;
 *  - store appdetails — название (кэшируется навсегда), обложка capsule 231×87 — в файлы приложения.
 * «В тренде» = кто сильнее всех поднялся в чарте за неделю (новички чарта — выше всех).
 * График «7 дней» строится из наших замеров: копится по мере работы обоев.
 */
object SteamTrends {

    const val ROWS = 15
    private const val PREFS = "infowall_steam"
    private const val K_NAMES = "names"
    private const val K_HIST = "hist"
    private const val WEEK_MS = 7 * 24 * 3600_000L
    private const val API = "https://api.steampowered.com/ISteamChartsService"

    @Throws(IOException::class)
    fun fetch(ctx: Context): List<TrendGame> {
        val most = JSONObject(get("$API/GetMostPlayedGames/v1/")).optJSONObject("response")?.optJSONArray("ranks")
            ?: throw IOException("Steam: пустой ответ")
        val live = HashMap<Int, Int>()
        runCatching {
            JSONObject(get("$API/GetGamesByConcurrentPlayers/v1/")).optJSONObject("response")?.optJSONArray("ranks")
        }.getOrNull()?.let { arr ->
            for (i in 0 until arr.length()) arr.optJSONObject(i)?.let { live[it.optInt("appid")] = it.optInt("concurrent_in_game") }
        }

        // рост в чарте за неделю
        data class Cand(val appid: Int, val rank: Int, val score: Int, val peak: Int)
        val cands = ArrayList<Cand>()
        for (i in 0 until most.length()) {
            val o = most.optJSONObject(i) ?: continue
            val rank = o.optInt("rank"); val lw = o.optInt("last_week_rank", 0)
            val score = if (lw <= 0) 200 - rank else lw - rank
            cands += Cand(o.optInt("appid"), rank, score, o.optInt("peak_in_game"))
        }
        val picked = cands.filter { it.score > 0 }.sortedWith(compareByDescending<Cand> { it.score }.thenBy { it.rank }).take(ROWS)
            .ifEmpty { cands.sortedBy { it.rank }.take(ROWS) }

        val p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val names = runCatching { JSONObject(p.getString(K_NAMES, "{}")!!) }.getOrDefault(JSONObject())
        val hist = runCatching { JSONObject(p.getString(K_HIST, "{}")!!) }.getOrDefault(JSONObject())
        val now = System.currentTimeMillis()
        val out = ArrayList<TrendGame>()
        for (c in picked) {
            val id = c.appid
            val name = names.optString(id.toString()).ifEmpty {
                runCatching { appName(id) }.getOrNull()?.also { names.put(id.toString(), it) } ?: "App $id"
            }
            val players = live[id] ?: runCatching { currentPlayers(id) }.getOrDefault(c.peak)
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
            out += TrendGame(id, name, players, series, capsule(ctx, id))
        }
        p.edit().putString(K_NAMES, names.toString()).putString(K_HIST, hist.toString()).apply()
        return out
    }

    private fun appName(id: Int): String? {
        val o = JSONObject(get("https://store.steampowered.com/api/appdetails?appids=$id&filters=basic&l=english"))
        return o.optJSONObject(id.toString())?.optJSONObject("data")?.optString("name")?.takeIf { it.isNotBlank() }
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
