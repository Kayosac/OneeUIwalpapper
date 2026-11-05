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
    private lateinit var glow: EditText
    private lateinit var uiScale: EditText
    private lateinit var shadeSolid: EditText
    private lateinit var shadeFade: EditText
    private lateinit var yaw: EditText
    private lateinit var zones: EditText
    private lateinit var swWeather: Switch
    private lateinit var placeStatus: TextView
    private lateinit var fontGroup: RadioGroup
    private val fontIds = HashMap<FontPreset, Int>()
    private lateinit var fontStatus: TextView
    private lateinit var swConverter: Switch
    private lateinit var curList: LinearLayout
    private val curCodes = ArrayList<String>()
    private lateinit var swNews: Switch
    private lateinit var newsRegion: EditText
    private lateinit var servers: EditText
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
        col += label("Свечение (bloom): 0 — выкл, 1 — норма, до 2 — сильнее")
        glow = field("1", trim(cfg.glow), InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL); col += glow
        col += label("Масштаб интерфейса: 0.6–1 (0.8 — как в макете)")
        uiScale = field("0.8", trim(cfg.uiScale), InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL); col += uiScale
        col += label("Чёрный низ под доком, % высоты экрана (подгони под свой док)")
        shadeSolid = field("9.3", trim(cfg.shadeSolid * 100f), InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL); col += shadeSolid
        col += label("Переход из чёрного в прозрачность над доком, % высоты")
        shadeFade = field("11.2", trim(cfg.shadeFade * 100f), InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL); col += shadeFade
        col += label("Поворот камеры, ° (0–34)")
        yaw = field("24", cfg.yawDeg.toInt().toString(), InputType.TYPE_CLASS_NUMBER); col += yaw

        // --- шрифт ---
        col += header("Шрифт")
        fontGroup = RadioGroup(this).apply { orientation = RadioGroup.VERTICAL }
        for (fp in FontPreset.values()) {
            val id = View.generateViewId()
            fontIds[fp] = id
            fontGroup.addView(RadioButton(this).apply { this.id = id; text = fp.title; minHeight = dp(44) })
        }
        fontGroup.check(fontIds.getValue(cfg.font))
        col += fontGroup
        col += button("Выбрать свой шрифт (.ttf / .otf)") { pickFont() }
        fontStatus = status(""); col += fontStatus

        // --- часы ---
        col += header("Часовые пояса")
        col += label("До трёх через запятую: метка=пояс (метки латиницей — так их рисует шрифт цифр)")
        zones = field(Prefs.DEFAULT_ZONES, p.getString(Prefs.K_ZONES, Prefs.DEFAULT_ZONES).orEmpty(), InputType.TYPE_CLASS_TEXT)
        col += zones

        // --- погода ---
        col += header("Погода")
        swWeather = switch("Показывать прогноз на неделю", cfg.weather); col += swWeather
        col += button("Определить местоположение") { locate() }
        placeStatus = status(placeText(cfg)); col += placeStatus


        // --- курсы ---
        col += header("Курсы валют")
        swConverter = switch("Конвертер на экране (тап по строке — клавиатура прямо на обоях)", cfg.converter); col += swConverter
        col += label("До ${RatesSource.MAX_CODES} валют. Тап по валюте — заменить, стрелки — порядок. Меняется на обоях сразу.")
        curCodes += RatesSource.parseCodes(cfg.currencies)
        curList = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        col += curList
        renderCurrencies()
        col += button("Открыть конвертер") { startActivity(Intent(this, ConverterActivity::class.java)) }

        // --- новости ---
        col += header("Бегущая строка")
        swNews = switch("Новости Google на русском", cfg.news); col += swNews
        col += label("Регион подборки: RU, KZ, BY, UA… (две буквы)")
        newsRegion = field("RU", cfg.newsRegion, InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS)
        col += newsRegion

        // --- серверы ---
        col += header("Серверы (до ${Servers.MAX})")
        col += label("На сервере: curl -fsSL https://raw.githubusercontent.com/Kayosac/OneeUIwalpapper/main/agent/install.sh | sudo bash — и вставь сюда строки iwa1|…, по одной на строку")
        servers = field("iwa1|имя|https://IP:порт|токен|отпечаток", p.getString(Prefs.K_SERVERS, "").orEmpty(),
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS).apply {
            isSingleLine = false; minLines = 2; maxLines = 4
        }
        col += servers

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

    private fun selectedFont(): FontPreset =
        fontIds.entries.firstOrNull { it.value == fontGroup.checkedRadioButtonId }?.key ?: FontPreset.FINAL

    private fun pickFont() {
        val i = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
        }
        try {
            startActivityForResult(i, REQ_FONT)
        } catch (_: ActivityNotFoundException) {
            toast("Нет приложения для выбора файлов")
        }
    }

    private fun importFont(uri: Uri) {
        fontStatus.text = "Загружаю шрифт…"
        Thread {
            val msg = try {
                val bytes = contentResolver.openInputStream(uri)?.use { Fonts.readLimited(it, 30L * 1024 * 1024) }
                    ?: throw java.io.IOException("не открыть файл")
                Fonts.importCustom(this, bytes)
                val p = Prefs.get(this)
                p.edit()
                    .putString(Prefs.K_FONT, FontPreset.CUSTOM.name)
                    .putInt(Prefs.K_FONT_VER, p.getInt(Prefs.K_FONT_VER, 0) + 1)
                    .apply()
                runOnUiThread { fontGroup.check(fontIds.getValue(FontPreset.CUSTOM)) }
                "Готово — свой шрифт уже на обоях"
            } catch (e: Exception) {
                "Ошибка: ${e.message ?: e.javaClass.simpleName}"
            }
            runOnUiThread { if (!isFinishing) fontStatus.text = msg }
        }.start()
    }

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
        val srvLines = servers.text.toString().lines().map { it.trim() }.filter { it.isNotEmpty() }
        val badSrv = srvLines.filter { Servers.parseLine(it) == null }
        if (badSrv.isNotEmpty()) {
            servers.error = "Не распознана строка: ${badSrv.first().take(24)}… — нужна целиком iwa1|…"
            servers.requestFocus()
            return false
        }
        hideKeyboard()
        Prefs.get(this).edit()
            .putString(Prefs.K_SERVERS, srvLines.joinToString("\n"))
            .putString(Prefs.K_ACCENT, color)
            .putInt(Prefs.K_FPS, (fps.text.toString().toIntOrNull() ?: 20).coerceIn(0, 30))
            .putFloat(Prefs.K_SHADE_SOLID, ((shadeSolid.text.toString().replace(',', '.').toFloatOrNull() ?: 9.3f) / 100f).coerceIn(0f, 0.4f))
            .putFloat(Prefs.K_SHADE_FADE, ((shadeFade.text.toString().replace(',', '.').toFloatOrNull() ?: 11.2f) / 100f).coerceIn(0.02f, 0.5f))
            .putFloat(Prefs.K_UI_SCALE, (uiScale.text.toString().replace(',', '.').toFloatOrNull() ?: 0.8f).coerceIn(0.6f, 1f))
            .putFloat(Prefs.K_GLOW, (glow.text.toString().replace(',', '.').toFloatOrNull() ?: 1f).coerceIn(0f, 2f))
            .putFloat(Prefs.K_YAW, (yaw.text.toString().toFloatOrNull() ?: 24f).coerceIn(0f, 34f))
            .putString(Prefs.K_ZONES, zones.text.toString().trim())
            .putBoolean(Prefs.K_WEATHER, swWeather.isChecked)
            .putString(Prefs.K_FONT, selectedFont().name)
            .putBoolean(Prefs.K_CONVERTER, swConverter.isChecked)
            .putBoolean(Prefs.K_NEWS, swNews.isChecked)
            .putString(Prefs.K_NEWS_REGION, newsRegion.text.toString().trim().uppercase().filter { it.isLetter() }.take(2).ifEmpty { "RU" })
            .putString(Prefs.K_URL, u)
            .putString(Prefs.K_TOKEN, token.text.toString().trim())
            .putInt(Prefs.K_INTERVAL, (interval.text.toString().toIntOrNull() ?: 300).coerceAtLeast(Prefs.MIN_INTERVAL))
            .apply()
        val badZones = zones.text.toString().isNotBlank() && Prefs.parseZones(zones.text.toString()).isEmpty()
        if (badZones) toast("Пояса не распознаны — пример: МСК=Europe/Moscow")
        return true
    }

    // ---------- валюты ----------

    private fun renderCurrencies() {
        curList.removeAllViews()
        curCodes.forEachIndexed { i, code ->
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                minimumHeight = dp(48)
            }
            row.addView(TextView(this).apply {
                text = "$code   ${CurrencyPicker.name(code)}"
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
                setPadding(dp(4), dp(10), dp(4), dp(10))
                isClickable = true
                setBackgroundResource(android.R.drawable.list_selector_background)
                setOnClickListener {
                    CurrencyPicker.show(this@SettingsActivity, "Валюта вместо $code", curCodes) { picked ->
                        val was = curCodes.indexOf(picked)
                        if (was >= 0) curCodes[was] = code   // уже была — меняются местами
                        curCodes[i] = picked
                        commitCurrencies()
                    }
                }
            }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            fun small(t: String, enabled: Boolean, act: () -> Unit) = Button(this, null, android.R.attr.borderlessButtonStyle).apply {
                text = t
                isEnabled = enabled
                minWidth = dp(44); minimumWidth = dp(44)
                setOnClickListener { act() }
            }
            row.addView(small("↑", i > 0) { java.util.Collections.swap(curCodes, i, i - 1); commitCurrencies() })
            row.addView(small("↓", i < curCodes.size - 1) { java.util.Collections.swap(curCodes, i, i + 1); commitCurrencies() })
            row.addView(small("✕", curCodes.size > 1) { curCodes.removeAt(i); commitCurrencies() })
            curList.addView(row)
        }
        if (curCodes.size < RatesSource.MAX_CODES) {
            curList.addView(button("+ Добавить валюту") {
                CurrencyPicker.show(this, "Добавить валюту", curCodes) { picked ->
                    if (picked !in curCodes) { curCodes += picked; commitCurrencies() }
                }
            })
        }
    }

    private fun commitCurrencies() {
        CurrencyPicker.save(this, curCodes)
        renderCurrencies()
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

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode != RESULT_OK) return
        val uri: Uri = data?.data ?: return
        if (requestCode == REQ_FONT) { importFont(uri); return }
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
        private const val REQ_FONT = 43
    }
}
