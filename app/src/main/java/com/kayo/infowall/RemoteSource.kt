package com.kayo.infowall

import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import kotlin.math.roundToInt

/**
 * Загружает JSON и превращает его в карточки. Поддерживаемые форматы:
 *
 * 1) {"title": "Хаб", "items": [{"label": "CPU", "value": "42%", "state": "ok"}]}
 * 2) [{"label": "CPU", "value": "42%", "state": "warn"}]
 * 3) {"CPU": "42%", "Диск": "71%"}            — плоский объект, ключ = подпись
 *
 * state: ok | warn | bad (или пусто). Вместо "label" можно "name".
 *
 * Лимиты: у элемента можно указать "used_percent" (0–100, сколько израсходовано),
 * "resets_at" и "updated_at" (Unix-секунды). Тогда обои рисуют полоску остатка,
 * отсчёт до сброса и сами обнуляют расход, когда сброс наступил.
 */
object RemoteSource {

    private const val MAX_BYTES = 256 * 1024

    @Throws(IOException::class)
    fun fetch(url: String, token: String): RemoteData {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 5_000
        conn.readTimeout = 8_000
        conn.useCaches = false
        conn.setRequestProperty("Accept", "application/json")
        if (token.isNotBlank()) conn.setRequestProperty("Authorization", "Bearer $token")
        try {
            val code = conn.responseCode
            if (code !in 200..299) throw IOException("HTTP $code")
            val bytes = conn.inputStream.use { it.readNBytesCompat(MAX_BYTES) }
            return parse(String(bytes, Charsets.UTF_8))
        } finally {
            conn.disconnect()
        }
    }

    fun parse(body: String): RemoteData {
        val t = body.trim()
        if (t.startsWith("[")) return RemoteData(null, parseArray(JSONArray(t)))
        val obj = JSONObject(t)
        val title = obj.optString("title").takeIf { it.isNotBlank() }
        obj.optJSONArray("items")?.let { return RemoteData(title, parseArray(it)) }

        val list = mutableListOf<Item>()
        val keys = obj.keys()
        while (keys.hasNext()) {
            val k = keys.next()
            if (k == "title") continue
            list += Item(k, valueToString(obj.opt(k)))
        }
        return RemoteData(title, list)
    }

    private fun parseArray(arr: JSONArray): List<Item> {
        val out = ArrayList<Item>(arr.length())
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val label = o.optString("label").ifBlank { o.optString("name").ifBlank { "?" } }
            val used = o.optDouble("used_percent", Double.NaN)
                .takeIf { !it.isNaN() }?.toFloat()?.coerceIn(0f, 100f)
            out += Item(
                label = label,
                value = if (o.has("value")) valueToString(o.opt("value"))
                else used?.let { "${(100 - it).roundToInt()}%" } ?: "—",
                state = State.from(o.optString("state")),
                usedPercent = used,
                resetsAt = epoch(o, "resets_at"),
                updatedAt = epoch(o, "updated_at"),
            )
        }
        return out
    }

    private fun epoch(o: JSONObject, key: String): Long? =
        o.optLong(key, 0L).takeIf { it > 0L }

    private fun valueToString(v: Any?): String = when (v) {
        null, JSONObject.NULL -> "—"
        is Double -> if (v % 1.0 == 0.0) v.toLong().toString() else "%.1f".format(v)
        else -> v.toString()
    }

    private fun java.io.InputStream.readNBytesCompat(limit: Int): ByteArray {
        val buf = java.io.ByteArrayOutputStream()
        val chunk = ByteArray(8192)
        var total = 0
        while (true) {
            val n = read(chunk)
            if (n < 0) break
            total += n
            if (total > limit) throw IOException("ответ больше ${limit / 1024} КБ")
            buf.write(chunk, 0, n)
        }
        return buf.toByteArray()
    }
}
