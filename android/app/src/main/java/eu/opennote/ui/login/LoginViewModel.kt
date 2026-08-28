package eu.opennote.ui.login

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import eu.opennote.AppContainer
import eu.opennote.data.ErrorCategory
import eu.opennote.data.OpenNoteException
import eu.opennote.data.TokenStore
import eu.opennote.data.OpenNoteRepository
import eu.opennote.data.userMessage
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Où l'écran de connexion doit mener une fois `Connect` réussi.
 *
 * La façade répond elle-même à la question : si `hasWorkspace` est vrai après
 * `Connect`, la bibliothèque a été remontée dans la foulée et l'utilisateur
 * retrouve ses notes sans repasser par la sélection d'espace.
 */
enum class SuiteConnexion { CHOIX_ESPACE, NAVIGATEUR }

data class LoginUiState(
    val serverUrl: String = "",
    val username: String = "",
    val appToken: String = "",
    val enCours: Boolean = false,
    /** Message d'erreur affiché sous le formulaire, s'il y en a un. */
    val erreur: String? = null,
    /**
     * Distingue « identifiants refusés » d'un « serveur injoignable ».
     *
     * Un refus vise les champs — c'est le token ou le nom d'utilisateur qui
     * est faux. Un problème réseau ne vise personne : le bouton devient
     * « Réessayer » et rien n'est à corriger.
     */
    val erreurEstAuth: Boolean = false,
    val suite: SuiteConnexion? = null,
) {
    val peutValider: Boolean
        get() = !enCours &&
            serverUrl.isNotBlank() &&
            username.isNotBlank() &&
            appToken.isNotBlank()
}

class LoginViewModel(
    private val repository: OpenNoteRepository,
    private val tokenStore: TokenStore,
) : ViewModel() {

    private val _uiState = MutableStateFlow(LoginUiState())
    val uiState: StateFlow<LoginUiState> = _uiState.asStateFlow()

    init {
        // Repré-remplissage : après un token expiré ou un démarrage hors
        // connexion, l'utilisateur ne doit pas ressaisir l'URL et son login.
        // Le token aussi est repris — il est peut-être encore bon, l'échec
        // pouvait venir du réseau.
        viewModelScope.launch {
            val etat = runCatching { repository.state() }.getOrNull()
            val token = runCatching { tokenStore.appToken() }.getOrNull()
            _uiState.update {
                it.copy(
                    serverUrl = it.serverUrl.ifBlank { etat?.serverUrl.orEmpty() },
                    username = it.username.ifBlank { etat?.username.orEmpty() },
                    appToken = it.appToken.ifBlank { token.orEmpty() },
                )
            }
        }
        repository.acknowledgeSessionExpired()
    }

    fun onServerUrlChange(valeur: String) = _uiState.update { it.copy(serverUrl = valeur, erreur = null) }

    fun onUsernameChange(valeur: String) = _uiState.update { it.copy(username = valeur, erreur = null) }

    fun onAppTokenChange(valeur: String) = _uiState.update { it.copy(appToken = valeur, erreur = null) }

    /**
     * Ouvre la session.
     *
     * `Connect` valide vraiment les identifiants auprès du serveur : à ce
     * stade, un `AUTH` veut dire refusé, tout le reste veut dire injoignable.
     * On ne réessaie jamais automatiquement sur un `AUTH` — le token est
     * mauvais, insister ne le rendra pas bon.
     */
    fun connecter() {
        val etat = _uiState.value
        if (!etat.peutValider) return

        _uiState.update { it.copy(enCours = true, erreur = null, erreurEstAuth = false) }

        viewModelScope.launch {
            try {
                repository.connect(etat.serverUrl, etat.username, etat.appToken)

                // La façade a pu remonter la bibliothèque toute seule si un
                // espace était déjà enregistré.
                val apres = repository.state()
                _uiState.update {
                    it.copy(
                        enCours = false,
                        suite = if (apres.hasWorkspace) {
                            SuiteConnexion.NAVIGATEUR
                        } else {
                            SuiteConnexion.CHOIX_ESPACE
                        },
                    )
                }
            } catch (e: OpenNoteException) {
                _uiState.update {
                    it.copy(
                        enCours = false,
                        erreur = e.userMessage(),
                        erreurEstAuth = e.category == ErrorCategory.AUTH,
                    )
                }
            }
        }
    }

    /** Consommé par l'écran après la navigation, pour ne pas naviguer deux fois. */
    fun suiteConsommee() = _uiState.update { it.copy(suite = null) }

    companion object {
        fun factory(container: AppContainer): ViewModelProvider.Factory = viewModelFactory {
            initializer { LoginViewModel(container.repository, container.tokenStore) }
        }
    }
}
