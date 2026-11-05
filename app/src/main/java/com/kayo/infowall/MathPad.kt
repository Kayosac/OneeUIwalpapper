package com.kayo.infowall

/**
 * Ввод своей формулы прямо на обоях: клавиатура на месте виджета игр (как у конвертера —
 * окно обоев не получает системную клавиатуру, а тапы получает).
 * [text] — набранное, [cursor] — позиция курсора; [lastValid] — последняя верная формула
 * (её плоскость и показывает, пока в набранном ошибка).
 */
class MathEdit(start: String) {
    var text: String = start
    var cursor: Int = start.length
    var lastValid: String = if (start.isNotBlank() && MathExpr.check(start) == null) start else ""
    var error: String? = if (start.isBlank()) null else MathExpr.check(start)
    var flashKey: String? = null
    var flashAt = 0L
    var lastAt = 0L

    fun press(key: String) {
        when (key) {
            KEY_LEFT -> cursor = (cursor - 1).coerceAtLeast(0)
            KEY_RIGHT -> cursor = (cursor + 1).coerceAtMost(text.length)
            KEY_BS -> if (cursor > 0) {
                // функция целиком: «sin(|» стирается одним нажатием
                val before = text.substring(0, cursor)
                val fn = FUNCS.firstOrNull { before.endsWith("$it(") }
                val n = if (fn != null) fn.length + 1 else 1
                text = text.removeRange(cursor - n, cursor); cursor -= n
            }
            KEY_CLR -> { text = ""; cursor = 0 }
            else -> {
                val ins = insertOf(key)
                text = text.substring(0, cursor) + ins + text.substring(cursor)
                cursor += ins.length
            }
        }
        if (text.length > 120) { text = text.take(120); cursor = cursor.coerceAtMost(text.length) }
        error = if (text.isBlank()) null else MathExpr.check(text)
        if (error == null && text.isNotBlank()) lastValid = text
    }

    companion object {
        const val KEY_LEFT = "◄"
        const val KEY_RIGHT = "►"
        const val KEY_BS = "⌫"
        const val KEY_CLR = "CLR"
        const val KEY_ESC = "ESC"
        const val KEY_OK = "OK"

        val FUNCS = listOf(
            "sin", "cos", "tan", "asin", "acos", "atan", "atan2", "hypot",
            "sinh", "cosh", "tanh", "exp", "ln", "log", "sqrt", "abs",
            "min", "max", "floor", "mod", "sign", "round",
        )

        /** 8 × 7: функции, переменные и константы, цифры и знаки, правка. */
        val ROWS = listOf(
            listOf("sin", "cos", "tan", "asin", "acos", "atan", "atan2", "hypot"),
            listOf("sinh", "cosh", "tanh", "exp", "ln", "log", "sqrt", "abs"),
            listOf("x", "y", "t", "r", "θ", "π", "e", ","),
            listOf("7", "8", "9", "(", ")", "|", "min", "max"),
            listOf("4", "5", "6", "·", "/", "^", "²", "floor"),
            listOf("1", "2", "3", "+", "−", "mod", "sign", "round"),
            listOf("0", ".", KEY_LEFT, KEY_RIGHT, KEY_BS, KEY_CLR, KEY_ESC, KEY_OK),
        )

        fun insertOf(key: String): String = when {
            key in FUNCS -> "$key("
            else -> key
        }

        fun isFunc(key: String) = key in FUNCS
        fun isCmd(key: String) = key == KEY_CLR || key == KEY_ESC || key == KEY_OK
    }
}
