package play.ott.nativeapp.core

import java.io.IOException
import okhttp3.ConnectionSpec
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Interceptor
import okhttp3.OkHttpClient

/** Explicit distribution policy: credentials cannot be reliably detected from URL spelling. */
class RemoteTransportPolicy(val allowInsecureHttp: Boolean) {
    fun requireHttpUrl(value: String): HttpUrl {
        val url = value.trim().toHttpUrlOrNull()
            ?: throw IllegalArgumentException("Enter a valid HTTP or HTTPS address")
        require(allowInsecureHttp || url.isHttps) { HTTPS_REQUIRED }
        return url
    }

    /**
     * Apply at the HTTP boundary, not just at source entry. Media manifests may introduce
     * segments, keys, subtitles or other URLs long after the original URL was checked.
     * The application guard covers initial/cached requests; the network guard also covers
     * OkHttp redirects and authenticator follow-ups. TLS-only connection specs reject HTTP
     * before a connection is opened, including a redirect on a platform that allows HTTP.
     */
    fun secure(client: OkHttpClient): OkHttpClient {
        val builder = client.newBuilder()
        if (TlsCertificateKeyPolicy !in client.networkInterceptors) {
            builder.addNetworkInterceptor(TlsCertificateKeyPolicy)
        }
        if (allowInsecureHttp) return builder.build()
        val guard = Interceptor { chain ->
            if (!chain.request().url.isHttps) throw IOException(HTTPS_REQUIRED)
            chain.proceed(chain.request())
        }
        return builder
            .addInterceptor(guard)
            .addNetworkInterceptor(guard)
            .connectionSpecs(listOf(ConnectionSpec.MODERN_TLS))
            .build()
    }

    companion object {
        const val HTTPS_REQUIRED = "This distribution requires HTTPS for every network request"
        val HTTPS_ONLY = RemoteTransportPolicy(allowInsecureHttp = false)
        val HTTP_COMPATIBLE = RemoteTransportPolicy(allowInsecureHttp = true)
    }
}
