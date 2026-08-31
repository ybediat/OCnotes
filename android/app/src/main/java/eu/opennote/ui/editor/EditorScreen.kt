package eu.opennote.ui.editor

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.BringIntoViewSpec
import androidx.compose.foundation.gestures.LocalBringIntoViewSpec
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Visibility
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
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.Alignment
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import eu.opennote.R
import eu.opennote.appContainer
import eu.opennote.ui.common.ChargementPleinEcran
import eu.opennote.ui.common.EtatVide
import eu.opennote.ui.common.resoudre
import eu.opennote.ui.theme.StyleEditeur

/**
 * Éditeur plein écran.
 *
 * Une liste virtualisée de source brute. Une seule fenêtre autour du curseur
 * devient un champ de saisie ; le reste demeure du texte léger.
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

    // Rédigé hors du `LaunchedEffect` : une coroutine n'est pas un contexte
    // de composition, elle ne peut pas lire de ressource.
    val messageErreur = etat.erreur?.resoudre()

    LaunchedEffect(messageErreur) {
        messageErreur?.let {
            snackbar.showSnackbar(it)
            viewModel.erreurConsommee()
        }
    }

    // Le retour arrière enregistre avant de quitter. `WriteNote` écrit dans le
    // cache local : l'opération est immédiate et ne peut pas échouer faute de
    // réseau, il n'y a donc rien à attendre ni à confirmer.
    val quitter = {
        viewModel.enregistrerMaintenant()
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
                    IconButton(onClick = viewModel::basculerApercu) {
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
                detail = stringResource(R.string.editeur_illisible_detail),
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
                .imePadding(),
        ) {
            EditeurVirtualise(
                document = etat.document,
                tranches = etat.tranches,
                focus = etat.focus,
                activation = etat.activation,
                valeur = etat.valeur,
                onActiver = viewModel::activer,
                onValeurChangee = viewModel::onValeurChangee,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
            )

            // Rien à mettre en forme dans un .txt : les marqueurs y
            // resteraient des marqueurs, y compris à l'aperçu.
            if (!etat.texteBrut) {
                FormatToolbar(
                    actions = etat.actions,
                    onAction = viewModel::appliquer,
                )
            }
        }
    }
}

/** Une liste verticale unique ; jamais plus d'un champ de saisie composé. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun EditeurVirtualise(
    document: String,
    tranches: List<TrancheEditeur>,
    focus: Int,
    activation: Long,
    valeur: TextFieldValue,
    onActiver: (Int) -> Unit,
    onValeurChangee: (TextFieldValue) -> Unit,
    modifier: Modifier = Modifier,
) {
    val miseEnVue = remember {
        object : BringIntoViewSpec {
            override fun calculateScrollDistance(
                offset: Float,
                size: Float,
                containerSize: Float,
            ): Float = calculerDistanceMiseEnVue(offset, size, containerSize)
        }
    }

    CompositionLocalProvider(LocalBringIntoViewSpec provides miseEnVue) {
        LazyColumn(
            contentPadding = PaddingValues(vertical = 8.dp),
            modifier = modifier,
        ) {
            itemsIndexed(
                items = tranches,
                // La clé du champ reste identique quand sa fenêtre glisse : Compose
                // conserve alors le nœud focalisé et l'IME ne se ferme pas.
                key = { index, tranche ->
                    if (index == focus) "active" else "tranche-${tranche.debut}"
                },
            ) { index, tranche ->
                if (index == focus) {
                    FenetreActive(
                        valeur = valeur,
                        activation = activation,
                        onValeurChangee = onValeurChangee,
                    )
                } else {
                    TrancheInactive(
                        texte = tranche.texteDe(document),
                        onOffsetTouche = { offsetLocal ->
                            onActiver(tranche.debut + offsetLocal)
                        },
                    )
                }
            }
        }
    }
}

/**
 * Laisse le curseur tranquille dans les deux tiers supérieurs de la liste.
 *
 * Une grande demande correspond au champ entier : la recentrer ferait sortir
 * son début de l'écran. Seules les petites demandes du curseur sont donc
 * ramenées, au minimum, sur la frontière des deux tiers.
 */
internal fun calculerDistanceMiseEnVue(
    offset: Float,
    taille: Float,
    tailleConteneur: Float,
): Float {
    if (tailleConteneur <= 0f) return 0f
    if (offset < 0f) return offset

    val seuil = tailleConteneur * (2f / 3f)
    if (taille >= seuil) return 0f
    return (offset + taille - seuil).coerceAtLeast(0f)
}

/** Traduit le premier toucher en offset source avant de remplacer le `Text`. */
@Composable
private fun TrancheInactive(
    texte: String,
    onOffsetTouche: (Int) -> Unit,
) {
    var resultat by remember(texte) { mutableStateOf<TextLayoutResult?>(null) }
    val resultatCourant by rememberUpdatedState(resultat)
    val onOffsetCourant by rememberUpdatedState(onOffsetTouche)
    val style = StyleEditeur.copy(color = MaterialTheme.colorScheme.onSurface)

    Text(
        text = texte,
        style = style,
        onTextLayout = { resultat = it },
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 2.dp)
            .pointerInput(texte) {
                detectTapGestures { position: Offset ->
                    val offset = resultatCourant?.getOffsetForPosition(position) ?: 0
                    onOffsetCourant(offset.coerceIn(0, texte.length))
                }
            },
    )
}

/** Champ borné, recentré automatiquement par le ViewModel quand il déborde. */
@Composable
private fun FenetreActive(
    valeur: TextFieldValue,
    activation: Long,
    onValeurChangee: (TextFieldValue) -> Unit,
) {
    val focusRequester = remember { FocusRequester() }
    val clavier = LocalSoftwareKeyboardController.current
    val style = StyleEditeur.copy(color = MaterialTheme.colorScheme.onSurface)
    val fond = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.20f)

    LaunchedEffect(activation) {
        if (activation > 0) {
            focusRequester.requestFocus()
            clavier?.show()
        }
    }

    BasicTextField(
        value = valeur,
        onValueChange = onValeurChangee,
        textStyle = style,
        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
        decorationBox = { champ ->
            Box {
                if (valeur.text.isEmpty()) {
                    Text(
                        text = stringResource(R.string.editeur_saisie_vide),
                        style = style,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                champ()
            }
        },
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = if (valeur.text.isEmpty()) 180.dp else 24.dp)
            .background(fond)
            .focusRequester(focusRequester)
            .padding(horizontal = 20.dp, vertical = 2.dp),
    )
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
