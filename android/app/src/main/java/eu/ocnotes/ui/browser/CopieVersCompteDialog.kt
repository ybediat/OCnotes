package eu.ocnotes.ui.browser

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import eu.ocnotes.R
import eu.ocnotes.appContainer
import eu.ocnotes.data.DestinationCopie
import eu.ocnotes.ui.common.nomCompte

/**
 * Choix du profil et du dossier où copier la sélection.
 *
 * Les destinations sont lues dans le cœur Go de chaque autre profil : le temps
 * de les lire, un indicateur remplace la liste. Rien n'est supprimé à la
 * source — copier ne déplace pas.
 */
@Composable
fun CopieVersCompteDialog(
    nombre: Int,
    onValider: (destination: DestinationCopie, dossier: String, nomCompte: String) -> Unit,
    onFermer: () -> Unit,
) {
    val container = LocalContext.current.appContainer
    var destinations by remember { mutableStateOf<List<DestinationCopie>?>(null) }
    var choisie by remember { mutableStateOf<DestinationCopie?>(null) }
    var dossier by remember { mutableStateOf("") }

    LaunchedEffect(Unit) {
        val lues = container.destinationsCopie()
        destinations = lues
        choisie = lues.firstOrNull()
        dossier = choisie?.dossiers?.firstOrNull()?.path.orEmpty()
    }

    val nomChoisi = choisie?.profil?.let { nomCompte(it) }.orEmpty()

    AlertDialog(
        onDismissRequest = onFermer,
        title = { Text(stringResource(R.string.browser_copier_compte_titre)) },
        text = {
            val liste = destinations
            when {
                liste == null -> CircularProgressIndicator()
                liste.isEmpty() -> Text(stringResource(R.string.browser_copier_compte_aucun))
                else -> Column {
                    liste.forEach { destination ->
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .selectable(
                                    selected = destination == choisie,
                                    role = Role.RadioButton,
                                    onClick = {
                                        choisie = destination
                                        dossier = destination.dossiers.firstOrNull()?.path.orEmpty()
                                    },
                                ),
                        ) {
                            RadioButton(selected = destination == choisie, onClick = null)
                            Text(
                                text = nomCompte(destination.profil),
                                modifier = Modifier.padding(start = 8.dp),
                            )
                        }
                    }
                    choisie?.let { destination ->
                        SelecteurDossier(
                            dossiers = destination.dossiers,
                            nomRacine = nomChoisi,
                            valeur = dossier,
                            onValeur = { dossier = it },
                            label = stringResource(R.string.browser_note_dossier),
                            modifier = Modifier.padding(top = 12.dp),
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    choisie?.let { onValider(it, dossier, nomChoisi) }
                    onFermer()
                },
                enabled = choisie != null,
            ) {
                Text(stringResource(R.string.action_copier))
            }
        },
        dismissButton = {
            TextButton(onClick = onFermer) { Text(stringResource(R.string.action_annuler)) }
        },
    )
}
