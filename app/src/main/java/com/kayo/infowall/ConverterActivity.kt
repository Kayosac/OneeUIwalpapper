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
 * Маленькое окошко ввода по тапу на строку конвертера: [валюта][сумма].
 * Сумма сохраняется на каждое изменение — обои сразу пересчитывают все строки.
 * Закрывается тапом мимо окошка (Enter не нужен).
 */
class ConverterActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val cfg = Prefs.load(this)
        val accent = cfg.accent
        val codes = RatesSource.parseCodes(cfg.currencies).ifEmpty { listOf("KZT", "RUB", "USD", "EUR") }
        val code = intent.getStringExtra(EXTRA_CODE)?.takeIf { it in codes } ?: cfg.fxFrom.takeIf { it in codes } ?: codes.first()
        val fonts = Fonts.load(this, cfg.font)
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_PAN or WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE)

        // текущее значение этой валюты — чтобы поле не открывалось пустым
        val start = RatesSource.cached(this)?.convert(cfg.fxAmount, cfg.fxFrom, code)

        fun frame(active: Boolean) = GradientDrawable().apply {
            setColor(Color.argb(242, 0, 10, 14))
            setStroke(dp(1), CyberRenderer.withA(accent, if (active) 0.85f else 0.32f))
        }

        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            isClickable = true   // тап по окошку не закрывает его
        }
        row.addView(TextView(this).apply {
            text = code
            typeface = fonts.label
            setTextColor(accent)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            letterSpacing = 0.08f
            gravity = Gravity.CENTER
            background = frame(false)
        }, LinearLayout.LayoutParams(dp(54), dp(38)).apply { rightMargin = dp(4) })

        val field = EditText(this).apply {
            typeface = fonts.num
            setTextColor(CyberRenderer.mix(accent, Color.WHITE, 0.35f))
            setHintTextColor(CyberRenderer.withA(accent, 0.3f))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
            imeOptions = EditorInfo.IME_ACTION_DONE or EditorInfo.IME_FLAG_NO_EXTRACT_UI
            isSingleLine = true
            hint = "0"
            gravity = Gravity.CENTER_VERTICAL or Gravity.END
            background = frame(true)
            setPadding(dp(10), 0, dp(10), 0)
            if (start != null) setText(plain(start))
            setSelectAllOnFocus(true)
            setOnEditorActionListener { _, _, _ -> finish(); true }   // «Готово» на клавиатуре тоже закрывает
        }
        field.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: Editable?) {
                val v = s?.toString()?.replace(" ", "")?.replace(',', '.')?.toDoubleOrNull() ?: return
                Prefs.get(this@ConverterActivity).edit()
                    .putString(Prefs.K_FX_AMOUNT, v.toString())
                    .putString(Prefs.K_FX_FROM, code)
                    .apply()
            }
        })
        row.addView(field, LinearLayout.LayoutParams(0, dp(38), 1f))

        val root = FrameLayout(this).apply {
            setBackgroundColor(Color.argb(90, 0, 0, 0))
            setOnClickListener { finish() }   // тап мимо окошка — закрыть
            addView(row, FrameLayout.LayoutParams(dp(230), FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.CENTER))
        }
        setContentView(root)
        field.requestFocus()
        field.selectAll()
    }

    /** Число без группировки — удобно править. */
    private fun plain(v: Double): String {
        val a = abs(v)
        return when {
            a >= 10_000 -> String.format(Locale.US, "%.0f", v)
            a >= 1 -> String.format(Locale.US, "%.2f", v).trimEnd('0').trimEnd('.')
            a == 0.0 -> "0"
            else -> String.format(Locale.US, "%.4f", v).trimEnd('0').trimEnd('.')
        }
    }

    private fun dp(v: Int) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics).toInt()

    companion object {
        const val EXTRA_CODE = "code"
    }
}
