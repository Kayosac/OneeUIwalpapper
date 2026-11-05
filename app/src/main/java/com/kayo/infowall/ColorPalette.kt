package com.kayo.infowall

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.widget.GridLayout
import android.widget.LinearLayout
import android.widget.TextView
import java.util.Locale

/**
 * Выбор цвета акцента: готовые неоновые цвета + полосы «оттенок / насыщенность / яркость» с образцом.
 * [onPick] вызывается на каждое изменение — цвет сразу уходит на обои.
 */
class ColorPalette(ctx: Context, initial: Int, private val onPick: (Int) -> Unit) : LinearLayout(ctx) {

    private val hsv = FloatArray(3)
    private val preview = TextView(ctx)
    private val swatches = ArrayList<Pair<Int, View>>()
    private val bars = ArrayList<HsvBar>()

    init {
        orientation = VERTICAL
        Color.colorToHSV(initial, hsv)

        // образец + hex
        preview.apply {
            gravity = Gravity.CENTER
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            typeface = android.graphics.Typeface.MONOSPACE
            setPadding(0, dp(10), 0, dp(10))
        }
        addView(preview, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply { topMargin = dp(6) })

        // готовые цвета
        val grid = GridLayout(ctx).apply { columnCount = 6 }
        for (c in PRESETS) {
            val v = object : View(ctx) {
                private val p = Paint(Paint.ANTI_ALIAS_FLAG)
                override fun onDraw(cv: Canvas) {
                    val r = minOf(width, height) / 2f - dp(4)
                    p.style = Paint.Style.FILL; p.color = c
                    cv.drawCircle(width / 2f, height / 2f, r, p)
                    if (sameColor(c, current())) {
                        p.style = Paint.Style.STROKE; p.strokeWidth = dp(2).toFloat(); p.color = Color.WHITE
                        cv.drawCircle(width / 2f, height / 2f, r + dp(2), p)
                    }
                }
            }
            v.setOnClickListener { set(c) }
            v.contentDescription = hex(c)
            grid.addView(v, GridLayout.LayoutParams().apply {
                width = dp(48); height = dp(48)
                columnSpec = GridLayout.spec(GridLayout.UNDEFINED, 1f)
            })
            swatches += c to v
        }
        addView(grid, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply { topMargin = dp(8) })

        // полосы
        for (k in 0..2) {
            val bar = HsvBar(ctx, k)
            bars += bar
            addView(bar, LayoutParams(LayoutParams.MATCH_PARENT, dp(40)).apply { topMargin = dp(10) })
        }
        refresh(notify = false)
    }

    fun current(): Int = Color.HSVToColor(hsv)

    private fun set(c: Int) {
        Color.colorToHSV(c, hsv)
        refresh(notify = true)
    }

    private fun refresh(notify: Boolean) {
        val c = current()
        preview.text = hex(c)
        preview.setTextColor(if (Color.luminance(c) > 0.45f) Color.BLACK else Color.WHITE)
        preview.background = android.graphics.drawable.GradientDrawable().apply {
            setColor(c); cornerRadius = dp(10).toFloat()
        }
        swatches.forEach { it.second.invalidate() }
        bars.forEach { it.invalidate() }
        if (notify) onPick(c)
    }

    /** Полоса: 0 — оттенок, 1 — насыщенность, 2 — яркость. Тянуть пальцем. */
    private inner class HsvBar(ctx: Context, private val kind: Int) : View(ctx) {
        private val p = Paint(Paint.ANTI_ALIAS_FLAG)
        private val r = RectF()

        override fun onDraw(cv: Canvas) {
            val pad = dp(14).toFloat()
            r.set(pad, height * 0.3f, width - pad, height * 0.7f)
            val colors = when (kind) {
                0 -> IntArray(7) { Color.HSVToColor(floatArrayOf(it * 60f, 1f, 1f)) }
                1 -> intArrayOf(Color.HSVToColor(floatArrayOf(hsv[0], 0f, hsv[2])), Color.HSVToColor(floatArrayOf(hsv[0], 1f, hsv[2])))
                else -> intArrayOf(Color.BLACK, Color.HSVToColor(floatArrayOf(hsv[0], hsv[1], 1f)))
            }
            p.style = Paint.Style.FILL
            p.shader = LinearGradient(r.left, 0f, r.right, 0f, colors, null, Shader.TileMode.CLAMP)
            cv.drawRoundRect(r, r.height() / 2, r.height() / 2, p)
            p.shader = null
            val frac = when (kind) { 0 -> hsv[0] / 360f; 1 -> hsv[1]; else -> hsv[2] }
            val x = r.left + r.width() * frac
            p.color = Color.WHITE
            cv.drawCircle(x, height / 2f, height * 0.32f, p)
            p.color = current()
            cv.drawCircle(x, height / 2f, height * 0.24f, p)
        }

        override fun onTouchEvent(e: MotionEvent): Boolean {
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> {
                    parent?.requestDisallowInterceptTouchEvent(true)   // не скроллить настройки, пока тянем
                    val pad = dp(14).toFloat()
                    val f = ((e.x - pad) / (width - 2 * pad)).coerceIn(0f, 1f)
                    when (kind) { 0 -> hsv[0] = f * 359.9f; 1 -> hsv[1] = f; else -> hsv[2] = f }
                    refresh(notify = true)
                    return true
                }
                MotionEvent.ACTION_UP -> { performClick(); return true }
            }
            return super.onTouchEvent(e)
        }

        override fun performClick(): Boolean { super.performClick(); return true }
    }

    private fun dp(v: Int) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics).toInt()

    companion object {
        /** Неоновые цвета, которые хорошо светятся на чёрном. */
        val PRESETS = intArrayOf(
            0xFF00E5FF.toInt(), 0xFF00FFF0.toInt(), 0xFF3DD6FF.toInt(), 0xFF7DF9FF.toInt(), 0xFF00FF9C.toInt(), 0xFF39FF14.toInt(),
            0xFFFFE600.toInt(), 0xFFFF9F1C.toInt(), 0xFFFF3B5C.toInt(), 0xFFFF2BD6.toInt(), 0xFFB26BFF.toInt(), 0xFFE8F7FF.toInt(),
        )

        fun hex(c: Int) = String.format(Locale.US, "#%06X", c and 0xFFFFFF)

        /** Сравнение с допуском: после пересчёта через HSV компоненты могут сдвинуться на 1–2. */
        private fun sameColor(a: Int, b: Int) =
            kotlin.math.abs(Color.red(a) - Color.red(b)) <= 2 && kotlin.math.abs(Color.green(a) - Color.green(b)) <= 2 &&
                kotlin.math.abs(Color.blue(a) - Color.blue(b)) <= 2
    }
}
