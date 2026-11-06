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
import java.time.LocalDate
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min

/**
 * Строка виджета. [signal] — почему игра в списке: NEW, ▲34, HOT, WISH #14.
 * [upcoming] — для невышедших: дата выхода вместо онлайна («Feb 2027», «TBA»).
 */
class TrendGame(
    val appid: Int, val name: String, val players: Int, val peak24: Int,
    val price: String?, val signal: String, val kind: Int, val upcoming: String?, val img: Bitmap?,
) {
    companion object {
        const val K_NEW = 0; const val K_JUMP = 1; const val K_HOT = 2; const val K_WISH = 3
    }
}

/**
 * Детектор взлётов на официальных данных Steam — сразу, без накопления истории
 * (у SteamDB API нет, а сбор с сайта они запрещают):
 *  - NEW  — игра впервые попала в топ-100 по онлайну (ISteamChartsService, место неделю назад отсутствует);
 *  - ▲N   — поднялась в этом топе на N ≥ 15 мест за неделю;
 *  - LAUNCH — вышла за последние 5 дней и уже ≥ 6000 онлайн: всегда в списке и выше всех;
 *  - HOT  — игра не старше 3 месяцев, которую сейчас играют ≥ 500 человек; выше — больше онлайн и новее игра;
 *  - WISH — невышедшая игра со свежей страницей (большой appid) уже высоко в Top Wishlists магазина;
 *           со второго дня — ещё и рывок по месту в Top Wishlists относительно вчера.
 * Вышедшие игры попадают в список, только если вышли не раньше чем 3 месяца назад.
 * Пул: топ-100 по онлайну, популярные новинки, лидеры продаж, топ-200 по вишлистам.
 * Отдаёт [ROWS] самых сильных сигналов; ⟳ на виджете собирает список заново.
 */
object SteamTrends {

    /** appid → адрес картинки из поиска магазина (поиски идут параллельно). */
    private val logos = java.util.concurrent.ConcurrentHashMap<Int, String>()

    const val ROWS = 15
    const val MAX = 80        // кандидатов на проверку перед отбором 15 (многие отсеются по дате выхода)
    private const val FRESH_DAYS = 90
    private const val HOT_MIN = 500       // онлайн, с которого свежая игра — HOT
    private const val LAUNCH_DAYS = 5     // «громкий запуск»: вышла не раньше стольких дней назад…
    private const val LAUNCH_MIN = 6000   // …и уже собрала столько онлайна
    private const val MAX_WISH = 6     // строк WISH в списке, если игр с онлайном хватает на остальные
    private const val PREFS = "infowall_steam2"
    private const val API = "https://api.steampowered.com"
    private const val STORE = "https://store.steampowered.com"
    private const val DAY = 86_400_000L

    private class Cand(val id: Int, var name: String) {
        var score = 0f; var signal = ""; var kind = -1
        var players = -1; var peak = -1
        fun offer(s: Float, label: String, k: Int) { if (s > score) { score = s; signal = label; kind = k } }
    }

    @Throws(IOException::class)
    fun fetch(ctx: Context, cc: String = "us"): List<TrendGame> {
        val p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val meta = obj(p, "meta")          // id → {t: type, r: дата выхода (мс, -1 — не вышла), d: текст даты, n: имя}
        val reviews = obj(p, "reviews")    // id → [число отзывов, когда спрашивали]
        val now = System.currentTimeMillis()
        val pool = LinkedHashMap<Int, Cand>()
        fun cand(id: Int, name: String) = pool.getOrPut(id) { Cand(id, name) }.also { if (it.name.isBlank()) it.name = name }

        // 1) чарты онлайна: сейчас + пик за сутки; место неделю назад
        val live = HashMap<Int, Pair<Int, Int>>()
        runCatching { arr(get("$API/ISteamChartsService/GetGamesByConcurrentPlayers/v1/"), "ranks") }.getOrNull()?.forEachObj {
            live[it.optInt("appid")] = it.optInt("concurrent_in_game") to it.optInt("peak_in_game")
        }
        val most = runCatching { arr(get("$API/ISteamChartsService/GetMostPlayedGames/v1/"), "ranks") }.getOrNull()
        most?.forEachObj {
            val id = it.optInt("appid"); val rank = it.optInt("rank"); val lw = it.optInt("last_week_rank", 0)
            val c = cand(id, "")
            if (lw <= 0) c.offer(90f + (100 - rank) / 10f, "NEW", TrendGame.K_NEW)
            else if (lw - rank >= 15) c.offer(60f + min(30f, (lw - rank) / 2f), "▲${lw - rank}", TrendGame.K_JUMP)
        }

        // 2) подборки магазина — четыре запроса разом
        val q = listOf(Triple("popularnew", 100, 0), Triple("topsellers", 40, 0),
            Triple("popularwishlist", 100, 0), Triple("popularwishlist", 100, 100))
        val sr = pmap(q, 4) { (f, n, start) -> search(f, n, start, cc) }.map { it ?: emptyList() }
        val newRel = sr[0]
        val sellers = sr[1]
        val wish = sr[2] + sr[3]
        for ((id, name) in newRel + sellers) cand(id, name)

        // 3) HOT: свежая (до FRESH_DAYS) игра, которую сейчас много играют. Кандидаты — новинки, лидеры продаж
        //    и топ-100 по онлайну. Сила: чем больше онлайн и чем новее игра, тем выше.
        //    Онлайн — каждый раз (8 потоков), карточка с датой выхода — из кэша на сутки (4 потока).
        val hotIds = ((newRel + sellers).map { it.first } + live.keys).distinct()
        val ccu = HashMap<Int, Int>()
        live.forEach { (id, v) -> ccu[id] = v.first }
        pmap(hotIds.filter { it !in live }, 8) { id -> id to runCatching { players(id) }.getOrDefault(-1) }
            .filterNotNull().forEach { (id, n) -> ccu[id] = n }
        val hot = pmap(hotIds.filter { (ccu[it] ?: -1) >= HOT_MIN }, 4) { id ->
            val m = details(meta, id, cc) ?: return@pmap null
            if (m.optString("t") != "game") return@pmap null
            val rel = m.optLong("r", -1L)
            if (rel <= 0 || now - rel > FRESH_DAYS * DAY || m.optBoolean("cs", false)) return@pmap null
            id to rel
        }
        for ((id, rel) in hot.filterNotNull()) {
            val n = (ccu[id] ?: 0).coerceAtLeast(HOT_MIN)
            val days = ((now - rel) / DAY).toFloat().coerceIn(0f, FRESH_DAYS.toFloat())
            val score = 50f + min(30f, 12f * log10(n / HOT_MIN.toFloat())) + 15f * (1f - days / FRESH_DAYS)
            cand(id, "").offer(score, "HOT", TrendGame.K_HOT)
            // громкий запуск: вышла за последние LAUNCH_DAYS дней и уже ≥ LAUNCH_MIN онлайн — всегда в списке, в самом верху
            if (now - rel <= LAUNCH_DAYS * DAY && n >= LAUNCH_MIN)
                cand(id, "").offer(110f + min(20f, 8f * log10(n / LAUNCH_MIN.toFloat())), "LAUNCH", TrendGame.K_HOT)
        }

        // 4) WISH: свежая страница высоко в Top Wishlists; со второго дня — рывок по месту
        val today = LocalDate.now().toString()
        val wishToday = JSONObject(); wish.forEachIndexed { i, (id, _) -> wishToday.put(id.toString(), i + 1) }
        var wishPrev = obj(p, "wishPrev")
        if (p.getString("wishDay", "") != today) {
            p.edit().putString("wishPrev", p.getString("wishNow", "{}")).putString("wishDay", today).apply()
            wishPrev = obj(p, "wishNow")
        }
        p.edit().putString("wishNow", wishToday.toString()).apply()
        val allIds = (pool.keys + wish.map { it.first }).sorted()
        fun freshness(id: Int): Float {   // 0..1 — доля appid пула меньше этого (новые страницы — у верха)
            val i = allIds.binarySearch(id).let { if (it < 0) -it - 1 else it }
            return if (allIds.isEmpty()) 0f else i.toFloat() / allIds.size
        }
        wish.forEachIndexed { i, (id, name) ->
            val rank = i + 1
            val c = cand(id, name)
            val f = freshness(id)
            if (rank <= 120 && f >= 0.85f) {
                val strength = (1f - rank / 200f) * ((f - 0.85f) / 0.15f)
                c.offer(55f + 35f * strength, "WISH #$rank", TrendGame.K_WISH)
            }
            val prev = wishPrev.optInt(id.toString(), 0)
            if (prev > 0 && prev - rank >= 20) c.offer(65f + min(25f, (prev - rank) / 4f), "WISH ▲${prev - rank}", TrendGame.K_WISH)
            else if (prev == 0 && wishPrev.length() > 0 && rank <= 100) c.offer(70f, "WISH NEW", TrendGame.K_WISH)
        }

        // 5) рейтинг и данные для показа
        //    берём с запасом (часть отсеется как не-игры), считаем параллельно, оставляем 15 сильнейших
        // вишлистов в пуле сотни — берём их немного с запасом, а игр с онлайном — сколько влезет в MAX
        val sig = pool.values.filter { it.kind >= 0 }
        val top = (sig.filter { it.kind != TrendGame.K_WISH }.sortedByDescending { it.score }.take(MAX) +
            sig.filter { it.kind == TrendGame.K_WISH }.sortedByDescending { it.score }.take(ROWS + 3))
            .sortedByDescending { it.score }
        val out = pmap(top, 6) { c ->
            val m = details(meta, c.id, cc)
            val type = m?.optString("t").orEmpty()
            if (type.isNotEmpty() && type != "game") return@pmap null
            val name = c.name.ifBlank { m?.optString("n").orEmpty() }.ifBlank { "App ${c.id}" }
            // карточку не получили — считаем вышедшей всё, кроме сигнала по вишлистам
            val released = if (m == null) c.kind != TrendGame.K_WISH else !m.optBoolean("cs", m.optLong("r", -1L) <= 0)
            // вышедшие — только свежие: дата выхода известна и не старше FRESH_DAYS (старые хиты, вернувшиеся в чарт, не нужны)
            if (released) {
                val rel = m?.optLong("r", -1L) ?: -1L
                if (rel <= 0 || now - rel > FRESH_DAYS * DAY) return@pmap null
            }
            val l = live[c.id]
            val n = if (!released && c.kind == TrendGame.K_WISH) -1
                else ccu[c.id] ?: runCatching { players(c.id) }.getOrDefault(-1)
            TrendGame(
                c.id, name, n, max(l?.second ?: n, n), m?.optString("price")?.ifEmpty { null },
                c.signal, c.kind, if (released) null else m?.optString("d")?.ifEmpty { "TBA" } ?: "TBA", null,
            )
        }.filterNotNull().let { all ->
            // вишлисты — не больше MAX_WISH строк, остальное место — играм с онлайном;
            // если таких игр не хватило до ROWS, свободные места добирают следующие вишлисты
            var w = 0
            val pick = all.filter { it.kind != TrendGame.K_WISH || w++ < MAX_WISH }.take(ROWS).toMutableSet()
            all.filter { it !in pick }.filter { it.kind == TrendGame.K_WISH }.take(ROWS - pick.size).forEach { pick += it }
            all.filter { it in pick }
        }
            .let { list ->
                pmap(list, 6) { g ->
                    val img = synchronized(meta) { meta.optJSONObject(g.appid.toString()) }?.optString("img").orEmpty()
                    g.withImg(capsule(ctx, g.appid, listOf(img, logos[g.appid].orEmpty())))
                }.mapIndexed { i, g -> g ?: list[i] }
            }
        synchronized(meta) { synchronized(reviews) {
            p.edit().putString("meta", meta.toString()).putString("reviews", reviews.toString()).apply()
        } }
        if (out.isEmpty()) throw IOException("Steam: сигналов нет")
        p.edit().putString("last", toJson(out).toString()).apply()
        return out
    }

    /** Последний собранный список с диска — показать сразу при запуске, пока идёт свежий сбор. */
    fun cached(ctx: Context): List<TrendGame> {
        val a = runCatching {
            JSONArray(ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("last", "[]"))
        }.getOrNull() ?: return emptyList()
        val dir = File(ctx.filesDir, "steam")
        val out = ArrayList<TrendGame>()
        a.forEachObj { o ->
            val id = o.optInt("id")
            val f = File(dir, "$id.jpg")
            val img = if (f.exists()) runCatching { BitmapFactory.decodeFile(f.path) }.getOrNull() else null
            out += TrendGame(
                id, o.optString("n"), o.optInt("p", -1), o.optInt("k", -1), o.optString("pr").ifEmpty { null },
                o.optString("s"), o.optInt("kind"), o.optString("u").ifEmpty { null }, img,
            )
        }
        return out
    }

    private fun toJson(list: List<TrendGame>) = JSONArray().also { a ->
        for (g in list) a.put(JSONObject().put("id", g.appid).put("n", g.name).put("p", g.players).put("k", g.peak24)
            .put("pr", g.price ?: "").put("s", g.signal).put("kind", g.kind).put("u", g.upcoming ?: ""))
    }

    private fun TrendGame.withImg(b: Bitmap?) = TrendGame(appid, name, players, peak24, price, signal, kind, upcoming, b)

    /** Параллельный map для сетевых запросов; порядок результата — как у входа. */
    private fun <T, R> pmap(items: List<T>, threads: Int, f: (T) -> R): List<R?> {
        if (items.isEmpty()) return emptyList()
        val ex = Executors.newFixedThreadPool(min(threads, items.size))
        try {
            return ex.invokeAll(items.map { Callable { f(it) } }, 3, TimeUnit.MINUTES)
                .map { fu -> runCatching { fu.get() }.getOrNull() }   // упал или не успел — null
        } finally {
            ex.shutdownNow()
        }
    }

    // ---------- источники ----------

    /** Поиск магазина (json=1): [(appid, имя)] в порядке Steam. */
    private fun search(filter: String, count: Int, start: Int, cc: String): List<Pair<Int, String>> = runCatching {
        val items = JSONObject(get("$STORE/search/results/?json=1&filter=$filter&count=$count&start=$start&cc=$cc&l=english")).optJSONArray("items")
            ?: return@runCatching emptyList()
        val re = Regex("/apps/(\\d+)/")
        val out = ArrayList<Pair<Int, String>>()
        for (i in 0 until items.length()) {
            val o = items.optJSONObject(i) ?: continue
            val id = re.find(o.optString("logo"))?.groupValues?.get(1)?.toIntOrNull() ?: continue
            logos[id] = o.optString("logo")      // точный адрес картинки (у новых игр в пути есть хэш)
            if (out.none { it.first == id }) out += id to o.optString("name")
        }
        out
    }.getOrDefault(emptyList())

    /**
     * Карточка игры из кэша или магазина: тип, дата выхода, цена, имя.
     * Вышедшие кэшируются надолго (цена — сутки), невышедшие перепроверяются раз в сутки.
     */
    private fun details(meta: JSONObject, id: Int, cc: String): JSONObject? {
        val now = System.currentTimeMillis()
        // карточки без адреса обложки (старый кэш) — перезапросить
        synchronized(meta) { meta.optJSONObject(id.toString()) }
            ?.let { if (now - it.optLong("at") < DAY && it.optString("img").isNotEmpty()) return it }
        val o = runCatching {
            Thread.sleep(200)   // бережно к магазину
            JSONObject(get("$STORE/api/appdetails?appids=$id&cc=$cc&filters=basic,price_overview,release_date&l=english"))
                .optJSONObject(id.toString())?.optJSONObject("data")
        }.getOrNull() ?: return synchronized(meta) { meta.optJSONObject(id.toString()) }
        val rd = o.optJSONObject("release_date")
        val soon = rd == null || rd.optBoolean("coming_soon")
        val dateText = rd?.optString("date").orEmpty()
        val price = when {
            o.optBoolean("is_free") -> "Free"
            else -> o.optJSONObject("price_overview")?.optString("final_formatted")?.trim().orEmpty()
        }
        val m = JSONObject()
            .put("t", o.optString("type")).put("n", o.optString("name"))
            .put("r", if (soon) -1L else parseDate(dateText)).put("cs", soon).put("img", o.optString("capsule_image")).put("d", shortDate(dateText))
            .put("price", price).put("at", now)
        synchronized(meta) { meta.put(id.toString(), m) }
        return m
    }

    private fun cachedReviews(store: JSONObject, id: Int, now: Long): Int? {
        synchronized(store) { store.optJSONArray(id.toString()) }?.let { if (now - it.optLong(1) < DAY) return it.optInt(0) }
        val n = runCatching {
            Thread.sleep(200)
            JSONObject(get("$STORE/appreviews/$id?json=1&language=all&purchase_type=all&num_per_page=0"))
                .optJSONObject("query_summary")?.optInt("total_reviews")
        }.getOrNull() ?: return synchronized(store) { store.optJSONArray(id.toString()) }?.optInt(0)
        synchronized(store) { store.put(id.toString(), JSONArray().put(n).put(now)) }
        return n
    }

    private fun players(id: Int): Int =
        JSONObject(get("$API/ISteamUserStats/GetNumberOfCurrentPlayers/v1/?appid=$id")).optJSONObject("response")?.optInt("player_count") ?: -1

    // ---------- даты ----------

    /** «Oct 28, 2026» / «28 Oct, 2026» → мс; не распознали — -1. */
    private fun parseDate(s: String): Long {
        for (pat in listOf("MMM d, yyyy", "d MMM, yyyy")) {
            val d = runCatching { LocalDate.parse(s.trim(), java.time.format.DateTimeFormatter.ofPattern(pat, java.util.Locale.ENGLISH)) }.getOrNull() ?: continue
            return d.atStartOfDay(java.time.ZoneOffset.UTC).toInstant().toEpochMilli()
        }
        // «Q4 2024», «2024» и т.п. — хотя бы год: 1 января этого года
        Regex("(19|20)\\d{2}").find(s)?.value?.toIntOrNull()?.let {
            return LocalDate.of(it, 1, 1).atStartOfDay(java.time.ZoneOffset.UTC).toInstant().toEpochMilli()
        }
        return -1L
    }

    /** Короткая дата для колонки: «Feb 23, 2027» → «Feb 2027», «To be announced» → «TBA». */
    private fun shortDate(s: String): String {
        val t = s.trim()
        if (t.isEmpty() || t.startsWith("To be", true) || t.startsWith("Coming", true)) return "TBA"
        Regex("([A-Za-z]{3})[a-z]* \\d{1,2}, (\\d{4})").find(t)?.let { return "${it.groupValues[1]} ${it.groupValues[2]}" }
        Regex("\\d{1,2} ([A-Za-z]{3})[a-z]*, (\\d{4})").find(t)?.let { return "${it.groupValues[1]} ${it.groupValues[2]}" }
        return t.take(9)
    }

    // ---------- сеть и утилиты ----------

    private fun obj(p: android.content.SharedPreferences, key: String) =
        runCatching { JSONObject(p.getString(key, "{}")!!) }.getOrDefault(JSONObject())

    private fun arr(body: String, key: String): JSONArray? = JSONObject(body).optJSONObject("response")?.optJSONArray(key)

    private inline fun JSONArray.forEachObj(f: (JSONObject) -> Unit) { for (i in 0 until length()) optJSONObject(i)?.let(f) }

    /** Обложка 231×87: из кэша или скачать (два известных адреса CDN Steam). */
    private fun capsule(ctx: Context, id: Int, known: List<String>): Bitmap? {
        val dir = File(ctx.filesDir, "steam").apply { mkdirs() }
        val f = File(dir, "$id.jpg")
        if (!f.exists() || f.length() < 512) {
            // сначала точный адрес из карточки/поиска (у игр с 2024 г. в пути хэш — старые адреса дают 404),
            // затем старые адреса без хэша
            val urls = known.filter { it.startsWith("http") } + listOf(
                "https://shared.akamai.steamstatic.com/store_item_assets/steam/apps/$id/capsule_231x87.jpg",
                "https://cdn.akamai.steamstatic.com/steam/apps/$id/capsule_231x87.jpg",
            )
            for (u in urls) {
                val bytes = runCatching { getBytes(u, 300 * 1024) }.getOrNull() ?: continue
                if (bytes.size > 512) { f.writeBytes(bytes); break }
            }
        } else f.setLastModified(System.currentTimeMillis())   // в ходу — не вычищать
        // держим обложки только недавних игр (до 120 файлов)
        dir.listFiles()?.sortedBy { it.lastModified() }?.dropLast(120)?.forEach { it.delete() }
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
