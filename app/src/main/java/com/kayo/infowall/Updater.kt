package com.kayo.infowall

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * Обновление поверх установленного приложения: настройки, цели, серверы и выбранные обои сохраняются.
 * CI кладёт в релиз «latest» два файла: InfoWall.apk и version.json {versionCode, versionName, notes}.
 * Работает, только пока все сборки подписаны одним ключом (секрет KEYSTORE_B64 в репозитории).
 */
object Updater {

    private const val BASE = "https://github.com/Kayosac/OneeUIwalpapper/releases/download/latest"
    private const val PREFS = "infowall_update"

    class Info(val code: Long, val name: String, val notes: String)

    fun currentCode(ctx: Context): Long {
        val pi = ctx.packageManager.getPackageInfo(ctx.packageName, 0)
        return if (Build.VERSION.SDK_INT >= 28) pi.longVersionCode else @Suppress("DEPRECATION") pi.versionCode.toLong()
    }

    fun currentName(ctx: Context): String = ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName ?: "?"

    /** Что лежит в релизе; null — не удалось узнать. */
    @Throws(IOException::class)
    fun latest(): Info {
        val o = JSONObject(String(download("$BASE/version.json", 64 * 1024), Charsets.UTF_8))
        return Info(o.optLong("versionCode"), o.optString("versionName"), o.optString("notes"))
    }

    /** Есть ли версия новее установленной; результат запоминается для обоев. */
    fun check(ctx: Context): Info? {
        val l = runCatching { latest() }.getOrNull() ?: return null
        val newer = l.code > currentCode(ctx)
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putLong("available", if (newer) l.code else 0L)
            .putString("availableName", if (newer) l.name else "")
            .putLong("checkedAt", System.currentTimeMillis())
            .apply()
        return if (newer) l else null
    }

    /** Последний известный результат проверки (без сети). */
    fun knownUpdate(ctx: Context): String? {
        val p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val code = p.getLong("available", 0L)
        return if (code > currentCode(ctx)) p.getString("availableName", "")?.ifEmpty { "$code" } else null
    }

    fun lastCheck(ctx: Context): Long = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getLong("checkedAt", 0L)

    /** Можно ли ставить пакеты (разрешение «Установка неизвестных приложений» для InfoWall). */
    fun canInstall(ctx: Context) = ctx.packageManager.canRequestPackageInstalls()

    /**
     * Скачивает APK и отдаёт системе на установку. Android покажет своё окно «Обновить?» —
     * подтверждение одно нажатие. Вызывать не в главном потоке.
     */
    @Throws(IOException::class)
    fun downloadAndInstall(ctx: Context, onProgress: (String) -> Unit) {
        onProgress("Скачиваю…")
        val apk = File(ctx.cacheDir, "update.apk")
        apk.writeBytes(download("$BASE/InfoWall.apk", 80L * 1024 * 1024))
        onProgress("Устанавливаю…")
        val pi = ctx.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(ctx.packageName)
            if (Build.VERSION.SDK_INT >= 31) setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
        }
        val id = pi.createSession(params)
        pi.openSession(id).use { s ->
            s.openWrite("InfoWall.apk", 0, apk.length()).use { out -> apk.inputStream().use { it.copyTo(out) }; s.fsync(out) }
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0)
            val status = PendingIntent.getBroadcast(ctx, id, Intent(ctx, UpdateReceiver::class.java), flags)
            s.commit(status.intentSender)
        }
    }

    private fun download(url: String, limit: Long): ByteArray {
        var u = url
        repeat(5) {   // GitHub отдаёт файл через переадресацию на другой хост
            val c = URL(u).openConnection() as HttpURLConnection
            c.instanceFollowRedirects = false
            c.connectTimeout = 10_000
            c.readTimeout = 30_000
            c.setRequestProperty("User-Agent", "InfoWall-updater")
            try {
                val code = c.responseCode
                if (code in 300..399) { u = c.getHeaderField("Location") ?: throw IOException("переадресация без адреса"); return@repeat }
                if (code !in 200..299) throw IOException("HTTP $code")
                return c.inputStream.use { Fonts.readLimited(it, limit) }
            } finally {
                c.disconnect()
            }
        }
        throw IOException("слишком много переадресаций")
    }
}

/** Ответ установщика: если системе нужно подтверждение — показываем её окно. */
class UpdateReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        when (intent.getIntExtra(PackageInstaller.EXTRA_STATUS, -999)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                @Suppress("DEPRECATION")
                val confirm = (if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
                else intent.getParcelableExtra(Intent.EXTRA_INTENT)) ?: return
                ctx.startActivity(confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
            PackageInstaller.STATUS_SUCCESS -> Unit
            else -> {
                val msg = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE) ?: "ошибка установки"
                android.widget.Toast.makeText(ctx, "Обновление: $msg", android.widget.Toast.LENGTH_LONG).show()
            }
        }
    }
}
