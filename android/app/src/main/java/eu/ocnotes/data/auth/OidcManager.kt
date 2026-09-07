package eu.ocnotes.data.auth

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import net.openid.appauth.AuthState
import net.openid.appauth.AuthorizationException
import net.openid.appauth.AuthorizationRequest
import net.openid.appauth.AuthorizationResponse
import net.openid.appauth.AuthorizationService
import net.openid.appauth.AuthorizationServiceConfiguration
import net.openid.appauth.ResponseTypeValues
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

data class OidcConnexion(
    val accountId: String,
    val accessToken: String,
    val serializedState: String,
)

data class JetonOidc(
    val accessToken: String,
    val serializedState: String,
)

/** Parcours OIDC natif : WebFinger, navigateur, PKCE et renouvellement. */
class OidcManager(private val context: Context) {

    suspend fun authorizationIntent(serverUrl: String): Intent {
        val server = normalizeServerUrl(serverUrl)
        val discovery = discover(server)
        val configuration = fetchConfiguration(discovery.issuer)
        val request = AuthorizationRequest.Builder(
            configuration,
            CLIENT_ID,
            ResponseTypeValues.CODE,
            REDIRECT_URI,
        )
            .setScope(discovery.scopes.joinToString(" "))
            .build()
        return AuthorizationService(context).getAuthorizationRequestIntent(request)
    }

    suspend fun finishAuthorization(data: Intent?): OidcConnexion {
        requireNotNull(data) { "Connexion annulée" } // i18n-ok : erreur technique, remplacée par le ViewModel
        val response = AuthorizationResponse.fromIntent(data)
        val exception = AuthorizationException.fromIntent(data)
        if (response == null) throw exception ?: IllegalStateException("Réponse OIDC absente") // i18n-ok

        val state = AuthState(response, exception)
        val service = AuthorizationService(context)
        try {
            val tokenResponse = suspendCancellableCoroutine { continuation ->
                service.performTokenRequest(response.createTokenExchangeRequest()) { token, error ->
                    if (token != null) continuation.resume(token)
                    else continuation.resumeWithException(error ?: IllegalStateException("Jeton OIDC absent")) // i18n-ok
                }
            }
            state.update(tokenResponse, null)
            val accessToken = requireNotNull(state.accessToken) { "Access token OIDC absent" } // i18n-ok
            val accountId = subject(state.idToken)
            return OidcConnexion(accountId, accessToken, state.jsonSerializeString())
        } finally {
            service.dispose()
        }
    }

    suspend fun freshToken(serializedState: String): JetonOidc {
        val state = AuthState.jsonDeserialize(serializedState)
        val service = AuthorizationService(context)
        try {
            return suspendCancellableCoroutine { continuation ->
                state.performActionWithFreshTokens(service) { accessToken, _, error ->
                    if (accessToken != null) {
                        continuation.resume(JetonOidc(accessToken, state.jsonSerializeString()))
                    } else {
                        continuation.resumeWithException(error ?: IllegalStateException("Jeton OIDC absent")) // i18n-ok
                    }
                }
            }
        } finally {
            service.dispose()
        }
    }

    fun lastAccessToken(serializedState: String): String? =
        AuthState.jsonDeserialize(serializedState).accessToken

    private suspend fun fetchConfiguration(issuer: Uri): AuthorizationServiceConfiguration =
        suspendCancellableCoroutine { continuation ->
            AuthorizationServiceConfiguration.fetchFromIssuer(issuer) { configuration, error ->
                if (configuration != null) continuation.resume(configuration)
                else continuation.resumeWithException(error ?: IllegalStateException("Configuration OIDC absente")) // i18n-ok
            }
        }

    private suspend fun discover(server: String): Discovery = withContext(Dispatchers.IO) {
        val endpoint = Uri.parse(server).buildUpon()
            .appendEncodedPath(".well-known/webfinger")
            .appendQueryParameter("resource", server)
            .appendQueryParameter("rel", ISSUER_REL)
            .appendQueryParameter("platform", "android")
            .build()
        val connection = URL(endpoint.toString()).openConnection() as HttpURLConnection
        connection.connectTimeout = TIMEOUT_MS
        connection.readTimeout = TIMEOUT_MS
        connection.instanceFollowRedirects = false
        connection.setRequestProperty("Accept", "application/json")
        try {
            if (connection.responseCode !in 200..299) {
                error("WebFinger a répondu HTTP ${connection.responseCode}") // i18n-ok
            }
            val json = connection.inputStream.bufferedReader().use { JSONObject(it.readText()) }
            val links = json.getJSONArray("links")
            var issuer: Uri? = null
            for (index in 0 until links.length()) {
                val link = links.getJSONObject(index)
                if (link.optString("rel") == ISSUER_REL) issuer = Uri.parse(link.getString("href"))
            }
            val safeIssuer = requireNotNull(issuer) { "Issuer OIDC absent de WebFinger" } // i18n-ok
            require(safeIssuer.scheme == "https") { "Issuer OIDC non HTTPS" } // i18n-ok
            val properties = json.optJSONObject("properties")
            val scopesJson = properties?.optJSONArray(SCOPES_PROPERTY)
            val scopes = if (scopesJson == null) DEFAULT_SCOPES else buildList {
                for (index in 0 until scopesJson.length()) add(scopesJson.getString(index))
            }
            Discovery(safeIssuer, scopes.ifEmpty { DEFAULT_SCOPES })
        } finally {
            connection.disconnect()
        }
    }

    private fun subject(idToken: String?): String {
        require(!idToken.isNullOrBlank()) { "ID token OIDC absent" } // i18n-ok
        val parts = idToken.split('.')
        require(parts.size >= 2) { "ID token OIDC invalide" } // i18n-ok
        val payload = String(Base64.decode(parts[1], Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING))
        return JSONObject(payload).getString("sub")
    }

    private fun normalizeServerUrl(raw: String): String {
        val normalized = raw.trim().let { if ("://" in it) it else "https://$it" }.trimEnd('/')
        val uri = Uri.parse(normalized)
        require(uri.scheme == "https" && !uri.host.isNullOrBlank()) { "Adresse de serveur HTTPS invalide" } // i18n-ok
        return normalized
    }

    private data class Discovery(val issuer: Uri, val scopes: List<String>)

    private companion object {
        const val CLIENT_ID = "OCnotesAndroid"
        // OpenCloud's native clients use a custom URI with an authority
        // (for example, oc://android.opencloud.eu). Keep the same shape: the
        // built-in IDP validates these native redirect URIs more reliably than
        // the single-slash variant commonly used by AppAuth samples.
        val REDIRECT_URI: Uri = Uri.parse("eu.ocnotes://oauth2redirect")
        const val ISSUER_REL = "http://openid.net/specs/connect/1.0/issuer"
        const val SCOPES_PROPERTY = "http://opencloud.eu/ns/oidc/scopes"
        val DEFAULT_SCOPES = listOf("openid", "profile", "email", "offline_access")
        const val TIMEOUT_MS = 15_000
    }
}
