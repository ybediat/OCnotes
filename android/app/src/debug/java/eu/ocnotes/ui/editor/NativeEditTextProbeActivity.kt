package eu.ocnotes.ui.editor

import android.annotation.SuppressLint
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
import eu.ocnotes.appContainer
import eu.ocnotes.data.FolderEntryDto
import eu.ocnotes.ui.theme.OCnotesTheme
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
    private var coloration = false
    private var dense = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val note = intent.getStringExtra(EXTRA_NOTE).orEmpty().ifBlank { NOTE_PAR_DEFAUT }
        val dossier = intent.getStringExtra(EXTRA_DOSSIER).orEmpty().ifBlank { DOSSIER_PAR_DEFAUT }

        coloration = intent.getBooleanExtra(EXTRA_COLORATION, false)
        dense = intent.getBooleanExtra(EXTRA_DENSE, false)

        Log.i(TAG, "START note=$note dossier=$dossier coloration=$coloration dense=$dense")
        setContent {
            OCnotesTheme {
                Surface(Modifier.fillMaxSize()) {
                    when (val courant = etat) {
                        EtatSonde.Chargement -> MessageSonde("Chargement de la note de référence…")
                        is EtatSonde.Echec -> MessageSonde("Échec : ${courant.message}")
                        is EtatSonde.Prete -> EditTextNatif(courant.contenu, coloration)
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
        if (note == NOTE_SYNTHETIQUE) {
            val texte = noteSynthetique()
            Log.i(TAG, "LOADED path=synthetique chars=${texte.length} rawChars=${texte.length} images=0")
            val effectif = if (dense) mettreUnMotSurCinqEnGras(texte) else texte
            if (coloration) mesurerCoutDeLaColoration(effectif)
            return ContenuSonde("synthetique", effectif)
        }
        val repository = appContainer.repository
        check(repository.ensureSession()) { "session OCnotes indisponible" }

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
        // Mode « dense » : un mot sur cinq passe en gras, pour mesurer la
        // coloration sur une note dont le Markdown est bien plus serré que la
        // prose de test — dix à vingt mille marques sur 295 ko.
        val texte = if (dense) mettreUnMotSurCinqEnGras(prepare.text) else prepare.text
        if (dense) Log.i(TAG, "DENSE chars=${texte.length}")
        if (coloration) mesurerCoutDeLaColoration(texte)
        return ContenuSonde(entree.path, texte)
    }

    companion object {
        const val EXTRA_NOTE = "note"
        const val EXTRA_DOSSIER = "dossier"
        const val EXTRA_COLORATION = "coloration"
        const val EXTRA_DENSE = "dense"

        /** Note générée sur place : ni session ni réseau, donc mesure reproductible. */
        const val NOTE_SYNTHETIQUE = "@synthetique"

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
private fun EditTextNatif(contenu: ContenuSonde, coloration: Boolean) {
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
            colorationCode = coloration,
            onColoration = { spans, ms -> Log.i(TAG, "COLOR spans=$spans ms=$ms") },
            onInitialise = { champ, debut ->
                Log.i(
                    TAG,
                    "SET_TEXT chars=${champ.text.length} " +
                        "ms=${(System.nanoTime() - debut) / 1_000_000.0}",
                )
                champ.journaliserPremierDessin(debut, contenu.chemin)
                champ.suivreDefilement()
            },
        )
    }
}

/**
 * ~295 ko de prose coupée à 70 colonnes, comme la note de référence, avec le
 * Markdown clairsemé d'une vraie note : un titre tous les quinze paragraphes,
 * une liste tous les cinq, une entité `&nbsp;` de temps en temps.
 */
private fun noteSynthetique(): String {
    val lorem = (
        "Lorem ipsum dolor sit amet consectetur adipiscing elit placerat in id cursus " +
            "mi pretium tellus duis urna tempor pulvinar vivamus fringilla lacus nec metus " +
            "integer nunc posuere ut hendrerit semper vel class conubia nostra inceptos " +
            "himenaeos orci varius natoque penatibus mus donec rhoncus eros lobortis nulla"
        ).split(' ')
    val sortie = StringBuilder(300_000)
    var mot = 0
    var paragraphe = 0
    while (sortie.length < 295_000) {
        if (paragraphe % 15 == 0) sortie.append("## Titre ").append(paragraphe).append("\n\n")
        val liste = paragraphe % 5 == 0
        val lignes = 3 + paragraphe % 4
        repeat(lignes) {
            val ligne = StringBuilder(if (liste) "- " else "")
            while (ligne.length < 70) {
                ligne.append(lorem[mot++ % lorem.size])
                ligne.append(if (mot % 23 == 0) "&nbsp;" else " ")
            }
            sortie.append(ligne.toString().trimEnd()).append('\n')
        }
        sortie.append('\n')
        paragraphe++
    }
    return sortie.toString()
}

/** Sépare l'analyse (pure) de la pose des spans, pour savoir lequel coûte. */
private fun mesurerCoutDeLaColoration(texte: String) {
    var n = 0
    val t0 = System.nanoTime()
    plagesDeCode(texte, 0, texte.length) { _, _ -> n++ }
    val t1 = System.nanoTime()
    val plages = IntArray(n * 2)
    var k = 0
    plagesDeCode(texte, 0, texte.length) { d, f -> plages[k++] = d; plages[k++] = f }
    val tampon = android.text.SpannableStringBuilder(texte)
    val t2 = System.nanoTime()
    for (j in 0 until n) {
        tampon.setSpan(SpanCode(0x80000000.toInt()), plages[2 * j], plages[2 * j + 1],
            android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
    }
    val t3 = System.nanoTime()
    val sansArbre = android.text.SpannableString(texte)
    val t4 = System.nanoTime()
    for (j in 0 until n) {
        sansArbre.setSpan(SpanCode(0x80000000.toInt()), plages[2 * j], plages[2 * j + 1],
            android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
    }
    val t5 = System.nanoTime()
    Log.i(TAG, "SCAN plages=$n analyseMs=${(t1 - t0) / 1e6} " +
        "poseSSBms=${(t3 - t2) / 1e6} poseSpannableStringMs=${(t5 - t4) / 1e6}")
}

private fun mettreUnMotSurCinqEnGras(texte: String): String {
    val sortie = StringBuilder(texte.length + texte.length / 10)
    var mot = 0
    var i = 0
    while (i < texte.length) {
        if (texte[i].isWhitespace()) {
            sortie.append(texte[i++])
            continue
        }
        val d = i
        while (i < texte.length && !texte[i].isWhitespace()) i++
        val unMot = texte.substring(d, i)
        if (mot++ % 5 == 0 && unMot.length < 60) sortie.append("**").append(unMot).append("**")
        else sortie.append(unMot)
    }
    return sortie.toString()
}

/** Journalise la position de défilement une fois par seconde (essai du retour au curseur). */
private fun EditText.suivreDefilement() {
    postDelayed(
        object : Runnable {
            override fun run() {
                Log.i(TAG, "SCROLLY y=$scrollY focused=${hasFocus()} cursor=$selectionStart")
                postDelayed(this, 1000)
            }
        },
        1000,
    )
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
// Sonde : on mesure le `android.widget.EditText` brut, pas la variante AppCompat.
@SuppressLint("AppCompatCustomView")
private class ProbeEditText(context: Context) : ChampEditeur(context) {
    override fun onScrollChanged(horiz: Int, vert: Int, oldHoriz: Int, oldVert: Int) {
        super.onScrollChanged(horiz, vert, oldHoriz, oldVert)
        // Un retour en arrière brutal, alors qu'on défile vers le bas : qui l'a demandé ?
        if (vert < oldVert - 200) {
            Log.i(TAG, "RETOUR de $oldVert a $vert par :\n" +
                Throwable().stackTrace.drop(1).take(14).joinToString("\n") { "    at $it" })
        }
    }

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

private const val DESCRIPTION_SONDE = "ocnotes-native-edittext-probe"
private const val TAG = "OCnotesNativeProbe"
