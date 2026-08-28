package eu.opennote.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import eu.opennote.appContainer
import eu.opennote.ui.common.Bandeau

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

    LaunchedEffect(etat.deconnecte) {
        if (etat.deconnecte) onDeconnecte()
    }

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text("Réglages") },
                navigationIcon = {
                    IconButton(onClick = onRetour) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Retour")
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
            Text("Compte", style = MaterialTheme.typography.titleSmall)
            Ligne("Serveur", etat.etat.serverUrl.ifBlank { "—" })
            Ligne("Utilisateur", etat.etat.username.ifBlank { "—" })

            // La session a pu être remontée hors connexion : l'application est
            // utilisable, mais le serveur n'a encore rien confirmé.
            if (!etat.tokenValide) {
                Text(
                    text = "Le serveur n'a pas encore confirmé votre App Token depuis " +
                        "le lancement. Vos notes restent consultables et modifiables " +
                        "en attendant.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            HorizontalDivider(Modifier.padding(vertical = 8.dp))

            Text("Espace de notes", style = MaterialTheme.typography.titleSmall)
            Ligne("Espace", etat.etat.driveName.ifBlank { "—" })
            Ligne("Dossier", etat.etat.root.ifBlank { "racine de l'espace" })

            HorizontalDivider(Modifier.padding(vertical = 8.dp))

            Text("Synchronisation", style = MaterialTheme.typography.titleSmall)
            Ligne(
                libelle = "Opérations en attente",
                valeur = if (etat.enAttente == 0) "aucune" else etat.enAttente.toString(),
            )
            Text(
                text = "Une passe automatique a lieu au retour dans l'application, " +
                    "peu après chaque modification, et toutes les heures si le " +
                    "réseau est disponible.",
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
                    Text("Synchronisation…")
                } else {
                    Icon(Icons.Default.Sync, null, Modifier.padding(end = 8.dp))
                    Text("Synchroniser maintenant")
                }
            }

            etat.resume?.let { texte ->
                Bandeau(
                    texte = texte,
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
                Text(message, color = MaterialTheme.colorScheme.error)
            }

            HorizontalDivider(Modifier.padding(vertical = 8.dp))

            OutlinedButton(
                onClick = { confirmation = true },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Se déconnecter", color = MaterialTheme.colorScheme.error)
            }

            Text(
                text = "La déconnexion efface le token, la configuration et le cache " +
                    "local de cet appareil. Les notes restent sur le serveur.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }

    if (confirmation) {
        AlertDialog(
            onDismissRequest = { confirmation = false },
            title = { Text("Se déconnecter ?") },
            text = {
                Text(
                    if (etat.enAttente > 0) {
                        "Attention : ${etat.enAttente} modification(s) locale(s) n'ont " +
                            "pas encore été envoyées au serveur. La déconnexion efface " +
                            "le cache et elles seront perdues. Synchronisez d'abord."
                    } else {
                        "Le token, la configuration et le cache local seront effacés. " +
                            "Vos notes restent sur le serveur."
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
                    Text("Se déconnecter", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmation = false }) { Text("Annuler") }
            },
        )
    }

    // Un conflit est le seul événement de synchronisation qui mérite qu'on
    // interrompe l'utilisateur : sa version a été mise de côté, il doit savoir
    // où la retrouver.
    if (etat.conflits.isNotEmpty()) {
        AlertDialog(
            onDismissRequest = viewModel::conflitsConsommes,
            title = { Text("Modifiée sur deux appareils") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        "La version du serveur était plus récente. Votre version a été " +
                            "conservée à côté, sous un nouveau nom — rien n'est perdu :",
                    )
                    etat.conflits.forEach { conflit ->
                        Text(
                            text = "• ${conflit.copyPath.substringAfterLast('/')}",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = viewModel::conflitsConsommes) { Text("Compris") }
            },
        )
    }
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
