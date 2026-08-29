package eu.opennote.ui.browser

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CreateNewFolder
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import eu.opennote.appContainer
import eu.opennote.data.FolderEntryDto
import eu.opennote.ui.common.Bandeau
import eu.opennote.ui.common.BandeauCache
import eu.opennote.ui.common.ChargementPleinEcran
import eu.opennote.ui.common.EtatVide
import eu.opennote.ui.common.PastilleEnAttente
import eu.opennote.ui.common.resoudre

/** Boîte de dialogue ouverte, s'il y en a une. */
private sealed interface Dialogue {
    data object NouvelleNote : Dialogue
    data object NouveauDossier : Dialogue
    data class Renommer(val entree: FolderEntryDto) : Dialogue
    data class Supprimer(val entree: FolderEntryDto) : Dialogue
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BrowserScreen(
    onOuvrirNote: (String) -> Unit,
    onReglages: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: BrowserViewModel = viewModel(
        factory = BrowserViewModel.factory(LocalContext.current.appContainer),
    ),
) {
    val etat by viewModel.uiState.collectAsStateWithLifecycle()
    val evenement by viewModel.evenements.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }

    var dialogue by remember { mutableStateOf<Dialogue?>(null) }

    // Rédigé ici, dans la composition : le `LaunchedEffect` ci-dessous est une
    // coroutine, et une coroutine ne peut pas lire de ressource.
    val messageEvenement = (evenement as? BrowserEvent.Message)?.texte?.resoudre()

    LaunchedEffect(evenement) {
        when (val e = evenement) {
            is BrowserEvent.OuvrirNote -> {
                viewModel.evenementConsomme()
                onOuvrirNote(e.chemin)
            }

            is BrowserEvent.Message -> {
                viewModel.evenementConsomme()
                messageEvenement?.let { snackbar.showSnackbar(it) }
            }

            null -> Unit
        }
    }

    // Le retour système remonte d'un dossier tant qu'on n'est pas à la racine ;
    // au-delà, on laisse le système fermer l'application.
    BackHandler(enabled = etat.peutRemonter) { viewModel.remonter() }

    Scaffold(
        modifier = modifier,
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = {
                    Text(etat.titre, maxLines = 1, overflow = TextOverflow.Ellipsis)
                },
                navigationIcon = {
                    if (etat.peutRemonter) {
                        IconButton(onClick = viewModel::remonter) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, "Dossier parent")
                        }
                    }
                },
                actions = {
                    IconButton(onClick = viewModel::rafraichir) {
                        Icon(Icons.Default.Refresh, "Rafraîchir et synchroniser")
                    }
                    IconButton(onClick = onReglages) {
                        Icon(Icons.Default.Settings, "Réglages")
                    }
                },
            )
        },
        floatingActionButton = {
            Column(
                horizontalAlignment = Alignment.End,
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                FloatingActionButton(
                    onClick = { dialogue = Dialogue.NouveauDossier },
                    containerColor = MaterialTheme.colorScheme.secondaryContainer,
                ) {
                    Icon(Icons.Default.CreateNewFolder, "Nouveau dossier")
                }
                ExtendedFloatingActionButton(
                    onClick = { dialogue = Dialogue.NouvelleNote },
                    icon = { Icon(Icons.Default.Add, null) },
                    text = { Text("Nouvelle note") },
                )
            }
        },
    ) { paddings ->
        Column(modifier = Modifier.padding(paddings)) {
            if (etat.depuisCache) BandeauCache()

            etat.erreur?.let { message ->
                Bandeau(
                    texte = message.resoudre(),
                    couleurFond = MaterialTheme.colorScheme.errorContainer,
                    couleurTexte = MaterialTheme.colorScheme.onErrorContainer,
                )
            }

            when {
                etat.chargement && etat.entrees.isEmpty() -> ChargementPleinEcran()

                // Un dossier vide n'est pas une panne, et la façade le
                // renvoie désormais comme tel même hors connexion : listing
                // vide plutôt qu'erreur réseau.
                etat.entrees.isEmpty() -> EtatVide(
                    titre = "Ce dossier est vide",
                    detail = if (etat.depuisCache) {
                        "Rien en cache pour ce dossier. Vous pouvez quand même " +
                            "créer une note : elle partira à la prochaine connexion."
                    } else {
                        "Créez une note avec le bouton en bas de l'écran."
                    },
                )

                else -> LazyColumn(modifier = Modifier.fillMaxSize()) {
                    items(etat.entrees, key = { it.path }) { entree ->
                        LigneEntree(
                            entree = entree,
                            onClick = { viewModel.ouvrir(entree) },
                            onRenommer = { dialogue = Dialogue.Renommer(entree) },
                            onSupprimer = { dialogue = Dialogue.Supprimer(entree) },
                        )
                    }
                }
            }
        }
    }

    when (val d = dialogue) {
        null -> Unit

        Dialogue.NouvelleNote -> SaisieDialog(
            titre = "Nouvelle note",
            label = "Titre",
            valeurInitiale = "",
            libelleValidation = "Créer",
            aide = "Le nom de fichier est dérivé du titre ; un suffixe est ajouté " +
                "si le nom est déjà pris.",
            onValider = viewModel::creerNote,
            onFermer = { dialogue = null },
        )

        Dialogue.NouveauDossier -> SaisieDialog(
            titre = "Nouveau dossier",
            label = "Nom du dossier",
            valeurInitiale = "",
            libelleValidation = "Créer",
            onValider = viewModel::creerDossier,
            onFermer = { dialogue = null },
        )

        is Dialogue.Renommer -> SaisieDialog(
            titre = "Renommer",
            label = "Nouveau nom",
            // `display`, pas `name` : la façade réajoute `.md` quand la cible
            // est une note, et « ma note » se relit mieux que « ma note.md »
            // dans un champ de saisie.
            valeurInitiale = d.entree.display,
            libelleValidation = "Renommer",
            onValider = { nom -> viewModel.renommer(d.entree, nom) },
            onFermer = { dialogue = null },
        )

        is Dialogue.Supprimer -> SuppressionDialog(
            nomAffiche = d.entree.display,
            estDossier = d.entree.isDir,
            onConfirmer = { viewModel.supprimer(d.entree) },
            onFermer = { dialogue = null },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LigneEntree(
    entree: FolderEntryDto,
    onClick: () -> Unit,
    onRenommer: () -> Unit,
    onSupprimer: () -> Unit,
) {
    var menuOuvert by remember { mutableStateOf(false) }

    ListItem(
        headlineContent = {
            Text(entree.display, maxLines = 1, overflow = TextOverflow.Ellipsis)
        },
        supportingContent = {
            sousTitre(entree)?.let { texte ->
                Text(
                    text = texte,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        leadingContent = {
            Icon(
                imageVector = if (entree.isDir) {
                    Icons.Default.Folder
                } else {
                    Icons.Default.Description
                },
                contentDescription = if (entree.isDir) "Dossier" else "Note",
                tint = MaterialTheme.colorScheme.primary,
            )
        },
        trailingContent = {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                // Pastille : modification locale pas encore poussée.
                if (entree.pending) PastilleEnAttente()

                IconButton(onClick = { menuOuvert = true }) {
                    Icon(Icons.Default.MoreVert, "Actions sur « ${entree.display} »")
                }

                DropdownMenu(expanded = menuOuvert, onDismissRequest = { menuOuvert = false }) {
                    DropdownMenuItem(
                        text = { Text("Renommer") },
                        onClick = {
                            menuOuvert = false
                            onRenommer()
                        },
                    )
                    DropdownMenuItem(
                        text = { Text("Supprimer") },
                        onClick = {
                            menuOuvert = false
                            onSupprimer()
                        },
                    )
                }
            }
        },
        modifier = Modifier.clickable(onClick = onClick),
    )
}

/**
 * Sous-titre d'une ligne.
 *
 * Un dossier n'en a pas : `size` et `modTime` sont vides pour lui côté façade,
 * afficher « 0 o » serait une invention.
 */
private fun sousTitre(entree: FolderEntryDto): String? {
    if (entree.isDir) return null

    val taille = when {
        entree.size < 1024 -> "${entree.size} o"
        else -> "${entree.size / 1024} Kio"
    }
    val date = entree.modTime.take(10).takeIf { it.length == 10 }
    val etat = if (entree.pending) "en attente d'envoi" else null

    return listOfNotNull(date, taille, etat).joinToString(" · ")
}
