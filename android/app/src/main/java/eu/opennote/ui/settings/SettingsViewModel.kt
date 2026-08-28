package eu.opennote.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import eu.opennote.AppContainer
import eu.opennote.data.AppStateDto
import eu.opennote.data.ConflictDto
import eu.opennote.data.OpenNoteException
import eu.opennote.data.OpenNoteRepository
import eu.opennote.data.SyncResultDto
import eu.opennote.data.userMessage
import eu.opennote.sync.SyncNotifier
import eu.opennote.sync.SyncScheduler
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class SettingsUiState(
    val etat: AppStateDto = AppStateDto(),
    val enAttente: Int = 0,
    /**
     * Faux tant que le serveur n'a pas confirmé le token depuis le lancement.
     * `Restore` ouvre une session utilisable hors connexion : l'application
     * marche, mais sans preuve que le jeton soit encore valide.
     */
    val tokenValide: Boolean = false,
    val syncEnCours: Boolean = false,
    /** Résumé de la dernière passe, affiché sous le bouton. */
    val resume: String? = null,
    /** Vrai quand ce résumé décrit une passe incomplète. */
    val resumePartiel: Boolean = false,
    val conflits: List<ConflictDto> = emptyList(),
    val erreur: String? = null,
    val deconnecte: Boolean = false,
)

class SettingsViewModel(
    private val repository: OpenNoteRepository,
    private val syncScheduler: SyncScheduler,
    private val syncNotifier: SyncNotifier,
) : ViewModel() {

    private val _uiState = MutableStateFlow(SettingsUiState())
    val uiState: StateFlow<SettingsUiState> = _uiState.asStateFlow()

    init {
        rafraichir()
        viewModelScope.launch {
            repository.pendingCount.collect { n -> _uiState.update { it.copy(enAttente = n) } }
        }
        viewModelScope.launch {
            repository.sessionValidee.collect { v -> _uiState.update { it.copy(tokenValide = v) } }
        }
    }

    fun rafraichir() {
        viewModelScope.launch {
            try {
                val etat = repository.state()
                _uiState.update { it.copy(etat = etat) }
                repository.refreshPending()
            } catch (e: OpenNoteException) {
                _uiState.update { it.copy(erreur = e.userMessage()) }
            }
        }
    }

    /**
     * Synchronisation manuelle.
     *
     * Appel direct plutôt que passage par WorkManager : l'utilisateur regarde
     * l'écran et attend un résultat maintenant, pas une tâche différée. Les
     * déclencheurs automatiques, eux, restent dans [SyncScheduler].
     */
    fun synchroniser() {
        if (_uiState.value.syncEnCours) return
        _uiState.update { it.copy(syncEnCours = true, erreur = null, resume = null) }

        viewModelScope.launch {
            try {
                val rapport = repository.sync()
                if (rapport.conflicts.isNotEmpty()) {
                    syncNotifier.notifyConflicts(rapport.conflicts)
                }
                val apres = repository.state()
                _uiState.update {
                    it.copy(
                        syncEnCours = false,
                        resume = resumeDe(rapport),
                        resumePartiel = rapport.hasError,
                        conflits = rapport.conflicts,
                        etat = apres,
                    )
                }
            } catch (e: OpenNoteException) {
                // Seule l'absence d'espace de travail arrive ici : une panne
                // réseau est décrite dans le rapport, pas levée.
                _uiState.update { it.copy(syncEnCours = false, erreur = e.userMessage()) }
            }
        }
    }

    fun deconnecter() {
        viewModelScope.launch {
            try {
                syncScheduler.cancelAll()
                repository.disconnect()
                _uiState.update { it.copy(deconnecte = true) }
            } catch (e: OpenNoteException) {
                _uiState.update { it.copy(erreur = e.userMessage()) }
            }
        }
    }

    fun conflitsConsommes() = _uiState.update { it.copy(conflits = emptyList()) }

    companion object {

        /**
         * Résumé d'une passe, en français et sans jargon.
         *
         * Une panne réseau donne un état **partiel**, pas un échec : « 3 notes
         * envoyées, 2 en attente » décrit exactement ce qui s'est passé, là où
         * « échec de la synchronisation » ferait croire à une perte.
         */
        fun resumeDe(rapport: SyncResultDto): String {
            val morceaux = buildList {
                if (rapport.pushed > 0) add("${rapport.pushed} ${notes(rapport.pushed)} ${envoyees(rapport.pushed)}")
                if (rapport.moved > 0) add("${rapport.moved} ${deplacee(rapport.moved)}")
                if (rapport.deleted > 0) add("${rapport.deleted} ${supprimee(rapport.deleted)}")
                if (rapport.remaining > 0) add("${rapport.remaining} en attente")
            }

            if (morceaux.isEmpty()) {
                return if (rapport.hasError) {
                    "Serveur injoignable. Rien à envoyer pour l'instant."
                } else {
                    "Tout est à jour."
                }
            }

            val phrase = morceaux.joinToString(", ").replaceFirstChar { it.uppercase() } + "."
            return if (rapport.hasError) "$phrase Le serveur a cessé de répondre en cours de route." else phrase
        }

        private fun notes(n: Int) = if (n > 1) "notes" else "note"
        private fun envoyees(n: Int) = if (n > 1) "envoyées" else "envoyée"
        private fun deplacee(n: Int) = if (n > 1) "déplacements" else "déplacement"
        private fun supprimee(n: Int) = if (n > 1) "suppressions" else "suppression"

        fun factory(container: AppContainer): ViewModelProvider.Factory = viewModelFactory {
            initializer {
                SettingsViewModel(
                    container.repository,
                    container.syncScheduler,
                    container.syncNotifier,
                )
            }
        }
    }
}
