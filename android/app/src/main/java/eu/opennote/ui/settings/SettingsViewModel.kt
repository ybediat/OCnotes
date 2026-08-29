package eu.opennote.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import eu.opennote.AppContainer
import eu.opennote.R
import eu.opennote.data.AppStateDto
import eu.opennote.data.ConflictDto
import eu.opennote.data.OpenNoteException
import eu.opennote.data.OpenNoteRepository
import eu.opennote.data.SyncResultDto
import eu.opennote.ui.common.Texte
import eu.opennote.ui.common.texte
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
    val resume: Texte? = null,
    /** Vrai quand ce résumé décrit une passe incomplète. */
    val resumePartiel: Boolean = false,
    val conflits: List<ConflictDto> = emptyList(),
    val erreur: Texte? = null,
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
                _uiState.update { it.copy(erreur = e.texte()) }
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
                _uiState.update { it.copy(syncEnCours = false, erreur = e.texte()) }
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
                _uiState.update { it.copy(erreur = e.texte()) }
            }
        }
    }

    fun conflitsConsommes() = _uiState.update { it.copy(conflits = emptyList()) }

    companion object {

        /**
         * Résumé d'une passe, décrit et non rédigé.
         *
         * Une panne réseau donne un état **partiel**, pas un échec : « 3 notes
         * envoyées, 2 en attente » décrit exactement ce qui s'est passé, là où
         * « échec de la synchronisation » ferait croire à une perte.
         *
         * # Ce qui a changé pour la traduction
         *
         * Les accords se faisaient ici, par `if (n > 1)`. C'est faux dès qu'on
         * sort du français : le polonais distingue trois formes, l'arabe six.
         * Chaque morceau est donc devenu un `<plurals>`, seul endroit qui
         * connaisse les règles d'accord de sa langue.
         *
         * Le `replaceFirstChar { it.uppercase() }` d'origine a disparu avec :
         * les morceaux commencent tous par un nombre, il ne faisait rien, et
         * il aurait mal tourné dans les langues où la majuscule ne s'obtient
         * pas caractère par caractère.
         *
         * Reste une approximation assumée : joindre par un séparateur suppose
         * que l'énumération se lit dans cet ordre partout. C'est une liste,
         * pas une phrase, et le séparateur lui-même est une ressource.
         */
        fun resumeDe(rapport: SyncResultDto): Texte {
            val morceaux = buildList {
                if (rapport.pushed > 0) add(Texte.pluriel(R.plurals.sync_notes_envoyees, rapport.pushed))
                if (rapport.moved > 0) add(Texte.pluriel(R.plurals.sync_deplacements, rapport.moved))
                if (rapport.deleted > 0) add(Texte.pluriel(R.plurals.sync_suppressions, rapport.deleted))
                if (rapport.remaining > 0) add(Texte.pluriel(R.plurals.sync_en_attente, rapport.remaining))
            }

            if (morceaux.isEmpty()) {
                return Texte.de(
                    if (rapport.hasError) R.string.sync_hors_ligne else R.string.sync_a_jour,
                )
            }

            val liste = Texte.Liste(morceaux, R.string.sync_separateur)
            return Texte.de(
                if (rapport.hasError) R.string.sync_resume_partiel else R.string.sync_resume,
                liste,
            )
        }

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
