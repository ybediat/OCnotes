package eu.opennote.ui.browser

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import eu.opennote.AppContainer
import eu.opennote.R
import eu.opennote.data.FolderEntryDto
import eu.opennote.data.FolderRefDto
import eu.opennote.data.OpenNoteException
import eu.opennote.data.OpenNoteRepository
import eu.opennote.data.PreferencesAffichage
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
    /** Ordre des notes. Les dossiers restent en tête, toujours alphabétiques. */
    val tri: Tri = Tri.DEFAUT,
    /** Filtre sur les noms affichés : ce dossier, ou toute la bibliothèque. */
    val recherche: String = "",
    /** Arborescence ou liste plate. */
    val mode: ModeAffichage = ModeAffichage.DEFAUT,
    /** Dossiers proposés à la création d'une note. */
    val dossiers: List<FolderRefDto> = emptyList(),
    /** Dernier dossier de création, pour le mode liste plate. */
    val dernierDossier: String = "",
) {
    val enListePlate: Boolean get() = mode == ModeAffichage.LISTE

    /**
     * Ce que la liste montre réellement : [entrees] filtré puis ordonné.
     *
     * Calculé une fois par état plutôt qu'à chaque accès — un état est
     * immuable, et la composition lit cette propriété plusieurs fois par
     * dessin.
     */
    val entreesAffichees: List<FolderEntryDto> by lazy { classer(entrees, recherche, tri) }

    /**
     * Le dossier a du contenu, mais la recherche n'en retient rien.
     *
     * Distinguer ce cas du dossier vide compte : le premier se corrige en
     * effaçant le champ, le second en créant une note.
     */
    val rechercheSansResultat: Boolean
        get() = entrees.isNotEmpty() && entreesAffichees.isEmpty()

    /**
     * Le dossier de notes est le plancher de la navigation : on ne remonte
     * jamais au-dessus, même si l'espace contient d'autres dossiers. Y être
     * se reconnaît à un chemin vide.
     */
    val peutRemonter: Boolean get() = !enListePlate && cheminCourant.isNotEmpty()

    /**
     * Titre de la barre.
     *
     * En liste plate il n'y a pas de dossier courant : le nom de la
     * bibliothèque est ce qui décrit le mieux ce qu'on regarde.
     */
    val titre: String
        get() = if (enListePlate) nomRacine else cheminCourant.substringAfterLast('/').ifBlank { nomRacine }

    /**
     * Dossier proposé à la création d'une note.
     *
     * En arborescence, celui qu'on regarde : c'est ce que l'application
     * faisait déjà, en silence. En liste plate il n'y en a pas, d'où le
     * dernier utilisé — « toujours la racine » entasserait tout au même
     * endroit et viderait les dossiers de leur intérêt.
     */
    val dossierPropose: String
        get() = if (enListePlate) dernierDossier else cheminCourant
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
    private val preferences: PreferencesAffichage,
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

        // Les réglages d'affichage viennent d'un flux : le mode se change
        // depuis le tiroir, qui ne connaît pas ce ViewModel. Le premier
        // relisting est déclenché par le changement de mode, pas ici.
        viewModelScope.launch {
            preferences.tri.collect { valeur ->
                _uiState.update { it.copy(tri = Tri.depuis(valeur)) }
            }
        }

        viewModelScope.launch {
            preferences.dernierDossier.collect { valeur ->
                _uiState.update { it.copy(dernierDossier = valeur.orEmpty()) }
            }
        }

        viewModelScope.launch {
            var premier = true
            preferences.mode.collect { valeur ->
                val mode = ModeAffichage.depuis(valeur)
                if (!premier && mode == _uiState.value.mode) return@collect
                premier = false
                _uiState.update { it.copy(mode = mode).sansRecherche() }
                recharger()
            }
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
        if (entree.isDir && !_uiState.value.enListePlate) {
            _uiState.update { it.copy(cheminCourant = entree.path).sansRecherche() }
            recharger()
        } else {
            _evenements.value = BrowserEvent.OuvrirNote(entree.path)
        }
    }

    fun remonter() {
        val etat = _uiState.value
        if (!etat.peutRemonter) return
        _uiState.update {
            it.copy(cheminCourant = etat.cheminCourant.substringBeforeLast('/', "")).sansRecherche()
        }
        recharger()
    }

    fun recharger() {
        _uiState.update { it.copy(chargement = true, erreur = null) }

        viewModelScope.launch {
            try {
                val etat = _uiState.value
                // Deux sources, un seul état : la liste plate est un listing
                // comme un autre, simplement sans dossier et sans chemin.
                val listing = if (etat.enListePlate) {
                    repository.listAll()
                } else {
                    repository.listFolder(etat.cheminCourant)
                }

                _uiState.update {
                    it.copy(
                        chargement = false,
                        // `path` est le chemin nettoyé par Go : on adopte le
                        // sien plutôt que de garder notre concaténation. En
                        // liste plate il est vide, et le chemin courant ne
                        // doit pas bouger — il sert au retour en arborescence.
                        cheminCourant = if (it.enListePlate) it.cheminCourant else listing.path,
                        entrees = listing.entries,
                        depuisCache = listing.fromCache,
                    )
                }
                repository.refreshPending()
                rechargerDossiers()
            } catch (e: OpenNoteException) {
                _uiState.update { it.copy(chargement = false, erreur = e.texte()) }
            }
        }
    }

    /**
     * Rafraîchit la liste des destinations possibles.
     *
     * Elle vient du cache, donc sans réseau, et son échec ne doit rien casser :
     * un sélecteur incomplet vaut mieux qu'une liste qui refuse de s'ouvrir.
     */
    private suspend fun rechargerDossiers() {
        val dossiers = try {
            repository.folders()
        } catch (_: OpenNoteException) {
            return
        }
        _uiState.update { it.copy(dossiers = dossiers) }
    }

    /** Geste de rafraîchissement explicite : on relit ET on pousse. */
    fun rafraichir() {
        syncScheduler.syncNow()
        recharger()
    }

    // --- Affichage ---------------------------------------------------------

    /**
     * Bascule l'ordre des notes, et le retient.
     *
     * L'écriture de la préférence n'est pas attendue : la liste se réordonne
     * dans l'image suivante, et un disque lent ne doit pas se voir sur un
     * appui de bouton.
     */
    fun changerTri() {
        preferences.definirTri(_uiState.value.tri.suivant().name)
    }

    fun effacerRecherche() {
        _uiState.update { it.sansRecherche() }
    }

    fun chercher(texte: String) {
        _uiState.update { it.copy(recherche = texte) }
    }

    /**
     * La recherche ne survit pas à un changement de dossier.
     *
     * Elle porte sur le dossier affiché — c'est ce que son invite annonce.
     * La laisser en place en changeant de dossier ferait apparaître un dossier
     * fraîchement ouvert comme presque vide, sans que rien ne l'explique.
     */
    private fun BrowserUiState.sansRecherche(): BrowserUiState = copy(recherche = "")

    // --- Actions -----------------------------------------------------------

    /**
     * Crée une note à partir d'un titre saisi.
     *
     * Le titre passe par `SuggestName`, qui produit un nom de fichier valide —
     * la logique de nommage est en Go, testée, et tient compte de ce que le
     * cache local sait écrire. La note s'ouvre aussitôt : on ne crée pas une
     * note pour la regarder dans une liste.
     */
    fun creerNote(titre: String, dossier: String) {
        viewModelScope.launch {
            try {
                val nom = repository.suggestName(titre)
                val contenu = if (titre.isBlank()) "" else "# ${titre.trim()}\n\n"
                val note = repository.createNote(dossier, nom, contenu)
                // Retenu pour la prochaine création en liste plate, où
                // aucun dossier courant ne peut servir de proposition.
                preferences.definirDernierDossier(dossier)
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

    /**
     * Déplace une note vers un autre dossier.
     *
     * `entree.path` reste celui d'avant le déplacement : si l'utilisateur
     * regardait ce dossier en arborescence, la note doit en disparaître au
     * rechargement, ce que le nouveau chemin lu depuis le serveur garantit
     * déjà — inutile de la retirer de la liste ici.
     */
    fun deplacer(entree: FolderEntryDto, dossier: String) {
        viewModelScope.launch {
            try {
                repository.move(entree.path, dossier)
                recharger()
                _evenements.value = BrowserEvent.Message(Texte.de(R.string.browser_deplace, entree.display))
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
            initializer {
                BrowserViewModel(
                    container.repository,
                    container.syncScheduler,
                    container.preferencesAffichage,
                )
            }
        }
    }
}
