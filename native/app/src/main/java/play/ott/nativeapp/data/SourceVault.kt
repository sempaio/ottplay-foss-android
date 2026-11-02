package play.ott.nativeapp.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import play.ott.nativeapp.core.SourceConfig
import java.io.File
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Credentials and cached stream URLs stay encrypted, excluded from Android backup. */
class SourceVault(context: Context) {
    private val file = AtomicFile(File(context.noBackupFilesDir, "sources.enc"))
    private val json = Json { ignoreUnknownKeys = true }
    private val key: SecretKey by lazy {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey("ottplay.sources.v1", null) as? SecretKey) ?: KeyGenerator.getInstance(
            KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore"
        ).apply {
            init(KeyGenParameterSpec.Builder("ottplay.sources.v1",
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
        }.generateKey()
    }

    fun encrypt(data: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key)
        return byteArrayOf(1, cipher.iv.size.toByte()) + cipher.iv + cipher.doFinal(data)
    }

    fun decrypt(data: ByteArray): ByteArray {
        require(data.size > 18 && data[0] == 1.toByte()) { "Unknown encrypted data format" }
        val ivLength = data[1].toInt() and 255
        require(ivLength == 12 && data.size > 2 + ivLength + 16) { "Invalid encrypted data" }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, data.copyOfRange(2, 2 + ivLength)))
        return cipher.doFinal(data.copyOfRange(2 + ivLength, data.size))
    }

    @Synchronized fun read(): List<SourceConfig> {
        if (!file.baseFile.exists() && !File(file.baseFile.path + ".bak").exists()) return emptyList()
        return json.decodeFromString(decrypt(file.readFully()).decodeToString())
    }

    @Synchronized fun write(sources: List<SourceConfig>) {
        val bytes = encrypt(json.encodeToString(sources).encodeToByteArray())
        val stream = file.startWrite()
        try { stream.write(bytes); file.finishWrite(stream) }
        catch (e: Exception) { file.failWrite(stream); throw e }
    }
}
