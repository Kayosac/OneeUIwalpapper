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
import kotlin.math.roundToInt

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
    private val metaP = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF8A94A3.toInt() }
    private val trackP = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x26FFFFFF }
    private val barP = Paint(Paint.ANTI_ALIAS_FLAG)
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
            if (items.isNotEmpty()) {
                drawCards(c, items, left, y, right, s)
            } else if (remote != null) {
                // источник ответил, но пуст — подсказываем, а не молчим
                smallP.color = 0xFF6B7585.toInt()
                smallP.textSize = 22 * s
                c.drawText(fit("пока нет данных — источник ответил пустым списком", smallP, right - left),
                    left, y - smallP.ascent(), smallP)
            }
        }
    }

    /** Сетка карточек с переносом по ширине. Возвращает нижнюю границу. */
    private fun drawCards(c: Canvas, items: List<Item>, left: Float, top: Float, right: Float, s: Float): Float {
        val cw = 240 * s
        val hasLimits = items.any { it.usedPercent != null }
        val ch = (if (hasLimits) 144 else 112) * s
        val gap = 16 * s
        val pad = 20 * s
        val r = 22 * s
        val perRow = max(1, ((right - left + gap) / (cw + gap)).toInt())
        labelP.textSize = 22 * s
        metaP.textSize = 19 * s
        val nowSec = System.currentTimeMillis() / 1000

        var bottom = top
        items.forEachIndexed { i, raw ->
            val item = resolveLimit(raw, nowSec)
            val x = left + (i % perRow) * (cw + gap)
            val y = top + (i / perRow) * (ch + gap)
            rect.set(x, y, x + cw, y + ch)
            c.drawRoundRect(rect, r, r, cardP)

            // подпись + цветная точка статуса; справа — время данных, если они устарели
            val ly = y + pad - labelP.ascent()
            var lx = x + pad
            var labelRight = x + cw - pad
            stateColor(item.state)?.let { col ->
                dotP.color = col
                c.drawCircle(lx + 5 * s, ly - labelP.textSize * 0.35f, 5 * s, dotP)
                lx += 18 * s
            }
            val upd = item.updatedAt
            if (item.usedPercent != null && upd != null && nowSec - upd > STALE_SEC) {
                val t = clockFmt.format(Date(upd * 1000))
                val tw = metaP.measureText(t)
                c.drawText(t, x + cw - pad - tw, ly, metaP)
                labelRight -= tw + 10 * s
            }
            c.drawText(fit(item.label, labelP, labelRight - lx), lx, ly, labelP)

            // значение: сначала ужимаем шрифт, потом обрезаем «…»
            val avail = cw - 2 * pad
            val barH = 8 * s
            val valueBottom = if (item.usedPercent != null) y + ch - pad - barH - 14 * s else y + ch - pad
            var meta: String? = null
            var metaW = 0f
            if (item.usedPercent != null) {
                meta = when {
                    raw.resetsAt != null && item.resetsAt == null -> "сброшен"
                    item.resetsAt != null -> "через " + countdown(item.resetsAt - nowSec)
                    else -> null
                }
                metaW = meta?.let { metaP.measureText(it) + 12 * s } ?: 0f
            }
            val valueAvail = avail - metaW
            var size = 40 * s
            valueP.textSize = size
            while (valueP.measureText(item.value) > valueAvail && size > 26 * s) {
                size -= 2 * s
                valueP.textSize = size
            }
            val vy = valueBottom - valueP.descent()
            c.drawText(fit(item.value, valueP, valueAvail), x + pad, vy, valueP)
            if (meta != null) {
                c.drawText(fit(meta, metaP, metaW), x + cw - pad - metaP.measureText(meta).coerceAtMost(metaW), vy, metaP)
            }

            // полоска остатка
            item.usedPercent?.let { used ->
                val remaining = (100f - used) / 100f
                val by = y + ch - pad - barH
                rect.set(x + pad, by, x + cw - pad, by + barH)
                c.drawRoundRect(rect, barH / 2, barH / 2, trackP)
                if (remaining > 0f) {
                    barP.color = stateColor(item.state) ?: Color.WHITE
                    val w = max(barH, (cw - 2 * pad) * remaining)
                    rect.set(x + pad, by, x + pad + w, by + barH)
                    c.drawRoundRect(rect, barH / 2, barH / 2, barP)
                }
            }

            bottom = max(bottom, y + ch)
        }
        return bottom
    }

    /**
     * Для лимитов: если время сброса прошло, а свежих данных ещё нет —
     * считаем окно обнулённым. Статус всегда пересчитывается по остатку.
     */
    private fun resolveLimit(item: Item, nowSec: Long): Item {
        var used = item.usedPercent ?: return item
        var resets = item.resetsAt
        if (resets != null && nowSec >= resets) {
            used = 0f
            resets = null
        }
        val remaining = 100f - used
        val state = when {
            remaining < 10f -> State.BAD
            remaining < 30f -> State.WARN
            else -> State.OK
        }
        return item.copy(value = "${remaining.roundToInt()}%", state = state, usedPercent = used, resetsAt = resets)
    }

    private fun countdown(sec: Long): String {
        val m = max(1L, (sec + 59) / 60)
        return when {
            m < 60 -> "${m}м"
            m < 24 * 60 -> "${m / 60}ч ${m % 60}м"
            else -> "${m / 1440}д ${(m % 1440) / 60}ч"
        }
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
        /** Данные лимитов старше этого — показываем, когда они пришли. */
        private const val STALE_SEC = 15 * 60L
    }
}
