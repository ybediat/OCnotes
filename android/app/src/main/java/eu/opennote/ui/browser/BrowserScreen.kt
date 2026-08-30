package eu.opennote.ui.browser

import android.text.format.Formatter
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.CreateNewFolder
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.SortByAlpha
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
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
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import eu.opennote.R
import eu.opennote.appContainer
import eu.opennote.data.FolderEntryDto
import eu.opennote.ui.common.Bandeau
import eu.opennote.ui.common.BandeauCache
import eu.opennote.ui.common.ChargementPleinEcran
import eu.opennote.ui.common.EtatVide
import eu.opennote.ui.common.PastilleEnAttente
import eu.opennote.ui.common.resoudre
import eu.opennote.ui.theme.CouleurSignatureClaire
import eu.opennote.ui.theme.CouleurSignatureSombre
import java.time.Instant
import java.time.YearMonth
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import java.time.format.FormatStyle

/** Boîte de dialogue ouverte, s'il y en a une. */
private sealed interface Dialogue {
    data object NouvelleNote : Dialogue
    data object NouveauDossier : Dialogue
    data class Renommer(val entree: FolderEntryDto) : Dialogue
    data class Deplacer(val entree: FolderEntryDto) : Dialogue
    data class Supprimer(val entree: FolderEntryDto) : Dialogue
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BrowserScreen(
    onOuvrirNote: (String) -> Unit,
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

    // Le retour système annule d'abord la recherche, puis remonte d'un dossier
    // tant qu'on n'est pas à la racine ; au-delà, on laisse le système fermer
    // l'application. Les deux gardes s'excluent, l'ordre de déclaration ne
    // décide donc de rien.
    BackHandler(enabled = etat.recherche.isNotBlank()) { viewModel.effacerRecherche() }
    BackHandler(enabled = etat.recherche.isBlank() && etat.peutRemonter) { viewModel.remonter() }

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
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = stringResource(
                                    R.string.browser_dossier_parent,
                                ),
                            )
                        }
                    }
                },
                // Deux actions, pas quatre : la recherche a sa propre ligne,
                // et les réglages vivent dans le tiroir. Ce qui reste ici est
                // ce qui agit sur la liste affichée.
                actions = {
                    BoutonTri(tri = etat.tri, onChanger = viewModel::changerTri)
                    IconButton(onClick = viewModel::rafraichir) {
                        Icon(
                            imageVector = Icons.Default.Refresh,
                            contentDescription = stringResource(R.string.browser_rafraichir),
                        )
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
                    Icon(
                        imageVector = Icons.Default.CreateNewFolder,
                        contentDescription = stringResource(
                            R.string.browser_nouveau_dossier,
                        ),
                    )
                }
                ExtendedFloatingActionButton(
                    onClick = { dialogue = Dialogue.NouvelleNote },
                    icon = { Icon(Icons.Default.Add, null) },
                    text = { Text(stringResource(R.string.browser_nouvelle_note)) },
                )
            }
        },
    ) { paddings ->
        Column(modifier = Modifier.padding(paddings)) {
            BarreRecherche(
                valeur = etat.recherche,
                onValeur = viewModel::chercher,
                onEffacer = viewModel::effacerRecherche,
                // La recherche suit le mode, et son invite doit le dire : un
                // résultat manquant ne doit jamais passer pour une note perdue.
                invite = stringResource(
                    if (etat.enListePlate) {
                        R.string.browser_recherche_invite_tout
                    } else {
                        R.string.browser_recherche_invite
                    },
                ),
            )

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
                    titre = stringResource(
                        if (etat.enListePlate) {
                            R.string.browser_liste_vide_titre
                        } else {
                            R.string.browser_vide_titre
                        },
                    ),
                    detail = stringResource(
                        when {
                            etat.depuisCache -> R.string.browser_vide_cache
                            etat.enListePlate -> R.string.browser_liste_vide_detail
                            else -> R.string.browser_vide_detail
                        },
                    ),
                )

                // Un dossier plein dont la recherche ne retient rien n'est pas
                // un dossier vide : le premier se corrige en effaçant le
                // champ, le second en créant une note.
                etat.rechercheSansResultat -> EtatVide(
                    titre = stringResource(R.string.browser_recherche_vide_titre),
                    detail = stringResource(R.string.browser_recherche_vide_detail),
                )

                else -> ListeEntrees(
                    entrees = etat.entreesAffichees,
                    grouperParMois = etat.enListePlate && etat.tri == Tri.DATE,
                    afficherDossier = etat.enListePlate,
                    onOuvrir = viewModel::ouvrir,
                    onRenommer = { dialogue = Dialogue.Renommer(it) },
                    onDeplacer = { dialogue = Dialogue.Deplacer(it) },
                    onSupprimer = { dialogue = Dialogue.Supprimer(it) },
                )
            }
        }
    }

    when (val d = dialogue) {
        null -> Unit

        Dialogue.NouvelleNote -> NouvelleNoteDialog(
            dossiers = etat.dossiers,
            dossierPropose = etat.dossierPropose,
            nomRacine = etat.nomRacine,
            onValider = viewModel::creerNote,
            onFermer = { dialogue = null },
        )

        Dialogue.NouveauDossier -> SaisieDialog(
            titre = stringResource(R.string.browser_nouveau_dossier),
            label = stringResource(R.string.browser_dossier_label),
            valeurInitiale = "",
            libelleValidation = stringResource(R.string.action_creer),
            onValider = viewModel::creerDossier,
            onFermer = { dialogue = null },
        )

        is Dialogue.Renommer -> SaisieDialog(
            titre = stringResource(R.string.action_renommer),
            label = stringResource(R.string.browser_renommer_label),
            // `display`, pas `name` : la façade réajoute `.md` quand la cible
            // est une note, et « ma note » se relit mieux que « ma note.md »
            // dans un champ de saisie.
            valeurInitiale = d.entree.display,
            libelleValidation = stringResource(R.string.action_renommer),
            onValider = { nom -> viewModel.renommer(d.entree, nom) },
            onFermer = { dialogue = null },
        )

        is Dialogue.Deplacer -> DeplacerDialog(
            entree = d.entree,
            dossiers = etat.dossiers,
            nomRacine = etat.nomRacine,
            onValider = { dossier -> viewModel.deplacer(d.entree, dossier) },
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

/**
 * Barre de recherche, posée sous la barre de titre et toujours visible.
 *
 * Toujours visible et jamais focalisée d'office : ouvrir un dossier ne doit
 * pas faire surgir le clavier. Le champ se voit, il n'insiste pas.
 *
 * La croix n'apparaît qu'une fois quelque chose saisi — un bouton d'effacement
 * sur un champ vide est un bouton mort.
 */
@Composable
private fun ListeEntrees(
    entrees: List<FolderEntryDto>,
    grouperParMois: Boolean,
    afficherDossier: Boolean,
    onOuvrir: (FolderEntryDto) -> Unit,
    onRenommer: (FolderEntryDto) -> Unit,
    onDeplacer: (FolderEntryDto) -> Unit,
    onSupprimer: (FolderEntryDto) -> Unit,
) {
    LazyColumn(modifier = Modifier.fillMaxSize()) {
        var moisPrecedent: YearMonth? = null

        entrees.forEach { entree ->
            if (grouperParMois && !entree.isDir) {
                val mois = moisDe(entree.modTime)
                if (mois != null && mois != moisPrecedent) {
                    item(key = "mois-${mois.year}-${mois.monthValue}") {
                        SeparateurMois(mois)
                    }
                    moisPrecedent = mois
                }
            }

            item(key = entree.path) {
                LigneEntree(
                    entree = entree,
                    afficherDossier = afficherDossier,
                    onClick = { onOuvrir(entree) },
                    onRenommer = { onRenommer(entree) },
                    onDeplacer = if (entree.isDir) null else { onDeplacer(entree) },
                    onSupprimer = { onSupprimer(entree) },
                )
            }
        }
    }
}

@Composable
private fun SeparateurMois(mois: YearMonth) {
    val locale = LocalConfiguration.current.locales[0]
    val couleur = if (isSystemInDarkTheme()) CouleurSignatureSombre else CouleurSignatureClaire
    val libelle = remember(mois, locale) {
        mois.atDay(1)
            .format(DateTimeFormatter.ofPattern("LLLL yyyy", locale))
            .replaceFirstChar { it.titlecase(locale) }
    }

    Row(
        horizontalArrangement = Arrangement.Center,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 10.dp),
    ) {
        Surface(
            shape = MaterialTheme.shapes.extraLarge,
            color = MaterialTheme.colorScheme.surface,
            border = BorderStroke(1.dp, couleur),
        ) {
            Text(
                text = libelle,
                style = MaterialTheme.typography.labelLarge,
                color = couleur,
                modifier = Modifier.padding(horizontal = 18.dp, vertical = 5.dp),
            )
        }
    }
}

@Composable
private fun BarreRecherche(
    valeur: String,
    onValeur: (String) -> Unit,
    onEffacer: () -> Unit,
    invite: String,
) {
    OutlinedTextField(
        value = valeur,
        onValueChange = onValeur,
        placeholder = { Text(invite) },
        // Icône décorative : l'invite nomme déjà le champ, la redoubler ferait
        // dire la même chose deux fois à TalkBack.
        leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
        trailingIcon = {
            if (valeur.isNotEmpty()) {
                IconButton(onClick = onEffacer) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = stringResource(
                            R.string.browser_recherche_effacer,
                        ),
                    )
                }
            }
        },
        singleLine = true,
        shape = MaterialTheme.shapes.extraLarge,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
    )
}

/**
 * Bouton de tri à deux états.
 *
 * L'icône **et** la description annoncent la même chose : l'ordre qu'un appui
 * donnerait, pas celui en cours. Les faire diverger — l'icône pour l'état, la
 * description pour l'action — ferait entendre à TalkBack le contraire de ce
 * que l'écran montre. L'ordre en vigueur, lui, se lit dans la liste.
 */
@Composable
private fun BoutonTri(tri: Tri, onChanger: () -> Unit) {
    val propose = tri.suivant()

    IconButton(onClick = onChanger) {
        Icon(
            imageVector = when (propose) {
                Tri.NOM -> Icons.Default.SortByAlpha
                Tri.DATE -> Icons.Default.Schedule
            },
            contentDescription = stringResource(
                when (propose) {
                    Tri.NOM -> R.string.browser_tri_par_nom
                    Tri.DATE -> R.string.browser_tri_par_date
                },
            ),
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LigneEntree(
    entree: FolderEntryDto,
    afficherDossier: Boolean,
    onClick: () -> Unit,
    onRenommer: () -> Unit,
    // `null` retire l'action du menu plutôt que de l'y laisser inerte —
    // c'est le cas d'un dossier, que DeplacerDialog ne couvre pas.
    onDeplacer: (() -> Unit)?,
    onSupprimer: () -> Unit,
) {
    var menuOuvert by remember { mutableStateOf(false) }

    ListItem(
        headlineContent = {
            Text(entree.display, maxLines = 1, overflow = TextOverflow.Ellipsis)
        },
        supportingContent = {
            sousTitre(entree, afficherDossier)?.let { texte ->
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
                contentDescription = stringResource(
                    if (entree.isDir) {
                        R.string.browser_type_dossier
                    } else {
                        R.string.browser_type_note
                    },
                ),
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
                    Icon(
                        imageVector = Icons.Default.MoreVert,
                        contentDescription = stringResource(
                            R.string.browser_actions,
                            entree.display,
                        ),
                    )
                }

                DropdownMenu(expanded = menuOuvert, onDismissRequest = { menuOuvert = false }) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.action_renommer)) },
                        onClick = {
                            menuOuvert = false
                            onRenommer()
                        },
                    )
                    onDeplacer?.let { deplacer ->
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.action_deplacer)) },
                            onClick = {
                                menuOuvert = false
                                deplacer()
                            },
                        )
                    }
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.action_supprimer)) },
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
 * Sous-titre d'une ligne : date, taille, état d'envoi.
 *
 * Un dossier n'en a pas : `size` et `modTime` sont vides pour lui côté façade,
 * afficher « 0 o » serait une invention.
 *
 * La date et la taille sont mises en forme par la plateforme et non à la
 * main : elle seule connaît l'ordre des composantes d'une date et les unités
 * de chaque langue.
 */
@Composable
private fun sousTitre(entree: FolderEntryDto, afficherDossier: Boolean): String? {
    if (entree.isDir) return null

    val taille = Formatter.formatShortFileSize(LocalContext.current, entree.size)
    val etat = if (entree.pending) stringResource(R.string.browser_en_attente) else null

    // Le dossier se lit dans le chemin, dont il est le préfixe : la façade
    // n'a pas de champ pour lui. Vide pour une note posée à la racine, qui
    // n'a alors rien à afficher.
    val dossier = if (afficherDossier) entree.path.substringBeforeLast('/', "").ifBlank { null } else null

    return listOfNotNull(dossier, dateLocale(entree.modTime), taille, etat)
        .joinToString(stringResource(R.string.browser_soustitre_separateur))
}

/**
 * Date de modification, dans le fuseau et la langue de l'appareil.
 *
 * `modTime` est en RFC 3339 UTC. La première version le tronquait à dix
 * caractères, ce qui affichait la date **UTC** : celle du lendemain pour une
 * note enregistrée en soirée à Paris.
 *
 * La locale vient de la composition et non de `Locale.getDefault()`, pour la
 * même raison que `Texte` existe : un changement de langue doit redessiner la
 * liste. Une date vide ou illisible disparaît sans un mot — dans une liste,
 * le reste du sous-titre vaut mieux qu'un message d'erreur.
 */
@Composable
private fun dateLocale(modTime: String): String? {
    val locale = LocalConfiguration.current.locales[0]
    val format = DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withLocale(locale)

    return try {
        Instant.parse(modTime).atZone(ZoneId.systemDefault()).format(format)
    } catch (_: DateTimeParseException) {
        null
    }
}

private fun moisDe(modTime: String): YearMonth? =
    try {
        YearMonth.from(Instant.parse(modTime).atZone(ZoneId.systemDefault()))
    } catch (_: DateTimeParseException) {
        null
    }
