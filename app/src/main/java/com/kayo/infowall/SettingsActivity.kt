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
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import java.util.Locale

/**
 * Настройки поверх живых обоев: панель снизу, обои видны сверху.
 * Каждое изменение сразу пишется в настройки — обои перерисовываются на лету, кнопки «Сохранить» нет.
 */
class SettingsActivity : Activity() {

    private lateinit var zones: EditText
    private lateinit var swWeather: Switch
    private lateinit var placeStatus: TextView
    private lateinit var swConverter: Switch
    private lateinit var curList: LinearLayout
    private lateinit var overlayStatus: TextView
    private val curCodes = ArrayList<String>()
    private lateinit var swNews: Switch
    private lateinit var newsRegion: EditText
    private lateinit var servers: EditText

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val cfg = Prefs.load(this)
        val p = Prefs.get(this)

        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val pad = dp(20)
            setPadding(pad, dp(8), pad, pad)
        }
        col += label("Обои видны сверху — всё меняется на них сразу.")

        // --- вид ---
        col += header("Вид")
        col += label("Цвет")
        col += ColorPalette(this, cfg.accent) { c -> put { putString(Prefs.K_ACCENT, ColorPalette.hex(c)) } }
        col += slider("Свечение (bloom)", 0f, 2f, 0.05f, cfg.glow, { if (it == 0f) "выкл" else String.format(Locale.US, "%.2f", it) }) { v ->
            put { putFloat(Prefs.K_GLOW, v) }
        }
        col += slider("Чёрный низ под доком", 0f, 30f, 0.1f, cfg.shadeSolid * 100f, { String.format(Locale.US, "%.1f %%", it) }) { v ->
            put { putFloat(Prefs.K_SHADE_SOLID, v / 100f) }
        }
        col += slider("Переход в прозрачность над доком", 2f, 40f, 0.1f, cfg.shadeFade * 100f, { String.format(Locale.US, "%.1f %%", it) }) { v ->
            put { putFloat(Prefs.K_SHADE_FADE, v / 100f) }
        }
        col += slider("Поворот камеры", 0f, 34f, 1f, cfg.yawDeg, { "${it.toInt()}°" }) { v ->
            put { putFloat(Prefs.K_YAW, v) }
        }
        col += slider("Кадров в секунду", 0f, 30f, 1f, cfg.fps.toFloat(), { if (it == 0f) "без анимации" else "${it.toInt()}" }) { v ->
            put { putInt(Prefs.K_FPS, v.toInt()) }
        }

        // --- часы ---
        col += header("Часовые пояса")
        col += label("До трёх через запятую: метка=пояс (метки латиницей — так их рисует шрифт цифр)")
        zones = field(Prefs.DEFAULT_ZONES, p.getString(Prefs.K_ZONES, Prefs.DEFAULT_ZONES).orEmpty(), InputType.TYPE_CLASS_TEXT)
        onText(zones) { t ->
            if (t.isBlank() || Prefs.parseZones(t).isNotEmpty()) { put { putString(Prefs.K_ZONES, t.trim()) }; null }
            else "Пример: MOS=Europe/Moscow"
        }
        col += zones

        // --- погода ---
        col += header("Погода")
        swWeather = switch("Показывать прогноз на неделю", cfg.weather) { v -> put { putBoolean(Prefs.K_WEATHER, v) } }; col += swWeather
        col += button("Определить местоположение") { locate() }
        placeStatus = status(placeText(cfg)); col += placeStatus


        // --- курсы ---
        col += header("Курсы валют")
        swConverter = switch("Конвертер на экране (тап по строке — клавиатура прямо на обоях)", cfg.converter) { v -> put { putBoolean(Prefs.K_CONVERTER, v) } }; col += swConverter
        col += label("До ${RatesSource.MAX_CODES} валют. Тап по валюте — заменить, стрелки — порядок. Меняется на обоях сразу.")
        curCodes += RatesSource.parseCodes(cfg.currencies)
        curList = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        col += curList
        renderCurrencies()
        col += button("Открыть конвертер") { startActivity(Intent(this, ConverterActivity::class.java)) }

        // --- новости ---
        col += header("Бегущая строка")
        swNews = switch("Новости Google на русском", cfg.news) { v -> put { putBoolean(Prefs.K_NEWS, v) } }; col += swNews
        col += label("Темы")
        val chosen = cfg.newsTopics.toMutableSet()
        for (t in NewsTopic.values()) {
            col += android.widget.CheckBox(this).apply {
                text = t.title
                isChecked = t in chosen
                minHeight = dp(44)
                setOnCheckedChangeListener { b, on ->
                    if (on) chosen += t else chosen -= t
                    if (chosen.isEmpty()) { chosen += t; b.isChecked = true; toast("Нужна хотя бы одна тема"); return@setOnCheckedChangeListener }
                    put { putString(Prefs.K_NEWS_TOPICS, NewsTopic.join(chosen)) }
                }
            }
        }
        col += label("Регион подборки: RU, KZ, BY, UA… (две буквы)")
        newsRegion = field("RU", cfg.newsRegion, InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS)
        onText(newsRegion) { t ->
            val r = t.trim().uppercase().filter { it.isLetter() }
            if (r.length == 2) { put { putString(Prefs.K_NEWS_REGION, r) }; null } else if (r.isEmpty()) null else "Две буквы, например KZ"
        }
        col += newsRegion

        // --- открытие приложений ---
        col += header("Открытие приложений с обоев")
        col += label("Тап по часам — «Часы», по календарю — Google Календарь. Android разрешает обоям открывать приложения только с разрешением «Поверх других приложений».")
        col += button("Выдать разрешение") {
            try {
                startActivity(Intent(android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
            } catch (_: ActivityNotFoundException) {
                toast("Открой: Настройки → Приложения → InfoWall → Поверх других приложений")
            }
        }
        overlayStatus = status(""); col += overlayStatus

        // --- серверы ---
        col += header("Серверы (до ${Servers.MAX})")
        col += label("На сервере: curl -fsSL https://raw.githubusercontent.com/Kayosac/OneeUIwalpapper/main/agent/install.sh | sudo bash — и вставь сюда строки iwa1|…, по одной на строку")
        servers = field("iwa1|имя|https://IP:порт|токен|отпечаток", p.getString(Prefs.K_SERVERS, "").orEmpty(),
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS).apply {
            isSingleLine = false; minLines = 2; maxLines = 4
        }
        onText(servers) { t ->
            val lines = t.lines().map { it.trim() }.filter { it.isNotEmpty() }
            val bad = lines.firstOrNull { Servers.parseLine(it) == null }
            if (bad == null) { put { putString(Prefs.K_SERVERS, lines.joinToString("\n")) }; null }
            else "Не распознана строка: ${bad.take(24)}… — нужна целиком iwa1|…"
        }
        col += servers

        col += space(dp(16))
        col += button("Установить InfoWall на рабочий стол") { setWallpaper() }
        col += label("Двойной тап по пустому месту рабочего стола — обновить погоду и серверы.")

        // живые обои за окном настроек; панель — нижние ~55 % экрана, верх (часы, плашки, погода) остаётся виден
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_SHOW_WALLPAPER)
        val panelH = (resources.displayMetrics.heightPixels * 0.56f).toInt()
        val scroll = ScrollView(this).apply {
            setBackgroundColor(android.graphics.Color.argb(236, 6, 10, 14))
            addView(col)
        }
        val root = android.widget.FrameLayout(this).apply {
            addView(scroll, android.widget.FrameLayout.LayoutParams(android.widget.FrameLayout.LayoutParams.MATCH_PARENT, panelH, Gravity.BOTTOM))
        }
        setContentView(root)
    }

    /** Записать изменение сразу — обои слушают настройки и перерисовываются. */
    private inline fun put(block: android.content.SharedPreferences.Editor.() -> Unit) {
        Prefs.get(this).edit().apply(block).apply()
    }

    /** Поле с проверкой на лету: validate возвращает текст ошибки или null (значение уже записано). */
    private fun onText(f: EditText, validate: (String) -> String?) {
        f.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(e: android.text.Editable?) { f.error = validate(e?.toString().orEmpty()) }
        })
    }

    private fun slider(title: String, min: Float, max: Float, step: Float, value: Float, fmt: (Float) -> String, onChange: (Float) -> Unit): View {
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(0, dp(10), 0, 0) }
        val cap = TextView(this).apply { alpha = 0.85f }
        val steps = Math.round((max - min) / step)
        val bar = android.widget.SeekBar(this).apply {
            this.max = steps
            progress = Math.round((value.coerceIn(min, max) - min) / step)
        }
        fun v(p: Int) = min + p * step
        cap.text = "$title: ${fmt(v(bar.progress))}"
        bar.setOnSeekBarChangeListener(object : android.widget.SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: android.widget.SeekBar?, p: Int, fromUser: Boolean) {
                cap.text = "$title: ${fmt(v(p))}"
                if (fromUser) onChange(v(p))
            }
            override fun onStartTrackingTouch(sb: android.widget.SeekBar?) {}
            override fun onStopTrackingTouch(sb: android.widget.SeekBar?) {}
        })
        box.addView(cap)
        box.addView(bar, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(40)))
        return box
    }


    override fun onResume() {
        super.onResume()
        val ok = Build.VERSION.SDK_INT < 23 || android.provider.Settings.canDrawOverlays(this)
        overlayStatus.text = if (ok) "✓ Разрешение есть — часы и календарь на обоях открывают приложения"
        else "✗ Разрешения нет — тап по часам и календарю ничего не откроет"
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

    // ---------- источник ----------

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

    private fun switch(s: String, checked: Boolean, onChange: (Boolean) -> Unit) = Switch(this).apply {
        text = s
        isChecked = checked
        setOnCheckedChangeListener { _, v -> onChange(v) }
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
    }
}
