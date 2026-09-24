package eu.ocnotes.ui.editor

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.Alignment
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import eu.ocnotes.R
import eu.ocnotes.appContainer
import eu.ocnotes.ui.common.ChargementPleinEcran
import eu.ocnotes.ui.common.EtatVide
import eu.ocnotes.ui.common.resoudre
import kotlinx.coroutines.delay

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
    var dernierInstantaneNatif by remember(chemin, viewModel) {
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
    val onDetachementNatif: (InstantaneEditeurNatif) -> Unit = remember(chemin, viewModel) {
        { instantane ->
            dernierInstantaneNatif = instantane
            viewModel.enregistrerInstantaneNatif(instantane, survivreEcran = true)
        }
    }

    // Overlay d'attente tant que le champ natif n'a pas dessiné une fois. Sans
    // lui, le spinner de chargement disparaît puis l'UI gèle ~700 ms sur le
    // layout de la note : ça se lit comme un plantage. La clé sur `apercu` le
    // réarme quand on revient de l'aperçu, où le champ est reconstruit.
    var natifPret by remember(chemin, etat.apercu) { mutableStateOf(false) }
    val onPretNatif = remember(chemin, etat.apercu) { { natifPret = true } }

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
    LaunchedEffect(etat.modifie, etat.revision, etat.apercu) {
        if (etat.modifie && !etat.apercu) {
            delay(DELAI_ENREGISTREMENT_MS)
            sessionNative.instantane()?.let { instantane ->
                viewModel.enregistrerInstantaneNatif(instantane, survivreEcran = false)
            }
        }
    }

    // Le retour arrière enregistre avant de quitter. `WriteNote` écrit dans le
    // cache local : l'opération est immédiate et ne peut pas échouer faute de
    // réseau, il n'y a donc rien à attendre ni à confirmer.
    val quitter = {
        (sessionNative.instantane() ?: dernierInstantaneNatif)?.let { instantane ->
            viewModel.enregistrerInstantaneNatif(instantane, survivreEcran = true)
        }
        onRetour()
    }
    BackHandler(onBack = quitter)

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
                        if (etat.modifie) {
                            Text(
                                text = stringResource(R.string.editeur_brouillon),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
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
                    // Rien à basculer sur une note en lecture seule : un
                    // bouton qui ne fait rien vaut moins que pas de bouton.
                    if (!etat.modifiable) return@TopAppBar

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

        // L'aperçu remplace la saisie plutôt que de la doubler : sur un
        // téléphone, deux volets côte à côte ne laisseraient de place ni à
        // l'un ni à l'autre.
        if (etat.apercu) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddings),
            ) {
                if (!etat.modifiable) BandeauLectureSeule(etat.documentBureautique)
                VueMarkdown(blocs = etat.blocs, modifier = Modifier.weight(1f))
            }
            return@Scaffold
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddings)
                .consumeWindowInsets(paddings)
                .imePadding(),
        ) {
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
            ) {
                val restauration = dernierInstantaneNatif
                EditeurNatif(
                    texteInitial = restauration?.texte ?: etat.document,
                    session = sessionNative,
                    selectionInitiale = restauration?.selection
                        ?: SelectionEditeurNatif(0, 0),
                    revisionInitiale = restauration?.revision ?: 0,
                    defilementInitialX = restauration?.defilementX ?: 0,
                    defilementInitialY = restauration?.defilementY ?: 0,
                    indication = stringResource(R.string.editeur_saisie_vide),
                    descriptionDefilementRapide = stringResource(
                        R.string.editeur_defilement_rapide,
                    ),
                    onMutation = viewModel::signalerMutationNative,
                    onAvantDetachement = onDetachementNatif,
                    onPret = onPretNatif,
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
            // resteraient des marqueurs, y compris à l'aperçu.
            if (!etat.texteBrut) {
                FormatToolbar(
                    actions = etat.actions,
                    onAction = { action ->
                        sessionNative.instantane()?.let { instantane ->
                            viewModel.appliquer(action, instantane) { resultat ->
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
}

/**
 * Explique pourquoi la note ne s'ouvre pas en saisie.
 *
 * Sans ce bandeau, l'absence de champ de texte passerait pour une panne. Le
 * message dit la cause — document Office ou suite de caractères démesurée —
 * plutôt que la mécanique, dont l'utilisateur n'a rien à faire.
 */
@Composable
private fun BandeauLectureSeule(documentBureautique: Boolean) {
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
            Text(
                text = stringResource(
                    if (documentBureautique) {
                        R.string.apercu_document_lecture_seule
                    } else {
                        R.string.apercu_lecture_seule
                    },
                ),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSecondaryContainer,
            )
        }
    }
}

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
