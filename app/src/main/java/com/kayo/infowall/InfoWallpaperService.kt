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

    override fun onCreateEngine(): Engine = InfoEngine()

    inner class InfoEngine : Engine(), SharedPreferences.OnSharedPreferenceChangeListener {

        private val handler = Handler(Looper.getMainLooper())
        private val io: ExecutorService = Executors.newSingleThreadExecutor()
        private val renderer = Renderer()

        private lateinit var cfg: Config
        private var visible = false
        private var width = 0
        private var height = 0

        // Состояние удалённого источника (только main thread)
        private var remote: RemoteData? = null
        private var remoteError: String? = null
        private var remoteOkAt = 0L
        private var nextFetchAt = 0L      // elapsedRealtime
        private var fetching = false      // только main thread
        private var lastTap = 0L

        /** Перерисовка раз в 30 с, выровненная по часам, — смена минуты не опаздывает. */
        private val tick = object : Runnable {
            override fun run() {
                if (!visible) return
                maybeFetch()
                draw()
                val now = System.currentTimeMillis()
                handler.postDelayed(this, PERIOD_MS - now % PERIOD_MS + 50)
            }
        }

        override fun onCreate(holder: SurfaceHolder) {
            super.onCreate(holder)
            cfg = Prefs.load(applicationContext)
            Prefs.get(applicationContext).registerOnSharedPreferenceChangeListener(this)
        }

        override fun onSurfaceChanged(holder: SurfaceHolder, format: Int, w: Int, h: Int) {
            super.onSurfaceChanged(holder, format, w, h)
            width = w
            height = h
            draw()
        }

        override fun onVisibilityChanged(v: Boolean) {
            visible = v
            handler.removeCallbacks(tick)
            if (v) tick.run()   // сразу актуальные данные при каждом показе
        }

        override fun onSurfaceDestroyed(holder: SurfaceHolder) {
            visible = false
            handler.removeCallbacks(tick)
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
                remote = null
                remoteError = null
                remoteOkAt = 0L
            }
            nextFetchAt = 0L
            if (visible) {
                handler.removeCallbacks(tick)
                tick.run()
            }
        }

        /** Двойной тап по пустому месту рабочего стола — принудительное обновление. */
        override fun onCommand(
            action: String?, x: Int, y: Int, z: Int, extras: Bundle?, resultRequested: Boolean,
        ): Bundle? {
            if (action == WallpaperManager.COMMAND_TAP) {
                val now = SystemClock.elapsedRealtime()
                if (now - lastTap < DOUBLE_TAP_MS) {
                    nextFetchAt = 0L
                    maybeFetch()
                    draw()
                    lastTap = 0L
                } else {
                    lastTap = now
                }
            }
            return null
        }

        private fun maybeFetch() {
            val url = cfg.url
            if (url.isBlank() || fetching) return
            val now = SystemClock.elapsedRealtime()
            if (now < nextFetchAt) return

            fetching = true
            val token = cfg.token
            val interval = cfg.intervalSec * 1000L
            try {
                io.execute {
                    // В фоне только сеть; всё состояние меняем в main thread
                    val result = runCatching { RemoteSource.fetch(url, token) }
                    handler.post { onFetched(url, result, interval) }
                }
            } catch (_: Exception) {
                fetching = false   // executor уже остановлен
            }
        }

        private fun onFetched(url: String, result: Result<RemoteData>, interval: Long) {
            fetching = false
            if (url != cfg.url) {
                // пока шёл запрос, URL сменили — сразу грузим новый
                nextFetchAt = 0L
                if (visible) maybeFetch()
                return
            }
            result.onSuccess {
                remote = it
                remoteError = null
                remoteOkAt = System.currentTimeMillis()
            }.onFailure {
                remoteError = it.message ?: it.javaClass.simpleName
            }
            // при ошибке повторяем не позже чем через минуту
            nextFetchAt = SystemClock.elapsedRealtime() +
                if (result.isSuccess) interval else minOf(interval, RETRY_MS)
            if (visible) draw()
        }

        private fun draw() {
            if (width == 0 || height == 0) return
            val holder = surfaceHolder
            var c: Canvas? = null
            try {
                c = holder.lockHardwareCanvas() ?: return
                val sys = SystemStats.collect(applicationContext, cfg)
                renderer.draw(c, width, height, cfg, sys, remote, remoteError, remoteOkAt)
            } catch (_: Exception) {
                // поверхность могла исчезнуть между проверкой и отрисовкой
            } finally {
                if (c != null) {
                    try {
                        holder.unlockCanvasAndPost(c)
                    } catch (_: Exception) {
                    }
                }
            }
        }
    }

    companion object {
        private const val PERIOD_MS = 30_000L
        private const val RETRY_MS = 60_000L
        private const val DOUBLE_TAP_MS = 400L
    }
}
