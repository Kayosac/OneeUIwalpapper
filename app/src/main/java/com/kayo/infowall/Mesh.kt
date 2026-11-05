package com.kayo.infowall

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLDecoder
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.PriorityQueue
import java.util.zip.ZipInputStream
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Low-poly сетка для отрисовки каркасом: вершины (x,y,z, нормированы в радиус 1) и треугольники.
 * Рёбра, их соседние грани и нормали граней считаются один раз при создании —
 * по ним рендер каждый кадр находит силуэт.
 */
class WireMesh(val verts: FloatArray, val tris: IntArray) {
    val vertexCount get() = verts.size / 3
    val faceCount get() = tris.size / 3
    val edges: IntArray
    val edgeFaces: IntArray
    val normals = FloatArray(tris.size)

    init {
        val map = HashMap<Long, Int>()
        val e = ArrayList<Int>()
        val ef = ArrayList<Int>()
        for (f in 0 until faceCount) {
            val a = tris[f * 3]; val b = tris[f * 3 + 1]; val c = tris[f * 3 + 2]
            // нормаль грани
            val ux = verts[b * 3] - verts[a * 3]; val uy = verts[b * 3 + 1] - verts[a * 3 + 1]; val uz = verts[b * 3 + 2] - verts[a * 3 + 2]
            val vx = verts[c * 3] - verts[a * 3]; val vy = verts[c * 3 + 1] - verts[a * 3 + 1]; val vz = verts[c * 3 + 2] - verts[a * 3 + 2]
            var nx = uy * vz - uz * vy; var ny = uz * vx - ux * vz; var nz = ux * vy - uy * vx
            val l = sqrt(nx * nx + ny * ny + nz * nz)
            if (l > 0f) { nx /= l; ny /= l; nz /= l }
            normals[f * 3] = nx; normals[f * 3 + 1] = ny; normals[f * 3 + 2] = nz
            for (k in 0 until 3) {
                val p = tris[f * 3 + k]; val q = tris[f * 3 + (k + 1) % 3]
                val key = (minOf(p, q).toLong() shl 32) or maxOf(p, q).toLong()
                val idx = map[key]
                if (idx == null) {
                    map[key] = e.size / 2
                    e.add(minOf(p, q)); e.add(maxOf(p, q))
                    ef.add(f); ef.add(-1)
                } else if (ef[idx * 2 + 1] == -1) {
                    ef[idx * 2 + 1] = f
                }
            }
        }
        edges = e.toIntArray()
        edgeFaces = ef.toIntArray()
    }

    val edgeCount get() = edges.size / 2
}

/**
 * Модели для обоев: встроенная уточка, свой файл (.glb / .gltf в .zip) и Sketchfab по ссылке.
 * Импорт: сварка вершин по швам UV → (для тяжёлых) грубая кластеризация →
 * QEM-упрощение (Garland–Heckbert) до ~TARGET_TRIS треугольников.
 */
object Meshes {

    private const val FILE = "model2.bin"
    private const val TARGET_TRIS = 760
    private const val MAX_TARGET_TRIS = 1800
    private const val PRECLUSTER_ABOVE = 140_000
    private const val MAX_TRIS = 4_000_000
    private const val MAX_DOWNLOAD = 120L * 1024 * 1024

    // ---------- хранение ----------

    fun saved(ctx: Context): WireMesh? = runCatching {
        DataInputStream(File(ctx.filesDir, FILE).inputStream().buffered()).use { inp ->
            val nv = inp.readInt()
            val v = FloatArray(nv) { inp.readFloat() }
            val nt = inp.readInt()
            val t = IntArray(nt) { inp.readInt() }
            WireMesh(v, t)
        }
    }.getOrNull()

    fun save(ctx: Context, m: WireMesh) {
        val tmp = File(ctx.filesDir, "$FILE.tmp")
        DataOutputStream(tmp.outputStream().buffered()).use { out ->
            out.writeInt(m.verts.size)
            m.verts.forEach { out.writeFloat(it) }
            out.writeInt(m.tris.size)
            m.tris.forEach { out.writeInt(it) }
        }
        if (!tmp.renameTo(File(ctx.filesDir, FILE))) throw IOException("не смог сохранить модель")
    }

    // ---------- встроенная уточка ----------

    fun duck(): WireMesh {
        val verts = FloatList()
        val tris = IntList()
        fun ellipsoid(c: FloatArray, r: FloatArray, nLat: Int, nLon: Int, deform: ((FloatArray) -> Unit)?) {
            val idx = Array(nLat + 1) { IntArray(nLon) }
            for (i in 0..nLat) {
                val ph = -Math.PI / 2 + Math.PI * i / nLat
                for (j in 0 until nLon) {
                    if ((i == 0 || i == nLat) && j > 0) { idx[i][j] = idx[i][0]; continue }
                    val la = 2 * Math.PI * j / nLon
                    val p = floatArrayOf(
                        (c[0] + r[0] * cos(ph) * cos(la)).toFloat(),
                        (c[1] + r[1] * sin(ph)).toFloat(),
                        (c[2] + r[2] * cos(ph) * sin(la)).toFloat(),
                    )
                    deform?.invoke(p)
                    idx[i][j] = verts.size / 3
                    verts.add(p[0]); verts.add(p[1]); verts.add(p[2])
                }
            }
            fun tri(a: Int, b: Int, d: Int) {
                if (a == b || b == d || a == d) return
                tris.add(a); tris.add(b); tris.add(d)
            }
            for (i in 0 until nLat) for (j in 0 until nLon) {
                val n = (j + 1) % nLon
                tri(idx[i][j], idx[i + 1][j], idx[i + 1][n])
                tri(idx[i][j], idx[i + 1][n], idx[i][n])
            }
        }
        ellipsoid(floatArrayOf(0f, 0f, 0f), floatArrayOf(1.15f, 0.62f, 0.8f), 7, 14) { p ->
            if (p[0] < -0.55f) p[1] += (-p[0] - 0.55f) * (if (p[1] > -0.1f) 1.0f else 0.45f)
            if (p[1] < -0.42f) p[1] = -0.42f + (p[1] + 0.42f) * 0.4f
        }
        ellipsoid(floatArrayOf(0.72f, 0.86f, 0f), floatArrayOf(0.43f, 0.43f, 0.43f), 7, 12, null)
        ellipsoid(floatArrayOf(1.2f, 0.76f, 0f), floatArrayOf(0.3f, 0.085f, 0.2f), 3, 8, null)
        val v = verts.toArray()
        normalize(v)
        return WireMesh(v, tris.toArray())
    }

    // ---------- импорт ----------

    /** Определяет формат по содержимому: GLB, ZIP с glTF, или голый .gltf со встроенными буферами. */
    @Throws(IOException::class)
    fun fromBytes(bytes: ByteArray): WireMesh {
        val g = when {
            bytes.size >= 12 && le32(bytes, 0) == 0x46546C67 -> parseGlb(bytes)
            bytes.size >= 4 && bytes[0] == 'P'.code.toByte() && bytes[1] == 'K'.code.toByte() -> parseZip(bytes)
            bytes.isNotEmpty() && bytes[0] == '{'.code.toByte() -> Gltf(JSONObject(String(bytes, Charsets.UTF_8)), emptyMap(), "")
            else -> throw IOException("нужен .glb или .zip с glTF")
        }
        checkExtensions(g.json)
        val (pos, tris) = extractTriangles(g)
        if (tris.isEmpty()) throw IOException("в модели нет треугольников")
        return buildFromTriangles(pos, tris)
    }

    @Throws(IOException::class)
    fun readAll(input: InputStream, limit: Long = MAX_DOWNLOAD): ByteArray {
        val out = ByteArrayOutputStream()
        val buf = ByteArray(64 * 1024)
        var total = 0L
        while (true) {
            val n = input.read(buf)
            if (n < 0) break
            total += n
            if (total > limit) throw IOException("файл больше ${limit / 1024 / 1024} МБ")
            out.write(buf, 0, n)
        }
        return out.toByteArray()
    }

    // ---------- Sketchfab ----------

    fun sketchfabUid(link: String): String? =
        Regex("([0-9a-f]{32})").findAll(link.lowercase()).lastOrNull()?.value

    /** Скачивает модель через Download API (нужен личный API-токен, модель должна быть скачиваемой). */
    @Throws(IOException::class)
    fun downloadSketchfab(link: String, apiToken: String, progress: (String) -> Unit): ByteArray {
        val uid = sketchfabUid(link) ?: throw IOException("в ссылке не нашёл id модели")
        if (apiToken.isBlank()) throw IOException("нужен API-токен Sketchfab")
        progress("запрашиваю ссылку на скачивание…")
        val c = URL("https://api.sketchfab.com/v3/models/$uid/download").openConnection() as HttpURLConnection
        c.connectTimeout = 8_000
        c.readTimeout = 15_000
        c.setRequestProperty("Authorization", "Token ${apiToken.trim()}")
        val json = try {
            when (c.responseCode) {
                in 200..299 -> JSONObject(c.inputStream.bufferedReader().use { it.readText() })
                401 -> throw IOException("Sketchfab: неверный токен")
                403, 404 -> throw IOException("Sketchfab: модель не скачивается (автор запретил или её нет)")
                429 -> throw IOException("Sketchfab: слишком много запросов, попробуй позже")
                else -> throw IOException("Sketchfab: HTTP ${c.responseCode}")
            }
        } finally {
            c.disconnect()
        }
        val target = json.optJSONObject("glb") ?: json.optJSONObject("gltf")
            ?: throw IOException("Sketchfab не дал glTF-версию модели")
        val fileUrl = target.optString("url").ifBlank { throw IOException("пустая ссылка на файл") }
        val size = target.optLong("size", 0L)
        if (size > MAX_DOWNLOAD) throw IOException("модель весит ${size / 1024 / 1024} МБ — слишком много")
        progress("скачиваю ${if (size > 0) "${size / 1024 / 1024} МБ" else "файл"}…")
        val d = URL(fileUrl).openConnection() as HttpURLConnection
        d.connectTimeout = 8_000
        d.readTimeout = 30_000
        try {
            if (d.responseCode !in 200..299) throw IOException("скачивание: HTTP ${d.responseCode}")
            return d.inputStream.use { readAll(it) }
        } finally {
            d.disconnect()
        }
    }

    // ---------- glTF ----------

    private class Gltf(val json: JSONObject, val files: Map<String, ByteArray>, val baseDir: String, val glbBin: ByteArray? = null)

    private fun le32(b: ByteArray, off: Int): Int =
        (b[off].toInt() and 0xFF) or ((b[off + 1].toInt() and 0xFF) shl 8) or
            ((b[off + 2].toInt() and 0xFF) shl 16) or ((b[off + 3].toInt() and 0xFF) shl 24)

    private fun parseGlb(b: ByteArray): Gltf {
        val total = minOf(le32(b, 8), b.size)
        var off = 12
        var json: JSONObject? = null
        var bin: ByteArray? = null
        while (off + 8 <= total) {
            val len = le32(b, off)
            val type = le32(b, off + 4)
            if (len < 0 || off + 8 + len > b.size) throw IOException("битый GLB")
            when (type) {
                0x4E4F534A -> json = JSONObject(String(b, off + 8, len, Charsets.UTF_8))
                0x004E4942 -> bin = b.copyOfRange(off + 8, off + 8 + len)
            }
            off += 8 + len
        }
        return Gltf(json ?: throw IOException("в GLB нет JSON"), emptyMap(), "", bin)
    }

    private fun parseZip(b: ByteArray): Gltf {
        val files = HashMap<String, ByteArray>()
        ZipInputStream(ByteArrayInputStream(b)).use { z ->
            while (true) {
                val e = z.nextEntry ?: break
                if (!e.isDirectory) files[e.name.replace('\\', '/')] = readAll(z)
            }
        }
        files.entries.firstOrNull { it.key.endsWith(".glb", true) }?.let { return parseGlb(it.value) }
        val main = files.keys.filter { it.endsWith(".gltf", true) }.minByOrNull { it.count { c -> c == '/' } }
            ?: throw IOException("в архиве нет .gltf или .glb")
        val dir = main.substringBeforeLast('/', "").let { if (it.isEmpty()) "" else "$it/" }
        return Gltf(JSONObject(String(files.getValue(main), Charsets.UTF_8)), files, dir)
    }

    private fun checkExtensions(json: JSONObject) {
        val req = json.optJSONArray("extensionsRequired") ?: return
        for (i in 0 until req.length()) {
            val ext = req.optString(i)
            if (ext.contains("draco", true) || ext.contains("meshopt", true) || ext.contains("quantization", true))
                throw IOException("модель сжата ($ext) — такой формат пока не поддерживается")
        }
    }

    private fun bufferBytes(g: Gltf, index: Int): ByteArray {
        val buf = g.json.getJSONArray("buffers").getJSONObject(index)
        val uri = buf.optString("uri", "")
        if (uri.isEmpty()) return g.glbBin ?: throw IOException("нет бинарного буфера")
        if (uri.startsWith("data:")) {
            val comma = uri.indexOf(',')
            return java.util.Base64.getDecoder().decode(uri.substring(comma + 1))
        }
        val name = g.baseDir + URLDecoder.decode(uri, "UTF-8")
        return g.files[name] ?: g.files.entries.firstOrNull { it.key.endsWith("/" + uri) || it.key == uri }?.value
            ?: throw IOException("не найден файл буфера $uri")
    }

    private class Accessor(val buf: ByteBuffer, val start: Int, val stride: Int, val count: Int, val compType: Int, val comps: Int)

    private fun accessor(g: Gltf, idx: Int, cache: HashMap<Int, ByteBuffer>): Accessor {
        val a = g.json.getJSONArray("accessors").getJSONObject(idx)
        if (!a.has("bufferView")) throw IOException("разреженные данные не поддерживаются")
        val bv = g.json.getJSONArray("bufferViews").getJSONObject(a.getInt("bufferView"))
        val bi = bv.getInt("buffer")
        val bb = cache.getOrPut(bi) { ByteBuffer.wrap(bufferBytes(g, bi)).order(ByteOrder.LITTLE_ENDIAN) }
        val comps = when (a.optString("type")) { "SCALAR" -> 1; "VEC2" -> 2; "VEC3" -> 3; "VEC4" -> 4; else -> 1 }
        val ct = a.getInt("componentType")
        val compSize = when (ct) { 5120, 5121 -> 1; 5122, 5123 -> 2; else -> 4 }
        val stride = bv.optInt("byteStride", 0).let { if (it > 0) it else compSize * comps }
        return Accessor(bb, bv.optInt("byteOffset", 0) + a.optInt("byteOffset", 0), stride, a.getInt("count"), ct, comps)
    }

    private fun extractTriangles(g: Gltf): Pair<FloatArray, IntArray> {
        val json = g.json
        val nodes = json.optJSONArray("nodes") ?: JSONArray()
        val meshes = json.optJSONArray("meshes") ?: throw IOException("в файле нет мешей")
        val cache = HashMap<Int, ByteBuffer>()
        val pos = FloatList()
        val tris = IntList()

        fun addMesh(meshIdx: Int, m: FloatArray) {
            val prims = meshes.getJSONObject(meshIdx).optJSONArray("primitives") ?: return
            for (p in 0 until prims.length()) {
                val prim = prims.getJSONObject(p)
                if (prim.optInt("mode", 4) != 4) continue
                val posIdx = prim.optJSONObject("attributes")?.optInt("POSITION", -1) ?: -1
                if (posIdx < 0) continue
                val pa = accessor(g, posIdx, cache)
                if (pa.compType != 5126 || pa.comps < 3) throw IOException("нестандартные координаты вершин")
                val base = pos.size / 3
                for (i in 0 until pa.count) {
                    val o = pa.start + i * pa.stride
                    val x = pa.buf.getFloat(o); val y = pa.buf.getFloat(o + 4); val z = pa.buf.getFloat(o + 8)
                    pos.add(m[0] * x + m[4] * y + m[8] * z + m[12])
                    pos.add(m[1] * x + m[5] * y + m[9] * z + m[13])
                    pos.add(m[2] * x + m[6] * y + m[10] * z + m[14])
                }
                if (prim.has("indices")) {
                    val ia = accessor(g, prim.getInt("indices"), cache)
                    for (i in 0 until ia.count) {
                        val o = ia.start + i * ia.stride
                        val v = when (ia.compType) {
                            5121 -> ia.buf.get(o).toInt() and 0xFF
                            5123 -> ia.buf.getShort(o).toInt() and 0xFFFF
                            else -> ia.buf.getInt(o)
                        }
                        tris.add(base + v)
                    }
                } else {
                    for (i in 0 until pa.count - pa.count % 3) tris.add(base + i)
                }
                if (tris.size / 3 > MAX_TRIS) throw IOException("слишком тяжёлая модель")
            }
        }

        fun visit(nodeIdx: Int, parent: FloatArray, depth: Int) {
            if (depth > 64) return
            val n = nodes.getJSONObject(nodeIdx)
            val world = mul(parent, localMatrix(n))
            if (n.has("mesh")) addMesh(n.getInt("mesh"), world)
            val ch = n.optJSONArray("children") ?: return
            for (i in 0 until ch.length()) visit(ch.getInt(i), world, depth + 1)
        }

        val roots = ArrayList<Int>()
        val scenes = json.optJSONArray("scenes")
        if (scenes != null && scenes.length() > 0) {
            val sc = scenes.getJSONObject(json.optInt("scene", 0).coerceIn(0, scenes.length() - 1))
            val arr = sc.optJSONArray("nodes") ?: JSONArray()
            for (i in 0 until arr.length()) roots.add(arr.getInt(i))
        } else {
            val child = HashSet<Int>()
            for (i in 0 until nodes.length()) nodes.getJSONObject(i).optJSONArray("children")?.let { a ->
                for (k in 0 until a.length()) child.add(a.getInt(k))
            }
            for (i in 0 until nodes.length()) if (i !in child) roots.add(i)
        }
        if (roots.isEmpty() && nodes.length() == 0) {
            for (i in 0 until meshes.length()) addMesh(i, identity())
        } else {
            roots.forEach { visit(it, identity(), 0) }
        }
        return pos.toArray() to tris.toArray()
    }

    // ---------- матрицы (column-major, как в glTF) ----------

    private fun identity() = floatArrayOf(1f, 0f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 0f, 1f)

    private fun mul(a: FloatArray, b: FloatArray): FloatArray {
        val r = FloatArray(16)
        for (c in 0 until 4) for (rw in 0 until 4) {
            var s = 0f
            for (k in 0 until 4) s += a[k * 4 + rw] * b[c * 4 + k]
            r[c * 4 + rw] = s
        }
        return r
    }

    private fun localMatrix(n: JSONObject): FloatArray {
        n.optJSONArray("matrix")?.let { m -> if (m.length() == 16) return FloatArray(16) { m.optDouble(it).toFloat() } }
        val t = n.optJSONArray("translation")
        val r = n.optJSONArray("rotation")
        val s = n.optJSONArray("scale")
        val tx = t?.optDouble(0)?.toFloat() ?: 0f; val ty = t?.optDouble(1)?.toFloat() ?: 0f; val tz = t?.optDouble(2)?.toFloat() ?: 0f
        val qx = r?.optDouble(0)?.toFloat() ?: 0f; val qy = r?.optDouble(1)?.toFloat() ?: 0f
        val qz = r?.optDouble(2)?.toFloat() ?: 0f; val qw = r?.optDouble(3)?.toFloat() ?: 1f
        val sx = s?.optDouble(0)?.toFloat() ?: 1f; val sy = s?.optDouble(1)?.toFloat() ?: 1f; val sz = s?.optDouble(2)?.toFloat() ?: 1f
        val xx = qx * qx; val yy = qy * qy; val zz = qz * qz
        val xy = qx * qy; val xz = qx * qz; val yz = qy * qz
        val wx = qw * qx; val wy = qw * qy; val wz = qw * qz
        return floatArrayOf(
            (1 - 2 * (yy + zz)) * sx, (2 * (xy + wz)) * sx, (2 * (xz - wy)) * sx, 0f,
            (2 * (xy - wz)) * sy, (1 - 2 * (xx + zz)) * sy, (2 * (yz + wx)) * sy, 0f,
            (2 * (xz + wy)) * sz, (2 * (yz - wx)) * sz, (1 - 2 * (xx + yy)) * sz, 0f,
            tx, ty, tz, 1f,
        )
    }

    // ---------- упрощение ----------


    // ---------- упрощение ----------

    private fun buildFromTriangles(pos: FloatArray, tris: IntArray): WireMesh {
        var p = DoubleArray(pos.size) { pos[it].toDouble() }
        var t = tris
        // 1) сварка: glTF режет вершины по швам текстур, без этого QEM видит «дыры»
        run { val w = weld(p, t, 1 shl 20); p = w.first; t = w.second }
        // 2) очень тяжёлые модели сначала грубо сжимаем кластеризацией
        if (t.size / 3 > PRECLUSTER_ABOVE) {
            val c = cluster(p, t, 240); p = c.first; t = c.second
        }
        if (t.isEmpty()) throw IOException("после очистки в модели не осталось граней")
        // 3) QEM: простым формам хватает ~760 треугольников, сложным даём до 1800
        val target = (t.size / 3 / 8).coerceIn(TARGET_TRIS, MAX_TARGET_TRIS)
        val (vp, vt) = qem(p, t, target)
        val v = FloatArray(vp.size) { vp[it].toFloat() }
        normalize(v)
        return WireMesh(v, vt)
    }

    private fun bounds(p: DoubleArray): DoubleArray {
        val b = doubleArrayOf(Double.MAX_VALUE, Double.MAX_VALUE, Double.MAX_VALUE, -Double.MAX_VALUE, -Double.MAX_VALUE, -Double.MAX_VALUE)
        var i = 0
        while (i < p.size) {
            for (k in 0 until 3) {
                val x = p[i + k]
                if (x.isFinite()) { if (x < b[k]) b[k] = x; if (x > b[k + 3]) b[k + 3] = x }
            }
            i += 3
        }
        return b
    }

    /** Склейка вершин, совпадающих с точностью до 1/grid габарита; вырожденные грани выкидываются. */
    private fun weld(p: DoubleArray, t: IntArray, grid: Int): Pair<DoubleArray, IntArray> = remapByCells(p, t, grid, average = false)

    /** Кластеризация в сетку grid³ с усреднением позиций. */
    private fun cluster(p: DoubleArray, t: IntArray, grid: Int): Pair<DoubleArray, IntArray> = remapByCells(p, t, grid, average = true)

    private fun remapByCells(p: DoubleArray, t: IntArray, grid: Int, average: Boolean): Pair<DoubleArray, IntArray> {
        val b = bounds(p)
        val ext = max(b[3] - b[0], max(b[4] - b[1], b[5] - b[2]))
        if (!(ext > 0)) throw IOException("модель пустая или плоская")
        val cell = ext / grid
        val ids = HashMap<Long, Int>()
        val sum = DoubleList()
        val cnt = IntList()
        val map = IntArray(p.size / 3)
        for (v in map.indices) {
            val x = p[v * 3]; val y = p[v * 3 + 1]; val z = p[v * 3 + 2]
            val ix = ((x - b[0]) / cell).toLong().coerceIn(0, grid.toLong())
            val iy = ((y - b[1]) / cell).toLong().coerceIn(0, grid.toLong())
            val iz = ((z - b[2]) / cell).toLong().coerceIn(0, grid.toLong())
            val key = (ix shl 42) or (iy shl 21) or iz
            var id = ids[key]
            if (id == null) {
                id = cnt.size
                ids[key] = id
                sum.add(x); sum.add(y); sum.add(z); cnt.add(1)
            } else if (average) {
                sum[id * 3] += x; sum[id * 3 + 1] += y; sum[id * 3 + 2] += z
                cnt[id] = cnt[id] + 1
            }
            map[v] = id
        }
        val out = DoubleArray(cnt.size * 3)
        for (k in 0 until cnt.size) {
            val n = if (average) cnt[k].toDouble() else 1.0
            out[k * 3] = sum[k * 3] / n; out[k * 3 + 1] = sum[k * 3 + 1] / n; out[k * 3 + 2] = sum[k * 3 + 2] / n
        }
        val faces = IntList()
        val seen = HashSet<String>()
        var i = 0
        while (i + 2 < t.size) {
            val a = map.getOrElse(t[i]) { -1 }; val bb = map.getOrElse(t[i + 1]) { -1 }; val c = map.getOrElse(t[i + 2]) { -1 }
            i += 3
            if (a < 0 || bb < 0 || c < 0 || a == bb || bb == c || a == c) continue
            if (average) {
                // при кластеризации появляются дубли граней — отсекаем
                val s = intArrayOf(a, bb, c).sorted()
                if (!seen.add("${s[0]},${s[1]},${s[2]}")) continue
            }
            faces.add(a); faces.add(bb); faces.add(c)
        }
        return out to faces.toArray()
    }

    private class Cand(val cost: Double, val u: Int, val w: Int, val vu: Int, val vw: Int, val x: Double, val y: Double, val z: Double)

    /**
     * Упрощение по квадрикам ошибки (Garland–Heckbert, 1997): схлопываем ребро с наименьшей
     * ошибкой, запрещаем перевороты граней, штрафуем сдвиг открытых краёв.
     */
    private fun qem(pos: DoubleArray, tris: IntArray, target: Int): Pair<DoubleArray, IntArray> {
        val nv = pos.size / 3
        val nf = tris.size / 3
        val p = pos.copyOf()
        val f = tris.copyOf()
        val alive = BooleanArray(nf) { true }
        val q = DoubleArray(nv * 10)
        val vf = Array(nv) { IntBag() }

        fun addPlane(v: Int, a: Double, b: Double, c: Double, d: Double, wgt: Double) {
            val o = v * 10
            q[o] += a * a * wgt; q[o + 1] += a * b * wgt; q[o + 2] += a * c * wgt; q[o + 3] += a * d * wgt
            q[o + 4] += b * b * wgt; q[o + 5] += b * c * wgt; q[o + 6] += b * d * wgt
            q[o + 7] += c * c * wgt; q[o + 8] += c * d * wgt; q[o + 9] += d * d * wgt
        }

        fun normalOf(a: Int, b: Int, c: Int, out: DoubleArray) {
            val ux = p[b * 3] - p[a * 3]; val uy = p[b * 3 + 1] - p[a * 3 + 1]; val uz = p[b * 3 + 2] - p[a * 3 + 2]
            val vx = p[c * 3] - p[a * 3]; val vy = p[c * 3 + 1] - p[a * 3 + 1]; val vz = p[c * 3 + 2] - p[a * 3 + 2]
            out[0] = uy * vz - uz * vy; out[1] = uz * vx - ux * vz; out[2] = ux * vy - uy * vx
        }

        val n = DoubleArray(3)
        for (fi in 0 until nf) {
            val a = f[fi * 3]; val b = f[fi * 3 + 1]; val c = f[fi * 3 + 2]
            vf[a].add(fi); vf[b].add(fi); vf[c].add(fi)
            normalOf(a, b, c, n)
            val area = sqrt(n[0] * n[0] + n[1] * n[1] + n[2] * n[2])
            if (area < 1e-20) continue
            val nx = n[0] / area; val ny = n[1] / area; val nz = n[2] / area
            val d = -(nx * p[a * 3] + ny * p[a * 3 + 1] + nz * p[a * 3 + 2])
            addPlane(a, nx, ny, nz, d, area); addPlane(b, nx, ny, nz, d, area); addPlane(c, nx, ny, nz, d, area)
        }

        // рёбра и открытые края
        val edgeCount = HashMap<Long, Int>()
        for (fi in 0 until nf) for (k in 0 until 3) {
            val u = f[fi * 3 + k]; val w = f[fi * 3 + (k + 1) % 3]
            val key = (minOf(u, w).toLong() shl 32) or maxOf(u, w).toLong()
            edgeCount[key] = (edgeCount[key] ?: 0) + 1
        }
        for ((key, count) in edgeCount) {
            if (count != 1) continue
            val u = (key shr 32).toInt(); val w = (key and 0xFFFFFFFFL).toInt()
            val fi = vf[u].firstCommon(vf[w]) ?: continue
            normalOf(f[fi * 3], f[fi * 3 + 1], f[fi * 3 + 2], n)
            val ex = p[w * 3] - p[u * 3]; val ey = p[w * 3 + 1] - p[u * 3 + 1]; val ez = p[w * 3 + 2] - p[u * 3 + 2]
            var bx = ey * n[2] - ez * n[1]; var by = ez * n[0] - ex * n[2]; var bz = ex * n[1] - ey * n[0]
            val bl = sqrt(bx * bx + by * by + bz * bz)
            if (bl < 1e-20) continue
            bx /= bl; by /= bl; bz /= bl
            val d = -(bx * p[u * 3] + by * p[u * 3 + 1] + bz * p[u * 3 + 2])
            val wgt = (ex * ex + ey * ey + ez * ez) * 10.0
            addPlane(u, bx, by, bz, d, wgt); addPlane(w, bx, by, bz, d, wgt)
        }

        val ver = IntArray(nv)
        val heap = PriorityQueue<Cand>(edgeCount.size + 16) { a, b -> a.cost.compareTo(b.cost) }

        fun err(o1: Int, o2: Int, x: Double, y: Double, z: Double): Double {
            fun g(k: Int) = q[o1 + k] + q[o2 + k]
            return g(0) * x * x + 2 * g(1) * x * y + 2 * g(2) * x * z + 2 * g(3) * x +
                g(4) * y * y + 2 * g(5) * y * z + 2 * g(6) * y + g(7) * z * z + 2 * g(8) * z + g(9)
        }

        fun push(u: Int, w: Int) {
            val o1 = u * 10; val o2 = w * 10
            val ux = p[u * 3]; val uy = p[u * 3 + 1]; val uz = p[u * 3 + 2]
            val wx = p[w * 3]; val wy = p[w * 3 + 1]; val wz = p[w * 3 + 2]
            val mx = (ux + wx) / 2; val my = (uy + wy) / 2; val mz = (uz + wz) / 2
            var bx = ux; var by = uy; var bz = uz; var be = err(o1, o2, ux, uy, uz)
            err(o1, o2, wx, wy, wz).let { if (it < be) { be = it; bx = wx; by = wy; bz = wz } }
            err(o1, o2, mx, my, mz).let { if (it < be) { be = it; bx = mx; by = my; bz = mz } }
            // оптимальная точка: решаем 3×3 по Крамеру
            fun g(k: Int) = q[o1 + k] + q[o2 + k]
            val a11 = g(0); val a12 = g(1); val a13 = g(2); val a22 = g(4); val a23 = g(5); val a33 = g(7)
            val r1 = -g(3); val r2 = -g(6); val r3 = -g(8)
            val det = a11 * (a22 * a33 - a23 * a23) - a12 * (a12 * a33 - a23 * a13) + a13 * (a12 * a23 - a22 * a13)
            if (abs(det) > 1e-14) {
                val x = (r1 * (a22 * a33 - a23 * a23) - a12 * (r2 * a33 - a23 * r3) + a13 * (r2 * a23 - a22 * r3)) / det
                val y = (a11 * (r2 * a33 - a23 * r3) - r1 * (a12 * a33 - a23 * a13) + a13 * (a12 * r3 - r2 * a13)) / det
                val z = (a11 * (a22 * r3 - r2 * a23) - a12 * (a12 * r3 - r2 * a13) + r1 * (a12 * a23 - a22 * a13)) / det
                val len = sqrt((ux - wx) * (ux - wx) + (uy - wy) * (uy - wy) + (uz - wz) * (uz - wz))
                val dist = sqrt((x - mx) * (x - mx) + (y - my) * (y - my) + (z - mz) * (z - mz))
                if (dist < len * 2) {
                    val e = err(o1, o2, x, y, z)
                    if (e < be) { be = e; bx = x; by = y; bz = z }
                }
            }
            heap.add(Cand(be, u, w, ver[u], ver[w], bx, by, bz))
        }

        for (key in edgeCount.keys) push((key shr 32).toInt(), (key and 0xFFFFFFFFL).toInt())

        var live = nf
        val n0 = DoubleArray(3)
        val n1 = DoubleArray(3)
        val nbr = IntBag()
        val saveU = DoubleArray(3)
        while (live > target) {
            val cnd = heap.poll() ?: break
            val u = cnd.u; val w = cnd.w
            if (ver[u] != cnd.vu || ver[w] != cnd.vw) continue
            if (!vf[u].hasCommon(vf[w], f)) continue

            // запрет переворота граней вокруг u и w
            var bad = false
            for (src in arrayOf(vf[u], vf[w])) {
                for (k in 0 until src.size) {
                    val fi = src[k]
                    val a = f[fi * 3]; val b = f[fi * 3 + 1]; val c = f[fi * 3 + 2]
                    val hasU = a == u || b == u || c == u
                    val hasW = a == w || b == w || c == w
                    if (hasU && hasW) continue
                    normalOf(a, b, c, n0)
                    // временно двигаем вершины в новую точку
                    val moved = if (hasU) u else w
                    saveU[0] = p[moved * 3]; saveU[1] = p[moved * 3 + 1]; saveU[2] = p[moved * 3 + 2]
                    p[moved * 3] = cnd.x; p[moved * 3 + 1] = cnd.y; p[moved * 3 + 2] = cnd.z
                    normalOf(a, b, c, n1)
                    p[moved * 3] = saveU[0]; p[moved * 3 + 1] = saveU[1]; p[moved * 3 + 2] = saveU[2]
                    if (n0[0] * n1[0] + n0[1] * n1[1] + n0[2] * n1[2] <= 0.0) { bad = true; break }
                }
                if (bad) break
            }
            if (bad) continue

            // схлопываем w → u
            p[u * 3] = cnd.x; p[u * 3 + 1] = cnd.y; p[u * 3 + 2] = cnd.z
            for (k in 0 until 10) q[u * 10 + k] += q[w * 10 + k]
            val wf = vf[w]
            for (k in 0 until wf.size) {
                val fi = wf[k]
                if (!alive[fi]) continue
                val a = f[fi * 3]; val b = f[fi * 3 + 1]; val c = f[fi * 3 + 2]
                if (a == u || b == u || c == u) {
                    alive[fi] = false; live--
                    for (vv in intArrayOf(a, b, c)) if (vv != w) vf[vv].remove(fi)
                } else {
                    if (a == w) f[fi * 3] = u
                    if (b == w) f[fi * 3 + 1] = u
                    if (c == w) f[fi * 3 + 2] = u
                    vf[u].add(fi)
                }
            }
            wf.clear()
            ver[u]++; ver[w]++

            nbr.clear()
            val uf = vf[u]
            for (k in 0 until uf.size) {
                val fi = uf[k]
                for (j in 0 until 3) { val vv = f[fi * 3 + j]; if (vv != u && !nbr.contains(vv)) nbr.add(vv) }
            }
            for (k in 0 until nbr.size) push(u, nbr[k])
        }

        // упаковка: только живые грани и используемые вершины
        val remap = IntArray(nv) { -1 }
        val outV = DoubleList()
        val outF = IntList()
        for (fi in 0 until nf) {
            if (!alive[fi]) continue
            for (j in 0 until 3) {
                val v = f[fi * 3 + j]
                if (remap[v] < 0) { remap[v] = outV.size / 3; outV.add(p[v * 3]); outV.add(p[v * 3 + 1]); outV.add(p[v * 3 + 2]) }
                outF.add(remap[v])
            }
        }
        return outV.toArray() to outF.toArray()
    }

    /** Центр по габаритам в 0, наибольший радиус = 1. */
    private fun normalize(v: FloatArray) {
        var minX = Float.MAX_VALUE; var minY = Float.MAX_VALUE; var minZ = Float.MAX_VALUE
        var maxX = -Float.MAX_VALUE; var maxY = -Float.MAX_VALUE; var maxZ = -Float.MAX_VALUE
        var i = 0
        while (i < v.size) {
            minX = minOf(minX, v[i]); maxX = maxOf(maxX, v[i])
            minY = minOf(minY, v[i + 1]); maxY = maxOf(maxY, v[i + 1])
            minZ = minOf(minZ, v[i + 2]); maxZ = maxOf(maxZ, v[i + 2])
            i += 3
        }
        val cx = (minX + maxX) / 2; val cy = (minY + maxY) / 2; val cz = (minZ + maxZ) / 2
        var r = 0f
        i = 0
        while (i < v.size) {
            v[i] -= cx; v[i + 1] -= cy; v[i + 2] -= cz
            r = max(r, sqrt(v[i] * v[i] + v[i + 1] * v[i + 1] + v[i + 2] * v[i + 2]))
            i += 3
        }
        if (r > 0f && abs(r) < Float.MAX_VALUE) for (k in v.indices) v[k] /= r
    }

    // ---------- компактные коллекции без боксинга ----------

    private class FloatList {
        private var a = FloatArray(1024)
        var size = 0; private set
        fun add(x: Float) { if (size == a.size) a = a.copyOf(a.size * 2); a[size++] = x }
        operator fun get(i: Int) = a[i]
        fun toArray() = a.copyOf(size)
    }

    private class DoubleList {
        private var a = DoubleArray(1024)
        var size = 0; private set
        fun add(x: Double) { if (size == a.size) a = a.copyOf(a.size * 2); a[size++] = x }
        operator fun get(i: Int) = a[i]
        operator fun set(i: Int, x: Double) { a[i] = x }
        fun toArray() = a.copyOf(size)
    }

    private class IntList {
        private var a = IntArray(1024)
        var size = 0; private set
        fun add(x: Int) { if (size == a.size) a = a.copyOf(a.size * 2); a[size++] = x }
        operator fun get(i: Int) = a[i]
        operator fun set(i: Int, x: Int) { a[i] = x }
        fun toArray() = a.copyOf(size)
    }

    /** Маленький набор int без дублей контроля — для списков граней у вершины. */
    private class IntBag {
        private var a = IntArray(8)
        var size = 0; private set
        fun add(x: Int) { if (size == a.size) a = a.copyOf(a.size * 2); a[size++] = x }
        operator fun get(i: Int) = a[i]
        fun clear() { size = 0 }
        fun contains(x: Int): Boolean { for (i in 0 until size) if (a[i] == x) return true; return false }
        fun remove(x: Int) {
            for (i in 0 until size) if (a[i] == x) { a[i] = a[size - 1]; size--; return }
        }
        fun firstCommon(o: IntBag): Int? { for (i in 0 until size) if (o.contains(a[i])) return a[i]; return null }
        /** Есть ли у двух вершин общая живая грань (вершины проверяем по самим граням). */
        fun hasCommon(o: IntBag, @Suppress("UNUSED_PARAMETER") faces: IntArray): Boolean = firstCommon(o) != null
    }
}
