package eu.opennote.ui.editor

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import eu.opennote.appContainer
import eu.opennote.ui.common.ChargementPleinEcran
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

    LaunchedEffect(etat.erreur) {
        etat.erreur?.let {
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
                                text = "Brouillon local, envoi programmé",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                },
                navigationIcon = {
                    IconButton(onClick = quitter) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Retour")
                    }
                },
            )
        },
    ) { paddings ->
        if (etat.chargement) {
            ChargementPleinEcran(Modifier.padding(paddings))
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
                placeholder = { Text("Écrivez ici…", style = StyleEditeur) },
                colors = TextFieldDefaults.colors(
                    focusedContainerColor = Color.Transparent,
                    unfocusedContainerColor = Color.Transparent,
                    focusedIndicatorColor = Color.Transparent,
                    unfocusedIndicatorColor = Color.Transparent,
                ),
                // Pas de `verticalScroll` autour : un TextField borné fait
                // défiler son propre contenu et garde le curseur visible, ce
                // qu'un conteneur scrollable externe lui retirerait.
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(horizontal = 4.dp),
            )

            FormatToolbar(
                actions = etat.actions,
                onAction = viewModel::appliquer,
            )
        }
    }
}
