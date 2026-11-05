package com.kayo.infowall

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.View
import android.widget.RemoteViews

/**
 * «Зона касаний» — прозрачный виджет рабочего стола поверх панелей обоев.
 *
 * Лаунчер отдаёт обоям копию ВСЕХ касаний, в том числе по иконкам в меню приложений и в папках,
 * и по ним одним не понять, что лежит сверху. Виджет решает это руками самого лаунчера:
 * тап доходит до виджета, только если над ним ничего не открыто. Виджет сообщает «тап был мой»,
 * а куда именно попал палец, обои уже знают из своего касания — и выполняют действие.
 */
class TapZoneProvider : AppWidgetProvider() {

    override fun onUpdate(ctx: Context, mgr: AppWidgetManager, ids: IntArray) {
        TapGate.render(ctx, mgr, ids)
        TapGate.refresh(ctx)
    }

    override fun onDeleted(ctx: Context, ids: IntArray) = TapGate.refresh(ctx)
    override fun onDisabled(ctx: Context) = TapGate.refresh(ctx)
    override fun onEnabled(ctx: Context) = TapGate.refresh(ctx)

    override fun onReceive(ctx: Context, intent: Intent) {
        if (intent.action == TapGate.ACTION_TAP) { TapGate.fire(); return }
        super.onReceive(ctx, intent)
    }
}

object TapGate {
    const val ACTION_TAP = "com.kayo.infowall.TAPZONE"
    const val K_FRAME = "tapzone_frame"     // показывать рамку зоны (на время настройки)

    /** Стоит ли хоть одна зона: тогда обои действуют только по её подтверждению. */
    @Volatile var active = false
        private set

    /** Время последнего подтверждения (elapsedRealtime). */
    @Volatile var lastAt = 0L
        private set

    private val main = Handler(Looper.getMainLooper())
    private val listeners = LinkedHashSet<() -> Unit>()

    fun listen(l: () -> Unit) = main.post { listeners += l }
    fun unlisten(l: () -> Unit) = main.post { listeners -= l }

    fun fire() {
        lastAt = SystemClock.elapsedRealtime()
        main.post { listeners.toList().forEach { it() } }
    }

    fun refresh(ctx: Context) {
        active = runCatching { ids(ctx).isNotEmpty() }.getOrDefault(false)
    }

    fun ids(ctx: Context): IntArray =
        AppWidgetManager.getInstance(ctx).getAppWidgetIds(ComponentName(ctx, TapZoneProvider::class.java))

    /** Перерисовать все зоны (например, включить или выключить рамку). */
    fun renderAll(ctx: Context) {
        val mgr = AppWidgetManager.getInstance(ctx)
        render(ctx, mgr, ids(ctx))
    }

    fun render(ctx: Context, mgr: AppWidgetManager, ids: IntArray) {
        if (ids.isEmpty()) return
        val frame = Prefs.get(ctx).getBoolean(K_FRAME, true)
        val pi = PendingIntent.getBroadcast(
            ctx, 0,
            Intent(ctx, TapZoneProvider::class.java).setAction(ACTION_TAP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val v = RemoteViews(ctx.packageName, R.layout.tapzone)
        v.setOnClickPendingIntent(R.id.tz_root, pi)
        v.setViewVisibility(R.id.tz_frame, if (frame) View.VISIBLE else View.GONE)
        mgr.updateAppWidget(ids, v)
    }

    /** Попросить лаунчер поставить зону на стол (One UI показывает своё окно подтверждения). */
    fun requestPin(ctx: Context): Boolean {
        val mgr = AppWidgetManager.getInstance(ctx)
        if (!mgr.isRequestPinAppWidgetSupported) return false
        return mgr.requestPinAppWidget(ComponentName(ctx, TapZoneProvider::class.java), null, null)
    }
}
