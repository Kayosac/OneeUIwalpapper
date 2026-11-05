package com.kayo.infowall

import org.json.JSONObject
import java.io.IOException
import java.net.URL
import java.security.MessageDigest
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

/** Строка подключения из install.sh: iwa1|ИМЯ|https://IP:ПОРТ|ТОКЕН|SHA256-отпечаток. */
data class ServerConf(val name: String, val url: String, val token: String, val fp: String)

data class ServerMetrics(val cpu: Float, val ramPct: Float, val diskPct: Float, val uptimeSec: Long)

/** Что видно на обоях: последние цифры и жив ли сервер. */
class ServerView(val name: String, val metrics: ServerMetrics?, val online: Boolean?, val error: String?)

object Servers {

    const val MAX = 2
    private const val MAX_BYTES = 16 * 1024

    fun parse(s: String): List<ServerConf> = s.lines()
        .mapNotNull { parseLine(it) }
        .distinctBy { it.url }
        .take(MAX)

    fun parseLine(line: String): ServerConf? {
        val p = line.trim().split('|')
        if (p.size != 5 || p[0] != "iwa1") return null
        val name = p[1].trim().take(10).ifEmpty { "VPS" }
        val url = p[2].trim().trimEnd('/')
        val token = p[3].trim()
        val fp = p[4].trim().lowercase().replace(":", "")
        if (!url.startsWith("https://")) return null
        if (token.length < 24 || !fp.matches(Regex("[0-9a-f]{64}"))) return null
        return ServerConf(name, url, token, fp)
    }

    /**
     * Один GET /metrics. Сертификат сервера самоподписанный, поэтому системным CA не проверяется:
     * принимаем ровно тот сертификат, чей SHA-256 записан в строке подключения — подмена невозможна.
     */
    @Throws(IOException::class)
    fun fetch(s: ServerConf): ServerMetrics {
        val conn = URL(s.url + "/metrics").openConnection() as? HttpsURLConnection
            ?: throw IOException("нужен https")
        conn.sslSocketFactory = pinned(s.fp)
        conn.hostnameVerifier = javax.net.ssl.HostnameVerifier { _, _ -> true }   // адрес — IP, доверие держится на отпечатке
        conn.connectTimeout = 5_000
        conn.readTimeout = 6_000
        conn.useCaches = false
        conn.instanceFollowRedirects = false
        conn.setRequestProperty("Authorization", "Bearer ${s.token}")
        conn.setRequestProperty("Accept", "application/json")
        try {
            val code = conn.responseCode
            if (code == 401) throw IOException("неверный токен")
            if (code !in 200..299) throw IOException("HTTP $code")
            val bytes = conn.inputStream.use { Fonts.readLimited(it, MAX_BYTES.toLong()) }
            val o = JSONObject(String(bytes, Charsets.UTF_8))
            fun pct(used: String, total: String): Float {
                val t = o.optDouble(total, 0.0)
                return if (t > 0) (o.optDouble(used, 0.0) / t * 100.0).toFloat().coerceIn(0f, 100f) else -1f
            }
            return ServerMetrics(
                cpu = o.optDouble("cpu", -1.0).toFloat(),
                ramPct = pct("ram_used", "ram_total"),
                diskPct = pct("disk_used", "disk_total"),
                uptimeSec = o.optLong("uptime", 0L),
            )
        } finally {
            conn.disconnect()
        }
    }

    private val factories = HashMap<String, SSLSocketFactory>()

    @Synchronized
    private fun pinned(fp: String): SSLSocketFactory = factories.getOrPut(fp) {
        val tm = object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) =
                throw CertificateException("client certs not used")

            override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {
                val leaf = chain?.firstOrNull() ?: throw CertificateException("нет сертификата")
                val got = MessageDigest.getInstance("SHA-256").digest(leaf.encoded)
                    .joinToString("") { "%02x".format(it) }
                if (!MessageDigest.isEqual(got.toByteArray(), fp.toByteArray())) throw CertificateException("чужой сертификат")
            }

            override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
        }
        SSLContext.getInstance("TLS").apply { init(null, arrayOf<TrustManager>(tm), null) }.socketFactory
    }
}
