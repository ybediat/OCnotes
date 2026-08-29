package eu.opennote.ui.browser

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import eu.opennote.AppContainer
import eu.opennote.R
import eu.opennote.data.FolderEntryDto
import eu.opennote.data.OpenNoteException
import eu.opennote.data.OpenNoteRepository
import eu.opennote.ui.common.Texte
import eu.opennote.ui.common.texte
import eu.opennote.sync.SyncScheduler
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class BrowserUiState(
    /**
     * Nom du dossier de notes, uniquement pour l'affichage du titre.
     *
     * Ce n'est PAS un chemin utilisable : `state.root` est relatif à l'espace,
     * alors que tous les chemins de la façade sont relatifs au dossier de
     * notes. Les confondre fait demander « Notes » là où le serveur attend la
     * racine, donc chercher « Notes/Notes ».
     */
    val nomRacine: String = "Notes",
    /** Chemin courant, relatif au dossier de notes. Vide = sa racine. */
    val cheminCourant: String = "",
    val entrees: List<FolderEntryDto> = emptyList(),
    val chargement: Boolean = true,
    /** Le listing vient du cache : le réseau manquait. */
    val depuisCache: Boolean = false,
    val erreur: Texte? = null,
    val enAttente: Int = 0,
) {
    /**
     * Le dossier de notes est le plancher de la navigation : on ne remonte
     * jamais au-dessus, même si l'espace contient d'autres dossiers. Y être
     * se reconnaît à un chemin vide.
     */
    val peutRemonter: Boolean get() = cheminCourant.isNotEmpty()

    val titre: String
        get() = cheminCourant.substringAfterLast('/').ifBlank { nomRacine }
}

/** Ce que la vue doit faire une fois une opération terminée. */
sealed interface BrowserEvent {
    data class OuvrirNote(val chemin: String) : BrowserEvent
    data class Message(val texte: Texte) : BrowserEvent
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
                    // Le dernier segment suffit à titrer : « Mes notes » pour
                    // une racine « Documents/Mes notes ».
                    nomRacine = etat.root.substringAfterLast('/').ifBlank { "Notes" },
                    // `lastPath` ramène l'utilisateur là où il était, ce que
                    // la façade a mémorisé pour nous. Vide, il désigne la
                    // racine du dossier de notes — surtout pas `etat.root`,
                    // qui est exprimé dans un autre référentiel.
                    cheminCourant = etat.lastPath,
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
                _uiState.update { it.copy(chargement = false, erreur = e.texte()) }
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
                _evenements.value = BrowserEvent.Message(e.texte())
            }
        }
    }

    fun creerDossier(nom: String) {
        viewModelScope.launch {
            try {
                repository.createFolder(_uiState.value.cheminCourant, nom.trim())
                recharger()
            } catch (e: OpenNoteException) {
                _evenements.value = BrowserEvent.Message(e.texte())
            }
        }
    }

    fun renommer(entree: FolderEntryDto, nouveauNom: String) {
        viewModelScope.launch {
            try {
                repository.rename(entree.path, nouveauNom.trim())
                recharger()
            } catch (e: OpenNoteException) {
                _evenements.value = BrowserEvent.Message(e.texte())
            }
        }
    }

    fun supprimer(entree: FolderEntryDto) {
        viewModelScope.launch {
            try {
                repository.delete(entree.path)
                recharger()
                _evenements.value = BrowserEvent.Message(Texte.de(R.string.browser_supprime, entree.display))
            } catch (e: OpenNoteException) {
                _evenements.value = BrowserEvent.Message(e.texte())
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
