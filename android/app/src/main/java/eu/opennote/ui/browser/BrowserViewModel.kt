package eu.opennote.ui.browser

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import eu.opennote.AppContainer
import eu.opennote.data.FolderEntryDto
import eu.opennote.data.OpenNoteException
import eu.opennote.data.OpenNoteRepository
import eu.opennote.data.userMessage
import eu.opennote.sync.SyncScheduler
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class BrowserUiState(
    val racine: String = "",
    val cheminCourant: String = "",
    val entrees: List<FolderEntryDto> = emptyList(),
    val chargement: Boolean = true,
    /** Le listing vient du cache : le réseau manquait. */
    val depuisCache: Boolean = false,
    val erreur: String? = null,
    val enAttente: Int = 0,
) {
    /**
     * Le dossier racine est le plancher de la navigation : on ne remonte
     * jamais au-dessus du dossier de notes, même si l'espace contient d'autres
     * dossiers.
     */
    val peutRemonter: Boolean get() = cheminCourant != racine && cheminCourant.isNotEmpty()

    val titre: String
        get() = cheminCourant.substringAfterLast('/').ifBlank { "Notes" }
}

/** Ce que la vue doit faire une fois une opération terminée. */
sealed interface BrowserEvent {
    data class OuvrirNote(val chemin: String) : BrowserEvent
    data class Message(val texte: String) : BrowserEvent
}

/**
 * Navigateur de l'arborescence de notes.
 *
 * La navigation dans les sous-dossiers ne crée pas d'entrée dans la pile de
 * navigation Compose : le chemin courant est un état, et le retour arrière se
 * déduit du chemin lui-même. Une pile parallèle n'apporterait rien et pourrait
 * diverger du chemin réel après un renommage.
 */
class BrowserViewModel(
    private val repository: OpenNoteRepository,
    private val syncScheduler: SyncScheduler,
) : ViewModel() {

    private val _uiState = MutableStateFlow(BrowserUiState())
    val uiState: StateFlow<BrowserUiState> = _uiState.asStateFlow()

    private val _evenements = MutableStateFlow<BrowserEvent?>(null)
    val evenements: StateFlow<BrowserEvent?> = _evenements.asStateFlow()

    init {
        viewModelScope.launch {
            val etat = repository.state()
            _uiState.update {
                it.copy(
                    racine = etat.root,
                    // `lastPath` ramène l'utilisateur là où il était, ce que
                    // la façade a mémorisé pour nous.
                    cheminCourant = etat.lastPath.ifBlank { etat.root },
                )
            }
            recharger()
        }

        viewModelScope.launch {
            repository.pendingCount.collect { n -> _uiState.update { it.copy(enAttente = n) } }
        }

        // Le premier listing peut venir du cache : `Restore` ouvre la session
        // sans réseau, et la validation du token se termine après coup. Quand
        // elle aboutit, la vue est rechargée depuis le serveur.
        //
        // La valeur courante est ignorée — un `StateFlow` la rejoue à la
        // souscription et on rechargerait le listing qu'on vient de demander.
        viewModelScope.launch {
            var premier = true
            repository.sessionValidee.collect { valide ->
                if (premier) {
                    premier = false
                } else if (valide) {
                    recharger()
                }
            }
        }
    }

    // --- Navigation --------------------------------------------------------

    fun ouvrir(entree: FolderEntryDto) {
        if (entree.isDir) {
            _uiState.update { it.copy(cheminCourant = entree.path) }
            recharger()
        } else {
            _evenements.value = BrowserEvent.OuvrirNote(entree.path)
        }
    }

    fun remonter() {
        val etat = _uiState.value
        if (!etat.peutRemonter) return
        _uiState.update { it.copy(cheminCourant = etat.cheminCourant.substringBeforeLast('/', "")) }
        recharger()
    }

    fun recharger() {
        _uiState.update { it.copy(chargement = true, erreur = null) }

        viewModelScope.launch {
            try {
                val listing = repository.listFolder(_uiState.value.cheminCourant)
                _uiState.update {
                    it.copy(
                        chargement = false,
                        // `path` est le chemin nettoyé par Go : on adopte le
                        // sien plutôt que de garder notre concaténation.
                        cheminCourant = listing.path,
                        entrees = listing.entries,
                        depuisCache = listing.fromCache,
                    )
                }
                repository.refreshPending()
            } catch (e: OpenNoteException) {
                _uiState.update { it.copy(chargement = false, erreur = e.userMessage()) }
            }
        }
    }

    /** Geste de rafraîchissement explicite : on relit ET on pousse. */
    fun rafraichir() {
        syncScheduler.syncNow()
        recharger()
    }

    // --- Actions -----------------------------------------------------------

    /**
     * Crée une note à partir d'un titre saisi.
     *
     * Le titre passe par `SuggestName`, qui produit un nom de fichier valide —
     * la logique de nommage est en Go, testée, et tient compte de ce que le
     * cache local sait écrire. La note s'ouvre aussitôt : on ne crée pas une
     * note pour la regarder dans une liste.
     */
    fun creerNote(titre: String) {
        viewModelScope.launch {
            try {
                val nom = repository.suggestName(titre)
                val contenu = if (titre.isBlank()) "" else "# ${titre.trim()}\n\n"
                val note = repository.createNote(_uiState.value.cheminCourant, nom, contenu)
                recharger()
                syncScheduler.syncAfterWrite()
                _evenements.value = BrowserEvent.OuvrirNote(note.path)
            } catch (e: OpenNoteException) {
                _evenements.value = BrowserEvent.Message(e.userMessage())
            }
        }
    }

    fun creerDossier(nom: String) {
        viewModelScope.launch {
            try {
                repository.createFolder(_uiState.value.cheminCourant, nom.trim())
                recharger()
            } catch (e: OpenNoteException) {
                _evenements.value = BrowserEvent.Message(e.userMessage())
            }
        }
    }

    fun renommer(entree: FolderEntryDto, nouveauNom: String) {
        viewModelScope.launch {
            try {
                repository.rename(entree.path, nouveauNom.trim())
                recharger()
            } catch (e: OpenNoteException) {
                _evenements.value = BrowserEvent.Message(e.userMessage())
            }
        }
    }

    fun supprimer(entree: FolderEntryDto) {
        viewModelScope.launch {
            try {
                repository.delete(entree.path)
                recharger()
                _evenements.value = BrowserEvent.Message("« ${entree.display} » supprimé.")
            } catch (e: OpenNoteException) {
                _evenements.value = BrowserEvent.Message(e.userMessage())
            }
        }
    }

    fun evenementConsomme() {
        _evenements.value = null
    }

    companion object {
        fun factory(container: AppContainer): ViewModelProvider.Factory = viewModelFactory {
            initializer { BrowserViewModel(container.repository, container.syncScheduler) }
        }
    }
}
