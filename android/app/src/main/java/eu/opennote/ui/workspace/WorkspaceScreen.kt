package eu.opennote.ui.workspace

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.FolderShared
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import eu.opennote.R
import eu.opennote.appContainer
import eu.opennote.data.DriveDto
import eu.opennote.data.DriveType
import eu.opennote.ui.common.ChargementPleinEcran
import eu.opennote.ui.common.resoudre

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WorkspaceScreen(
    onEspaceChoisi: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: WorkspaceViewModel = viewModel(
        factory = WorkspaceViewModel.factory(LocalContext.current.appContainer),
    ),
) {
    val etat by viewModel.uiState.collectAsStateWithLifecycle()

    LaunchedEffect(etat.termine) {
        if (etat.termine) {
            viewModel.finConsommee()
            onEspaceChoisi()
        }
    }

    Scaffold(
        modifier = modifier,
        topBar = { TopAppBar(title = { Text(stringResource(R.string.espace_titre)) }) },
    ) { paddings ->
        if (etat.chargement) {
            ChargementPleinEcran(Modifier.padding(paddings))
            return@Scaffold
        }

        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddings),
        ) {
            item {
                Text(
                    text = stringResource(R.string.espace_stockage),
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.padding(start = 16.dp, top = 16.dp, bottom = 4.dp),
                )
            }

            items(etat.drives, key = { it.id }) { drive ->
                LigneEspace(
                    drive = drive,
                    selectionne = drive.id == etat.driveChoisi,
                    onClick = { viewModel.choisirDrive(drive) },
                )
            }

            item {
                HorizontalDivider(Modifier.padding(vertical = 16.dp))

                Column(
                    modifier = Modifier.padding(horizontal = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        text = stringResource(R.string.espace_dossier_titre),
                        style = MaterialTheme.typography.titleSmall,
                    )

                    OutlinedTextField(
                        value = etat.racine,
                        onValueChange = viewModel::onRacineChange,
                        label = { Text(stringResource(R.string.espace_chemin_label)) },
                        singleLine = true,
                        enabled = !etat.validation,
                        modifier = Modifier.fillMaxWidth(),
                    )

                    Text(
                        text = stringResource(R.string.espace_chemin_aide),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )

                    etat.erreur?.let { message ->
                        Text(
                            text = message.resoudre(),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }

                    Button(
                        onClick = viewModel::valider,
                        enabled = etat.peutValider,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 16.dp),
                    ) {
                        Text(
                            stringResource(
                                if (etat.validation) {
                                    R.string.espace_preparation
                                } else {
                                    R.string.espace_continuer
                                },
                            ),
                        )
                    }
                }
            }
        }
    }
}

/**
 * Une ligne d'espace.
 *
 * Un espace inutilisable reste visible mais grisé, non cliquable, et porte
 * l'explication de son inaptitude. C'est plus honnête que de le masquer :
 * l'utilisateur le voit dans son navigateur web et se demanderait où il est
 * passé.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LigneEspace(
    drive: DriveDto,
    selectionne: Boolean,
    onClick: () -> Unit,
) {
    val estompe = if (drive.usable) 1f else 0.45f

    ListItem(
        headlineContent = {
            Text(
                text = drive.name,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = estompe),
            )
        },
        supportingContent = {
            Text(
                text = explication(drive),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = estompe),
            )
        },
        leadingContent = {
            Icon(
                imageVector = if (drive.type == DriveType.VIRTUAL) {
                    Icons.Default.FolderShared
                } else {
                    Icons.Default.Cloud
                },
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = estompe),
            )
        },
        trailingContent = {
            if (drive.usable) {
                RadioButton(selected = selectionne, onClick = onClick)
            }
        },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        modifier = Modifier.clickable(enabled = drive.usable, onClick = onClick),
    )
}

/**
 * Pourquoi cet espace convient — ou non.
 *
 * Composable pour lire ses ressources. Le repli affiche le type brut reçu
 * de la façade : un type que cette version ne connaît pas n'a pas de
 * formulation, et le taire laisserait une ligne sans explication.
 */
@Composable
private fun explication(drive: DriveDto): String = when {
    !drive.usable && drive.type == DriveType.VIRTUAL ->
        stringResource(R.string.espace_virtuel)

    !drive.usable -> stringResource(R.string.espace_inutilisable)

    drive.type == DriveType.PERSONAL -> stringResource(R.string.espace_personnel)
    drive.type == DriveType.PROJECT -> stringResource(R.string.espace_projet)
    drive.type == DriveType.MOUNTPOINT -> stringResource(R.string.espace_monte)
    else -> drive.type
}
