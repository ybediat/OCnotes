package eu.ocnotes.ui.common

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PersistableBundle
import android.widget.Toast
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
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
    val context = LocalContext.current
    var report by remember(reporter) { mutableStateOf(reporter.pendingReport()) }
    val pending = report
    if (pending == null) {
        content()
        return
    }

    fun discard() {
        reporter.discardPending()
        report = null
    }

    AlertDialog(
        onDismissRequest = ::discard,
        title = { Text(text = context.getString(R.string.diagnostic_crash_titre)) },
        text = {
            androidx.compose.foundation.layout.Column {
                Text(text = context.getString(R.string.diagnostic_crash_explication))
                Text(
                    text = pending,
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
                    if (ouvrirIssueGitHub(context, pending)) discard()
                },
            ) {
                Text(text = context.getString(R.string.diagnostic_signaler_github))
            }
        },
        dismissButton = {
            Row {
                TextButton(onClick = ::discard) {
                    Text(text = context.getString(R.string.action_supprimer))
                }
                TextButton(
                    onClick = {
                        if (partagerRapport(context, pending)) discard()
                    },
                ) {
                    Text(text = context.getString(R.string.action_partager))
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
    val uri = Uri.Builder()
        .scheme("https")
        .authority("github.com")
        .appendPath("ybediat")
        .appendPath("OCnotes")
        .appendPath("issues")
        .appendPath("new")
        .appendQueryParameter("template", "bug_report.md")
        .appendQueryParameter("title", "[CRASH] ")
        .build()
    context.startActivity(Intent(Intent.ACTION_VIEW, uri))
}.isSuccess
