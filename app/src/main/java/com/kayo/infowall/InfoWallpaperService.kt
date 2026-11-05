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

class InfoWallpaperService : WallpaperService() {

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
        private val srvState = HashMap<String, SrvState>()
        private var srvBusy = false
        private var nextSrvAt = 0L
        private var serverViews: List<ServerView> = emptyList()
        private var nextNetAt = 0L
        private var nextSlowAt = 0L
        private var lastTap = 0L
        private var fxEdit: FxEdit? = null      // идёт ввод суммы на обоях

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
            io.shutdownNow()
            srvIo.shutdownNow()
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
            if (old.newsRegion != cfg.newsRegion) news = emptyList()
            if (old.servers != cfg.servers) {
                srvState.keys.retainAll(cfg.servers.map { it.url }.toSet())
                nextSrvAt = 0L
                rebuildServerViews()
            }
            nextRemoteAt = 0L
            nextWeatherAt = 0L
            nextNewsAt = 0L
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
            if (action == WallpaperManager.COMMAND_TAP) {
                val fx = fxEdit
                val tx = x.toFloat(); val ty = y.toFloat()
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
                    return null
                }
                val code = if (cfg.converter) renderer.converterHit(tx, ty) else null
                if (code != null) {
                    startFx(code)
                    redraw()
                    return null
                }
                val now = SystemClock.elapsedRealtime()
                if (now - lastTap < 400) {
                    nextRemoteAt = 0L; nextWeatherAt = 0L; nextSrvAt = 0L; lastTap = 0L
                } else lastTap = now
            }
            return null
        }

        // ---------- данные ----------

        private fun tick() {
            val now = SystemClock.elapsedRealtime()
            if (now >= nextNetAt) { stats.sampleNet(); nextNetAt = now + 1500 }
            if (now >= nextSlowAt) { stats.sampleSlow(); nextSlowAt = now + 10_000 }
            maybeFetchRemote(now)
            maybeFetchWeather(now)
            maybeFetchNews(now)
            maybeFetchRates(now)
            maybeFetchServers(now)
            // клавиатура сама закрывается через 30 с без нажатий (с сохранением набранного)
            fxEdit?.let { if (now - it.lastAt > 30_000L) closeFx(true) }
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
                ServerView(conf.name, conf.ip, st?.metrics, online, st?.error)
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
            submit {
                val r = runCatching { News.fetch(region).shuffled() }
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
            renderer.setFonts(set.clock, set.num, set.label)
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
                val fd = FrameData(cfg, if (cfg.fps <= 0) 0f else t, stats, remote, remoteError, weather, weatherNote, cfg.city, news, rates, serverViews, fxEdit)
                if (Build.VERSION.SDK_INT >= 31 && cfg.glow > 0f && c.isHardwareAccelerated) drawWithBloom(c, fd)
                else renderer.draw(c, width, height, fd)
                // чёрный низ — поверх всего, включая свечение
                renderer.drawBottomShade(c, width, height, cfg)
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
}
