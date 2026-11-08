package com.github.pandeiro.tempore.slsk.core.secure

import android.content.Context
import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import kotlinx.serialization.json.Json

/**
 * Fields sealed so callers can distinguish "device Keystore is broken" (setup card) from
 * "stored blob corrupted" (offer reconfigure). Each carries a plain-text description.
 *
 * [StoreError] is deliberately surfaced, never swallowed: the credential store is the one
 * place where silent failure would mean silently storing/re-sending an API key in
 * plaintext.
 */
sealed class StoreError(message: String, cause: Throwable? = null) : Exception(message, cause) {
    /** Keystore (AndroidKeyStore) could not provide a usable AES key. */
    class KeystoreInit(message: String, cause: Throwable? = null) : StoreError(message, cause)

    /** The persisted blob failed to decrypt (device changed, key invalidated, tamper). */
    class Decrypt(message: String, cause: Throwable? = null) : StoreError(message, cause)

    /** Encryption failed while SAVEing. */
    class Encrypt(message: String, cause: Throwable? = null) : StoreError(message, cause)
}

/**
 * Android Keystore AES-GCM credential store — no androidx security-crypto, no WorkManager,
 * no Room. One JSON blob (the whole [SlskdConfig]) lives in a plain `SharedPreferences`
 * file (`slsk_secure`) under a single key; confidentiality comes from the Keystore key.
 *
 * Errors are thrown as sealed [StoreError]s: the session layer surfaces them so the UI can
 * show a setup card. Note for tests: Android Keystore only works on a device — unit tests
 * use an in-memory [CredentialStore] fake instead.
 */
class KeystoreCredentialStore private constructor(
    private val prefs: SharedPreferences,
    private val prefsName: String,
) : CredentialStore {

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    override fun load(): SlskdConfig? {
        val raw: String? = prefs.getString(BLOB_KEY, null)
        if (raw.isNullOrEmpty()) return null
        val parts = raw.split(':')
        if (parts.size != 2) {
            prefs.edit().remove(BLOB_KEY).apply()
            throw StoreError.Decrypt("stored slsk credential blob is malformed", null)
        }
        val cipher = cipherFor(Cipher.DECRYPT_MODE, parts[0])
        val plaintext = try {
            cipher.doFinal(Base64.decode(parts[1], Base64.NO_WRAP))
        } catch (e: Exception) {
            throw StoreError.Decrypt("stored slsk credentials could not be decrypted: ${e.message}", e)
        }
        val text = plaintext.toString(Charsets.UTF_8)
        try {
            return json.decodeFromString(SlskdConfig.serializer(), text)
        } catch (e: Exception) {
            throw StoreError.Decrypt("stored slsk credentials are unreadable: ${e.message}", e)
        }
    }

    override fun save(config: SlskdConfig) {
        val cipher = cipherFor(Cipher.ENCRYPT_MODE, null)
        val ciphertext = cipher.doFinal(
            json.encodeToString(SlskdConfig.serializer(), config).toByteArray(Charsets.UTF_8)
        )
        val stored = Base64.encodeToString(cipher.iv, Base64.NO_WRAP) + ":" +
            Base64.encodeToString(ciphertext, Base64.NO_WRAP)
        prefs.edit().putString(BLOB_KEY, stored).apply()
    }

    override fun clear() {
        prefs.edit().remove(BLOB_KEY).apply()
    }

    /** Builds/loads the master key and returns a cipher wired to it (fresh IV for encrypt). */
    private fun cipherFor(mode: Int, ivBase64: String?): Cipher {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        if (mode == Cipher.DECRYPT_MODE) {
            val iv = Base64.decode(requireNotNull(ivBase64), Base64.NO_WRAP)
            cipher.init(mode, masterKey(), GCMParameterSpec(128, iv))
        } else {
            cipher.init(Cipher.ENCRYPT_MODE, masterKey())
        }
        return cipher
    }

    companion object {
        private const val KEYSTORE_PROVIDER = "AndroidKeyStore"
        private const val MASTER_ALIAS = "slsk_master"
        private const val BLOB_KEY = "slsk_config_blob"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"

        /**
         * Preferred entry point. Throws [StoreError.KeystoreInit] when the Keystore itself
         * is unusable (device policies, corrupted keystore, OEM weirdness) so the caller
         * can surface the setup card instead of proceeding unencrypted.
         */
        fun open(context: Context, prefsName: String = DEFAULT_PREFS_FILE): KeystoreCredentialStore {
            val prefs = context.getSharedPreferences(prefsName, Context.MODE_PRIVATE)
            try {
                // Fail fast if Keystore cannot produce a key — before anything is stored.
                masterKey()
            } catch (e: Exception) {
                throw StoreError.KeystoreInit("Android Keystore unavailable: ${e.message}", e)
            }
            return KeystoreCredentialStore(prefs, prefsName)
        }

        /** Loads or generates the AES-GCM master key inside the Android Keystore. */
        internal fun masterKey(): SecretKey {
            val keyStore = KeyStore.getInstance(KEYSTORE_PROVIDER).apply { load(null) }
            (keyStore.getEntry(MASTER_ALIAS, null) as? java.security.KeyStore.SecretKeyEntry)
                ?.let { return it.secretKey }
            val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE_PROVIDER)
            generator.init(
                KeyGenParameterSpec.Builder(MASTER_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256)
                    .build()
            )
            return generator.generateKey()
        }

        const val DEFAULT_PREFS_FILE = "slsk_secure"
    }
}
