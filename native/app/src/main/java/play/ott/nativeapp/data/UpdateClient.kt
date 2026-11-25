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
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.HttpUrl.Companion.toHttpUrl
import play.ott.nativeapp.BuildConfig
import play.ott.nativeapp.StarlinkConfig
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

internal data class AppUpdate(val version: Long, val name: String, val packageName: String, val url: String, val sha256: String, val text: String, val mandatory: Boolean = false)

/** Only the read-only M81 update contract is used. Preview updates are never forced. */
internal class UpdateClient(private val context: Context) {
    // Force HTTP/1.1 for update downloads to avoid HTTP/2 flow-control stalls over relay tunnels.
    private val client = OkHttpClient.Builder()
        .protocols(listOf(Protocol.HTTP_1_1))
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .followRedirects(false)
        .followSslRedirects(false)
        .build()

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

    suspend fun downloadAndVerify(
        update: AppUpdate,
        onProgress: (downloadedBytes: Long, totalBytes: Long, status: String) -> Unit = { _, _, _ -> },
    ): File = withContext(Dispatchers.IO) {
        require(update.packageName == context.packageName && update.version > BuildConfig.VERSION_CODE) {
            "Versao ou pacote incompativel"
        }
        val directory = File(context.cacheDir, "updates").apply { mkdirs() }
        val file = File(directory, "verified.apk")
        val pending = File(directory, "download.part")
        // Android 11+ PackageManager.getPackageArchiveInfo requires the file path to end with ".apk".
        val staged = File(directory, "staged.apk")
        try {
            pending.delete()
            staged.delete()
            onProgress(0L, -1L, "Conectando ao servidor...")
            response(update.url).use { result ->
                require(result.isSuccessful) { "HTTP ${result.code}" }
                val totalBytes = result.body.contentLength()
                onProgress(0L, totalBytes, "Baixando atualização...")
                result.body.byteStream().use { input -> pending.outputStream().use { output ->
                    val buffer = ByteArray(64 * 1024)
                    var bytes = 0L
                    var lastReport = 0L
                    while (true) {
                        val size = input.read(buffer)
                        if (size < 0) break
                        bytes += size
                        require(bytes <= 150L * 1024 * 1024) { "APK excede limite" }
                        output.write(buffer, 0, size)
                        if (bytes - lastReport >= 128 * 1024 || (totalBytes > 0 && bytes >= totalBytes)) {
                            lastReport = bytes
                            onProgress(bytes, totalBytes, "Baixando atualização...")
                        }
                    }
                    require(bytes > 0) { "Arquivo vazio" }
                } }
            }
            onProgress(-1L, -1L, "Verificando integridade e assinatura...")
            val digest = MessageDigest.getInstance("SHA-256")
            pending.inputStream().use { input ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    val size = input.read(buffer)
                    if (size < 0) break
                    digest.update(buffer, 0, size)
                }
            }
            require(digest.digest().joinToString("") { "%02x".format(it) }.equals(update.sha256, true)) {
                "SHA-256 divergente"
            }
            if (!pending.renameTo(staged)) {
                pending.copyTo(staged, overwrite = true)
                pending.delete()
            }
            val archive = archiveInfo(staged) ?: error("APK invalido")
            val installed = installedInfo()
            require(archive.packageName == context.packageName) { "Pacote divergente" }
            val version = if (Build.VERSION.SDK_INT >= 28) archive.longVersionCode else @Suppress("DEPRECATION") archive.versionCode.toLong()
            require(version == update.version) { "Build divergente ($version != ${update.version})" }
            val installedCerts = certificates(installed)
            require(certificates(archive) == installedCerts && installedCerts.isNotEmpty()) {
                "Assinatura do APK divergente"
            }
            if (file.exists()) file.delete()
            if (!staged.renameTo(file)) {
                staged.copyTo(file, overwrite = true)
                staged.delete()
            }
            onProgress(-1L, -1L, "Abrindo instalador do Android...")
            file
        } finally {
            pending.delete()
            staged.delete()
        }
    }

    private fun archiveInfo(apkFile: File): PackageInfo? {
        val flags = if (Build.VERSION.SDK_INT >= 28) PackageManager.GET_SIGNING_CERTIFICATES else @Suppress("DEPRECATION") PackageManager.GET_SIGNATURES
        return if (Build.VERSION.SDK_INT >= 33) {
            context.packageManager.getPackageArchiveInfo(apkFile.path, PackageManager.PackageInfoFlags.of(flags.toLong()))
        } else {
            @Suppress("DEPRECATION")
            context.packageManager.getPackageArchiveInfo(apkFile.path, flags)
        }
    }

    private fun installedInfo(): PackageInfo {
        val flags = if (Build.VERSION.SDK_INT >= 28) PackageManager.GET_SIGNING_CERTIFICATES else @Suppress("DEPRECATION") PackageManager.GET_SIGNATURES
        return if (Build.VERSION.SDK_INT >= 33) {
            context.packageManager.getPackageInfo(context.packageName, PackageManager.PackageInfoFlags.of(flags.toLong()))
        } else {
            @Suppress("DEPRECATION")
            context.packageManager.getPackageInfo(context.packageName, flags)
        }
    }

    fun requestInstall(file: File): Boolean {
        if (!context.packageManager.canRequestPackageInstalls()) {
            context.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, ("package:" + context.packageName).toUri()).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            return false
        }
        val uri = FileProvider.getUriForFile(context, context.packageName + ".updates", file)
        context.startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/vnd.android.package-archive")
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION))
        return true
    }

    fun openInBrowser(address: String) {
        val url = address.toHttpUrl()
        require(url.isHttps && url.username.isEmpty() && url.password.isEmpty())
        context.startActivity(Intent(Intent.ACTION_VIEW, url.toString().toUri()).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    private fun certificates(info: PackageInfo): Set<String> {
        val signatures = if (Build.VERSION.SDK_INT >= 28) {
            info.signingInfo?.let { signing ->
                signing.apkContentsSigners?.takeIf { it.isNotEmpty() } ?: signing.signingCertificateHistory
            }
        } else {
            @Suppress("DEPRECATION") info.signatures
        }
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
    val mandatory = string("force_upgrade").lowercase() in setOf("1", "true")
    return AppUpdate(version, string("version_name"), packageName, url.toString(), checksum, string("text").take(4000), mandatory)
}

/** An optional notice can be deferred for this launch; mandatory notices cannot. */
internal fun shouldShowUpdatePrompt(version: Long?, dismissedVersion: Long?, mandatory: Boolean): Boolean =
    version != null && (mandatory || dismissedVersion != version)

/** Preserve optional QA variants: only the official signed package can be blocked. */
internal fun effectiveMandatoryUpdate(requested: Boolean, packageName: String): Boolean =
    requested && packageName == "br.starlink.iptv"
