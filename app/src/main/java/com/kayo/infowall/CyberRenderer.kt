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
    val mesh: WireMesh?,
    val news: List<Headline> = emptyList(),
    val rates: Rates? = null,
)

/**
 * Концепт «4.3 · Циан»: рельеф из треугольников, полосатое солнце в точке схода,
 * вращающийся каркас, HUD с часами, метриками, погодой и календарём.
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

    // правый край интерфейса в координатах слоя (слой масштабируется на uiScale)
    private var uiR = 1232f

    // где на экране (в пикселях) лежит блок курсов — для тапа
    private val converterOnScreen = RectF()
    private var screenScale = 1f
    private var screenOx = 0f

    fun converterHit(x: Float, y: Float): Boolean = !converterOnScreen.isEmpty && converterOnScreen.contains(x, y)

    /** Циферблат, мелкие цифры (значения, пояса) и подписи (кириллица). */
    fun setFonts(clock: Typeface, num: Typeface, label: Typeface) {
        clockFace = clock
        numFace = num
        labelFace = label
        tickerRunsSize = -1f   // ширины отрезков бегущей строки зависят от шрифтов
    }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = mono }
    private val skyP = Paint()
    private val bloomP = Paint(Paint.ANTI_ALIAS_FLAG)
    private val sunP = Paint(Paint.ANTI_ALIAS_FLAG)
    private val fogP = Paint()
    private val bottomP = Paint()
    private val horizonP = Paint(Paint.ANTI_ALIAS_FLAG)
    private val wireP = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND }

    private val rect = RectF()
    private val path = Path()
    private val sunClip = Path()
    private val camera = Camera()
    private val matrix = Matrix()

    // кэш шейдеров по ключу (цвет, точка схода, высота)
    private var shaderKey = ""

    // ---------- рельеф ----------
    private val rows = 18
    private val cols = 66
    private val gx = Array(rows) { FloatArray(cols) }
    private val gy = Array(rows) { FloatArray(cols) }
    private val gok = Array(rows) { BooleanArray(cols) }
    private val rowLines = FloatArray(rows * (cols - 1) * 4)
    private val colLines = FloatArray(cols * (rows - 1) * 4)
    private val diagLines = FloatArray((rows - 1) * (cols - 1) * 4)

    // ---------- модель ----------
    private var frontLines = FloatArray(0)
    private var backLines = FloatArray(0)
    private var silLines = FloatArray(0)
    private var front = BooleanArray(0)
    private var px = FloatArray(0)
    private var py = FloatArray(0)

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
        val vp = CX - tan(Math.toRadians(cfg.yawDeg.toDouble())).toFloat() * F

        screenScale = s
        screenOx = ox
        c.drawColor(Color.BLACK)
        c.save()
        c.translate(ox, 0f)
        c.scale(s, s)

        ensureShaders(vp, dh, ext)
        drawScene(c, f, vp, ext, dh)
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

    private fun ensureShaders(vp: Float, dh: Float, ext: Float) {
        val key = "$accent|${vp.roundToInt()}|${dh.roundToInt()}|${ext.roundToInt()}"
        if (key == shaderKey) return
        shaderKey = key
        skyP.shader = LinearGradient(0f, 300f, 0f, HZ, Color.TRANSPARENT, withA(accent, 0.14f), Shader.TileMode.CLAMP)
        bloomP.shader = RadialGradient(
            vp, HZ, 330f,
            intArrayOf(withA(accent, 0.42f), withA(accent, 0.14f), Color.TRANSPARENT),
            floatArrayOf(0f, 0.45f, 1f), Shader.TileMode.CLAMP,
        )
        sunP.shader = LinearGradient(
            0f, HZ - SUN_R, 0f, HZ,
            intArrayOf(Color.WHITE, accent, withA(accent, 0.1f)),
            floatArrayOf(0f, 0.3f, 1f), Shader.TileMode.CLAMP,
        )
        fogP.shader = LinearGradient(
            0f, HZ, 0f, HZ + FOG_H,
            intArrayOf(withA(Color.BLACK, 0.6f), withA(Color.BLACK, 0.22f), Color.TRANSPARENT),
            floatArrayOf(0f, 0.3f, 1f), Shader.TileMode.CLAMP,
        )
        val top = 548f
        val span = max(1f, dh - top)
        bottomP.shader = LinearGradient(
            0f, top, 0f, dh,
            intArrayOf(Color.TRANSPARENT, withA(Color.BLACK, 0.28f), withA(Color.BLACK, 0.5f), withA(Color.BLACK, 0.66f)),
            floatArrayOf(0f, 80f / span, 180f / span, 1f).map { it.coerceIn(0f, 1f) }.toFloatArray(),
            Shader.TileMode.CLAMP,
        )
        val xl = -ext - 200f
        val xr = W + ext + 200f
        val vpf = ((vp - xl) / (xr - xl)).coerceIn(0.05f, 0.9f)
        horizonP.shader = LinearGradient(
            xl, 0f, xr, 0f,
            intArrayOf(withA(accent, 0.4f), Color.WHITE, accent, withA(accent, 0.4f)),
            floatArrayOf(0f, vpf, (vpf + 0.12f).coerceAtMost(0.98f), 1f), Shader.TileMode.CLAMP,
        )
        sunClip.reset()
        sunClip.addArc(RectF(vp - SUN_R, HZ - SUN_R, vp + SUN_R, HZ + SUN_R), 180f, 180f)
        sunClip.close()
    }

    // ================= сцена =================

    private fun drawScene(c: Canvas, f: FrameData, vp: Float, ext: Float, dh: Float) {
        val t = f.t
        val xl = -ext - 200f
        val xr = W + ext + 200f

        // небо у горизонта
        c.drawRect(xl, 300f, xr, HZ, skyP)

        // свечение за солнцем
        bloomP.alpha = (200 + 55 * sin(t * 0.9f)).roundToInt().coerceIn(0, 255)
        c.drawCircle(vp, HZ, 330f, bloomP)

        // полосатое солнце, полосы ползут вниз
        c.save()
        c.clipPath(sunClip)
        sunP.alpha = 210
        val off = (t * 5.6f) % 28f
        var y = HZ - SUN_R - 28f + off
        while (y < HZ) {
            c.drawRect(vp - SUN_R, max(y, HZ - SUN_R), vp + SUN_R, min(y + 20f, HZ), sunP)
            y += 28f
        }
        c.restore()

        // рельеф
        buildTerrain(t, f.cfg.yawDeg)
        var nr = 0; var nc = 0; var nd = 0
        for (r in 0 until rows) for (k in 0 until cols - 1) {
            if (gok[r][k] && gok[r][k + 1]) {
                rowLines[nr++] = gx[r][k]; rowLines[nr++] = gy[r][k]; rowLines[nr++] = gx[r][k + 1]; rowLines[nr++] = gy[r][k + 1]
            }
        }
        for (k in 0 until cols) for (r in 0 until rows - 1) {
            if (gok[r][k] && gok[r + 1][k]) {
                colLines[nc++] = gx[r][k]; colLines[nc++] = gy[r][k]; colLines[nc++] = gx[r + 1][k]; colLines[nc++] = gy[r + 1][k]
            }
        }
        for (r in 0 until rows - 1) for (k in 0 until cols - 1) {
            if (gok[r][k] && gok[r + 1][k + 1]) {
                diagLines[nd++] = gx[r][k]; diagLines[nd++] = gy[r][k]; diagLines[nd++] = gx[r + 1][k + 1]; diagLines[nd++] = gy[r + 1][k + 1]
            }
        }
        line(withA(accent, 0.34f), 1f); c.drawLines(diagLines, 0, nd, stroke)
        line(withA(accent, 0.58f), 1.1f); c.drawLines(colLines, 0, nc, stroke)
        line(withA(accent, 0.2f), 4.5f); c.drawLines(rowLines, 0, nr, stroke)
        line(withA(accent, 0.92f), 1.4f); c.drawLines(rowLines, 0, nr, stroke)

        // дымка у горизонта прячет мельтешение дальних треугольников
        c.drawRect(xl, HZ, xr, HZ + FOG_H, fogP)

        // горизонт: три слоя свечения
        val pulse = 0.8f + 0.2f * sin(t * 1.05f)
        fill.shader = null
        fill.color = withA(accent, 0.10f * pulse); c.drawRect(xl, HZ - 13f, xr, HZ + 13f, fill)
        fill.color = withA(accent, 0.28f * pulse); c.drawRect(xl, HZ - 5f, xr, HZ + 5f, fill)
        horizonP.alpha = (255 * pulse).roundToInt().coerceIn(0, 255)
        c.drawRect(xl, HZ - 1.5f, xr, HZ + 1.5f, horizonP)

        // каркасная модель в центре солнца
        f.mesh?.let { drawMesh(c, it, vp, 446f, t * 0.55f * f.cfg.modelSpeed + 0.6f) }

        // частицы поднимаются из точки схода
        for (p in 0 until 12) {
            val dur = 5f + (p % 4) * 1.3f
            val ph = ((t + p * 0.8f) / dur) % 1f
            val a = if (ph < 0.15f) ph / 0.15f else 1f - (ph - 0.15f) / 0.85f
            val x = vp + PARTICLE_X[p]
            val yy = 504f - (p % 3) * 6f - ph * 240f
            fill.color = withA(accent, 0.35f * a); c.drawRect(x - 3f, yy - 3f, x + 6f, yy + 6f, fill)
            fill.color = withA(Color.WHITE, a); c.drawRect(x, yy, x + 3f, yy + 3f, fill)
        }

        // затемнение снизу — спокойный фон под иконками
        c.drawRect(xl, 548f, xr, dh, bottomP)
    }

    private fun buildTerrain(t: Float, yawDeg: Float) {
        val a = Math.toRadians(yawDeg.toDouble())
        val ca = cos(a).toFloat(); val sa = sin(a).toFloat()
        val zN = 0.9f; val zF = 26f
        for (r in 0 until rows) {
            val z = zN * (zF / zN).pow(r / (rows - 1f))
            val amp = 0.25f + 0.08f * z
            for (k in 0 until cols) {
                val x = -26f + k * 0.8f
                val hgt = 0.18f * sin(0.55f * x + 0.35f * z + 0.25f * t) +
                    0.14f * sin(0.9f * z - 0.75f * x - 0.18f * t) +
                    0.08f * sin(1.6f * x + 1.1f * z + 0.33f * t)
                val yv = hgt * amp
                val xc = x * ca - z * sa
                val zc = x * sa + z * ca
                if (zc < 0.7f) { gok[r][k] = false; continue }
                val sx = CX + xc * F / zc
                val sy = HZ + (CAM - yv) * F / zc
                gok[r][k] = sx > -900f && sx < 2200f && sy < 1500f
                gx[r][k] = sx; gy[r][k] = sy
            }
        }
    }

    private fun drawMesh(c: Canvas, m: WireMesh, cx: Float, cy: Float, theta: Float) {
        val n = m.vertexCount
        if (px.size < n) { px = FloatArray(n); py = FloatArray(n) }
        val nf = m.faceCount
        if (front.size < nf) front = BooleanArray(nf)
        val need = m.edgeCount * 4
        if (frontLines.size < need) { frontLines = FloatArray(need); backLines = FloatArray(need); silLines = FloatArray(need) }
        val ct = cos(theta); val st = sin(theta)
        val al = -0.32f; val ca = cos(al); val sa = sin(al)
        val d = 7f; val sc = 560f
        val v = m.verts
        for (i in 0 until n) {
            val x = v[i * 3]; val y = v[i * 3 + 1]; val z = v[i * 3 + 2]
            val x1 = x * ct + z * st
            val z1 = -x * st + z * ct
            val y2 = y * ca - z1 * sa
            val z2 = y * sa + z1 * ca
            val k = sc / (d - z2)
            px[i] = cx + x1 * k
            py[i] = cy - y2 * k
        }
        // какие грани смотрят на камеру
        val nm = m.normals
        for (f in 0 until nf) {
            val nx = nm[f * 3]; val ny = nm[f * 3 + 1]; val nz = nm[f * 3 + 2]
            val z1 = -nx * st + nz * ct
            front[f] = ny * sa + z1 * ca > 0f
        }
        var nfr = 0; var nb = 0; var ns = 0
        val e = m.edges
        val ef = m.edgeFaces
        for (k in 0 until m.edgeCount) {
            val a = e[k * 2]; val b = e[k * 2 + 1]
            val fa = front[ef[k * 2]]
            val f2 = ef[k * 2 + 1]
            val fb = if (f2 >= 0) front[f2] else !fa
            when {
                fa != fb -> { silLines[ns++] = px[a]; silLines[ns++] = py[a]; silLines[ns++] = px[b]; silLines[ns++] = py[b] }
                fa -> { frontLines[nfr++] = px[a]; frontLines[nfr++] = py[a]; frontLines[nfr++] = px[b]; frontLines[nfr++] = py[b] }
                else -> { backLines[nb++] = px[a]; backLines[nb++] = py[a]; backLines[nb++] = px[b]; backLines[nb++] = py[b] }
            }
        }
        wireP.color = withA(accent, 0.2f); wireP.strokeWidth = 0.8f
        c.drawLines(backLines, 0, nb, wireP)
        wireP.color = withA(accent, 0.3f); wireP.strokeWidth = 3.5f
        c.drawLines(frontLines, 0, nfr, wireP)
        wireP.color = withA(mix(accent, Color.WHITE, 0.45f), 0.85f); wireP.strokeWidth = 1.1f
        c.drawLines(frontLines, 0, nfr, wireP)
        // контур — самый яркий
        wireP.color = withA(accent, 0.5f); wireP.strokeWidth = 7f
        c.drawLines(silLines, 0, ns, wireP)
        wireP.color = Color.rgb(220, 253, 255); wireP.strokeWidth = 2f
        c.drawLines(silLines, 0, ns, wireP)
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

        // мягкий поворот: правый край ближе к зрителю
        c.save()
        camera.save()
        camera.setLocation(0f, 0f, -14f)
        camera.rotateY(16f)
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
            text.textSize = 22f
            zx += drawMixed(c, lab, zx, zy) + 8f
            text.typeface = numFace; text.textSize = 24f
            text.color = accent
            text.setShadowLayer(10f, 0f, 0f, withA(accent, 0.65f))
            c.drawText(time, zx, zy, text)
            text.clearShadowLayer()
            zx += text.measureText(time) + 24f
        }
    }

    private fun drawMetrics(c: Canvas, f: FrameData) {
        val st = f.stats
        // две колонки справа от часов: [батарея + память] и [сеть]
        val x0 = 78f + 480f + 32f
        val x1 = uiR - 28f
        val gap = 24f
        val colA = (x1 - x0 - gap) / 2.12f
        val xB = x0 + colA + gap
        fill.shader = null
        fill.color = withA(accent, 0.32f)
        c.drawRect(x0, 50f, x0 + 1f, 198f, fill)
        c.drawRect(xB, 50f, xB + 1f, 198f, fill)

        // --- батарея ---
        val ax = x0 + 16f
        val aEnd = x0 + colA
        caption(c, "БАТАРЕЯ", ax, 68f)
        val pct = st.batteryPct
        val batText = buildString {
            append(if (pct >= 0) "$pct%" else "—")
            st.batteryTemp?.let { append(" $it°") }
            if (st.charging) append(" +")
        }
        text.typeface = numFace; text.textSize = 22f; text.letterSpacing = 0f; text.color = accent
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
        caption(c, "СЕТЬ", bx, 68f)
        netRow(c, "↓", st.rxNow, st.rxHist, st.histCount, bx, x1, 116f)
        netRow(c, "↑", st.txNow, st.txHist, st.histCount, bx, x1, 172f)
    }

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
        val vw = text.measureText(v)
        text.typeface = labelFace; text.textSize = 10f; text.color = label
        c.drawText("Мбит/с", x + 18f + vw, y, text)

        // график
        val gx0 = x + 138f
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
        val wl = uiR - 446f
        if (f.cfg.weather) drawWeather(c, f, wl, top, wl + 268f, bottom)
        drawCalendar(c, wl + 278f, top, uiR, bottom)
        if (f.cfg.converter) drawRates(c, f, wl, bottom + 10f, uiR, bottom + 84f) else converterOnScreen.setEmpty()
    }

    private fun drawWeather(c: Canvas, f: FrameData, l: Float, t: Float, r: Float, b: Float) {
        panel(c, l, t, r, b)
        val w = f.weather
        text.typeface = labelFace; text.letterSpacing = 0.25f; text.textSize = 10f; text.color = label
        drawMixed(c, f.city.ifBlank { "ПОГОДА" }.uppercase(), l + 12f, t + 20f)
        text.letterSpacing = 0f; text.textSize = 11f
        if (w == null) {
            drawMixed(c, f.weatherNote ?: "загрузка…", l + 12f, t + 36f)
            return
        }
        drawMixed(c, "${w.text} · ${w.wind.roundToInt()} м/с", l + 12f, t + 36f)
        val temp = signed(w.temp) + "°"
        text.typeface = numFace; text.textSize = 32f; text.color = accent
        text.setShadowLayer(12f, 0f, 0f, withA(accent, 0.6f))
        c.drawText(temp, r - 12f - text.measureText(temp), t + 38f, text)
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
            text.typeface = numFace; text.letterSpacing = 0f; text.textSize = 13f; text.color = accent
            center(c, signed(d.max) + "°", mid, y0 + 70f)
            text.textSize = 11f; text.color = label
            center(c, signed(d.min) + "°", mid, y0 + 86f)
        }
    }

    /** Курсы в первой валюте списка; тап по блоку открывает конвертер. */
    private fun drawRates(c: Canvas, f: FrameData, l: Float, t: Float, r: Float, b: Float) {
        panel(c, l, t, r, b)
        val ui = f.cfg.uiScale
        converterOnScreen.set(
            screenOx + l * ui * screenScale, t * ui * screenScale,
            screenOx + r * ui * screenScale, b * ui * screenScale,
        )
        val codes = RatesSource.parseCodes(f.cfg.currencies)
        val base = codes.firstOrNull() ?: "KZT"
        text.letterSpacing = 0.25f; text.textSize = 10f; text.color = label
        drawMixed(c, "КУРС В $base", l + 12f, t + 18f)
        val rates = f.rates
        text.letterSpacing = 0f
        val hint = if (rates == null) "загрузка…" else "тап — конвертер"
        drawMixed(c, hint, r - 12f - measureMixed(hint), t + 18f)
        if (rates == null) return
        val others = codes.drop(1)
        if (others.isEmpty()) return
        val cellW = (r - l - 24f) / others.size
        others.forEachIndexed { i, code ->
            val x = l + 12f + i * cellW
            text.textSize = 11f; text.color = label; text.letterSpacing = 0.1f
            drawMixed(c, code, x, t + 52f)
            val v = rates.convert(1.0, code, base)
            val s = when {
                v == null -> "—"
                v >= 100 -> String.format(Locale.US, "%.1f", v)
                v >= 1 -> String.format(Locale.US, "%.2f", v)
                else -> String.format(Locale.US, "%.4f", v)
            }
            text.textSize = 20f; text.color = accent; text.letterSpacing = 0f
            text.setShadowLayer(10f, 0f, 0f, withA(accent, 0.6f))
            drawMixed(c, s, x + 38f, t + 53f)
            text.clearShadowLayer()
        }
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
        text.letterSpacing = 0.1f
        drawMixed(c, yearTitle, r - 10f - measureMixed(yearTitle), t + 20f)
        val cw = (r - l - 20f) / 7f
        text.letterSpacing = 0f; text.textSize = 9f
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

    private fun gb(b: Long): String {
        val v = b / 1_073_741_824.0
        return if (v >= 100) String.format(Locale.US, "%.0f", v) else String.format(Locale.US, "%.1f", v)
    }

    private fun fmtSpeed(v: Float) =
        if (v >= 100f) String.format(Locale.US, "%.0f", v) else String.format(Locale.US, "%.1f", v)

    private fun signed(v: Int) = if (v > 0) "+$v" else v.toString()

    companion object {
        private const val W = 1280f
        private const val H = 800f
        private const val CX = 640f
        private const val HZ = 519f
        private const val F = 600f
        private const val CAM = 0.5617f
        private const val SUN_R = 175f
        private const val FOG_H = 90f
        private const val NUM_PUNCT = ".,:%°+−-/"
        private val PARTICLE_X = floatArrayOf(-73f, -47f, -25f, -7f, 7f, 21f, 39f, 61f, -35f, 31f, -53f, 15f)

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
