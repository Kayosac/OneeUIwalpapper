package com.kayo.infowall

import android.app.Activity
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.util.TypedValue
import android.view.Gravity
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs

/**
 * Всплывающий конвертер поверх рабочего стола. Число можно ввести в любое из четырёх полей —
 * остальные пересчитываются сразу. Всё управление кнопками/касанием, Enter не нужен.
 */
class ConverterActivity : Activity() {

    private lateinit var codes: List<String>
    private val fields = ArrayList<EditText>()
    private var rates: Rates? = null
    private var updating = false
    private lateinit var status: TextView
    private var accent = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val cfg = Prefs.load(this)
        accent = cfg.accent
        codes = RatesSource.parseCodes(cfg.currencies).ifEmpty { listOf("KZT", "RUB", "USD", "EUR") }
        val fonts = Fonts.load(this, cfg.font)
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE or WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE)

        val label = CyberRenderer.mix(accent, Color.rgb(110, 130, 140), 0.42f)
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val p = dp(20)
            setPadding(p, p, p, dp(14))
            background = GradientDrawable().apply {
                setColor(Color.argb(238, 0, 10, 14))
                setStroke(dp(1), CyberRenderer.withA(accent, 0.45f))
            }
            isClickable = true   // тап по панели не закрывает окно
        }

        panel.addView(TextView(this).apply {
            text = "КОНВЕРТЕР"
            typeface = fonts.label
            setTextColor(accent)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            letterSpacing = 0.25f
        })
        status = TextView(this).apply {
            typeface = fonts.label
            setTextColor(label)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            setPadding(0, dp(4), 0, dp(12))
        }
        panel.addView(status)

        for ((i, code) in codes.withIndex()) {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, dp(4), 0, dp(4))
            }
            row.addView(TextView(this).apply {
                text = code
                typeface = fonts.label
                setTextColor(label)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
                minWidth = dp(64)
            })
            val et = EditText(this).apply {
                typeface = fonts.num
                setTextColor(CyberRenderer.mix(accent, Color.WHITE, 0.35f))
                setHintTextColor(CyberRenderer.withA(accent, 0.35f))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 22f)
                inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
                imeOptions = EditorInfo.IME_ACTION_NEXT or EditorInfo.IME_FLAG_NO_EXTRACT_UI
                isSingleLine = true
                hint = "0"
                background = GradientDrawable().apply {
                    setColor(Color.TRANSPARENT)
                    setStroke(dp(1), CyberRenderer.withA(accent, 0.3f))
                }
                setPadding(dp(12), dp(8), dp(12), dp(8))
                minHeight = dp(48)
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }
            et.addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun afterTextChanged(s: Editable?) { if (!updating) recalc(i) }
            })
            fields += et
            row.addView(et)
            panel.addView(row)
        }

        panel.addView(Button(this).apply {
            text = "Закрыть"
            typeface = fonts.label
            setTextColor(accent)
            background = GradientDrawable().apply { setColor(CyberRenderer.withA(accent, 0.12f)) }
            setOnClickListener { finish() }
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(48)).apply { topMargin = dp(12) }
        })

        val root = FrameLayout(this).apply {
            setBackgroundColor(Color.argb(140, 0, 0, 0))
            setOnClickListener { finish() }   // тап мимо панели — закрыть
            addView(panel, FrameLayout.LayoutParams(dp(360), FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.CENTER))
        }
        setContentView(root)

        rates = RatesSource.cached(this)
        showStatus()
        seed()
        val stale = rates?.let { System.currentTimeMillis() - it.fetchedAt > RatesSource.STALE_MS } ?: true
        if (stale) refresh()
    }

    private fun refresh() {
        if (rates == null) status.text = "загружаю курсы…"
        Thread {
            val r = runCatching { RatesSource.fetch(this) }
            runOnUiThread {
                if (isFinishing) return@runOnUiThread
                r.onSuccess { rates = it; showStatus(); val i = focusedIndex(); recalc(if (i >= 0) i else baseIndex()) }
                    .onFailure { if (rates == null) status.text = "нет связи — курсы не загружены" }
            }
        }.start()
    }

    private fun showStatus() {
        val r = rates ?: return
        val date = SimpleDateFormat("dd.MM HH:mm", Locale.getDefault()).format(Date(if (r.updatedUtc > 0) r.updatedUtc else r.fetchedAt))
        status.text = "курс на $date · open.er-api.com"
    }

    private fun baseIndex() = codes.indexOf("USD").let { if (it >= 0) it else 0 }

    private fun focusedIndex() = fields.indexOfFirst { it.hasFocus() }

    /** Стартовое значение: 1 USD (или 1 первой валюты) и пересчёт остальных. */
    private fun seed() {
        val i = baseIndex()
        updating = true
        fields[i].setText("1")
        updating = false
        recalc(i)
        fields[i].requestFocus()
        fields[i].setSelection(fields[i].text.length)
    }

    private fun recalc(src: Int) {
        val r = rates ?: return
        val amount = fields[src].text.toString().replace(" ", "").replace(',', '.').toDoubleOrNull()
        updating = true
        for ((k, f) in fields.withIndex()) {
            if (k == src) continue
            val v = if (amount == null) null else r.convert(amount, codes[src], codes[k])
            f.setText(if (v == null) "" else fmt(v))
        }
        updating = false
    }

    private fun fmt(v: Double): String {
        val a = abs(v)
        val s = when {
            a >= 1000 -> String.format(Locale.US, "%,.0f", v)
            a >= 1 -> String.format(Locale.US, "%,.2f", v)
            a == 0.0 -> "0"
            else -> String.format(Locale.US, "%.4f", v)
        }
        return s.replace(',', ' ')
    }

    private fun dp(v: Int) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics).toInt()
}
