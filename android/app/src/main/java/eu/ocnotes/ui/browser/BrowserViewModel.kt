package eu.ocnotes.ui.browser

import androidx.annotation.PluralsRes
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import eu.ocnotes.AppContainer
import eu.ocnotes.R
import eu.ocnotes.data.AppMode
import eu.ocnotes.data.FolderEntryDto
import eu.ocnotes.data.FolderRefDto
import eu.ocnotes.data.OCnotesException
import eu.ocnotes.data.OCnotesRepository
import eu.ocnotes.data.PreferencesAffichage
import eu.ocnotes.ui.common.FichierPartage
import eu.ocnotes.ui.common.Texte
import eu.ocnotes.ui.common.texte
import eu.ocnotes.sync.SyncScheduler
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
    /**
     * Chemins des entrées cochées en sélection multiple.
     *
     * Des chemins, pas des [FolderEntryDto] : une entrée peut être remplacée
     * par un rechargement (nouveau `modTime`, pastille qui tombe) sans que la
     * sélection doive sauter. [recharger] élague ce qui n'existe plus.
     */
    val selection: Set<String> = emptySet(),
    /** Les suppressions sont définitives : aucune copie serveur n'existe. */
    val modeLocal: Boolean = false,
) {
    val enListePlate: Boolean get() = mode == ModeAffichage.LISTE

    /** Une sélection multiple est en cours : la barre de titre devient contextuelle. */
    val modeSelection: Boolean get() = selection.isNotEmpty()

    /**
     * La sélection contient au moins un dossier.
     *
     * Le déplacement groupé ne couvre que les notes — un dossier déplacé
     * localement ne met à jour que ses notes déjà chargées, même règle de
     * cache que le menu par ligne, qui n'offre pas « Déplacer » sur un
     * dossier. La suppression, elle, l'accepte.
     */
    val selectionContientDossier: Boolean
        get() = entrees.any { it.isDir && it.path in selection }

    /**
     * La sélection contient au moins un document en lecture seule (`.docx`,
     * `.odt`). La façade refuse d'en copier les octets — un PUT les
     * corromprait — d'où le retrait de « Copier ». Un déplacement, lui, ne
     * touche pas au contenu et reste permis.
     */
    val selectionContientDocument: Boolean
        get() = entrees.any { it.readOnly && it.path in selection }

    /** Le déplacement groupé est proposable : une sélection, et aucune n'est un dossier. */
    val peutDeplacerSelection: Boolean get() = modeSelection && !selectionContientDossier

    /** La copie groupée est proposable : que des notes modifiables, ni dossier ni document. */
    val peutCopierSelection: Boolean
        get() = peutDeplacerSelection && !selectionContientDocument

    /**
     * Le partage groupé est proposable dans les mêmes conditions que la copie :
     * on ne joint en pièce jointe que des notes `.md` ou `.txt`. Un dossier n'a
     * pas de contenu, et le binaire d'un document `.docx` ne traverse pas la
     * façade — seul son texte extrait le fait.
     */
    val peutPartagerSelection: Boolean get() = peutCopierSelection

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

    /**
     * Ouvrir le sélecteur de partage avec ces notes en pièce jointe.
     *
     * Le contenu est déjà lu : l'écriture des fichiers et l'intent sont du
     * ressort de la vue, qui seule a un `Context`. [avertissement] porte le cas
     * d'un lot dont une partie n'a pas pu être lue hors connexion — les autres
     * partent quand même, et le message le dit après coup.
     */
    data class Partager(
        val fichiers: List<FichierPartage>,
        val avertissement: Texte? = null,
    ) : BrowserEvent
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
    private val repository: OCnotesRepository,
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
                    modeLocal = etat.mode == AppMode.LOCAL,
                )
            }
            recharger()
        }

        viewModelScope.launch {
            var precedent = repository.mode.value
            repository.mode.collect { mode ->
                val change = precedent.isNotBlank() && mode.isNotBlank() && mode != precedent
                precedent = mode
                _uiState.update { it.copy(modeLocal = mode == AppMode.LOCAL) }
                if (change) recharger()
            }
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
                _uiState.update { it.copy(mode = mode).sansRechercheNiSelection() }
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
            _uiState.update { it.copy(cheminCourant = entree.path).sansRechercheNiSelection() }
            recharger()
        } else {
            _evenements.value = BrowserEvent.OuvrirNote(entree.path)
        }
    }

    fun remonter() {
        val etat = _uiState.value
        if (!etat.peutRemonter) return
        _uiState.update {
            it.copy(cheminCourant = etat.cheminCourant.substringBeforeLast('/', ""))
                .sansRechercheNiSelection()
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

                val cheminsPresents = listing.entries.mapTo(HashSet()) { entree -> entree.path }
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
                        // Une entrée déplacée ou supprimée disparaît du listing :
                        // la retirer de la sélection referme la barre contextuelle
                        // toute seule quand un lot a été traité, et évite qu'un
                        // rechargement de synchro garde des chemins fantômes.
                        selection = it.selection intersect cheminsPresents,
                    )
                }
                repository.refreshPending()
                rechargerDossiers()
            } catch (e: OCnotesException) {
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
        } catch (_: OCnotesException) {
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

    /**
     * Changer de dossier ou de mode d'affichage vide aussi la sélection : ses
     * chemins ne désignent plus rien d'affiché, et une action groupée porterait
     * sur des lignes que l'utilisateur ne voit plus.
     */
    private fun BrowserUiState.sansRechercheNiSelection(): BrowserUiState =
        copy(recherche = "", selection = emptySet())

    // --- Sélection multiple ----------------------------------------------------

    /** Coche ou décoche une ligne. Le premier appui ouvre la barre contextuelle. */
    fun basculerSelection(entree: FolderEntryDto) {
        _uiState.update {
            val s = it.selection
            it.copy(selection = if (entree.path in s) s - entree.path else s + entree.path)
        }
    }

    /** Sort du mode sélection sans rien faire d'autre. */
    fun viderSelection() {
        _uiState.update { it.copy(selection = emptySet()) }
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
                syncScheduler.syncAfterLocalChange()
                _evenements.value = BrowserEvent.OuvrirNote(note.path)
            } catch (e: OCnotesException) {
                _evenements.value = BrowserEvent.Message(e.texte())
            }
        }
    }

    fun creerDossier(nom: String) {
        viewModelScope.launch {
            try {
                repository.createFolder(_uiState.value.cheminCourant, nom.trim())
                recharger()
                syncScheduler.syncAfterLocalChange()
            } catch (e: OCnotesException) {
                _evenements.value = BrowserEvent.Message(e.texte())
            }
        }
    }

    fun renommer(entree: FolderEntryDto, nouveauNom: String) {
        viewModelScope.launch {
            try {
                repository.rename(entree.path, nouveauNom.trim())
                recharger()
                syncScheduler.syncAfterLocalChange()
            } catch (e: OCnotesException) {
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
                syncScheduler.syncAfterLocalChange()
                _evenements.value = BrowserEvent.Message(Texte.de(R.string.browser_deplace, entree.display))
            } catch (e: OCnotesException) {
                _evenements.value = BrowserEvent.Message(e.texte())
            }
        }
    }

    fun supprimer(entree: FolderEntryDto) {
        viewModelScope.launch {
            try {
                repository.delete(entree.path)
                recharger()
                syncScheduler.syncAfterLocalChange()
                _evenements.value = BrowserEvent.Message(Texte.de(R.string.browser_supprime, entree.display))
            } catch (e: OCnotesException) {
                _evenements.value = BrowserEvent.Message(e.texte())
            }
        }
    }

    /**
     * Partage une note en pièce jointe.
     *
     * La lecture passe par le cache (`readNote`) : elle aboutit hors connexion
     * si la note a déjà été ouverte, et échoue proprement sinon —
     * `[CONTENT_NOT_CACHED]`, formulé par la couche d'erreur. L'écriture du
     * fichier et l'intent sont laissés à la vue.
     */
    fun partager(entree: FolderEntryDto) {
        viewModelScope.launch {
            try {
                val contenu = repository.readNote(entree.path)
                _evenements.value = BrowserEvent.Partager(
                    listOf(FichierPartage(entree.name, contenu)),
                )
            } catch (e: OCnotesException) {
                _evenements.value = BrowserEvent.Message(e.texte())
            }
        }
    }

    // --- Actions groupées ----------------------------------------------------

    /** Déplace toutes les entrées sélectionnées vers un même dossier. */
    fun deplacerLot(dossier: String) =
        executerLot(R.plurals.browser_lot_deplaces) { repository.move(it, dossier) }

    /** Copie toutes les notes sélectionnées vers un même dossier. */
    fun copierLot(dossier: String) =
        executerLot(R.plurals.browser_lot_copies) { repository.copy(it, dossier) }

    /** Supprime toutes les entrées sélectionnées. */
    fun supprimerLot() =
        executerLot(R.plurals.browser_lot_supprimes) { repository.delete(it) }

    /**
     * Lit chaque note sélectionnée et émet un [BrowserEvent.Partager].
     *
     * Ce n'est pas un [executerLot] : rien n'est écrit ni synchronisé, et le
     * résultat n'est pas un résumé mais une liste de fichiers à joindre. Une
     * note illisible hors connexion est retirée du lot — les autres partent, et
     * l'avertissement porté par l'événement le signale. Si aucune n'a pu être
     * lue, on ne montre que l'erreur.
     */
    fun partagerLot() {
        val cibles = _uiState.value.selection.toList()
        if (cibles.isEmpty()) return
        viewModelScope.launch {
            val fichiers = mutableListOf<FichierPartage>()
            val echecs = mutableListOf<OCnotesException>()
            for (chemin in cibles) {
                val entree = _uiState.value.entrees.firstOrNull { it.path == chemin } ?: continue
                try {
                    fichiers += FichierPartage(entree.name, repository.readNote(chemin))
                } catch (e: OCnotesException) {
                    echecs += e
                }
            }
            if (fichiers.isEmpty()) {
                echecs.firstOrNull()?.let { _evenements.value = BrowserEvent.Message(it.texte()) }
                return@launch
            }
            _evenements.value = BrowserEvent.Partager(
                fichiers = fichiers,
                avertissement = if (echecs.isEmpty()) {
                    null
                } else {
                    Texte.pluriel(R.plurals.browser_partager_echecs, echecs.size)
                },
            )
            viderSelection()
        }
    }

    /**
     * Rejoue une opération sur chaque entrée sélectionnée, une par une.
     *
     * Chaque opération est indépendante : un échec — conflit, note disparue,
     * contenu absent hors connexion — n'interrompt pas les autres. Le résumé
     * dit ce qui est passé et ce qui a échoué ; [recharger] retire ensuite de
     * la sélection ce qui a effectivement changé, laissant les échecs cochés
     * pour une nouvelle tentative, et refermant la barre contextuelle quand
     * tout a abouti. Un seul rechargement et une seule passe de synchro à la
     * fin, pas un par élément.
     */
    private fun executerLot(
        @PluralsRes succes: Int,
        operation: suspend (chemin: String) -> Unit,
    ) {
        val cibles = _uiState.value.selection.toList()
        if (cibles.isEmpty()) return
        viewModelScope.launch {
            var reussis = 0
            val echecs = mutableListOf<OCnotesException>()
            for (chemin in cibles) {
                try {
                    operation(chemin)
                    reussis++
                } catch (e: OCnotesException) {
                    echecs += e
                }
            }
            recharger()
            syncScheduler.syncAfterLocalChange()
            _evenements.value = BrowserEvent.Message(resumeLot(succes, reussis, echecs))
        }
    }

    /**
     * Résumé d'une action groupée, sur le même modèle que celui de la synchro :
     * des morceaux `<plurals>` joints par `sync_separateur` et clos par
     * `sync_resume`. Quand rien n'a abouti, le vrai message d'erreur — hors
     * connexion, conflit — en dit plus qu'un « 0 déplacé ».
     */
    private fun resumeLot(
        @PluralsRes succes: Int,
        reussis: Int,
        echecs: List<OCnotesException>,
    ): Texte = when {
        echecs.isEmpty() -> Texte.de(R.string.sync_resume, Texte.pluriel(succes, reussis))
        reussis == 0 -> echecs.first().texte()
        else -> Texte.de(
            R.string.sync_resume,
            Texte.Liste(
                listOf(
                    Texte.pluriel(succes, reussis),
                    Texte.pluriel(R.plurals.browser_lot_echecs, echecs.size),
                ),
                R.string.sync_separateur,
            ),
        )
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
