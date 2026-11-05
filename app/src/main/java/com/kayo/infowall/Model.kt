package com.kayo.infowall

import android.content.Context
import android.content.SharedPreferences

enum class State {
    OK, WARN, BAD, NONE;

    companion object {
        fun from(s: String?): State = when (s?.trim()?.lowercase()) {
            "ok", "good", "up", "green" -> OK
            "warn", "warning", "yellow" -> WARN
            "bad", "error", "down", "red", "crit", "critical" -> BAD
            else -> NONE
        }
    }
}

data class Item(val label: String, val value: String, val state: State = State.NONE)

data class RemoteData(val title: String?, val items: List<Item>)

data class Config(
    val url: String,
    val token: String,
    val intervalSec: Int,
    val topPct: Int,
    val showClock: Boolean,
    val showBattery: Boolean,
    val showRam: Boolean,
    val showStorage: Boolean,
    val showNetwork: Boolean,
)

object Prefs {
    private const val NAME = "infowall"
    private const val K_URL = "url"
    private const val K_TOKEN = "token"
    private const val K_INTERVAL = "interval_sec"
    private const val K_TOP = "top_pct"
    private const val K_CLOCK = "show_clock"
    private const val K_BATTERY = "show_battery"
    private const val K_RAM = "show_ram"
    private const val K_STORAGE = "show_storage"
    private const val K_NETWORK = "show_network"

    const val MIN_INTERVAL = 15

    fun get(ctx: Context): SharedPreferences =
        ctx.applicationContext.getSharedPreferences(NAME, Context.MODE_PRIVATE)

    fun load(ctx: Context): Config {
        val p = get(ctx)
        return Config(
            url = p.getString(K_URL, "").orEmpty().trim(),
            token = p.getString(K_TOKEN, "").orEmpty().trim(),
            intervalSec = p.getInt(K_INTERVAL, 300).coerceAtLeast(MIN_INTERVAL),
            topPct = p.getInt(K_TOP, 8).coerceIn(0, 85),
            showClock = p.getBoolean(K_CLOCK, true),
            showBattery = p.getBoolean(K_BATTERY, true),
            showRam = p.getBoolean(K_RAM, true),
            showStorage = p.getBoolean(K_STORAGE, true),
            showNetwork = p.getBoolean(K_NETWORK, true),
        )
    }

    fun save(ctx: Context, c: Config) {
        get(ctx).edit()
            .putString(K_URL, c.url.trim())
            .putString(K_TOKEN, c.token.trim())
            .putInt(K_INTERVAL, c.intervalSec.coerceAtLeast(MIN_INTERVAL))
            .putInt(K_TOP, c.topPct.coerceIn(0, 85))
            .putBoolean(K_CLOCK, c.showClock)
            .putBoolean(K_BATTERY, c.showBattery)
            .putBoolean(K_RAM, c.showRam)
            .putBoolean(K_STORAGE, c.showStorage)
            .putBoolean(K_NETWORK, c.showNetwork)
            .apply()
    }
}
