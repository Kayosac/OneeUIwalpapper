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
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
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
    private var accent = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val cfg = Prefs.load(this)
        accent = cfg.accent
        codes = RatesSource.parseCodes(cfg.currencies).ifEmpty { listOf("KZT", "RUB", "USD", "EUR") }
        val fonts = Fonts.load(this, cfg.font)
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE or WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE)

        val label = CyberRenderer.mix(accent, Color.rgb(110, 130, 140), 0.42f)
        val valueColor = CyberRenderer.mix(accent, Color.WHITE, 0.35f)
        fun frame(active: Boolean) = GradientDrawable().apply {
            setColor(Color.argb(235, 0, 10, 14))
            setStroke(dp(1), CyberRenderer.withA(accent, if (active) 0.85f else 0.32f))
        }

        // четыре компактные строки: [валюта][сумма]
        val stack = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            isClickable = true   // тап по строкам не закрывает окно
        }
        for ((i, code) in codes.withIndex()) {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(36)).apply {
                    if (i > 0) topMargin = dp(4)
                }
            }
            val codeView = TextView(this).apply {
                text = code
                typeface = fonts.label
                setTextColor(label)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                letterSpacing = 0.08f
                gravity = Gravity.CENTER
                background = frame(false)
                layoutParams = LinearLayout.LayoutParams(dp(54), LinearLayout.LayoutParams.MATCH_PARENT).apply { rightMargin = dp(4) }
            }
            val et = EditText(this).apply {
                typeface = fonts.num
                setTextColor(valueColor)
                setHintTextColor(CyberRenderer.withA(accent, 0.3f))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f)
                inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
                imeOptions = EditorInfo.IME_ACTION_NEXT or EditorInfo.IME_FLAG_NO_EXTRACT_UI
                isSingleLine = true
                hint = "0"
                gravity = Gravity.CENTER_VERTICAL or Gravity.END
                background = frame(false)
                setPadding(dp(10), 0, dp(10), 0)
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f)
                setOnFocusChangeListener { v, has ->
                    v.background = frame(has)
                    codeView.setTextColor(if (has) accent else label)
                }
            }
            et.addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun afterTextChanged(s: Editable?) { if (!updating) recalc(i) }
            })
            fields += et
            row.addView(codeView)
            row.addView(et)
            stack.addView(row)
        }

        val root = FrameLayout(this).apply {
            setBackgroundColor(Color.argb(140, 0, 0, 0))
            setOnClickListener { finish() }   // тап мимо строк — закрыть
            addView(stack, FrameLayout.LayoutParams(dp(250), FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.CENTER))
        }
        setContentView(root)

        rates = RatesSource.cached(this)
        seed()
        val stale = rates?.let { System.currentTimeMillis() - it.fetchedAt > RatesSource.STALE_MS } ?: true
        if (stale) refresh()
    }

    private fun refresh() {
        Thread {
            val r = runCatching { RatesSource.fetch(this) }
            runOnUiThread {
                if (isFinishing) return@runOnUiThread
                r.onSuccess { rates = it; val i = focusedIndex(); recalc(if (i >= 0) i else baseIndex()) }
                    .onFailure { if (rates == null) android.widget.Toast.makeText(this, "Нет связи — курсы не загружены", android.widget.Toast.LENGTH_SHORT).show() }
            }
        }.start()
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
