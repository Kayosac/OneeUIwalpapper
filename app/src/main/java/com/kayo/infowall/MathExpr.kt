package com.kayo.infowall

import kotlin.math.PI
import kotlin.math.E

/**
 * Своя формула для виджета «Формула»: z = выражение от x, y, t, r, θ.
 * Небольшой разборщик с рекурсивным спуском — формула компилируется один раз в дерево лямбд,
 * дальше на каждую вершину сетки только вычисление.
 *
 * Понимает: + − * / ^, скобки, |x| (модуль), неявное умножение (2x, 3sin(t), x(y+1)),
 * ·, ×, −, ², ³, π, θ; переменные x y t r θ (или a, theta); константы pi, e;
 * функции sin cos tan asin acos atan sinh cosh tanh exp ln log sqrt abs floor ceil round sign
 * min max atan2 pow mod hypot.
 */
object MathExpr {

    class Vars { var x = 0.0; var y = 0.0; var t = 0.0; var r = 0.0; var a = 0.0 }

    fun interface Node { fun v(s: Vars): Double }

    class Error(msg: String, val pos: Int) : Exception(msg)

    /** Разобрать: дерево или исключение [Error] с понятным текстом и позицией. */
    fun compile(src: String): Node {
        var s = src.trim()
        // «z = …» в начале — можно писать, можно нет
        Regex("^z\\s*=\\s*", RegexOption.IGNORE_CASE).find(s)?.let { s = s.substring(it.range.last + 1) }
        if (s.isBlank()) throw Error("пустая формула", 0)
        val p = Parser(normalize(s))
        val n = p.expr()
        p.ws()
        if (!p.end()) throw Error("лишнее: «${p.rest()}»", p.i)
        return n
    }

    /** Текст ошибки или null, если формула верна. */
    fun check(src: String): String? = try { compile(src); null } catch (e: Error) { e.message } catch (e: Exception) { "не разобрать" }

    private fun normalize(s: String): String = buildString {
        for (ch in s) when (ch) {
            '·', '×', '∙', '⋅' -> append('*')
            '−', '–', '—' -> append('-')
            '÷', '∕' -> append('/')
            '²' -> append("^2")
            '³' -> append("^3")
            'π' -> append("pi")
            'θ', 'φ' -> append("theta")
            ',' -> append(',')
            else -> append(ch)
        }
    }

    private class Parser(val s: String) {
        var i = 0

        fun ws() { while (i < s.length && s[i].isWhitespace()) i++ }
        fun end() = i >= s.length
        fun rest() = s.substring(i).take(12)
        fun peek(): Char? { ws(); return if (i < s.length) s[i] else null }
        fun eat(c: Char): Boolean { if (peek() == c) { i++; return true }; return false }

        fun expr(): Node {
            var l = term()
            while (true) {
                l = when (peek()) {
                    '+' -> { i++; val a = l; val b = term(); Node { st -> a.v(st) + b.v(st) } }
                    '-' -> { i++; val a = l; val b = term(); Node { st -> a.v(st) - b.v(st) } }
                    else -> return l
                }
            }
        }

        // a*b, a/b: множители — «неявные произведения», они связывают сильнее: 1/2x = 1/(2x), sin 9r / 9r
        fun term(): Node {
            var l = implicit(false)
            while (true) {
                l = when (peek()) {
                    '*' -> { i++; val a = l; val b = implicit(false); Node { st -> a.v(st) * b.v(st) } }
                    '/' -> { i++; val a = l; val b = implicit(false); Node { st -> a.v(st) / b.v(st) } }
                    '%' -> { i++; val a = l; val b = implicit(false); Node { st -> a.v(st).mod(b.v(st)) } }
                    else -> return l
                }
            }
        }

        /** 2x, 3sin(t), (x+1)(y-1), 2pi. [argOfFn] — аргумент функции без скобок: «sin 2x cos y» = sin(2x)·cos(y). */
        fun implicit(argOfFn: Boolean): Node {
            var l = unary()
            while (true) {
                val c = peek() ?: return l
                if (!(c.isLetterOrDigit() || c == '(' || c == '.')) return l
                if (argOfFn && c.isLetter() && startsFunction()) return l
                val a = l; val b = power()
                l = Node { st -> a.v(st) * b.v(st) }
            }
        }

        private fun startsFunction(): Boolean {
            var j = i
            while (j < s.length && (s[j].isLetter() || s[j].isDigit() || s[j] == '_')) j++
            val w = s.substring(i, j).lowercase()
            return F1.any { w.startsWith(it) } || w in setOf("min", "max", "atan2", "pow", "mod", "hypot")
        }

        fun unary(): Node = when (peek()) {
            '-' -> { i++; val a = unary(); Node { st -> -a.v(st) } }
            '+' -> { i++; unary() }
            else -> power()
        }

        fun power(): Node {
            val base = atom()
            if (peek() == '^') {
                i++
                val ex = unary()          // справа налево: 2^3^2 = 2^9; 2^-x можно
                return Node { st -> Math.pow(base.v(st), ex.v(st)) }
            }
            return base
        }

        fun atom(): Node {
            val c = peek() ?: throw Error("формула оборвалась", i)
            if (c == '(') {
                i++; val e = expr()
                if (!eat(')')) throw Error("не хватает «)»", i)
                return e
            }
            if (c == '|') {
                i++; val e = expr()
                if (!eat('|')) throw Error("не хватает «|»", i)
                return Node { st -> Math.abs(e.v(st)) }
            }
            if (c.isDigit() || c == '.') {
                val st = i
                while (i < s.length && (s[i].isDigit() || s[i] == '.')) i++
                // 1e-3
                if (i < s.length && (s[i] == 'e' || s[i] == 'E') && i + 1 < s.length &&
                    (s[i + 1].isDigit() || ((s[i + 1] == '-' || s[i + 1] == '+') && i + 2 < s.length && s[i + 2].isDigit()))) {
                    i += 2
                    while (i < s.length && s[i].isDigit()) i++
                }
                val v = s.substring(st, i).toDoubleOrNull() ?: throw Error("число «${s.substring(st, i)}»", st)
                return Node { v }
            }
            if (c.isLetter()) {
                val st = i
                while (i < s.length && (s[i].isLetter() || s[i].isDigit() || s[i] == '_')) i++
                val name = s.substring(st, i).lowercase()
                return ident(name, st)
            }
            throw Error("непонятный знак «$c»", i)
        }

        fun ident(name: String, at: Int): Node {
            if (peek() == '(') {
                i++
                val args = ArrayList<Node>()
                if (peek() != ')') {
                    args += expr()
                    while (eat(',') || eat(';')) args += expr()
                }
                if (!eat(')')) throw Error("не хватает «)» после $name(", i)
                return func(name, args, at)
            }
            when (name) {
                "x" -> return Node { it.x }
                "y" -> return Node { it.y }
                "t" -> return Node { it.t }
                "r" -> return Node { it.r }
                "a", "theta", "th" -> return Node { it.a }
                "pi" -> return Node { PI }
                "e" -> return Node { E }
            }
            // функция без скобок: sin x, cos 3t
            if (name in F1) {
                val arg = implicit(true)
                return func(name, listOf(arg), at)
            }
            // функция, приклеенная к переменной: sinx, cosxy, exp2... — только для переменных
            F1.sortedByDescending { it.length }.firstOrNull { name.startsWith(it) && name.length > it.length }?.let { f ->
                val rest = name.substring(f.length)
                if (rest.all { it in "xytra" }) return func(f, listOf(ident(rest, at + f.length)), at)
            }
            // склеенное неявное умножение: xy, xt, tx, pix, rt
            if (name.length > 1 && name.all { it in "xytra" }) {
                val parts = name.map { ident(it.toString(), at) }
                return Node { st -> parts.fold(1.0) { m, p -> m * p.v(st) } }
            }
            throw Error("неизвестно «$name»", at)
        }

        fun func(name: String, a: List<Node>, at: Int): Node {
            fun need(n: Int) { if (a.size != n) throw Error("$name: нужно аргументов — $n", at) }
            if (name in F1) {
                need(1); val p = a[0]
                return when (name) {
                    "sin" -> Node { Math.sin(p.v(it)) }
                    "cos" -> Node { Math.cos(p.v(it)) }
                    "tan", "tg" -> Node { Math.tan(p.v(it)) }
                    "asin" -> Node { Math.asin(p.v(it)) }
                    "acos" -> Node { Math.acos(p.v(it)) }
                    "atan", "arctg" -> Node { Math.atan(p.v(it)) }
                    "sinh" -> Node { Math.sinh(p.v(it)) }
                    "cosh" -> Node { Math.cosh(p.v(it)) }
                    "tanh" -> Node { Math.tanh(p.v(it)) }
                    "exp" -> Node { Math.exp(p.v(it)) }
                    "ln" -> Node { Math.log(p.v(it)) }
                    "log" -> Node { Math.log10(p.v(it)) }
                    "sqrt" -> Node { Math.sqrt(p.v(it)) }
                    "abs" -> Node { Math.abs(p.v(it)) }
                    "floor" -> Node { Math.floor(p.v(it)) }
                    "ceil" -> Node { Math.ceil(p.v(it)) }
                    "round" -> Node { Math.rint(p.v(it)) }
                    "sign", "sgn" -> Node { Math.signum(p.v(it)) }
                    else -> throw Error("неизвестно «$name»", at)
                }
            }
            return when (name) {
                "min" -> { if (a.isEmpty()) need(2); Node { st -> a.minOf { it.v(st) } } }
                "max" -> { if (a.isEmpty()) need(2); Node { st -> a.maxOf { it.v(st) } } }
                "atan2" -> { need(2); Node { st -> Math.atan2(a[0].v(st), a[1].v(st)) } }
                "pow" -> { need(2); Node { st -> Math.pow(a[0].v(st), a[1].v(st)) } }
                "mod" -> { need(2); Node { st -> a[0].v(st).mod(a[1].v(st)) } }
                "hypot" -> { need(2); Node { st -> Math.hypot(a[0].v(st), a[1].v(st)) } }
                else -> throw Error("неизвестная функция «$name»", at)
            }
        }
    }

    private val F1 = setOf(
        "sin", "cos", "tan", "tg", "asin", "acos", "atan", "arctg", "sinh", "cosh", "tanh",
        "exp", "ln", "log", "sqrt", "abs", "floor", "ceil", "round", "sign", "sgn",
    )
}
