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
import java.util.zip.ZipInputStream
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.sqrt

/** Каркас для отрисовки: вершины (x,y,z подряд, нормированы в радиус 1) и пары индексов рёбер. */
class WireMesh(val verts: FloatArray, val edges: IntArray) {
    val vertexCount get() = verts.size / 3
    val edgeCount get() = edges.size / 2
}

/**
 * Модели для обоев: встроенная уточка, свой файл (.glb / .gltf в .zip) и Sketchfab по ссылке.
 * Любая модель упрощается кластеризацией вершин до ~MAX_EDGES рёбер — получается
 * лёгкий светящийся каркас в духе low-poly.
 */
object Meshes {

    private const val FILE = "model.bin"
    private const val MAX_EDGES = 1800
    private const val MAX_TRIS = 4_000_000
    private const val MAX_DOWNLOAD = 120L * 1024 * 1024

    // ---------- хранение ----------

    fun saved(ctx: Context): WireMesh? = runCatching {
        DataInputStream(File(ctx.filesDir, FILE).inputStream().buffered()).use { inp ->
            val nv = inp.readInt()
            val v = FloatArray(nv) { inp.readFloat() }
            val ne = inp.readInt()
            val e = IntArray(ne) { inp.readInt() }
            WireMesh(v, e)
        }
    }.getOrNull()

    fun save(ctx: Context, m: WireMesh) {
        val tmp = File(ctx.filesDir, "$FILE.tmp")
        DataOutputStream(tmp.outputStream().buffered()).use { out ->
            out.writeInt(m.verts.size)
            m.verts.forEach { out.writeFloat(it) }
            out.writeInt(m.edges.size)
            m.edges.forEach { out.writeInt(it) }
        }
        if (!tmp.renameTo(File(ctx.filesDir, FILE))) throw IOException("не смог сохранить модель")
    }

    // ---------- встроенная уточка ----------

    fun duck(): WireMesh {
        val verts = ArrayList<Float>()
        val edges = ArrayList<Int>()
        fun ellipsoid(c: FloatArray, r: FloatArray, nLat: Int, nLon: Int, deform: ((FloatArray) -> Unit)?) {
            val base = verts.size / 3
            // полюса — по одной вершине
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
            for (i in 0..nLat) for (j in 0 until nLon) {
                val n = (j + 1) % nLon
                if (i in 1 until nLat) { edges.add(idx[i][j]); edges.add(idx[i][n]) }
                if (i < nLat) {
                    edges.add(idx[i][j]); edges.add(idx[i + 1][j])
                    if (i in 1 until nLat - 1) { edges.add(idx[i][j]); edges.add(idx[i + 1][n]) }
                }
            }
            if (base < 0) throw IllegalStateException()
        }
        ellipsoid(floatArrayOf(0f, 0f, 0f), floatArrayOf(1.15f, 0.62f, 0.8f), 6, 12) { p ->
            if (p[0] < -0.55f) p[1] += (-p[0] - 0.55f) * (if (p[1] > -0.1f) 1.0f else 0.45f)
            if (p[1] < -0.42f) p[1] = -0.42f + (p[1] + 0.42f) * 0.4f
        }
        ellipsoid(floatArrayOf(0.72f, 0.86f, 0f), floatArrayOf(0.43f, 0.43f, 0.43f), 6, 10, null)
        ellipsoid(floatArrayOf(1.2f, 0.76f, 0f), floatArrayOf(0.3f, 0.085f, 0.2f), 2, 8, null)
        val v = verts.toFloatArray()
        normalize(v)
        return WireMesh(v, edges.toIntArray())
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
        return simplifyToWire(pos, tris)
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

    /** Перебирает размер сетки кластеризации, пока число рёбер не влезет в бюджет. */
    private fun simplifyToWire(pos: FloatArray, tris: IntArray): WireMesh {
        var minX = Float.MAX_VALUE; var minY = Float.MAX_VALUE; var minZ = Float.MAX_VALUE
        var maxX = -Float.MAX_VALUE; var maxY = -Float.MAX_VALUE; var maxZ = -Float.MAX_VALUE
        var i = 0
        while (i < pos.size) {
            val x = pos[i]; val y = pos[i + 1]; val z = pos[i + 2]
            if (x.isFinite() && y.isFinite() && z.isFinite()) {
                if (x < minX) minX = x; if (y < minY) minY = y; if (z < minZ) minZ = z
                if (x > maxX) maxX = x; if (y > maxY) maxY = y; if (z > maxZ) maxZ = z
            }
            i += 3
        }
        val extent = max(maxX - minX, max(maxY - minY, maxZ - minZ))
        if (!(extent > 0f)) throw IOException("модель пустая или плоская")

        var best: WireMesh? = null
        for (grid in intArrayOf(56, 44, 34, 26, 20, 15, 11, 8)) {
            val cell = extent / grid
            val cluster = HashMap<Long, Int>()
            val sum = FloatList()
            val cnt = IntList()
            val map = IntArray(pos.size / 3)
            for (v in map.indices) {
                val x = pos[v * 3]; val y = pos[v * 3 + 1]; val z = pos[v * 3 + 2]
                val ix = ((x - minX) / cell).toInt().coerceIn(0, grid)
                val iy = ((y - minY) / cell).toInt().coerceIn(0, grid)
                val iz = ((z - minZ) / cell).toInt().coerceIn(0, grid)
                val key = (ix.toLong() * 4096 + iy) * 4096 + iz
                val id = cluster.getOrPut(key) {
                    sum.add(0f); sum.add(0f); sum.add(0f); cnt.add(0); cnt.size - 1
                }
                sum[id * 3] += x; sum[id * 3 + 1] += y; sum[id * 3 + 2] += z
                cnt[id] = cnt[id] + 1
                map[v] = id
            }
            val edgeSet = HashSet<Long>()
            val edges = IntList()
            var t = 0
            while (t + 2 < tris.size) {
                val a = map.getOrElse(tris[t]) { -1 }
                val b = map.getOrElse(tris[t + 1]) { -1 }
                val c = map.getOrElse(tris[t + 2]) { -1 }
                t += 3
                if (a < 0 || b < 0 || c < 0 || a == b || b == c || a == c) continue
                for ((p, q) in arrayOf(a to b, b to c, c to a)) {
                    val lo = minOf(p, q); val hi = maxOf(p, q)
                    if (edgeSet.add(lo.toLong() shl 32 or hi.toLong())) { edges.add(lo); edges.add(hi) }
                }
                if (edges.size / 2 > MAX_EDGES * 4) break
            }
            val verts = FloatArray(cnt.size * 3)
            for (k in 0 until cnt.size) {
                val n = cnt[k].toFloat()
                verts[k * 3] = sum[k * 3] / n; verts[k * 3 + 1] = sum[k * 3 + 1] / n; verts[k * 3 + 2] = sum[k * 3 + 2] / n
            }
            val wire = WireMesh(verts, edges.toArray())
            best = wire
            if (wire.edgeCount <= MAX_EDGES) break
        }
        val result = best ?: throw IOException("не удалось упростить модель")
        val v = result.verts
        normalize(v)
        return WireMesh(v, compactEdges(result.edges, MAX_EDGES))
    }

    private fun compactEdges(e: IntArray, limit: Int): IntArray =
        if (e.size / 2 <= limit) e else e.copyOf(limit * 2)

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

    // ---------- компактные списки без боксинга ----------

    private class FloatList {
        private var a = FloatArray(1024)
        var size = 0; private set
        fun add(x: Float) { if (size == a.size) a = a.copyOf(a.size * 2); a[size++] = x }
        operator fun get(i: Int) = a[i]
        operator fun set(i: Int, x: Float) { a[i] = x }
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
}
