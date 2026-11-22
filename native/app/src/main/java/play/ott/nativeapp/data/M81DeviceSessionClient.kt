package play.ott.nativeapp.data

import android.content.Context
import android.util.AtomicFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import play.ott.nativeapp.StarlinkConfig
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.UUID
import java.util.concurrent.TimeUnit

@Serializable
internal data class M81DeviceSession(
    val deviceId: String,
    val sessionToken: String? = null,
    val clientName: String = "",
    val status: String = "",
)

internal sealed interface M81SessionResult {
    data class Authorized(
        val sessionToken: String,
        val clientName: String,
        val status: String,
    ) : M81SessionResult

    data object RevokedOrExpired : M81SessionResult
    data class Denied(val code: String) : M81SessionResult
    data object RateLimited : M81SessionResult
    data object Unavailable : M81SessionResult
}

internal interface M81DeviceSessionApi {
    suspend fun login(username: String, password: String, deviceId: String): M81SessionResult
    suspend fun validate(sessionToken: String, deviceId: String): M81SessionResult
    suspend fun logout(sessionToken: String, deviceId: String)
}

internal sealed interface M81SessionSyncOutcome {
    data class Active(val clientName: String) : M81SessionSyncOutcome
    data object Revoked : M81SessionSyncOutcome
    data class Denied(val code: String) : M81SessionSyncOutcome
    data object Deferred : M81SessionSyncOutcome
}

/**
 * Handles M81 login during SaveSource. If switching to a different username, revokes/clears
 * any previous account's cached session token before attempting login so a fail-open
 * response never leaks the previous user's token or client name.
 */
internal suspend fun loginM81DeviceSession(
    api: M81DeviceSessionApi,
    store: M81DeviceSessionStore,
    previousUsername: String?,
    username: String,
    password: String,
): M81SessionResult {
    val trimmedUser = username.trim()
    val accountChanged = !previousUsername.isNullOrBlank() &&
        !previousUsername.trim().equals(trimmedUser, ignoreCase = true)
    val stored = withContext(Dispatchers.IO) { store.readOrCreate() }
    if (accountChanged) {
        if (!stored.sessionToken.isNullOrBlank()) {
            api.logout(stored.sessionToken, stored.deviceId)
        }
        withContext(Dispatchers.IO) { store.clearToken() }
    }
    val result = api.login(trimmedUser, password, stored.deviceId)
    if (result is M81SessionResult.Authorized) {
        withContext(Dispatchers.IO) {
            store.saveToken(result.sessionToken, result.clientName, result.status)
        }
    }
    return result
}

/**
 * Validates an existing M81 device session or recovers a missing token after a prior
 * fail-open login so the device reclaims its slot in the 3-device limit automatically.
 */
internal suspend fun syncM81DeviceSession(
    api: M81DeviceSessionApi,
    store: M81DeviceSessionStore,
    username: String,
    password: String,
): M81SessionSyncOutcome {
    val stored = withContext(Dispatchers.IO) { store.readOrCreate() }
    val token = stored.sessionToken
    val result = if (!token.isNullOrBlank()) {
        api.validate(token, stored.deviceId)
    } else {
        if (username.isBlank() || password.isBlank()) return M81SessionSyncOutcome.Deferred
        api.login(username, password, stored.deviceId)
    }
    return when (result) {
        is M81SessionResult.Authorized -> {
            withContext(Dispatchers.IO) {
                store.saveToken(result.sessionToken, result.clientName, result.status)
            }
            M81SessionSyncOutcome.Active(result.clientName)
        }
        M81SessionResult.RevokedOrExpired -> M81SessionSyncOutcome.Revoked
        is M81SessionResult.Denied -> M81SessionSyncOutcome.Denied(result.code)
        M81SessionResult.RateLimited,
        M81SessionResult.Unavailable -> M81SessionSyncOutcome.Deferred
    }
}

/**
 * Integrates with the M81 panel's 3-device session contract:
 * - POST /app/auth/login
 * - POST /app/auth/validate
 * - POST /app/auth/logout
 */
internal class M81DeviceSessionClient(
    private val baseUrl: String = StarlinkConfig.XTREAM_BASE_URL,
    private val requireHttps: Boolean = true,
) : M81DeviceSessionApi {
    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()
    private val http = OkHttpClient.Builder()
        .connectTimeout(6, TimeUnit.SECONDS)
        .readTimeout(6, TimeUnit.SECONDS)
        .followRedirects(false)
        .followSslRedirects(false)
        .build()

    override suspend fun login(username: String, password: String, deviceId: String): M81SessionResult =
        withContext(Dispatchers.IO) {
            val payload = buildJsonObject {
                put("login", username.trim())
                put("password", password)
                put("device_id", deviceId.trim())
            }.toString()
            postJson("/app/auth/login", payload, fallbackToken = null)
        }

    override suspend fun validate(sessionToken: String, deviceId: String): M81SessionResult =
        withContext(Dispatchers.IO) {
            val payload = buildJsonObject {
                put("session_token", sessionToken.trim())
                put("device_id", deviceId.trim())
            }.toString()
            postJson("/app/auth/validate", payload, fallbackToken = sessionToken.trim())
        }

    override suspend fun logout(sessionToken: String, deviceId: String) {
        withContext(Dispatchers.IO) {
            val payload = buildJsonObject {
                put("session_token", sessionToken.trim())
                put("device_id", deviceId.trim())
            }.toString()
            runCatching {
                val endpoint = (baseUrl.trimEnd('/') + "/app/auth/logout").toHttpUrl()
                require((!requireHttps || endpoint.isHttps) && endpoint.username.isEmpty() && endpoint.password.isEmpty())
                val request = Request.Builder()
                    .url(endpoint)
                    .post(payload.toRequestBody(jsonMediaType))
                    .build()
                http.newCall(request).execute().close()
            }
        }
    }

    private fun postJson(path: String, body: String, fallbackToken: String?): M81SessionResult {
        return try {
            val endpoint = (baseUrl.trimEnd('/') + path).toHttpUrl()
            require((!requireHttps || endpoint.isHttps) && endpoint.username.isEmpty() && endpoint.password.isEmpty())
            val request = Request.Builder()
                .url(endpoint)
                .post(body.toRequestBody(jsonMediaType))
                .build()
            http.newCall(request).execute().use { response ->
                val raw = readBoundedBody(response)
                parseSessionResponse(response.code, raw, fallbackToken)
            }
        } catch (_: Exception) {
            M81SessionResult.Unavailable
        }
    }

    private fun readBoundedBody(response: okhttp3.Response): String {
        val output = ByteArrayOutputStream()
        response.body.byteStream().use { input ->
            val buffer = ByteArray(4096)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                if (output.size() + count > 32 * 1024) error("Response exceeds 32 KiB limit")
                output.write(buffer, 0, count)
            }
        }
        return output.toString(Charsets.UTF_8.name())
    }
}

internal fun parseSessionResponse(
    statusCode: Int,
    rawBody: String,
    fallbackToken: String?,
): M81SessionResult {
    if (statusCode == 429) return M81SessionResult.RateLimited
    val root = runCatching { Json.parseToJsonElement(rawBody).jsonObject }.getOrNull()
    if (statusCode == 200 && root != null) {
        val authorized = root["authorized"]?.jsonPrimitive?.booleanOrNull == true
        val token = root["session_token"]?.jsonPrimitive?.contentOrNull
            ?: root["access_token"]?.jsonPrimitive?.contentOrNull
            ?: fallbackToken
        if (!authorized || token.isNullOrBlank()) return M81SessionResult.Unavailable
        val clientName = root["client_name"]?.jsonPrimitive?.contentOrNull.orEmpty().trim().take(120)
        val status = root["status"]?.jsonPrimitive?.contentOrNull.orEmpty().trim().take(40)
        return M81SessionResult.Authorized(token, clientName, status)
    }
    val detailObj = root?.get("detail") as? JsonObject
    val code = detailObj?.get("code")?.jsonPrimitive?.contentOrNull.orEmpty()
    return when (statusCode) {
        401 -> if (fallbackToken != null) M81SessionResult.RevokedOrExpired
        else M81SessionResult.Denied(code.ifBlank { "invalid_credentials" })
        403 -> M81SessionResult.Denied(code.ifBlank { "client_inactive" })
        else -> M81SessionResult.Unavailable
    }
}

/**
 * Stores the installation's random device ID and encrypted M81 session token in noBackupFilesDir.
 * Keeping deviceId stable across logout/login ensures this device reuses its own slot in M81's
 * 3-device limit instead of occupying multiple slots.
 */
internal class M81DeviceSessionStore(context: Context, private val vault: SourceVault) {
    private val file = AtomicFile(File(context.noBackupFilesDir, "m81_device_session.enc"))
    private val json = Json { ignoreUnknownKeys = true }

    @Synchronized
    fun readOrCreate(): M81DeviceSession {
        val existing = readOrNull()
        if (existing != null && existing.deviceId.isNotBlank()) return existing
        val created = M81DeviceSession(deviceId = "android-" + UUID.randomUUID().toString())
        write(created)
        return created
    }

    @Synchronized
    fun saveToken(sessionToken: String, clientName: String, status: String): M81DeviceSession {
        val current = readOrCreate()
        val updated = current.copy(
            sessionToken = sessionToken,
            clientName = clientName,
            status = status,
        )
        write(updated)
        return updated
    }

    @Synchronized
    fun clearToken(): M81DeviceSession {
        val current = readOrCreate()
        val cleared = current.copy(sessionToken = null, clientName = "", status = "")
        write(cleared)
        return cleared
    }

    private fun readOrNull(): M81DeviceSession? {
        if (!file.baseFile.exists() && !File(file.baseFile.path + ".bak").exists()) return null
        return runCatching {
            json.decodeFromString<M81DeviceSession>(vault.decrypt(file.readFully()).decodeToString())
        }.getOrNull()
    }

    private fun write(session: M81DeviceSession) {
        val bytes = vault.encrypt(json.encodeToString(session).encodeToByteArray())
        val stream = file.startWrite()
        try {
            stream.write(bytes)
            file.finishWrite(stream)
        } catch (e: Exception) {
            file.failWrite(stream)
            throw e
        }
    }
}
