package eu.opennote.ui.editor

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Column
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
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
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
 * Un seul champ de texte, sans décoration, qui occupe tout ce que le clavier
 * lui laisse : c'est la fonction principale de l'application, elle mérite
 * l'écran entier. La barre d'outils se pose au-dessus du clavier.
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
                if (!etat.modifiable) BandeauLectureSeule()
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
            TextField(
                value = etat.valeur,
                onValueChange = viewModel::onValeurChangee,
                textStyle = StyleEditeur,
                placeholder = {
                    Text(stringResource(R.string.editeur_saisie_vide), style = StyleEditeur)
                },
                colors = TextFieldDefaults.colors(
                    focusedContainerColor = Color.Transparent,
                    unfocusedContainerColor = Color.Transparent,
                    focusedIndicatorColor = Color.Transparent,
                    unfocusedIndicatorColor = Color.Transparent,
                ),
                // Pas de `verticalScroll` autour : un TextField borné fait
                // défiler son propre contenu et garde le curseur visible, ce
                // qu'un conteneur scrollable externe lui retirerait.
                //
                // Ce n'est plus la seule raison, et la seconde est
                // rédhibitoire. Sortir le défilement du champ pour le poser
                // dans une couche translatable — la piste évidente contre les
                // 505 ms de dessin par image mesurés sur une note de 285 ko —
                // a été essayé et **fait planter l'application** :
                //
                //   IllegalArgumentException: Can't represent a width of 1058
                //   and height of 531251 in Constraints
                //   at androidx.compose.material3.TextFieldMeasurePolicy.measure
                //
                // Compose empaquette largeur et hauteur dans un seul Long ; à
                // cette largeur, la hauteur plafonne à 262 143 px. Ce document
                // en demande 531 251. Un champ de saisie non borné est donc
                // **impossible** au-delà d'environ 1300 lignes affichées, quel
                // que soit son coût de dessin. La LazyColumn de l'aperçu y
                // échappe parce qu'elle ne mesure jamais la hauteur totale.
                //
                // Conclusion : la virtualisation n'est pas une optimisation
                // ici, c'est la seule chose qui tienne.
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(horizontal = 4.dp),
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

/**
 * Explique pourquoi la note ne s'ouvre pas en saisie.
 *
 * Sans ce bandeau, l'absence de champ de texte passerait pour une panne. Le
 * message dit la cause — une suite de caractères démesurée — plutôt que la
 * mécanique, dont l'utilisateur n'a rien à faire.
 */
@Composable
private fun BandeauLectureSeule() {
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
                text = stringResource(R.string.apercu_lecture_seule),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSecondaryContainer,
            )
        }
    }
}
