package com.kayo.infowall

import android.app.Activity
import android.os.SystemClock

/**
 * Свои окна поверх обоев (настройки, цели, формула, конвертер) показывают обои сквозь себя —
 * и система отдаёт обоям копии касаний по этим окнам. Пока такое окно открыто (и чуть после
 * закрытия — тот же тап мог его закрыть), обои тапы не обрабатывают.
 */
object OwnUi {
    @Volatile private var resumed = 0
    @Volatile private var lastPauseAt = 0L

    fun busy(): Boolean = resumed > 0 || SystemClock.uptimeMillis() - lastPauseAt < 500

    fun onResume() { resumed++ }
    fun onPause() { resumed = maxOf(0, resumed - 1); lastPauseAt = SystemClock.uptimeMillis() }
}

/** Базовый класс для своих окон поверх обоев. */
open class OwnActivity : Activity() {
    override fun onResume() { super.onResume(); OwnUi.onResume() }
    override fun onPause() { OwnUi.onPause(); super.onPause() }
}
