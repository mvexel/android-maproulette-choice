package lol.osm.streettally.auth

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.maproulette.sdk.CredentialStore

/** The SDK's sign-in state, AES/GCM-encrypted with an Android Keystore key, one slot per backend
 * origin and OAuth client ([binding]). Tokens never reach plaintext preferences; clearing deletes
 * the key as well. Ported from the SDK's android-example. */
class KeystoreCredentialStore(context: Context, private val binding: String) : CredentialStore {
    private val bindingHash = MessageDigest.getInstance("SHA-256").digest(binding.toByteArray())
        .joinToString("") { "%02x".format(it) }
    private val alias = "street-tally-auth-$bindingHash"
    private val preferences = context.getSharedPreferences("auth-$bindingHash", Context.MODE_PRIVATE)
    private val keyStore by lazy { KeyStore.getInstance("AndroidKeyStore").apply { load(null) } }

    override suspend fun read(): String? = withContext(Dispatchers.IO) {
        val encoded = preferences.getString(KEY, null) ?: return@withContext null
        val bytes = Base64.decode(encoded, Base64.NO_WRAP)
        require(bytes.size > IV_BYTES)
        val key = keyStore.getKey(alias, null) as? SecretKey ?: error("Stored credential unavailable")
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, bytes.copyOfRange(0, IV_BYTES)))
        cipher.updateAAD(binding.toByteArray())
        cipher.doFinal(bytes.copyOfRange(IV_BYTES, bytes.size)).toString(Charsets.UTF_8)
    }

    override suspend fun write(data: String): Unit = withContext(Dispatchers.IO) {
        val key = keyStore.getKey(alias, null) as? SecretKey ?: KeyGenerator.getInstance(
            KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore",
        ).apply {
            init(
                KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setRandomizedEncryptionRequired(true)
                    .build(),
            )
        }.generateKey()
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key)
        cipher.updateAAD(binding.toByteArray())
        val encoded = Base64.encodeToString(cipher.iv + cipher.doFinal(data.toByteArray()), Base64.NO_WRAP)
        check(preferences.edit().putString(KEY, encoded).commit()) { "Credential storage failed" }
    }

    override suspend fun clear(): Unit = withContext(Dispatchers.IO) {
        // Removing the key first makes stale ciphertext unusable even if a preferences write fails.
        val keyRemoved = runCatching { keyStore.deleteEntry(alias) }.isSuccess
        val preferencesRemoved = runCatching { preferences.edit().clear().commit() }.getOrDefault(false)
        check(keyRemoved || preferencesRemoved) { "Credential storage failed" }
    }

    private companion object {
        const val KEY = "encrypted"
        const val IV_BYTES = 12
        const val TRANSFORMATION = "AES/GCM/NoPadding"
    }
}
