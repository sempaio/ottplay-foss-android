package play.ott.nativeapp.core

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Dns
import java.net.InetAddress
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response

internal const val MAX_CATALOG_BYTES = 32 * 1024 * 1024
internal const val MAX_EPG_BYTES = 64 * 1024 * 1024

internal fun httpUrl(value: String): HttpUrl = value.trim().toHttpUrlOrNull()
    ?: throw ProviderException("Enter an HTTP or HTTPS source URL")

internal fun resolveHttp(base: String, value: String): String {
    if (value.isBlank()) return ""
    val resolved = value.trim().toHttpUrlOrNull() ?: base.toHttpUrlOrNull()?.resolve(value.trim())
    return resolved?.toString() ?: ""
}

internal fun mergedHeaders(vararg maps: Map<String, String>): Map<String, String> {
    val result = linkedMapOf<String, String>()
    maps.forEach { map -> map.forEach { (name, value) ->
        require(name.matches(Regex("[!#$%&'*+.^_`|~0-9A-Za-z-]+"))) { "Invalid HTTP header name" }
        // Validate before OkHttp, whose detailed validation exceptions can echo a secret value.
        require(value.all { it == '\t' || it.code in 32..126 }) { "Invalid HTTP header value" }
        require(name.lowercase() !in setOf("host", "content-length", "connection", "transfer-encoding")) {
            "Unsupported HTTP header"
        }
        result.keys.firstOrNull { it.equals(name, ignoreCase = true) }?.let(result::remove)
        result[name] = value
    } }
    return result
}

internal data class HttpPayload(val bytes: ByteArray, val url: String, val contentType: String?) {
    fun text(): String = bytes.toString(Charsets.UTF_8).removePrefix("\uFEFF")
}

internal class ProviderHttp(client: OkHttpClient, private val transportPolicy: RemoteTransportPolicy) {
    // Redirects are inspected explicitly so custom authentication headers cannot leak to a CDN.
    private val http = transportPolicy.secure(client).newBuilder().followRedirects(false).followSslRedirects(false).build()

    fun checkedUrl(value: String): HttpUrl = try { transportPolicy.requireHttpUrl(value) }
        catch (error: IllegalArgumentException) { throw ProviderException(error.message ?: "Invalid source address") }

    suspend fun get(url: String, headers: Map<String, String> = emptyMap(), limit: Int = MAX_CATALOG_BYTES): HttpPayload =
        request(url, headers, null, limit)

    /** Same authenticated GET and TLS hostname; only prefer the next DNS route after timeout. */
    suspend fun getOnAlternateRoute(url: String, headers: Map<String, String>): HttpPayload {
        val alternate = http.newBuilder().dns(AlternateRouteDns(http.dns, checkedUrl(url).host)).build()
        return ProviderHttp(alternate, transportPolicy).get(url, headers)
    }

    suspend fun postJson(url: String, json: String, headers: Map<String, String>): HttpPayload =
        request(url, headers, json, MAX_CATALOG_BYTES)

    private suspend fun request(initialUrl: String, initialHeaders: Map<String, String>, json: String?, limit: Int): HttpPayload {
        var url = checkedUrl(initialUrl)
        var headers = mergedHeaders(mapOf("User-Agent" to "OTTPlayNative/0.1"), initialHeaders)
        var body = json
        repeat(6) { attempt ->
            val builder = Request.Builder().url(url)
            headers.forEach { (name, value) -> builder.header(name, value) }
            body?.let { builder.post(it.toRequestBody("application/json; charset=utf-8".toMediaType())) }
            val result = execute(builder.build(), limit)
            if (result.code !in setOf(301, 302, 303, 307, 308)) {
                if (result.code !in 200..299) throw ProviderException("Provider returned HTTP ${result.code}", result.code)
                return HttpPayload(result.bytes, url.toString(), result.contentType)
            }
            if (attempt == 5) throw ProviderException("Too many source redirects")
            val next = result.location?.let(url::resolve)?.let { checkedUrl(it.toString()) }
                ?: throw ProviderException("Invalid source redirect")
            val sameOrigin = next.scheme == url.scheme && next.host == url.host && next.port == url.port
            if (!sameOrigin) {
                if (body != null) throw ProviderException("Portal redirected a credential request to another server")
                headers = headers.filterKeys { it.lowercase() in setOf("user-agent", "accept", "accept-language") }
            }
            if (result.code == 303 || (result.code in setOf(301, 302) && body != null)) body = null
            url = next
        }
        throw ProviderException("Too many source redirects")
    }

    private data class Result(val code: Int, val location: String?, val bytes: ByteArray, val contentType: String?)

    private suspend fun execute(request: Request, limit: Int): Result = suspendCancellableCoroutine { continuation ->
        val call = http.newCall(request)
        continuation.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (continuation.isActive) continuation.resumeWithException(networkFailure(e))
            }

            override fun onResponse(call: Call, response: Response) {
                try {
                    response.use {
                        // Error and redirect bodies are irrelevant and may contain credentials.
                        val bytes = if (response.isSuccessful) {
                            val body = response.body
                            if (body.contentLength() > limit) throw ProviderException("Source exceeds the supported size limit")
                            body.byteStream().use { stream ->
                                val output = ByteArrayOutputStream()
                                val buffer = ByteArray(8192)
                                while (true) {
                                    if (!continuation.isActive) return
                                    val count = stream.read(buffer)
                                    if (count == -1) break
                                    if (output.size() > limit - count) throw ProviderException("Source exceeds the supported size limit")
                                    output.write(buffer, 0, count)
                                }
                                output.toByteArray()
                            }
                        } else byteArrayOf()
                        if (continuation.isActive) continuation.resume(Result(response.code, response.header("Location"), bytes, response.header("Content-Type")))
                    }
                } catch (error: Exception) {
                    if (continuation.isActive) continuation.resumeWithException(
                        if (error is ProviderException) error else if (error is IOException) networkFailure(error) else ProviderException("Unable to read source response", failure = ProviderFailure.INVALID_RESPONSE)
                    )
                }
            }
        })
    }

    companion object {
        fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .callTimeout(90, TimeUnit.SECONDS)
            .build()
    }
}

/** Keep the diagnostic category, never the IOException text/cause (which may contain credentials). */
internal fun networkFailure(error: IOException): ProviderException = ProviderException(
    "Unable to connect to provider", failure = when (error) {
        is java.net.UnknownHostException -> ProviderFailure.DNS
        is java.net.SocketTimeoutException, is java.io.InterruptedIOException -> ProviderFailure.TIMEOUT
        is javax.net.ssl.SSLException -> ProviderFailure.TLS
        else -> ProviderFailure.CONNECTION
    }
)

/** Request-scoped DNS ordering, never a pinned IP or a substitute hostname. */
internal class AlternateRouteDns(private val delegate: Dns, private val targetHost: String) : Dns {
    override fun lookup(hostname: String): List<InetAddress> {
        val addresses = delegate.lookup(hostname)
        return if (hostname == targetHost && addresses.size > 1)
            addresses.drop(1) + addresses.take(1) else addresses
    }
}
