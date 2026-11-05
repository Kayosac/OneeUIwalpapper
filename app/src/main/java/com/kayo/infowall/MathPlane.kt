package com.kayo.infowall

import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Плоскость z = f(x, y, t) для мини-виджета рядом с играми. x, y ∈ [−1, 1], z примерно в [−1, 1].
 * Кнопки виджета переключают формулу; смена — плавным перетеканием поверхности.
 */
object MathPlane {

    class Fn(val name: String, val formula: String)

    val FNS = listOf(
        Fn("RIPPLE", "z = sin(10r − 3t)·e^(−1.5r)"),
        Fn("SADDLE", "z = (x² − y²)·cos 0.8t"),
        Fn("WAVES", "z = sin(4x+2t)·cos(4y−1.3t)"),
        Fn("ORBIT", "z = G(p − a(t)) − G(p + a(t))"),
        Fn("SPIRAL", "z = r·sin(3θ + 6r − 2t)"),
        Fn("SINC", "z = cos 3t · sin 9r / 9r"),
        Fn("PEAKS", "z = peaks(3x, 3y)·cos 0.7t"),
        Fn("DUAL", "z = sin(12d₁ − 3t) + sin(12d₂ − 3t)"),
    )

    fun z(fn: Int, x: Float, y: Float, t: Float): Float {
        val r = sqrt(x * x + y * y)
        return when (fn) {
            0 -> sin(10f * r - 3f * t) * exp(-1.5f * r)
            1 -> (x * x - y * y) * cos(0.8f * t)
            2 -> 0.6f * sin(4f * x + 2f * t) * cos(4f * y - 1.3f * t)
            3 -> {
                val ax = 0.45f * cos(t); val ay = 0.45f * sin(t)
                fun g(dx: Float, dy: Float) = exp(-7f * (dx * dx + dy * dy))
                g(x - ax, y - ay) - g(x + ax, y + ay)
            }
            4 -> 0.8f * r * sin(3f * atan2(y, x) + 6f * r - 2f * t)
            5 -> { val u = 9f * r; (if (u < 1e-3f) 1f else sin(u) / u) * cos(3f * t) }
            6 -> {
                val u = 3f * x; val v = 3f * y
                val p = 3f * (1 - u) * (1 - u) * exp(-u * u - (v + 1) * (v + 1)) -
                    10f * (u / 5f - u * u * u - v * v * v * v * v) * exp(-u * u - v * v) -
                    exp(-(u + 1) * (u + 1) - v * v) / 3f
                0.12f * p * cos(0.7f * t)
            }
            7 -> {
                val d1 = sqrt((x - 0.5f) * (x - 0.5f) + y * y)
                val d2 = sqrt((x + 0.5f) * (x + 0.5f) + y * y)
                0.35f * (sin(12f * d1 - 3f * t) + sin(12f * d2 - 3f * t))
            }
            else -> 0f
        }
    }
}
