package com.kayo.infowall

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate
import java.time.temporal.ChronoUnit

/**
 * Цель: название и дата. [start] — день, когда дату задали: от него считается заполнение кольца
 * (сколько пути до цели уже пройдено).
 */
data class Goal(val name: String, val date: LocalDate?, val start: LocalDate?) {
    val empty get() = date == null

    fun daysLeft(today: LocalDate = LocalDate.now()): Long? = date?.let { ChronoUnit.DAYS.between(today, it) }

    /** 0..1 — доля прошедшего времени от [start] до [date]. */
    fun progress(today: LocalDate = LocalDate.now()): Float {
        val d = date ?: return 0f
        val s = start ?: today
        val total = ChronoUnit.DAYS.between(s, d).toFloat()
        if (total <= 0f) return 1f
        return (ChronoUnit.DAYS.between(s, today) / total).coerceIn(0f, 1f)
    }
}

object Goals {
    const val COUNT = 6
    private const val KEY = "goals"

    fun load(ctx: Context): List<Goal> = parse(Prefs.get(ctx).getString(KEY, null))

    fun parse(s: String?): List<Goal> {
        val arr = runCatching { JSONArray(s ?: "[]") }.getOrDefault(JSONArray())
        return List(COUNT) { i ->
            val o = arr.optJSONObject(i)
            Goal(
                o?.optString("name").orEmpty().take(16),
                o?.optString("date")?.let { runCatching { LocalDate.parse(it) }.getOrNull() },
                o?.optString("start")?.let { runCatching { LocalDate.parse(it) }.getOrNull() },
            )
        }
    }

    fun save(ctx: Context, goals: List<Goal>) {
        val arr = JSONArray()
        goals.take(COUNT).forEach { g ->
            arr.put(JSONObject().apply {
                put("name", g.name)
                g.date?.let { put("date", it.toString()) }
                g.start?.let { put("start", it.toString()) }
            })
        }
        Prefs.get(ctx).edit().putString(KEY, arr.toString()).apply()
    }

    const val PREF_KEY = KEY
}
