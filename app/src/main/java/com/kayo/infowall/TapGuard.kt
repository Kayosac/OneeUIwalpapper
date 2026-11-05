package com.kayo.infowall

import android.accessibilityservice.AccessibilityService
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Rect
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo

/**
 * «Страж касаний» — служба специальных возможностей, которая отвечает на один вопрос:
 * «под этой точкой экрана сейчас есть что-то от лаунчера или другое окно?».
 *
 * Лаунчер отдаёт обоям копию ВСЕХ касаний, в том числе по иконкам в меню приложений, в папках,
 * в контекстных меню. Обои спрашивают стража в момент тапа: если в точке лежит иконка, папка,
 * всплывающее меню, поиск или чужое окно — тап принадлежит им, обои ничего не делают.
 * Если там только пустая страница рабочего стола — тап наш.
 *
 * Служба смотрит дерево окон только в момент тапа по обоям и только вокруг точки тапа;
 * ничего не сохраняет, не отправляет и не нажимает, событий не обрабатывает.
 */
class TapGuardService : AccessibilityService() {

    override fun onServiceConnected() { instance = this }
    /**
     * Нажатия лаунчера (иконка, папка, пункт меню, долгое нажатие) и смена окон (открылось приложение,
     * всплыла папка): обои отменяют свой тап, если такое пришло сразу после него. Запоминаем только время
     * и тип — без текста.
     */
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val e = event ?: return
        val pkg = e.packageName?.toString() ?: return
        if (pkg == packageName) return                       // свои окна — их отсекает OwnUi
        val launcher = pkg == homePackage()
        val hit = when (e.eventType) {
            AccessibilityEvent.TYPE_VIEW_CLICKED, AccessibilityEvent.TYPE_VIEW_LONG_CLICKED -> launcher
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> true
            else -> false
        }
        if (hit) {
            lastLauncherEventAt = android.os.SystemClock.uptimeMillis()
            lastEvent = "${if (launcher) "лаунчер" else pkg} · ${AccessibilityEvent.eventTypeToString(e.eventType)} · ${e.className?.toString()?.substringAfterLast('.')}"
        }
    }
    override fun onInterrupt() {}
    override fun onUnbind(intent: Intent?): Boolean { if (instance === this) instance = null; return super.onUnbind(intent) }
    override fun onDestroy() { if (instance === this) instance = null; super.onDestroy() }

    private var home: String? = null

    private fun homePackage(): String? {
        home?.let { return it }
        val ri = packageManager.resolveActivity(
            Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME), PackageManager.MATCH_DEFAULT_ONLY,
        )
        return ri?.activityInfo?.packageName?.also { home = it }
    }

    /**
     * true — в точке (экранные пиксели) лежит интерфейс лаунчера или другое окно: тап не обоям.
     * false — только пустая страница рабочего стола. null — не удалось понять (тогда обои решают сами).
     */
    fun ownsPoint(x: Int, y: Int): Boolean? = runCatching {
        val dm = resources.displayMetrics
        val big = 0.4 * dm.widthPixels * dm.heightPixels        // «фон»: страница стола, вся раскладка
        val pad = (14 * dm.density).toInt()                      // зазоры между иконками в меню приложений
        val r = Rect()
        val top = windows
            .filter { it.type != AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY }
            .sortedByDescending { it.layer }
            .firstOrNull { w -> w.getBoundsInScreen(r); r.contains(x, y) }
            ?: return@runCatching false
        val root = top.root ?: return@runCatching true
        if (root.packageName?.toString() != homePackage()) {          // строка состояния, клавиатура, чужое окно
            lastHit = "окно ${root.packageName}"
            return@runCatching true
        }
        hit(root, x, y, big, pad, r, 0).also { if (!it) lastHit = "пусто — тап обоям" }
    }.getOrNull()

    private fun hit(n: AccessibilityNodeInfo, x: Int, y: Int, big: Double, pad: Int, r: Rect, depth: Int): Boolean {
        if (depth > 40 || !n.isVisibleToUser) return false
        n.getBoundsInScreen(r)
        val area = r.width().toDouble() * r.height()
        if (area in 1.0..big) {
            // небольшой элемент лаунчера рядом с точкой: иконка, подпись, папка, меню, поле поиска
            val interactive = n.isClickable || n.isLongClickable || n.isCheckable || n.isEditable ||
                !n.text.isNullOrEmpty() || !n.contentDescription.isNullOrEmpty()
            if (interactive) {
                r.inset(-pad, -pad)
                if (r.contains(x, y)) {
                    // для проверки в настройках: что именно перекрыло тап (класс и id, без текста)
                    lastHit = listOfNotNull(n.className?.toString()?.substringAfterLast('.'), n.viewIdResourceName?.substringAfterLast('/'))
                        .joinToString(" · ")
                    return true
                }
            }
        }
        for (i in 0 until n.childCount) {
            val c = n.getChild(i) ?: continue
            if (hit(c, x, y, big, pad, r, depth + 1)) return true
        }
        return false
    }

    companion object {
        @Volatile var instance: TapGuardService? = null
            private set

        /** Время последнего нажатия в лаунчере или смены окна (uptimeMillis). */
        @Volatile var lastLauncherEventAt = 0L

        /** Последнее такое событие — для строки статуса в настройках. */
        @Volatile var lastEvent: String = ""

        /** Что оказалось под последним тапом по обоям — для строки статуса в настройках. */
        @Volatile var lastHit: String = ""

        /** Включена ли служба в «Специальных возможностях» (даже если ещё не подключилась). */
        fun enabled(ctx: Context): Boolean {
            val me = ComponentName(ctx, TapGuardService::class.java).flattenToString()
            val list = android.provider.Settings.Secure.getString(
                ctx.contentResolver, android.provider.Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
            ).orEmpty()
            return list.split(':').any { it.equals(me, ignoreCase = true) }
        }
    }
}
