package com.kayo.infowall

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.util.TypedValue
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ListView
import java.util.Currency
import java.util.Locale

/**
 * Выбор валюты: поиск по коду или названию («тенге», «yen», «KG»), популярные — сверху.
 * Список валют — те, для которых есть курс; без курсов — все известные системе.
 */
object CurrencyPicker {

    private val POPULAR = listOf(
        "KZT", "RUB", "USD", "EUR", "CNY", "GBP", "TRY", "KGS", "UZS", "AED",
        "GEL", "AMD", "AZN", "BYN", "UAH", "JPY", "KRW", "CHF", "THB", "INR",
    )
    private val RU = Locale("ru")

    fun name(code: String): String =
        runCatching { Currency.getInstance(code).getDisplayName(RU) }.getOrNull()
            ?.replaceFirstChar { it.uppercase() } ?: code

    private fun allCodes(ctx: Context): List<String> {
        val known = RatesSource.cached(ctx)?.rates?.keys
            ?: runCatching { Currency.getAvailableCurrencies().map { it.currencyCode }.toSet() }.getOrDefault(emptySet())
        val rest = known.filter { it.length == 3 && it !in POPULAR }.sorted()
        return POPULAR.filter { known.isEmpty() || it in known } + rest
    }

    /** [taken] — уже выбранные валюты: они видны, но помечены. */
    fun show(a: Activity, title: String, taken: Collection<String>, onPick: (String) -> Unit) {
        val codes = allCodes(a)
        val rows = codes.map { Row(it, (if (it in taken) "✓ " else "") + "$it   ${name(it)}") }
        val shown = ArrayList(rows)
        val adapter = ArrayAdapter(a, android.R.layout.simple_list_item_1, shown.map { it.text }.toMutableList())

        val dp = { v: Int -> TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), a.resources.displayMetrics).toInt() }
        val search = EditText(a).apply {
            hint = "Поиск: код или название"
            isSingleLine = true
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            imeOptions = EditorInfo.IME_ACTION_DONE or EditorInfo.IME_FLAG_NO_EXTRACT_UI
        }
        val list = ListView(a).apply { this.adapter = adapter }
        val box = LinearLayout(a).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(8), dp(16), 0)
            addView(search)
            addView(list, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(360)))
        }
        val dlg = AlertDialog.Builder(a, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle(title)
            .setView(box)
            .setNegativeButton("Отмена", null)
            .create()

        search.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, st: Int, c: Int, af: Int) {}
            override fun onTextChanged(s: CharSequence?, st: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: Editable?) {
                val q = s?.toString()?.trim()?.lowercase(RU).orEmpty()
                shown.clear()
                shown += if (q.isEmpty()) rows else rows.filter { it.code.lowercase().startsWith(q) || it.text.lowercase(RU).contains(q) }
                adapter.clear()
                adapter.addAll(shown.map { it.text })
            }
        })
        list.setOnItemClickListener { _, _, pos, _ ->
            shown.getOrNull(pos)?.let { onPick(it.code) }
            dlg.dismiss()
        }
        dlg.show()
    }

    private class Row(val code: String, val text: String)

    /** Сохраняет список валют; если базовая валюта суммы пропала — сумма переводится в первую. */
    fun save(ctx: Context, codes: List<String>) {
        val p = Prefs.get(ctx)
        val cfg = Prefs.load(ctx)
        val e = p.edit().putString(Prefs.K_CURRENCIES, codes.joinToString(", "))
        if (codes.isNotEmpty() && cfg.fxFrom !in codes) {
            val to = codes.first()
            val v = RatesSource.cached(ctx)?.convert(cfg.fxAmount, cfg.fxFrom, to)
            e.putString(Prefs.K_FX_FROM, to)
            if (v != null) e.putString(Prefs.K_FX_AMOUNT, v.toString())
        }
        e.apply()
    }
}
