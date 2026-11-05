package com.kayo.infowall

import android.content.Context
import android.graphics.Typeface
import java.io.File
import java.io.IOException

/**
 * Наборы шрифтов: первый — для цифр (часы, значения), второй — для подписей с кириллицей.
 * Все встроенные шрифты — Google Fonts под SIL OFL 1.1 (assets/fonts/LICENSES.txt).
 */
enum class FontPreset(
    val title: String,
    val num: String?,
    val numAxes: String?,
    val label: String?,
    val labelAxes: String?,
    val clock: String? = num,
    val clockAxes: String? = numAxes,
) {
    FINAL("Итог · часы Sixtyfour, цифры Bitcount, текст Days One",
        "bitcount.ttf", "'wght' 400", "daysone.ttf", null, "sixtyfour.ttf", null),
    SYSTEM("Системный моноширинный", null, null, null, null),
    DOTO_HANDJET("Точечный · Doto + Handjet", "doto.ttf", "'wght' 800", "handjet.ttf", "'wght' 400"),
    BITCOUNT_GEIST("Nothing 1 · Bitcount Grid + Geist Mono", "bitcount.ttf", "'wght' 400", "geistmono.ttf", "'wght' 400"),
    DOTGOTHIC("Nothing 2 · DotGothic16", "dotgothic16.ttf", null, "dotgothic16.ttf", null),
    TINY5_PLEX("Nothing 3 · Tiny5 + IBM Plex Mono", "tiny5.ttf", null, "ibmplexmono.ttf", null),
    SIXTYFOUR_PLEX("Nothing 4 · Sixtyfour + IBM Plex Mono", "sixtyfour.ttf", null, "ibmplexmono.ttf", null),
    DOTO_GEIST("Nothing 5 · Doto + Geist Mono", "doto.ttf", "'wght' 600", "geistmono.ttf", "'wght' 400"),
    CUSTOM("Свой файл (.ttf / .otf)", null, null, null, null),
}

object Fonts {

    private const val CUSTOM_FILE = "custom_font"
    private val fallback: Typeface = Typeface.create(Typeface.MONOSPACE, Typeface.NORMAL)

    class Set(val clock: Typeface, val num: Typeface, val label: Typeface)

    /** Циферблат, мелкие цифры и подписи; при любой ошибке — системный моноширинный. */
    fun load(ctx: Context, preset: FontPreset): Set = when (preset) {
        FontPreset.SYSTEM -> Set(fallback, fallback, fallback)
        FontPreset.CUSTOM -> {
            val f = File(ctx.filesDir, CUSTOM_FILE)
            val tf = (if (f.exists()) runCatching { Typeface.Builder(f).build() }.getOrNull() else null) ?: fallback
            Set(tf, tf, tf)
        }
        else -> Set(
            asset(ctx, preset.clock, preset.clockAxes),
            asset(ctx, preset.num, preset.numAxes),
            asset(ctx, preset.label, preset.labelAxes),
        )
    }

    private fun asset(ctx: Context, name: String?, axes: String?): Typeface {
        if (name == null) return fallback
        return runCatching {
            val b = Typeface.Builder(ctx.assets, "fonts/$name")
            if (axes != null) b.setFontVariationSettings(axes)
            b.build()
        }.getOrNull() ?: fallback
    }

    /** Сохраняет свой шрифт в личную папку приложения (в APK и репозиторий он не попадает). */
    @Throws(IOException::class)
    fun importCustom(ctx: Context, bytes: ByteArray) {
        if (bytes.size < 12) throw IOException("файл слишком маленький")
        val tag = ((bytes[0].toInt() and 0xFF) shl 24) or ((bytes[1].toInt() and 0xFF) shl 16) or
            ((bytes[2].toInt() and 0xFF) shl 8) or (bytes[3].toInt() and 0xFF)
        val ok = tag == 0x00010000 || tag == 0x4F54544F /* OTTO */ || tag == 0x74727565 /* true */ || tag == 0x74746366 /* ttcf */
        if (!ok) throw IOException("нужен шрифт .ttf или .otf (woff не поддерживается)")
        val tmp = File(ctx.filesDir, "$CUSTOM_FILE.tmp")
        tmp.writeBytes(bytes)
        runCatching { Typeface.Builder(tmp).build() }.getOrNull() ?: run {
            tmp.delete(); throw IOException("Android не смог прочитать шрифт")
        }
        if (!tmp.renameTo(File(ctx.filesDir, CUSTOM_FILE))) throw IOException("не смог сохранить шрифт")
    }
}
