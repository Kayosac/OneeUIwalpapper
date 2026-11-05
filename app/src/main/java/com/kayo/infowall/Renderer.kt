package com.kayo.infowall

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.text.TextPaint
import android.text.TextUtils
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.max
import kotlin.math.min

/** Рисует один кадр обоев. Все размеры — в долях от короткой стороны экрана. */
class Renderer {

    private val bg = Paint()
    private var bgW = 0
    private var bgH = 0

    private val light = Typeface.create("sans-serif-light", Typeface.NORMAL)
    private val medium = Typeface.create("sans-serif-medium", Typeface.NORMAL)

    private val clockP = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; typeface = light }
    private val dateP = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF9AA4B2.toInt() }
    private val sectionP = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFCDD5E0.toInt(); typeface = medium }
    private val smallP = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF6B7585.toInt() }
    private val cardP = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x1AFFFFFF }
    private val labelP = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF9AA4B2.toInt() }
    private val valueP = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; typeface = medium }
    private val dotP = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rect = RectF()

    private val clockFmt = SimpleDateFormat("HH:mm", Locale.getDefault())
    private val dateFmt = SimpleDateFormat("EEEE, d MMMM", Locale.getDefault())

    fun draw(
        c: Canvas,
        w: Int,
        h: Int,
        cfg: Config,
        sys: List<Item>,
        remote: RemoteData?,
        remoteError: String?,
        remoteOkAt: Long,
    ) {
        if (bgW != w || bgH != h) {
            bg.shader = LinearGradient(
                0f, 0f, w.toFloat(), h.toFloat(),
                0xFF0E1116.toInt(), 0xFF1C2433.toInt(), Shader.TileMode.CLAMP,
            )
            bgW = w; bgH = h
        }
        c.drawRect(0f, 0f, w.toFloat(), h.toFloat(), bg)

        val s = min(w, h) / 1000f
        val left = w * 0.06f
        val right = w * 0.94f
        var y = h * cfg.topPct / 100f
        val now = Date()

        if (cfg.showClock) {
            clockP.textSize = 150 * s
            y += -clockP.ascent()
            c.drawText(clockFmt.format(now), left, y, clockP)

            dateP.textSize = 34 * s
            y += clockP.descent() + 6 * s - dateP.ascent()
            val date = dateFmt.format(now).replaceFirstChar { it.titlecase(Locale.getDefault()) }
            c.drawText(date, left, y, dateP)
            y += dateP.descent() + 36 * s
        }

        if (sys.isNotEmpty()) {
            y = drawCards(c, sys, left, y, right, s) + 30 * s
        }

        if (cfg.url.isNotBlank()) {
            sectionP.textSize = 26 * s
            smallP.textSize = 20 * s
            val title = remote?.title ?: "Источник"
            y += -sectionP.ascent()
            c.drawText(title, left, y, sectionP)

            val status = when {
                remoteError != null -> "ошибка: $remoteError"
                remoteOkAt > 0 -> "обновлено ${clockFmt.format(Date(remoteOkAt))}"
                else -> "загрузка…"
            }
            val sx = left + sectionP.measureText(title) + 16 * s
            smallP.color = if (remoteError != null) COLOR_BAD else 0xFF6B7585.toInt()
            c.drawText(fit(status, smallP, right - sx), sx, y, smallP)
            y += sectionP.descent() + 16 * s

            val items = remote?.items.orEmpty()
            if (items.isNotEmpty()) drawCards(c, items, left, y, right, s)
        }
    }

    /** Сетка карточек с переносом по ширине. Возвращает нижнюю границу. */
    private fun drawCards(c: Canvas, items: List<Item>, left: Float, top: Float, right: Float, s: Float): Float {
        val cw = 240 * s
        val ch = 112 * s
        val gap = 16 * s
        val pad = 20 * s
        val r = 22 * s
        val perRow = max(1, ((right - left + gap) / (cw + gap)).toInt())
        labelP.textSize = 22 * s

        var bottom = top
        items.forEachIndexed { i, item ->
            val x = left + (i % perRow) * (cw + gap)
            val y = top + (i / perRow) * (ch + gap)
            rect.set(x, y, x + cw, y + ch)
            c.drawRoundRect(rect, r, r, cardP)

            // подпись + цветная точка статуса
            val ly = y + pad - labelP.ascent()
            var lx = x + pad
            stateColor(item.state)?.let { col ->
                dotP.color = col
                c.drawCircle(lx + 5 * s, ly - labelP.textSize * 0.35f, 5 * s, dotP)
                lx += 18 * s
            }
            c.drawText(fit(item.label, labelP, x + cw - pad - lx), lx, ly, labelP)

            // значение: сначала ужимаем шрифт, потом обрезаем «…»
            val avail = cw - 2 * pad
            var size = 40 * s
            valueP.textSize = size
            while (valueP.measureText(item.value) > avail && size > 26 * s) {
                size -= 2 * s
                valueP.textSize = size
            }
            val vy = y + ch - pad - valueP.descent()
            c.drawText(fit(item.value, valueP, avail), x + pad, vy, valueP)

            bottom = max(bottom, y + ch)
        }
        return bottom
    }

    private fun fit(text: String, p: TextPaint, width: Float): String =
        if (width <= 0f) "" else TextUtils.ellipsize(text, p, width, TextUtils.TruncateAt.END).toString()

    private fun stateColor(s: State): Int? = when (s) {
        State.OK -> COLOR_OK
        State.WARN -> COLOR_WARN
        State.BAD -> COLOR_BAD
        State.NONE -> null
    }

    companion object {
        private val COLOR_OK = 0xFF4ADE80.toInt()
        private val COLOR_WARN = 0xFFFBBF24.toInt()
        private val COLOR_BAD = 0xFFF87171.toInt()
    }
}
