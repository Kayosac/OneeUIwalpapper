package com.kayo.infowall

import android.util.Xml
import org.xmlpull.v1.XmlPullParser
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

data class Headline(val title: String, val source: String?)

/**
 * Заголовки из официальной RSS-ленты Google Новостей (только заголовок и источник —
 * для бегущей строки). Регион задаёт подборку: RU, KZ, BY, UA…
 */
object News {

    private const val MAX = 25

    @Throws(IOException::class)
    fun fetch(region: String): List<Headline> {
        val gl = region.trim().uppercase().filter { it.isLetter() }.take(2).ifEmpty { "RU" }
        val url = "https://news.google.com/rss?hl=ru&gl=$gl&ceid=$gl:ru"
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 6_000
        c.readTimeout = 10_000
        c.setRequestProperty("User-Agent", "InfoWall/0.6 (Android live wallpaper)")
        try {
            if (c.responseCode !in 200..299) throw IOException("новости: HTTP ${c.responseCode}")
            return c.inputStream.use { parse(it) }
        } finally {
            c.disconnect()
        }
    }

    private fun parse(input: java.io.InputStream): List<Headline> {
        val p = Xml.newPullParser()
        p.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
        p.setInput(input, null)
        val out = ArrayList<Headline>()
        var inItem = false
        var title: String? = null
        var source: String? = null
        while (p.next() != XmlPullParser.END_DOCUMENT && out.size < MAX) {
            when (p.eventType) {
                XmlPullParser.START_TAG -> when (p.name) {
                    "item" -> { inItem = true; title = null; source = null }
                    "title" -> if (inItem) title = p.nextText().trim()
                    "source" -> if (inItem) source = p.nextText().trim()
                }
                XmlPullParser.END_TAG -> if (p.name == "item" && inItem) {
                    inItem = false
                    val t = title
                    if (!t.isNullOrBlank()) {
                        // Google дописывает « - Источник» в конец заголовка — убираем, источник отдельно
                        val src = source
                        val clean = if (src != null && t.endsWith(" - $src")) t.removeSuffix(" - $src") else t
                        out += Headline(clean.trim(), src)
                    }
                }
            }
        }
        return out
    }
}
