package eu.opennote.ui.editor

import android.content.Context
import android.os.Bundle
import android.util.Log
import android.view.ViewTreeObserver
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.lifecycle.lifecycleScope
import eu.opennote.appContainer
import eu.opennote.data.FolderEntryDto
import eu.opennote.ui.theme.OpenNoteTheme
import kotlinx.coroutines.launch

/**
 * Sonde jetable : un vrai [android.widget.EditText] porte la note complète.
 *
 * Elle reste dans la variante debug et ne sauvegarde jamais. Son unique rôle
 * est de décider, sur la note et l'appareil de référence, si le widget Android
 * classique mérite de remplacer l'éditeur Compose virtualisé.
 */
class NativeEditTextProbeActivity : ComponentActivity() {

    private var etat by mutableStateOf<EtatSonde>(EtatSonde.Chargement)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val note = intent.getStringExtra(EXTRA_NOTE).orEmpty().ifBlank { NOTE_PAR_DEFAUT }
        val dossier = intent.getStringExtra(EXTRA_DOSSIER).orEmpty().ifBlank { DOSSIER_PAR_DEFAUT }

        Log.i(TAG, "START note=$note dossier=$dossier")
        setContent {
            OpenNoteTheme {
                Surface(Modifier.fillMaxSize()) {
                    when (val courant = etat) {
                        EtatSonde.Chargement -> MessageSonde("Chargement de la note de référence…")
                        is EtatSonde.Echec -> MessageSonde("Échec : ${courant.message}")
                        is EtatSonde.Prete -> EditTextNatif(courant.contenu)
                    }
                }
            }
        }

        lifecycleScope.launch {
            etat = runCatching { chargerNote(note, dossier) }
                .fold(
                    onSuccess = EtatSonde::Prete,
                    onFailure = { erreur ->
                        Log.e(TAG, "ERROR ${erreur.message}", erreur)
                        EtatSonde.Echec(erreur.message ?: erreur.javaClass.simpleName)
                    },
                )
        }
    }

    private suspend fun chargerNote(note: String, dossier: String): ContenuSonde {
        val repository = appContainer.repository
        check(repository.ensureSession()) { "session OpenNote indisponible" }

        val entree = choisirEntree(repository.listAll().entries, note, dossier)
            ?: error("note '$note' introuvable dans '$dossier'")
        val brut = repository.readNote(entree.path)
        val prepare = repository.prepareEdit(entree.name, brut)
        check(prepare.editable) {
            "note refusée par PrepareEdit (mot le plus long : ${prepare.longestWord})"
        }

        Log.i(
            TAG,
            "LOADED path=${entree.path} chars=${prepare.text.length} " +
                "rawChars=${brut.length} images=${prepare.images.size}",
        )
        return ContenuSonde(entree.path, prepare.text)
    }

    companion object {
        const val EXTRA_NOTE = "note"
        const val EXTRA_DOSSIER = "dossier"

        private const val NOTE_PAR_DEFAUT = "scolarisation des enfants rrom"
        private const val DOSSIER_PAR_DEFAUT = "env test"
    }
}

private sealed interface EtatSonde {
    data object Chargement : EtatSonde
    data class Prete(val contenu: ContenuSonde) : EtatSonde
    data class Echec(val message: String) : EtatSonde
}

private data class ContenuSonde(
    val chemin: String,
    val texte: String,
)

private fun choisirEntree(
    entrees: List<FolderEntryDto>,
    note: String,
    dossier: String,
): FolderEntryDto? {
    val dossierNormalise = dossier.trim('/').lowercase()
    val candidates = entrees.filter { entree ->
        !entree.isDir && (
            entree.display.equals(note, ignoreCase = true) ||
                entree.name.equals(note, ignoreCase = true)
            )
    }
    return candidates.firstOrNull { entree ->
        val parent = entree.path.substringBeforeLast('/', "").lowercase()
        parent == dossierNormalise || parent.endsWith("/$dossierNormalise")
    } ?: candidates.singleOrNull()
}

@Composable
private fun EditTextNatif(contenu: ContenuSonde) {
    val focusRacine = remember { FocusRequester() }
    val session = remember { SessionEditeurNatif() }

    // L'ouverture historique se mesure sans clavier et sans curseur actif.
    // La frappe et le repos focalisé sont déclenchés ensuite par le banc.
    LaunchedEffect(contenu.chemin) { focusRacine.requestFocus() }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .focusRequester(focusRacine)
            .focusable(),
    ) {
        EditeurNatif(
            texteInitial = contenu.texte,
            session = session,
            modifier = Modifier.fillMaxSize(),
            description = DESCRIPTION_SONDE,
            creerChamp = ::ProbeEditText,
            onInitialise = { champ, debut ->
                Log.i(
                    TAG,
                    "SET_TEXT chars=${champ.text.length} " +
                        "ms=${(System.nanoTime() - debut) / 1_000_000.0}",
                )
                champ.journaliserPremierDessin(debut, contenu.chemin)
            },
        )
    }
}

private fun EditText.journaliserPremierDessin(debut: Long, chemin: String) {
    viewTreeObserver.addOnPreDrawListener(
        object : ViewTreeObserver.OnPreDrawListener {
            override fun onPreDraw(): Boolean {
                viewTreeObserver.removeOnPreDrawListener(this)
                post {
                    Log.i(
                        TAG,
                        "READY path=$chemin chars=${text.length} lines=${layout?.lineCount ?: -1} " +
                            "totalMs=${(System.nanoTime() - debut) / 1_000_000.0}",
                    )
                }
                return true
            }
        },
    )
}

/** Journalise seulement les opérations globales utilisées par le banc. */
private class ProbeEditText(context: Context) : EditText(context) {
    override fun onSelectionChanged(selStart: Int, selEnd: Int) {
        super.onSelectionChanged(selStart, selEnd)
        val longueur = text?.length ?: return
        if (longueur > 0 && selStart == 0 && selEnd == longueur) {
            Log.i(TAG, "SELECT_ALL chars=$longueur")
        }
    }

    override fun onTextContextMenuItem(id: Int): Boolean {
        if (id == android.R.id.copy) {
            Log.i(
                TAG,
                "COPY start=$selectionStart end=$selectionEnd " +
                    "chars=${kotlin.math.abs(selectionEnd - selectionStart)}",
            )
        }
        return super.onTextContextMenuItem(id)
    }

    override fun onFocusChanged(focused: Boolean, direction: Int, previouslyFocusedRect: android.graphics.Rect?) {
        super.onFocusChanged(focused, direction, previouslyFocusedRect)
        Log.i(TAG, "FOCUS focused=$focused selection=$selectionStart:$selectionEnd")
        if (!focused) {
            (context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager)
                .hideSoftInputFromWindow(windowToken, 0)
        }
    }
}

@Composable
private fun MessageSonde(message: String) {
    Text(
        text = message, // i18n-ok : activité de mesure absente des builds release.
        color = MaterialTheme.colorScheme.onSurface,
    )
}

private const val DESCRIPTION_SONDE = "opennote-native-edittext-probe"
private const val TAG = "OpenNoteNativeProbe"
