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
        private var mesh: WireMesh? = null
        private var meshKey = ""
        private var fontKey = ""
        private var nextNetAt = 0L
        private var nextSlowAt = 0L
        private var lastTap = 0L

        // bloom: сцена пишется в RenderNode, поверх кладутся две размытые копии в режиме PLUS
        private var sceneNode: RenderNode? = null
        private var bloomNear: RenderNode? = null
        private var bloomFar: RenderNode? = null
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
            loadMesh()
            loadFonts()
        }

        override fun onSurfaceChanged(holder: SurfaceHolder, format: Int, w: Int, h: Int) {
            super.onSurfaceChanged(holder, format, w, h)
            width = w
            height = h
            draw()
        }

        override fun onVisibilityChanged(v: Boolean) {
            visible = v
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
            nextRemoteAt = 0L
            nextWeatherAt = 0L
            nextNewsAt = 0L
            renderer.invalidateTime()
            loadMesh()
            loadFonts()
            if (visible) {
                handler.removeCallbacks(frame)
                frame.run()
            }
        }

        /** Двойной тап по пустому месту — обновить источник и погоду. */
        override fun onCommand(action: String?, x: Int, y: Int, z: Int, extras: Bundle?, resultRequested: Boolean): Bundle? {
            if (action == WallpaperManager.COMMAND_TAP) {
                val code = if (cfg.converter) renderer.converterHit(x.toFloat(), y.toFloat()) else null
                if (code != null) {
                    openConverter(code)
                    return null
                }
                val now = SystemClock.elapsedRealtime()
                if (now - lastTap < 400) {
                    nextRemoteAt = 0L; nextWeatherAt = 0L; lastTap = 0L
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

        private fun openConverter(code: String) {
            try {
                startActivity(
                    android.content.Intent(applicationContext, ConverterActivity::class.java)
                        .putExtra(ConverterActivity.EXTRA_CODE, code)
                        .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK or android.content.Intent.FLAG_ACTIVITY_CLEAR_TASK),
                )
            } catch (_: Exception) {
                // система не дала открыть окно из фона — остаётся иконка «Конвертер» в списке приложений
            }
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

        private fun loadMesh() {
            val key = "${cfg.modelSource}|${cfg.modelVersion}"
            if (key == meshKey) return
            meshKey = key
            when (cfg.modelSource) {
                ModelSource.NONE -> mesh = null
                ModelSource.DUCK -> mesh = Meshes.duck()
                ModelSource.FILE, ModelSource.SKETCHFAB -> {
                    val ctx = applicationContext
                    submit {
                        val m = Meshes.saved(ctx) ?: Meshes.duck()
                        handler.post { if (meshKey == key) mesh = m }
                    }
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
                val fd = FrameData(cfg, if (cfg.fps <= 0) 0f else t, stats, remote, remoteError, weather, weatherNote, cfg.city, mesh, news, rates)
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

        @TargetApi(31)
        private fun drawWithBloom(c: Canvas, fd: FrameData) {
            if (Build.VERSION.SDK_INT < 31) return
            val scene = sceneNode ?: RenderNode("scene").also { sceneNode = it }
            scene.setPosition(0, 0, width, height)
            val rc = scene.beginRecording(width, height)
            try { renderer.draw(rc, width, height, fd) } finally { scene.endRecording() }

            val key = "$width|$height|${cfg.glow}"
            if (key != bloomKey) {
                bloomKey = key
                val s = minOf(width / 1280f, height / 800f)
                bloomNear = makeBloom("bloomNear", 7f * s, 0.75f * cfg.glow)
                bloomFar = makeBloom("bloomFar", 26f * s, 0.6f * cfg.glow)
            }
            c.drawRenderNode(scene)
            for (b in listOfNotNull(bloomNear, bloomFar)) {
                val r = b.beginRecording(width, height)
                try { r.drawRenderNode(scene) } finally { b.endRecording() }
                c.drawRenderNode(b)
            }
        }

        @TargetApi(31)
        private fun makeBloom(name: String, radius: Float, strength: Float): RenderNode? {
            if (Build.VERSION.SDK_INT < 31) return null
            return RenderNode(name).apply {
                setPosition(0, 0, width, height)
                setRenderEffect(RenderEffect.createBlurEffect(radius, radius, Shader.TileMode.DECAL))
                setUseCompositingLayer(true, Paint().apply {
                    blendMode = BlendMode.PLUS
                    alpha = (strength.coerceIn(0f, 1f) * 255).toInt()
                })
            }
        }
    }
}
