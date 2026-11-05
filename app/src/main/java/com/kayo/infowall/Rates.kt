package com.kayo.infowall

import android.content.Context
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/** Курсы относительно USD: rates["KZT"] = сколько тенге в одном долларе. */
data class Rates(val rates: Map<String, Double>, val updatedUtc: Long, val fetchedAt: Long) {

    /** Сколько единиц [to] в [amount] единицах [from]; null — если валюты нет в данных. */
    fun convert(amount: Double, from: String, to: String): Double? {
        val f = rates[from] ?: return null
        val t = rates[to] ?: return null
        if (f <= 0.0) return null
        return amount / f * t
    }
}

/**
 * Курсы с open.er-api.com (ExchangeRate-API, открытый доступ без ключа, обновление раз в сутки).
 * Последний ответ хранится в настройках, чтобы конвертер работал и без сети.
 */
object RatesSource {

    private const val PREFS = "infowall_rates"
    private const val K_JSON = "json"
    private const val K_FETCHED = "fetched"
    const val STALE_MS = 6 * 60 * 60 * 1000L

    @Throws(IOException::class)
    fun fetch(ctx: Context): Rates {
        val c = URL("https://open.er-api.com/v6/latest/USD").openConnection() as HttpURLConnection
        c.connectTimeout = 6_000
        c.readTimeout = 10_000
        try {
            if (c.responseCode !in 200..299) throw IOException("курсы: HTTP ${c.responseCode}")
            val body = c.inputStream.bufferedReader().use { it.readText() }
            val r = parse(body, System.currentTimeMillis()) ?: throw IOException("курсы: неожиданный ответ")
            ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putString(K_JSON, body).putLong(K_FETCHED, r.fetchedAt).apply()
            return r
        } finally {
            c.disconnect()
        }
    }

    fun cached(ctx: Context): Rates? {
        val p = ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val body = p.getString(K_JSON, null) ?: return null
        return parse(body, p.getLong(K_FETCHED, 0L))
    }

    private fun parse(body: String, fetchedAt: Long): Rates? = runCatching {
        val j = JSONObject(body)
        if (j.optString("result") != "success") return@runCatching null
        val obj = j.getJSONObject("rates")
        val map = HashMap<String, Double>()
        for (k in obj.keys()) map[k] = obj.getDouble(k)
        Rates(map, j.optLong("time_last_update_unix", 0L) * 1000L, fetchedAt)
    }.getOrNull()

    /** «KZT, RUB, usd,EUR» → [KZT, RUB, USD, EUR] (до четырёх, без повторов). */
    fun parseCodes(s: String): List<String> = s.split(',', ';', ' ')
        .map { it.trim().uppercase() }
        .filter { it.length == 3 && it.all(Char::isLetter) }
        .distinct()
        .take(MAX_CODES)

    const val MAX_CODES = 7
}
