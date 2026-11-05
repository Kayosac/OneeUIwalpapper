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
    fun padHit(x: Float, y: Float): String? {
        if (padOnScreen.isEmpty || !padOnScreen.contains(x, y)) return null
        for ((k, r) in padKeys) if (r.contains(x, y)) return k
        return ""
    }

    // зоны тапа «открыть приложение»: часы → Часы, календарь → Google Календарь
    private val clockOnScreen = RectF()
    private val calendarOnScreen = RectF()
    private val goalsOnScreen = RectF()

    /** "clock" / "calendar" / "goals" под точкой тапа или null. */
    fun appHit(x: Float, y: Float): String? = when {
        !clockOnScreen.isEmpty && clockOnScreen.contains(x, y) -> "clock"
        !calendarOnScreen.isEmpty && calendarOnScreen.contains(x, y) -> "calendar"
        !goalsOnScreen.isEmpty && goalsOnScreen.contains(x, y) -> "goals"
        else -> null
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
        val yawNow = (cfg.yawDeg + 9f * sin(t * 0.05f) + 3f * sin(t * 0.13f + 1f)).coerceIn(0f, 36f)
        val vp = CX - tan(Math.toRadians(yawNow.toDouble())).toFloat() * F

        screenScale = s
        screenOx = ox
        c.drawColor(Color.BLACK)
        c.save()
        c.translate(ox, 0f)
        c.scale(s, s)

        ensureShaders(dh, ext)
        drawScene(c, f, vp, yawNow, ext, dh)
        // интерфейс мельче сцены: слой рисуется в увеличенных координатах и сжимается
        val ui = cfg.uiScale
        c.save()
        c.scale(ui, ui)
        uiR = W / ui - 48f
        drawHud(c, f)
        drawTicker(c, f)
        drawWeatherAndCalendar(c, f)
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
        val roll = 4f * sin(t * 0.09f) + 1.5f * sin(t * 0.23f)      // крен: горизонт покачивается
        val pitch = 14f * sin(t * 0.07f)                           // тангаж: горизонт чуть ходит вверх-вниз

        c.save()
        c.rotate(roll, vp, HZ + pitch)
        c.translate(0f, pitch)

        // небо у горизонта
        c.drawRect(xl, 300f, xr, HZ, skyP)

        // рельеф: летим вперёд; яркость отрезков гаснет с глубиной, вдали — горы
        terrain.build(t, yaw, (W / 2f + ext) / F * 1.3f, CX, HZ, F, CAM)
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
        drawMetrics(c, f)
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

        // --- сеть: графики ---
        val bx = xB + 16f
        val bEnd = xB + netW
        caption(c, "СЕТЬ", bx, 68f)
        text.typeface = labelFace; text.textSize = 10f; text.letterSpacing = 0f; text.color = label
        c.drawText("Мбит/с", bEnd - text.measureText("Мбит/с"), 68f, text)
        netRow(c, "↓", st.rxNow, st.rxHist, st.histCount, bx, bEnd, 116f)
        netRow(c, "↑", st.txNow, st.txHist, st.histCount, bx, bEnd, 172f)

        // --- серверы ---
        for (i in 0 until nSrv) {
            val sx = bEnd + gap + i * (srvW + gap)
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

    private fun drawGoals(c: Canvas, goals: List<Goal>, x: Float, end: Float) {
        caption(c, "ЦЕЛИ", x, 68f)
        val cellW = (end - x) / 3f
        val r = 17f
        val now = today
        for (i in 0 until Goals.COUNT) {
            val g = goals.getOrNull(i) ?: Goal("", null, null)
            val cx = x + cellW * (i % 3 + 0.5f)
            val cy = if (i < 3) 101f else 160f
            stroke.strokeCap = Paint.Cap.ROUND
            if (g.empty) {
                line(withA(accent, 0.14f), 2f); c.drawCircle(cx, cy, r, stroke)
                text.typeface = labelFace; text.textSize = 12f; text.letterSpacing = 0f; text.color = withA(label, 0.5f)
                center(c, "+", cx, cy + 4.5f)
                stroke.strokeCap = Paint.Cap.BUTT
                continue
            }
            val left = g.daysLeft(now) ?: 0L
            val p = g.progress(now)
            val col = when { left < 0 -> withA(label, 0.6f); left <= 3 -> BAD_COLOR; else -> accent }
            line(withA(accent, 0.14f), 3f); c.drawCircle(cx, cy, r, stroke)
            rect.set(cx - r, cy - r, cx + r, cy + r)
            line(withA(col, 0.3f), 6f); c.drawArc(rect, -90f, 360f * p, false, stroke)
            line(col, 2.6f); c.drawArc(rect, -90f, 360f * p, false, stroke)
            stroke.strokeCap = Paint.Cap.BUTT
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
            centerMixed(c, name, cx, cy + r + 13f)
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
        private val BAD_COLOR = Color.rgb(255, 77, 109)   // сервер не отвечает / занято ≥ 90 %
        private const val H = 800f
        private const val CX = 640f
        private const val HZ = 519f
        private const val F = 600f
        private const val CAM = 0.5617f
        private const val FOG_H = 90f
        private const val NUM_PUNCT = ".,:%°+−-/"

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
