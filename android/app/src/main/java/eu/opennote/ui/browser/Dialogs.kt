package eu.opennote.ui.browser

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.TextRange
import eu.opennote.R

/**
 * Boîte de saisie d'une ligne : création de note, de dossier, renommage.
 *
 * Le texte initial arrive présélectionné, pour qu'un renommage puisse être
 * remplacé d'une frappe sans avoir à tout effacer.
 */
@Composable
fun SaisieDialog(
    titre: String,
    label: String,
    valeurInitiale: String,
    libelleValidation: String,
    aide: String? = null,
    onValider: (String) -> Unit,
    onFermer: () -> Unit,
) {
    var valeur by remember {
        mutableStateOf(
            TextFieldValue(valeurInitiale, TextRange(0, valeurInitiale.length)),
        )
    }

    AlertDialog(
        onDismissRequest = onFermer,
        title = { Text(titre) },
        text = {
            Column {
                OutlinedTextField(
                    value = valeur,
                    onValueChange = { valeur = it },
                    label = { Text(label) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                aide?.let {
                    Text(
                        text = it,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    onValider(valeur.text)
                    onFermer()
                },
                enabled = valeur.text.isNotBlank(),
            ) {
                Text(libelleValidation)
            }
        },
        dismissButton = {
            TextButton(onClick = onFermer) { Text(stringResource(R.string.action_annuler)) }
        },
    )
}

/**
 * Confirmation de suppression.
 *
 * Un dossier part avec tout ce qu'il contient : le dire explicitement, parce
 * que c'est la seule action de l'application qui détruise des données.
 */
@Composable
fun SuppressionDialog(
    nomAffiche: String,
    estDossier: Boolean,
    onConfirmer: () -> Unit,
    onFermer: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onFermer,
        title = { Text(stringResource(R.string.dialogue_supprimer_titre, nomAffiche)) },
        text = {
            Text(
                stringResource(
                    if (estDossier) {
                        R.string.dialogue_supprimer_dossier
                    } else {
                        R.string.dialogue_supprimer_note
                    },
                ),
            )
        },
        confirmButton = {
            TextButton(
                onClick = {
                    onConfirmer()
                    onFermer()
                },
            ) {
                Text(
                    text = stringResource(R.string.action_supprimer),
                    color = MaterialTheme.colorScheme.error,
                )
            }
        },
        dismissButton = {
            TextButton(onClick = onFermer) { Text(stringResource(R.string.action_annuler)) }
        },
    )
}
