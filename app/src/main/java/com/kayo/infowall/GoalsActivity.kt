package com.kayo.infowall

import android.app.Activity
import android.app.DatePickerDialog
import android.graphics.Color
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
import android.widget.ScrollView
import android.widget.TextView
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Цели по тапу на блок колец: 6 строк [название][дата][✕].
 * Панель снизу, сверху видны живые обои — кольца меняются сразу.
 */
class GoalsActivity : Activity() {

    private val goals = ArrayList<Goal>()
    private val fmt = DateTimeFormatter.ofPattern("d MMM yyyy", Locale("ru"))

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_SHOW_WALLPAPER)
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        goals += Goals.load(this)

        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(12), dp(20), dp(16))
        }
        col.addView(TextView(this).apply {
            text = "Цели — дни до даты"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
            setPadding(0, 0, 0, dp(4))
        })
        col.addView(TextView(this).apply {
            text = "Кольцо — убывающий таймер: в день, когда задана дата, оно полное и тает к самой дате. Меняется на обоях сразу."
            alpha = 0.7f
            setPadding(0, 0, 0, dp(8))
        })
        for (i in 0 until Goals.COUNT) col.addView(row(i))
        col.addView(Button(this).apply { text = "Готово"; setOnClickListener { finish() } })

        val scroll = ScrollView(this).apply {
            setBackgroundColor(Color.argb(236, 6, 10, 14))
            addView(col)
        }
        val h = (resources.displayMetrics.heightPixels * 0.56f).toInt()
        setContentView(FrameLayout(this).apply {
            setOnClickListener { finish() }     // тап по обоям над панелью — закрыть
            addView(scroll, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, h, Gravity.BOTTOM))
        })
    }

    private fun row(i: Int): LinearLayout {
        val r = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = dp(52)
        }
        r.addView(TextView(this).apply { text = "${i + 1}"; alpha = 0.6f; minWidth = dp(22) })
        val name = EditText(this).apply {
            hint = "Название"
            setText(goals[i].name)
            isSingleLine = true
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            imeOptions = EditorInfo.IME_ACTION_DONE or EditorInfo.IME_FLAG_NO_EXTRACT_UI
            filters = arrayOf(android.text.InputFilter.LengthFilter(16))
        }
        name.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(e: Editable?) {
                goals[i] = goals[i].copy(name = e?.toString().orEmpty().trim())
                Goals.save(this@GoalsActivity, goals)
            }
        })
        r.addView(name, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))

        val dateBtn = Button(this, null, android.R.attr.borderlessButtonStyle)
        fun showDate() { dateBtn.text = goals[i].date?.format(fmt) ?: "Дата…" }
        showDate()
        dateBtn.setOnClickListener {
            val d = goals[i].date ?: LocalDate.now().plusDays(30)
            DatePickerDialog(this, { _, y, m, day ->
                val picked = LocalDate.of(y, m + 1, day)
                // отсчёт кольца — с сегодняшнего дня, если дату задали впервые или поменяли
                val start = if (goals[i].date == picked) goals[i].start else LocalDate.now()
                goals[i] = goals[i].copy(date = picked, start = start)
                Goals.save(this, goals)
                showDate()
            }, d.year, d.monthValue - 1, d.dayOfMonth).show()
        }
        r.addView(dateBtn)

        r.addView(Button(this, null, android.R.attr.borderlessButtonStyle).apply {
            text = "✕"
            minWidth = dp(44); minimumWidth = dp(44)
            setOnClickListener {
                goals[i] = Goal("", null, null)
                Goals.save(this@GoalsActivity, goals)
                name.setText("")
                showDate()
            }
        })
        return r
    }

    private fun dp(v: Int) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics).toInt()
}
