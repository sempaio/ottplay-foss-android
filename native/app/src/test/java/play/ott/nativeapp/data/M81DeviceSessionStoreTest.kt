package play.ott.nativeapp.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers
import play.ott.nativeapp.playback.PlaybackTestApplication
import java.io.File
import javax.crypto.KeyGenerator

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = PlaybackTestApplication::class, shadows = [PosixAtomicFileShadow::class])
class M81DeviceSessionStoreTest {
    private lateinit var context: Context
    private lateinit var store: M81DeviceSessionStore

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        File(context.noBackupFilesDir, "m81_device_session.enc").delete()
        File(context.noBackupFilesDir, "m81_device_session.enc.bak").delete()
        val vault = SourceVault(context)
        val key = KeyGenerator.getInstance("AES").apply { init(128) }.generateKey()
        ReflectionHelpers.setField(vault, "key\$delegate", lazy { key })
        store = M81DeviceSessionStore(context, vault)
    }

    @Test
    fun encryptedStorePreservesStableDeviceIdAcrossTokenSaveAndClear() {
        val initial = store.readOrCreate()
        assertTrue(initial.deviceId.startsWith("android-"))
        assertNull(initial.sessionToken)

        val saved = store.saveToken("tok_secret_987654321", "Maria Silva", "ativo")
        assertEquals(initial.deviceId, saved.deviceId)
        assertEquals("tok_secret_987654321", saved.sessionToken)
        assertEquals("Maria Silva", saved.clientName)
        assertEquals("ativo", saved.status)

        val rawDisk = File(context.noBackupFilesDir, "m81_device_session.enc").readBytes().decodeToString()
        assertFalse(rawDisk.contains("tok_secret_987654321"))
        assertFalse(rawDisk.contains("Maria Silva"))

        val cleared = store.clearToken()
        assertEquals(initial.deviceId, cleared.deviceId)
        assertNull(cleared.sessionToken)
        assertEquals("", cleared.clientName)
        assertEquals("", cleared.status)
        assertEquals(initial.deviceId, store.readOrCreate().deviceId)
    }

    @Test
    fun corruptedSessionFileRecoversWithoutCrashing() {
        File(context.noBackupFilesDir, "m81_device_session.enc").writeBytes(byteArrayOf(0, 1, 2, 3))
        val recovered = store.readOrCreate()
        assertTrue(recovered.deviceId.startsWith("android-"))
        assertNull(recovered.sessionToken)
    }

    @Test
    fun syncRecoversMissingSessionTokenAfterPriorFailOpenLogin() = runTest {
        val initial = store.readOrCreate()
        var loginCalls = 0
        var validateCalls = 0
        val api = object : M81DeviceSessionApi {
            override suspend fun login(username: String, password: String, deviceId: String): M81SessionResult {
                loginCalls++
                assertEquals("cliente1", username)
                assertEquals("senha1", password)
                assertEquals(initial.deviceId, deviceId)
                return M81SessionResult.Authorized("tok_recovered_123456", "João", "ativo")
            }
            override suspend fun validate(sessionToken: String, deviceId: String): M81SessionResult {
                validateCalls++
                return M81SessionResult.Unavailable
            }
            override suspend fun logout(sessionToken: String, deviceId: String) = Unit
        }

        val outcome = syncM81DeviceSession(api, store, "cliente1", "senha1")
        assertEquals(M81SessionSyncOutcome.Active("João"), outcome)
        assertEquals(1, loginCalls)
        assertEquals(0, validateCalls)
        assertEquals("tok_recovered_123456", store.readOrCreate().sessionToken)
        assertEquals("João", store.readOrCreate().clientName)
    }

    @Test
    fun syncValidatesExistingTokenAndHandlesRevokedDeniedAndDeferred() = runTest {
        store.saveToken("tok_existing_123456", "Ana", "ativo")

        val revokedApi = fakeApi(validateResult = M81SessionResult.RevokedOrExpired)
        assertEquals(
            M81SessionSyncOutcome.Revoked,
            syncM81DeviceSession(revokedApi, store, "u", "p"),
        )

        val deniedApi = fakeApi(validateResult = M81SessionResult.Denied("client_blocked"))
        assertEquals(
            M81SessionSyncOutcome.Denied("client_blocked"),
            syncM81DeviceSession(deniedApi, store, "u", "p"),
        )

        val offlineApi = fakeApi(validateResult = M81SessionResult.Unavailable)
        assertEquals(
            M81SessionSyncOutcome.Deferred,
            syncM81DeviceSession(offlineApi, store, "u", "p"),
        )
        assertEquals("tok_existing_123456", store.readOrCreate().sessionToken)
    }

    @Test
    fun syncDefersWhenNoTokenAndCredentialsAreBlank() = runTest {
        store.readOrCreate()
        var called = false
        val api = object : M81DeviceSessionApi {
            override suspend fun login(username: String, password: String, deviceId: String): M81SessionResult {
                called = true
                return M81SessionResult.Unavailable
            }
            override suspend fun validate(sessionToken: String, deviceId: String) = M81SessionResult.Unavailable
            override suspend fun logout(sessionToken: String, deviceId: String) = Unit
        }
        assertEquals(M81SessionSyncOutcome.Deferred, syncM81DeviceSession(api, store, "", ""))
        assertFalse(called)
    }

    @Test
    fun switchingAccountLogsOutPreviousTokenAndPreventsStaleTokenLeakOnFailOpen() = runTest {
        val original = store.saveToken("tok_user_a_123456", "Usuario A", "ativo")
        val loggedOutTokens = mutableListOf<String>()
        val api = object : M81DeviceSessionApi {
            override suspend fun login(username: String, password: String, deviceId: String): M81SessionResult {
                assertEquals("user_b", username)
                assertEquals(original.deviceId, deviceId)
                return M81SessionResult.Unavailable
            }
            override suspend fun validate(sessionToken: String, deviceId: String) = M81SessionResult.Unavailable
            override suspend fun logout(sessionToken: String, deviceId: String) {
                loggedOutTokens += sessionToken
            }
        }

        val result = loginM81DeviceSession(
            api = api,
            store = store,
            previousUsername = "user_a",
            username = "user_b",
            password = "secret",
        )
        assertEquals(M81SessionResult.Unavailable, result)
        assertEquals(listOf("tok_user_a_123456"), loggedOutTokens)
        val after = store.readOrCreate()
        assertEquals(original.deviceId, after.deviceId)
        assertNull(after.sessionToken)
        assertEquals("", after.clientName)
    }

    private fun fakeApi(
        loginResult: M81SessionResult = M81SessionResult.Unavailable,
        validateResult: M81SessionResult = M81SessionResult.Unavailable,
    ) = object : M81DeviceSessionApi {
        override suspend fun login(username: String, password: String, deviceId: String) = loginResult
        override suspend fun validate(sessionToken: String, deviceId: String) = validateResult
        override suspend fun logout(sessionToken: String, deviceId: String) = Unit
    }
}
