package com.kayo.infowall

import android.app.Activity
import android.app.WallpaperManager
import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Intent
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

/**
 * Экран настроек. Всё управление — кнопками: ни одно действие
 * не требует клавиши Enter на экранной клавиатуре.
 */
class SettingsActivity : Activity() {

    private lateinit var url: EditText
    private lateinit var token: EditText
    private lateinit var interval: EditText
    private lateinit var top: EditText
    private lateinit var swClock: Switch
    private lateinit var swBattery: Switch
    private lateinit var swRam: Switch
    private lateinit var swStorage: Switch
    private lateinit var swNetwork: Switch
    private lateinit var result: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val cfg = Prefs.load(this)

        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val p = dp(20)
            setPadding(p, p, p, p)
        }

        col += header("Источник данных (необязательно)")
        url = field("URL JSON, например http://192.168.1.10:8080/status.json", cfg.url,
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI)
        col += url
        token = field("Токен (Bearer), если нужен", cfg.token,
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD)
        col += token
        interval = field("Обновлять раз в N секунд (мин. ${Prefs.MIN_INTERVAL})", cfg.intervalSec.toString(),
            InputType.TYPE_CLASS_NUMBER)
        col += label("Интервал обновления, сек")
        col += interval
        col += button("Проверить источник") { testSource() }
        result = TextView(this).apply {
            setTextIsSelectable(true)
            setPadding(0, dp(8), 0, dp(8))
        }
        col += result

        col += header("Что показывать")
        swClock = switch("Часы и дата", cfg.showClock); col += swClock
        swBattery = switch("Батарея", cfg.showBattery); col += swBattery
        swRam = switch("Оперативная память", cfg.showRam); col += swRam
        swStorage = switch("Свободное место", cfg.showStorage); col += swStorage
        swNetwork = switch("Сеть", cfg.showNetwork); col += swNetwork

        col += header("Расположение")
        col += label("Отступ блока сверху, % высоты экрана (0–85)")
        top = field("8", cfg.topPct.toString(), InputType.TYPE_CLASS_NUMBER)
        col += top

        col += space(dp(16))
        col += button("Сохранить") { if (save()) toast("Сохранено") }
        col += button("Сохранить и установить обои") { if (save()) setWallpaper() }
        col += label("Подсказка: двойной тап по пустому месту рабочего стола — обновить данные " +
            "(работает, если лаунчер передаёт тапы обоям).")

        setContentView(ScrollView(this).apply {
            isFillViewport = true
            addView(col)
        })
    }

    private fun readConfig(): Config? {
        val u = url.text.toString().trim()
        if (u.isNotEmpty() && !(u.startsWith("http://") || u.startsWith("https://"))) {
            url.error = "Нужен адрес, начинающийся с http:// или https://"
            url.requestFocus()
            return null
        }
        val sec = interval.text.toString().toIntOrNull() ?: 300
        val pct = top.text.toString().toIntOrNull() ?: 8
        return Config(
            url = u,
            token = token.text.toString().trim(),
            intervalSec = sec.coerceAtLeast(Prefs.MIN_INTERVAL),
            topPct = pct.coerceIn(0, 85),
            showClock = swClock.isChecked,
            showBattery = swBattery.isChecked,
            showRam = swRam.isChecked,
            showStorage = swStorage.isChecked,
            showNetwork = swNetwork.isChecked,
        )
    }

    private fun save(): Boolean {
        val c = readConfig() ?: return false
        hideKeyboard()
        Prefs.save(this, c)
        // показываем нормализованные значения
        interval.setText(c.intervalSec.toString())
        top.setText(c.topPct.toString())
        return true
    }

    private fun testSource() {
        val c = readConfig() ?: return
        hideKeyboard()
        if (c.url.isEmpty()) {
            result.text = "URL пустой — будут только локальные метрики."
            return
        }
        result.text = "Загружаю…"
        Thread {
            val text = try {
                val d = RemoteSource.fetch(c.url, c.token)
                buildString {
                    append("OK")
                    d.title?.let { append(" · «$it»") }
                    append(" · ${d.items.size} шт.\n")
                    d.items.take(12).forEach {
                        append("• ${it.label}: ${it.value}")
                        it.usedPercent?.let { u -> append("  (израсходовано ${u.toInt()}%)") }
                        if (it.state != State.NONE) append("  [${it.state.name.lowercase()}]")
                        append("\n")
                    }
                }
            } catch (e: Exception) {
                "Ошибка: ${e.message ?: e.javaClass.simpleName}"
            }
            runOnUiThread { if (!isFinishing) result.text = text }
        }.start()
    }

    private fun setWallpaper() {
        val component = ComponentName(this, InfoWallpaperService::class.java)
        val direct = Intent(WallpaperManager.ACTION_CHANGE_LIVE_WALLPAPER)
            .putExtra(WallpaperManager.EXTRA_LIVE_WALLPAPER_COMPONENT, component)
        try {
            startActivity(direct)
        } catch (_: ActivityNotFoundException) {
            try {
                startActivity(Intent(WallpaperManager.ACTION_LIVE_WALLPAPER_CHOOSER))
            } catch (_: ActivityNotFoundException) {
                toast("Не нашёл экран выбора живых обоев")
            }
        }
    }

    // ---- мелкие UI-хелперы ----

    private operator fun LinearLayout.plusAssign(v: View) = addView(v)

    private fun dp(v: Int) = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics,
    ).toInt()

    private fun header(text: String) = TextView(this).apply {
        this.text = text
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
        setPadding(0, dp(20), 0, dp(6))
    }

    private fun label(text: String) = TextView(this).apply {
        this.text = text
        alpha = 0.75f
        setPadding(0, dp(8), 0, dp(2))
    }

    private fun field(hint: String, value: String, type: Int) = EditText(this).apply {
        this.hint = hint
        setText(value)
        inputType = type
        isSingleLine = true
        // «Далее/Готово» только двигает фокус — сохранение всегда кнопкой
        imeOptions = EditorInfo.IME_ACTION_NEXT or EditorInfo.IME_FLAG_NO_EXTRACT_UI
    }

    private fun switch(text: String, checked: Boolean) = Switch(this).apply {
        this.text = text
        isChecked = checked
        gravity = Gravity.CENTER_VERTICAL
        minHeight = dp(48)
    }

    private fun button(text: String, onClick: () -> Unit) = Button(this).apply {
        this.text = text
        setOnClickListener { onClick() }
    }

    private fun space(h: Int) = View(this).apply {
        layoutParams = LinearLayout.LayoutParams(1, h)
    }

    private fun hideKeyboard() {
        currentFocus?.let {
            getSystemService(InputMethodManager::class.java)?.hideSoftInputFromWindow(it.windowToken, 0)
        }
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()
}
