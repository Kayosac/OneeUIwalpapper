package com.kayo.infowall

import android.util.Xml
import org.xmlpull.v1.XmlPullParser
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

data class Headline(val title: String, val source: String?)

/** Темы бегущей строки: раздел Google Новостей или поиск по ключевым словам за последние дни. */
enum class NewsTopic(val title: String, val section: String? = null, val query: String? = null) {
    TECH("Технологии", section = "TECHNOLOGY"),
    GAMES("Игры и геймдев", query = "видеоигры OR \"игровая индустрия\" OR геймдев OR PlayStation OR Xbox OR Nintendo OR Steam"),
    AI("ИИ и нейросети", query = "\"искусственный интеллект\" OR нейросеть OR OpenAI OR Anthropic OR Gemini"),
    GADGETS("Гаджеты и железо", query = "смартфон OR гаджет OR видеокарта OR процессор OR Samsung OR Apple"),
    SCIENCE("Наука", section = "SCIENCE"),
    SPACE("Космос", query = "космос OR SpaceX OR NASA OR Роскосмос");

    companion object {
        val DEFAULT = setOf(TECH, GAMES, AI)
        fun parse(s: String?): Set<NewsTopic> =
            s?.split(',')?.mapNotNull { n -> values().firstOrNull { it.name == n.trim() } }?.toSet()?.ifEmpty { null } ?: DEFAULT
        fun join(set: Set<NewsTopic>) = set.joinToString(",") { it.name }
    }
}

/**
 * Заголовки из официальной RSS-ленты Google Новостей (только заголовок и источник —
 * для бегущей строки). Регион задаёт подборку: RU, KZ, BY, UA…
 */
object News {

    private const val MAX = 25

    /** Заголовки по выбранным темам: ленты по очереди, без повторов, вперемешку. */
    @Throws(IOException::class)
    fun fetch(region: String, topics: Set<NewsTopic>): List<Headline> {
        val gl = region.trim().uppercase().filter { it.isLetter() }.take(2).ifEmpty { "RU" }
        val tail = "hl=ru&gl=$gl&ceid=$gl:ru"
        val lists = ArrayList<List<Headline>>()
        var lastError: IOException? = null
        for (t in topics.ifEmpty { NewsTopic.DEFAULT }) {
            val url = if (t.section != null) "https://news.google.com/rss/headlines/section/topic/${t.section}?$tail"
            else "https://news.google.com/rss/search?q=" + java.net.URLEncoder.encode("(${t.query}) when:3d", "UTF-8") + "&$tail"
            try { lists += load(url) } catch (e: IOException) { lastError = e }
        }
        if (lists.isEmpty()) throw lastError ?: IOException("новости: пусто")
        // чередуем темы и убираем одинаковые заголовки
        val seen = HashSet<String>()
        val out = ArrayList<Headline>()
        val per = MAX / lists.size + 2
        for (i in 0 until per) for (l in lists) {
            val h = l.getOrNull(i) ?: continue
            if (seen.add(h.title.lowercase().take(60))) out += h
        }
        return out.take(MAX)
    }

    @Throws(IOException::class)
    private fun load(url: String): List<Headline> {
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
