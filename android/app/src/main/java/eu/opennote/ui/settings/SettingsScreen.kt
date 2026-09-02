package eu.opennote.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import eu.opennote.data.ConflictDto
import eu.opennote.data.MoteurEdition
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import eu.opennote.R
import eu.opennote.appContainer
import eu.opennote.ui.common.Bandeau
import eu.opennote.ui.common.resoudre
import android.text.format.Formatter

private object QuotaCache {
    const val MO_50 = 50L * 1024 * 1024
    const val MO_250 = 250L * 1024 * 1024
    const val GO_1 = 1024L * 1024 * 1024
    const val GO_5 = 5L * 1024 * 1024 * 1024
    val choix = listOf(MO_50, MO_250, GO_1, GO_5, 0L)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onRetour: () -> Unit,
    onDeconnecte: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: SettingsViewModel = viewModel(
        factory = SettingsViewModel.factory(LocalContext.current.appContainer),
    ),
) {
    val etat by viewModel.uiState.collectAsStateWithLifecycle()
    var confirmation by remember { mutableStateOf(false) }
    var choixQuota by remember { mutableStateOf(false) }
    val context = LocalContext.current

    LaunchedEffect(etat.deconnecte) {
        if (etat.deconnecte) onDeconnecte()
    }

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.menu_reglages)) },
                navigationIcon = {
                    IconButton(onClick = onRetour) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.action_retour),
                        )
                    }
                },
            )
        },
    ) { paddings ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddings)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // Une valeur absente s'affiche en tiret plutôt qu'en vide :
            // la ligne garde sa place, et l'absence se voit.
            val absent = stringResource(R.string.commun_valeur_vide)

            Text(
                text = stringResource(R.string.reglages_compte),
                style = MaterialTheme.typography.titleSmall,
            )
            Ligne(
                libelle = stringResource(R.string.reglages_serveur),
                valeur = etat.etat.serverUrl.ifBlank { absent },
            )
            Ligne(
                libelle = stringResource(R.string.reglages_utilisateur),
                valeur = etat.etat.username.ifBlank { absent },
            )

            // La session a pu être remontée hors connexion : l'application est
            // utilisable, mais le serveur n'a encore rien confirmé.
            if (!etat.tokenValide) {
                Text(
                    text = stringResource(R.string.reglages_token_non_confirme),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            HorizontalDivider(Modifier.padding(vertical = 8.dp))

            Text(
                text = stringResource(R.string.reglages_espace_titre),
                style = MaterialTheme.typography.titleSmall,
            )
            Ligne(
                libelle = stringResource(R.string.reglages_espace),
                valeur = etat.etat.driveName.ifBlank { absent },
            )
            Ligne(
                libelle = stringResource(R.string.reglages_dossier),
                valeur = etat.etat.root.ifBlank {
                    stringResource(R.string.reglages_racine_espace)
                },
            )

            HorizontalDivider(Modifier.padding(vertical = 8.dp))

            Text(
                text = stringResource(R.string.reglages_edition_titre),
                style = MaterialTheme.typography.titleSmall,
            )
            Text(
                text = stringResource(R.string.reglages_edition_explication),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            ChoixMoteur(
                titre = stringResource(R.string.reglages_moteur_natif),
                detail = stringResource(R.string.reglages_moteur_natif_detail),
                selectionne = etat.moteurEdition == MoteurEdition.NATIF,
                onClick = { viewModel.definirMoteurEdition(MoteurEdition.NATIF) },
            )
            ChoixMoteur(
                titre = stringResource(R.string.reglages_moteur_virtualise),
                detail = stringResource(R.string.reglages_moteur_virtualise_detail),
                selectionne = etat.moteurEdition == MoteurEdition.VIRTUALISE,
                onClick = { viewModel.definirMoteurEdition(MoteurEdition.VIRTUALISE) },
            )

            HorizontalDivider(Modifier.padding(vertical = 8.dp))

            Text(
                text = stringResource(R.string.reglages_sync_titre),
                style = MaterialTheme.typography.titleSmall,
            )
            Ligne(
                libelle = stringResource(R.string.reglages_en_attente),
                valeur = if (etat.enAttente == 0) {
                    stringResource(R.string.reglages_aucune)
                } else {
                    etat.enAttente.toString()
                },
            )
            Text(
                text = stringResource(R.string.reglages_sync_explication),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Button(
                onClick = viewModel::synchroniser,
                enabled = !etat.syncEnCours,
                modifier = Modifier.fillMaxWidth(),
            ) {
                if (etat.syncEnCours) {
                    CircularProgressIndicator(
                        strokeWidth = 2.dp,
                        modifier = Modifier.padding(end = 8.dp),
                        color = MaterialTheme.colorScheme.onPrimary,
                    )
                    Text(stringResource(R.string.reglages_sync_en_cours))
                } else {
                    Icon(Icons.Default.Sync, null, Modifier.padding(end = 8.dp))
                    Text(stringResource(R.string.reglages_sync_maintenant))
                }
            }

            if (etat.conflits.isNotEmpty()) {
                OutlinedButton(
                    onClick = viewModel::ouvrirConflits,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(stringResource(R.string.reglages_conflits_ouvrir, etat.conflits.size))
                }
            }

            etat.resume?.let { texte ->
                Bandeau(
                    texte = texte.resoudre(),
                    couleurFond = if (etat.resumePartiel) {
                        MaterialTheme.colorScheme.tertiaryContainer
                    } else {
                        MaterialTheme.colorScheme.secondaryContainer
                    },
                    couleurTexte = if (etat.resumePartiel) {
                        MaterialTheme.colorScheme.onTertiaryContainer
                    } else {
                        MaterialTheme.colorScheme.onSecondaryContainer
                    },
                )
            }

            etat.erreur?.let { message ->
                Text(message.resoudre(), color = MaterialTheme.colorScheme.error)
            }

            HorizontalDivider(Modifier.padding(vertical = 8.dp))

            Text(
                text = stringResource(R.string.reglages_cache_titre),
                style = MaterialTheme.typography.titleSmall,
            )
            Ligne(
                libelle = stringResource(R.string.reglages_cache_quota),
                valeur = libelleQuota(etat.cache.quota),
            )
            Ligne(
                libelle = stringResource(R.string.reglages_cache_utilisation),
                valeur = stringResource(
                    R.string.reglages_cache_usage,
                    Formatter.formatShortFileSize(context, etat.cache.usage),
                    libelleQuota(etat.cache.quota),
                ),
            )
            Text(
                text = stringResource(R.string.reglages_cache_explication),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedButton(
                onClick = { choixQuota = true },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringResource(R.string.reglages_cache_modifier_quota))
            }
            OutlinedButton(
                onClick = viewModel::libererEspace,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringResource(R.string.reglages_cache_liberer))
            }

            HorizontalDivider(Modifier.padding(vertical = 8.dp))

            OutlinedButton(
                onClick = { confirmation = true },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    text = stringResource(R.string.reglages_deconnexion),
                    color = MaterialTheme.colorScheme.error,
                )
            }

            Text(
                text = stringResource(R.string.reglages_deconnexion_explication),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }

    if (choixQuota) {
        AlertDialog(
            onDismissRequest = { choixQuota = false },
            title = { Text(stringResource(R.string.reglages_cache_choix_titre)) },
            text = {
                Column {
                    QuotaCache.choix.forEach { quota ->
                        TextButton(
                            onClick = {
                                choixQuota = false
                                viewModel.definirQuotaCache(quota)
                            },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            RadioButton(selected = etat.cache.quota == quota, onClick = null)
                            Text(libelleQuota(quota), modifier = Modifier.padding(start = 8.dp))
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { choixQuota = false }) {
                    Text(stringResource(R.string.action_annuler))
                }
            },
        )
    }

    if (confirmation) {
        AlertDialog(
            onDismissRequest = { confirmation = false },
            title = { Text(stringResource(R.string.reglages_deconnexion_titre)) },
            text = {
                Text(
                    if (etat.enAttente > 0) {
                        pluralStringResource(
                            R.plurals.reglages_deconnexion_attente,
                            etat.enAttente,
                            etat.enAttente,
                        )
                    } else {
                        stringResource(R.string.reglages_deconnexion_confirmation)
                    },
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmation = false
                        viewModel.deconnecter()
                    },
                ) {
                    Text(
                        text = stringResource(R.string.reglages_deconnexion),
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmation = false }) {
                    Text(stringResource(R.string.action_annuler))
                }
            },
        )
    }

    // Un conflit est le seul événement de synchronisation qui mérite qu'on
    // interrompe l'utilisateur : sa version a été mise de côté, il doit savoir
    // où la retrouver.
    if (etat.dialogueConflits && etat.conflits.isNotEmpty()) {
        val conflit = etat.conflits.first()
        val resolutionEnCours = etat.conflitEnResolution == conflit.id
        AlertDialog(
            onDismissRequest = viewModel::fermerConflits,
            title = { Text(stringResource(R.string.reglages_conflits_titre)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(R.string.reglages_conflits_explication))
                    Text(
                        text = ligneConflit(conflit),
                        style = MaterialTheme.typography.bodySmall,
                    )
                    if (etat.conflits.size > 1) {
                        Text(
                            text = stringResource(R.string.reglages_conflits_reste, etat.conflits.size - 1),
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = { viewModel.resoudreConflit(conflit, "server") },
                    enabled = !resolutionEnCours,
                ) {
                    Text(stringResource(R.string.reglages_conflits_garder_serveur))
                }
            },
            dismissButton = {
                Column {
                    if (conflit.copyPath.isNotBlank()) {
                        TextButton(
                            onClick = { viewModel.resoudreConflit(conflit, "local") },
                            enabled = !resolutionEnCours,
                        ) {
                            Text(stringResource(R.string.reglages_conflits_garder_local))
                        }
                    }
                    TextButton(
                        onClick = { viewModel.resoudreConflit(conflit, "both") },
                        enabled = !resolutionEnCours,
                    ) {
                        Text(stringResource(R.string.reglages_conflits_garder_deux))
                    }
                    TextButton(onClick = viewModel::fermerConflits, enabled = !resolutionEnCours) {
                        Text(stringResource(R.string.action_annuler))
                    }
                }
            },
        )
    }
}

@Composable
private fun ChoixMoteur(
    titre: String,
    detail: String,
    selectionne: Boolean,
    onClick: () -> Unit,
) {
    Surface(onClick = onClick, modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.padding(vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            RadioButton(selected = selectionne, onClick = null)
            Column(modifier = Modifier.padding(start = 8.dp)) {
                Text(titre, style = MaterialTheme.typography.bodyLarge)
                Text(
                    text = detail,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun libelleQuota(quota: Long): String = when (quota) {
    QuotaCache.MO_50 -> stringResource(R.string.reglages_cache_50_mo)
    QuotaCache.MO_250 -> stringResource(R.string.reglages_cache_250_mo)
    QuotaCache.GO_1 -> stringResource(R.string.reglages_cache_1_go)
    QuotaCache.GO_5 -> stringResource(R.string.reglages_cache_5_go)
    else -> stringResource(R.string.reglages_cache_illimite)
}

@Composable
private fun ligneConflit(conflit: ConflictDto): String = when (conflit.operation) {
    "delete" -> stringResource(R.string.reglages_conflits_suppression, conflit.path.substringAfterLast('/'))
    "move" -> stringResource(R.string.reglages_conflits_deplacement, conflit.copyPath.substringAfterLast('/'))
    else -> stringResource(R.string.reglages_conflits_ligne, conflit.copyPath.substringAfterLast('/'))
}

@Composable
private fun Ligne(libelle: String, valeur: String) {
    Surface(color = MaterialTheme.colorScheme.surface, modifier = Modifier.fillMaxWidth()) {
        Column {
            Text(
                text = libelle,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(text = valeur, style = MaterialTheme.typography.bodyLarge)
        }
    }
}
