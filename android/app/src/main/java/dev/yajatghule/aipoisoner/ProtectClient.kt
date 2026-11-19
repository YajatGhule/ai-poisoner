package dev.yajatghule.aipoisoner

import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * Talks to the AI Poisoner backend that runs on the person's own computer (poisoner/ in this repo).
 * Plain HttpURLConnection, no extra libraries. Every call blocks: run them off the main thread.
 *
 * The connection is plain HTTP on the home network, see res/xml/network_security_config.xml for why that is allowed.
 */
object ProtectClient {
    const val DEFAULT_PORT = 8765
    /** The backend refuses bigger uploads (413). We check first so the person isn't made to wait for an upload to fail. */
    const val MAX_UPLOAD = 30L * 1024 * 1024
    private const val MAX_DOWNLOAD = 128 * 1024 * 1024

    /** The server answered with an error status. [detail] is its own explanation when it gave one. */
    class ApiException(val code: Int, val detail: String) : IOException("HTTP $code: $detail")

    /** Something answered, but it isn't an AI Poisoner backend. */
    class NotAipServer(message: String) : IOException(message)

    data class Health(val version: String, val device: String, val tokenRequired: Boolean)

    /** The few numbers from a finished job that are worth explaining to a person. */
    data class Numbers(val psnrDb: Double?, val ssim: Double?, val maxChange: Double?, val timesRandomNoise: Double?)

    data class JobStatus(
        val status: String,
        val step: Int,
        val steps: Int,
        val etaSeconds: Int?,
        val queuePosition: Int,
        val error: String?,
        val numbers: Numbers?,
        val seconds: Double?,
    )

    /** "192.168.1.20", "http://192.168.1.20:8765/" -> "http://192.168.1.20:8765", or null if it can't be an address. */
    fun baseUrl(address: String): String? {
        var a = address.trim().removePrefix("http://").removePrefix("HTTP://").trimEnd('/')
        if (a.isEmpty() || a.any { it.isWhitespace() || it == '/' || it == '@' || it == '?' || it == '#' }) return null
        if ("://" in a) return null                      // https:// and friends: the backend speaks plain HTTP only
        if (!a.contains(':')) a += ":$DEFAULT_PORT"
        val port = a.substringAfterLast(':').toIntOrNull() ?: return null
        if (port !in 1..65535 || a.substringBeforeLast(':').isEmpty()) return null
        return "http://$a"
    }

    fun health(base: String): Health {
        val body = try {
            call(base, "/api/health", "", readMs = 8000)
        } catch (e: ApiException) {
            throw NotAipServer("It answered with HTTP ${e.code}")
        }
        val o = try { JSONObject(String(body)) } catch (e: Exception) { throw NotAipServer("It did not answer like AI Poisoner") }
        if (o.optString("name") != "aipoisoner") throw NotAipServer("It did not answer like AI Poisoner")
        return Health(o.optString("version", "?"), o.optString("device", "cpu"), o.optBoolean("token_required", false))
    }

    /** /api/health needs no token, so the token is proved with a call that does: a made-up job id gives 401 or 404. */
    fun verifyToken(base: String, token: String) {
        try {
            call(base, "/api/jobs/token-check", token, readMs = 8000)
        } catch (e: ApiException) {
            if (e.code != 404) throw e
        }
    }

    /** Uploads the photo and returns the job id. The result is always a PNG: the lossless choice. */
    fun submit(base: String, token: String, name: String, data: ByteArray, strength: String, mode: String): String {
        val boundary = "----aip" + System.nanoTime().toString(16)
        fun field(n: String, v: String) =
            "--$boundary\r\nContent-Disposition: form-data; name=\"$n\"\r\n\r\n$v\r\n"
        val safeName = name.replace(Regex("[\"\\r\\n\\\\]"), "_").ifEmpty { "image" }
        val head = (field("strength", strength) + field("mode", mode) + field("format", "png") +
            "--$boundary\r\nContent-Disposition: form-data; name=\"file\"; filename=\"$safeName\"\r\n" +
            "Content-Type: application/octet-stream\r\n\r\n").toByteArray()
        val tail = "\r\n--$boundary--\r\n".toByteArray()
        val reply = call(base, "/api/protect", token, method = "POST", readMs = 120_000) { c ->
            c.doOutput = true
            c.setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
            c.setFixedLengthStreamingMode(head.size.toLong() + data.size + tail.size)   // no second copy of the photo in memory
            c.outputStream.use { out -> out.write(head); out.write(data); out.write(tail) }
        }
        return JSONObject(String(reply)).getString("id")
    }

    fun status(base: String, token: String, id: String): JobStatus {
        val o = JSONObject(String(call(base, "/api/jobs/$id", token)))
        fun num(obj: JSONObject, k: String) = if (obj.isNull(k) || !obj.has(k)) null else obj.optDouble(k).takeIf { !it.isNaN() }
        val m = o.optJSONObject("metrics")
        return JobStatus(
            status = o.optString("status"),
            step = o.optInt("step"),
            steps = o.optInt("steps"),
            etaSeconds = if (o.isNull("eta_seconds")) null else o.optInt("eta_seconds"),
            queuePosition = o.optInt("queue_position"),
            error = if (o.isNull("error")) null else o.optString("error"),
            numbers = m?.let { Numbers(num(it, "psnr_db"), num(it, "ssim"), num(it, "max_change"), num(it, "times_random_noise")) },
            seconds = num(o, "seconds"),
        )
    }

    fun image(base: String, token: String, id: String): ByteArray = call(base, "/api/jobs/$id/image", token, readMs = 60_000)

    /** Cancels a waiting or running job, or makes the server forget a finished one. */
    fun delete(base: String, token: String, id: String) {
        call(base, "/api/jobs/$id", token, method = "DELETE")
    }

    private fun call(
        base: String, path: String, token: String, method: String = "GET",
        connectMs: Int = 6000, readMs: Int = 15_000, prepare: ((HttpURLConnection) -> Unit)? = null,
    ): ByteArray {
        val c = URL(base + path).openConnection() as HttpURLConnection
        try {
            c.requestMethod = method
            c.connectTimeout = connectMs
            c.readTimeout = readMs
            c.instanceFollowRedirects = false
            c.useCaches = false
            if (token.isNotBlank()) c.setRequestProperty("X-AIP-Token", token.trim())
            prepare?.invoke(c)
            val code = c.responseCode
            if (code in 200..299) return c.inputStream.use { readAll(it, MAX_DOWNLOAD) }
            val err = c.errorStream?.use { readAll(it, 64 * 1024) } ?: ByteArray(0)
            val detail = runCatching { JSONObject(String(err)).optString("detail") }.getOrDefault("")
            throw ApiException(code, detail)
        } finally {
            c.disconnect()
        }
    }

    private fun readAll(input: InputStream, max: Int): ByteArray {
        val out = ByteArrayOutputStream()
        val buf = ByteArray(64 * 1024)
        while (true) {
            val n = input.read(buf)
            if (n < 0) break
            out.write(buf, 0, n)
            if (out.size() > max) throw IOException("The reply is too large")
        }
        return out.toByteArray()
    }
}
