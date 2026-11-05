package com.kayo.infowall

import android.graphics.Color
import android.graphics.Typeface
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
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/**
 * Своя формула для виджета «Формула»: тап по плоскости на обоях. Панель снизу, виджет сверху виден —
 * поверхность меняется прямо по ходу набора (каждая верная формула сразу уходит на обои).
 */
class MathEditActivity : OwnActivity() {

    private lateinit var input: EditText
    private lateinit var err: TextView
    private var oldFn = 0
    private var oldSrc = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_SHOW_WALLPAPER)
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE or WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE)
        val p = Prefs.get(this)
        oldFn = p.getInt(Prefs.K_MATH_FN, 0)
        oldSrc = p.getString(Prefs.K_MATH_CUSTOM, "").orEmpty()

        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(12), dp(20), dp(16))
        }
        col.addView(TextView(this).apply {
            text = "Своя формула"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
        })
        col.addView(TextView(this).apply {
            text = "z от x и y (от −1 до 1), t — время, r — расстояние от центра, θ — угол. " +
                "Можно: + − * / ^, скобки, |x|, 2x, sin x; sin cos tan exp ln sqrt abs min max atan2 … " +
                "Поверхность меняется на обоях по ходу набора; высота обрезается по краям."
            alpha = 0.7f
            setPadding(0, dp(4), 0, dp(8))
        })

        // поле ввода: «z =» слева
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        row.addView(TextView(this).apply { text = "z ="; typeface = Typeface.MONOSPACE; setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f) })
        input = EditText(this).apply {
            typeface = Typeface.MONOSPACE
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
            isSingleLine = true
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
            imeOptions = EditorInfo.IME_ACTION_DONE or EditorInfo.IME_FLAG_NO_EXTRACT_UI
            hint = "sin(5r − 2t)·cos(3θ)"
            setText(if (oldFn == MathPlane.CUSTOM) oldSrc else "")
            setSelection(text.length)
            setOnEditorActionListener { _, a, _ -> if (a == EditorInfo.IME_ACTION_DONE) { finishOk(); true } else false }
        }
        row.addView(input, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        col.addView(row)

        err = TextView(this).apply { setTextColor(Color.rgb(255, 120, 140)); setPadding(0, dp(2), 0, dp(6)) }
        col.addView(err)

        // быстрые вставки
        col.addView(chips(listOf("x", "y", "t", "r", "θ", "π", "^", "·", "(", ")", "sin(", "cos(", "exp(", "sqrt(", "abs(", "atan2(")) { insert(it) })

        // примеры
        col.addView(TextView(this).apply { text = "Примеры"; alpha = 0.7f; setPadding(0, dp(10), 0, dp(2)) })
        col.addView(chips(EXAMPLES) { input.setText(it); input.setSelection(it.length) })

        val bottom = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.END; setPadding(0, dp(10), 0, 0) }
        bottom.addView(Button(this, null, android.R.attr.borderlessButtonStyle).apply { text = "Отмена"; setOnClickListener { cancel() } })
        bottom.addView(Button(this).apply { text = "Готово"; setOnClickListener { finishOk() } })
        col.addView(bottom)

        input.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(e: Editable?) = preview(e?.toString().orEmpty())
        })
        preview(input.text.toString())

        val scroll = ScrollView(this).apply {
            setBackgroundColor(Color.argb(236, 6, 10, 14))
            addView(col)
        }
        val h = (resources.displayMetrics.heightPixels * 0.56f).toInt()
        setContentView(FrameLayout(this).apply {
            setOnClickListener { finishOk() }      // тап по обоям над панелью — оставить как есть
            addView(scroll, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, h, Gravity.BOTTOM))
        })
        input.requestFocus()
    }

    /** Верная формула — сразу на обои; с ошибкой — подсказка, обои не трогаем. */
    private fun preview(src: String) {
        if (src.isBlank()) { err.text = ""; return }
        val e = MathExpr.check(src)
        err.text = e?.let { "✗ $it" } ?: "✓"
        if (e == null) Prefs.get(this).edit().putString(Prefs.K_MATH_CUSTOM, src.trim()).putInt(Prefs.K_MATH_FN, MathPlane.CUSTOM).apply()
    }

    private fun finishOk() {
        val src = input.text.toString()
        if (src.isNotBlank() && MathExpr.check(src) != null) return      // с ошибкой не закрываем
        if (src.isBlank()) restore()
        finish()
    }

    private fun cancel() { restore(); finish() }

    private fun restore() {
        Prefs.get(this).edit().putInt(Prefs.K_MATH_FN, oldFn).putString(Prefs.K_MATH_CUSTOM, oldSrc).apply()
    }

    override fun onBackPressed() { cancel() }

    private fun insert(s: String) {
        val a = input.selectionStart.coerceAtLeast(0); val b = input.selectionEnd.coerceAtLeast(0)
        input.text.replace(minOf(a, b), maxOf(a, b), s)
    }

    private fun chips(items: List<String>, onTap: (String) -> Unit): HorizontalScrollView {
        val line = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        for (it in items) line.addView(Button(this, null, android.R.attr.borderlessButtonStyle).apply {
            text = it
            isAllCaps = false
            typeface = Typeface.MONOSPACE
            minWidth = dp(40); minimumWidth = dp(40)
            setOnClickListener { _ -> onTap(it) }
        })
        return HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false; addView(line) }
    }

    private fun dp(v: Int) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics).toInt()

    companion object {
        val EXAMPLES = listOf(
            "sin(5r − 2t)·cos(3θ)",
            "sin(3x + t)·sin(3y − t)",
            "0.5·sin(8(x² + y²) − 3t)",
            "cos(6x)·cos(6y)·sin(t)",
            "exp(−4r²)·sin(10r − 4t)",
            "0.3·tan(3x·y − t)",
            "sin(6θ + 4r − 2t)·(1 − r)",
            "|sin(4x + t)| − |cos(4y − t)|",
        )
    }
}
