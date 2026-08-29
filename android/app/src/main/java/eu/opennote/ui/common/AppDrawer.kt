package eu.opennote.ui.common

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.DrawerState
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.NavigationDrawerItemDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import eu.opennote.R
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

    ModalNavigationDrawer(
        drawerState = etatTiroir,
        gesturesEnabled = gestesActifs,
        drawerContent = {
            ModalDrawerSheet {
                Text(
                    text = stringResource(R.string.app_name),
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.padding(horizontal = 28.dp, vertical = 20.dp),
                )
                HorizontalDivider()

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
}
