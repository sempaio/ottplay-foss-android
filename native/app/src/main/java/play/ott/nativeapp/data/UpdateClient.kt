package play.ott.nativeapp.data

import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import androidx.core.net.toUri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.HttpUrl.Companion.toHttpUrl
import play.ott.nativeapp.BuildConfig
import play.ott.nativeapp.StarlinkConfig
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

internal data class AppUpdate(val version: Long, val name: String, val packageName: String, val url: String, val sha256: String, val text: String)

/** Only the read-only M81 update contract is used. Preview updates are always optional. */
internal class UpdateClient(private val context: Context) {
    private val client = OkHttpClient.Builder().connectTimeout(8, TimeUnit.SECONDS).readTimeout(30, TimeUnit.SECONDS)
        .followRedirects(false).followSslRedirects(false).build()

    suspend fun latest(): AppUpdate? = withContext(Dispatchers.IO) {
        val url = (StarlinkConfig.XTREAM_BASE_URL + "/app/update/latest").toHttpUrl().newBuilder()
            .addQueryParameter("package_name", context.packageName).build().toString()
        response(url).use { result ->
            require(result.isSuccessful)
            val raw = result.body.byteStream().use { readBounded(it, 256 * 1024 + 1) }
            require(raw.size <= 256 * 1024)
            parseUpdate(raw.decodeToString(), context.packageName, BuildConfig.VERSION_CODE.toLong())
        }
    }

    private fun response(address: String): okhttp3.Response {
        var url = address.toHttpUrl()
        repeat(6) {
            require(url.isHttps && url.username.isEmpty() && url.password.isEmpty())
            val result = client.newCall(Request.Builder().url(url).build()).execute()
            if (result.code !in listOf(301, 302, 303, 307, 308)) return result
            val location = result.header("Location")
            result.close()
            url = location?.let { url.resolve(it) } ?: error("Invalid update redirect")
        }
        error("Too many update redirects")
    }

    suspend fun downloadAndVerify(update: AppUpdate): File = withContext(Dispatchers.IO) {
        require(update.packageName == context.packageName && update.version > BuildConfig.VERSION_CODE)
        val directory = File(context.cacheDir, "updates").apply { mkdirs() }
        val file = File(directory, "verified.apk")
        val pending = File(directory, "download.part")
        try {
            response(update.url).use { result ->
                require(result.isSuccessful)
                result.body.byteStream().use { input -> pending.outputStream().use { output ->
                    val buffer = ByteArray(8192)
                    var bytes = 0L
                    while (true) {
                        val size = input.read(buffer)
                        if (size < 0) break
                        bytes += size
                        require(bytes <= 150L * 1024 * 1024)
                        output.write(buffer, 0, size)
                    }
                    require(bytes > 0)
                } }
            }
            val digest = MessageDigest.getInstance("SHA-256")
            pending.inputStream().use { input -> val buffer = ByteArray(8192); while (true) { val size = input.read(buffer); if (size < 0) break; digest.update(buffer, 0, size) } }
            require(digest.digest().joinToString("") { "%02x".format(it) }.equals(update.sha256, true))
            val flags = if (Build.VERSION.SDK_INT >= 28) PackageManager.GET_SIGNING_CERTIFICATES else @Suppress("DEPRECATION") PackageManager.GET_SIGNATURES
            @Suppress("DEPRECATION") val archive = context.packageManager.getPackageArchiveInfo(pending.path, flags) ?: error("Invalid APK")
            @Suppress("DEPRECATION") val installed = context.packageManager.getPackageInfo(context.packageName, flags)
            require(archive.packageName == context.packageName)
            val version = if (Build.VERSION.SDK_INT >= 28) archive.longVersionCode else @Suppress("DEPRECATION") archive.versionCode.toLong()
            require(version == update.version)
            require(certificates(archive) == certificates(installed) && certificates(installed).isNotEmpty())
            require(pending.renameTo(file))
            file
        } finally { pending.delete() }
    }

    fun requestInstall(file: File) {
        if (!context.packageManager.canRequestPackageInstalls()) {
            context.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, ("package:" + context.packageName).toUri()).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            return
        }
        val uri = FileProvider.getUriForFile(context, context.packageName + ".updates", file)
        context.startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/vnd.android.package-archive")
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION))
    }

    private fun certificates(info: PackageInfo): Set<String> {
        val signatures = if (Build.VERSION.SDK_INT >= 28) info.signingInfo?.apkContentsSigners else @Suppress("DEPRECATION") info.signatures
        return signatures.orEmpty().map { signature -> MessageDigest.getInstance("SHA-256").digest(signature.toByteArray()).joinToString("") { "%02x".format(it) } }.toSet()
    }
}

internal fun parseUpdate(raw: String, packageName: String, currentVersion: Long): AppUpdate? {
    val root = Json.parseToJsonElement(raw).jsonObject
    require(root["code"]?.jsonPrimitive?.int == 200)
    val result = root["result"] ?: return null
    if (result is JsonNull) return null
    val obj = result.jsonObject
    fun string(key: String) = obj[key]?.jsonPrimitive?.contentOrNull.orEmpty()
    val version = string("version").toLongOrNull() ?: error("Invalid update version")
    if (string("package_name") != packageName || version <= currentVersion) return null
    val url = string("url").toHttpUrl()
    require(url.isHttps && url.username.isEmpty() && url.password.isEmpty())
    val checksum = string("sha256")
    require(checksum.matches(Regex("[a-fA-F0-9]{64}")))
    return AppUpdate(version, string("version_name"), packageName, url.toString(), checksum, string("text").take(4000))
}
