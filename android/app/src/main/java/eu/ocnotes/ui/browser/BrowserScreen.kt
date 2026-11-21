package eu.ocnotes.ui.browser

import android.text.format.Formatter
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Article
import androidx.compose.material.icons.automirrored.filled.DriveFileMove
import androidx.compose.material.icons.automirrored.filled.MenuOpen
import androidx.compose.material.icons.automirrored.filled.Sort
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.SwitchAccount
import androidx.compose.material.icons.filled.CreateNewFolder
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.EditNote
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import eu.ocnotes.R
import eu.ocnotes.appContainer
import eu.ocnotes.data.FolderEntryDto
import eu.ocnotes.data.RechercheTexteDto
import eu.ocnotes.data.ResultatTexteDto
import eu.ocnotes.ui.common.Bandeau
import eu.ocnotes.ui.common.BandeauCache
import eu.ocnotes.ui.common.ChargementPleinEcran
import eu.ocnotes.ui.common.EtatVide
import eu.ocnotes.ui.common.PastilleEnAttente
import eu.ocnotes.ui.common.partagerFichiers
import eu.ocnotes.ui.common.resoudre
import eu.ocnotes.ui.theme.CouleurSignatureClaire
import eu.ocnotes.ui.theme.CouleurSignatureSombre
import eu.ocnotes.ui.theme.StyleTitrePrincipal
import java.time.Instant
import java.time.YearMonth
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import java.time.format.FormatStyle
import kotlinx.coroutines.launch

/** Boîte de dialogue ouverte, s'il y en a une. */
private sealed interface Dialogue {
    data object NouvelleNote : Dialogue
    data object NouveauDossier : Dialogue
    data class Renommer(val entree: FolderEntryDto) : Dialogue
    data class Deplacer(val entree: FolderEntryDto) : Dialogue
    data class Copier(val entree: FolderEntryDto) : Dialogue
    data class CopierUneVersCompte(val entree: FolderEntryDto) : Dialogue
    data class Supprimer(val entree: FolderEntryDto) : Dialogue

    /** Actions groupées : la cible est la sélection portée par l'état. */
    data object DeplacerLot : Dialogue
    data object CopierLot : Dialogue
    data object CopierVersCompte : Dialogue
    data object SupprimerLot : Dialogue
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BrowserScreen(
    onOuvrirNote: (String) -> Unit,
    onOuvrirMenu: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: BrowserViewModel = viewModel(
        factory = BrowserViewModel.factory(LocalContext.current.appContainer),
    ),
) {
    val etat by viewModel.uiState.collectAsStateWithLifecycle()
    val comptes by LocalContext.current.appContainer.accountRegistry.state.collectAsStateWithLifecycle()
    val plusieursComptes = comptes.accounts.size > 1
    val evenement by viewModel.evenements.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val context = LocalContext.current

    // Revenir des réglages ne recharge pas la liste : c'est pourtant là que
    // le seuil d'alerte se relève, et le bandeau doit tomber aussitôt.
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { viewModel.verifierSeuil() }

    var dialogue by remember { mutableStateOf<Dialogue?>(null) }

    // Rédigé ici, dans la composition : le `LaunchedEffect` ci-dessous est une
    // coroutine, et une coroutine ne peut pas lire de ressource.
    val messageEvenement = (evenement as? BrowserEvent.Message)?.texte?.resoudre()
    val avertissementPartage = (evenement as? BrowserEvent.Partager)?.avertissement?.resoudre()

    // Consommer l'événement change la clé du `LaunchedEffect`, qui s'annule à
    // la recomposition suivante. Tout ce qui suspend part donc dans cette
    // portée-ci, qui survit à la consommation : sans elle, le partage était
    // annulé avant `startActivity` et le bouton « Partager » ne produisait
    // rien — le même sort attendait chaque snackbar de cet écran.
    val portee = rememberCoroutineScope()

    LaunchedEffect(evenement) {
        when (val e = evenement) {
            is BrowserEvent.OuvrirNote -> {
                viewModel.evenementConsomme()
                onOuvrirNote(e.chemin)
            }

            is BrowserEvent.Message -> {
                viewModel.evenementConsomme()
                portee.launch { messageEvenement?.let { snackbar.showSnackbar(it) } }
            }

            is BrowserEvent.Partager -> {
                viewModel.evenementConsomme()
                partagerFichiers(context, e.fichiers)
                portee.launch { avertissementPartage?.let { snackbar.showSnackbar(it) } }
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

    // Déclaré après les deux précédents, donc prioritaire : le dispatcher sert
    // le dernier `BackHandler` actif. Tant qu'une sélection est en cours, le
    // retour la vide et rien d'autre.
    BackHandler(enabled = etat.modeSelection) { viewModel.viderSelection() }

    Scaffold(
        modifier = modifier,
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            // Une sélection en cours remplace la barre de navigation par une
            // barre contextuelle : compteur et actions groupées.
            if (etat.modeSelection) {
                BarreSelection(
                    nombre = etat.selection.size,
                    peutDeplacer = etat.peutDeplacerSelection,
                    peutCopier = etat.peutCopierSelection,
                    peutCopierCompte = etat.peutCopierVersCompteSelection && plusieursComptes,
                    peutPartager = etat.peutPartagerSelection,
                    peutSupprimer = etat.peutSupprimerSelection,
                    onQuitter = viewModel::viderSelection,
                    onDeplacer = { dialogue = Dialogue.DeplacerLot },
                    onCopier = { dialogue = Dialogue.CopierLot },
                    onCopierCompte = { dialogue = Dialogue.CopierVersCompte },
                    onPartager = viewModel::partagerLot,
                    onSupprimer = { dialogue = Dialogue.SupprimerLot },
                )
            } else {
                TopAppBar(
                    title = {
                        Text(
                            text = etat.titre,
                            style = StyleTitrePrincipal,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
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
                        } else {
                            IconButton(onClick = onOuvrirMenu) {
                                Icon(
                                    imageVector = Icons.AutoMirrored.Filled.MenuOpen,
                                    contentDescription = stringResource(R.string.menu_ouvrir_navigation),
                                )
                            }
                        }
                    },
                    // Deux actions, pas quatre : la recherche a sa propre ligne,
                    // et les réglages vivent dans le tiroir. Ce qui reste ici est
                    // ce qui agit sur la liste affichée.
                    actions = {
                        BoutonTri(tri = etat.tri, onChanger = viewModel::changerTri)
                        if (!etat.modeLocal) {
                            IndicateurSynchronisation(
                                enCours = etat.synchronisationEnCours,
                                enAttente = etat.enAttente,
                                attention = etat.depuisCache || etat.erreur != null,
                                modifier = Modifier.padding(start = 6.dp, end = 2.dp),
                            )
                        }
                        // En mode local il n'y a personne à qui pousser : le
                        // geste ne relit que le disque, et le dire autrement
                        // laisserait croire à une synchronisation qui n'existe
                        // pas.
                        IconButton(
                            onClick = if (etat.modeLocal) viewModel::recharger else viewModel::rafraichir,
                        ) {
                            Icon(
                                imageVector = Icons.Default.Refresh,
                                contentDescription = stringResource(
                                    if (etat.modeLocal) {
                                        R.string.browser_rafraichir_local
                                    } else {
                                        R.string.browser_rafraichir
                                    },
                                ),
                            )
                        }
                    },
                    // Une teinte de surface, pas la couleur de fond brute : la
                    // barre se détache légèrement du contenu sans devenir un
                    // bandeau de couleur.
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.surfaceContainer,
                    ),
                )
            }
        },
        floatingActionButton = {
            // Rien à créer pendant une sélection : la barre contextuelle a la
            // main, le bouton d'ajout n'y a pas sa place.
            if (!etat.modeSelection && (etat.peutCreerNote || etat.peutCreerDossier)) {
                Column(
                    horizontalAlignment = Alignment.End,
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    if (etat.peutCreerDossier) {
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
                    }
                    if (etat.peutCreerNote) {
                        ExtendedFloatingActionButton(
                            onClick = { dialogue = Dialogue.NouvelleNote },
                            icon = { Icon(Icons.Default.Add, null) },
                            text = { Text(stringResource(R.string.browser_nouvelle_note)) },
                        )
                    }
                }
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

            etat.seuilDepasse?.let { octets ->
                Bandeau(
                    texte = stringResource(
                        R.string.browser_seuil_depasse,
                        Formatter.formatShortFileSize(context, octets),
                    ),
                    icone = Icons.Default.Storage,
                    couleurFond = MaterialTheme.colorScheme.tertiaryContainer,
                    couleurTexte = MaterialTheme.colorScheme.onTertiaryContainer,
                )
            }

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
                    // Une requête trop courte n'a fouillé que les noms : le
                    // message ne doit pas prétendre davantage.
                    detail = stringResource(
                        if (etat.rechercheTexte != null) {
                            R.string.browser_recherche_vide_detail_texte
                        } else {
                            R.string.browser_recherche_vide_detail
                        },
                    ),
                )

                else -> ListeEntrees(
                    entrees = etat.entreesAffichees,
                    extraits = etat.repartition.extraits,
                    dansLeTexte = etat.repartition.dansLeTexte,
                    rechercheTexte = etat.rechercheTexte,
                    rechercheTexteEnCours = etat.rechercheTexteEnCours,
                    // En arborescence, le chemin d'un résultat se lit depuis
                    // le dossier affiché ; en liste plate, depuis la racine.
                    dossierAffiche = etat.porteeRecherche,
                    onOuvrirResultat = viewModel::ouvrirResultat,
                    grouperParMois = etat.enListePlate && etat.tri == Tri.DATE,
                    afficherDossier = etat.enListePlate,
                    selection = etat.selection,
                    modeSelection = etat.modeSelection,
                    peutRecevoirNote = etat.peutRecevoirNote,
                    plusieursComptes = plusieursComptes,
                    onOuvrir = viewModel::ouvrir,
                    onBasculer = viewModel::basculerSelection,
                    onRenommer = { dialogue = Dialogue.Renommer(it) },
                    onDeplacer = { dialogue = Dialogue.Deplacer(it) },
                    onCopier = { dialogue = Dialogue.Copier(it) },
                    onCopierCompte = { dialogue = Dialogue.CopierUneVersCompte(it) },
                    onPartager = { viewModel.partager(it) },
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

        Dialogue.NouveauDossier -> if (etat.enListePlate) {
            NouveauDossierDialog(
                dossiers = etat.dossiers,
                nomRacine = etat.nomRacine,
                onValider = viewModel::creerDossier,
                onFermer = { dialogue = null },
            )
        } else {
            SaisieDialog(
                titre = stringResource(R.string.browser_nouveau_dossier),
                label = stringResource(R.string.browser_dossier_label),
                valeurInitiale = "",
                libelleValidation = stringResource(R.string.action_creer),
                onValider = { nom -> viewModel.creerDossier(nom, etat.cheminCourant) },
                onFermer = { dialogue = null },
            )
        }

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

        // Copie d'une seule note depuis son menu « ⋮ » : mêmes boîtes que la
        // copie groupée, mais la note est portée par le dialogue, pas par la
        // sélection.
        is Dialogue.Copier -> DossierCibleLotDialog(
            titre = stringResource(R.string.browser_copier_titre, d.entree.display),
            libelleAction = stringResource(R.string.action_copier),
            dossiers = etat.dossiers,
            nomRacine = etat.nomRacine,
            onValider = { dossier -> viewModel.copier(d.entree, dossier) },
            onFermer = { dialogue = null },
        )

        is Dialogue.CopierUneVersCompte -> CopieVersCompteDialog(
            nombre = 1,
            onValider = { destination, dossier, nom ->
                viewModel.copierVersCompte(d.entree, destination.profil.id, dossier, nom)
            },
            onFermer = { dialogue = null },
        )

        is Dialogue.Supprimer -> SuppressionDialog(
            nomAffiche = d.entree.display,
            estDossier = d.entree.isDir,
            modeLocal = etat.modeLocal,
            onConfirmer = { viewModel.supprimer(d.entree) },
            onFermer = { dialogue = null },
        )

        Dialogue.DeplacerLot -> DossierCibleLotDialog(
            titre = pluralStringResource(
                R.plurals.browser_deplacer_lot_titre,
                etat.selection.size,
                etat.selection.size,
            ),
            libelleAction = stringResource(R.string.action_deplacer),
            dossiers = etat.dossiers,
            nomRacine = etat.nomRacine,
            onValider = viewModel::deplacerLot,
            onFermer = { dialogue = null },
        )

        Dialogue.CopierLot -> DossierCibleLotDialog(
            titre = pluralStringResource(
                R.plurals.browser_copier_lot_titre,
                etat.selection.size,
                etat.selection.size,
            ),
            libelleAction = stringResource(R.string.action_copier),
            dossiers = etat.dossiers,
            nomRacine = etat.nomRacine,
            onValider = viewModel::copierLot,
            onFermer = { dialogue = null },
        )

        Dialogue.CopierVersCompte -> CopieVersCompteDialog(
            nombre = etat.selection.size,
            onValider = { destination, dossier, nom ->
                viewModel.copierVersCompte(destination.profil.id, dossier, nom)
            },
            onFermer = { dialogue = null },
        )

        Dialogue.SupprimerLot -> SuppressionLotDialog(
            nombre = etat.selection.size,
            contientDossier = etat.selectionContientDossier,
            modeLocal = etat.modeLocal,
            onConfirmer = viewModel::supprimerLot,
            onFermer = { dialogue = null },
        )
    }
}

/**
 * Barre de titre contextuelle affichée pendant une sélection multiple.
 *
 * La croix vide la sélection — même geste que le retour système. « Déplacer »
 * disparaît dès qu'un dossier est coché, « Copier » et « Partager » dès qu'un
 * dossier ou un document l'est : ces gestes groupés ne valent que pour des
 * notes. La suppression, elle, reste toujours proposée.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun BarreSelection(
    nombre: Int,
    peutDeplacer: Boolean,
    peutCopier: Boolean,
    peutCopierCompte: Boolean,
    peutPartager: Boolean,
    peutSupprimer: Boolean,
    onQuitter: () -> Unit,
    onDeplacer: () -> Unit,
    onCopier: () -> Unit,
    onCopierCompte: () -> Unit,
    onPartager: () -> Unit,
    onSupprimer: () -> Unit,
) {
    TopAppBar(
        title = {
            Text(pluralStringResource(R.plurals.browser_selection_compte, nombre, nombre))
        },
        navigationIcon = {
            IconButton(onClick = onQuitter) {
                Icon(
                    imageVector = Icons.Default.Close,
                    contentDescription = stringResource(R.string.browser_selection_quitter),
                )
            }
        },
        actions = {
            if (peutDeplacer) {
                IconButton(onClick = onDeplacer) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.DriveFileMove,
                        contentDescription = stringResource(R.string.action_deplacer),
                    )
                }
            }
            if (peutCopier) {
                IconButton(onClick = onCopier) {
                    Icon(
                        imageVector = Icons.Default.ContentCopy,
                        contentDescription = stringResource(R.string.action_copier),
                    )
                }
            }
            if (peutCopierCompte) {
                IconButton(onClick = onCopierCompte) {
                    Icon(
                        imageVector = Icons.Default.SwitchAccount,
                        contentDescription = stringResource(R.string.browser_copier_compte_titre),
                    )
                }
            }
            if (peutPartager) {
                IconButton(onClick = onPartager) {
                    Icon(
                        imageVector = Icons.Default.Share,
                        contentDescription = stringResource(R.string.action_partager),
                    )
                }
            }
            if (peutSupprimer) {
                IconButton(onClick = onSupprimer) {
                    Icon(
                        imageVector = Icons.Default.Delete,
                        contentDescription = stringResource(R.string.action_supprimer),
                    )
                }
            }
        },
        // Teinte distincte de la barre normale : le mode sélection doit se
        // reconnaître d'un coup d'œil, pas seulement à son contenu.
        colors = TopAppBarDefaults.topAppBarColors(
            containerColor = MaterialTheme.colorScheme.secondaryContainer,
            titleContentColor = MaterialTheme.colorScheme.onSecondaryContainer,
            navigationIconContentColor = MaterialTheme.colorScheme.onSecondaryContainer,
            actionIconContentColor = MaterialTheme.colorScheme.onSecondaryContainer,
        ),
    )
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
    extraits: Map<String, ResultatTexteDto>,
    dansLeTexte: List<ResultatTexteDto>,
    rechercheTexte: RechercheTexteDto?,
    rechercheTexteEnCours: Boolean,
    dossierAffiche: String,
    onOuvrirResultat: (ResultatTexteDto) -> Unit,
    grouperParMois: Boolean,
    afficherDossier: Boolean,
    selection: Set<String>,
    modeSelection: Boolean,
    peutRecevoirNote: Boolean,
    plusieursComptes: Boolean,
    onOuvrir: (FolderEntryDto) -> Unit,
    onBasculer: (FolderEntryDto) -> Unit,
    onRenommer: (FolderEntryDto) -> Unit,
    onDeplacer: (FolderEntryDto) -> Unit,
    onCopier: (FolderEntryDto) -> Unit,
    onCopierCompte: (FolderEntryDto) -> Unit,
    onPartager: (FolderEntryDto) -> Unit,
    onSupprimer: (FolderEntryDto) -> Unit,
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        // Les deux boutons de création flottent au-dessus du contenu. Cette
        // réserve permet de faire remonter la dernière ligne entièrement.
        contentPadding = PaddingValues(bottom = 156.dp),
    ) {
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
                    extrait = extraits[entree.path],
                    afficherDossier = afficherDossier,
                    selectionnee = entree.path in selection,
                    modeSelection = modeSelection,
                    onClick = { onOuvrir(entree) },
                    onBasculer = { onBasculer(entree) },
                    onRenommer = if (entree.canRename) ({ onRenommer(entree) }) else null,
                    onDeplacer = if (entree.isDir || !entree.canMove || !peutRecevoirNote) {
                        null
                    } else {
                        ({ onDeplacer(entree) })
                    },
                    // Copie : une note modifiable seulement, comme en
                    // sélection — un dossier n'est pas copiable, et la façade
                    // refuse les octets d'un document en lecture seule.
                    onCopier = if (entree.isDir || entree.readOnly || !peutRecevoirNote) {
                        null
                    } else {
                        ({ onCopier(entree) })
                    },
                    // Vers un autre profil : c'est la cible qui reçoit, le
                    // dossier courant n'a pas à accepter de note.
                    onCopierCompte = if (entree.isDir || entree.readOnly || !plusieursComptes) {
                        null
                    } else {
                        ({ onCopierCompte(entree) })
                    },
                    // Partage réservé aux fichiers : un dossier n'a pas de
                    // contenu à joindre. Un document `.docx`/`.odt` part tel
                    // quel, recopié par le cœur Go.
                    onPartager = if (entree.isDir) {
                        null
                    } else {
                        ({ onPartager(entree) })
                    },
                    onSupprimer = if (entree.canDelete) ({ onSupprimer(entree) }) else null,
                )
            }
        }

        // Section « Dans le texte » : sous les noms, qui restent instantanés.
        // Son titre paraît dès que Go fouille, pour que l'attente se voie.
        if (rechercheTexteEnCours || dansLeTexte.isNotEmpty()) {
            item(key = "recherche-texte-titre") {
                TitreSectionTexte(enCours = rechercheTexteEnCours)
            }
        }
        dansLeTexte.forEach { resultat ->
            // Préfixe dans la clé : la même note peut figurer plus haut par
            // son nom dans un autre état, et deux clés égales planteraient.
            item(key = "texte:${resultat.path}") {
                LigneResultatTexte(
                    resultat = resultat,
                    dossierAffiche = dossierAffiche,
                    onClick = { onOuvrirResultat(resultat) },
                )
            }
        }
        rechercheTexte?.takeIf { it.truncated }?.let { reponse ->
            item(key = "recherche-texte-tronquee") {
                NoteRecherche(
                    pluralStringResource(
                        R.plurals.browser_recherche_tronquee,
                        reponse.results.size,
                        reponse.results.size,
                    ),
                )
            }
        }
        rechercheTexte?.takeIf { it.skipped > 0 }?.let { reponse ->
            item(key = "recherche-texte-non-fouillees") {
                NoteRecherche(
                    pluralStringResource(
                        R.plurals.browser_recherche_non_fouillees,
                        reponse.skipped,
                        reponse.skipped,
                    ),
                )
            }
        }
    }
}

/** Titre de la section « Dans le texte », avec un indicateur pendant la fouille. */
@Composable
private fun TitreSectionTexte(enCours: Boolean) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 4.dp),
    ) {
        Text(
            text = stringResource(R.string.browser_recherche_dans_le_texte),
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.primary,
        )
        if (enCours) {
            CircularProgressIndicator(
                strokeWidth = 2.dp,
                modifier = Modifier.size(14.dp),
            )
        }
    }
}

/** Ligne discrète sous les résultats : liste tronquée, notes non fouillées. */
@Composable
private fun NoteRecherche(texte: String) {
    Text(
        text = texte,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
    )
}

/**
 * Une note trouvée par son texte, hors de la liste des noms.
 *
 * Elle s'ouvre d'un appui et ne se coche pas : la sélection multiple ne porte
 * que sur les entrées du dossier affiché (v1). Un résultat d'un sous-dossier
 * montre son chemin depuis le dossier affiché.
 */
@Composable
private fun LigneResultatTexte(
    resultat: ResultatTexteDto,
    dossierAffiche: String,
    onClick: () -> Unit,
) {
    val dossier = resultat.path.substringBeforeLast('/', "")
    val chemin = when {
        dossier == dossierAffiche -> null
        dossierAffiche.isEmpty() -> dossier
        else -> dossier.removePrefix("$dossierAffiche/")
    }
    ListItem(
        overlineContent = chemin?.let { { Text(it, maxLines = 1, overflow = TextOverflow.Ellipsis) } },
        headlineContent = {
            Text(resultat.display, maxLines = 1, overflow = TextOverflow.Ellipsis)
        },
        supportingContent = { ExtraitTexte(resultat) },
        leadingContent = {
            Surface(
                shape = CircleShape,
                color = MaterialTheme.colorScheme.primaryContainer,
                modifier = Modifier.size(40.dp),
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = Icons.Default.EditNote,
                        contentDescription = stringResource(R.string.browser_type_note),
                        tint = MaterialTheme.colorScheme.onPrimaryContainer,
                    )
                }
            }
        },
        modifier = Modifier.clickable(onClick = onClick),
    )
}

/**
 * L'extrait d'une note trouvée, le terme en gras, et le nombre d'occurrences
 * quand il y en a plusieurs.
 *
 * Les bornes viennent de Go en unités UTF-16, celles de `AnnotatedString` :
 * aucune conversion. Elles sont tout de même vérifiées — un gras hors du texte
 * ferait planter le dessin, l'extrait seul vaut mieux.
 */
@Composable
private fun ExtraitTexte(resultat: ResultatTexteDto) {
    val extrait = remember(resultat) {
        buildAnnotatedString {
            append(resultat.excerpt)
            if (resultat.start in 0 until resultat.end && resultat.end <= resultat.excerpt.length) {
                addStyle(SpanStyle(fontWeight = FontWeight.Bold), resultat.start, resultat.end)
            }
        }
    }
    Column {
        Text(
            text = extrait,
            style = MaterialTheme.typography.bodySmall,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        if (resultat.count > 1) {
            Text(
                text = pluralStringResource(
                    R.plurals.browser_recherche_occurrences,
                    resultat.count,
                    resultat.count,
                ),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun SeparateurMois(mois: YearMonth) {
    val locale = LocalConfiguration.current.locales[0]
    val couleur = if (isSystemInDarkTheme()) CouleurSignatureSombre else CouleurSignatureClaire
    val libelle = remember(mois, locale) {
        mois.atDay(1)
            .format(DateTimeFormatter.ofPattern("LLLL yyyy", locale)) // i18n-ok
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
        // Champ tonal plutôt qu'un simple contour : plus proche d'un bouton de
        // recherche que d'un formulaire à remplir.
        colors = OutlinedTextFieldDefaults.colors(
            focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            unfocusedBorderColor = Color.Transparent,
            focusedBorderColor = MaterialTheme.colorScheme.primary,
        ),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
    )
}

/** Menu de tri explicite : la coche montre l'ordre actuellement appliqué. */
@Composable
private fun BoutonTri(tri: Tri, onChanger: () -> Unit) {
    var ouvert by remember { mutableStateOf(false) }

    Box {
        IconButton(onClick = { ouvert = true }) {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.Sort,
                contentDescription = stringResource(R.string.browser_tri_menu),
            )
        }
        DropdownMenu(expanded = ouvert, onDismissRequest = { ouvert = false }) {
            Tri.entries.forEach { option ->
                DropdownMenuItem(
                    text = {
                        Text(
                            stringResource(
                                when (option) {
                                    Tri.NOM -> R.string.browser_tri_par_nom
                                    Tri.DATE -> R.string.browser_tri_par_date
                                },
                            ),
                        )
                    },
                    trailingIcon = {
                        if (option == tri) {
                            Icon(Icons.Default.Check, contentDescription = null)
                        }
                    },
                    onClick = {
                        ouvert = false
                        if (option != tri) onChanger()
                    },
                )
            }
        }
    }
}

/** État compact de la synchronisation, accolé au nom du dossier courant. */
@Composable
private fun IndicateurSynchronisation(
    enCours: Boolean,
    enAttente: Int,
    attention: Boolean,
    modifier: Modifier = Modifier,
) {
    val orange = if (isSystemInDarkTheme()) Color(0xFFFFB45C) else Color(0xFFC75B00)
    val vert = if (isSystemInDarkTheme()) Color(0xFF6DD58C) else Color(0xFF19753A)
    // On garde l'état de l'animation sans le lire ici : sa valeur n'est lue
    // que dans `graphicsLayer`, au dessin. Lue pendant la composition, elle
    // recomposait la pastille à chaque image pendant toute la synchro.
    val pulsation: State<Float>? = if (enCours) {
        val transition = rememberInfiniteTransition(label = "synchronisation")
        transition.animateFloat(
            initialValue = 0.42f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(
                animation = tween(durationMillis = 700),
                repeatMode = RepeatMode.Reverse,
            ),
            label = "pulsation-synchronisation",
        )
    } else {
        null
    }
    val description = when {
        enCours -> stringResource(R.string.browser_sync_en_cours)
        enAttente > 0 -> pluralStringResource(
            R.plurals.browser_sync_en_attente,
            enAttente,
            enAttente,
        )
        attention -> stringResource(R.string.browser_sync_attention)
        else -> stringResource(R.string.browser_sync_ok)
    }

    Box(
        modifier = modifier
            .size(10.dp)
            .then(
                if (pulsation != null) {
                    // Même rendu que `scale(...).alpha(...)` : une seule couche,
                    // découpée à ses bords comme le fait `alpha`.
                    Modifier.graphicsLayer {
                        val valeur = pulsation.value
                        val echelle = 0.85f + valeur * 0.15f
                        scaleX = echelle
                        scaleY = echelle
                        alpha = valeur
                        clip = true
                    }
                } else {
                    Modifier
                },
            )
            .background(if (enCours || enAttente > 0 || attention) orange else vert, CircleShape)
            .semantics { contentDescription = description },
    )
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
private fun LigneEntree(
    entree: FolderEntryDto,
    // Extrait trouvé dans le texte de cette note, si la recherche en a un.
    extrait: ResultatTexteDto?,
    afficherDossier: Boolean,
    selectionnee: Boolean,
    modeSelection: Boolean,
    onClick: () -> Unit,
    onBasculer: () -> Unit,
    onRenommer: (() -> Unit)?,
    // `null` retire l'action du menu plutôt que de l'y laisser inerte —
    // c'est le cas d'un dossier, que DeplacerDialog ne couvre pas.
    onDeplacer: (() -> Unit)?,
    // `null` pour un dossier ou un document en lecture seule.
    onCopier: (() -> Unit)?,
    // `null` aussi quand il n'existe pas d'autre profil.
    onCopierCompte: (() -> Unit)?,
    // `null` pour un dossier : le partage ne joint que des fichiers.
    onPartager: (() -> Unit)?,
    onSupprimer: (() -> Unit)?,
) {
    var menuOuvert by remember { mutableStateOf(false) }
    val aDesActions = onRenommer != null || onDeplacer != null || onCopier != null ||
        onCopierCompte != null || onPartager != null || onSupprimer != null

    ListItem(
        headlineContent = {
            Text(entree.display, maxLines = 1, overflow = TextOverflow.Ellipsis)
        },
        supportingContent = {
            Column {
                sousTitre(entree, afficherDossier)?.let { texte ->
                    Text(
                        text = texte,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                // Une note déjà listée par son nom n'est pas répétée dans la
                // section « Dans le texte » : elle y gagne son extrait.
                extrait?.let { ExtraitTexte(it) }
            }
        },
        leadingContent = {
            // En sélection, la case remplace l'icône de type : le geste en
            // cours est de cocher, pas de reconnaître un format.
            if (modeSelection) {
                Checkbox(checked = selectionnee, onCheckedChange = null)
            } else {
                // Un document en lecture seule ne se distingue pas par la seule teinte : une couleur
                // ne se voit ni en daltonisme, ni sous un écran au soleil, ni pour
                // TalkBack. L'icône et la description changent donc avec elle.
                //
                // Le format se demande au cœur, qui l'a mis dans le listing : la
                // liste des extensions reconnues ne vit qu'à un endroit.
                val document = !entree.isDir && entree.readOnly

                // Un disque tonal derrière l'icône, plutôt qu'un pictogramme nu :
                // la liste gagne un point d'ancrage visuel par ligne, sans rien
                // ajouter au message porté par l'icône elle-même.
                Surface(
                    shape = CircleShape,
                    color = if (document) {
                        MaterialTheme.colorScheme.tertiaryContainer
                    } else {
                        MaterialTheme.colorScheme.primaryContainer
                    },
                    modifier = Modifier.size(40.dp),
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            imageVector = when {
                                entree.isDir -> Icons.Default.Folder
                                document -> Icons.AutoMirrored.Filled.Article
                                else -> Icons.Default.EditNote
                            },
                            contentDescription = stringResource(
                                when {
                                    entree.isDir -> R.string.browser_type_dossier
                                    document -> R.string.browser_type_document
                                    else -> R.string.browser_type_note
                                },
                            ),
                            tint = if (document) {
                                MaterialTheme.colorScheme.onTertiaryContainer
                            } else {
                                MaterialTheme.colorScheme.onPrimaryContainer
                            },
                        )
                    }
                }
            }
        },
        trailingContent = {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                // Pastille : modification locale pas encore poussée.
                if (entree.pending) PastilleEnAttente()

                // Le menu par ligne n'a pas de sens pendant une sélection
                // multiple : les actions passent alors par la barre contextuelle.
                if (!modeSelection && aDesActions) {
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
                        onRenommer?.let { renommer ->
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.action_renommer)) },
                                onClick = {
                                    menuOuvert = false
                                    renommer()
                                },
                            )
                        }
                        onDeplacer?.let { deplacer ->
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.action_deplacer)) },
                                onClick = {
                                    menuOuvert = false
                                    deplacer()
                                },
                            )
                        }
                        onCopier?.let { copier ->
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.action_copier)) },
                                onClick = {
                                    menuOuvert = false
                                    copier()
                                },
                            )
                        }
                        onCopierCompte?.let { copierCompte ->
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.browser_copier_compte_titre)) },
                                onClick = {
                                    menuOuvert = false
                                    copierCompte()
                                },
                            )
                        }
                        onPartager?.let { partager ->
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.action_partager)) },
                                onClick = {
                                    menuOuvert = false
                                    partager()
                                },
                            )
                        }
                        onSupprimer?.let { supprimer ->
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.action_supprimer)) },
                                onClick = {
                                    menuOuvert = false
                                    supprimer()
                                },
                            )
                        }
                    }
                }
            }
        },
        colors = ListItemDefaults.colors(
            containerColor = if (selectionnee) {
                MaterialTheme.colorScheme.secondaryContainer
            } else {
                MaterialTheme.colorScheme.surface
            },
        ),
        // Appui long : entre en sélection et coche la ligne. En sélection, un
        // appui simple coche aussi — c'est le geste attendu, ouvrir viendrait
        // en travers.
        modifier = Modifier.combinedClickable(
            onClick = { if (modeSelection) onBasculer() else onClick() },
            onLongClick = onBasculer,
            onLongClickLabel = stringResource(R.string.browser_selection_demarrer),
        ),
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
