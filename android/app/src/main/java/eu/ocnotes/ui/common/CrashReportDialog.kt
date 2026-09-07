package eu.ocnotes.ui.common

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PersistableBundle
import android.widget.Toast
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import eu.ocnotes.R
import eu.ocnotes.diagnostic.CrashReporter

/**
 * Montre le dernier rapport avant d'initialiser l'interface normale.
 *
 * Cette porte est importante lorsqu'un ViewModel provoque le crash au
 * démarrage : son prochain lancement laisse d'abord l'utilisateur récupérer le
 * diagnostic, au lieu de retomber immédiatement dans la même boucle.
 */
@Composable
fun CrashReportGate(
    reporter: CrashReporter,
    content: @Composable () -> Unit,
) {
    var etat by remember(reporter) { mutableStateOf<EtatRapport>(EtatRapport.Lecture) }

    LaunchedEffect(reporter) {
        val texte = reporter.pendingReport()
        etat = if (texte == null) EtatRapport.Aucun else EtatRapport.Present(texte)
    }

    when (val courant = etat) {
        // Un écran vide le temps de lire le cache. Composer `content()` dès
        // maintenant relancerait le ViewModel qui vient peut-être de faire
        // tomber l'application, avant même d'avoir montré pourquoi.
        EtatRapport.Lecture -> Unit

        EtatRapport.Aucun -> {
            // L'interface s'affiche : la série d'arrêts est rompue. C'est le
            // seul endroit qui le sache — `Application.onCreate` s'exécute
            // aussi pour un réveil de `SyncWorker`, sans interface.
            LaunchedEffect(reporter) { reporter.signalerDemarrageSain() }
            content()
        }

        is EtatRapport.Present -> RapportDialogue(
            rapport = courant.texte,
            onMasquer = { etat = EtatRapport.Aucun },
            onOublier = {
                reporter.discardPending()
                etat = EtatRapport.Aucun
            },
        )
    }
}

private sealed interface EtatRapport {
    data object Lecture : EtatRapport
    data object Aucun : EtatRapport
    data class Present(val texte: String) : EtatRapport
}

@Composable
private fun RapportDialogue(
    rapport: String,
    onMasquer: () -> Unit,
    onOublier: () -> Unit,
) {
    val context = LocalContext.current

    AlertDialog(
        // Le rejet implicite — retour, appui à côté — masque seulement. Le
        // rapport n'existe qu'en un exemplaire : le geste le plus facile à
        // faire par mégarde ne doit pas être celui qui le détruit. Il
        // reviendra au lancement suivant tant qu'on n'en aura rien fait.
        onDismissRequest = onMasquer,
        title = { Text(text = stringResource(R.string.diagnostic_crash_titre)) },
        text = {
            Column {
                Text(text = stringResource(R.string.diagnostic_crash_explication))
                Text(
                    text = rapport,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 220.dp)
                        .verticalScroll(rememberScrollState()),
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    if (ouvrirIssueGitHub(context, rapport)) onOublier()
                },
            ) {
                Text(text = stringResource(R.string.diagnostic_signaler_github))
            }
        },
        dismissButton = {
            Row {
                TextButton(onClick = onOublier) {
                    Text(text = stringResource(R.string.action_supprimer))
                }
                TextButton(
                    onClick = {
                        if (partagerRapport(context, rapport)) onOublier()
                    },
                ) {
                    Text(text = stringResource(R.string.action_partager))
                }
            }
        },
    )
}

private fun partagerRapport(context: Context, report: String): Boolean = runCatching {
    val send = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_SUBJECT, context.getString(R.string.diagnostic_partage_sujet))
        putExtra(Intent.EXTRA_TEXT, report)
    }
    context.startActivity(
        Intent.createChooser(send, context.getString(R.string.diagnostic_partage_via)),
    )
}.isSuccess

/**
 * GitHub ne reçoit rien directement : le rapport est copié, puis son formulaire
 * public est ouvert. L'utilisateur voit et valide donc encore la publication.
 */
private fun ouvrirIssueGitHub(context: Context, report: String): Boolean = runCatching {
    val clipboard = context.getSystemService(ClipboardManager::class.java)
    val clip = ClipData.newPlainText(context.getString(R.string.diagnostic_partage_sujet), report)
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        clip.description.extras = PersistableBundle().apply {
            putBoolean(android.content.ClipDescription.EXTRA_IS_SENSITIVE, true)
        }
    }
    clipboard.setPrimaryClip(clip)

    Toast.makeText(context, R.string.diagnostic_github_copie, Toast.LENGTH_LONG).show()
    // Le dépôt n'a qu'une adresse, celle qu'affiche déjà « À propos ». La
    // réécrire ici ferait un second endroit à corriger au prochain
    // renommage — et le premier a déjà été manqué une fois.
    val uri = Uri.parse(context.getString(R.string.a_propos_depot_url))
        .buildUpon()
        .appendPath("issues")
        .appendPath("new")
        .appendQueryParameter("template", "bug_report.md")
        .appendQueryParameter("title", "[CRASH] ")
        .build()
    context.startActivity(Intent(Intent.ACTION_VIEW, uri))
}.isSuccess
