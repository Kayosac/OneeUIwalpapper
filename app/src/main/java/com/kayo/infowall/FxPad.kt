package com.kayo.infowall

import java.util.Locale
import kotlin.math.abs

/**
 * Ввод суммы прямо на обоях: своя цифровая клавиатура вместо системной
 * (окно обоев не может получить фокус клавиатуры, а тапы получает).
 */
class FxEdit(var code: String, var input: String) {
    var fresh = true                 // первая цифра заменяет показанное значение
    var currencyMode = false         // вместо цифр — сетка валют
    var flashKey: String? = null     // подсветка нажатой клавиши
    var flashAt = 0L
    var lastAt = 0L                  // для автозакрытия

    /** Набранное число или null, если поле пустое. */
    fun value(): Double? = input.toDoubleOrNull()

    /** Обработка цифровой клавиши. */
    fun press(key: String) {
        when (key) {
            KEY_BS -> input = if (fresh) "" else input.dropLast(1)
            KEY_CLEAR -> input = ""
            KEY_DOT -> {
                if (fresh) input = ""
                if ('.' !in input) input = (input.ifEmpty { "0" }) + "."
            }
            else -> {
                if (fresh) input = ""
                val add = key
                val next = if (input.isEmpty() || input == "0") add.trimStart('0').ifEmpty { "0" } else input + add
                val digits = next.count { it.isDigit() }
                val frac = next.substringAfter('.', "").length
                if (digits <= 13 && frac <= 4) input = next
            }
        }
        fresh = false
    }

    companion object {
        const val KEY_BS = "BS"
        const val KEY_CLEAR = "C"
        const val KEY_DOT = "."
        const val KEY_CUR = "CUR"
        const val KEY_OK = "OK"
        const val KEY_BACK = "BACK"
        const val CUR_PREFIX = "cur:"

        /** Раскладка цифр: 4×4, подписи как на клавишах. */
        val DIGIT_ROWS = listOf(
            listOf("7", "8", "9", KEY_BS),
            listOf("4", "5", "6", KEY_CLEAR),
            listOf("1", "2", "3", KEY_CUR),
            listOf("0", "000", KEY_DOT, KEY_OK),
        )

        fun keyLabel(k: String): String = when (k) {
            KEY_BS -> "СТЕРЕТЬ"
            KEY_CLEAR -> "C"
            KEY_CUR -> "ВАЛЮТА"
            KEY_OK -> "ГОТОВО"
            KEY_BACK -> "НАЗАД К ЦИФРАМ"
            else -> k.removePrefix(CUR_PREFIX)
        }

        /** Число без группировки — удобно править. */
        fun plain(v: Double): String {
            val a = abs(v)
            return when {
                a >= 10_000 -> String.format(Locale.US, "%.0f", v)
                a >= 1 -> String.format(Locale.US, "%.2f", v).trimEnd('0').trimEnd('.')
                a == 0.0 -> "0"
                else -> String.format(Locale.US, "%.4f", v).trimEnd('0').trimEnd('.')
            }
        }
    }
}
