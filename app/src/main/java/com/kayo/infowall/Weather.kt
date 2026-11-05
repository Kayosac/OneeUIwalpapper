package com.kayo.infowall

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Geocoder
import android.location.Location
import android.location.LocationManager
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.time.LocalDate
import java.util.Locale
import kotlin.math.roundToInt

data class DayForecast(val dow: String, val kind: WxKind, val max: Int, val min: Int)

data class WeatherData(
    val temp: Int,
    val kind: WxKind,
    val text: String,
    val wind: Float,
    val days: List<DayForecast>,
    val at: Long,
)

enum class WxKind { SUN, PART, CLOUD, FOG, RAIN, SNOW, STORM }

/** Прогноз Open-Meteo (без ключа) и определение места. */
object Weather {

    private val DOW = arrayOf("ПН", "ВТ", "СР", "ЧТ", "ПТ", "СБ", "ВС")

    fun hasPermission(ctx: Context) =
        ctx.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

    /** Последнее известное место; без разрешения или без данных — null. */
    fun lastLocation(ctx: Context): Location? {
        if (!hasPermission(ctx)) return null
        val lm = ctx.getSystemService(LocationManager::class.java) ?: return null
        return lm.getProviders(true).mapNotNull { p ->
            try {
                lm.getLastKnownLocation(p)
            } catch (_: SecurityException) {
                null
            } catch (_: IllegalArgumentException) {
                null
            }
        }.maxByOrNull { it.time }
    }

    @Suppress("DEPRECATION")
    fun cityName(ctx: Context, lat: Double, lon: Double): String? = try {
        if (!Geocoder.isPresent()) null
        else Geocoder(ctx, Locale("ru")).getFromLocation(lat, lon, 1)?.firstOrNull()?.let {
            it.locality ?: it.subAdminArea ?: it.adminArea
        }
    } catch (_: Exception) {
        null
    }

    @Throws(IOException::class)
    fun fetch(lat: Double, lon: Double): WeatherData {
        val url = String.format(
            Locale.US,
            "https://api.open-meteo.com/v1/forecast?latitude=%.4f&longitude=%.4f" +
                "&current=temperature_2m,weather_code,wind_speed_10m" +
                "&daily=weather_code,temperature_2m_max,temperature_2m_min" +
                "&timezone=auto&forecast_days=7&wind_speed_unit=ms",
            lat, lon,
        )
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 6_000
        conn.readTimeout = 10_000
        try {
            if (conn.responseCode !in 200..299) throw IOException("погода: HTTP ${conn.responseCode}")
            val body = conn.inputStream.bufferedReader().use { it.readText() }
            val j = JSONObject(body)
            val cur = j.getJSONObject("current")
            val code = cur.optInt("weather_code", 3)
            val daily = j.getJSONObject("daily")
            val times = daily.getJSONArray("time")
            val codes = daily.getJSONArray("weather_code")
            val maxs = daily.getJSONArray("temperature_2m_max")
            val mins = daily.getJSONArray("temperature_2m_min")
            val days = (0 until minOf(7, times.length())).map { i ->
                val d = LocalDate.parse(times.getString(i))
                DayForecast(
                    dow = DOW[d.dayOfWeek.value - 1],
                    kind = kindOf(codes.optInt(i, 3)),
                    max = maxs.optDouble(i, 0.0).roundToInt(),
                    min = mins.optDouble(i, 0.0).roundToInt(),
                )
            }
            return WeatherData(
                temp = cur.optDouble("temperature_2m", 0.0).roundToInt(),
                kind = kindOf(code),
                text = describe(code),
                wind = cur.optDouble("wind_speed_10m", 0.0).toFloat(),
                days = days,
                at = System.currentTimeMillis(),
            )
        } finally {
            conn.disconnect()
        }
    }

    /** WMO weather codes → тип иконки. */
    fun kindOf(code: Int): WxKind = when (code) {
        0 -> WxKind.SUN
        1, 2 -> WxKind.PART
        3 -> WxKind.CLOUD
        45, 48 -> WxKind.FOG
        in 51..67, in 80..82 -> WxKind.RAIN
        in 71..77, 85, 86 -> WxKind.SNOW
        in 95..99 -> WxKind.STORM
        else -> WxKind.CLOUD
    }

    fun describe(code: Int): String = when (code) {
        0 -> "ясно"
        1 -> "почти ясно"
        2 -> "переменная облачность"
        3 -> "облачно"
        45, 48 -> "туман"
        in 51..57 -> "морось"
        in 61..67 -> "дождь"
        in 71..77 -> "снег"
        in 80..82 -> "ливень"
        85, 86 -> "снегопад"
        in 95..99 -> "гроза"
        else -> "облачно"
    }
}
