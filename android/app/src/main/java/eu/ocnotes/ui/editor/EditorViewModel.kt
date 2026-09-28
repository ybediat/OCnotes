package eu.ocnotes.ui.editor

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import eu.ocnotes.AppContainer
import eu.ocnotes.R
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
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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

    /**
     * Faux quand le fichier ne peut pas passer par un champ de saisie. Il
     * s'ouvre alors en aperçu, sans retour possible vers la saisie ;
     * [raisonLectureSeule] dit pourquoi.
     */
    val modifiable: Boolean = true,

    /** Pourquoi [modifiable] est faux ; sans objet sinon. */
    val raisonLectureSeule: RaisonLectureSeule = RaisonLectureSeule.MOT_TROP_LONG,

    /**
     * Encodage du fichier quand ce n'est pas de l'UTF-8 — « windows-1252 »,
     * « UTF-16LE »… — et `null` sinon.
     *
     * Affiché sous le titre : c'est ce qui explique d'avance qu'un emoji soit
     * refusé dans un fichier Windows-1252. Décide aussi du contrôle fait avant
     * de quitter, inutile en UTF-8, qui écrit tout.
     */
    val encodage: String? = null,

    /**
     * L'encodage dans lequel une note ouverte en aperçu pour [RaisonLectureSeule.ENCODAGE]
     * peut s'ouvrir quand même, sur un geste explicite ; `null` sinon.
     *
     * La détection doute parfois à tort — une note courte, trois accents
     * d'affilée. C'est l'utilisateur qui sait ce que contient son fichier.
     */
    val encodageForcable: String? = null,

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
    /** Lu une fois à l'ouverture, comme les réglages d'écran allumé. */
    val saisieAutomatique: Boolean = true,
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

/**
 * Ce qui tient un fichier hors du champ de saisie. Le bandeau de l'aperçu dit
 * la cause plutôt que la mécanique.
 */
enum class RaisonLectureSeule {
    /** Un mot si long qu'un champ de saisie ne survivrait pas à sa mise en page. */
    MOT_TROP_LONG,

    /** Un document Office, lu et analysé par Go. */
    DOCUMENT,

    /** Un fichier texte de configuration ou de données, que l'application n'écrit pas. */
    FORMAT_TEXTE,

    /** Le serveur autorise la lecture du fichier, mais pas sa modification. */
    AUTORISATION,

    /**
     * Un contenu qui n'est pas de l'UTF-8 valide. Passé par une chaîne, il
     * perdrait ses accents au premier enregistrement.
     */
    ENCODAGE,
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
    saisieAutomatique: Boolean = true,
) : ViewModel() {

    private val nom = chemin.substringAfterLast('/')

    // La frontière de formats vit dans Go. La poser avant le chargement évite
    // qu'un .docx passe par ReadNote, où sa chaîne binaire serait abîmée avant
    // d'atteindre RenderFileJSON — et qu'un .yaml atteigne un champ de saisie.
    private val lectureSeuleFormat: RaisonLectureSeule? = when {
        repository.isDocument(nom) -> RaisonLectureSeule.DOCUMENT
        repository.isReadOnly(nom) -> RaisonLectureSeule.FORMAT_TEXTE
        else -> null
    }

    // Le format se demande à Go, et dès la construction : la question se pose
    // avant qu'il y ait le moindre bloc à regarder, ne serait-ce que pour un
    // fichier vide. C'est aussi ce qui évite de recopier la liste des
    // extensions ici, où elle divergerait au premier format ajouté.
    private val _uiState = MutableStateFlow(
        EditorUiState(
            chemin = chemin,
            texteBrut = lectureSeuleFormat != RaisonLectureSeule.DOCUMENT && repository.isPlainText(nom),
            garderEcranAllumeLecture = garderEcranAllumeLecture,
            garderEcranAllumeEdition = garderEcranAllumeEdition,
            saisieAutomatique = saisieAutomatique,
            // Lu une fois à l'ouverture, comme le format : le mode
            // ne change pas pendant qu'une note est en train de s'éditer.
            modeLocal = repository.mode.value == AppMode.LOCAL,
        ),
    )
    val uiState: StateFlow<EditorUiState> = _uiState.asStateFlow()

    private var enregistrement: Job? = null
    private var revisionNativeDeSortie: Long? = null
    private var instantaneNatifConserve: InstantaneEditeurNatif? = null

    /** Dernière écriture lancée en portée applicative, que la fermeture attend. */
    private var ecritureDeSortie: Job? = null

    /**
     * Session d'édition côté Go, qui garde les images retirées du texte.
     *
     * Vide quand la note n'en a pas. Les images ne traversent plus la
     * frontière : [ecrire] passe cet identifiant, Go restitue et écrit.
     */
    private var sessionEdition: String = ""

    /**
     * Sérialise les écritures et la fermeture de session.
     *
     * Une écriture automatique annulée continue de s'exécuter côté Go : sans ce
     * verrou, elle pouvait atterrir **après** l'écriture de sortie, plus
     * récente, et la recouvrir. Le verrou et [revisionEcrite] rangent les
     * écritures dans l'ordre des révisions.
     */
    private val verrouEcriture = Mutex()
    private var revisionEcrite = Long.MIN_VALUE

    init {
        viewModelScope.launch { charger() }

        // La barre d'outils est construite à partir de la liste renvoyée par
        // Go : ajouter une action côté cœur suffit à la faire apparaître ici.
        viewModelScope.launch {
            val actions = runCatching { repository.formatActions() }.getOrDefault(emptyList())
            _uiState.update { it.copy(actions = actions) }
        }
    }

    /**
     * Lit la note et prépare l'écran : en saisie si elle peut l'être, en aperçu
     * sinon. Relancé par [ouvrirQuandMeme] une fois l'encodage choisi.
     */
    private suspend fun charger() {
        try {
            val lectureSeule = lectureSeuleFormat ?: repository.capabilities(chemin)
                .takeUnless { it.canWrite }
                ?.let { RaisonLectureSeule.AUTORISATION }
            if (lectureSeule != null) {
                ouvrirEnApercu(lectureSeule)
            } else {
                val contenu = try {
                    repository.readNote(chemin)
                } catch (e: OCnotesException) {
                    // Go refuse de livrer en saisie un contenu dont il ne
                    // reconnaît pas l'encodage avec certitude : le réécrire
                    // pourrait l'abîmer. L'aperçu, lu en octets côté Go, n'a
                    // pas ce risque, et le bandeau propose d'ouvrir quand même.
                    if (e.code != CODE_NON_UTF8) throw e
                    ouvrirEnApercu(RaisonLectureSeule.ENCODAGE)
                    return
                }

                // Les images en ligne sortent du texte avant qu'il n'atteigne
                // le champ de saisie, et n'y reviennent qu'à l'écriture. Sans
                // cette étape, une note contenant une photo insérée depuis
                // l'interface web fait tuer l'application par le système.
                // Les images restent côté Go, sous `sessionEdition`.
                val prepare = repository.openEdit(nom, contenu)
                sessionEdition = prepare.session
                val encodage = repository.noteEncoding(chemin).takeUnless { it == ENCODAGE_UTF8 }

                // `update` prend une lambda non suspendue : tout appel à la
                // façade se fait avant, jamais dedans.
                val titre = prepare.title
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
                        raisonLectureSeule = RaisonLectureSeule.MOT_TROP_LONG,
                        encodage = encodage,
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

    /**
     * Ouvre en saisie, dans l'encodage deviné, une note que la détection
     * n'osait pas affirmer. Le choix est retenu par le cache pour ce contenu :
     * il vaut aux ouvertures suivantes, jusqu'à ce que le fichier change
     * ailleurs.
     */
    fun ouvrirQuandMeme() {
        viewModelScope.launch {
            try {
                repository.forceEncoding(chemin)
            } catch (e: OCnotesException) {
                _uiState.update { it.copy(erreur = e.texte()) }
                return@launch
            }
            _uiState.update { it.copy(chargement = true, charge = false, encodageForcable = null) }
            charger()
        }
    }

    /**
     * Ouvre le fichier en aperçu seul, lu et rendu côté Go.
     *
     * Ni le contenu ni un champ de saisie ne traversent cette branche : seuls
     * les blocs d'affichage passent la frontière. [charge] reste vrai pour que
     * l'écran s'affiche, et [modifiable] faux ferme toute écriture — voir
     * [EditorUiState.enregistrable].
     */
    private suspend fun ouvrirEnApercu(raison: RaisonLectureSeule) {
        val blocs = repository.renderFile(chemin)
        val titre = repository.titleOf(nom, "")
        val encodageForcable = if (raison == RaisonLectureSeule.ENCODAGE) {
            repository.forcibleEncoding(chemin)
        } else {
            null
        }
        _uiState.update {
            it.copy(
                chargement = false,
                charge = true,
                titre = titre,
                modifiable = false,
                raisonLectureSeule = raison,
                apercu = true,
                blocs = blocs,
                encodageForcable = encodageForcable,
            )
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
        if (survivreEcran) ecritureDeSortie = travail else enregistrement = travail
    }

    data class FormatNatifApplique(
        val revisionSource: Long,
        val remplacement: RemplacementNatif,
        val selection: SelectionEditeurNatif,
    )

    /**
     * Applique une action de mise en forme à une fenêtre de lignes, sans jamais
     * conserver l'Editable.
     *
     * Go ne reçoit que les lignes sélectionnées et une ligne de contexte de
     * chaque côté ; son résultat remplace cette fenêtre, et elle seule. Le
     * résultat est **identique** à celui d'une mise en forme du document entier
     * — `TestFenetreEquivautAuDocumentEntier` le prouve, voir
     * [fenetreMiseEnForme] pour la règle.
     *
     * Le cœur du contrat : les bornes de sélection partent telles quelles, et
     * celles renvoyées par Go sont réappliquées telles quelles, au décalage de
     * la fenêtre près. Android et Go comptent tous deux en unités UTF-16 : pas
     * de conversion, dans aucun sens.
     */
    fun appliquer(
        action: FormatAction,
        fenetre: FenetreNatif,
        onAppliquer: (FormatNatifApplique) -> Unit,
    ) {
        viewModelScope.launch {
            try {
                val apres = repository.applyFormat(
                    text = fenetre.texte,
                    start = fenetre.selection.debut - fenetre.debut,
                    end = fenetre.selection.fin - fenetre.debut,
                    action = action,
                )
                if (!revisionNativeToujoursCourante(fenetre.revision, _uiState.value.revision)) {
                    return@launch
                }
                // Une fenêtre reste petite, sauf sur une sélection de tout le
                // document : le diff reste hors du thread principal.
                val local = withContext(Dispatchers.Default) {
                    calculerRemplacementNatif(fenetre.texte, apres.text)
                }
                if (!revisionNativeToujoursCourante(fenetre.revision, _uiState.value.revision)) {
                    return@launch
                }
                onAppliquer(
                    FormatNatifApplique(
                        revisionSource = fenetre.revision,
                        remplacement = RemplacementNatif(
                            debut = local.debut + fenetre.debut,
                            fin = local.fin + fenetre.debut,
                            texte = local.texte,
                        ),
                        selection = SelectionEditeurNatif(
                            apres.start + fenetre.debut,
                            apres.end + fenetre.debut,
                        ),
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
        // Le champ survit à l'aperçu, masqué : aucun détachement n'enregistre
        // plus au passage, et l'enregistrement différé est suspendu pendant la
        // lecture. Sans cet appel, la frappe des 700 dernières millisecondes
        // attendrait le retour en saisie ou la sortie de l'écran.
        enregistrerInstantaneNatif(instantane, survivreEcran = false)

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

        verrouEcriture.withLock {
            // Une écriture plus ancienne arrivée en retard ne recouvre jamais
            // une plus récente.
            if (revision <= revisionEcrite) return
            try {
                // La restitution n'est pas une commodité : sans elle, c'est le
                // texte à jetons qui partirait sur le serveur, et l'image serait
                // perdue dans la vraie note, en silence. Go la fait à partir de
                // la session, et refuse d'écrire s'il ne la connaît pas.
                repository.writeEditedNote(sessionEdition, chemin, contenu)
                revisionEcrite = revision
                _uiState.update {
                    if (it.revision == revision) it.copy(modifie = false) else it
                }
            } catch (e: OCnotesException) {
                // Le réseau n'entre pas en jeu ici. Ce qui reste — un cache
                // illisible, un disque plein, un caractère que l'encodage du
                // fichier ne sait pas écrire — mérite d'être dit. La note reste
                // « modifiée » : rien n'est perdu, rien n'est écrit.
                val texte = if (e.code == CODE_NON_REPRESENTABLE) {
                    texteCaractereRefuse(contenu) ?: e.texte()
                } else {
                    e.texte()
                }
                _uiState.update { it.copy(erreur = texte) }
            }
        }
    }

    /**
     * Le message qui nomme le caractère que l'encodage du fichier refuse, ou
     * `null` si le texte n'en porte aucun.
     *
     * Go ne met pas ce caractère dans son code d'erreur, qui ne porte aucun
     * paramètre : on le lui redemande, et seulement après un refus.
     */
    private suspend fun texteCaractereRefuse(contenu: String): Texte? {
        val encodage = _uiState.value.encodage ?: return null
        val caractere = try {
            repository.unrepresentable(chemin, contenu)
        } catch (_: OCnotesException) {
            null
        } ?: return null
        return Texte.de(R.string.err_caractere_non_representable, caractere, encodage)
    }

    /** Vrai une fois la sortie accordée : un second appui ne la relance pas. */
    private var sortieAccordee = false

    /**
     * Enregistre puis quitte — sauf si le texte porte un caractère que
     * l'encodage du fichier ne sait pas écrire.
     *
     * La sortie n'attend pas son écriture, qui part dans `applicationScope` :
     * en UTF-8, elle ne peut pas échouer. Dans un fichier Windows-1252, un
     * emoji la ferait échouer **après** la fermeture de l'écran, et les
     * modifications non encore enregistrées seraient perdues sans un mot. Le
     * texte est donc vérifié d'abord, et l'utilisateur reste dans l'éditeur,
     * avec le message qui nomme le caractère.
     */
    fun quitter(instantane: InstantaneEditeurNatif?, sortir: () -> Unit) {
        if (sortieAccordee) return
        val etat = _uiState.value
        if (instantane == null || etat.encodage == null || !etat.modifie || !etat.enregistrable) {
            sortieAccordee = true
            instantane?.let { enregistrerInstantaneNatif(it, survivreEcran = true) }
            sortir()
            return
        }
        viewModelScope.launch {
            val refus = texteCaractereRefuse(instantane.texte)
            if (refus != null) {
                _uiState.update { it.copy(erreur = refus) }
                return@launch
            }
            if (sortieAccordee) return@launch
            sortieAccordee = true
            enregistrerInstantaneNatif(instantane, survivreEcran = true)
            sortir()
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

        // Les images ne sont libérées qu'après la dernière écriture : Go
        // refuserait toute écriture qui suit, pour ne pas écrire le texte à
        // jetons. `applicationScope` est multithread, l'ordre de lancement ne
        // suffit pas : on attend explicitement l'écriture de sortie.
        val session = sessionEdition
        if (session.isNotEmpty()) {
            val derniere = ecritureDeSortie
            applicationScope.launch {
                derniere?.join()
                verrouEcriture.withLock { runCatching { repository.closeEdit(session) } }
            }
        }
    }

    companion object {
        /** Code Go d'un contenu qui n'est pas de l'UTF-8 (`mobile.CodeNotUTF8`). */
        private const val CODE_NON_UTF8 = "NOT_UTF8"

        /** Code Go d'un caractère que l'encodage du fichier ne sait pas écrire. */
        private const val CODE_NON_REPRESENTABLE = "ENCODING_UNREPRESENTABLE"

        /** Nom Go de l'UTF-8 (`charset.UTF8`), le seul à ne rien refuser. */
        private const val ENCODAGE_UTF8 = "UTF-8"

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
                        saisieAutomatique = container.preferencesAffichage.saisieAutomatiqueEdition.value,
                    )
                }
            }
    }
}
