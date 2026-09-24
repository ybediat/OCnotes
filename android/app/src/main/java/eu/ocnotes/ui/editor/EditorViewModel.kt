package eu.ocnotes.ui.editor

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import eu.ocnotes.AppContainer
import eu.ocnotes.data.AppMode
import eu.ocnotes.data.FormatAction
import eu.ocnotes.data.NoteBlockDto
import eu.ocnotes.data.OCnotesException
import eu.ocnotes.data.OCnotesRepository
import eu.ocnotes.ui.common.Texte
import eu.ocnotes.ui.common.texte
import eu.ocnotes.sync.SyncScheduler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class EditorUiState(
    val chemin: String = "",

    /**
     * Le texte **complet** de la note, tel que préparé à l'ouverture, puis tel
     * que photographié à la dernière bascule vers l'aperçu.
     *
     * Ce n'est **pas** la source de vérité pendant la saisie : celle-ci est
     * l'`Editable` du champ natif, qui ne traverse jamais cet état. Publier
     * 285 ko ici à chaque touche recomposerait l'écran pour rien ; le texte
     * n'est photographié qu'aux frontières qui en ont besoin — enregistrement
     * différé, retour, aperçu, mise en forme.
     */
    val document: String = "",

    /**
     * Version du contenu, incrémentée par le `TextWatcher` du champ natif.
     *
     * Elle écarte un résultat asynchrone périmé et décide qu'un instantané est
     * encore le bon à écrire.
     */
    val revision: Long = 0,
    val titre: String = "",
    val actions: List<FormatAction> = emptyList(),
    val chargement: Boolean = true,
    val modifie: Boolean = false,
    val erreur: Texte? = null,

    /**
     * Vrai quand le contenu de la note a réellement été lu.
     *
     * Ce n'est pas l'inverse de [chargement], et c'est tout l'intérêt :
     * l'état initial porte un texte vide, et un chargement qui échoue le laisse
     * vide en repassant [chargement] à faux. Sans ce troisième état, quitter
     * l'écran à ce moment-là enregistrait cette chaîne vide **par-dessus la
     * note** — le cache la marquait modifiée, la synchronisation la poussait, et
     * une note qu'on n'avait même pas réussi à ouvrir se retrouvait effacée sur
     * le serveur, sans un message.
     */
    val charge: Boolean = false,

    /** Mode lecture : le texte est rendu, la saisie est suspendue. */
    val apercu: Boolean = false,

    /** Blocs de l'aperçu, recalculés à chaque bascule. */
    val blocs: List<NoteBlockDto> = emptyList(),

    /**
     * Fichier affiché tel quel — un .txt.
     *
     * La barre de mise en forme est alors masquée : y insérer du Markdown
     * écrirait des marqueurs que rien ne rendra jamais.
     */
    val texteBrut: Boolean = false,

    /** Document Office lu par Go et toujours ouvert en lecture seule. */
    val documentBureautique: Boolean = false,

    /**
     * Faux quand la note porte un mot si long qu'un champ de saisie ne
     * survivrait pas à sa mise en page. Elle s'ouvre alors en aperçu, sans
     * retour possible vers la saisie.
     */
    val modifiable: Boolean = true,

    /**
     * Vrai quand les notes ne vivent que sur cet appareil.
     *
     * Décide de la formulation d'un contenu introuvable : « rouvrez-la une
     * fois la connexion revenue » n'a pas de sens à écrire à quelqu'un qui n'a
     * pas de serveur — il n'y a pas de connexion à attendre.
     */
    val modeLocal: Boolean = false,
    val garderEcranAllumeLecture: Boolean = false,
    val garderEcranAllumeEdition: Boolean = false,
) {
    /**
     * Vrai quand le texte saisi peut être écrit dans la note sans risquer de la
     * remplacer par autre chose qu'elle-même.
     *
     * Deux conditions, deux raisons distinctes : [charge] dit qu'il y a bien un
     * contenu derrière ce qui s'affiche, [modifiable] qu'il n'a pas été allégé
     * pour tenir dans un champ de saisie.
     *
     * La règle vit sur l'état plutôt que dans le ViewModel pour être vérifiable
     * sans appareil ni Robolectric : c'est une fonction de quatre booléens, et
     * la course qu'elle protège — sortir de l'écran pendant le chargement —
     * n'est pas reproductible à la main.
     */
    val enregistrable: Boolean get() = charge && modifiable
}

/** Garde pure du chemin natif : aucune ouverture ou révision périmée n'écrit. */
internal fun doitEnregistrerInstantaneNatif(
    etat: EditorUiState,
    instantane: InstantaneEditeurNatif,
): Boolean = etat.modifie &&
    etat.enregistrable &&
    etat.revision == instantane.revision

/**
 * Éditeur d'une note.
 *
 * Deux règles à ne pas perdre de vue :
 *
 *  - **`WriteNote` n'échoue jamais faute de réseau.** L'écriture va dans le
 *    cache local et la file persistée. Aucun message d'erreur réseau ne doit
 *    apparaître à l'enregistrement, il n'y a rien à signaler.
 *  - **Les bornes de sélection ne subissent aucune conversion.** `EditText` et
 *    Go comptent tous les deux en unités de code UTF-16 ; convertir en octets
 *    déplacerait le curseur dès la première lettre accentuée.
 */
class EditorViewModel(
    private val chemin: String,
    private val repository: OCnotesRepository,
    private val syncScheduler: SyncScheduler,
    private val applicationScope: CoroutineScope,
    garderEcranAllumeLecture: Boolean = false,
    garderEcranAllumeEdition: Boolean = false,
) : ViewModel() {

    private val nom = chemin.substringAfterLast('/')

    // La frontière de formats vit dans Go. La poser avant le chargement évite
    // qu'un .docx passe par ReadNote, où sa chaîne binaire serait abîmée avant
    // d'atteindre RenderFileJSON.
    private val documentBureautique = repository.isDocument(nom)

    // Le format se demande à Go, et dès la construction : la question se pose
    // avant qu'il y ait le moindre bloc à regarder, ne serait-ce que pour un
    // fichier vide. C'est aussi ce qui évite de recopier la liste des
    // extensions ici, où elle divergerait au premier format ajouté.
    private val _uiState = MutableStateFlow(
        EditorUiState(
            chemin = chemin,
            texteBrut = !documentBureautique && repository.isPlainText(nom),
            documentBureautique = documentBureautique,
            garderEcranAllumeLecture = garderEcranAllumeLecture,
            garderEcranAllumeEdition = garderEcranAllumeEdition,
            // Lu une fois à l'ouverture, comme documentBureautique : le mode
            // ne change pas pendant qu'une note est en train de s'éditer.
            modeLocal = repository.mode.value == AppMode.LOCAL,
        ),
    )
    val uiState: StateFlow<EditorUiState> = _uiState.asStateFlow()

    private var enregistrement: Job? = null
    private var revisionNativeDeSortie: Long? = null
    private var instantaneNatifConserve: InstantaneEditeurNatif? = null

    /**
     * Données en ligne retirées du texte au chargement.
     *
     * Elles vivent ici plutôt que dans l'état : ce sont plusieurs dizaines de
     * milliers de caractères, que rien n'a à recomposer. [ecrire] les remet en
     * place avant chaque écriture.
     */
    private var images: List<String> = emptyList()

    init {
        viewModelScope.launch {
            try {
                if (documentBureautique) {
                    // Le ZIP est lu et analysé côté Go : ni le binaire ni un
                    // champ de saisie ne traversent cette branche.
                    val blocs = repository.renderFile(chemin)
                    val titre = repository.titleOf(nom, "")
                    _uiState.update {
                        it.copy(
                            chargement = false,
                            charge = true,
                            titre = titre,
                            modifiable = false,
                            apercu = true,
                            blocs = blocs,
                        )
                    }
                } else {
                    val contenu = repository.readNote(chemin)

                    // Les images en ligne sortent du texte avant qu'il n'atteigne
                    // le champ de saisie, et n'y reviennent qu'à l'écriture. Sans
                    // cette étape, une note contenant une photo insérée depuis
                    // l'interface web fait tuer l'application par le système.
                    val prepare = repository.prepareEdit(nom, contenu)
                    images = prepare.images

                    // `update` prend une lambda non suspendue : tout appel à la
                    // façade se fait avant, jamais dedans.
                    val titre = repository.titleOf(nom, prepare.text)
                    val blocs = if (prepare.editable) {
                        emptyList()
                    } else {
                        repository.renderNote(nom, prepare.text)
                    }
                    _uiState.update {
                        it.copy(
                            chargement = false,
                            charge = true,
                            document = prepare.text,
                            titre = titre,
                            modifiable = prepare.editable,
                            // Une note inaffichable en saisie s'ouvre directement
                            // en lecture : c'est le seul mode qui tienne.
                            apercu = !prepare.editable,
                            blocs = blocs,
                        )
                    }
                }
            } catch (e: OCnotesException) {
                _uiState.update { it.copy(chargement = false, erreur = e.texte()) }
            }
        }

        // La barre d'outils est construite à partir de la liste renvoyée par
        // Go : ajouter une action côté cœur suffit à la faire apparaître ici.
        viewModelScope.launch {
            val actions = runCatching { repository.formatActions() }.getOrDefault(emptyList())
            _uiState.update { it.copy(actions = actions) }
        }
    }

    /** Signal léger du TextWatcher : aucun texte complet ne traverse ici. */
    fun signalerMutationNative(revision: Long) {
        _uiState.update { etat ->
            if (!etat.enregistrable) etat else etat.copy(modifie = true, revision = revision)
        }
    }

    /**
     * Dernière photographie immuable prise à une frontière de la session.
     *
     * Le ViewModel survit à une recréation d'activité, contrairement au
     * `remember` de l'écran. Il ne retient jamais le champ Android ni son
     * contexte : seulement la String, la sélection et le défilement nécessaires
     * pour reconstruire exactement la surface après une rotation.
     */
    fun instantaneNatifConserve(): InstantaneEditeurNatif? = instantaneNatifConserve

    private fun conserverInstantaneNatif(instantane: InstantaneEditeurNatif) {
        if (revisionNativeToujoursCourante(instantane.revision, _uiState.value.revision)) {
            instantaneNatifConserve = instantane
        }
    }

    /**
     * Reçoit l'unique String immuable photographiée par la composition.
     *
     * [survivreEcran] réserve la portée applicative au retour et au détachement ;
     * l'expiration normale reste annulable avec le ViewModel.
     */
    fun enregistrerInstantaneNatif(
        instantane: InstantaneEditeurNatif,
        survivreEcran: Boolean,
    ) {
        conserverInstantaneNatif(instantane)
        val etat = _uiState.value
        if (!doitEnregistrerInstantaneNatif(etat, instantane)) return
        // Le retour et le détachement portent la même photo à la même révision :
        // la seconde écriture pérenne n'a rien à faire. Ce court-circuit précède
        // `cancel()`, sinon la première serait tuée sans jamais être relancée.
        if (survivreEcran && revisionNativeDeSortie == instantane.revision) return
        if (survivreEcran) revisionNativeDeSortie = instantane.revision

        enregistrement?.cancel()
        val portee = if (survivreEcran) applicationScope else viewModelScope
        val travail = portee.launch {
            ecrire(instantane.texte, instantane.revision)
            syncScheduler.syncAfterLocalChange()
        }
        if (!survivreEcran) enregistrement = travail
    }

    data class FormatNatifApplique(
        val revisionSource: Long,
        val remplacement: RemplacementNatif,
        val selection: SelectionEditeurNatif,
    )

    /**
     * Applique une action de mise en forme à l'instantané complet, sans jamais
     * conserver l'Editable.
     *
     * Le cœur du contrat : les bornes de sélection partent telles quelles, et
     * celles renvoyées par Go sont réappliquées telles quelles. Android et Go
     * comptent tous deux en unités UTF-16 : pas de conversion, dans aucun sens.
     */
    fun appliquer(
        action: FormatAction,
        instantane: InstantaneEditeurNatif,
        onAppliquer: (FormatNatifApplique) -> Unit,
    ) {
        viewModelScope.launch {
            try {
                val apres = repository.applyFormat(
                    text = instantane.texte,
                    start = instantane.selection.debut,
                    end = instantane.selection.fin,
                    action = action,
                )
                if (!revisionNativeToujoursCourante(instantane.revision, _uiState.value.revision)) {
                    return@launch
                }
                // Le diff parcourt deux textes complets : hors du thread
                // principal, comme le JSON qui les a transportés.
                val remplacement = withContext(Dispatchers.Default) {
                    calculerRemplacementNatif(instantane.texte, apres.text)
                }
                if (!revisionNativeToujoursCourante(instantane.revision, _uiState.value.revision)) {
                    return@launch
                }
                onAppliquer(
                    FormatNatifApplique(
                        revisionSource = instantane.revision,
                        remplacement = remplacement,
                        selection = SelectionEditeurNatif(apres.start, apres.end),
                    ),
                )
            } catch (e: OCnotesException) {
                // Une action inconnue est un bug de version, pas une panne :
                // on le dit sans dramatiser et sans toucher au texte.
                _uiState.update { it.copy(erreur = e.texte()) }
            }
        }
    }

    /**
     * Bascule entre saisie et aperçu.
     *
     * Le rendu part du **texte affiché**, pas du fichier : l'aperçu montre donc
     * ce que l'utilisateur vient de taper, avant même que l'enregistrement
     * différé se déclenche. C'est aussi pourquoi il est refait à chaque
     * bascule plutôt que gardé en cache.
     *
     * `renderNote` ne touche ni réseau ni disque : l'aperçu s'ouvre hors
     * connexion comme le reste.
     */
    fun basculerApercu(instantane: InstantaneEditeurNatif?) {
        // Une note non modifiable n'a pas d'autre mode : la bascule ne mène
        // nulle part, et proposer un retour vers la saisie serait un piège.
        if (!_uiState.value.modifiable) return
        if (_uiState.value.apercu) {
            _uiState.update { it.copy(apercu = false) }
            return
        }
        if (instantane == null || instantane.revision != _uiState.value.revision) return
        conserverInstantaneNatif(instantane)

        viewModelScope.launch {
            try {
                val blocs = repository.renderNote(nom, instantane.texte)
                _uiState.update { courant ->
                    if (!revisionNativeToujoursCourante(instantane.revision, courant.revision)) {
                        courant
                    } else {
                        courant.copy(
                            document = instantane.texte,
                            apercu = true,
                            blocs = blocs,
                        )
                    }
                }
            } catch (e: OCnotesException) {
                _uiState.update { it.copy(erreur = e.texte()) }
            }
        }
    }

    private suspend fun ecrire(contenu: String, revision: Long) {
        // Dernière garde avant le cache, et la seule qui compte : on n'écrit
        // que ce qu'on a su lire. Une note en lecture seule serait écrasée par
        // sa version allégée ; une note pas encore — ou jamais — chargée le
        // serait par une chaîne vide.
        if (!_uiState.value.enregistrable) return

        try {
            // La restitution n'est pas une commodité : sans elle, c'est le
            // texte à jetons qui partirait sur le serveur, et l'image serait
            // perdue dans la vraie note, en silence.
            repository.writeNote(chemin, repository.restoreImages(contenu, images))
            _uiState.update {
                if (it.revision == revision) it.copy(modifie = false) else it
            }
        } catch (e: OCnotesException) {
            // Le réseau n'entre pas en jeu ici. Ce qui reste — un cache
            // illisible, un disque plein — mérite d'être dit.
            _uiState.update { it.copy(erreur = e.texte()) }
        }
    }

    fun erreurConsommee() = _uiState.update { it.copy(erreur = null) }

    override fun onCleared() {
        super.onCleared()
        // Filet de sécurité : l'écran peut disparaître autrement que par le
        // bouton retour (processus recyclé, navigation profonde). Le champ est
        // déjà détaché par `onRelease` : le dernier instantané conservé est
        // tout ce qu'il reste à écrire. Le garde de `revisionNativeDeSortie`
        // évite le doublon quand `quitter` vient de le faire ; l'écriture part
        // dans `applicationScope`, hors du scope du ViewModel déjà annulé.
        instantaneNatifConserve?.let {
            enregistrerInstantaneNatif(it, survivreEcran = true)
        }
    }

    companion object {
        fun factory(container: AppContainer, chemin: String): ViewModelProvider.Factory =
            viewModelFactory {
                initializer {
                    EditorViewModel(
                        chemin = chemin,
                        repository = container.repository,
                        syncScheduler = container.syncScheduler,
                        applicationScope = container.applicationScope,
                        garderEcranAllumeLecture = container.preferencesAffichage.garderEcranAllumeLecture.value,
                        garderEcranAllumeEdition = container.preferencesAffichage.garderEcranAllumeEdition.value,
                    )
                }
            }
    }
}
