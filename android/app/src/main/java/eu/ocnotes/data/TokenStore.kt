package eu.ocnotes.data

import android.annotation.SuppressLint
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
 * Chaque profil possède ses préférences, sa clé et son AAD. Le secret global
 * de l'ancien schéma est repris au premier accès — déchiffré avec l'ancienne
 * clé, rechiffré sous celle du profil — puis effacé : une mise à jour ne doit
 * ni déconnecter l'utilisateur, ni lui fermer ses notes hors connexion.
 *
 * Les préférences ne contiennent que le nonce et le texte chiffré AES-GCM. La
 * clé AES reste non exportable dans Android Keystore. Le chiffrement est aussi
 * lié à ce format de donnée par une donnée authentifiée additionnelle stable.
 */
class TokenStore(
    private val context: Context,
    private val accountId: String,
) {

    @Volatile
    private var cached: SharedPreferences? = null

    // N'est appelé que depuis les fonctions suspendues, sur Dispatchers.IO : la
    // reprise de l'ancien secret passe par le Keystore, hors du thread principal.
    private fun prefs(): SharedPreferences =
        cached ?: synchronized(this) {
            cached ?: context.getSharedPreferences("${FILE_NAME}_$accountId", Context.MODE_PRIVATE)
                .also {
                    reprendreAncienSecret(it)
                    cached = it
                }
        }

    /**
     * Reprend le secret du schéma global, s'il en reste un.
     *
     * Le nouveau secret est écrit avant que l'ancien ne soit effacé : tué entre
     * les deux, l'appareil refait la reprise au lancement suivant. Un ancien
     * secret illisible est effacé sans reprise — l'utilisateur se reconnecte,
     * comme pour tout token perdu. Un Keystore indisponible laisse tout en
     * place, pour un nouvel essai au lancement suivant.
     */
    @SuppressLint("ApplySharedPref")
    private fun reprendreAncienSecret(cible: SharedPreferences) {
        val ancien = context.getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE)
        if (ancien.all.isEmpty()) return

        if (cible.all.isEmpty()) {
            try {
                val repris = listOf(KEY_APP_TOKEN, KEY_OIDC_STATE).mapNotNull { cle ->
                    val chiffre = ancien.getString(cle, null) ?: return@mapNotNull null
                    val clair = runCatching { decrypt(chiffre, KEY_ALIAS, LEGACY_AAD) }.getOrNull()
                    clair?.let { cle to encrypt(it) }
                }
                if (repris.isNotEmpty()) {
                    val edition = cible.edit()
                    repris.forEach { (cle, valeur) -> edition.putString(cle, valeur) }
                    if (!edition.commit()) return
                }
            } catch (_: Exception) {
                // Le Keystore lève aussi des ProviderException, hors de la
                // hiérarchie GeneralSecurityException : aucune ne doit
                // empêcher l'application de démarrer.
                return
            }
        }

        // Vider avant de supprimer : le contexte garde en mémoire l'instance
        // déjà ouverte, que la seule suppression du fichier laisserait pleine.
        ancien.edit().clear().commit()
        context.deleteSharedPreferences(FILE_NAME)
        runCatching { keyStore().deleteEntry(KEY_ALIAS) }
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
        check(prefs().edit().remove(KEY_OIDC_STATE).putString(KEY_APP_TOKEN, encrypted).commit()) {
            "écriture du token chiffré impossible" // i18n-ok: exception technique, non affichée
        }
    }

    /** État AppAuth complet, chiffré : access token, refresh token et issuer. */
    suspend fun oidcState(): String? = withContext(Dispatchers.IO) {
        val encoded = prefs().getString(KEY_OIDC_STATE, null) ?: return@withContext null
        try {
            decrypt(encoded)?.takeIf { it.isNotBlank() }
        } catch (_: GeneralSecurityException) {
            discardUnreadableToken()
            null
        } catch (_: IllegalArgumentException) {
            discardUnreadableToken()
            null
        }
    }

    suspend fun saveOidcState(state: String) = withContext(Dispatchers.IO) {
        val encrypted = encrypt(state)
        check(prefs().edit().remove(KEY_APP_TOKEN).putString(KEY_OIDC_STATE, encrypted).commit()) {
            "écriture de la session OIDC chiffrée impossible" // i18n-ok
        }
    }

    /** Efface le texte chiffré puis la clé qui permettrait de le déchiffrer. */
    suspend fun clear() = withContext(Dispatchers.IO) {
        check(prefs().edit().clear().commit()) {
            "effacement du token chiffré impossible" // i18n-ok: exception technique, non affichée
        }
        keyStore().deleteEntry(keyAlias)
    }

    private fun encrypt(token: String): String {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, encryptionKey())
        cipher.updateAAD(aad)
        val encrypted = cipher.doFinal(token.toByteArray(Charsets.UTF_8))
        return listOf(
            Base64.encodeToString(cipher.iv, Base64.NO_WRAP),
            Base64.encodeToString(encrypted, Base64.NO_WRAP),
        ).joinToString(".")
    }

    private fun decrypt(
        encoded: String,
        alias: String = keyAlias,
        donneeAuthentifiee: ByteArray = aad,
    ): String? {
        val (ivEncoded, ciphertextEncoded) = encoded.split('.', limit = 2)
            .takeIf { it.size == 2 } ?: return null
        val key = decryptionKey(alias) ?: return null

        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(
            Cipher.DECRYPT_MODE,
            key,
            GCMParameterSpec(GCM_TAG_LENGTH_BITS, Base64.decode(ivEncoded, Base64.NO_WRAP)),
        )
        cipher.updateAAD(donneeAuthentifiee)
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
                keyAlias,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(AES_KEY_SIZE_BITS)
                .build(),
        )
        return generator.generateKey()
    }

    private fun decryptionKey(alias: String = keyAlias): SecretKey? =
        keyStore().getKey(alias, null) as? SecretKey

    private fun keyStore(): KeyStore = KeyStore.getInstance(KEYSTORE_PROVIDER).apply {
        load(null)
    }

    // `commit()` synchrone : le secret illisible doit avoir disparu avant que
    // l'appelant ne conclue qu'il n'y a pas de session.
    @SuppressLint("ApplySharedPref")
    private fun discardUnreadableToken() {
        prefs().edit().remove(KEY_APP_TOKEN).remove(KEY_OIDC_STATE).commit()
        runCatching { keyStore().deleteEntry(keyAlias) }
    }

    private val keyAlias: String get() = "${KEY_ALIAS}_$accountId"
    private val aad: ByteArray get() =
        "eu.ocnotes.account-secret.v3:$accountId".toByteArray(Charsets.UTF_8)

    private companion object {
        // FILE_NAME et KEY_ALIAS nus désignent aussi le schéma global d'avant
        // les profils, que reprendreAncienSecret reprend puis efface.
        const val FILE_NAME = "ocnotes_secrets_v2"
        const val KEY_APP_TOKEN = "app_token"
        const val KEY_OIDC_STATE = "oidc_state"
        const val KEY_ALIAS = "ocnotes_app_token_v2"
        const val KEYSTORE_PROVIDER = "AndroidKeyStore"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val AES_KEY_SIZE_BITS = 256
        const val GCM_TAG_LENGTH_BITS = 128
        val LEGACY_AAD = "eu.ocnotes.app-token.v2".toByteArray(Charsets.UTF_8)
    }
}
