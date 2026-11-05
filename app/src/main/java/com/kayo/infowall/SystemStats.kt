package com.kayo.infowall

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.BatteryManager
import android.os.Environment
import android.os.StatFs
import java.util.Locale

/** Локальные метрики планшета — без каких-либо разрешений. */
object SystemStats {

    fun collect(ctx: Context, cfg: Config): List<Item> {
        val out = ArrayList<Item>(4)
        if (cfg.showBattery) safe { battery(ctx) }?.let(out::add)
        if (cfg.showRam) safe { ram(ctx) }?.let(out::add)
        if (cfg.showStorage) safe { storage() }?.let(out::add)
        if (cfg.showNetwork) safe { network(ctx) }?.let(out::add)
        return out
    }

    private fun battery(ctx: Context): Item? {
        val bm = ctx.getSystemService(BatteryManager::class.java) ?: return null
        val pct = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        if (pct < 0 || pct > 100) return null
        val charging = bm.isCharging

        // Температура есть только в «липком» broadcast
        val sticky = ctx.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val tempRaw = sticky?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Int.MIN_VALUE) ?: Int.MIN_VALUE
        val temp = if (tempRaw != Int.MIN_VALUE) " · ${tempRaw / 10}°" else ""

        val state = when {
            charging -> State.OK
            pct <= 15 -> State.BAD
            pct <= 30 -> State.WARN
            else -> State.OK
        }
        return Item(if (charging) "Батарея ⚡" else "Батарея", "$pct%$temp", state)
    }

    private fun ram(ctx: Context): Item? {
        val am = ctx.getSystemService(ActivityManager::class.java) ?: return null
        val mi = ActivityManager.MemoryInfo().also(am::getMemoryInfo)
        if (mi.totalMem <= 0) return null
        val used = mi.totalMem - mi.availMem
        val pct = (used * 100 / mi.totalMem).toInt()
        val state = when {
            pct >= 92 -> State.BAD
            pct >= 85 -> State.WARN
            else -> State.OK
        }
        return Item("Память", "${gb(used)} / ${gb(mi.totalMem)} ГБ", state)
    }

    private fun storage(): Item? {
        val fs = StatFs(Environment.getDataDirectory().path)
        val total = fs.totalBytes
        if (total <= 0) return null
        val free = fs.availableBytes
        val freePct = (free * 100 / total).toInt()
        val state = when {
            freePct < 5 -> State.BAD
            freePct < 10 -> State.WARN
            else -> State.OK
        }
        return Item("Свободно", "${gb(free)} ГБ", state)
    }

    private fun network(ctx: Context): Item {
        val cm = ctx.getSystemService(ConnectivityManager::class.java)
        val caps = cm?.activeNetwork?.let { cm.getNetworkCapabilities(it) }
            ?: return Item("Сеть", "нет", State.BAD)
        val kind = when {
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "Wi-Fi"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "Ethernet"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "Моб."
            else -> "есть"
        }
        val vpn = if (caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) " + VPN" else ""
        val online = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
        return Item("Сеть", kind + vpn, if (online) State.OK else State.WARN)
    }

    private fun gb(bytes: Long): String {
        val v = bytes / 1_073_741_824.0
        return if (v >= 100) String.format(Locale.US, "%.0f", v) else String.format(Locale.US, "%.1f", v)
    }

    private inline fun <T> safe(block: () -> T?): T? = try {
        block()
    } catch (_: Exception) {
        null
    }
}
