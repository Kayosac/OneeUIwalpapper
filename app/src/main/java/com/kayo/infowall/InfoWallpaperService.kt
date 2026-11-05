package com.kayo.infowall

import android.app.WallpaperManager
import android.content.SharedPreferences
import android.graphics.Canvas
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
        private var mesh: WireMesh? = null
        private var meshKey = ""
        private var nextNetAt = 0L
        private var nextSlowAt = 0L
        private var lastTap = 0L

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
            nextRemoteAt = 0L
            nextWeatherAt = 0L
            renderer.invalidateTime()
            loadMesh()
            if (visible) {
                handler.removeCallbacks(frame)
                frame.run()
            }
        }

        /** Двойной тап по пустому месту — обновить источник и погоду. */
        override fun onCommand(action: String?, x: Int, y: Int, z: Int, extras: Bundle?, resultRequested: Boolean): Bundle? {
            if (action == WallpaperManager.COMMAND_TAP) {
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
                renderer.draw(
                    c, width, height,
                    FrameData(cfg, if (cfg.fps <= 0) 0f else t, stats, remote, remoteError, weather, weatherNote, cfg.city, mesh),
                )
            } catch (_: Exception) {
                // поверхность могла исчезнуть
            } finally {
                if (c != null) runCatching { holder.unlockCanvasAndPost(c) }
            }
        }
    }
}
