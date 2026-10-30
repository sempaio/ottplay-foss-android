package play.ott.nativeapp.security

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.net.InetAddress
import java.util.concurrent.TimeUnit
import okhttp3.ConnectionSpec
import okhttp3.Dns
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.TlsVersion
import org.json.JSONObject
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import play.ott.nativeapp.core.RemoteTransportPolicy

/** Explicit opt-in, public health only; no sources, credentials, catalogs or media. */
@RunWith(AndroidJUnit4::class)
class M81NetworkDiagnosticTest {
    @Test fun compareAndroidTlsRoutes() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("m81NetworkProbe") == "true")
        val host = "panel.taila5c5c2.ts.net"
        val addresses = InetAddress.getAllByName(host).toList()
        for (address in addresses) for (mode in listOf("modern", "tls12")) {
            val started = System.nanoTime()
            val builder = OkHttpClient.Builder()
                .connectTimeout(4, TimeUnit.SECONDS).readTimeout(5, TimeUnit.SECONDS)
                .callTimeout(10, TimeUnit.SECONDS)
                .dns(object : Dns { override fun lookup(hostname: String): List<InetAddress> =
                    if (hostname == host) listOf(address) else Dns.SYSTEM.lookup(hostname) })
            if (mode == "tls12") builder.connectionSpecs(listOf(
                ConnectionSpec.Builder(ConnectionSpec.MODERN_TLS).tlsVersions(TlsVersion.TLS_1_2).build()))
            val client = RemoteTransportPolicy.HTTP_COMPATIBLE.secure(builder.build())
            val result = JSONObject().put("ip", address.hostAddress).put("mode", mode)
            try {
                client.newCall(Request.Builder().url("https://$host/health").build()).execute().use {
                    result.put("status", it.code).put("tls", it.handshake?.tlsVersion?.javaName)
                }
            } catch (error: Exception) { result.put("error", error.javaClass.simpleName) }
            finally {
                result.put("ms", (System.nanoTime() - started) / 1_000_000)
                Log.i("M81NetworkProbe", result.toString())
                client.connectionPool.evictAll()
                client.dispatcher.executorService.shutdown()
            }
        }
    }
}
