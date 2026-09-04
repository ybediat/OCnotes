package eu.ocnotes.ui.editor

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Typeface
import android.os.Build
import android.text.Editable
import android.text.InputType
import android.text.Layout
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewTreeObserver
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.TextView
import androidx.annotation.MainThread
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.Stable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.graphics.drawable.DrawableCompat
import kotlin.math.roundToInt

/** Sélection exprimée dans les offsets UTF-16 natifs d'Android et de Compose. */
data class SelectionEditeurNatif(val debut: Int, val fin: Int)

/** Copie immuable qui peut quitter le thread principal sans exposer l'Editable. */
data class InstantaneEditeurNatif(
    val texte: String,
    val selection: SelectionEditeurNatif,
    val revision: Long,
    val defilementX: Int,
    val defilementY: Int,
)

/**
 * Pont possédé par la composition, jamais par le ViewModel.
 *
 * Il retient le champ uniquement entre [attacher] et [detacher]. Tout texte
 * remis à une coroutine passe d'abord par [instantane], qui crée une String.
 *
 * `@Stable` : identité fixe et aucun état mutable *public* — ses champs sont
 * privés et pilotés à la main. Sans cette annotation, Compose le juge instable
 * et [EditeurNatif] recompose son `AndroidView` à chaque frappe.
 */
@Stable
class SessionEditeurNatif {
    private var champ: EditText? = null
    private var revision: Long = 0

    @MainThread
    fun instantane(): InstantaneEditeurNatif? = champ?.let { vue ->
        InstantaneEditeurNatif(
            texte = vue.text.toString(),
            selection = SelectionEditeurNatif(vue.selectionStart, vue.selectionEnd),
            revision = revision,
            defilementX = vue.scrollX,
            defilementY = vue.scrollY,
        )
    }

    @MainThread
    fun selection(): SelectionEditeurNatif? = champ?.let {
        SelectionEditeurNatif(it.selectionStart, it.selectionEnd)
    }

    @MainThread
    fun restaurerSelection(selection: SelectionEditeurNatif): Boolean {
        val vue = champ ?: return false
        vue.setSelection(
            selection.debut.coerceIn(0, vue.length()),
            selection.fin.coerceIn(0, vue.length()),
        )
        return true
    }

    /** Remplace une plage sans recréer le champ ni effacer sa pile d'annulation. */
    @MainThread
    fun appliquerRemplacement(
        revisionAttendue: Long,
        remplacement: RemplacementNatif,
        selection: SelectionEditeurNatif,
    ): Boolean {
        val vue = champ ?: return false
        if (!revisionNativeToujoursCourante(revisionAttendue, revision)) return false
        val editable = vue.text
        val borneDebut = remplacement.debut.coerceIn(0, editable.length)
        val borneFin = remplacement.fin.coerceIn(borneDebut, editable.length)
        if (!remplacement.vide) editable.replace(borneDebut, borneFin, remplacement.texte)
        restaurerSelection(selection)
        // Le bouton Compose de la barre de format prend temporairement le focus.
        // Le rendre au champ permet notamment à l'annulation Android de recevoir
        // immédiatement Ctrl+Z après le remplacement ciblé.
        vue.requestFocus()
        return true
    }

    @MainThread
    fun demanderFocusEtClavier(): Boolean {
        val vue = champ ?: return false
        vue.requestFocus()
        vue.post {
            (vue.context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager)
                .showSoftInput(vue, InputMethodManager.SHOW_IMPLICIT)
        }
        return true
    }

    /** Déplace le champ sans modifier texte, sélection ni historique d'annulation. */
    @MainThread
    fun defilerVers(progression: Float): Boolean {
        val vue = champ ?: return false
        val maximum = vue.etatDefilementNatif().maximum
        vue.scrollTo(
            vue.scrollX,
            (maximum * progression.coerceIn(0f, 1f)).roundToInt(),
        )
        return true
    }

    @MainThread
    internal fun attacher(vue: EditText, revisionInitiale: Long) {
        check(champ == null || champ === vue) {
            "Une session native est déjà attachée." // i18n-ok : invariant interne, jamais affiché.
        }
        champ = vue
        revision = revisionInitiale
    }

    @MainThread
    internal fun signalerModification(vue: EditText): Long? {
        if (champ !== vue) return null
        revision += 1
        return revision
    }

    @MainThread
    internal fun detacher(vue: EditText) {
        if (champ !== vue) return
        (vue.context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager)
            .hideSoftInputFromWindow(vue.windowToken, 0)
        vue.clearFocus()
        champ = null
    }
}

/**
 * Champ Android monolithique partagé par l'écran de production et le harnais
 * debug. [texteInitial] est posé une fois dans `factory` : `update` ne rappelle
 * jamais `setText`.
 *
 * Le texte n'est posé qu'à la frame suivante : le champ vide se dessine
 * aussitôt, l'écran a le temps de peindre son indicateur d'attente, puis
 * [onPret] est appelé au premier dessin réel du champ pour lever cet overlay.
 */
@Composable
@SuppressLint("WrongConstant") // LineBreaker exige API 29 ; Layout garde la compatibilité API 26.
fun EditeurNatif(
    texteInitial: String,
    session: SessionEditeurNatif,
    modifier: Modifier = Modifier,
    selectionInitiale: SelectionEditeurNatif = SelectionEditeurNatif(0, 0),
    revisionInitiale: Long = 0,
    defilementInitialX: Int = 0,
    defilementInitialY: Int = 0,
    demanderFocus: Boolean = false,
    description: String? = null,
    descriptionDefilementRapide: String? = null,
    creerChamp: (Context) -> EditText = ::EditText,
    onInitialise: (EditText, Long) -> Unit = { _, _ -> },
    onMutation: (Long) -> Unit = {},
    onAvantDetachement: (InstantaneEditeurNatif) -> Unit = {},
    onPret: () -> Unit = {},
) {
    val couleurTexte = androidx.compose.material3.MaterialTheme.colorScheme.onSurface.toArgb()
    val couleurFond = androidx.compose.material3.MaterialTheme.colorScheme.surface.toArgb()
    val couleurCurseur = androidx.compose.material3.MaterialTheme.colorScheme.primary.toArgb()
    val couleurSelection = androidx.compose.material3.MaterialTheme.colorScheme.primary
        .copy(alpha = 0.28f)
        .toArgb()
    val densite = LocalDensity.current
    val paddingHorizontal = with(densite) { 20.dp.roundToPx() }
    val paddingVertical = with(densite) { 2.dp.roundToPx() }
    val mutationCourante = rememberUpdatedState(onMutation)
    val detachementCourant = rememberUpdatedState(onAvantDetachement)
    val pretCourant = rememberUpdatedState(onPret)
    var defilement by remember(session) { mutableStateOf(EtatDefilementNatif()) }

    key(session) {
        Box(modifier = modifier) {
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { context ->
                    val debutInitialisation = System.nanoTime()
                    creerChamp(context).apply {
                        contentDescription = description
                        gravity = Gravity.TOP or Gravity.START
                        setHorizontallyScrolling(false)
                        isVerticalScrollBarEnabled = false
                        overScrollMode = View.OVER_SCROLL_IF_CONTENT_SCROLLS
                        inputType = InputType.TYPE_CLASS_TEXT or
                            InputType.TYPE_TEXT_FLAG_MULTI_LINE or
                            InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
                        breakStrategy = Layout.BREAK_STRATEGY_SIMPLE
                        hyphenationFrequency = Layout.HYPHENATION_FREQUENCY_NONE
                        typeface = Typeface.MONOSPACE
                        textSize = 15f
                        // Valeur de la sonde mesurée : la modifier change le coût
                        // du StaticLayout initial sur les 8 853 lignes.
                        setLineSpacing(0f, 1.35f)
                        setTextColor(couleurTexte)
                        setBackgroundColor(couleurFond)
                        highlightColor = couleurSelection
                        setPadding(
                            paddingHorizontal,
                            paddingVertical,
                            paddingHorizontal,
                            paddingVertical,
                        )
                        setSelectAllOnFocus(false)
                        isSaveEnabled = false
                        teinterCurseur(couleurCurseur)

                        // Tout ce qui suit est reporté d'une frame : le champ
                        // vide se dessine d'abord — l'overlay d'attente de
                        // l'écran a le temps de peindre — puis le layout des
                        // milliers de lignes s'exécute. `attacher` est reporté
                        // avec le reste pour qu'une sortie dans cette frame ne
                        // photographie jamais un champ encore vide.
                        post {
                            if (!isAttachedToWindow) return@post

                            setText(texteInitial, TextView.BufferType.EDITABLE)
                            setSelection(
                                selectionInitiale.debut.coerceIn(0, length()),
                                selectionInitiale.fin.coerceIn(0, length()),
                            )

                            session.attacher(this@apply, revisionInitiale)
                            addTextChangedListener(
                                object : TextWatcher {
                                    override fun beforeTextChanged(
                                        s: CharSequence?,
                                        start: Int,
                                        count: Int,
                                        after: Int,
                                    ) = Unit

                                    override fun onTextChanged(
                                        s: CharSequence?,
                                        start: Int,
                                        before: Int,
                                        count: Int,
                                    ) = Unit

                                    override fun afterTextChanged(s: Editable?) {
                                        session.signalerModification(this@apply)?.let {
                                            mutationCourante.value(it)
                                        }
                                    }
                                },
                            )
                            setOnScrollChangeListener { _, _, _, _, _ ->
                                defilement = etatDefilementNatif()
                            }
                            onInitialise(this@apply, debutInitialisation)
                            post {
                                scrollTo(defilementInitialX, defilementInitialY)
                                defilement = etatDefilementNatif()
                                if (demanderFocus) session.demanderFocusEtClavier()
                            }
                            this@apply.viewTreeObserver.addOnPreDrawListener(
                                object : ViewTreeObserver.OnPreDrawListener {
                                    override fun onPreDraw(): Boolean {
                                        this@apply.viewTreeObserver
                                            .removeOnPreDrawListener(this)
                                        pretCourant.value()
                                        return true
                                    }
                                },
                            )
                        }
                    }
                },
                update = { champ ->
                    // Styles seulement : jamais de setText dans ce bloc.
                    champ.setTextColor(couleurTexte)
                    champ.setBackgroundColor(couleurFond)
                    champ.highlightColor = couleurSelection
                    champ.teinterCurseur(couleurCurseur)
                },
                onRelease = { champ ->
                    champ.setOnScrollChangeListener(null)
                    session.instantane()?.let { detachementCourant.value(it) }
                    session.detacher(champ)
                },
            )

            BandeDefilementRapideNatif(
                etat = defilement,
                description = descriptionDefilementRapide,
                onDefiler = session::defilerVers,
                modifier = Modifier.align(Alignment.CenterEnd),
            )
        }
    }
}

private fun EditText.etatDefilementNatif(): EtatDefilementNatif {
    val hauteurVisible = (height - compoundPaddingTop - compoundPaddingBottom)
        .coerceAtLeast(0)
    val hauteurContenu = layout?.height ?: hauteurVisible
    val maximum = (hauteurContenu - hauteurVisible).coerceAtLeast(0)
    return EtatDefilementNatif(
        position = scrollY.coerceIn(0, maximum),
        maximum = maximum,
        hauteurVisible = hauteurVisible,
    )
}

private fun EditText.teinterCurseur(couleur: Int) {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        textCursorDrawable?.let { curseur ->
            val enveloppe = DrawableCompat.wrap(curseur.mutate())
            DrawableCompat.setTint(enveloppe, couleur)
            textCursorDrawable = enveloppe
        }
    }
}
