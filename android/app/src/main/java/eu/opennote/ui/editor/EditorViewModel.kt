package eu.opennote.ui.editor

import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import eu.opennote.AppContainer
import eu.opennote.data.FormatAction
import eu.opennote.data.NoteBlockDto
import eu.opennote.data.OpenNoteException
import eu.opennote.data.OpenNoteRepository
import eu.opennote.ui.common.Texte
import eu.opennote.ui.common.texte
import eu.opennote.sync.SyncScheduler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class EditorUiState(
    val chemin: String = "",

    /**
     * Le texte **complet** de la note, et la seule source de vérité.
     *
     * Les sections n'en sont qu'une vue : des couples de bornes. C'est ce qui
     * permet au chemin d'enregistrement de ne pas changer d'un iota — on écrit
     * ce document-là, comme avant — et donc à tout ce qui protège déjà les
     * données de continuer à s'appliquer.
     *
     * Ne jamais le reconstruire en concaténant des sections gardées à part :
     * la première divergence entre les deux représentations serait invisible et
     * partirait sur le serveur.
     */
    val document: String = "",

    /**
     * Contenu **et** sélection de la section en cours d'édition — pas du
     * document entier.
     *
     * `TextFieldValue` vit dans le ViewModel parce que la barre d'outils doit
     * pouvoir imposer une nouvelle sélection après une mise en forme : c'est
     * le seul moyen de reposer le curseur exactement là où Go l'a calculé. Ses
     * bornes sont donc relatives à la section, et `ApplyFormat` reçoit le texte
     * de la section — lui passer le document entier recopierait 295 ko à chaque
     * appui sur « gras ».
     */
    val valeur: TextFieldValue = TextFieldValue(),

    /** Vues exactes de [document]. Une seule devient mutable — voir [focus]. */
    val tranches: List<TrancheEditeur> = emptyList(),

    /**
     * Indice de la section en saisie, ou -1 quand aucune ne l'est.
     *
     * **L'invariant du chantier** : au plus un `TextField` composé à la fois.
     * En laisser deux — « pour éviter un clignotement » — reconstruirait la
     * chose lente : le coût de dessin d'un champ suit le nombre de lignes qu'il
     * porte, à 0,14–0,24 ms la ligne, et le budget d'une image est de 16 ms.
     */
    val focus: Int = -1,

    /** Change à chaque demande de focus, même si l'indice reste identique. */
    val activation: Long = 0,

    /** Version du contenu, utilisée pour écarter un résultat asynchrone périmé. */
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
) {
    /**
     * Vrai quand [valeur] peut être écrit dans la note sans risquer de la
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

/** Le seul contenu autorisé à partir vers l'aperçu, le cache ou le serveur. */
internal fun materialiser(etat: EditorUiState): String = materialiserDocument(
    document = etat.document,
    active = etat.tranches.getOrNull(etat.focus),
    brouillon = etat.valeur.text,
)

/** Transition pure : committe l'ancien brouillon puis ouvre l'offset touché. */
internal fun activerFenetre(etat: EditorUiState, offsetAvantCommit: Int): EditorUiState {
    val ancienne = etat.tranches.getOrNull(etat.focus)
    val delta = if (ancienne == null) {
        0
    } else {
        etat.valeur.text.length - (ancienne.fin - ancienne.debut)
    }
    val offsetApresCommit = when {
        ancienne == null -> offsetAvantCommit
        offsetAvantCommit >= ancienne.fin -> offsetAvantCommit + delta
        else -> offsetAvantCommit
    }

    val document = materialiser(etat)
    val montage = monterFenetre(document, offsetApresCommit)
    val active = montage.tranches[montage.focus]

    return etat.copy(
        document = document,
        tranches = montage.tranches,
        focus = montage.focus,
        valeur = TextFieldValue(
            text = active.texteDe(document),
            selection = TextRange(montage.selectionDebut, montage.selectionFin),
        ),
        activation = etat.activation + 1,
    )
}

/** Transition pure : change le brouillon et remonte rarement la fenêtre. */
internal fun modifierFenetre(etat: EditorUiState, nouvelle: TextFieldValue): EditorUiState {
    if (etat.focus !in etat.tranches.indices) return etat
    if (nouvelle.text == etat.valeur.text) {
        val selectionSeulement = etat.copy(valeur = nouvelle)
        return if (nouvelle.composition == null && doitReequilibrer(nouvelle.text)) {
            reequilibrerFenetre(selectionSeulement)
        } else {
            selectionSeulement
        }
    }

    val modifie = etat.copy(
        valeur = nouvelle,
        modifie = true,
        revision = etat.revision + 1,
    )
    return if (nouvelle.composition == null && doitReequilibrer(nouvelle.text)) {
        reequilibrerFenetre(modifie)
    } else {
        modifie
    }
}

private fun reequilibrerFenetre(etat: EditorUiState): EditorUiState {
    val active = etat.tranches.getOrNull(etat.focus) ?: return etat
    val document = materialiser(etat)
    val debutGlobal = active.debut + etat.valeur.selection.start
    val finGlobal = active.debut + etat.valeur.selection.end
    val montage = monterFenetre(document, debutGlobal, finGlobal)
    val nouvelleActive = montage.tranches[montage.focus]

    return etat.copy(
        document = document,
        tranches = montage.tranches,
        focus = montage.focus,
        valeur = TextFieldValue(
            text = nouvelleActive.texteDe(document),
            selection = TextRange(montage.selectionDebut, montage.selectionFin),
        ),
        activation = etat.activation + 1,
    )
}

/**
 * Éditeur d'une note.
 *
 * Deux règles à ne pas perdre de vue :
 *
 *  - **`WriteNote` n'échoue jamais faute de réseau.** L'écriture va dans le
 *    cache local et la file persistée. Aucun message d'erreur réseau ne doit
 *    apparaître à l'enregistrement, il n'y a rien à signaler.
 *  - **Les bornes de sélection ne subissent aucune conversion.** Compose et Go
 *    comptent tous les deux en unités de code UTF-16 ; convertir en octets
 *    déplacerait le curseur dès la première lettre accentuée.
 */
class EditorViewModel(
    private val chemin: String,
    private val repository: OpenNoteRepository,
    private val syncScheduler: SyncScheduler,
    private val applicationScope: CoroutineScope,
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
        ),
    )
    val uiState: StateFlow<EditorUiState> = _uiState.asStateFlow()

    private var enregistrement: Job? = null

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
                    val tranches = if (prepare.editable) {
                        decouperDocument(prepare.text)
                    } else {
                        emptyList()
                    }
                    val focus = if (prepare.editable && prepare.text.isEmpty()) 0 else -1

                    _uiState.update {
                        it.copy(
                            chargement = false,
                            charge = true,
                            document = prepare.text,
                            tranches = tranches,
                            focus = focus,
                            valeur = TextFieldValue(),
                            titre = titre,
                            modifiable = prepare.editable,
                            // Une note inaffichable en saisie s'ouvre directement
                            // en lecture : c'est le seul mode qui tienne.
                            apercu = !prepare.editable,
                            blocs = blocs,
                        )
                    }
                }
            } catch (e: OpenNoteException) {
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

    /** Active une fenêtre glissante à l'offset touché dans l'instantané. */
    fun activer(offsetAvantCommit: Int) {
        _uiState.value = activerFenetre(_uiState.value, offsetAvantCommit)
    }

    /**
     * Saisie de l'utilisateur.
     *
     * Un déplacement du curseur ne relance pas l'enregistrement. La fenêtre
     * n'est remontée qu'après dépassement de sa borne dure, jamais à chaque
     * caractère. Une composition IME en cours n'est pas interrompue.
     */
    fun onValeurChangee(nouvelle: TextFieldValue) {
        val etat = _uiState.value
        val texteChange = etat.focus in etat.tranches.indices && nouvelle.text != etat.valeur.text
        _uiState.value = modifierFenetre(etat, nouvelle)
        if (texteChange) planifierEnregistrement()
    }

    /**
     * Applique une action de mise en forme.
     *
     * Le cœur du contrat : `selection.start` et `selection.end` partent tels
     * quels, et la sélection renvoyée est réappliquée telle quelle. Pas de
     * conversion, dans aucun sens.
     */
    fun appliquer(action: FormatAction) {
        val etatAvant = _uiState.value
        if (etatAvant.focus !in etatAvant.tranches.indices) return
        val avant = etatAvant.valeur

        viewModelScope.launch {
            try {
                val apres = repository.applyFormat(
                    text = avant.text,
                    start = avant.selection.start,
                    end = avant.selection.end,
                    action = action,
                )
                val courant = _uiState.value
                if (
                    courant.revision != etatAvant.revision ||
                    courant.activation != etatAvant.activation ||
                    courant.focus != etatAvant.focus ||
                    courant.valeur != avant
                ) {
                    return@launch
                }

                onValeurChangee(
                    TextFieldValue(
                        text = apres.text,
                        selection = TextRange(apres.start, apres.end),
                    ),
                )
            } catch (e: OpenNoteException) {
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
    fun basculerApercu() {
        // Une note non modifiable n'a pas d'autre mode : la bascule ne mène
        // nulle part, et proposer un retour vers la saisie serait un piège.
        if (!_uiState.value.modifiable) return

        if (_uiState.value.apercu) {
            _uiState.update { it.copy(apercu = false) }
            return
        }

        val etatAvant = _uiState.value
        val texte = materialiser(etatAvant)
        viewModelScope.launch {
            try {
                val blocs = repository.renderNote(nom, texte)
                _uiState.update { courant ->
                    if (courant.revision != etatAvant.revision) {
                        courant
                    } else {
                        courant.copy(
                            document = texte,
                            tranches = decouperDocument(texte),
                            focus = -1,
                            valeur = TextFieldValue(),
                            apercu = true,
                            blocs = blocs,
                        )
                    }
                }
            } catch (e: OpenNoteException) {
                _uiState.update { it.copy(erreur = e.texte()) }
            }
        }
    }

    /**
     * Enregistre après un temps de calme.
     *
     * Le cache absorbe déjà les écritures répétées — la file n'en garde qu'une
     * par chemin — mais traverser la frontière gomobile à chaque frappe reste
     * du gaspillage. La synchronisation, elle, a son propre anti-rebond dans
     * [SyncScheduler].
     */
    private fun planifierEnregistrement() {
        _uiState.update { it.copy(modifie = true) }

        enregistrement?.cancel()
        enregistrement = viewModelScope.launch {
            delay(DELAI_ENREGISTREMENT_MS)
            val etat = _uiState.value
            ecrire(materialiser(etat), etat.revision)
            syncScheduler.syncAfterLocalChange()
        }
    }

    /**
     * Enregistrement immédiat, sur geste explicite ou sortie de l'écran.
     *
     * La décision d'écrire est prise ici, et nulle part ailleurs : les deux
     * appelants — le retour arrière et [onCleared] — n'ont pas à la reprendre
     * chacun de leur côté, où ils divergeraient.
     */
    fun enregistrerMaintenant() {
        enregistrement?.cancel()

        // Une note ouverte, lue et refermée ne doit laisser aucune trace : ni
        // écriture, ni réveil de la synchronisation.
        val etat = _uiState.value
        if (!etat.modifie) return

        val contenu = materialiser(etat)
        val revision = etat.revision

        // La portée applicative, pas `viewModelScope` : au retour arrière, le
        // ViewModel est détruit avant que la coroutine ait pu écrire.
        applicationScope.launch {
            ecrire(contenu, revision)
            syncScheduler.syncAfterLocalChange()
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
        } catch (e: OpenNoteException) {
            // Le réseau n'entre pas en jeu ici. Ce qui reste — un cache
            // illisible, un disque plein — mérite d'être dit.
            _uiState.update { it.copy(erreur = e.texte()) }
        }
    }

    fun erreurConsommee() = _uiState.update { it.copy(erreur = null) }

    override fun onCleared() {
        super.onCleared()
        // Filet de sécurité : l'écran peut disparaître autrement que par le
        // bouton retour (processus recyclé, navigation profonde).
        enregistrerMaintenant()
    }

    companion object {
        private const val DELAI_ENREGISTREMENT_MS = 700L

        fun factory(container: AppContainer, chemin: String): ViewModelProvider.Factory =
            viewModelFactory {
                initializer {
                    EditorViewModel(
                        chemin = chemin,
                        repository = container.repository,
                        syncScheduler = container.syncScheduler,
                        applicationScope = container.applicationScope,
                    )
                }
            }
    }
}
