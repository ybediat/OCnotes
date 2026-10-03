package eu.ocnotes.ui.common

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DrawerState
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.NavigationDrawerItemDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import eu.ocnotes.R
import eu.ocnotes.appContainer
import eu.ocnotes.data.AccountProfile
import eu.ocnotes.data.AuthMode
import eu.ocnotes.ui.browser.ModeAffichage
import eu.ocnotes.ui.theme.CouleurSignatureClaire
import eu.ocnotes.ui.theme.CouleurSignatureSombre
import eu.ocnotes.ui.theme.StyleTitrePrincipal
import kotlinx.coroutines.launch

/**
 * Menu latéral de l'application, ouvert par un glissement depuis la gauche.
 *
 * # Portée
 *
 * Il enveloppe le `NavHost` entier, pas un écran. Son contenu reste commun,
 * mais le geste est activé par l'appelant seulement sur les destinations où il
 * ne concurrence pas une surface de saisie.
 *
 * # Le geste, et sa limite
 *
 * Material3 attache le glissement à toute la surface, pas seulement au bord.
 * Il reste donc désactivé dans l'éditeur : une faible dérive horizontale d'un
 * défilement vertical ne doit jamais commencer à ouvrir le tiroir et retirer
 * le flux tactile au champ natif.
 *
 * `gestesActifs` sert à éteindre le geste là où le tiroir n'a rien à offrir —
 * la connexion, le choix d'espace — plutôt que d'ouvrir un menu inerte.
 *
 * # Le choix du mode d'affichage vit ici, pas dans les réglages
 *
 * Basculer entre l'arborescence et la liste plate n'est pas une configuration
 * qu'on pose une fois : c'est deux façons de regarder la même bibliothèque, et
 * on passe de l'une à l'autre selon ce qu'on cherche. Un réglage enfoui à deux
 * écrans de là rendrait le second mode inutilisable en pratique.
 *
 * Le tiroir lit la préférence partagée plutôt que de traverser un ViewModel :
 * il enveloppe le NavHost entier et ne sait pas quel écran est affiché
 * dessous. Le navigateur observe le même flux et se recharge tout seul.
 */
@Composable
fun TiroirApplication(
    etatTiroir: DrawerState,
    gestesActifs: Boolean,
    onReglages: () -> Unit,
    onCompte: (String) -> Unit,
    onAjouterCompte: () -> Unit,
    onSupprimerCompte: (String) -> Unit,
    content: @Composable () -> Unit,
) {
    val portee = rememberCoroutineScope()
    val fermer: () -> Unit = { portee.launch { etatTiroir.close() } }
    var aProposOuvert by rememberSaveable { mutableStateOf(false) }
    var compteASupprimer by remember { mutableStateOf<AccountProfile?>(null) }
    val couleurTitre = if (isSystemInDarkTheme()) CouleurSignatureSombre else CouleurSignatureClaire
    val container = LocalContext.current.appContainer
    val comptes by container.accountRegistry.state.collectAsStateWithLifecycle()

    ModalNavigationDrawer(
        drawerState = etatTiroir,
        gesturesEnabled = gestesActifs,
        drawerContent = {
            ModalDrawerSheet {
                Text(
                    text = stringResource(R.string.app_name),
                    style = StyleTitrePrincipal,
                    color = couleurTitre,
                    modifier = Modifier.padding(horizontal = 28.dp, vertical = 20.dp),
                )
                HorizontalDivider()

                Text(
                    text = stringResource(R.string.menu_comptes),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 28.dp, vertical = 12.dp),
                )

                comptes.accounts.forEach { compte ->
                    val nom = nomCompte(compte)
                    NavigationDrawerItem(
                        icon = { Icon(Icons.Default.AccountCircle, contentDescription = null) },
                        label = {
                            Column {
                                Text(nom)
                                val adresse = adresseServeur(compte)
                                if (adresse != null && adresse != nom) {
                                    Text(
                                        adresse,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                        },
                        selected = compte.id == comptes.active.id,
                        badge = {
                            IconButton(onClick = { compteASupprimer = compte }) {
                                Icon(
                                    Icons.Default.Delete,
                                    contentDescription = stringResource(R.string.compte_supprimer),
                                )
                            }
                        },
                        onClick = {
                            fermer()
                            onCompte(compte.id)
                        },
                        modifier = Modifier.padding(NavigationDrawerItemDefaults.ItemPadding),
                    )
                }

                NavigationDrawerItem(
                    icon = { Icon(Icons.Default.Add, contentDescription = null) },
                    label = { Text(stringResource(R.string.compte_ajouter)) },
                    selected = false,
                    onClick = {
                        fermer()
                        onAjouterCompte()
                    },
                    modifier = Modifier.padding(NavigationDrawerItemDefaults.ItemPadding),
                )

                HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

                val preferences = container.preferencesAffichage
                val mode by preferences.mode.collectAsStateWithLifecycle()
                val modeCourant = ModeAffichage.depuis(mode)

                Text(
                    text = stringResource(R.string.menu_affichage),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 28.dp, vertical = 12.dp),
                )

                NavigationDrawerItem(
                    icon = { Icon(Icons.Default.FolderOpen, contentDescription = null) },
                    label = { Text(stringResource(R.string.menu_mode_arborescence)) },
                    selected = modeCourant == ModeAffichage.ARBORESCENCE,
                    onClick = {
                        preferences.definirMode(ModeAffichage.ARBORESCENCE.name)
                        fermer()
                    },
                    modifier = Modifier.padding(NavigationDrawerItemDefaults.ItemPadding),
                )

                NavigationDrawerItem(
                    icon = { Icon(Icons.AutoMirrored.Filled.List, contentDescription = null) },
                    label = { Text(stringResource(R.string.menu_mode_liste)) },
                    selected = modeCourant == ModeAffichage.LISTE,
                    onClick = {
                        preferences.definirMode(ModeAffichage.LISTE.name)
                        fermer()
                    },
                    modifier = Modifier.padding(NavigationDrawerItemDefaults.ItemPadding),
                )

                HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

                NavigationDrawerItem(
                    icon = { Icon(Icons.Default.Settings, contentDescription = null) },
                    label = { Text(stringResource(R.string.menu_reglages)) },
                    selected = false,
                    onClick = {
                        fermer()
                        onReglages()
                    },
                    modifier = Modifier.padding(NavigationDrawerItemDefaults.ItemPadding),
                )

                NavigationDrawerItem(
                    icon = { Icon(Icons.Default.Info, contentDescription = null) },
                    label = { Text(stringResource(R.string.menu_a_propos)) },
                    selected = false,
                    onClick = {
                        fermer()
                        aProposOuvert = true
                    },
                    modifier = Modifier.padding(NavigationDrawerItemDefaults.ItemPadding),
                )
            }
        },
    ) {
        Box {
            content()

            // Composé **après** le contenu, et c'est ce qui le fait marcher :
            // le dernier gestionnaire activé l'emporte, donc celui-ci passe
            // devant celui de l'éditeur tant que le tiroir est ouvert. Placé
            // avant, le retour arrière quitterait la note avec le menu encore
            // déployé par-dessus l'écran suivant.
            BackHandler(enabled = etatTiroir.isOpen, onBack = fermer)
        }
    }

    if (aProposOuvert) {
        AProposDialog(onFermer = { aProposOuvert = false })
    }

    compteASupprimer?.let { compte ->
        DialogueSuppressionCompte(
            compte = compte,
            onConfirme = {
                compteASupprimer = null
                fermer()
                onSupprimerCompte(compte.id)
            },
            onAnnule = { compteASupprimer = null },
        )
    }
}

/**
 * Confirmation de la suppression d'un profil.
 *
 * C'est le seul geste de l'application qui détruise des notes sans copie
 * ailleurs : celles d'un profil local, et les écritures d'un profil serveur que
 * la synchronisation n'a pas encore envoyées. Dans ces deux cas la case est
 * obligatoire. Une lecture du travail en attente qui échoue compte comme un
 * risque, pas comme son absence — il faut pouvoir supprimer un profil abîmé,
 * mais pas à l'aveugle.
 */
@Composable
private fun DialogueSuppressionCompte(
    compte: AccountProfile,
    onConfirme: () -> Unit,
    onAnnule: () -> Unit,
) {
    val container = LocalContext.current.appContainer
    val local = compte.kind == "local"
    // `null` tant que la lecture est en cours ou si elle a échoué.
    var enAttente by remember(compte.id) { mutableStateOf<Int?>(null) }
    var compris by remember(compte.id) { mutableStateOf(false) }
    LaunchedEffect(compte.id) { enAttente = container.operationsEnAttente(compte.id) }

    val perte = !local && enAttente?.let { it > 0 } == true
    val verrou = local || enAttente != 0

    AlertDialog(
        onDismissRequest = onAnnule,
        title = {
            Text(
                stringResource(
                    if (local || perte) {
                        R.string.compte_supprimer_perte_titre
                    } else {
                        R.string.compte_supprimer_titre
                    },
                ),
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                val attente = enAttente
                Text(
                    when {
                        local -> stringResource(R.string.compte_supprimer_local_message)
                        perte && attente != null -> pluralStringResource(
                            R.plurals.compte_supprimer_attente_message,
                            attente,
                            nomCompte(compte),
                            attente,
                        )
                        else -> stringResource(R.string.compte_supprimer_message, nomCompte(compte))
                    },
                )
                if (verrou) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.toggleable(
                            value = compris,
                            role = Role.Checkbox,
                            onValueChange = { compris = it },
                        ),
                    ) {
                        Checkbox(checked = compris, onCheckedChange = null)
                        Text(
                            text = stringResource(
                                if (local) {
                                    R.string.reglages_local_effacer_case
                                } else {
                                    R.string.reglages_deconnexion_perte_case
                                },
                            ),
                            modifier = Modifier.padding(start = 8.dp),
                        )
                    }
                }
            }
        },
        confirmButton = {
            val actif = !verrou || compris
            TextButton(onClick = onConfirme, enabled = actif) {
                Text(
                    stringResource(R.string.compte_supprimer),
                    color = if (actif) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
                    },
                )
            }
        },
        dismissButton = {
            TextButton(onClick = onAnnule) {
                Text(stringResource(R.string.action_annuler))
            }
        },
    )
}

/**
 * Titre d'un compte dans le tiroir. Jamais le `username` d'un compte OIDC :
 * c'est le subject, un identifiant opaque. Tant que le nom affiché manque —
 * il arrive avec la session suivante —, l'adresse du serveur en tient lieu.
 */
@Composable
private fun nomCompte(compte: AccountProfile): String = when {
    compte.kind == "local" -> stringResource(R.string.compte_local)
    compte.displayName.isNotBlank() -> compte.displayName
    compte.authMode != AuthMode.OIDC && compte.username.isNotBlank() -> compte.username
    else -> adresseServeur(compte) ?: stringResource(R.string.compte_nouveau)
}

/** L'hôte du serveur, sans le schéma : `https://` n'apprend rien ici. */
private fun adresseServeur(compte: AccountProfile): String? =
    compte.serverUrl.takeIf { it.isNotBlank() }?.substringAfter("://")?.trimEnd('/')

@Composable
private fun AProposDialog(onFermer: () -> Unit) {
    val context = LocalContext.current
    val uriHandler = LocalUriHandler.current
    val version = remember(context) {
        runCatching {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName
        }.getOrNull().orEmpty()
    }

    AlertDialog(
        onDismissRequest = onFermer,
        title = { Text(stringResource(R.string.a_propos_titre)) },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    text = stringResource(R.string.a_propos_description),
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(
                    text = stringResource(R.string.a_propos_signature, version),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 8.dp),
                )

                AProposSection(stringResource(R.string.a_propos_section_projet))
                AProposLink(
                    label = stringResource(R.string.a_propos_opencloud),
                    url = stringResource(R.string.a_propos_opencloud_url),
                    onOpen = uriHandler::openUri,
                )
                AProposLink(
                    label = stringResource(R.string.a_propos_depot),
                    url = stringResource(R.string.a_propos_depot_url),
                    onOpen = uriHandler::openUri,
                )
                AProposLink(
                    label = stringResource(R.string.a_propos_licence),
                    url = stringResource(R.string.a_propos_licence_url),
                    onOpen = uriHandler::openUri,
                )

                AProposSection(stringResource(R.string.a_propos_section_polices))
                AProposLink(
                    label = stringResource(R.string.a_propos_police_faune),
                    url = stringResource(R.string.a_propos_police_faune_url),
                    onOpen = uriHandler::openUri,
                )
                AProposLink(
                    label = stringResource(R.string.a_propos_police_lexend),
                    url = stringResource(R.string.a_propos_police_lexend_url),
                    onOpen = uriHandler::openUri,
                )
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onFermer) {
                Text(stringResource(R.string.action_fermer))
            }
        },
    )
}

@Composable
private fun AProposSection(label: String) {
    Text(
        text = label,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 8.dp, start = 12.dp),
    )
}

@Composable
private fun AProposLink(
    label: String,
    url: String,
    onOpen: (String) -> Unit,
) {
    TextButton(
        onClick = { onOpen(url) },
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.OpenInNew,
                contentDescription = null,
                modifier = Modifier.size(18.dp),
            )
            Text(label)
        }
    }
}
