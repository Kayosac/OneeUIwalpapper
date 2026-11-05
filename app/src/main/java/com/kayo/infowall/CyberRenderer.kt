package com.kayo.infowall

import android.graphics.Camera
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.tan

/** Всё, что нужно одному кадру. */
class FrameData(
    val cfg: Config,
    val t: Float,
    val stats: Stats,
    val remote: RemoteData?,
    val remoteError: String?,
    val weather: WeatherData?,
    val weatherNote: String?,
    val city: String,
    val news: List<Headline> = emptyList(),
    val rates: Rates? = null,
    val servers: List<ServerView> = emptyList(),
    val fx: FxEdit? = null,
    val steam: List<TrendGame> = emptyList(),
    val steamNote: String? = null,
    val update: String? = null,
    val steamBusy: Boolean = false,     // идёт сбор Steam — значок ⟳ крутится
    val mathEdit: MathEdit? = null,     // набор своей формулы: клавиатура на месте игр
    val lock: Boolean = false,          // экран блокировки: только сцена, ракурс круче
)

/**
 * Концепт «4.3 · Циан»: рельеф из треугольников, полосатое солнце в точке схода,
 * HUD с часами, метриками, погодой и календарём.
 * Рисует в макетных координатах 1280×800 и масштабирует под экран.
 */
class CyberRenderer {

    // ---------- цвета ----------
    private var accent = 0
    private var label = 0
    private var face = 0

    // ---------- кисти ----------
    private val mono = Typeface.create(Typeface.MONOSPACE, Typeface.NORMAL)
    private var clockFace: Typeface = mono
    private var numFace: Typeface = mono
    private var labelFace: Typeface = mono
    private var hudFace: Typeface = mono

    // правый край интерфейса в координатах слоя (слой масштабируется на uiScale)
    private var uiR = 1232f

    // где на экране (в пикселях) лежит блок курсов — для тапа
    private val converterOnScreen = RectF()
    private val fxRowRects = Array(RatesSource.MAX_CODES) { RectF() }
    private var fxRowCodes: List<String> = emptyList()
    private var screenScale = 1f
    private var screenOx = 0f

    // клавиатура ввода суммы на обоях: экранные прямоугольники клавиш
    private val padOnScreen = RectF()
    private val padKeys = ArrayList<Pair<String, RectF>>()
    private val padPool = ArrayList<RectF>()

    /** Клавиша под тапом; "" — тап по клавиатуре мимо клавиш; null — мимо клавиатуры. */
    /** Клавиша формульной клавиатуры под тапом; "" — мимо клавиш, но по панели; null — мимо панели. */
    fun mathPadHit(x: Float, y: Float): String? {
        if (mathPadOnScreen.isEmpty || !mathPadOnScreen.contains(x, y)) return null
        for ((k, r) in mathKeys) if (r.contains(x, y)) return k
        return ""
    }

    fun padHit(x: Float, y: Float): String? {
        if (padOnScreen.isEmpty || !padOnScreen.contains(x, y)) return null
        for ((k, r) in padKeys) if (r.contains(x, y)) return k
        return ""
    }

    // зоны тапа «открыть приложение»: часы → Часы, календарь → Google Календарь
    private val clockOnScreen = RectF()
    private val calendarOnScreen = RectF()
    private val goalsOnScreen = RectF()
    private val memOnScreen = RectF()       // блок «Память» → экран памяти в «Обслуживании устройства»
    private val updOnScreen = RectF()
    private val srvRects = ArrayList<RectF>()

    /** "clock" / "calendar" / "goals" под точкой тапа или null. */
    fun appHit(x: Float, y: Float): String? = when {
        !updOnScreen.isEmpty && updOnScreen.contains(x, y) -> "update"      // метка лежит на зоне часов — проверяем раньше
        !clockOnScreen.isEmpty && clockOnScreen.contains(x, y) -> "clock"
        !calendarOnScreen.isEmpty && calendarOnScreen.contains(x, y) -> "calendar"
        !goalsOnScreen.isEmpty && goalsOnScreen.contains(x, y) -> "goals"
        !memOnScreen.isEmpty && memOnScreen.contains(x, y) -> "memory"
        mathBtns.any { !it.isEmpty && it.contains(x, y) } -> "math:" + mathBtns.indexOfFirst { !it.isEmpty && it.contains(x, y) }
        !mathEditRect.isEmpty && mathEditRect.contains(x, y) -> "mathedit"
        !steamNextRect.isEmpty && steamNextRect.contains(x, y) -> "steamnext"
        else -> steamRows.indices.firstOrNull { steamRows[it].contains(x, y) }?.let { "steam:${steamIds[it]}" }
            ?: srvRects.indices.firstOrNull { srvRects[it].contains(x, y) }?.let { "srv:$it" }
    }

    // ---------- отклик на тап: расходящееся кольцо в точке касания (экранные пиксели) ----------
    private var tapX = 0f
    private var tapY = 0f
    private var tapAtMs = 0L
    private val tapP = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }

    fun tapAt(x: Float, y: Float) { tapX = x; tapY = y; tapAtMs = android.os.SystemClock.uptimeMillis() }

    /** Рисует кольцо; true — анимация ещё идёт (нужен следующий кадр). */
    fun drawTapFx(c: Canvas): Boolean {
        val k = (android.os.SystemClock.uptimeMillis() - tapAtMs) / 420f
        if (tapAtMs == 0L || k >= 1f) return false
        val s = screenScale
        val a = 1f - k
        tapP.color = withA(accent, 0.9f * a)
        tapP.strokeWidth = 2.5f * s
        tapP.setShadowLayer(8f * s, 0f, 0f, withA(accent, 0.8f * a))
        c.drawCircle(tapX, tapY, (8f + 30f * k) * s, tapP)
        tapP.clearShadowLayer()
        val dot = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = withA(Color.WHITE, a) }
        c.drawCircle(tapX, tapY, 3f * s * (1f - k * 0.5f), dot)
        return true
    }

    private fun toScreen(out: RectF, l: Float, t: Float, r: Float, b: Float, ui: Float) {
        val k = ui * screenScale
        out.set(screenOx + l * k, t * k, screenOx + r * k, b * k)
    }

    /** Валюта строки конвертера под точкой тапа (экранные пиксели) или null. */
    fun converterHit(x: Float, y: Float): String? {
        if (converterOnScreen.isEmpty || !converterOnScreen.contains(x, y)) return null
        for (i in fxRowCodes.indices) if (fxRowRects[i].contains(x, y)) return fxRowCodes[i]
        return null
    }

    // ---------- затемнение снизу: последний слой, поверх сцены и свечения ----------
    private val shadeP = Paint()
    private var shadeKey = ""

    fun drawBottomShade(c: Canvas, w: Int, h: Int, cfg: Config) {
        val solidTop = h * (1f - cfg.shadeSolid)
        val fadeTop = solidTop - h * cfg.shadeFade
        val key = "$h|${cfg.shadeSolid}|${cfg.shadeFade}"
        if (key != shadeKey) {
            shadeKey = key
            shadeP.shader = LinearGradient(
                0f, fadeTop, 0f, solidTop,
                intArrayOf(Color.TRANSPARENT, withA(Color.BLACK, 0.55f), withA(Color.BLACK, 0.9f), Color.BLACK),
                floatArrayOf(0f, 0.45f, 0.8f, 1f), Shader.TileMode.CLAMP,
            )
        }
        c.drawRect(0f, fadeTop, w.toFloat(), solidTop, shadeP)
        fill.shader = null
        fill.color = Color.BLACK
        c.drawRect(0f, solidTop, w.toFloat(), h.toFloat(), fill)
    }

    /** Циферблат, мелкие цифры (значения, пояса) и подписи (кириллица). */
    fun setFonts(clock: Typeface, num: Typeface, label: Typeface, hud: Typeface = num) {
        hudFace = hud
        clockFace = clock
        numFace = num
        labelFace = label
        tickerRunsSize = -1f   // ширины отрезков бегущей строки зависят от шрифтов
    }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = mono }
    private val skyP = Paint()
    private val fogP = Paint()
    private val bottomP = Paint()
    private val horizonP = Paint(Paint.ANTI_ALIAS_FLAG)

    private val rect = RectF()
    private val path = Path()
    private val camera = Camera()
    private val matrix = Matrix()

    // кэш шейдеров по ключу (цвет, точка схода, высота)
    private var shaderKey = ""

    // ---------- рельеф ----------
    private val terrain = Terrain()


    // ---------- иконки погоды (в квадрате 26×22) ----------
    private val cloudPath = Path().apply {
        val a = Path().apply { addRoundRect(RectF(4f, 11f, 22f, 18f), 3.5f, 3.5f, Path.Direction.CW) }
        val b = Path().apply { addCircle(9.5f, 11f, 3.8f, Path.Direction.CW) }
        val c = Path().apply { addCircle(15f, 9f, 5.2f, Path.Direction.CW) }
        op(a, b, Path.Op.UNION)
        op(this, c, Path.Op.UNION)
    }
    private val smallCloud = Path().apply {
        val m = Matrix().apply { setScale(0.75f, 0.75f); postTranslate(7f, 6.5f) }
        cloudPath.transform(m, this)
    }
    private val highCloud = Path().apply {
        val m = Matrix().apply { setTranslate(0f, -4f) }
        cloudPath.transform(m, this)
    }

    // ---------- кэш строк, пересчёт раз в минуту ----------
    private var minuteKey = -1L
    private var hh = ""
    private var mm = ""
    private var zoneTimes: List<Pair<String, String>> = emptyList()
    private var monthTitle = ""
    private var yearTitle = ""
    private var calStartCol = 0
    private var calDays = 30
    private var calToday = 1
    private val hhmm = DateTimeFormatter.ofPattern("HH:mm")
    private val months = arrayOf("ЯНВАРЬ", "ФЕВРАЛЬ", "МАРТ", "АПРЕЛЬ", "МАЙ", "ИЮНЬ", "ИЮЛЬ", "АВГУСТ", "СЕНТЯБРЬ", "ОКТЯБРЬ", "НОЯБРЬ", "ДЕКАБРЬ")

    fun draw(c: Canvas, w: Int, h: Int, f: FrameData) {
        val cfg = f.cfg
        updateColors(cfg.accent)
        updateTime(cfg)

        val s = min(w / W, h / H)
        val ox = (w - W * s) / 2f
        val ext = ox / s                    // поля по бокам на широких экранах
        val dh = h / s                      // видимая высота в макетных единицах (≥ 800)
        // камера «летит»: рыскание, крен и тангаж плавно гуляют во времени
        val t = f.t
        // не больше ~36°: иначе правый край кадра смотрит вдоль рядов сетки и она обрывается
        // на экране блокировки камера рыщет почти вдвое шире
        val yawSwing = if (f.lock) 16f else 9f
        val yawNow = (cfg.yawDeg + yawSwing * sin(t * 0.05f) + 3f * sin(t * 0.13f + 1f)).coerceIn(0f, 36f)
        val vp = CX - tan(Math.toRadians(yawNow.toDouble())).toFloat() * F

        screenScale = s
        screenOx = ox
        c.drawColor(Color.BLACK)
        c.save()
        c.translate(ox, 0f)
        c.scale(s, s)

        ensureShaders(dh, ext)
        drawScene(c, f, vp, yawNow, ext, dh)
        if (f.lock) {
            // экран блокировки: только сцена — ни часов, ни плашек, ни зон тапа
            clockOnScreen.setEmpty(); calendarOnScreen.setEmpty(); goalsOnScreen.setEmpty(); updOnScreen.setEmpty(); memOnScreen.setEmpty(); mathBtns.forEach { it.setEmpty() }; mathEditRect.setEmpty(); mathPadOnScreen.setEmpty()
            converterOnScreen.setEmpty(); padOnScreen.setEmpty(); steamRows.clear(); steamNextRect.setEmpty(); srvRects.clear()
            c.restore()
            return
        }
        // интерфейс мельче сцены: слой рисуется в увеличенных координатах и сжимается
        val ui = cfg.uiScale
        c.save()
        c.scale(ui, ui)
        uiR = W / ui - 48f
        drawHud(c, f)
        drawTicker(c, f)
        drawWeatherAndCalendar(c, f)
        val me = f.mathEdit
        if (me != null && cfg.math) {
            steamRows.clear(); steamNextRect.setEmpty()
            drawMathPad(c, f, me, 48f, 304f, if (cfg.steam) steamRight else 538f, 758f)
        } else {
            mathPadOnScreen.setEmpty()
            if (cfg.steam) drawSteam(c, f) else { steamRows.clear(); steamNextRect.setEmpty() }
        }
        // «формула» — справа от игр, размером с погоду
        val ml = if (cfg.steam) steamRight + 10f else 48f
        if (cfg.math) drawMath(c, f, ml, 304f, ml + 268f, 466f) else { mathBtns.forEach { it.setEmpty() }; mathEditRect.setEmpty() }
        c.restore()

        c.restore()
    }

    // ================= цвета и кэши =================

    private fun updateColors(a: Int) {
        if (a == accent && label != 0) return
        accent = a
        label = mix(a, Color.rgb(110, 130, 140), 0.42f)
        face = mix(a, Color.WHITE, 0.35f)
        shaderKey = ""
    }

    private fun updateTime(cfg: Config) {
        val now = System.currentTimeMillis()
        val key = now / 60_000
        if (key == minuteKey) return
        minuteKey = key
        val local = ZonedDateTime.now()
        hh = String.format(Locale.US, "%02d", local.hour)
        mm = String.format(Locale.US, "%02d", local.minute)
        zoneTimes = cfg.zones.map { z ->
            z.label to runCatching { ZonedDateTime.now(ZoneId.of(z.id)).format(hhmm) }.getOrDefault("--:--")
        }
        val d = LocalDate.now()
        monthTitle = months[d.monthValue - 1]
        yearTitle = d.year.toString()
        calStartCol = d.withDayOfMonth(1).dayOfWeek.value - 1
        calDays = d.lengthOfMonth()
        calToday = d.dayOfMonth
    }

    fun invalidateTime() { minuteKey = -1 }

    private fun ensureShaders(dh: Float, ext: Float) {
        val key = "$accent|${dh.roundToInt()}|${ext.roundToInt()}"
        if (key == shaderKey) return
        shaderKey = key
        skyP.shader = LinearGradient(0f, 300f, 0f, HZ, Color.TRANSPARENT, withA(accent, 0.14f), Shader.TileMode.CLAMP)
        fogP.shader = LinearGradient(
            0f, HZ, 0f, HZ + FOG_H,
            intArrayOf(withA(Color.BLACK, 0.6f), withA(Color.BLACK, 0.22f), Color.TRANSPARENT),
            floatArrayOf(0f, 0.3f, 1f), Shader.TileMode.CLAMP,
        )
        // горизонт: яркое пятно в нуле, при рисовании сдвигается в точку схода
        horizonP.shader = LinearGradient(
            -1600f, 0f, 1600f, 0f,
            intArrayOf(withA(accent, 0.4f), accent, Color.WHITE, accent, withA(accent, 0.4f)),
            floatArrayOf(0f, 0.42f, 0.5f, 0.62f, 1f), Shader.TileMode.CLAMP,
        )
    }

    // ================= сцена =================

    private fun drawScene(c: Canvas, f: FrameData, vp: Float, yaw: Float, ext: Float, dh: Float) {
        val t = f.t
        val xl = -ext - 400f
        val xr = W + ext + 400f
        val lk = f.lock
        // блокировка: крен и тангаж сильнее, горизонт выше, камера вдвое выше — земли в кадре больше
        val roll = if (lk) 8f * sin(t * 0.09f) + 3f * sin(t * 0.23f) else 4f * sin(t * 0.09f) + 1.5f * sin(t * 0.23f)
        val pitch = (if (lk) 24f else 14f) * sin(t * 0.07f)
        val lift = if (lk) 260f else 0f
        val cam = if (lk) CAM * 1.9f else CAM

        c.save()
        c.rotate(roll, vp, HZ + pitch - lift)
        c.translate(0f, pitch - lift)

        // небо у горизонта
        c.drawRect(xl, 300f, xr, HZ, skyP)

        // рельеф: летим вперёд; яркость отрезков гаснет с глубиной, вдали — горы
        terrain.build(t, yaw, (W / 2f + ext) / F * (if (lk) 1.6f else 1.3f), CX, HZ, F, cam)
        for (b in 0 until Terrain.NB) {
            val k = (b + 1f) / Terrain.NB
            val d = terrain.diagBuf[b]; val cl = terrain.colsBuf[b]; val rw = terrain.rowsBuf[b]
            if (d.n > 0) { line(withA(accent, 0.34f * k), 1f); c.drawLines(d.a, 0, d.n, stroke) }
            if (cl.n > 0) { line(withA(accent, 0.58f * k), 1.1f); c.drawLines(cl.a, 0, cl.n, stroke) }
            if (rw.n > 0) {
                line(withA(accent, 0.2f * k), 4.5f); c.drawLines(rw.a, 0, rw.n, stroke)
                line(withA(accent, 0.92f * k), 1.4f); c.drawLines(rw.a, 0, rw.n, stroke)
            }
        }

        // мягкое свечение у горизонта — без резкой линии, горы её ломают
        val pulse = 0.8f + 0.2f * sin(t * 1.05f)
        fill.shader = null
        fill.color = withA(accent, 0.06f * pulse); c.drawRect(xl, HZ - 26f, xr, HZ + 10f, fill)
        fill.color = withA(accent, 0.10f * pulse); c.drawRect(xl, HZ - 9f, xr, HZ + 3f, fill)

        c.restore()
    }

    // ================= HUD =================

    private fun drawHud(c: Canvas, f: FrameData) {
        // уголки рамки
        val l = 48f; val tp = 22f; val r = uiR; val b = 226f; val k = 22f
        stroke.shader = null
        line(withA(accent, 0.35f), 6f)
        corners(c, l, tp, r, b, k)
        line(accent, 2f)
        corners(c, l, tp, r, b, k)

        drawClock(c, f)
        toScreen(clockOnScreen, 60f, 40f, 560f, 205f, f.cfg.uiScale)   // часы вместе с поясами
        drawUpdateChip(c, f)
        drawMetrics(c, f)
    }

    /** Метка «вышло обновление» под часовыми поясами; тап — настройки с кнопкой «Обновить». */
    private fun drawUpdateChip(c: Canvas, f: FrameData) {
        val name = f.update
        if (name == null) { updOnScreen.setEmpty(); return }
        val s = "▲ ОБНОВЛЕНИЕ $name"
        text.typeface = labelFace; text.textSize = 10f; text.letterSpacing = 0.15f
        val w = measureMixed(s)
        val l = 82f; val t = 200f; val r = l + w + 16f; val b = t + 18f
        val pulse = 0.65f + 0.35f * sin(f.t * 3f)
        fill.shader = null; fill.color = withA(accent, 0.12f * pulse); c.drawRect(l, t, r, b, fill)
        line(withA(accent, 0.8f * pulse), 1f); c.drawRect(l, t, r, b, stroke)
        text.color = accent
        drawMixed(c, s, l + 8f, t + 13f)
        text.letterSpacing = 0f
        toScreen(updOnScreen, l - 6f, t - 6f, r + 6f, b + 6f, f.cfg.uiScale)
    }

    private fun corners(c: Canvas, l: Float, t: Float, r: Float, b: Float, k: Float) {
        path.reset()
        path.moveTo(l, t + k); path.lineTo(l, t); path.lineTo(l + k, t)
        path.moveTo(r - k, t); path.lineTo(r, t); path.lineTo(r, t + k)
        path.moveTo(l, b - k); path.lineTo(l, b); path.lineTo(l + k, b)
        path.moveTo(r - k, b); path.lineTo(r, b); path.lineTo(r, b - k)
        c.drawPath(path, stroke)
    }

    private fun drawClock(c: Canvas, f: FrameData) {
        val x = 78f
        val base = 134f
        text.typeface = clockFace
        text.textSize = 86f
        text.letterSpacing = 0f
        val full = "$hh:$mm"

        // мягкий поворот: правый край ближе к зрителю (как в макете: rotateY(-16°), перспектива 1100)
        c.save()
        camera.save()
        camera.setLocation(0f, 0f, -1100f / 72f)
        camera.rotateY(-16f)
        camera.getMatrix(matrix)
        camera.restore()
        matrix.preTranslate(-x, -(base - 38f))
        matrix.postTranslate(x, base - 38f)
        c.concat(matrix)

        // объём: слои уходят вправо и темнеют
        for (dd in 7 downTo 1) {
            text.color = shade(accent, 0.36f - dd * 0.035f)
            text.clearShadowLayer()
            c.drawText(full, x + dd * 1.2f, base + dd * 0.35f, text)
        }
        text.color = face
        text.setShadowLayer(22f, 0f, 0f, withA(accent, 0.6f))
        val hhW = text.measureText(hh)
        val colonW = text.measureText(":")
        c.drawText(hh, x, base, text)
        text.alpha = if ((f.t % 2f) < 1f) 255 else 70
        c.drawText(":", x + hhW, base, text)
        text.alpha = 255
        c.drawText(mm, x + hhW + colonW, base, text)
        text.clearShadowLayer()
        c.restore()

        // часовые пояса: метка и время одним шрифтом и размером
        var zx = 82f
        val zy = 190f
        text.typeface = numFace; text.textSize = 24f; text.letterSpacing = 0f
        for ((lab, time) in zoneTimes) {
            text.color = label
            text.typeface = numFace; text.textSize = 24f
            c.drawText(lab, zx, zy, text)
            zx += text.measureText(lab) + 8f
            text.typeface = numFace; text.textSize = 24f
            text.color = accent
            text.setShadowLayer(10f, 0f, 0f, withA(accent, 0.65f))
            c.drawText(time, zx, zy, text)
            text.clearShadowLayer()
            zx += text.measureText(time) + 24f
        }
    }

    /** Плашки мониторинга: все цифры — hudFace (подменяем шрифт цифр на время отрисовки). */
    private fun drawMetrics(c: Canvas, f: FrameData) {
        val keep = numFace
        numFace = hudFace
        try { drawMetricsInner(c, f) } finally { numFace = keep }
    }

    private fun drawMetricsInner(c: Canvas, f: FrameData) {
        val st = f.stats
        // колонки справа от часов: [батарея + память] [сеть] [сервер 1] [сервер 2] [цели]
        val x0 = 78f + 480f + 32f
        val x1 = uiR - 28f
        // все колонки одной ширины и делят панель целиком: 4 колонки по ~216 при двух серверах
        val nSrv = min(2, f.servers.size)
        val cols = 3 + nSrv
        val gap = 24f
        val cw = (x1 - x0 - gap * (cols - 1)) / cols
        val colW = cw; val netW = cw; val srvW = cw
        val xB = x0 + colW + gap
        fill.shader = null
        fill.color = withA(accent, 0.32f)
        c.drawRect(x0, 50f, x0 + 1f, 198f, fill)
        c.drawRect(xB, 50f, xB + 1f, 198f, fill)

        // --- батарея ---
        val ax = x0 + 16f
        val aEnd = x0 + colW
        caption(c, "БАТАРЕЯ", ax, 68f)
        val pct = st.batteryPct
        val batText = buildString {
            append(if (pct >= 0) "$pct%" else "—")
            st.batteryTemp?.let { append(" $it°") }
            if (st.charging) append(" +")
        }
        text.typeface = numFace; text.textSize = 18f; text.letterSpacing = 0f; text.color = accent
        text.setShadowLayer(10f, 0f, 0f, withA(accent, 0.65f))
        c.drawText(batText, aEnd - text.measureText(batText), 70f, text)
        text.clearShadowLayer()
        val on = if (pct >= 0) (pct + 5) / 10 else 0
        val segW = (aEnd - ax - 27f) / 10f
        for (k in 0 until 10) {
            val sx = ax + k * (segW + 3f)
            fill.color = if (k < on) accent else withA(accent, 0.14f)
            if (k < on) fill.setShadowLayer(6f, 0f, 0f, withA(accent, 0.8f)) else fill.clearShadowLayer()
            c.drawRect(sx, 80f, sx + segW, 88f, fill)
        }
        fill.clearShadowLayer()

        // --- память: RAM и ROM в одной сетке ---
        caption(c, "ПАМЯТЬ", ax, 122f)
        memRow(c, "RAM", st.ramUsed, st.ramTotal, ax, aEnd, 150f)
        memRow(c, "ROM", st.romUsed, st.romTotal, ax, aEnd, 178f)
        toScreen(memOnScreen, x0, 102f, x0 + colW + gap / 2f, 205f, f.cfg.uiScale)

        // --- сеть: графики ---
        val bx = xB + 16f
        val bEnd = xB + netW
        caption(c, "СЕТЬ", bx, 68f)
        text.typeface = labelFace; text.textSize = 10f; text.letterSpacing = 0f; text.color = label
        c.drawText("Мбит/с", bEnd - text.measureText("Мбит/с"), 68f, text)
        netRow(c, "↓", st.rxNow, st.rxHist, st.histCount, bx, bEnd, 116f)
        netRow(c, "↑", st.txNow, st.txHist, st.histCount, bx, bEnd, 172f)

        // --- серверы ---
        while (srvRects.size > nSrv) srvRects.removeAt(srvRects.size - 1)
        while (srvRects.size < nSrv) srvRects += RectF()
        for (i in 0 until nSrv) {
            val sx = bEnd + gap + i * (srvW + gap)
            toScreen(srvRects[i], sx, 40f, sx + srvW + gap / 2f, 205f, f.cfg.uiScale)
            fill.shader = null
            fill.color = withA(accent, 0.32f)
            c.drawRect(sx, 50f, sx + 1f, 198f, fill)
            drawServer(c, f.servers[i], sx + 16f, sx + srvW)
        }

        // --- цели: 6 колец «сколько дней осталось» ---
        val gxs = bEnd + gap + nSrv * (srvW + gap)
        fill.shader = null
        fill.color = withA(accent, 0.32f)
        c.drawRect(gxs, 50f, gxs + 1f, 198f, fill)
        drawGoals(c, f.cfg.goals, gxs + 16f, gxs + cw)
        toScreen(goalsOnScreen, gxs, 40f, gxs + cw + gap / 2f, 205f, f.cfg.uiScale)
    }

    private val today get() = java.time.LocalDate.now()

    /**
     * Сегментное кольцо в стиле ретровейв: [GOAL_SEG] толстых сегментов с зазорами,
     * [on] заполнено; последний заполненный — раскалённый (белый), если [hot] = 1.
     */
    private fun segRing(c: Canvas, on: Int, hot: Int, col: Int) {
        val step = 360f / GOAL_SEG
        for (k in 0 until GOAL_SEG) {
            val a0 = -90f + k * step + 2.2f
            val sw = step - 4.4f
            if (k < on) {
                line(withA(col, 0.28f), 10f); c.drawArc(rect, a0, sw, false, stroke)
                line(if (hot == 1 && k == on - 1) mix(col, Color.WHITE, 0.6f) else col, 6f)
                c.drawArc(rect, a0, sw, false, stroke)
            } else {
                line(withA(accent, 0.12f), 6f); c.drawArc(rect, a0, sw, false, stroke)
            }
        }
    }

    private fun drawGoals(c: Canvas, goals: List<Goal>, x: Float, end: Float) {
        caption(c, "ЦЕЛИ", x, 68f)
        val cellW = (end - x) / 3f
        val r = 17f
        val now = today
        for (i in 0 until Goals.COUNT) {
            val g = goals.getOrNull(i) ?: Goal("", null, null)
            val cx = x + cellW * (i % 3 + 0.5f)
            val cy = if (i < 3) 100f else 162f
            rect.set(cx - r, cy - r, cx + r, cy + r)
            // тонкое внешнее кольцо-рамка
            line(withA(accent, if (g.empty) 0.12f else 0.28f), 1f); c.drawCircle(cx, cy, r + 6f, stroke)
            if (g.empty) {
                segRing(c, 0, 0, accent)
                text.typeface = labelFace; text.textSize = 12f; text.letterSpacing = 0f; text.color = withA(label, 0.5f)
                center(c, "+", cx, cy + 4.5f)
                continue
            }
            val left = g.daysLeft(now) ?: 0L
            val p = g.progress(now)
            val col = when { left < 0 -> withA(label, 0.6f); left <= 3 -> BAD_COLOR; else -> accent }
            // убывающий таймер: в день, когда задали дату, кольцо полное, к самой дате — пустое
            val remain = if (left < 0) 0f else 1f - p
            segRing(c, kotlin.math.ceil(remain * GOAL_SEG).toInt(), if (left < 0) 0 else 1, col)
            // в центре — дни; прошла — галочка
            if (left < 0) {
                text.typeface = labelFace; text.textSize = 12f; text.color = col
                center(c, "✓", cx, cy + 4.5f)
            } else {
                val v = if (left > 999) "999+" else left.toString()
                text.typeface = numFace; text.letterSpacing = 0f
                text.textSize = if (v.length <= 2) 13f else if (v.length == 3) 11f else 9f
                text.color = if (left <= 3) BAD_COLOR else face
                center(c, v, cx, cy + text.textSize * 0.36f)
            }
            // подпись под кольцом, обрезанная по ширине ячейки
            text.typeface = labelFace; text.textSize = 9f; text.letterSpacing = 0.06f; text.color = label
            var name = g.name.ifBlank { "ЦЕЛЬ ${i + 1}" }.uppercase()
            while (name.length > 1 && measureMixed(name) > cellW - 6f) name = name.dropLast(1)
            centerMixed(c, name, cx, cy + r + 16f)
            text.letterSpacing = 0f
        }
    }

    /** Колонка сервера: ● ХОСТИНГ, IP, три мини-графика CPU / RAM / ROM (шкала 0–100 %). */
    private fun drawServer(c: Canvas, s: ServerView, x: Float, end: Float) {
        val off = s.online == false
        val dotC = when (s.online) { true -> accent; false -> BAD_COLOR; null -> withA(accent, 0.3f) }
        fill.shader = null; fill.color = dotC
        if (s.online != null) fill.setShadowLayer(6f, 0f, 0f, dotC)
        c.drawCircle(x + 3f, 64f, 3f, fill)
        fill.clearShadowLayer()
        text.typeface = labelFace; text.textSize = 11f; text.letterSpacing = 0.25f
        text.color = if (off) BAD_COLOR else label
        drawMixed(c, s.name.uppercase(), x + 12f, 68f)
        text.letterSpacing = 0f
        // аптайм справа от названия
        val up = s.metrics?.uptimeSec ?: 0L
        if (!off && up > 0) {
            val u = if (up >= 86_400) "${up / 86_400}д ${up % 86_400 / 3600}ч" else "${up / 3600}ч ${up % 3600 / 60}м"
            text.textSize = 10f; text.color = label
            drawMixed(c, u, end - measureMixed(u), 68f)
        }

        text.typeface = numFace; text.textSize = 11f; text.color = if (off) withA(label, 0.6f) else face
        c.drawText(s.ip, x, 86f, text)

        // строка: подпись · короткий график · цифры (CPU — %, память и диск — занято/всего в ГБ)
        val m = s.metrics
        val labels = arrayOf("CPU", "RAM", "ROM")
        for (k in 0 until 3) {
            val top = 98f + k * 32f
            val base = top + 15f
            text.typeface = labelFace; text.textSize = 10f; text.letterSpacing = 0.08f; text.color = label
            c.drawText(labels[k], x, base, text)
            text.letterSpacing = 0f
            val hist = when (k) { 0 -> m?.cpuHist; 1 -> m?.ramHist; else -> m?.diskHist }
            spark(c, hist, x + 32f, top, end - x - 32f - 72f, 22f, off)
            if (off && k == 0) {
                text.typeface = labelFace; text.textSize = 10f; text.letterSpacing = 0.12f; text.color = BAD_COLOR
                c.drawText("OFFLINE", end - text.measureText("OFFLINE"), base, text)
                text.letterSpacing = 0f
                continue
            }
            val v = when {
                m == null || off -> "—"
                k == 0 -> "${(m.cpuHist.lastOrNull() ?: 0f).roundToInt()}%"
                k == 1 -> "${gbShort(m.ramUsed)}/${gbShort(m.ramTotal)}G"
                else -> "${gbShort(m.diskUsed)}/${gbShort(m.diskTotal)}G"
            }
            text.textSize = 11f
            text.color = if (off) withA(label, 0.6f) else face
            drawMixed(c, v, end - measureMixed(v), base)
        }
    }

    /** Мини-график с постоянной шкалой 0–100 %: линия, заливка, точка на последнем значении. */
    private fun spark(c: Canvas, v: FloatArray?, x: Float, top: Float, w: Float, h: Float, dim: Boolean) {
        fill.shader = null
        fill.color = withA(accent, 0.07f)
        c.drawRect(x, top, x + w, top + h, fill)
        fill.color = withA(accent, 0.15f)
        c.drawRect(x, top + h, x + w, top + h + 1f, fill)
        if (v == null || v.isEmpty()) return
        val last = v[v.size - 1]
        val col = when { dim -> withA(label, 0.45f); last >= 90f -> BAD_COLOR; else -> accent }
        fun py(p: Float) = top + h - (p.coerceIn(0f, 100f) / 100f) * (h - 2f)
        path.reset()
        if (v.size == 1) {
            path.moveTo(x, py(last)); path.lineTo(x + w, py(last))
        } else {
            for (k in v.indices) {
                val px = x + w * k / (v.size - 1)
                if (k == 0) path.moveTo(px, py(v[k])) else path.lineTo(px, py(v[k]))
            }
        }
        sparkArea.set(path)
        sparkArea.lineTo(x + w, top + h); sparkArea.lineTo(x, top + h); sparkArea.close()
        fill.color = withA(col, 0.16f)
        c.drawPath(sparkArea, fill)
        if (!dim) { line(withA(col, 0.3f), 3f); c.drawPath(path, stroke) }
        line(col, 1.2f); c.drawPath(path, stroke)
        fill.color = if (dim) col else Color.WHITE
        c.drawCircle(x + w, py(last), 1.8f, fill)
    }

    private val sparkArea = Path()

    private fun memRow(c: Canvas, lab: String, used: Long, total: Long, x: Float, end: Float, y: Float) {
        text.typeface = labelFace; text.textSize = 10f; text.letterSpacing = 0.18f; text.color = label
        c.drawText(lab, x, y, text)
        val v = if (total > 0) "${gb(used)}/${gb(total)}" else "—"
        text.typeface = numFace; text.textSize = 15f; text.letterSpacing = 0f; text.color = accent
        val vw = text.measureText(v)
        text.setShadowLayer(8f, 0f, 0f, withA(accent, 0.6f))
        c.drawText(v, end - vw, y, text)
        text.clearShadowLayer()
        val bx = x + 44f
        val bw = max(20f, end - vw - 10f - bx)
        fill.shader = null
        fill.color = withA(accent, 0.14f)
        c.drawRect(bx, y - 6f, bx + bw, y - 2f, fill)
        val frac = if (total > 0) (used.toFloat() / total).coerceIn(0f, 1f) else 0f
        fill.color = accent
        fill.setShadowLayer(8f, 0f, 0f, withA(accent, 0.75f))
        c.drawRect(bx, y - 6f, bx + bw * frac, y - 2f, fill)
        fill.clearShadowLayer()
    }

    private fun netRow(c: Canvas, arrow: String, now: Float, hist: FloatArray, count: Int, x: Float, end: Float, y: Float) {
        text.typeface = labelFace; text.letterSpacing = 0f
        text.textSize = 14f; text.color = label
        c.drawText(arrow, x, y, text)
        val v = fmtSpeed(now)
        text.typeface = numFace; text.textSize = 22f; text.color = accent
        text.setShadowLayer(10f, 0f, 0f, withA(accent, 0.6f))
        c.drawText(v, x + 14f, y, text)
        text.clearShadowLayer()

        // график
        val gx0 = x + 92f
        val gw = end - gx0
        if (gw < 20f) return
        val top = y - 26f
        val hgt = 32f
        val n = hist.size
        var peak = 0.5f
        for (k in n - count until n) if (k >= 0) peak = max(peak, hist[k])
        peak *= 1.15f
        path.reset()
        val first = max(0, n - max(count, 2))
        for (k in first until n) {
            val px = gx0 + gw * (k - first) / max(1, n - 1 - first)
            val py = top + hgt - (hist[k] / peak) * (hgt - 3f)
            if (k == first) path.moveTo(px, py) else path.lineTo(px, py)
        }
        val lastY = top + hgt - (hist[n - 1] / peak) * (hgt - 3f)
        // заливка
        val area = Path(path)
        area.lineTo(gx0 + gw, top + hgt); area.lineTo(gx0, top + hgt); area.close()
        fill.shader = null
        fill.color = withA(accent, 0.18f)
        c.drawPath(area, fill)
        line(withA(accent, 0.3f), 4f); c.drawPath(path, stroke)
        line(accent, 1.4f); c.drawPath(path, stroke)
        fill.color = Color.WHITE
        c.drawCircle(gx0 + gw, lastY, 2.2f, fill)
        fill.color = withA(accent, 0.15f)
        c.drawRect(gx0, top + hgt, gx0 + gw, top + hgt + 1f, fill)
    }

    // ================= бегущая строка =================

    private var tickerKey = ""
    private var tickerMsg = ""
    private val tickerRuns = ArrayList<Run>()
    private var tickerRunsFor: String? = null
    private var tickerRunsSize = -1f
    private var tickerWidth = 0f

    private fun drawTicker(c: Canvas, f: FrameData) {
        val newsOn = f.cfg.news && f.news.isNotEmpty()
        if (f.cfg.url.isBlank() && !newsOn) return
        val top = 240f; val bottom = 274f
        fill.shader = null
        fill.color = withA(accent, 0.32f)
        c.drawRect(48f, top, uiR, top + 1f, fill)
        c.drawRect(48f, bottom - 1f, uiR, bottom, fill)

        // строку собираем только при изменении данных — не каждый кадр
        val key = "${System.identityHashCode(f.remote)}|${f.remoteError}|${System.identityHashCode(f.news)}|${f.cfg.url.isBlank()}|$newsOn"
        if (key != tickerKey) {
            tickerKey = key
            val parts = ArrayList<String>()
            if (f.cfg.url.isNotBlank()) {
                val items = f.remote?.items.orEmpty()
                parts += when {
                    f.remoteError != null && items.isEmpty() -> "ИСТОЧНИК ▸ ОШИБКА: ${f.remoteError}"
                    items.isEmpty() -> "ИСТОЧНИК ▸ ЗАГРУЗКА…"
                    else -> (f.remote?.title?.let { "${it.uppercase()} ▸ " } ?: "ИСТОЧНИК ▸ ") +
                        items.joinToString("   //   ") { "${it.label.uppercase()}: ${it.value}" + if (it.state == State.BAD || it.state == State.WARN) " [!]" else "" }
                }
            }
            if (newsOn) {
                parts += "НОВОСТИ ▸ " + f.news.joinToString("   //   ") { h ->
                    if (h.source.isNullOrBlank()) h.title else "${h.title} · ${h.source}"
                }
            }
            tickerMsg = parts.joinToString("   //   ") + "   //   "
        }
        if (tickerRunsFor !== tickerMsg) {
            runs(tickerMsg, tickerRuns.also { it.clear() })
            tickerRunsFor = tickerMsg
            tickerRunsSize = -1f
        }
        text.typeface = labelFace; text.textSize = 15f; text.letterSpacing = 0.06f; text.color = accent
        if (tickerRunsSize != text.textSize) {
            var total = 0f
            for (r in tickerRuns) {
                text.typeface = if (r.num) numFace else labelFace
                r.width = text.measureText(r.text)
                total += r.width
            }
            tickerWidth = total
            tickerRunsSize = text.textSize
        }
        val tw = tickerWidth
        if (tw <= 0f) return
        c.save()
        c.clipRect(48f, top + 1f, uiR, bottom - 1f)
        text.setShadowLayer(8f, 0f, 0f, withA(accent, 0.6f))
        var x = 48f - (f.t * 40f) % tw
        while (x < uiR) {
            var rx = x
            for (r in tickerRuns) {
                if (rx > uiR) break
                if (rx + r.width >= 48f) {
                    text.typeface = if (r.num) numFace else labelFace
                    c.drawText(r.text, rx, top + 22f, text)
                }
                rx += r.width
            }
            x += tw
        }
        text.clearShadowLayer()
        c.restore()
    }

    // ================= погода и календарь =================

    private fun panel(c: Canvas, l: Float, t: Float, r: Float, b: Float) {
        fill.shader = null
        fill.color = Color.argb(184, 0, 10, 14)
        c.drawRect(l, t, r, b, fill)
        line(withA(accent, 0.12f), 8f); c.drawRect(l, t, r, b, stroke)
        line(withA(accent, 0.32f), 1f); c.drawRect(l, t, r, b, stroke)
    }

    private fun drawWeatherAndCalendar(c: Canvas, f: FrameData) {
        val top = 304f; val bottom = 466f
        // в один ряд: погода · календарь · конвертер (столбик валют по высоте панелей)
        val convW = if (f.cfg.converter) 168f + 10f else 0f
        val cr = uiR - convW
        val wl = cr - 446f
        // пока вводится сумма — на месте погоды и календаря клавиатура (ниже панелей, до ряда приложений)
        if (f.fx != null && f.cfg.converter) {
            calendarOnScreen.setEmpty()
            drawPad(c, f, f.fx, wl, top, cr, bottom)   // ровно на месте погоды и календаря
        } else {
            padOnScreen.setEmpty()
            if (f.cfg.weather) drawWeather(c, f, wl, top, wl + 268f, bottom)
            drawCalendar(c, wl + 278f, top, cr, bottom)
            toScreen(calendarOnScreen, wl + 278f, top, cr, bottom, f.cfg.uiScale)
        }
        if (f.cfg.converter) drawRates(c, f, uiR - 168f, top, uiR, bottom) else converterOnScreen.setEmpty()
    }

    // ================= Trending Games (SteamDB-стиль) =================

    private val steamRows = ArrayList<RectF>()
    private val steamIds = ArrayList<Int>()
    private val capPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private var capKey = 0
    private val capDst = RectF()

    /**
     * Таблица как на главной SteamDB: [обложка][Trending Games][Last 7 days][Players Now],
     * обложки — монохром в цвет акцента, чтобы не спорить со стилем.
     */
    private val steamNextRect = RectF()
    private var steamRight = 538f

    // ================= «Формула»: плоскость z = f(x, y, t) =================

    private val mathBtns = Array(MathPlane.FNS.size) { RectF() }
    private val mathEditRect = RectF()
    private var mathCur = -1; private var mathPrev = 0; private var mathAt = 0f
    private val mathBufs = Array(MATH_NB) { FloatArray(MATH_N * (MATH_N + 1) * 2 * 4) }
    private val mathCnt = IntArray(MATH_NB)
    private val mx = FloatArray((MATH_N + 1) * (MATH_N + 1))
    private val my = FloatArray((MATH_N + 1) * (MATH_N + 1))
    private val md = FloatArray((MATH_N + 1) * (MATH_N + 1))

    /**
     * Слева — сетка-плоскость, медленно вращается, высота z = f(x, y, t); снизу — сама формула.
     * Справа — 8 кнопок (2 × 4); смена формулы — перетекание за 0.8 с.
     */
    private fun drawMath(c: Canvas, f: FrameData, l: Float, t: Float, r: Float, b: Float) {
        panel(c, l, t, r, b)
        val me = f.mathEdit
        val fn = if (me != null && me.lastValid.isNotBlank()) MathPlane.CUSTOM else f.cfg.mathFn
        if (mathCur < 0) { mathCur = fn; mathPrev = fn }
        if (fn != mathCur) { mathPrev = mathCur; mathCur = fn; mathAt = f.t }
        val mk = ((f.t - mathAt) / 0.8f).coerceIn(0f, 1f).let { it * it * (3f - 2f * it) }

        // --- плоскость ---
        val pl = l + 6f; val pt = t + 6f; val pr = l + 166f; val pb = b - 20f
        val cx = (pl + pr) / 2f; val cy = (pt + pb) / 2f + 6f
        val sc = (pr - pl) / 2.9f
        val rot = f.t * 0.22f
        val ca = cos(rot); val sa = sin(rot)
        val el = 0.62f; val se = sin(el); val ce = cos(el)
        val n = MATH_N
        for (j in 0..n) for (i in 0..n) {
            val x = -1f + 2f * i / n; val y = -1f + 2f * j / n
            val src = if (me != null && me.lastValid.isNotBlank()) me.lastValid else f.cfg.mathCustom
            val z0 = MathPlane.z(mathPrev, x, y, f.t, src); val z1 = MathPlane.z(mathCur, x, y, f.t, src)
            val z = (z0 + (z1 - z0) * mk).coerceIn(-1.4f, 1.4f)
            val xr = x * ca - y * sa; val yr = x * sa + y * ca
            val k = j * (n + 1) + i
            mx[k] = cx + xr * sc
            my[k] = cy + yr * sc * se - z * sc * ce * 0.55f
            md[k] = (yr + 1.42f) / 2.84f          // 0 — дальний край, 1 — ближний
        }
        mathCnt.fill(0)
        fun seg(a: Int, q: Int) {
            val bb = min(MATH_NB - 1, (((md[a] + md[q]) * 0.5f) * MATH_NB).toInt().coerceAtLeast(0))
            val buf = mathBufs[bb]; var m = mathCnt[bb]
            buf[m++] = mx[a]; buf[m++] = my[a]; buf[m++] = mx[q]; buf[m++] = my[q]
            mathCnt[bb] = m
        }
        for (j in 0..n) for (i in 0 until n) { seg(j * (n + 1) + i, j * (n + 1) + i + 1); seg(i * (n + 1) + j, (i + 1) * (n + 1) + j) }
        c.save()
        c.clipRect(pl, pt, pr, pb)
        for (bb in 0 until MATH_NB) {
            if (mathCnt[bb] == 0) continue
            val a = 0.22f + 0.78f * (bb + 1f) / MATH_NB
            line(withA(accent, 0.18f * a), 3.2f); c.drawLines(mathBufs[bb], 0, mathCnt[bb], stroke)
            line(withA(accent, 0.9f * a), 1f); c.drawLines(mathBufs[bb], 0, mathCnt[bb], stroke)
        }
        c.restore()
        // формула под плоскостью: моноширинный, в нём есть ², θ, ₁
        text.typeface = Typeface.MONOSPACE; text.textSize = 8.5f; text.letterSpacing = 0f; text.color = label
        val fs = when {
            me != null -> "z = " + me.text.ifBlank { "…" }
            fn == MathPlane.CUSTOM -> "z = " + f.cfg.mathCustom.trim().removePrefix("z =").removePrefix("z=").trim()
            else -> MathPlane.FNS[fn].formula
        }
        val fw = text.measureText(fs)
        c.save()
        if (fw > pr - pl) c.scale((pr - pl) / fw, 1f, pl, b - 7f)
        c.drawText(fs, pl, b - 7f, text)
        c.restore()
        // тап по плоскости или формуле — ввести свою
        toScreen(mathEditRect, pl, pt, pr, b, f.cfg.uiScale)

        // --- кнопки 2 × 4 ---
        val bl = pr + 6f; val br = r - 6f; val bt = t + 6f; val bbm = b - 6f
        val gap = 4f
        val bw = (br - bl - gap) / 2f; val bh = (bbm - bt - gap * 3f) / 4f
        val k = f.cfg.uiScale
        for ((i, fnd) in MathPlane.FNS.withIndex()) {
            val col = i % 2; val row = i / 2
            val x0 = bl + col * (bw + gap); val y0 = bt + row * (bh + gap)
            val on = i == fn
            box(c, x0, y0, x0 + bw, y0 + bh, on)
            text.typeface = labelFace; text.textSize = 8f; text.letterSpacing = 0.08f
            text.color = if (on) accent else label
            if (on) text.setShadowLayer(6f, 0f, 0f, withA(accent, 0.7f))
            val tw = text.measureText(fnd.name)
            c.drawText(fnd.name, x0 + (bw - tw) / 2f, y0 + bh / 2f + 3f, text)
            text.clearShadowLayer(); text.letterSpacing = 0f
            toScreen(mathBtns[i], x0 - 1f, y0 - 1f, x0 + bw + 1f, y0 + bh + 1f, k)
        }
    }

    private fun drawSteam(c: Canvas, f: FrameData) {
        // компактно: обложка · название · сигнал · онлайн · пик · цена
        val l = 48f; val t = 304f
        val headH = 28f; val rowH = 26f; val gap = 2f
        val rows = f.steam.take(SteamTrends.ROWS)
        val b = t + headH + max(1, rows.size) * (rowH + gap) + 6f
        val imgW = 52f; val imgH = imgW * 87f / 231f
        val nameX = l + 8f + imgW + 8f
        val nameEnd = nameX + 200f           // название + маленькая плашка сигнала сразу после него
        val playersR = nameEnd + 76f         // онлайн сейчас (влезает 1,284,500) или дата выхода
        val peakR = playersR + 74f           // пик за 24 ч
        val priceR = peakR + 62f             // цена, $
        val r = priceR + 10f
        steamRight = r
        panel(c, l, t, r, b)
        // шапка: ⟳ (собрать список заново) · TRENDING
        text.typeface = labelFace; text.textSize = 10f; text.letterSpacing = 0.12f; text.color = accent
        c.drawText("TRENDING", nameX, t + 20f, text)
        text.letterSpacing = 0f
        // значок обновления в левом верхнем углу панели: дуга 290° по часовой (от 30° до 320°)
        // и залитый треугольный наконечник по касательной на её конце; пока идёт сбор — крутится
        val icx = l + 18f; val icy = t + 15f; val ir = 5.5f
        c.save()
        if (f.steamBusy) c.rotate((f.t * 300f) % 360f, icx, icy)
        rect.set(icx - ir, icy - ir, icx + ir, icy + ir)
        stroke.strokeCap = Paint.Cap.ROUND
        line(accent, 1.6f); c.drawArc(rect, 30f, 290f, false, stroke)
        stroke.strokeCap = Paint.Cap.BUTT
        val ae = Math.toRadians(320.0)
        val nx = cos(ae).toFloat(); val ny = sin(ae).toFloat()      // наружу от центра
        val dx = -ny; val dy = nx                                    // касательная по часовой
        val px = icx + ir * nx; val py = icy + ir * ny
        path.reset()
        path.moveTo(px + dx * 3.4f, py + dy * 3.4f)
        path.lineTo(px - dx * 1.2f + nx * 3f, py - dy * 1.2f + ny * 3f)
        path.lineTo(px - dx * 1.2f - nx * 3f, py - dy * 1.2f - ny * 3f)
        path.close()
        fill.shader = null; fill.color = accent; c.drawPath(path, fill)
        c.restore()
        val k0 = f.cfg.uiScale * screenScale
        steamNextRect.set(screenOx + (icx - 18f) * k0, (t - 2f) * k0, screenOx + (icx + 18f) * k0, (t + headH) * k0)
        text.typeface = labelFace; text.textSize = 8f; text.letterSpacing = 0.06f; text.color = label
        for ((h, x) in listOf("NOW" to playersR, "24H PEAK" to peakR, "PRICE" to priceR))
            c.drawText(h, x - text.measureText(h), t + 20f, text)
        text.letterSpacing = 0f
        fill.shader = null; fill.color = withA(accent, 0.32f)
        c.drawRect(l + 8f, t + headH - 2f, r - 8f, t + headH - 1f, fill)

        if (capKey != accent) {
            capKey = accent
            // монохром: яркость пикселя → оттенок акцента
            val ar = Color.red(accent) / 255f; val ag = Color.green(accent) / 255f; val ab = Color.blue(accent) / 255f
            // яркость L (0..1) → L' = (L − 0.06)·1.2, цвет = L'·акцент: чёрное остаётся чёрным, светлое — чистым акцентом
            val g = 1.2f; val o = -0.06f * 255f * g
            val m = android.graphics.ColorMatrix(floatArrayOf(
                0.299f * ar * g, 0.587f * ar * g, 0.114f * ar * g, 0f, o * ar,
                0.299f * ag * g, 0.587f * ag * g, 0.114f * ag * g, 0f, o * ag,
                0.299f * ab * g, 0.587f * ab * g, 0.114f * ab * g, 0f, o * ab,
                0f, 0f, 0f, 1f, 0f,
            ))
            capPaint.colorFilter = android.graphics.ColorMatrixColorFilter(m)
        }

        steamRows.clear(); steamIds.clear()
        val k = f.cfg.uiScale * screenScale
        if (rows.isEmpty()) {
            text.typeface = labelFace; text.textSize = 10f; text.color = label
            drawMixed(c, f.steamNote?.let { "Steam: $it" } ?: "загрузка…", nameX, t + headH + 22f)
            return
        }
        rows.forEachIndexed { i, g ->
            val y = t + headH + 4f + i * (rowH + gap)
            if (i % 2 == 0) { fill.color = withA(accent, 0.04f); c.drawRect(l + 6f, y, r - 6f, y + rowH, fill) }
            // обложка
            val iy = y + (rowH - imgH) / 2f
            capDst.set(l + 8f, iy, l + 8f + imgW, iy + imgH)
            val bmp = g.img
            if (bmp != null) c.drawBitmap(bmp, null, capDst, capPaint)
            else { fill.color = withA(accent, 0.1f); c.drawRect(capDst, fill) }
            line(withA(accent, 0.35f), 1f); c.drawRect(capDst, stroke)
            // название обрезается так, чтобы плашка сигнала влезла сразу после него
            val badgeW = signalWidth(g.signal)
            text.typeface = labelFace; text.textSize = 10f; text.letterSpacing = 0.02f; text.color = face
            var name = g.name
            val maxW = nameEnd - 10f - nameX - badgeW - 5f
            if (measureMixed(name) > maxW) {
                while (name.length > 1 && measureMixed("$name…") > maxW) name = name.dropLast(1)
                name = "$name…"
            }
            val base = y + rowH / 2f + 4f
            val nw = drawMixed(c, name, nameX, base)
            text.letterSpacing = 0f
            // сигнал: маленькая плашка сразу после названия
            drawSignal(c, g, nameX + nw + 5f, base - 8f, base + 2f)
            if (g.upcoming != null) {
                // не вышла: вместо онлайна — дата выхода, пика нет
                text.typeface = labelFace; text.textSize = 9f; text.letterSpacing = 0.06f; text.color = label
                drawMixed(c, g.upcoming, playersR - measureMixed(g.upcoming), base)
                text.letterSpacing = 0f
                text.typeface = numFace; text.textSize = 11f; text.color = withA(label, 0.5f)
                c.drawText("—", peakR - text.measureText("—"), base, text)
            } else {
                // онлайн
                val v = if (g.players >= 0) String.format(java.util.Locale.US, "%,d", g.players) else "—"
                text.typeface = numFace; text.textSize = 12f; text.color = accent
                text.setShadowLayer(6f, 0f, 0f, withA(accent, 0.6f))
                c.drawText(v, playersR - text.measureText(v), base, text)
                text.clearShadowLayer()
                // пик за 24 ч
                val pk = if (g.peak24 >= 0) String.format(java.util.Locale.US, "%,d", g.peak24) else "—"
                text.textSize = 11f; text.color = face
                c.drawText(pk, peakR - text.measureText(pk), base, text)
            }
            // цена
            val pr = g.price
            if (pr != null) {
                text.color = face
                text.textSize = 10f
                drawMixed(c, pr, priceR - measureMixed(pr), base)
            }
            steamRows += RectF(screenOx + l * k, y * k, screenOx + r * k, (y + rowH) * k)
            steamIds += g.appid
        }
    }

    /**
     * Плашка сигнала: NEW — заливка акцентом, ▲N — рамка, HOT — красная, WISH — белёсая рамка.
     * Цифры — шрифтом цифр, буквы — шрифтом подписей.
     */
    private fun signalWidth(s: String): Float {
        text.typeface = labelFace; text.textSize = 7f; text.letterSpacing = 0.08f
        val w = measureMixed(s) + 8f
        text.letterSpacing = 0f
        return w
    }

    private fun drawSignal(c: Canvas, g: TrendGame, l: Float, t: Float, b: Float) {
        val s = g.signal
        val w = signalWidth(s)
        text.typeface = labelFace; text.textSize = 7f; text.letterSpacing = 0.08f
        val r = l + w
        val col = when (g.kind) {
            TrendGame.K_HOT -> BAD_COLOR
            TrendGame.K_WISH -> mix(accent, Color.WHITE, 0.55f)
            else -> accent
        }
        fill.shader = null
        if (g.kind == TrendGame.K_NEW) {
            fill.color = col; fill.setShadowLayer(8f, 0f, 0f, withA(col, 0.8f)); c.drawRect(l, t, r, b, fill); fill.clearShadowLayer()
            text.color = Color.BLACK
        } else {
            fill.color = withA(col, 0.12f); c.drawRect(l, t, r, b, fill)
            line(withA(col, 0.85f), 1f); c.drawRect(l, t, r, b, stroke)
            text.color = col
        }
        drawMixed(c, s, l + 4f, (t + b) / 2f + 2.5f)
        text.letterSpacing = 0f
    }

    private fun drawWeather(c: Canvas, f: FrameData, l: Float, t: Float, r: Float, b: Float) {
        panel(c, l, t, r, b)
        val w = f.weather
        text.typeface = labelFace; text.letterSpacing = 0.25f; text.textSize = 10f; text.color = label
        drawMixed(c, f.city.ifBlank { "ПОГОДА" }.uppercase(), l + 12f, t + 20f)
        text.letterSpacing = 0f; text.textSize = 10f
        if (w == null) {
            drawMixed(c, f.weatherNote ?: "загрузка…", l + 12f, t + 36f)
            return
        }
        drawMixed(c, "${w.text} · ${w.wind.roundToInt()} м/с", l + 12f, t + 36f)
        val temp = signed(w.temp) + "°"
        text.typeface = numFace; text.textSize = 11f; text.color = accent
        text.setShadowLayer(8f, 0f, 0f, withA(accent, 0.6f))
        c.drawText(temp, r - 12f - text.measureText(temp), t + 20f, text)
        text.clearShadowLayer()

        val cellW = (r - l - 24f) / 7f
        val y0 = t + 52f
        w.days.take(7).forEachIndexed { i, d ->
            val cx0 = l + 12f + i * cellW
            if (i == 0) {
                fill.color = withA(accent, 0.1f); c.drawRect(cx0, y0, cx0 + cellW - 2f, b - 10f, fill)
                line(withA(accent, 0.45f), 1f); c.drawRect(cx0, y0, cx0 + cellW - 2f, b - 10f, stroke)
            }
            val mid = cx0 + (cellW - 2f) / 2f
            text.typeface = labelFace; text.textSize = 10f; text.letterSpacing = 0.08f; text.color = if (i == 0) accent else label
            centerMixed(c, d.dow, mid, y0 + 16f)
            drawWxIcon(c, d.kind, mid, y0 + 38f)
            text.typeface = numFace; text.letterSpacing = 0f; text.textSize = 11f; text.color = accent
            center(c, signed(d.max) + "°", mid, y0 + 70f)
            text.textSize = 11f; text.color = label
            center(c, signed(d.min) + "°", mid, y0 + 86f)
        }
    }

    /** Конвертер на экране: строки [валюта][сумма]; тап по строке — ввод суммы в этой валюте. */
    private fun drawRates(c: Canvas, f: FrameData, l: Float, t: Float, r: Float, b: Float) {
        val codes = RatesSource.parseCodes(f.cfg.currencies)
        fxRowCodes = codes
        if (codes.isEmpty()) { converterOnScreen.setEmpty(); return }
        val gap = 3f; val codeW = 38f
        // строки делят высоту панелей поровну: 7 валют — по ~20, меньше — выше, но не больше 28
        val rowH = min(28f, (b - t - gap * (codes.size - 1)) / codes.size)
        val ty = rowH / 2f + 4f   // базовая линия текста по центру строки
        val ui = f.cfg.uiScale
        val k = ui * screenScale
        val bottom = t + codes.size * (rowH + gap) - gap
        converterOnScreen.set(screenOx + l * k, t * k, screenOx + r * k, bottom * k)
        val rates = f.rates
        val fx = f.fx
        val amount = if (fx != null) fx.value() ?: 0.0 else f.cfg.fxAmount
        val from = fx?.code ?: f.cfg.fxFrom
        codes.forEachIndexed { i, code ->
            val y = t + i * (rowH + gap)
            fxRowRects[i].set(screenOx + l * k, y * k, screenOx + r * k, (y + rowH) * k)
            val active = code == from
            // окошко валюты
            box(c, l, y, l + codeW, y + rowH, false)
            text.typeface = labelFace; text.textSize = 10f; text.letterSpacing = 0.08f
            text.color = if (active) accent else label
            center(c, code, l + codeW / 2f, y + ty)
            // окошко суммы
            box(c, l + codeW + gap, y, r, y + rowH, active)
            val v = rates?.convert(amount, from, code)
            // в редактируемой строке — набираемое число с мигающим курсором
            val s = if (fx != null && code == fx.code) {
                val caret = if ((System.currentTimeMillis() / 500) % 2 == 0L) "_" else " "
                (if (fx.fresh) fmtMoney(amount) else fx.input.ifEmpty { "0" }) + caret
            } else if (v == null) "—" else fmtMoney(v)
            text.typeface = numFace; text.textSize = 11f; text.letterSpacing = 0f
            text.color = face
            if (active) text.setShadowLayer(6f, 0f, 0f, withA(accent, 0.7f))
            c.drawText(s, r - 6f - text.measureText(s), y + ty, text)
            text.clearShadowLayer()
        }
    }

    /** Клавиатура: цифры 4×4 или сетка валют. Клавиши записываются в экранных координатах для тапа. */
    private fun drawPad(c: Canvas, f: FrameData, fx: FxEdit, l: Float, t: Float, r: Float, b: Float) {
        val k = f.cfg.uiScale * screenScale
        padOnScreen.set(screenOx + l * k, t * k, screenOx + r * k, b * k)
        padPool.addAll(padKeys.map { it.second }); padKeys.clear()
        panel(c, l, t, r, b)
        val pad = 6f; val g = 4f
        val flashOn = fx.flashKey != null && System.currentTimeMillis() - fx.flashAt < 160
        curHave = RatesSource.parseCodes(f.cfg.currencies)
        val known = f.rates?.rates
        val grid: List<List<String>> = if (fx.currencyMode) {
            val codes = CurrencyPicker.POPULAR.filter { known == null || known.containsKey(it) }.take(20)
            codes.map { FxEdit.CUR_PREFIX + it }.chunked(5) + listOf(listOf(FxEdit.KEY_BACK))
        } else FxEdit.DIGIT_ROWS
        val rowsN = grid.size
        val kh = (b - t - pad * 2 - g * (rowsN - 1)) / rowsN
        grid.forEachIndexed { ri, row ->
            val kw = (r - l - pad * 2 - g * (row.size - 1)) / row.size
            row.forEachIndexed { ci, key ->
                val x0 = l + pad + ci * (kw + g)
                val y0 = t + pad + ri * (kh + g)
                val pressed = flashOn && key == fx.flashKey
                val strong = key == FxEdit.KEY_OK || (key.startsWith(FxEdit.CUR_PREFIX) && key.removePrefix(FxEdit.CUR_PREFIX) == fx.code)
                box(c, x0, y0, x0 + kw, y0 + kh, strong || pressed)
                if (pressed) { fill.color = withA(accent, 0.35f); c.drawRect(x0, y0, x0 + kw, y0 + kh, fill) }
                val lab = FxEdit.keyLabel(key)
                val isNum = lab.all { it.isDigit() || it == '.' }
                text.typeface = if (isNum) numFace else labelFace
                text.textSize = if (isNum) 18f else if (key.startsWith(FxEdit.CUR_PREFIX)) 11f else 9f
                text.letterSpacing = if (isNum) 0f else 0.15f
                val taken = key.startsWith(FxEdit.CUR_PREFIX) && key.removePrefix(FxEdit.CUR_PREFIX) in curHave
                text.color = when { pressed -> Color.WHITE; strong -> accent; taken -> withA(label, 0.5f); isNum -> face; else -> label }
                if (strong) text.setShadowLayer(8f, 0f, 0f, withA(accent, 0.7f))
                center(c, lab, x0 + kw / 2f, y0 + kh / 2f + text.textSize * 0.36f)
                text.clearShadowLayer(); text.letterSpacing = 0f
                val rr = if (padPool.isEmpty()) RectF() else padPool.removeAt(padPool.size - 1)
                rr.set(screenOx + x0 * k, y0 * k, screenOx + (x0 + kw) * k, (y0 + kh) * k)
                padKeys += key to rr
            }
        }
    }

    private var curHave: List<String> = emptyList()

    // ================= клавиатура формулы (на месте виджета игр) =================

    private val mathPadOnScreen = RectF()
    private val mathKeys = ArrayList<Pair<String, RectF>>()
    private val mathPool = ArrayList<RectF>()

    private fun drawMathPad(c: Canvas, f: FrameData, me: MathEdit, l: Float, t: Float, r: Float, b: Float) {
        val k = f.cfg.uiScale * screenScale
        mathPadOnScreen.set(screenOx + l * k, t * k, screenOx + r * k, b * k)
        mathPool.addAll(mathKeys.map { it.second }); mathKeys.clear()
        panel(c, l, t, r, b)
        val pad = 8f; val g = 4f

        // шапка: «z = …» с курсором и строка проверки
        text.typeface = labelFace; text.textSize = 9f; text.letterSpacing = 0.15f; text.color = label
        c.drawText("СВОЯ ФОРМУЛА", l + pad + 2f, t + 16f, text)
        text.letterSpacing = 0f
        val fieldT = t + 22f; val fieldB = t + 52f
        box(c, l + pad, fieldT, r - pad, fieldB, true)
        text.typeface = Typeface.MONOSPACE; text.textSize = 15f; text.color = face
        val head = "z = "
        val full = head + me.text
        val cx = head.length + me.cursor
        val maxW = r - l - pad * 2 - 16f
        var tw = text.measureText(full)
        // длинная формула — сдвигаем, чтобы курсор был виден
        var shift = 0f
        if (tw > maxW) {
            val curX = text.measureText(full, 0, cx)
            shift = (curX - maxW * 0.7f).coerceIn(0f, tw - maxW)
        }
        c.save()
        c.clipRect(l + pad + 4f, fieldT, r - pad - 4f, fieldB)
        val tx0 = l + pad + 8f - shift
        c.drawText(full, tx0, fieldB - 10f, text)
        // курсор мигает
        if ((System.currentTimeMillis() / 500) % 2 == 0L) {
            val curX = tx0 + text.measureText(full, 0, cx)
            fill.shader = null; fill.color = accent
            c.drawRect(curX, fieldT + 6f, curX + 1.6f, fieldB - 6f, fill)
        }
        c.restore()
        text.typeface = labelFace; text.textSize = 9f
        val err = me.error
        text.color = if (err == null) accent else Color.rgb(255, 120, 140)
        val status = when {
            me.text.isBlank() -> "x, y от −1 до 1 · t — время · r — расстояние · θ — угол"
            err == null -> "✓ на виджете справа"
            else -> "✗ $err"
        }
        drawMixed(c, status, l + pad + 2f, fieldB + 14f)

        // клавиши 8 × 7
        val kt = fieldB + 22f
        val rowsN = MathEdit.ROWS.size
        val kh = (b - pad - kt - g * (rowsN - 1)) / rowsN
        val flashOn = me.flashKey != null && System.currentTimeMillis() - me.flashAt < 160
        MathEdit.ROWS.forEachIndexed { ri, row ->
            val kw = (r - l - pad * 2 - g * (row.size - 1)) / row.size
            row.forEachIndexed { ci, key ->
                val x0 = l + pad + ci * (kw + g)
                val y0 = kt + ri * (kh + g)
                val pressed = flashOn && key == me.flashKey
                val strong = key == MathEdit.KEY_OK
                box(c, x0, y0, x0 + kw, y0 + kh, strong || pressed)
                if (pressed) { fill.color = withA(accent, 0.35f); c.drawRect(x0, y0, x0 + kw, y0 + kh, fill) }
                val digit = key.length == 1 && key[0].isDigit()
                when {
                    digit -> { text.typeface = numFace; text.textSize = 18f }
                    MathEdit.isCmd(key) -> { text.typeface = labelFace; text.textSize = 9f; text.letterSpacing = 0.15f }
                    MathEdit.isFunc(key) -> { text.typeface = Typeface.MONOSPACE; text.textSize = 13f }
                    else -> { text.typeface = Typeface.MONOSPACE; text.textSize = 17f }
                }
                text.color = when { pressed -> Color.WHITE; strong -> accent; digit -> face; MathEdit.isFunc(key) -> label; else -> face }
                if (strong) text.setShadowLayer(8f, 0f, 0f, withA(accent, 0.7f))
                val w = text.measureText(key)
                if (w > kw - 6f) text.textSize *= (kw - 6f) / w
                center(c, key, x0 + kw / 2f, y0 + kh / 2f + text.textSize * 0.36f)
                text.clearShadowLayer(); text.letterSpacing = 0f
                val rr = if (mathPool.isEmpty()) RectF() else mathPool.removeAt(mathPool.size - 1)
                rr.set(screenOx + x0 * k, y0 * k, screenOx + (x0 + kw) * k, (y0 + kh) * k)
                mathKeys += key to rr
            }
        }
    }

    private fun box(c: Canvas, l: Float, t: Float, r: Float, b: Float, active: Boolean) {
        fill.shader = null
        fill.color = Color.argb(217, 0, 10, 14)
        c.drawRect(l, t, r, b, fill)
        if (active) { line(withA(accent, 0.3f), 4f); c.drawRect(l, t, r, b, stroke) }
        line(withA(accent, if (active) 0.85f else 0.32f), 1f)
        c.drawRect(l, t, r, b, stroke)
    }

    private fun fmtMoney(v: Double): String {
        val a = kotlin.math.abs(v)
        val s = when {
            a >= 10_000 -> String.format(Locale.US, "%,.0f", v)
            a >= 1 -> String.format(Locale.US, "%,.2f", v)
            a == 0.0 -> "0"
            else -> String.format(Locale.US, "%.4f", v)
        }
        return s.replace(',', ' ')
    }

    private fun drawWxIcon(c: Canvas, kind: WxKind, cx: Float, cy: Float) {
        c.save()
        c.translate(cx - 11f, cy - 9.5f)
        c.scale(22f / 26f, 22f / 26f)
        line(accent, 1.6f)
        stroke.strokeCap = Paint.Cap.ROUND
        when (kind) {
            WxKind.SUN -> sun(c, 13f, 11f, 4.5f, 9.5f)
            WxKind.PART -> { sun(c, 8.5f, 8f, 3.2f, 6.5f); c.drawPath(smallCloud, stroke) }
            WxKind.CLOUD -> c.drawPath(cloudPath, stroke)
            WxKind.FOG -> for (k in 0 until 3) c.drawLine(4f, 8f + k * 5f, 22f, 8f + k * 5f, stroke)
            WxKind.RAIN -> {
                c.drawPath(highCloud, stroke)
                for (k in 0 until 3) c.drawLine(9f + k * 4f, 17f, 8f + k * 4f, 20.5f, stroke)
            }
            WxKind.SNOW -> {
                c.drawPath(highCloud, stroke)
                fill.color = accent
                for (k in 0 until 3) c.drawCircle(9f + k * 4f, if (k == 1) 20f else 18.5f, 1.3f, fill)
            }
            WxKind.STORM -> {
                c.drawPath(highCloud, stroke)
                path.reset(); path.moveTo(14f, 15f); path.lineTo(11f, 19f); path.lineTo(14f, 19f); path.lineTo(12f, 22f)
                c.drawPath(path, stroke)
            }
        }
        stroke.strokeCap = Paint.Cap.BUTT
        c.restore()
    }

    private fun sun(c: Canvas, cx: Float, cy: Float, r: Float, ray: Float) {
        c.drawCircle(cx, cy, r, stroke)
        for (k in 0 until 8) {
            val a = k * PI.toFloat() / 4f
            c.drawLine(cx + cos(a) * (r + 2f), cy + sin(a) * (r + 2f), cx + cos(a) * ray, cy + sin(a) * ray, stroke)
        }
    }

    private fun drawCalendar(c: Canvas, l: Float, t: Float, r: Float, b: Float) {
        panel(c, l, t, r, b)
        text.typeface = labelFace; text.textSize = 10f; text.letterSpacing = 0.25f
        text.color = accent
        c.drawText(monthTitle, l + 10f, t + 20f, text)
        text.color = label
        text.letterSpacing = 0.1f; text.textSize = 11f
        drawMixed(c, yearTitle, r - 10f - measureMixed(yearTitle), t + 20f)
        val cw = (r - l - 20f) / 7f
        text.typeface = labelFace; text.letterSpacing = 0f; text.textSize = 10f
        val wd = arrayOf("П", "В", "С", "Ч", "П", "С", "В")
        for (k in 0 until 7) center(c, wd[k], l + 10f + cw * k + cw / 2f, t + 36f)
        val rowH = 18f
        val gy0 = t + 42f
        text.typeface = numFace; text.textSize = 11f
        for (d in 1..calDays) {
            val idx = calStartCol + d - 1
            val col = idx % 7; val row = idx / 7
            val cx0 = l + 10f + cw * col
            val cy0 = gy0 + row * (rowH + 2f)
            val mid = cx0 + cw / 2f
            if (d == calToday) {
                fill.color = accent
                fill.setShadowLayer(10f, 0f, 0f, accent)
                c.drawRect(cx0 + 1f, cy0, cx0 + cw - 1f, cy0 + rowH, fill)
                fill.clearShadowLayer()
                text.color = Color.BLACK; text.isFakeBoldText = true
            } else {
                text.color = if (col >= 5) label else accent
            }
            center(c, d.toString(), mid, cy0 + 13f)
            text.isFakeBoldText = false
        }
    }

    // ================= утилиты =================

    /** Цифра или «цифровой» знак рядом с цифрой (12:30, 3.5, 71%, +6°, 8/11). */
    private fun isNumChar(s: String, i: Int): Boolean {
        val ch = s[i]
        if (ch in '0'..'9') return true
        if (NUM_PUNCT.indexOf(ch) < 0) return false
        val prev = if (i > 0) s[i - 1] else ' '
        val next = if (i + 1 < s.length) s[i + 1] else ' '
        return prev in '0'..'9' || next in '0'..'9'
    }

    /** Разбивает строку на отрезки «цифры / буквы»; пробелы прилипают к текущему отрезку. */
    private fun runs(s: String, out: ArrayList<Run>) {
        out.clear()
        var i = 0
        while (i < s.length) {
            val num = isNumChar(s, i)
            var j = i + 1
            while (j < s.length && (s[j] == ' ' || isNumChar(s, j) == num)) j++
            out += Run(s.substring(i, j), num)
            i = j
        }
    }

    private class Run(val text: String, val num: Boolean) { var width = 0f }

    private val tmpRuns = ArrayList<Run>()

    /** Рисует строку: цифры — шрифтом цифр, всё остальное — шрифтом подписей. Возвращает ширину. */
    private fun drawMixed(c: Canvas?, s: String, x: Float, y: Float): Float {
        runs(s, tmpRuns)
        var cx = x
        for (r in tmpRuns) {
            text.typeface = if (r.num) numFace else labelFace
            c?.drawText(r.text, cx, y, text)
            cx += text.measureText(r.text)
        }
        return cx - x
    }

    private fun measureMixed(s: String) = drawMixed(null, s, 0f, 0f)

    private fun centerMixed(c: Canvas, s: String, cx: Float, y: Float) {
        drawMixed(c, s, cx - measureMixed(s) / 2f, y)
    }

    private fun caption(c: Canvas, s: String, x: Float, y: Float) {
        text.typeface = labelFace; text.textSize = 11f; text.letterSpacing = 0.25f; text.color = label
        c.drawText(s, x, y, text)
        text.letterSpacing = 0f
    }

    private fun value(c: Canvas, s: String, x: Float, y: Float, size: Float) {
        text.typeface = numFace; text.textSize = size; text.letterSpacing = 0f; text.color = accent
        text.setShadowLayer(12f, 0f, 0f, withA(accent, 0.65f))
        c.drawText(s, x, y, text)
        text.clearShadowLayer()
    }

    private fun center(c: Canvas, s: String, cx: Float, y: Float) {
        c.drawText(s, cx - text.measureText(s) / 2f, y, text)
    }

    private fun line(color: Int, width: Float) {
        stroke.shader = null
        stroke.color = color
        stroke.strokeWidth = width
    }

    /** Короткие ГБ для серверов: до 10 — с десятой, дальше целые. */
    private fun gbShort(b: Long): String {
        val v = b / 1_073_741_824.0
        return if (v < 10) String.format(Locale.US, "%.1f", v) else String.format(Locale.US, "%.0f", v)
    }

    private fun gb(b: Long): String {
        val v = b / 1_073_741_824.0
        return if (v >= 100) String.format(Locale.US, "%.0f", v) else String.format(Locale.US, "%.1f", v)
    }

    private fun fmtSpeed(v: Float) =
        if (v >= 100f) String.format(Locale.US, "%.0f", v) else String.format(Locale.US, "%.1f", v)

    private fun signed(v: Int) = if (v > 0) "+$v" else v.toString()

    companion object {
        private const val W = 1280f
        private val BAD_COLOR = Color.rgb(255, 77, 109)
        private const val GOAL_SEG = 16                       // сегментов в кольце цели   // сервер не отвечает / занято ≥ 90 %
        private const val H = 800f
        private const val CX = 640f
        private const val HZ = 519f
        private const val F = 600f
        private const val CAM = 0.5617f
        private const val MATH_N = 16          // клеток сетки «формулы» по стороне
        private const val MATH_NB = 5          // ступеней яркости по глубине
        private const val FOG_H = 90f
        private const val NUM_PUNCT = ".,:%°+−-/$"

        fun withA(c: Int, a: Float): Int =
            Color.argb((a.coerceIn(0f, 1f) * Color.alpha(c)).roundToInt(), Color.red(c), Color.green(c), Color.blue(c))

        fun mix(a: Int, b: Int, k: Float): Int = Color.rgb(
            (Color.red(a) + (Color.red(b) - Color.red(a)) * k).roundToInt(),
            (Color.green(a) + (Color.green(b) - Color.green(a)) * k).roundToInt(),
            (Color.blue(a) + (Color.blue(b) - Color.blue(a)) * k).roundToInt(),
        )

        fun shade(c: Int, k: Float): Int = Color.rgb(
            (Color.red(c) * k).roundToInt().coerceIn(0, 255),
            (Color.green(c) * k).roundToInt().coerceIn(0, 255),
            (Color.blue(c) * k).roundToInt().coerceIn(0, 255),
        )
    }
}
