package com.kayo.infowall

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.TrafficStats
import android.os.BatteryManager
import android.os.Environment
import android.os.StatFs
import android.os.SystemClock

/** Метрики планшета без разрешений. Читаются только из main thread. */
class Stats(private val ctx: Context) {

    var batteryPct = -1; private set
    var batteryTemp: Int? = null; private set
    var charging = false; private set
    var ramUsed = 0L; private set
    var ramTotal = 0L; private set
    var romUsed = 0L; private set
    var romTotal = 0L; private set

    /** История скорости сети в Мбит/с, от старых к новым. */
    val rxHist = FloatArray(HIST)
    val txHist = FloatArray(HIST)
    var histCount = 0; private set
    var rxNow = 0f; private set
    var txNow = 0f; private set

    private var lastRx = -1L
    private var lastTx = -1L
    private var lastAt = 0L

    fun sampleSlow() {
        runCatching {
            val bm = ctx.getSystemService(BatteryManager::class.java)
            batteryPct = bm?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY) ?: -1
            charging = bm?.isCharging ?: false
            val sticky = ctx.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            val t = sticky?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Int.MIN_VALUE) ?: Int.MIN_VALUE
            batteryTemp = if (t != Int.MIN_VALUE) t / 10 else null
        }
        runCatching {
            val am = ctx.getSystemService(ActivityManager::class.java)
            val mi = ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }
            ramTotal = mi.totalMem
            ramUsed = mi.totalMem - mi.availMem
        }
        runCatching {
            val fs = StatFs(Environment.getDataDirectory().path)
            romTotal = fs.totalBytes
            romUsed = fs.totalBytes - fs.availableBytes
        }
    }

    fun sampleNet() {
        val rx = TrafficStats.getTotalRxBytes()
        val tx = TrafficStats.getTotalTxBytes()
        if (rx == TrafficStats.UNSUPPORTED.toLong() || tx == TrafficStats.UNSUPPORTED.toLong()) return
        val now = SystemClock.elapsedRealtime()
        if (lastRx >= 0 && now > lastAt) {
            val dt = (now - lastAt) / 1000f
            // счётчики могут сброситься (смена сети) — тогда пропускаем точку
            if (rx >= lastRx && tx >= lastTx && dt > 0.2f) {
                rxNow = (rx - lastRx) * 8f / 1_000_000f / dt
                txNow = (tx - lastTx) * 8f / 1_000_000f / dt
                push(rxHist, rxNow)
                push(txHist, txNow)
                if (histCount < HIST) histCount++
            }
        }
        lastRx = rx
        lastTx = tx
        lastAt = now
    }

    private fun push(arr: FloatArray, v: Float) {
        System.arraycopy(arr, 1, arr, 0, arr.size - 1)
        arr[arr.size - 1] = v
    }

    companion object {
        const val HIST = 40
    }
}
