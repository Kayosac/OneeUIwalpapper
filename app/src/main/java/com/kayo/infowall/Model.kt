package com.kayo.infowall

import android.content.Context
import android.content.SharedPreferences
import android.graphics.Color

enum class State {
    OK, WARN, BAD, NONE;

    companion object {
        fun from(s: String?): State = when (s?.trim()?.lowercase()) {
            "ok", "good", "up", "green" -> OK
            "warn", "warning", "yellow" -> WARN
            "bad", "error", "down", "red", "crit", "critical" -> BAD
            else -> NONE
        }
    }
}

data class Item(val label: String, val value: String, val state: State = State.NONE)

data class RemoteData(val title: String?, val items: List<Item>)

data class Zone(val label: String, val id: String)

data class Config(
    val url: String,
    val token: String,
    val intervalSec: Int,
    val accent: Int,
    val fps: Int,
    val glow: Float,
    val uiScale: Float,
    val news: Boolean,
    val converter: Boolean,
    val currencies: String,
    val fxAmount: Double,
    val fxFrom: String,
    val shadeSolid: Float,
    val shadeFade: Float,
    val newsRegion: String,
    val newsTopics: Set<NewsTopic>,
    val font: FontPreset,
    val fontVersion: Int,
    val yawDeg: Float,
    val zones: List<Zone>,
    val weather: Boolean,
    val lat: Double?,
    val lon: Double?,
    val city: String,
    val servers: List<ServerConf>,
)

object Prefs {
    private const val NAME = "infowall"
    const val K_URL = "url"
    const val K_TOKEN = "token"
    const val K_INTERVAL = "interval_sec"
    const val K_ACCENT = "accent"
    const val K_FPS = "fps"
    const val K_GLOW = "glow"
    const val UI_SCALE = 0.6f   // масштаб интерфейса зафиксирован
    const val K_NEWS = "news"
    const val K_CONVERTER = "converter"
    const val K_CURRENCIES = "currencies"
    const val DEFAULT_CURRENCIES = "KZT, RUB, USD, EUR, CNY, GBP, TRY"
    private const val OLD_DEFAULT_CURRENCIES = "KZT, RUB, USD, EUR"
    const val K_FX_AMOUNT = "fx_amount"
    const val K_FX_FROM = "fx_from"
    const val K_SHADE_SOLID = "shade_solid"
    const val K_SHADE_FADE = "shade_fade"
    const val K_NEWS_REGION = "news_region"
    const val K_NEWS_TOPICS = "news_topics"
    const val K_FONT = "font"
    const val K_FONT_VER = "font_ver"
    const val K_YAW = "yaw"
    const val K_ZONES = "zones"
    const val K_WEATHER = "weather"
    const val K_LAT = "lat"
    const val K_LON = "lon"
    const val K_CITY = "city"
    const val K_SERVERS = "servers"

    const val MIN_INTERVAL = 15
    const val DEFAULT_ACCENT = "#00E5FF"
    const val DEFAULT_ZONES = "MOS=Europe/Moscow, NOV=Asia/Novosibirsk"
    private const val OLD_DEFAULT_ZONES = "МСК=Europe/Moscow, НСК=Asia/Novosibirsk"

    fun get(ctx: Context): SharedPreferences =
        ctx.applicationContext.getSharedPreferences(NAME, Context.MODE_PRIVATE)

    fun load(ctx: Context): Config {
        val p = get(ctx)
        val lat = p.getString(K_LAT, null)?.toDoubleOrNull()
        val lon = p.getString(K_LON, null)?.toDoubleOrNull()
        return Config(
            url = "",   // бегущая строка — только новости; внешний источник убран
            token = p.getString(K_TOKEN, "").orEmpty().trim(),
            intervalSec = p.getInt(K_INTERVAL, 300).coerceAtLeast(MIN_INTERVAL),
            accent = parseColor(p.getString(K_ACCENT, DEFAULT_ACCENT)),
            fps = p.getInt(K_FPS, 20).coerceIn(0, 30),
            glow = p.getFloat(K_GLOW, 1f).coerceIn(0f, 2f),
            uiScale = UI_SCALE,
            news = p.getBoolean(K_NEWS, true),
            converter = p.getBoolean(K_CONVERTER, true),
            currencies = p.getString(K_CURRENCIES, DEFAULT_CURRENCIES).orEmpty().ifBlank { DEFAULT_CURRENCIES }
                .let { if (it == OLD_DEFAULT_CURRENCIES) DEFAULT_CURRENCIES else it },
            fxAmount = p.getString(K_FX_AMOUNT, "1")?.toDoubleOrNull() ?: 1.0,
            fxFrom = p.getString(K_FX_FROM, "USD").orEmpty().ifBlank { "USD" },
            // доли высоты экрана: сплошной чёрный под доком и переход над ним
            shadeSolid = p.getFloat(K_SHADE_SOLID, 0.093f).coerceIn(0f, 0.4f),
            shadeFade = p.getFloat(K_SHADE_FADE, 0.112f).coerceIn(0.02f, 0.5f),
            newsRegion = p.getString(K_NEWS_REGION, "RU").orEmpty().ifBlank { "RU" },
            newsTopics = NewsTopic.parse(p.getString(K_NEWS_TOPICS, null)),
            font = FontPreset.FINAL,   // шрифты зафиксированы: Sixtyfour / Bitcount / Days One
            fontVersion = p.getInt(K_FONT_VER, 0),
            yawDeg = p.getFloat(K_YAW, 24f).coerceIn(0f, 34f),
            zones = parseZones(p.getString(K_ZONES, DEFAULT_ZONES).orEmpty().let { if (it == OLD_DEFAULT_ZONES) DEFAULT_ZONES else it }),
            weather = p.getBoolean(K_WEATHER, true),
            lat = lat,
            lon = lon,
            city = p.getString(K_CITY, "").orEmpty(),
            servers = Servers.parse(p.getString(K_SERVERS, "").orEmpty()),
        )
    }

    fun parseColor(s: String?): Int =
        runCatching { Color.parseColor(s?.trim().orEmpty()) }.getOrDefault(Color.parseColor(DEFAULT_ACCENT))

    /** «МСК=Europe/Moscow, НСК=Asia/Novosibirsk» → список поясов (неизвестные пропускаем). */
    fun parseZones(s: String): List<Zone> = s.split(',', ';', '\n')
        .mapNotNull { part ->
            val kv = part.split('=', limit = 2)
            if (kv.size != 2) return@mapNotNull null
            val label = kv[0].trim().take(6)
            val id = kv[1].trim()
            if (label.isEmpty() || id.isEmpty()) return@mapNotNull null
            val ok = runCatching { java.util.TimeZone.getAvailableIDs().contains(id) }.getOrDefault(false)
            if (ok) Zone(label, id) else null
        }
        .take(3)
}
