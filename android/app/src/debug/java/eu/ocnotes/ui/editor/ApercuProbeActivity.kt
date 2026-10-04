package eu.ocnotes.ui.editor

import android.content.Context
import android.graphics.Typeface
import android.os.Bundle
import android.text.Spannable
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.RelativeSizeSpan
import android.text.style.StyleSpan
import android.util.Log
import android.util.TypedValue
import android.view.ViewTreeObserver
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import eu.ocnotes.data.BlockKind
import eu.ocnotes.data.NoteBlockDto
import eu.ocnotes.data.NoteSpanDto
import eu.ocnotes.data.SpanStyleId
import eu.ocnotes.ui.theme.OCnotesTheme

/**
 * Sonde jetable : même note synthétique de ~295 ko, rendue soit par l'aperçu
 * actuel (`VueMarkdown`, Compose), soit par un unique [TextView] sélectionnable.
 *
 * Sans réseau ni session : la mesure est reproductible. Debug uniquement.
 */
// Layout.BREAK_STRATEGY_* est la bonne famille de constantes pour TextView ;
// lint la confond avec LineBreaker (API 29).
@android.annotation.SuppressLint("WrongConstant")
class ApercuProbeActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val mode = intent.getStringExtra(EXTRA_MODE).orEmpty().ifBlank { MODE_TEXTVIEW }
        val t0 = System.nanoTime()
        val blocs = blocsSynthetiques().toMutableList().also { liste ->
            // Un seul paragraphe démesuré (~700 ko, plusieurs milliers de lignes) :
            // sa hauteur dépasse le plafond de 262 143 px des Constraints Compose.
            if (intent.getBooleanExtra("geant", false)) {
                liste.add(0, NoteBlockDto(kind = BlockKind.PARAGRAPHE, text = "lorem ipsum dolor ".repeat(intent.getIntExtra("rep", 40_000))))
            }
        }
        Log.i(TAG, "START mode=$mode blocs=${blocs.size} " +
            "chars=${blocs.sumOf { it.text.length }} genMs=${(System.nanoTime() - t0) / 1e6}")

        if (mode == MODE_COMPOSE) {
            val debut = System.nanoTime()
            setContent {
                OCnotesTheme {
                    Surface(Modifier.fillMaxSize()) { eu.ocnotes.ui.editor.VueMarkdown(blocs) }
                }
            }
            signalerPremierDessin(window.decorView, debut, mode)
        } else {
            val debut = System.nanoTime()
            val texte = versSpannable(blocs)
            val figee = intent.getBooleanExtra("figee", false)
            val aAfficher: CharSequence = if (figee) android.text.SpannableString(texte) else texte
            val tPose = System.nanoTime()
            val vue = ChampApercu(this).apply {
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
                setTextIsSelectable(true)
                breakStrategy = android.text.Layout.BREAK_STRATEGY_SIMPLE
                hyphenationFrequency = android.text.Layout.HYPHENATION_FREQUENCY_NONE
                setPadding(dp(20), dp(16), dp(20), dp(16))
                setText(aAfficher, TextView.BufferType.SPANNABLE)
            }
            Log.i(TAG, "BUILD spannableMs=${(tPose - debut) / 1e6} " +
                "setTextMs=${(System.nanoTime() - tPose) / 1e6} spans=${texte.getSpans(0, texte.length, Any::class.java).size}")
            val sansSpans = intent.getBooleanExtra("sansspans", false)
            if (sansSpans) vue.setText(texte.toString(), TextView.BufferType.SPANNABLE)
            val largeur = resources.displayMetrics.widthPixels
            val tm = System.nanoTime()
            vue.measure(
                android.view.View.MeasureSpec.makeMeasureSpec(largeur, android.view.View.MeasureSpec.EXACTLY),
                android.view.View.MeasureSpec.makeMeasureSpec(0, android.view.View.MeasureSpec.UNSPECIFIED),
            )
            Log.i(TAG, "MEASURE sansSpans=$sansSpans ms=${(System.nanoTime() - tm) / 1e6} " +
                "lines=${vue.layout?.lineCount} height=${vue.measuredHeight}")
            setContentView(ScrollView(this).apply { addView(vue) })
            signalerPremierDessin(window.decorView, debut, mode)
        }
    }

    private fun Context.dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun signalerPremierDessin(vue: android.view.View, debut: Long, mode: String) {
        vue.viewTreeObserver.addOnPreDrawListener(object : ViewTreeObserver.OnPreDrawListener {
            override fun onPreDraw(): Boolean {
                vue.viewTreeObserver.removeOnPreDrawListener(this)
                vue.post {
                    Log.i(TAG, "READY mode=$mode totalMs=${(System.nanoTime() - debut) / 1e6}")
                }
                return true
            }
        })
    }

    companion object {
        const val EXTRA_MODE = "mode"
        const val MODE_TEXTVIEW = "textview"
        const val MODE_COMPOSE = "compose"
        const val TAG = "OCnotesApercuProbe"
    }
}

@android.annotation.SuppressLint("AppCompatCustomView")
private class ChampApercu(context: Context) : TextView(context) {
    override fun onSelectionChanged(selStart: Int, selEnd: Int) {
        super.onSelectionChanged(selStart, selEnd)
        val n = text?.length ?: return
        if (n > 0 && selStart == 0 && selEnd == n) {
            Log.i(ApercuProbeActivity.TAG, "SELECT_ALL chars=$n")
        }
    }

    override fun onTextContextMenuItem(id: Int): Boolean {
        if (id == android.R.id.copy) {
            Log.i(ApercuProbeActivity.TAG, "COPY chars=${kotlin.math.abs(selectionEnd - selectionStart)}")
        }
        return super.onTextContextMenuItem(id)
    }
}

/** Version la plus simple possible : un bloc = un paragraphe, titres agrandis, puces en texte. */
private fun versSpannable(blocs: List<NoteBlockDto>): Spannable {
    val sb = SpannableStringBuilder()
    for (bloc in blocs) {
        val debut = sb.length
        if (bloc.kind == BlockKind.PUCE) sb.append("•  ")
        val decalage = sb.length
        sb.append(bloc.text)
        for (s in bloc.spans) {
            if (s.style == SpanStyleId.GRAS) {
                sb.setSpan(StyleSpan(Typeface.BOLD), decalage + s.start, decalage + s.end,
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
        }
        if (bloc.kind == BlockKind.TITRE) {
            sb.setSpan(RelativeSizeSpan(1.3f), debut, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            sb.setSpan(StyleSpan(Typeface.BOLD), debut, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        sb.append("\n\n")
    }
    return sb
}

/** ~295 ko : un titre tous les quinze blocs, une puce sur cinq, un mot sur sept en gras. */
private fun blocsSynthetiques(): List<NoteBlockDto> {
    val lorem = (
        "Lorem ipsum dolor sit amet consectetur adipiscing elit placerat in id cursus " +
            "mi pretium tellus duis urna tempor pulvinar vivamus fringilla lacus nec metus " +
            "integer nunc posuere ut hendrerit semper vel class conubia nostra inceptos " +
            "himenaeos orci varius natoque penatibus mus donec rhoncus eros lobortis nulla"
        ).split(' ')
    val blocs = ArrayList<NoteBlockDto>()
    var taille = 0
    var mot = 0
    var i = 0
    while (taille < 295_000) {
        if (i % 15 == 0) {
            val t = "Titre $i"
            blocs += NoteBlockDto(kind = BlockKind.TITRE, text = t, level = 2)
            taille += t.length
        }
        val sb = StringBuilder()
        val spans = ArrayList<NoteSpanDto>()
        val lignes = 3 + i % 4
        while (sb.length < 70 * lignes) {
            val m = lorem[mot++ % lorem.size]
            if (mot % 7 == 0) spans += NoteSpanDto(sb.length, sb.length + m.length, SpanStyleId.GRAS)
            sb.append(m).append(' ')
        }
        val texte = sb.toString().trimEnd()
        blocs += NoteBlockDto(
            kind = if (i % 5 == 0) BlockKind.PUCE else BlockKind.PARAGRAPHE,
            text = texte,
            spans = spans.filter { it.end <= texte.length },
        )
        taille += texte.length
        i++
    }
    return blocs
}
