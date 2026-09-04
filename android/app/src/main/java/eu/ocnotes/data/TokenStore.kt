package eu.ocnotes.data

import android.content.Context
import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.security.GeneralSecurityException
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Stockage du token d'application chiffré par une clé Android Keystore.
 *
 * Aucune migration depuis l'ancien format n'est réalisée : une réinstallation
 * est requise lors du passage à ce schéma.
 *
 * Les préférences ne contiennent que le nonce et le texte chiffré AES-GCM. La
 * clé AES reste non exportable dans Android Keystore. Le chiffrement est aussi
 * lié à ce format de donnée par une donnée authentifiée additionnelle stable.
 */
class TokenStore(private val context: Context) {

    @Volatile
    private var cached: SharedPreferences? = null

    private fun prefs(): SharedPreferences =
        cached ?: synchronized(this) {
            cached ?: context.getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE)
                .also { cached = it }
        }

    /** Le token enregistré, ou `null` si aucun token n'est disponible. */
    suspend fun appToken(): String? = withContext(Dispatchers.IO) {
        val encoded = prefs().getString(KEY_APP_TOKEN, null) ?: return@withContext null

        try {
            val token = decrypt(encoded)
            if (token == null) {
                discardUnreadableToken()
                null
            } else {
                token.takeIf { it.isNotBlank() }
            }
        } catch (_: GeneralSecurityException) {
            // Une clé invalidée ou une valeur altérée ne doit pas empêcher
            // l'application de démarrer. L'utilisateur se reconnectera.
            discardUnreadableToken()
            null
        } catch (_: IllegalArgumentException) {
            // Base64 ou format de donnée malformé : même comportement sûr.
            discardUnreadableToken()
            null
        }
    }

    /** Chiffre et enregistre un token après une connexion validée. */
    suspend fun saveAppToken(token: String) = withContext(Dispatchers.IO) {
        val encrypted = encrypt(token)
        check(prefs().edit().putString(KEY_APP_TOKEN, encrypted).commit()) {
            "écriture du token chiffré impossible" // i18n-ok: exception technique, non affichée
        }
    }

    /** Efface le texte chiffré puis la clé qui permettrait de le déchiffrer. */
    suspend fun clear() = withContext(Dispatchers.IO) {
        check(prefs().edit().clear().commit()) {
            "effacement du token chiffré impossible" // i18n-ok: exception technique, non affichée
        }
        keyStore().deleteEntry(KEY_ALIAS)
    }

    private fun encrypt(token: String): String {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, encryptionKey())
        cipher.updateAAD(AAD)
        val encrypted = cipher.doFinal(token.toByteArray(Charsets.UTF_8))
        return listOf(
            Base64.encodeToString(cipher.iv, Base64.NO_WRAP),
            Base64.encodeToString(encrypted, Base64.NO_WRAP),
        ).joinToString(".")
    }

    private fun decrypt(encoded: String): String? {
        val (ivEncoded, ciphertextEncoded) = encoded.split('.', limit = 2)
            .takeIf { it.size == 2 } ?: return null
        val key = decryptionKey() ?: return null

        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(
            Cipher.DECRYPT_MODE,
            key,
            GCMParameterSpec(GCM_TAG_LENGTH_BITS, Base64.decode(ivEncoded, Base64.NO_WRAP)),
        )
        cipher.updateAAD(AAD)
        return cipher.doFinal(Base64.decode(ciphertextEncoded, Base64.NO_WRAP))
            .toString(Charsets.UTF_8)
    }

    @Synchronized
    private fun encryptionKey(): SecretKey {
        decryptionKey()?.let { return it }

        val generator = KeyGenerator.getInstance(
            KeyProperties.KEY_ALGORITHM_AES,
            KEYSTORE_PROVIDER,
        )
        generator.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(AES_KEY_SIZE_BITS)
                .build(),
        )
        return generator.generateKey()
    }

    private fun decryptionKey(): SecretKey? =
        keyStore().getKey(KEY_ALIAS, null) as? SecretKey

    private fun keyStore(): KeyStore = KeyStore.getInstance(KEYSTORE_PROVIDER).apply {
        load(null)
    }

    private fun discardUnreadableToken() {
        prefs().edit().remove(KEY_APP_TOKEN).commit()
        runCatching { keyStore().deleteEntry(KEY_ALIAS) }
    }

    private companion object {
        const val FILE_NAME = "ocnotes_secrets_v2"
        const val KEY_APP_TOKEN = "app_token"
        const val KEY_ALIAS = "ocnotes_app_token_v2"
        const val KEYSTORE_PROVIDER = "AndroidKeyStore"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val AES_KEY_SIZE_BITS = 256
        const val GCM_TAG_LENGTH_BITS = 128
        val AAD = "eu.ocnotes.app-token.v2".toByteArray(Charsets.UTF_8)
    }
}
