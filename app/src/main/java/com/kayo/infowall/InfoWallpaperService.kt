package com.kayo.infowall

import android.annotation.TargetApi
import android.app.WallpaperManager
import android.content.SharedPreferences
import android.graphics.BlendMode
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RenderEffect
import android.graphics.RenderNode
import android.graphics.Shader
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.service.wallpaper.WallpaperService
import android.view.SurfaceHolder
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

open class InfoWallpaperService : WallpaperService() {

    /** Вариант для экрана блокировки: только сцена, без данных, виджетов и касаний. */
    protected open val lockMode = false

    override fun onCreateEngine(): Engine = CyberEngine()

    inner class CyberEngine : Engine(), SharedPreferences.OnSharedPreferenceChangeListener {

        private val handler = Handler(Looper.getMainLooper())
        private val io: ExecutorService = Executors.newSingleThreadExecutor()
        private val renderer = CyberRenderer()
        private lateinit var stats: Stats
        private lateinit var cfg: Config

        private var visible = false
        private var width = 0
        private var height = 0
        private val startedAt = SystemClock.elapsedRealtime()

        // всё ниже меняется только в main thread
        private var remote: RemoteData? = null
        private var remoteError: String? = null
        private var nextRemoteAt = 0L
        private var remoteBusy = false
        private var weather: WeatherData? = null
        private var weatherNote: String? = null
        private var nextWeatherAt = 0L
        private var weatherBusy = false
        private var news: List<Headline> = emptyList()
        private var nextNewsAt = 0L
        private var newsBusy = false
        private var rates: Rates? = null
        private var nextRatesAt = 0L
        private var ratesBusy = false
        private var fontKey = ""
        // серверы: отдельный поток, чтобы недоступный VPS не задерживал погоду и новости
        private val srvIo: ExecutorService = Executors.newSingleThreadExecutor()
        private val steamIo: ExecutorService = Executors.newSingleThreadExecutor()
        private val srvState = HashMap<String, SrvState>()
        private var srvBusy = false
        private var nextSrvAt = 0L
        private var serverViews: List<ServerView> = emptyList()
        private var nextNetAt = 0L
        private var nextSlowAt = 0L
        private var fxEdit: FxEdit? = null      // идёт ввод суммы на обоях
        private var steam: List<TrendGame> = emptyList()
        private var steamNote: String? = null
        private var steamBusy = false
        private var nextSteamAt = 0L
        private var lastTap = 0L
        private var updateName: String? = null    // вышла версия новее — на обоях появляется метка
        private var nextUpdAt = 0L
        private var updBusy = false

        // bloom: сцена пишется в RenderNode с эффектом «сцена + размытая копия»
        private var sceneNode: RenderNode? = null
        private var bloomKey = ""

        private val frame = object : Runnable {
            override fun run() {
                if (!visible) return
                tick()
                draw()
                val fps = cfg.fps
                val delay = if (fps <= 0) 30_000L - System.currentTimeMillis() % 30_000L + 50 else 1000L / fps
                handler.postDelayed(this, delay)
            }
        }

        override fun onCreate(holder: SurfaceHolder) {
            super.onCreate(holder)
            stats = Stats(applicationContext)
            cfg = Prefs.load(applicationContext)
            Prefs.get(applicationContext).registerOnSharedPreferenceChangeListener(this)
            if (!lockMode) { TapGate.refresh(applicationContext); TapGate.listen(gateListener) }
            setTouchEventsEnabled(!lockMode)   // касания, которые лаунчер пропускает к обоям
            loadFonts()
            // остатки от 3D-моделей прошлых версий
            runCatching {
                java.io.File(filesDir, "model2.bin").delete()
                java.io.File(filesDir, "model.bin").delete()
            }
        }

        override fun onSurfaceChanged(holder: SurfaceHolder, format: Int, w: Int, h: Int) {
            super.onSurfaceChanged(holder, format, w, h)
            width = w
            height = h
            draw()
        }

        override fun onVisibilityChanged(v: Boolean) {
            visible = v
            pendAt = 0L
            if (v) TapGate.refresh(applicationContext)
            if (!v) closeFx(true)
            handler.removeCallbacks(frame)
            if (v) {
                renderer.invalidateTime()
                frame.run()
            }
        }

        override fun onSurfaceDestroyed(holder: SurfaceHolder) {
            visible = false
            handler.removeCallbacks(frame)
            super.onSurfaceDestroyed(holder)
        }

        override fun onDestroy() {
            handler.removeCallbacksAndMessages(null)
            Prefs.get(applicationContext).unregisterOnSharedPreferenceChangeListener(this)
            TapGate.unlisten(gateListener)
            io.shutdownNow()
            srvIo.shutdownNow()
            steamIo.shutdownNow()
            super.onDestroy()
        }

        override fun onSharedPreferenceChanged(p: SharedPreferences?, key: String?) {
            val old = cfg
            cfg = Prefs.load(applicationContext)
            if (old.url != cfg.url || old.token != cfg.token) {
                remote = null; remoteError = null
            }
            if (old.lat != cfg.lat || old.lon != cfg.lon || old.weather != cfg.weather) {
                weather = null; weatherNote = null
            }
            if (old.newsRegion != cfg.newsRegion || old.newsTopics != cfg.newsTopics) news = emptyList()
            // сеть дёргаем только если поменялось то, от чего зависят данные (ползунки в настройках шлют много изменений)
            val wxChanged = old.lat != cfg.lat || old.lon != cfg.lon || old.weather != cfg.weather
            val newsChanged = old.newsRegion != cfg.newsRegion || old.news != cfg.news || old.newsTopics != cfg.newsTopics
            if (old.servers != cfg.servers) {
                srvState.keys.retainAll(cfg.servers.map { it.url }.toSet())
                nextSrvAt = 0L
                rebuildServerViews()
            }
            if (old.url != cfg.url) nextRemoteAt = 0L
            if (wxChanged) nextWeatherAt = 0L
            if (newsChanged) nextNewsAt = 0L
            renderer.invalidateTime()
            loadFonts()
            if (visible) {
                handler.removeCallbacks(frame)
                frame.run()
            }
        }

        /**
         * Тап по строке конвертера — ввод суммы своей клавиатурой прямо на обоях.
         * Двойной тап по пустому месту — обновить источник и погоду.
         */
        override fun onCommand(action: String?, x: Int, y: Int, z: Int, extras: Bundle?, resultRequested: Boolean): Bundle? {
            // запасной путь: лаунчеры, которые шлют COMMAND_TAP; если тот же тап уже поймали касанием — пропускаем
            if (!lockMode && action == WallpaperManager.COMMAND_TAP && SystemClock.elapsedRealtime() - lastTouchTapAt > 600) {
                onRawTap(x.toFloat(), y.toFloat())
            }
            return null
        }

        // ---------- касания: свой детектор тапа (One UI Home не присылает COMMAND_TAP) ----------
        //
        // Лаунчер отдаёт обоям копию ВСЕХ касаний — и тех, что пришлись на иконки в меню приложений,
        // папках, контекстных меню. Если на столе стоит «зона касаний» (TapZone.kt), действие выполняется
        // только когда лаунчер подтвердил, что тап достался ей, то есть сверху ничего не открыто.
        // Без зоны — по-старому, по любому тапу. Клавиатура суммы, пока открыта, берёт тапы сразу.

        private var downX = 0f
        private var downY = 0f
        private var downAt = 0L
        private var downValid = false
        private var lastTouchTapAt = 0L
        private var pendX = 0f
        private var pendY = 0f
        private var pendAt = 0L          // тап ждёт подтверждения зоны; 0 — нет
        private var gateUsed = 0L        // подтверждение, которое уже потратили
        private val gateListener: () -> Unit = { onGate() }

        private fun onRawTap(x: Float, y: Float) {
            val now = SystemClock.elapsedRealtime()
            val fx = fxEdit
            if (fx != null && (renderer.padHit(x, y) != null || renderer.converterHit(x, y) != null)) {
                pendAt = 0L
                handleTap(x, y)
                return
            }
            if (!TapGate.active) { handleTap(x, y); return }
            // подтверждение могло прийти раньше нашего касания
            val g = TapGate.lastAt
            if (g > gateUsed && now - g < GATE_MS) { gateUsed = g; pendAt = 0L; handleTap(x, y); return }
            pendX = x; pendY = y; pendAt = now
        }

        private fun onGate() {
            if (!visible || lockMode) return
            val g = TapGate.lastAt
            if (pendAt != 0L && g - pendAt in -GATE_MS..GATE_MS && g > gateUsed) {
                gateUsed = g; pendAt = 0L
                handleTap(pendX, pendY)
            }
        }

        override fun onTouchEvent(e: android.view.MotionEvent) {
            if (lockMode) { super.onTouchEvent(e); return }
            val slop = 14f * resources.displayMetrics.density
            when (e.actionMasked) {
                android.view.MotionEvent.ACTION_DOWN -> {
                    downX = e.x; downY = e.y; downAt = SystemClock.elapsedRealtime(); downValid = true
                }
                android.view.MotionEvent.ACTION_MOVE ->
                    if (downValid && (kotlin.math.abs(e.x - downX) > slop || kotlin.math.abs(e.y - downY) > slop)) {
                        downValid = false
                        // свайп (меню приложений, листание экранов) — открытую клавиатуру закрыть с сохранением
                        if (fxEdit != null) { closeFx(true); redraw() }
                    }
                android.view.MotionEvent.ACTION_POINTER_DOWN, android.view.MotionEvent.ACTION_CANCEL -> downValid = false
                android.view.MotionEvent.ACTION_UP -> {
                    val quick = SystemClock.elapsedRealtime() - downAt < 450
                    if (downValid && quick) {
                        lastTouchTapAt = SystemClock.elapsedRealtime()
                        onRawTap(downX, downY)
                    }
                    downValid = false
                }
            }
            super.onTouchEvent(e)
        }

        private fun handleTap(tx: Float, ty: Float) {
            renderer.tapAt(tx, ty)       // кольцо-отклик: видно, что тап дошёл
            run {
                val fx = fxEdit
                if (fx != null) {
                    val key = renderer.padHit(tx, ty)
                    val row = renderer.converterHit(tx, ty)
                    when {
                        key != null -> if (key.isNotEmpty()) onFxKey(fx, key)
                        row == fx.code -> closeFx(true)
                        row != null -> { closeFx(true); startFx(row) }
                        else -> closeFx(true)            // тап мимо — сохранить и закрыть
                    }
                    redraw()
                    return@run
                }
                val code = if (cfg.converter) renderer.converterHit(tx, ty) else null
                if (code != null) {
                    startFx(code)
                    redraw()
                    return@run
                }
                when (val hit = renderer.appHit(tx, ty)) {
                    "clock" -> { openClock(); return@run }
                    "calendar" -> { openCalendar(); return@run }
                    "memory" -> { openMemory(); return@run }
                    "goals" -> { launchFirst(android.content.Intent(applicationContext, GoalsActivity::class.java)); return@run }
                    "update" -> { launchFirst(android.content.Intent(applicationContext, SettingsActivity::class.java)); return@run }
                    "steamnext" -> { refreshSteam(); return@run }
                    null -> {}
                    else -> {
                        val h = hit ?: ""
                        // игра — страница в Steam в Chrome; сервер — личный кабинет хостинга в Chrome
                        val url = when {
                            h.isEmpty() -> null
                            h.startsWith("steam:") -> h.removePrefix("steam:").toIntOrNull()?.let { "https://steamdb.info/app/$it/" }
                            h.startsWith("srv:") -> h.removePrefix("srv:").toIntOrNull()?.let { serverViews.getOrNull(it)?.panel }
                            else -> null
                        }
                        // кнопка «формулы» — переключить плоскость (настройка, обои подхватят её сами)
                        h.removePrefix("math:").takeIf { h.startsWith("math:") }?.toIntOrNull()?.let { i ->
                            Prefs.get(applicationContext).edit().putInt(Prefs.K_MATH_FN, i).apply()
                            return@run
                        }
                        if (url != null) { openInChrome(url); return@run }
                    }
                }
                // двойной тап по пустому месту — обновить погоду и серверы
                val now = SystemClock.elapsedRealtime()
                if (now - lastTap < 400) {
                    nextRemoteAt = 0L; nextWeatherAt = 0L; nextSrvAt = 0L; lastTap = 0L
                } else lastTap = now
            }
            redraw()
        }

        // ---------- данные ----------

        private fun tick() {
            if (lockMode) return   // экран блокировки: ни сети, ни метрик — только летящая сцена
            val now = SystemClock.elapsedRealtime()
            if (now >= nextNetAt) { stats.sampleNet(); nextNetAt = now + 1500 }
            if (now >= nextSlowAt) { stats.sampleSlow(); nextSlowAt = now + 10_000 }
            maybeFetchRemote(now)
            maybeFetchWeather(now)
            maybeFetchNews(now)
            maybeFetchRates(now)
            maybeFetchServers(now)
            maybeFetchSteam(now)
            maybeCheckUpdate(now)
            // клавиатура сама закрывается через 30 с без нажатий (с сохранением набранного)
            fxEdit?.let { if (now - it.lastAt > 30_000L) closeFx(true) }
        }

        /** Проверка обновления: раз в 6 часов, пока обои видны (маленький json с GitHub). */
        private fun maybeCheckUpdate(now: Long) {
            if (updBusy || now < nextUpdAt) return
            updBusy = true
            val ctx = applicationContext
            submit {
                runCatching { Updater.check(ctx) }
                val name = runCatching { Updater.knownUpdate(ctx) }.getOrNull()
                handler.post {
                    updBusy = false
                    updateName = name
                    nextUpdAt = SystemClock.elapsedRealtime() + 6 * 3600_000L
                }
            }
        }

        /**
         * Детектор взлётов Steam: раз в час (или по ⟳) и только пока обои видны. Отдельный поток.
         * При запуске сразу показываем прошлый список с диска, свежий подменяет его, когда готов.
         */
        private fun maybeFetchSteam(now: Long) {
            if (!cfg.steam || steamBusy || now < nextSteamAt) return
            steamBusy = true
            val ctx = applicationContext
            val empty = steam.isEmpty()
            try {
                steamIo.execute {
                    if (empty) {
                        val last = runCatching { SteamTrends.cached(ctx) }.getOrDefault(emptyList())
                        if (last.isNotEmpty()) handler.post { if (steam.isEmpty()) { steam = last; redraw() } }
                    }
                    val r = runCatching { SteamTrends.fetch(ctx) }
                    handler.post {
                        steamBusy = false
                        r.onSuccess { steam = it; steamNote = null }
                            .onFailure { steamNote = it.message ?: "нет связи" }
                        nextSteamAt = SystemClock.elapsedRealtime() + if (r.isSuccess) 60 * 60_000L else 5 * 60_000L
                        redraw()
                    }
                }
            } catch (_: Exception) {
                steamBusy = false
            }
        }

        /** ⟳ в шапке виджета: собрать список заново прямо сейчас (если сбор уже идёт — ничего). */
        private fun refreshSteam() {
            if (steamBusy) return
            nextSteamAt = 0L
            maybeFetchSteam(SystemClock.elapsedRealtime())
        }

        private inner class SrvState {
            var metrics: ServerMetrics? = null
            var lastOk = 0L
            var fails = 0
            var error: String? = null
        }

        /** Опрос раз в 30 с и только пока обои видны; после сбоя — повтор через 10 с. */
        private fun maybeFetchServers(now: Long) {
            val list = cfg.servers
            if (list.isEmpty() || srvBusy || now < nextSrvAt) return
            srvBusy = true
            try {
                srvIo.execute {
                    val results = list.map { it to runCatching { Servers.fetch(it) } }
                    handler.post {
                        srvBusy = false
                        var anyFail = false
                        val t = SystemClock.elapsedRealtime()
                        for ((conf, r) in results) {
                            val st = srvState.getOrPut(conf.url) { SrvState() }
                            r.onSuccess { st.metrics = it; st.lastOk = t; st.fails = 0; st.error = null }
                                .onFailure { st.fails++; st.error = it.message ?: it.javaClass.simpleName; anyFail = true }
                        }
                        rebuildServerViews()
                        nextSrvAt = t + if (anyFail) 10_000L else 30_000L
                    }
                }
            } catch (_: Exception) {
                srvBusy = false
            }
        }

        private fun rebuildServerViews() {
            val t = SystemClock.elapsedRealtime()
            serverViews = cfg.servers.map { conf ->
                val st = srvState[conf.url]
                val online = when {
                    st == null -> null                                        // ещё не опрашивали
                    st.fails == 0 && st.lastOk > 0 -> true
                    st.fails >= 2 || st.lastOk == 0L || t - st.lastOk > 90_000L -> false
                    else -> true                                              // один сбой подряд — ещё не тревога
                }
                ServerView(conf.name, conf.ip, st?.metrics, online, st?.error, conf.panel)
            }
        }

        private fun maybeFetchRemote(now: Long) {
            val url = cfg.url
            if (url.isBlank() || remoteBusy || now < nextRemoteAt) return
            remoteBusy = true
            val token = cfg.token
            val interval = cfg.intervalSec * 1000L
            submit {
                val r = runCatching { RemoteSource.fetch(url, token) }
                handler.post {
                    remoteBusy = false
                    if (url == cfg.url) {
                        r.onSuccess { remote = it; remoteError = null }
                            .onFailure { remoteError = it.message ?: it.javaClass.simpleName }
                    }
                    nextRemoteAt = SystemClock.elapsedRealtime() + if (r.isSuccess) interval else minOf(interval, 60_000L)
                }
            }
        }

        // ---------- открыть приложение по тапу ----------

        /** Пробует интенты по очереди: первый, для которого нашлось приложение, открывается. */
        private fun launchFirst(vararg intents: android.content.Intent) {
            // обои для Android — фоновое приложение; без этого разрешения запуск молча блокируется
            if (Build.VERSION.SDK_INT >= 23 && !android.provider.Settings.canDrawOverlays(applicationContext)) {
                android.widget.Toast.makeText(applicationContext,
                    "InfoWall: разреши «Поверх других приложений» — Настройки обоев → «Открытие приложений»",
                    android.widget.Toast.LENGTH_LONG).show()
            }
            for (i in intents) {
                try {
                    startActivity(i.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK or android.content.Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED))
                    return
                } catch (_: android.content.ActivityNotFoundException) {
                    // нет такого приложения — следующий вариант
                } catch (_: SecurityException) {
                }
            }
        }

        /** Ссылка в Chrome; если Chrome нет — в браузере по умолчанию. */
        private fun openInChrome(url: String) {
            val uri = android.net.Uri.parse(url)
            launchFirst(
                android.content.Intent(android.content.Intent.ACTION_VIEW, uri).setPackage("com.android.chrome"),
                android.content.Intent(android.content.Intent.ACTION_VIEW, uri).addCategory(android.content.Intent.CATEGORY_BROWSABLE),
            )
        }

        private fun mainOf(pkg: String) = android.content.Intent(android.content.Intent.ACTION_MAIN)
            .addCategory(android.content.Intent.CATEGORY_LAUNCHER).setPackage(pkg)

        private fun openCalendar() = launchFirst(
            mainOf("com.google.android.calendar"),
            android.content.Intent.makeMainSelectorActivity(android.content.Intent.ACTION_MAIN, android.content.Intent.CATEGORY_APP_CALENDAR),
            mainOf("com.samsung.android.calendar"),
        )

        /**
         * Экран памяти «Обслуживания устройства» Samsung: там одна кнопка «Очистить».
         * Чужие процессы обычному приложению закрывать нельзя (с Android 14), поэтому только ярлык.
         * Пробуем известные адреса экрана памяти, затем главный экран «Обслуживания», затем настройки.
         */
        private fun openMemory() {
            val lool = "com.samsung.android.lool"
            fun cmp(cls: String) = android.content.Intent().setClassName(lool, cls)
            launchFirst(
                android.content.Intent("com.samsung.android.sm.ACTION_RAM"),
                cmp("com.samsung.android.sm.ui.ram.RamActivity"),
                cmp("com.samsung.android.sm.ram.ui.RamActivity"),
                android.content.Intent("com.samsung.android.sm.ACTION_DASHBOARD"),
                cmp("com.samsung.android.sm.ui.dashboard.SmartManagerDashBoardActivity"),
                mainOf(lool),
                android.content.Intent(android.provider.Settings.ACTION_SETTINGS),
            )
        }

        private fun openClock() = launchFirst(
            mainOf("com.sec.android.app.clockpackage"),           // Часы Samsung
            android.content.Intent(android.provider.AlarmClock.ACTION_SHOW_ALARMS),
            mainOf("com.google.android.deskclock"),
        )

        // ---------- ввод суммы на обоях ----------

        private fun startFx(code: String) {
            val cur = rates?.convert(cfg.fxAmount, cfg.fxFrom, code) ?: if (code == cfg.fxFrom) cfg.fxAmount else 1.0
            fxEdit = FxEdit(code, FxEdit.plain(cur)).apply { lastAt = SystemClock.elapsedRealtime() }
        }

        private fun onFxKey(fx: FxEdit, key: String) {
            fx.flashKey = key
            fx.flashAt = System.currentTimeMillis()
            fx.lastAt = SystemClock.elapsedRealtime()
            when {
                key == FxEdit.KEY_OK -> closeFx(true)
                key == FxEdit.KEY_CUR -> fx.currencyMode = true
                key == FxEdit.KEY_BACK -> fx.currencyMode = false
                key.startsWith(FxEdit.CUR_PREFIX) -> swapCurrency(fx, key.removePrefix(FxEdit.CUR_PREFIX))
                else -> fx.press(key)
            }
            // погасить подсветку клавиши даже без анимации
            handler.postDelayed({ redraw() }, 200)
        }

        /** Валюта строки меняется на выбранную; если она уже была в списке — меняются местами. */
        private fun swapCurrency(fx: FxEdit, picked: String) {
            fx.currencyMode = false
            if (picked == fx.code) return
            val list = RatesSource.parseCodes(cfg.currencies).toMutableList()
            val at = list.indexOf(fx.code)
            val was = list.indexOf(picked)
            if (was >= 0) list[was] = fx.code
            if (at >= 0) list[at] = picked else list.add(0, picked)
            val typed = fx.value() ?: 0.0
            val conv = rates?.convert(typed, fx.code, picked)
            fx.code = picked
            fx.input = conv?.let { FxEdit.plain(it) } ?: fx.input
            fx.fresh = true
            Prefs.get(applicationContext).edit()
                .putString(Prefs.K_CURRENCIES, list.joinToString(", "))
                .putString(Prefs.K_FX_FROM, picked)
                .putString(Prefs.K_FX_AMOUNT, (conv ?: typed).toString())
                .apply()
        }

        private fun closeFx(save: Boolean) {
            val fx = fxEdit ?: return
            fxEdit = null
            val v = fx.value()
            if (save && v != null && !fx.fresh) {
                Prefs.get(applicationContext).edit()
                    .putString(Prefs.K_FX_AMOUNT, v.toString())
                    .putString(Prefs.K_FX_FROM, fx.code)
                    .apply()
            }
        }

        private fun redraw() {
            if (!visible) return
            handler.removeCallbacks(frame)
            frame.run()
        }

        private fun maybeFetchRates(now: Long) {
            if (!cfg.converter || ratesBusy || now < nextRatesAt) return
            if (rates == null) rates = RatesSource.cached(applicationContext)
            val fresh = rates?.let { System.currentTimeMillis() - it.fetchedAt < RatesSource.STALE_MS } ?: false
            if (fresh) { nextRatesAt = now + 30 * 60_000L; return }
            ratesBusy = true
            val ctx = applicationContext
            submit {
                val r = runCatching { RatesSource.fetch(ctx) }
                handler.post {
                    ratesBusy = false
                    r.onSuccess { rates = it }
                    nextRatesAt = SystemClock.elapsedRealtime() + if (r.isSuccess) RatesSource.STALE_MS else 5 * 60_000L
                }
            }
        }

        private fun maybeFetchNews(now: Long) {
            if (!cfg.news || newsBusy || now < nextNewsAt) return
            newsBusy = true
            val region = cfg.newsRegion
            val topics = cfg.newsTopics
            submit {
                val r = runCatching { News.fetch(region, topics) }
                handler.post {
                    newsBusy = false
                    r.onSuccess { if (it.isNotEmpty()) news = it }
                    nextNewsAt = SystemClock.elapsedRealtime() + if (r.isSuccess) 20 * 60_000L else 3 * 60_000L
                }
            }
        }

        private fun maybeFetchWeather(now: Long) {
            if (!cfg.weather || weatherBusy || now < nextWeatherAt) return
            weatherBusy = true
            val ctx = applicationContext
            val savedLat = cfg.lat
            val savedLon = cfg.lon
            submit {
                // свежая точка, если система её даёт; иначе — сохранённая в настройках
                val loc = runCatching { Weather.lastLocation(ctx) }.getOrNull()
                val lat = loc?.latitude ?: savedLat
                val lon = loc?.longitude ?: savedLon
                val result = if (lat == null || lon == null) {
                    Result.failure(IllegalStateException("место не определено — открой настройки"))
                } else runCatching { Weather.fetch(lat, lon) }
                handler.post {
                    weatherBusy = false
                    result.onSuccess { weather = it; weatherNote = null }
                        .onFailure { weatherNote = it.message ?: "нет связи" }
                    nextWeatherAt = SystemClock.elapsedRealtime() + if (result.isSuccess) 30 * 60_000L else 5 * 60_000L
                }
            }
        }

        private fun loadFonts() {
            val key = "${cfg.font}|${cfg.fontVersion}"
            if (key == fontKey) return
            fontKey = key
            val set = Fonts.load(applicationContext, cfg.font)
            renderer.setFonts(set.clock, set.num, set.label, set.hud)
        }

        private fun submit(block: () -> Unit) {
            try {
                io.execute(block)
            } catch (_: Exception) {
                // executor уже остановлен
            }
        }

        // ---------- кадр ----------

        private fun draw() {
            if (width == 0 || height == 0) return
            val holder = surfaceHolder
            var c: Canvas? = null
            try {
                c = holder.lockHardwareCanvas() ?: return
                val t = (SystemClock.elapsedRealtime() - startedAt) / 1000f
                val fd = FrameData(cfg, if (cfg.fps <= 0) 0f else t, stats, remote, remoteError, weather, weatherNote, cfg.city, news, rates, serverViews, fxEdit, steam, steamNote, updateName, steamBusy, lockMode)
                if (Build.VERSION.SDK_INT >= 31 && cfg.glow > 0f && c.isHardwareAccelerated) drawWithBloom(c, fd)
                else renderer.draw(c, width, height, fd)
                if (!lockMode) {
                    // чёрный низ — поверх всего, включая свечение (на экране блокировки дока нет)
                    renderer.drawBottomShade(c, width, height, cfg)
                    if (renderer.drawTapFx(c)) handler.postDelayed({ redraw() }, 16)   // анимация кольца даже при FPS 0
                }
            } catch (_: Exception) {
                // поверхность могла исчезнуть
            } finally {
                if (c != null) runCatching { holder.unlockCanvasAndPost(c) }
            }
        }

        /**
         * Свечение: один RenderNode со сценой и эффектом «сцена + размытая копия» (BlendMode.PLUS).
         * Сила — яркость размытой копии через цветовую матрицу, радиус тоже растёт с силой.
         */
        @TargetApi(31)
        private fun drawWithBloom(c: Canvas, fd: FrameData) {
            if (Build.VERSION.SDK_INT < 31) return
            val node = sceneNode ?: RenderNode("scene").also { sceneNode = it }
            val key = "$width|$height|${cfg.glow}"
            if (key != bloomKey) {
                bloomKey = key
                node.setPosition(0, 0, width, height)
                node.setRenderEffect(bloomEffect())
            }
            val rc = node.beginRecording(width, height)
            try { renderer.draw(rc, width, height, fd) } finally { node.endRecording() }
            c.drawRenderNode(node)
        }

        @TargetApi(31)
        private fun bloomEffect(): RenderEffect {
            val s = minOf(width / 1280f, height / 800f)
            val g = cfg.glow.coerceIn(0f, 2f)
            fun glowLayer(radius: Float, gain: Float): RenderEffect {
                val k = gain * g
                val m = android.graphics.ColorMatrix(floatArrayOf(
                    k, 0f, 0f, 0f, 0f,
                    0f, k, 0f, 0f, 0f,
                    0f, 0f, k, 0f, 0f,
                    0f, 0f, 0f, 1f, 0f,
                ))
                return RenderEffect.createColorFilterEffect(
                    android.graphics.ColorMatrixColorFilter(m),
                    RenderEffect.createBlurEffect(radius * s * (0.6f + 0.4f * g), radius * s * (0.6f + 0.4f * g), Shader.TileMode.DECAL),
                )
            }
            val halo = RenderEffect.createBlendModeEffect(glowLayer(26f, 0.55f), glowLayer(7f, 0.7f), BlendMode.PLUS)
            // исходная сцена (нулевой сдвиг) + свечение поверх
            return RenderEffect.createBlendModeEffect(RenderEffect.createOffsetEffect(0f, 0f), halo, BlendMode.PLUS)
        }
    }

    companion object {
        /** Сколько тап обоев и подтверждение зоны касаний могут расходиться во времени. */
        const val GATE_MS = 700L
    }
}
