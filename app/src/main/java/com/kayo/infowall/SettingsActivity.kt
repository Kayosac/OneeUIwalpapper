package com.kayo.infowall

import android.Manifest
import android.app.Activity
import android.app.WallpaperManager
import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Intent
import android.location.Location
import android.location.LocationManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.text.InputType
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import java.util.Locale

/**
 * Настройки. Всё управление — кнопками: ни одно действие не требует Enter
 * на экранной клавиатуре.
 */
class SettingsActivity : Activity() {

    private lateinit var accent: EditText
    private lateinit var fps: EditText
    private lateinit var yaw: EditText
    private lateinit var zones: EditText
    private lateinit var swWeather: Switch
    private lateinit var placeStatus: TextView
    private lateinit var modelGroup: RadioGroup
    private val modelIds = HashMap<ModelSource, Int>()
    private lateinit var modelSpeed: EditText
    private lateinit var sfUrl: EditText
    private lateinit var sfToken: EditText
    private lateinit var modelStatus: TextView
    private lateinit var url: EditText
    private lateinit var token: EditText
    private lateinit var interval: EditText
    private lateinit var result: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val cfg = Prefs.load(this)
        val p = Prefs.get(this)

        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val pad = dp(20)
            setPadding(pad, pad, pad, pad)
        }

        // --- вид ---
        col += header("Вид")
        col += label("Цвет (hex), например #00E5FF")
        accent = field("#00E5FF", p.getString(Prefs.K_ACCENT, Prefs.DEFAULT_ACCENT).orEmpty(), InputType.TYPE_CLASS_TEXT)
        col += accent
        col += label("Кадров в секунду: 0 — без анимации (экономия), 10–30 — плавно")
        fps = field("20", cfg.fps.toString(), InputType.TYPE_CLASS_NUMBER); col += fps
        col += label("Поворот камеры, ° (0–34)")
        yaw = field("24", cfg.yawDeg.toInt().toString(), InputType.TYPE_CLASS_NUMBER); col += yaw

        // --- часы ---
        col += header("Часовые пояса")
        col += label("До трёх через запятую: метка=пояс")
        zones = field(Prefs.DEFAULT_ZONES, p.getString(Prefs.K_ZONES, Prefs.DEFAULT_ZONES).orEmpty(), InputType.TYPE_CLASS_TEXT)
        col += zones

        // --- погода ---
        col += header("Погода")
        swWeather = switch("Показывать прогноз на неделю", cfg.weather); col += swWeather
        col += button("Определить местоположение") { locate() }
        placeStatus = status(placeText(cfg)); col += placeStatus

        // --- модель ---
        col += header("3D-модель в центре")
        modelGroup = RadioGroup(this).apply { orientation = RadioGroup.VERTICAL }
        for ((src, title) in listOf(
            ModelSource.DUCK to "Уточка (встроенная)",
            ModelSource.FILE to "Свой файл",
            ModelSource.SKETCHFAB to "Sketchfab по ссылке",
            ModelSource.NONE to "Без модели",
        )) {
            val id = View.generateViewId()
            modelIds[src] = id
            modelGroup.addView(RadioButton(this).apply { this.id = id; text = title; minHeight = dp(44) })
        }
        modelGroup.check(modelIds.getValue(cfg.modelSource))
        col += modelGroup
        col += label("Скорость вращения (0–3)")
        modelSpeed = field("1", trim(cfg.modelSpeed), InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL)
        col += modelSpeed
        col += button("Выбрать файл .glb или .zip с glTF") { pickFile() }
        col += label("Sketchfab: ссылка на модель и личный API-токен (sketchfab.com → Settings → Password & API)")
        sfUrl = field("https://sketchfab.com/3d-models/…", cfg.sketchfabUrl, InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI)
        col += sfUrl
        sfToken = field("API-токен", cfg.sketchfabToken, InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD)
        col += sfToken
        col += button("Скачать с Sketchfab") { downloadSketchfab() }
        modelStatus = status(""); col += modelStatus

        // --- источник ---
        col += header("Источник данных для бегущей строки (необязательно)")
        url = field("URL JSON, например http://192.168.1.10:8080/status.json", cfg.url,
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI)
        col += url
        token = field("Токен (Bearer), если нужен", cfg.token,
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD)
        col += token
        col += label("Интервал обновления, сек (мин. ${Prefs.MIN_INTERVAL})")
        interval = field("300", cfg.intervalSec.toString(), InputType.TYPE_CLASS_NUMBER); col += interval
        col += button("Проверить источник") { testSource() }
        result = status(""); col += result

        col += space(dp(16))
        col += button("Сохранить") { if (save()) toast("Сохранено") }
        col += button("Сохранить и установить обои") { if (save()) setWallpaper() }
        col += label("Двойной тап по пустому месту рабочего стола — обновить погоду и источник.")

        setContentView(ScrollView(this).apply { isFillViewport = true; addView(col) })
    }

    // ---------- сохранение ----------

    private fun selectedSource(): ModelSource =
        modelIds.entries.firstOrNull { it.value == modelGroup.checkedRadioButtonId }?.key ?: ModelSource.DUCK

    private fun save(): Boolean {
        val u = url.text.toString().trim()
        if (u.isNotEmpty() && !(u.startsWith("http://") || u.startsWith("https://"))) {
            url.error = "Нужен адрес, начинающийся с http:// или https://"
            url.requestFocus()
            return false
        }
        val color = accent.text.toString().trim().let { if (it.startsWith("#")) it else "#$it" }
        if (runCatching { android.graphics.Color.parseColor(color) }.isFailure) {
            accent.error = "Цвет в виде #RRGGBB"
            accent.requestFocus()
            return false
        }
        hideKeyboard()
        Prefs.get(this).edit()
            .putString(Prefs.K_ACCENT, color)
            .putInt(Prefs.K_FPS, (fps.text.toString().toIntOrNull() ?: 20).coerceIn(0, 30))
            .putFloat(Prefs.K_YAW, (yaw.text.toString().toFloatOrNull() ?: 24f).coerceIn(0f, 34f))
            .putString(Prefs.K_ZONES, zones.text.toString().trim())
            .putBoolean(Prefs.K_WEATHER, swWeather.isChecked)
            .putString(Prefs.K_MODEL_SRC, selectedSource().name)
            .putFloat(Prefs.K_MODEL_SPEED, (modelSpeed.text.toString().replace(',', '.').toFloatOrNull() ?: 1f).coerceIn(0f, 3f))
            .putString(Prefs.K_SF_URL, sfUrl.text.toString().trim())
            .putString(Prefs.K_SF_TOKEN, sfToken.text.toString().trim())
            .putString(Prefs.K_URL, u)
            .putString(Prefs.K_TOKEN, token.text.toString().trim())
            .putInt(Prefs.K_INTERVAL, (interval.text.toString().toIntOrNull() ?: 300).coerceAtLeast(Prefs.MIN_INTERVAL))
            .apply()
        val badZones = zones.text.toString().isNotBlank() && Prefs.parseZones(zones.text.toString()).isEmpty()
        if (badZones) toast("Пояса не распознаны — пример: МСК=Europe/Moscow")
        return true
    }

    // ---------- геолокация ----------

    private fun placeText(cfg: Config): String = when {
        cfg.lat == null -> "Место не задано"
        cfg.city.isNotBlank() -> "Место: ${cfg.city}"
        else -> String.format(Locale.US, "Место: %.3f, %.3f", cfg.lat, cfg.lon)
    }

    private fun locate() {
        if (!Weather.hasPermission(this)) {
            requestPermissions(arrayOf(Manifest.permission.ACCESS_COARSE_LOCATION), REQ_LOCATION)
            return
        }
        placeStatus.text = "Определяю…"
        val lm = getSystemService(LocationManager::class.java)
        val last = Weather.lastLocation(this)
        val provider = when {
            lm == null -> null
            lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER) -> LocationManager.NETWORK_PROVIDER
            lm.isProviderEnabled(LocationManager.GPS_PROVIDER) -> LocationManager.GPS_PROVIDER
            else -> null
        }
        if (Build.VERSION.SDK_INT >= 30 && lm != null && provider != null) {
            try {
                lm.getCurrentLocation(provider, null, mainExecutor) { loc -> onLocation(loc ?: last) }
                return
            } catch (_: SecurityException) {
            }
        }
        onLocation(last)
    }

    private fun onLocation(loc: Location?) {
        if (loc == null) {
            placeStatus.text = "Не получилось определить место: включи геолокацию и попробуй ещё раз"
            return
        }
        val lat = loc.latitude
        val lon = loc.longitude
        Thread {
            val city = Weather.cityName(this, lat, lon).orEmpty()
            runOnUiThread {
                Prefs.get(this).edit()
                    .putString(Prefs.K_LAT, lat.toString())
                    .putString(Prefs.K_LON, lon.toString())
                    .putString(Prefs.K_CITY, city)
                    .apply()
                placeStatus.text = placeText(Prefs.load(this))
            }
        }.start()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_LOCATION) {
            if (Weather.hasPermission(this)) locate()
            else placeStatus.text = "Без доступа к местоположению погода работать не будет"
        }
    }

    // ---------- модель ----------

    private fun pickFile() {
        val i = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
        }
        try {
            startActivityForResult(i, REQ_FILE)
        } catch (_: ActivityNotFoundException) {
            toast("Нет приложения для выбора файлов")
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQ_FILE || resultCode != RESULT_OK) return
        val uri: Uri = data?.data ?: return
        importModel(ModelSource.FILE) {
            contentResolver.openInputStream(uri)?.use { Meshes.readAll(it) } ?: throw java.io.IOException("не открыть файл")
        }
    }

    private fun downloadSketchfab() {
        val link = sfUrl.text.toString().trim()
        val tok = sfToken.text.toString().trim()
        hideKeyboard()
        Prefs.get(this).edit().putString(Prefs.K_SF_URL, link).putString(Prefs.K_SF_TOKEN, tok).apply()
        importModel(ModelSource.SKETCHFAB) {
            Meshes.downloadSketchfab(link, tok) { msg -> runOnUiThread { modelStatus.text = msg } }
        }
    }

    private fun importModel(src: ModelSource, read: () -> ByteArray) {
        modelStatus.text = "Загружаю…"
        Thread {
            val text = try {
                val bytes = read()
                runOnUiThread { modelStatus.text = "Упрощаю модель (${bytes.size / 1024} КБ)…" }
                val mesh = Meshes.fromBytes(bytes)
                Meshes.save(this, mesh)
                val p = Prefs.get(this)
                p.edit()
                    .putString(Prefs.K_MODEL_SRC, src.name)
                    .putInt(Prefs.K_MODEL_VER, p.getInt(Prefs.K_MODEL_VER, 0) + 1)
                    .apply()
                runOnUiThread { modelGroup.check(modelIds.getValue(src)) }
                "Готово: ${mesh.vertexCount} вершин, ${mesh.edgeCount} рёбер — уже на обоях"
            } catch (e: OutOfMemoryError) {
                "Слишком тяжёлая модель для памяти планшета"
            } catch (e: Exception) {
                "Ошибка: ${e.message ?: e.javaClass.simpleName}"
            }
            runOnUiThread { if (!isFinishing) modelStatus.text = text }
        }.start()
    }

    // ---------- источник ----------

    private fun testSource() {
        val u = url.text.toString().trim()
        val tk = token.text.toString().trim()
        hideKeyboard()
        if (u.isEmpty()) { result.text = "URL пустой — бегущей строки не будет."; return }
        result.text = "Загружаю…"
        Thread {
            val text = try {
                val d = RemoteSource.fetch(u, tk)
                buildString {
                    append("OK")
                    d.title?.let { append(" · «$it»") }
                    append(" · ${d.items.size} шт.\n")
                    d.items.take(12).forEach { append("• ${it.label}: ${it.value}\n") }
                }
            } catch (e: Exception) {
                "Ошибка: ${e.message ?: e.javaClass.simpleName}"
            }
            runOnUiThread { if (!isFinishing) result.text = text }
        }.start()
    }

    private fun setWallpaper() {
        val component = ComponentName(this, InfoWallpaperService::class.java)
        try {
            startActivity(Intent(WallpaperManager.ACTION_CHANGE_LIVE_WALLPAPER)
                .putExtra(WallpaperManager.EXTRA_LIVE_WALLPAPER_COMPONENT, component))
        } catch (_: ActivityNotFoundException) {
            try {
                startActivity(Intent(WallpaperManager.ACTION_LIVE_WALLPAPER_CHOOSER))
            } catch (_: ActivityNotFoundException) {
                toast("Не нашёл экран выбора живых обоев")
            }
        }
    }

    // ---------- UI-хелперы ----------

    private operator fun LinearLayout.plusAssign(v: View) = addView(v)

    private fun dp(v: Int) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics).toInt()

    private fun trim(f: Float) = if (f % 1f == 0f) f.toInt().toString() else f.toString()

    private fun header(s: String) = TextView(this).apply {
        text = s
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
        setPadding(0, dp(22), 0, dp(6))
    }

    private fun label(s: String) = TextView(this).apply {
        text = s
        alpha = 0.75f
        setPadding(0, dp(8), 0, dp(2))
    }

    private fun status(s: String) = TextView(this).apply {
        text = s
        setTextIsSelectable(true)
        setPadding(0, dp(6), 0, dp(6))
    }

    private fun field(hint: String, value: String, type: Int) = EditText(this).apply {
        this.hint = hint
        setText(value)
        inputType = type
        isSingleLine = true
        imeOptions = EditorInfo.IME_ACTION_NEXT or EditorInfo.IME_FLAG_NO_EXTRACT_UI
    }

    private fun switch(s: String, checked: Boolean) = Switch(this).apply {
        text = s
        isChecked = checked
        gravity = Gravity.CENTER_VERTICAL
        minHeight = dp(48)
    }

    private fun button(s: String, onClick: () -> Unit) = Button(this).apply {
        text = s
        setOnClickListener { onClick() }
    }

    private fun space(h: Int) = View(this).apply { layoutParams = LinearLayout.LayoutParams(1, h) }

    private fun hideKeyboard() {
        currentFocus?.let { getSystemService(InputMethodManager::class.java)?.hideSoftInputFromWindow(it.windowToken, 0) }
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()

    companion object {
        private const val REQ_LOCATION = 41
        private const val REQ_FILE = 42
    }
}
