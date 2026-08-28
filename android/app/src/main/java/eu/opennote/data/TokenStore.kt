package eu.opennote.data

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Conservation de l'App Token.
 *
 * C'est **le seul endroit** où le secret est écrit sur l'appareil. Le cœur Go
 * ne le persiste jamais : il le reçoit à chaque démarrage via `Connect` et le
 * garde en mémoire dans le client HTTP. Un test Go vérifie que le
 * `config.json` écrit par la couche métier ne contient aucun secret.
 *
 * `EncryptedSharedPreferences` s'appuie sur une clé maîtresse du Keystore,
 * adossée au matériel quand l'appareil en dispose. Les clés comme les valeurs
 * sont chiffrées.
 *
 * Toutes les méthodes sont `suspend` : la première ouverture dérive une clé et
 * peut solliciter le Keystore, ce qui n'a rien à faire sur le thread
 * principal.
 */
class TokenStore(private val context: Context) {

    @Volatile
    private var cached: SharedPreferences? = null

    private suspend fun prefs(): SharedPreferences = withContext(Dispatchers.IO) {
        cached ?: synchronized(this@TokenStore) {
            cached ?: create().also { cached = it }
        }
    }

    private fun create(): SharedPreferences {
        val masterKey = MasterKey.Builder(context, MasterKey.DEFAULT_MASTER_KEY_ALIAS)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()

        return EncryptedSharedPreferences.create(
            context,
            FILE_NAME,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }

    /** Le token enregistré, ou `null` si aucune session n'a été ouverte. */
    suspend fun appToken(): String? =
        prefs().getString(KEY_APP_TOKEN, null)?.takeIf { it.isNotBlank() }

    suspend fun saveAppToken(token: String) {
        withContext(Dispatchers.IO) {
            prefs().edit().putString(KEY_APP_TOKEN, token).commit()
        }
    }

    /** Efface le secret. Appelé par la déconnexion, avant `App.disconnect()`. */
    suspend fun clear() {
        withContext(Dispatchers.IO) {
            prefs().edit().clear().commit()
        }
    }

    private companion object {
        const val FILE_NAME = "opennote_secrets"
        const val KEY_APP_TOKEN = "app_token"
    }
}
