package eu.ocnotes.ui.editor

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.text.font.FontWeight
import eu.ocnotes.data.BlockKind
import kotlinx.coroutines.launch
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.TextDecrease
import androidx.compose.material.icons.filled.TextIncrease
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.ZoomIn
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.Alignment
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import eu.ocnotes.R
import eu.ocnotes.appContainer
import eu.ocnotes.data.PaliersLecture
import eu.ocnotes.ui.common.ChargementPleinEcran
import eu.ocnotes.ui.common.EtatVide
import eu.ocnotes.ui.common.resoudre
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest

/**
 * Éditeur plein écran.
 *
 * La saisie est un unique `EditText` Android portant toute la note préparée
 * (voir [EditeurNatif]). Son `Editable` est la source de vérité pendant la
 * frappe ; le ViewModel n'en reçoit que des instantanés, aux frontières qui en
 * ont besoin.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditorScreen(
    chemin: String,
    onRetour: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: EditorViewModel = viewModel(
        key = chemin,
        factory = EditorViewModel.factory(LocalContext.current.appContainer, chemin),
    ),
) {
    val etat by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val sessionNative = remember(chemin) { SessionEditeurNatif() }
    // `chargements` en clé : une note rechargée dans un autre encodage ne doit
    // pas retrouver la photo du champ d'avant, décodée autrement.
    var dernierInstantaneNatif by remember(chemin, viewModel, etat.chargements) {
        mutableStateOf(viewModel.instantaneNatifConserve())
    }
    val vueLocale = LocalView.current
    val garderEcranAllume = if (etat.apercu) {
        etat.garderEcranAllumeLecture
    } else {
        etat.garderEcranAllumeEdition
    }

    DisposableEffect(garderEcranAllume) {
        if (garderEcranAllume) {
            vueLocale.keepScreenOn = true
        }
        onDispose {
            if (garderEcranAllume) {
                vueLocale.keepScreenOn = false
            }
        }
    }

    // Fil d'Ariane du diagnostic : une mort de processus sur une note trop
    // lourde ne laisse aucune trace Kotlin, et ces deux mesures sont les
    // seules qui la relient à quelque chose. Réévaluées par paliers, pour ne
    // pas ajouter un appel système à chaque frappe.
    val rapporteur = LocalContext.current.appContainer.crashReporter
    LaunchedEffect(rapporteur, chemin, etat.document.length / PALIER_FIL_ARIANE) {
        rapporteur.noterEcran(
            ecran = ECRAN_EDITEUR,
            lignes = etat.document.count { it == '\n' } + 1,
            caracteres = etat.document.length,
        )
    }

    // Mémorisée pour que `EditeurNatif` reste « skippable » : recréée à chaque
    // frappe, cette lambda forcerait la recomposition de l'`AndroidView` natif
    // et le réglage des styles qu'elle porte, alors que le champ se suffit.
    val onDetachementNatif: (InstantaneEditeurNatif) -> Unit = remember(chemin, viewModel, etat.chargements) {
        { instantane ->
            dernierInstantaneNatif = instantane
            viewModel.enregistrerInstantaneNatif(instantane, survivreEcran = true)
        }
    }

    // Overlay d'attente tant que le champ natif n'a pas dessiné une fois. Sans
    // lui, le spinner de chargement disparaît puis l'UI gèle ~700 ms sur le
    // layout de la note : ça se lit comme un plantage. Aucune clé sur
    // `apercu` : le champ survit à l'aperçu, il ne redessine pas « une
    // première fois » au retour, et l'overlay ne se lèverait plus.
    var natifPret by remember(chemin, etat.chargements) { mutableStateOf(false) }
    val onPretNatif = remember(chemin, etat.chargements) { { natifPret = true } }

    // Rédigé hors du `LaunchedEffect` : une coroutine n'est pas un contexte
    // de composition, elle ne peut pas lire de ressource.
    val messageErreur = etat.erreur?.resoudre()

    LaunchedEffect(messageErreur) {
        messageErreur?.let {
            snackbar.showSnackbar(it)
            viewModel.erreurConsommee()
        }
    }

    // Une frappe native ne publie que sa révision. Après 700 ms de calme, et
    // seulement alors, l'Editable devient une String immuable à enregistrer.
    // La révision est suivie dans la coroutine, jamais lue par la composition :
    // une frappe ne recompose rien, `collectLatest` relance seulement le délai.
    //
    // Le délai court aussi en aperçu : le champ y survit, masqué, et une case
    // cochée l'y modifie. Plusieurs cases cochées d'affilée partent dans une
    // seule écriture ; sans lui, une liste cochée puis l'application tuée
    // serait une liste perdue.
    LaunchedEffect(viewModel, sessionNative) {
        viewModel.revisionNative.collectLatest {
            if (!viewModel.uiState.value.modifie) return@collectLatest
            delay(DELAI_ENREGISTREMENT_MS)
            sessionNative.instantane()?.let { instantane ->
                viewModel.enregistrerInstantaneNatif(instantane, survivreEcran = false)
            }
        }
    }

    // Le retour arrière enregistre avant de quitter. `WriteNote` écrit dans le
    // cache local : l'opération est immédiate et ne peut pas échouer faute de
    // réseau. Elle peut en revanche refuser un caractère que l'encodage du
    // fichier ne sait pas écrire : le ViewModel vérifie alors avant de sortir.
    val quitter = {
        viewModel.quitter(sessionNative.instantane() ?: dernierInstantaneNatif, onRetour)
    }
    BackHandler(onBack = quitter)

    // L'état de la liste de l'aperçu, levé ici pour que le plan sache ce
    // qu'elle montre et puisse la faire défiler. Une liste neuve à chaque
    // entrée en aperçu, comme quand l'aperçu la créait lui-même.
    val etatListeApercu = rememberSaveable(etat.apercu, etat.chargements, saver = LazyListState.Saver) {
        LazyListState()
    }
    val plan by viewModel.plan.collectAsStateWithLifecycle()
    val portee = rememberCoroutineScope()

    // Le plan se demande là où l'on est : en aperçu, les blocs affichés
    // suffisent ; en saisie, le texte du champ est rendu à la demande.
    val ouvrirPlan: () -> Unit = {
        if (viewModel.uiState.value.apercu) {
            viewModel.ouvrirPlanApercu(etatListeApercu.firstVisibleItemIndex)
        } else {
            sessionNative.repereVisible()?.let { repere ->
                viewModel.ouvrirPlanSaisie(sessionNative.instantane(), repere)
            }
        }
    }
    plan?.let { ouvert ->
        FeuillePlan(
            plan = ouvert,
            onFermer = viewModel::fermerPlan,
            onChoisir = { entree ->
                viewModel.fermerPlan()
                if (ouvert.enApercu) {
                    // Une case cochée ne change que l'état d'une tâche : les
                    // indices restent bons. Un bloc qui n'est plus un titre
                    // dit que l'aperçu a été rendu de nouveau : plan à refaire.
                    val blocs = viewModel.uiState.value.blocs
                    if (blocs.getOrNull(entree.indiceBloc)?.kind == BlockKind.TITRE) {
                        portee.launch { etatListeApercu.scrollToItem(indiceElement(blocs, entree.indiceBloc)) }
                    } else {
                        ouvrirPlan()
                    }
                } else {
                    val source = entree.source
                    if (source != null && !sessionNative.sauterVers(ouvert.revision, source)) {
                        // Le champ a bougé depuis le rendu : la position ne
                        // désigne plus le titre. On refait le plan.
                        ouvrirPlan()
                    }
                }
            },
        )
    }

    // Taille du texte de l'aperçu, commune à toutes les notes : lue
    // directement dans les préférences, comme la police dans `MainActivity`.
    val preferences = LocalContext.current.appContainer.preferencesAffichage
    val tailleLecture by preferences.tailleLecture.collectAsStateWithLifecycle()

    Scaffold(
        modifier = modifier,
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            text = etat.titre.ifBlank { chemin.substringAfterLast('/') },
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        // Un état, pas une alerte : « brouillon local » dit ce
                        // qui se passe sans laisser croire à une perte.
                        // L'encodage d'un fichier qui n'est pas en UTF-8 le
                        // rejoint sur la même ligne : il explique d'avance
                        // qu'un emoji y soit refusé.
                        val sousTitre = listOfNotNull(
                            stringResource(R.string.editeur_brouillon).takeIf { etat.brouillon },
                            etat.encodage?.let { stringResource(R.string.editeur_encodage, it) },
                        )
                        if (sousTitre.isNotEmpty()) {
                            Text(
                                text = sousTitre.joinToString(stringResource(R.string.separateur_sous_titre)),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                },
                navigationIcon = {
                    IconButton(onClick = quitter) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.action_retour),
                        )
                    }
                },
                actions = {
                    // La loupe ne règle que l'aperçu : elle n'apparaît qu'en
                    // lecture, notes en lecture seule comprises, et se place
                    // à gauche du crayon.
                    if (etat.apercu) {
                        MenuTailleLecture(
                            pourcent = tailleLecture,
                            onChoisir = preferences::definirTailleLecture,
                        )
                    }

                    // Rien à basculer sur une note en lecture seule : un
                    // bouton qui ne fait rien vaut moins que pas de bouton.
                    // Le menu d'encodage, lui, reste utile même en lecture
                    // seule : c'est justement là qu'un choix forcé peut
                    // laisser une note coincée, sans aucun champ pour en
                    // sortir autrement.
                    if (etat.modifiable) {
                        // Emplacement provisoire : ce geste rejoindra le menu
                        // latéral, où il sera nommé plutôt que dessiné.
                        IconButton(
                            onClick = { viewModel.basculerApercu(sessionNative.instantane()) },
                        ) {
                            if (etat.apercu) {
                                Icon(
                                    imageVector = Icons.Default.Edit,
                                    contentDescription = stringResource(R.string.apercu_quitter),
                                )
                            } else {
                                Icon(
                                    imageVector = Icons.Default.Visibility,
                                    contentDescription = stringResource(R.string.apercu_activer),
                                )
                            }
                        }
                    }

                    // Le plan : sur ce qui a des titres à montrer, donc pas sur
                    // un .txt, où un `#` n'est qu'un dièse. Le retour à la
                    // détection, seulement quand l'encodage a été choisi contre
                    // son avis : c'est l'annulation de « Ouvrir quand même ».
                    val planPossible = etat.charge && !etat.chargement && !etat.texteBrut
                    if (planPossible || etat.encodageForce) {
                        MenuEditeur(
                            onPlan = ouvrirPlan.takeIf { planPossible },
                            onRevenirALaDetection = {
                                viewModel.revenirALaDetection(
                                    sessionNative.instantane() ?: dernierInstantaneNatif,
                                )
                            }.takeIf { etat.encodageForce },
                        )
                    }
                },
            )
        },
    ) { paddings ->
        if (etat.chargement) {
            ChargementPleinEcran(Modifier.padding(paddings))
            return@Scaffold
        }

        // Le chargement a échoué : surtout pas de champ de saisie. Vide, il
        // laisserait croire à une note vide, et ce qu'on y taperait partirait
        // par-dessus un contenu qu'on n'a pas réussi à lire. L'erreur elle-même
        // est déjà passée par le snackbar.
        if (!etat.charge) {
            EtatVide(
                titre = stringResource(R.string.editeur_illisible_titre),
                // En mode local, il n'y a pas de serveur à attendre : la
                // formulation ne doit pas promettre une reconnexion qui
                // n'aura jamais lieu.
                detail = stringResource(
                    if (etat.modeLocal) {
                        R.string.editeur_illisible_detail_local
                    } else {
                        R.string.editeur_illisible_detail
                    },
                ),
                modifier = Modifier.padding(paddings),
            )
            return@Scaffold
        }

        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddings),
        ) {
            // Une note non modifiable n'a jamais de champ : c'est tout
            // l'objet de `modifiable`, un mot démesuré tuerait le processus.
            if (etat.modifiable) {
                ZoneSaisie(
                    etat = etat,
                    sessionNative = sessionNative,
                    restauration = dernierInstantaneNatif,
                    natifPret = natifPret,
                    onPretNatif = onPretNatif,
                    onDetachementNatif = onDetachementNatif,
                    viewModel = viewModel,
                    paddings = paddings,
                )
            }

            // L'aperçu se pose **par-dessus** la saisie au lieu de la
            // remplacer : le champ reste composé, masqué. Le reconstruire au
            // retour coûtait 1,4 s sur 285 ko et vidait la pile d'annulation.
            // Côte à côte, deux volets ne laisseraient de place à aucun des
            // deux sur un téléphone.
            if (etat.apercu) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(MaterialTheme.colorScheme.surface),
                ) {
                    if (!etat.modifiable) {
                        BandeauLectureSeule(
                            raison = etat.raisonLectureSeule,
                            encodageForcable = etat.encodageForcable,
                            onOuvrirQuandMeme = viewModel::ouvrirQuandMeme,
                        )
                    }
                    VueMarkdown(
                        blocs = etat.blocs,
                        modifier = Modifier.weight(1f),
                        echelle = tailleLecture / 100f,
                        etatListe = etatListeApercu,
                        // Pas de case cliquable sans champ où écrire : une note
                        // en lecture seule garde des cases décoratives.
                        onBasculerTache = if (etat.modifiable) {
                            { bloc ->
                                val position = bloc.source
                                val revision = position?.let {
                                    sessionNative.cocherCase(viewModel.uiState.value.revisionApercu, it)
                                }
                                if (position != null && revision != null) {
                                    viewModel.caseBasculee(position, revision)
                                } else {
                                    // Le rendu ne correspond plus au champ :
                                    // rien n'est écrit, on le refait.
                                    viewModel.rafraichirApercu(sessionNative.instantane())
                                }
                            }
                        } else {
                            null
                        },
                    )
                }
            }
        }
    }
}

/**
 * La loupe et son petit menu : A−, le pourcentage (qui ramène à 100 %), A+.
 *
 * Le menu reste ouvert d'un palier à l'autre, pour qu'on voie le texte changer
 * derrière lui et qu'on puisse enchaîner les appuis.
 */
@Composable
private fun MenuTailleLecture(
    pourcent: Int,
    onChoisir: (Int) -> Unit,
) {
    var ouvert by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { ouvert = true }) {
            Icon(
                imageVector = Icons.Default.ZoomIn,
                contentDescription = stringResource(R.string.apercu_taille_texte),
            )
        }
        DropdownMenu(expanded = ouvert, onDismissRequest = { ouvert = false }) {
            Row(
                modifier = Modifier.padding(horizontal = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(
                    onClick = { onChoisir(PaliersLecture.precedent(pourcent)) },
                    enabled = pourcent > PaliersLecture.valeurs.first(),
                ) {
                    Icon(
                        imageVector = Icons.Default.TextDecrease,
                        contentDescription = stringResource(R.string.apercu_taille_reduire),
                    )
                }
                // Le pourcentage sert aussi de bouton de retour à 100 % : le
                // lecteur d'écran annonce les deux, la taille et l'action.
                val normale = stringResource(R.string.apercu_taille_normale)
                TextButton(
                    onClick = { onChoisir(PaliersLecture.NORMAL) },
                    modifier = Modifier.semantics {
                        onClick(label = normale) {
                            onChoisir(PaliersLecture.NORMAL)
                            true
                        }
                    },
                ) {
                    Text(stringResource(R.string.apercu_taille_pourcent, pourcent))
                }
                IconButton(
                    onClick = { onChoisir(PaliersLecture.suivant(pourcent)) },
                    enabled = pourcent < PaliersLecture.valeurs.last(),
                ) {
                    Icon(
                        imageVector = Icons.Default.TextIncrease,
                        contentDescription = stringResource(R.string.apercu_taille_agrandir),
                    )
                }
            }
        }
    }
}

/**
 * Le champ natif, son overlay d'attente et la barre de mise en forme.
 *
 * Composé tant que la note est modifiable, **aperçu compris** : [EditeurNatif]
 * y est seulement masqué.
 */
@Composable
private fun ZoneSaisie(
    etat: EditorUiState,
    sessionNative: SessionEditeurNatif,
    restauration: InstantaneEditeurNatif?,
    natifPret: Boolean,
    onPretNatif: () -> Unit,
    onDetachementNatif: (InstantaneEditeurNatif) -> Unit,
    viewModel: EditorViewModel,
    paddings: PaddingValues,
) {
    // Mises en forme présentes sous le curseur, surlignées dans la barre.
    // `mutableStateOf` compare les ensembles : tant que le curseur reste dans
    // le même contexte, la frappe ne déclenche aucune recomposition.
    val actives = remember { mutableStateOf(emptySet<String>()) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .consumeWindowInsets(paddings)
            .imePadding(),
    ) {
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
        ) {
            EditeurNatif(
                texteInitial = restauration?.texte ?: etat.document,
                session = sessionNative,
                selectionInitiale = restauration?.selection
                    ?: SelectionEditeurNatif(0, 0),
                revisionInitiale = restauration?.revision ?: 0,
                defilementInitialX = restauration?.defilementX ?: 0,
                defilementInitialY = restauration?.defilementY ?: 0,
                masque = etat.apercu,
                saisieAutomatique = etat.saisieAutomatique,
                // Un `.txt` n'est pas interprété : y colorer un `#` ou un `*`
                // ferait croire à une mise en forme qui n'existera pas.
                colorationCode = !etat.texteBrut,
                // Même raison : dans un `.txt`, un `- ` n'ouvre aucune liste.
                suiteListes = !etat.texteBrut,
                indication = stringResource(R.string.editeur_saisie_vide),
                descriptionDefilementRapide = stringResource(
                    R.string.editeur_defilement_rapide,
                ),
                onMutation = viewModel::signalerMutationNative,
                onAvantDetachement = onDetachementNatif,
                onPret = onPretNatif,
                onSelection = { texte, debut, fin ->
                    if (!etat.texteBrut) actives.value = misesEnFormeActives(texte, debut, fin)
                },
                modifier = Modifier.fillMaxSize(),
            )

            // Le champ natif fige le thread principal le temps de sa mise
            // en page ; l'overlay reste opaque par-dessus jusqu'au premier
            // dessin, pour ne pas laisser voir un champ vide ni un gel nu.
            if (!natifPret) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(MaterialTheme.colorScheme.surface),
                    contentAlignment = Alignment.Center,
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        CircularProgressIndicator()
                        Text(
                            text = stringResource(R.string.editeur_ouverture_longue),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 16.dp),
                        )
                    }
                }
            }
        }

        // Rien à mettre en forme dans un .txt : les marqueurs y
        // resteraient des marqueurs, y compris à l'aperçu. Rien non plus
        // pendant l'aperçu, qui recouvre la barre.
        if (!etat.texteBrut && !etat.apercu) {
            FormatToolbar(
                actions = etat.actions,
                actives = { actives.value },
                onAction = { action ->
                    sessionNative.fenetre()?.let { fenetre ->
                        viewModel.appliquer(action, fenetre) { resultat ->
                            sessionNative.appliquerRemplacement(
                                revisionAttendue = resultat.revisionSource,
                                remplacement = resultat.remplacement,
                                selection = resultat.selection,
                            )
                        }
                    }
                },
            )
        }
    }
}

/**
 * Explique pourquoi la note ne s'ouvre pas en saisie.
 *
 * Sans ce bandeau, l'absence de champ de texte passerait pour une panne. Le
 * message dit la cause — document Office, format que l'application n'écrit
 * pas, encodage, suite de caractères démesurée — plutôt que la mécanique,
 * dont l'utilisateur n'a rien à faire.
 */
@Composable
private fun BandeauLectureSeule(
    raison: RaisonLectureSeule,
    encodageForcable: String?,
    onOuvrirQuandMeme: () -> Unit,
) {
    Surface(
        color = MaterialTheme.colorScheme.secondaryContainer,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = Icons.Default.Lock,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSecondaryContainer,
                modifier = Modifier.padding(end = 12.dp),
            )
            Column {
                Text(
                    text = stringResource(
                        when (raison) {
                            RaisonLectureSeule.MOT_TROP_LONG -> R.string.apercu_lecture_seule
                            RaisonLectureSeule.DOCUMENT -> R.string.apercu_document_lecture_seule
                            RaisonLectureSeule.FORMAT_TEXTE -> R.string.apercu_texte_lecture_seule
                            RaisonLectureSeule.AUTORISATION -> R.string.apercu_autorisation_lecture_seule
                            RaisonLectureSeule.ENCODAGE -> R.string.apercu_encodage_lecture_seule
                        },
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                )
                // La détection doute parfois à tort ; l'utilisateur, lui, sait
                // ce que contient son fichier. Aucun bouton quand aucun
                // encodage ne rend le fichier à l'identique.
                if (raison == RaisonLectureSeule.ENCODAGE && encodageForcable != null) {
                    TextButton(onClick = onOuvrirQuandMeme) {
                        Text(stringResource(R.string.apercu_ouvrir_quand_meme, encodageForcable))
                    }
                }
            }
        }
    }
}

/**
 * Le menu ⋮ de l'éditeur. Une entrée nulle n'est pas proposée ; l'écran ne
 * compose pas le menu quand aucune ne l'est.
 */
@Composable
private fun MenuEditeur(
    onPlan: (() -> Unit)?,
    onRevenirALaDetection: (() -> Unit)?,
) {
    var ouvert by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { ouvert = true }) {
            Icon(
                imageVector = Icons.Default.MoreVert,
                contentDescription = stringResource(R.string.editeur_plus_actions),
            )
        }
        DropdownMenu(expanded = ouvert, onDismissRequest = { ouvert = false }) {
            onPlan?.let { action ->
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.editeur_plan)) },
                    leadingIcon = { Icon(Icons.AutoMirrored.Filled.List, contentDescription = null) },
                    onClick = {
                        ouvert = false
                        action()
                    },
                )
            }
            onRevenirALaDetection?.let { action ->
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.editeur_detection_automatique)) },
                    onClick = {
                        ouvert = false
                        action()
                    },
                )
            }
        }
    }
}

/**
 * La feuille du plan : les titres dans l'ordre, indentés selon leur niveau,
 * une ligne chacun. Elle s'ouvre défilée sur la section où l'on se trouve, mise
 * en évidence ; une note sans titre le dit plutôt que de montrer une feuille
 * vide.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FeuillePlan(
    plan: PlanOuvert,
    onFermer: () -> Unit,
    onChoisir: (EntreePlan) -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onFermer) {
        Text(
            text = stringResource(R.string.editeur_plan_titre),
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(start = 24.dp, end = 24.dp, bottom = 8.dp),
        )
        if (plan.entrees.isEmpty()) {
            Text(
                text = stringResource(R.string.editeur_plan_vide),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 24.dp, end = 24.dp, top = 8.dp, bottom = 32.dp),
            )
            return@ModalBottomSheet
        }
        // Deux lignes de contexte au-dessus de la section courante : on voit
        // d'où l'on vient, pas seulement où l'on est.
        val etatListe = rememberLazyListState(
            initialFirstVisibleItemIndex = (plan.courante - LIGNES_AVANT_COURANTE).coerceAtLeast(0),
        )
        // L'application est bord à bord : sans la hauteur de la barre de
        // navigation, les derniers titres passaient sous ses boutons.
        val barreNavigation = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
        LazyColumn(
            state = etatListe,
            contentPadding = PaddingValues(bottom = 24.dp + barreNavigation),
        ) {
            itemsIndexed(plan.entrees) { index, entree ->
                val courante = index == plan.courante
                Text(
                    text = entree.texte,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = if (entree.niveau == 0 || courante) FontWeight.SemiBold else FontWeight.Normal,
                    color = if (courante) {
                        MaterialTheme.colorScheme.onSecondaryContainer
                    } else {
                        MaterialTheme.colorScheme.onSurface
                    },
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .fillMaxWidth()
                        .then(
                            if (courante) {
                                Modifier.background(MaterialTheme.colorScheme.secondaryContainer)
                            } else {
                                Modifier
                            },
                        )
                        .clickable { onChoisir(entree) }
                        .padding(
                            start = 24.dp + RETRAIT_NIVEAU_PLAN * entree.niveau,
                            end = 24.dp,
                            top = 12.dp,
                            bottom = 12.dp,
                        ),
                )
            }
        }
    }
}

/** Retrait d'un niveau de titre dans la feuille du plan. */
private val RETRAIT_NIVEAU_PLAN = 16.dp

/** Entrées montrées au-dessus de la section courante à l'ouverture du plan. */
private const val LIGNES_AVANT_COURANTE = 2

/**
 * Calme exigé avant l'enregistrement différé, en millisecondes.
 *
 * Le cache absorbe déjà les écritures répétées — la file n'en garde qu'une par
 * chemin — mais photographier l'`Editable` et traverser la frontière gomobile
 * à chaque frappe resterait du gaspillage. La synchronisation, elle, a son
 * propre anti-rebond dans `SyncScheduler`.
 */
private const val DELAI_ENREGISTREMENT_MS = 700L

/** Nom d'écran du fil d'Ariane de diagnostic. */
private const val ECRAN_EDITEUR = "editeur" // i18n-ok

/**
 * Palier de réévaluation du fil d'Ariane, en caractères.
 *
 * Le mettre à jour à chaque frappe ajouterait un appel système par caractère,
 * sur l'écran qui est déjà le plus coûteux de l'application.
 */
private const val PALIER_FIL_ARIANE = 4096
