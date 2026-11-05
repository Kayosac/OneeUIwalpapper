package com.kayo.infowall

import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * Рельеф под летящей камерой. Сетка неподвижна в мире, камера летит вперёд:
 *  - вершины берутся только там, куда смотрит камера (плюс одна за краем кадра) — сетка не обрывается
 *    по бокам и у камеры, отрезки уходят за край экрана, а не исчезают;
 *  - яркость отрезка гаснет с глубиной — новые ряды рождаются невидимыми, без «выскакивания» у горизонта;
 *  - вдали поднимаются большие волны-горы, они ломают линию горизонта.
 * Результат — отрезки, разложенные по [NB] ступеням яркости (одна кисть на ступень).
 */
class Terrain {

    class Buf {
        var a = FloatArray(2048); var n = 0
        fun clear() { n = 0 }
        fun add(x0: Float, y0: Float, x1: Float, y1: Float) {
            if (n + 4 > a.size) a = a.copyOf(a.size * 2)
            a[n++] = x0; a[n++] = y0; a[n++] = x1; a[n++] = y1
        }
    }

    val rowsBuf = Array(NB) { Buf() }
    val colsBuf = Array(NB) { Buf() }
    val diagBuf = Array(NB) { Buf() }

    private val px = Array(ROWS) { FloatArray(MAXK) }
    private val py = Array(ROWS) { FloatArray(MAXK) }
    private val pf = Array(ROWS) { FloatArray(MAXK) }      // яркость вершины 0..1 (0 — не рисовать)
    private val kLo = IntArray(ROWS)
    private val kN = IntArray(ROWS)

    /**
     * [halfView] — половина ширины кадра в единицах F (|x/z| на краю экрана, с запасом на крен).
     * Экранные координаты — в макетной системе 1280×800, горизонт на [hz].
     */
    fun build(t: Float, yawDeg: Float, halfView: Float, cx: Float, hz: Float, f: Float, cam: Float) {
        for (b in 0 until NB) { rowsBuf[b].clear(); colsBuf[b].clear(); diagBuf[b].clear() }
        val a = Math.toRadians(yawDeg.toDouble())
        val ca = cos(a).toFloat(); val sa = sin(a).toFloat()
        val dz = (Z_FAR - Z_NEAR) / (ROWS - 1)
        val travel = t * 1.1f
        val shift = travel % dz
        val strafe = 3f * sin(t * 0.04f)
        val camH = cam + 0.12f * sin(t * 0.19f)
        val L = halfView

        for (r in 0 until ROWS) {
            val z = Z_NEAR + r * dz - shift
            val wz = z + travel
            // какие x этой строки попадают в кадр: |xc/zc| ≤ L при zc > 0
            val xL = z * (sa - L * ca) / (ca + L * sa)
            val den = ca - L * sa
            var xR = if (den > 0.02f) z * (L * ca + sa) / den else z * 40f
            // дальше глубины FADE_TO всё равно невидимо — не считаем
            if (sa > 0.01f) xR = min(xR, (FADE_TO + 2f - z * ca) / sa)
            val k0 = floor(min(xL, xR) / XSTEP).toInt() - 1
            val k1 = min(ceil(max(xL, xR) / XSTEP).toInt() + 1, k0 + MAXK - 1)
            kLo[r] = k0; kN[r] = max(0, k1 - k0 + 1)
            val amp = 0.32f + 0.07f * max(z, 0f)
            for (i in 0 until kN[r]) {
                val x = (k0 + i) * XSTEP
                val xc = x * ca - z * sa
                val zc = x * sa + z * ca
                if (zc < 0.05f) { pf[r][i] = 0f; continue }
                val wx = x + strafe
                // ближние волны
                val near = (0.26f * sin(0.45f * wx + 0.3f * wz + 0.55f * t) +
                    0.18f * sin(0.8f * wz - 0.6f * wx - 0.4f * t) +
                    0.09f * sin(1.5f * wx + 1.2f * wz + 0.9f * t)) * amp
                // дальние горы: крупные медленные волны, растут с глубиной
                val m = smooth(6f, 22f, zc)
                val big = 1.6f * sin(0.11f * wx + 0.06f * wz + 0.15f * t) +
                    0.9f * sin(0.21f * wx - 0.13f * wz + 0.3f * t + 1f) +
                    0.45f * sin(0.5f * wx + 0.37f * wz - 0.4f * t)
                val y = near + m * 0.72f * (big + 1f)
                px[r][i] = cx + xc * f / zc
                py[r][i] = hz + (camH - y) * f / zc
                pf[r][i] = 1f - smooth(FADE_FROM, FADE_TO, zc)
            }
        }

        for (r in 0 until ROWS) {
            val n = kN[r]
            for (i in 0 until n - 1) seg(rowsBuf, r, i, r, i + 1)
            if (r == ROWS - 1) continue
            val off = kLo[r] - kLo[r + 1]          // индекс того же k в следующем ряду
            for (i in 0 until n) {
                val j = i + off
                if (j in 0 until kN[r + 1]) seg(colsBuf, r, i, r + 1, j)
                if (j + 1 in 0 until kN[r + 1]) seg(diagBuf, r, i, r + 1, j + 1)
            }
        }
    }

    private fun seg(out: Array<Buf>, r0: Int, i0: Int, r1: Int, i1: Int) {
        val f = min(pf[r0][i0], pf[r1][i1])
        if (f <= 0.02f) return
        val b = min(NB - 1, (f * NB).toInt())
        out[b].add(px[r0][i0], py[r0][i0], px[r1][i1], py[r1][i1])
    }

    private fun smooth(e0: Float, e1: Float, x: Float): Float {
        val k = ((x - e0) / (e1 - e0)).coerceIn(0f, 1f)
        return k * k * (3f - 2f * k)
    }

    companion object {
        const val NB = 10                 // ступеней яркости
        const val ROWS = 44
        const val MAXK = 900
        const val XSTEP = 0.65f
        const val Z_NEAR = 0.9f
        const val Z_FAR = 40f
        const val FADE_FROM = 13f
        const val FADE_TO = 34f
    }
}
