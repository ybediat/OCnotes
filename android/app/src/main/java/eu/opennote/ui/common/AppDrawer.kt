package eu.opennote.ui.common

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DrawerState
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.NavigationDrawerItemDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import eu.opennote.R
import eu.opennote.appContainer
import eu.opennote.ui.browser.ModeAffichage
import eu.opennote.ui.theme.CouleurSignatureClaire
import eu.opennote.ui.theme.CouleurSignatureSombre
import kotlinx.coroutines.launch

/**
 * Menu latéral de l'application, ouvert par un glissement depuis la gauche.
 *
 * # Portée
 *
 * Il enveloppe le `NavHost` entier, pas un écran : le même geste et le même
 * contenu depuis le navigateur comme depuis l'éditeur. C'est ce qu'un tiroir
 * gauche annonce, et ça évite d'avoir à le reconstruire à chaque écran ajouté.
 *
 * # Le geste, et sa limite
 *
 * Material3 attache le glissement à toute la surface, pas seulement au bord.
 * En navigation par gestes, le bord gauche sert aussi au retour système : les
 * deux se disputent la même zone, et un glissement mou déclenchera parfois le
 * retour. C'est le comportement Android habituel ; si ça gêne à l'usage, la
 * correction passe par `setSystemGestureExclusionRects`, pas par ce fichier.
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
    content: @Composable () -> Unit,
) {
    val portee = rememberCoroutineScope()
    val fermer: () -> Unit = { portee.launch { etatTiroir.close() } }
    var aProposOuvert by rememberSaveable { mutableStateOf(false) }
    val couleurTitre = if (isSystemInDarkTheme()) CouleurSignatureSombre else CouleurSignatureClaire

    ModalNavigationDrawer(
        drawerState = etatTiroir,
        gesturesEnabled = gestesActifs,
        drawerContent = {
            ModalDrawerSheet {
                Text(
                    text = stringResource(R.string.app_name),
                    style = MaterialTheme.typography.titleLarge,
                    color = couleurTitre,
                    modifier = Modifier.padding(horizontal = 28.dp, vertical = 20.dp),
                )
                HorizontalDivider()

                val preferences = LocalContext.current.appContainer.preferencesAffichage
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
}

@Composable
private fun AProposDialog(onFermer: () -> Unit) {
    val uriHandler = LocalUriHandler.current
    val depot = stringResource(R.string.a_propos_depot_url)

    AlertDialog(
        onDismissRequest = onFermer,
        title = { Text(stringResource(R.string.a_propos_titre)) },
        text = {
            Text(
                listOf(
                    stringResource(R.string.a_propos_description),
                    stringResource(R.string.a_propos_licence),
                ).joinToString("\n\n"),
            )
        },
        confirmButton = {
            TextButton(onClick = { uriHandler.openUri(depot) }) {
                Text(stringResource(R.string.a_propos_depot))
            }
        },
        dismissButton = {
            TextButton(onClick = onFermer) {
                Text(stringResource(R.string.action_retour))
            }
        },
    )
}
