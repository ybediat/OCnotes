package eu.ocnotes.ui.editor

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Typeface
import android.os.Build
import android.os.SystemClock
import android.text.Editable
import android.text.InputType
import android.text.Layout
import android.text.TextWatcher
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewTreeObserver
import android.view.inspector.WindowInspector
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.TextView
import androidx.annotation.MainThread
import androidx.annotation.RequiresApi
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
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.roundToInt

/** Opacité du code coloré : en retrait du texte, mais lisible. */
private const val COLORATION_ALPHA = 0.85f

/** Classe des poignées de sélection d'`Editor` : seul moyen de les reconnaître. */
private const val CLASSE_POIGNEE = "\$SelectionHandleView"

/** Après un changement de sélection, le temps qu'`Editor` montre ses poignées. */
private const val DELAI_RECHERCHE_POIGNEES_MS = 250L
private const val ESSAIS_RECHERCHE_POIGNEES = 8

private class ReferenceColoration {
    var valeur: ColorationFenetre? = null
}

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

    /**
     * Portion du texte à confier à une mise en forme (voir [fenetreMiseEnForme]).
     *
     * Seule la tranche est copiée hors de l'`Editable` : [instantane]
     * photographierait les 285 ko pour une action qui n'en lit que trois lignes.
     */
    @MainThread
    fun fenetre(): FenetreNatif? = champ?.let { vue ->
        val texte = vue.text
        val selection = SelectionEditeurNatif(
            vue.selectionStart.coerceIn(0, texte.length),
            vue.selectionEnd.coerceIn(0, texte.length),
        )
        val bornes = fenetreMiseEnForme(texte, selection.debut, selection.fin)
        FenetreNatif(
            texte = texte.subSequence(bornes.debut, bornes.fin).toString(),
            debut = bornes.debut,
            fin = bornes.fin,
            selection = selection,
            revision = revision,
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
    masque: Boolean = false,
    saisieAutomatique: Boolean = true,
    description: String? = null,
    indication: String? = null,
    descriptionDefilementRapide: String? = null,
    creerChamp: (Context) -> EditText = ::ChampEditeur,
    onInitialise: (EditText, Long) -> Unit = { _, _ -> },
    onMutation: (Long) -> Unit = {},
    onAvantDetachement: (InstantaneEditeurNatif) -> Unit = {},
    onPret: () -> Unit = {},
    colorationCode: Boolean = false,
    onColoration: (spans: Int, ms: Double) -> Unit = { _, _ -> },
) {
    val couleurTexte = androidx.compose.material3.MaterialTheme.colorScheme.onSurface.toArgb()
    val couleurIndication =
        androidx.compose.material3.MaterialTheme.colorScheme.onSurfaceVariant.toArgb()
    val couleurFond = androidx.compose.material3.MaterialTheme.colorScheme.surface.toArgb()
    val couleurCurseur = androidx.compose.material3.MaterialTheme.colorScheme.primary.toArgb()
    val couleurSelection = androidx.compose.material3.MaterialTheme.colorScheme.primary
        .copy(alpha = 0.28f)
        .toArgb()
    // Teinte tertiaire du thème, voisine du turquoise du curseur sans le
    // doubler ; légèrement estompée pour rester en retrait du texte.
    val couleurCode = androidx.compose.material3.MaterialTheme.colorScheme.tertiary
        .copy(alpha = COLORATION_ALPHA)
        .toArgb()
    val densite = LocalDensity.current
    val paddingHorizontal = with(densite) { 20.dp.roundToPx() }
    val paddingTop = with(densite) { 8.dp.roundToPx() }
    val paddingBottom = with(densite) { 48.dp.roundToPx() }
    val mutationCourante = rememberUpdatedState(onMutation)
    val detachementCourant = rememberUpdatedState(onAvantDetachement)
    val pretCourant = rememberUpdatedState(onPret)
    var defilement by remember(session) { mutableStateOf(EtatDefilementNatif()) }
    // Posée par `factory`, lue par `update` : une référence, jamais un état
    // Compose — la lire ne doit rien recomposer.
    val coloration = remember(session) { ReferenceColoration() }

    key(session) {
        Box(modifier = modifier) {
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { context ->
                    val debutInitialisation = System.nanoTime()
                    creerChamp(context).apply {
                        contentDescription = description
                        // Visible sur une note vide seulement : sans elle, un
                        // champ vide ne se distingue pas d'un écran inerte.
                        hint = indication
                        gravity = Gravity.TOP or Gravity.START
                        setHorizontallyScrolling(false)
                        isVerticalScrollBarEnabled = false
                        overScrollMode = View.OVER_SCROLL_IF_CONTENT_SCROLLS
                        inputType = InputType.TYPE_CLASS_TEXT or
                            InputType.TYPE_TEXT_FLAG_MULTI_LINE or
                            InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
                        // Jamais d'éditeur plein écran, en paysage : il
                        // recopierait toute la note dans sa propre vue
                        // (`ExtractedText`), à chaque modification.
                        imeOptions = EditorInfo.IME_FLAG_NO_EXTRACT_UI or
                            EditorInfo.IME_FLAG_NO_FULLSCREEN
                        breakStrategy = Layout.BREAK_STRATEGY_SIMPLE
                        hyphenationFrequency = Layout.HYPHENATION_FREQUENCY_NONE
                        typeface = Typeface.MONOSPACE
                        textSize = 15f
                        // Valeur de la sonde mesurée : la modifier change le coût
                        // du StaticLayout initial sur les 8 853 lignes.
                        setLineSpacing(0f, 1.35f)
                        setTextColor(couleurTexte)
                        setHintTextColor(couleurIndication)
                        setBackgroundColor(couleurFond)
                        highlightColor = couleurSelection
                        setPadding(
                            paddingHorizontal,
                            paddingTop,
                            paddingHorizontal,
                            paddingBottom,
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

                            if (colorationCode) {
                                coloration.valeur = ColorationFenetre(this@apply, couleurCode, onColoration)
                            }

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
                                    ) {
                                        // Seules les lignes touchées sont relues.
                                        val suivi = coloration.valeur ?: return
                                        (s as? Editable)?.let { suivi.apresModification(it, start, before, count) }
                                    }

                                    override fun afterTextChanged(s: Editable?) {
                                        session.signalerModification(this@apply)?.let {
                                            mutationCourante.value(it)
                                        }
                                    }
                                },
                            )
                            setOnScrollChangeListener { _, _, _, _, _ ->
                                defilement = etatDefilementNatif()
                                coloration.valeur?.suivre()
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
                                        // La mise en page existe enfin : on sait ce
                                        // qui est à l'écran.
                                        coloration.valeur?.suivre(force = true)
                                        pretCourant.value()
                                        return true
                                    }
                                },
                            )
                        }
                    }
                },
                update = { champ ->
                    // Styles et visibilité seulement : jamais de setText dans ce bloc.
                    champ.masquer(masque)
                    // Réglage de confidentialité, pas de performance : voir
                    // `PreferencesAffichage.saisieAutomatiqueEdition`.
                    (champ as? ChampEditeur)?.saisieAutomatique = saisieAutomatique
                    champ.setTextColor(couleurTexte)
                    champ.setHintTextColor(couleurIndication)
                    champ.setBackgroundColor(couleurFond)
                    champ.highlightColor = couleurSelection
                    champ.teinterCurseur(couleurCurseur)
                    coloration.valeur?.changerCouleur(couleurCode)
                },
                onRelease = { champ ->
                    champ.setOnScrollChangeListener(null)
                    session.instantane()?.let { detachementCourant.value(it) }
                    session.detacher(champ)
                },
            )

            if (!masque) {
                BandeDefilementRapideNatif(
                    etat = defilement,
                    description = descriptionDefilementRapide,
                    onDefiler = session::defilerVers,
                    modifier = Modifier.align(Alignment.CenterEnd),
                )
            }
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

/**
 * Retire le champ de la vue sans le détruire : texte, mise en page,
 * défilement et pile d'annulation restent en place.
 *
 * C'est ce que fait l'aperçu. Reconstruire le champ au retour coûtait
 * 1,4 s de mise en page sur 285 ko et vidait l'historique d'annulation
 * (section 7 bis d'`ARCHITECTURE.md`).
 */
private fun EditText.masquer(masque: Boolean) {
    val visibilite = if (masque) View.INVISIBLE else View.VISIBLE
    if (visibility == visibilite) return
    if (masque) {
        (context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager)
            .hideSoftInputFromWindow(windowToken, 0)
        clearFocus()
    }
    visibility = visibilite
}

/**
 * `EditText` dont la saisie automatique se coupe pour de bon.
 *
 * `importantForAutofill` ne suffit pas, ni sur le champ ni sur la vue hôte de
 * Compose : le système envoie quand même une requête au service de saisie
 * automatique dès que le champ prend le focus, un `EditText` étant réputé
 * remplissable d'office. Seul un type d'autofill nul l'en empêche. Constaté sur
 * le banc (Android 15, `dumpsys autofill`) : une session vers le service Google
 * et une vers l'autofill « augmenté » avec les deux exclusions, aucune avec
 * celle-ci.
 *
 * `EditText` et non `AppCompatEditText`, malgré lint : l'application n'est pas
 * une activité AppCompat et n'en dépend pas directement (la bibliothèque n'arrive
 * que par AppAuth). Ses aides — emoji, teinte, réception de contenu — viendraient
 * s'ajouter au champ dont les mesures sur la note de 295 ko ont été faites sans.
 */
@SuppressLint("AppCompatCustomView")
internal open class ChampEditeur(context: Context) : EditText(context) {
    var saisieAutomatique: Boolean = true

    private var suiviCurseurAutorise = true

    /**
     * `TextView` ramène le curseur à l'écran à chaque dessin qui suit un
     * changement de texte **ou de spans**. Colorer la fenêtre visible pendant
     * un défilement en est un : au relâchement du doigt, la note retombait là
     * où se trouvait le curseur (pile relevée sur appareil le 4 octobre 2026 :
     * `TextView.onPreDraw` → `bringPointIntoView` → `scrollTo`). La sonde, dont
     * le champ n'avait pas le focus, ne le montrait pas.
     *
     * Le suivi n'est donc accordé que si quelque chose qui concerne vraiment le
     * curseur a bougé — sélection, texte, taille du champ (clavier), focus —, et
     * il est consommé par le dessin qui l'exécute.
     */
    private fun autoriserSuiviCurseur() {
        suiviCurseurAutorise = true
    }

    override fun onSelectionChanged(selStart: Int, selEnd: Int) {
        autoriserSuiviCurseur()
        // Une poignée, un toucher : Android sait de nouveau quelle extrémité suivre.
        if (!selectionAuDoigt) extremiteTiree = null
        super.onSelectionChanged(selStart, selEnd)
        if (selStart != selEnd && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            // Les poignées ne s'affichent qu'après ce rappel, et `Editor` les
            // crée à la première sélection : on les cherche un peu plus tard.
            removeCallbacks(chercherPoignees)
            essaisRecherchePoignees = ESSAIS_RECHERCHE_POIGNEES
            postDelayed(chercherPoignees, DELAI_RECHERCHE_POIGNEES_MS)
        }
    }

    override fun onTextChanged(texte: CharSequence?, start: Int, avant: Int, apres: Int) {
        autoriserSuiviCurseur()
        super.onTextChanged(texte, start, avant, apres)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        autoriserSuiviCurseur()
        super.onSizeChanged(w, h, oldw, oldh)
    }

    override fun onFocusChanged(focused: Boolean, direction: Int, previouslyFocusedRect: android.graphics.Rect?) {
        autoriserSuiviCurseur()
        super.onFocusChanged(focused, direction, previouslyFocusedRect)
    }

    override fun bringPointIntoView(offset: Int): Boolean {
        val autorise = suiviCurseurAutorise
        suiviCurseurAutorise = false
        val cible = extremiteTiree?.takeIf { it in 0..length() } ?: offset
        return autorise && super.bringPointIntoView(cible)
    }

    // --- Défilement pendant une sélection au doigt ---------------------------
    //
    // Après un appui long ou un double toucher, Android étend la sélection en
    // suivant le doigt, mais la place une hauteur de poignée *au-dessus* de
    // lui : la ligne visée n'atteint jamais le bord du champ, et le texte ne
    // défile pas. Constaté sur
    // le banc (Redmi Note 12) : doigt au bas du champ, rien ne bouge ; doigt sur
    // la barre de format, une ligne par mouvement, rien quand il s'immobilise.
    //
    // Le geste reste celui d'Android : on ne fait que défiler, puis lui
    // rejouer le dernier mouvement du doigt, pour qu'il recalcule la sélection
    // sur le texte qui vient de passer dessous.

    /** Vrai d'un geste qui a ouvert ou changé une sélection, jusqu'au lever du doigt. */
    private var selectionAuDoigt = false

    /** Faux tant que le doigt n'a pas quitté l'endroit de l'appui long. */
    private var selectionArmee = false
    private var yAppui = 0f
    private var dernierMouvement: MotionEvent? = null
    private var vitesseSelection = 0f
    private var resteDefilement = 0f
    private var instantPrecedent = 0L
    private var defilementLance = false
    private val seuilGlisse = ViewConfiguration.get(context).scaledTouchSlop

    private val pasDefilementSelection = object : Runnable {
        override fun run() {
            val mouvement = dernierMouvement
            if (!(selectionAuDoigt || poigneeTenue != null) || vitesseSelection == 0f || mouvement == null) {
                defilementLance = false
                return
            }
            val maintenant = SystemClock.uptimeMillis()
            // Borné : une image sautée ne doit pas faire bondir le texte.
            val secondes = (maintenant - instantPrecedent).coerceIn(0L, 50L) / 1000f
            instantPrecedent = maintenant
            resteDefilement += vitesseSelection * secondes
            val pas = resteDefilement.toInt()
            resteDefilement -= pas

            val hauteurVisible = height - compoundPaddingTop - compoundPaddingBottom
            val maximum = ((layout?.height ?: 0) - hauteurVisible).coerceAtLeast(0)
            val avant = scrollY
            scrollTo(scrollX, (avant + pas).coerceIn(0, maximum))
            if (scrollY != avant) rejouer(mouvement)
            postOnAnimation(this)
        }
    }

    /**
     * Extrémité que le doigt a tirée, à garder en vue jusqu'au prochain
     * changement de sélection.
     *
     * Au lever du doigt, Android ouvre le clavier ; le champ rétrécit et
     * `TextView` ramène à l'écran la *fin* de la sélection. Après une sélection
     * tirée vers le haut, c'est l'extrémité restée en place, parfois des écrans
     * plus bas : le texte sautait loin de ce qu'on venait de sélectionner.
     *
     * Nullable plutôt que -1 : `TextView` appelle déjà ces méthodes depuis son
     * constructeur, avant les initialiseurs de cette classe, et `null` est la
     * valeur par défaut de la JVM.
     */
    private var extremiteTiree: Int? = null
    private var debutAppui = -1

    /** Sélection au moment où le doigt s'est posé : la comparer dit si le geste sélectionne. */
    private var selectionAuPoser = -1L

    // --- Double toucher ou toucher puis défilement ---------------------------
    //
    // Un toucher suivi, en moins de 300 ms, d'un glissé : `Editor` y voit un
    // double toucher glissé, qui sélectionne, alors qu'on voulait souvent
    // défiler. Relevé sur les gestes de l'utilisateur : un double toucher voulu
    // laisse le doigt presque immobile 250 ms (32 px au plus, sur huit gestes) ;
    // le toucher puis défilement l'avait déjà emmené de 465 px au bout de
    // 100 ms.
    //
    // Défaire la sélection après coup ne tient pas (code d'`Editor`, Android 13) :
    // seul un lever clôt son glissé de sélection — il ignore l'annulation —, et
    // ce lever relance une classification différée qui *réapplique* la
    // sélection, puis relâche l'interception au milieu de l'événement, ce que
    // l'interop Compose transforme en annulation du geste entier. Tout cela
    // constaté sur le banc.
    //
    // On décide donc *avant* `Editor` : le poser qui ferait un double toucher
    // est retenu au plus [DELAI_INTENTION_MS]. Si le doigt file entre-temps,
    // `Editor` reçoit un poser daté au-delà du délai de double toucher — un
    // premier toucher, et le champ défile normalement ; sinon il reçoit le
    // poser d'origine, et la sélection se fait comme avant, avec ce retard.

    private var poserRetenu: MotionEvent? = null
    private val mouvementsRetenus = ArrayList<MotionEvent>()
    private val delaiDoubleToucher = ViewConfiguration.getDoubleTapTimeout().toLong()
    private val ecartDoubleToucher = ViewConfiguration.get(context).scaledDoubleTapSlop
    // `scaledDoubleTapTouchSlop` est cachée ; Android la fixe égale à celle-ci.
    private val zoneToucher = ViewConfiguration.get(context).scaledTouchSlop

    /** Le geste précédent, tel qu'`Editor` le juge pour reconnaître un double toucher. */
    private var dernierPoser = 0L
    private var dernierLever = Long.MIN_VALUE / 2
    private var xDernierPoser = 0f
    private var yDernierPoser = 0f
    private var resteDansLaZone = false

    private val deciderSelection = Runnable { libererPoser(defilement = false) }

    @SuppressLint("ClickableViewAccessibility") // Le clic reste celui de TextView.
    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (poserRetenu != null) return retenir(event)
        if (event.actionMasked == MotionEvent.ACTION_DOWN && seraitDoubleToucher(event)) {
            poserRetenu = MotionEvent.obtain(event)
            postDelayed(deciderSelection, DELAI_INTENTION_MS)
            return true
        }
        return traiter(event)
    }

    /** Même règle qu'`EditorTouchState`, un peu plus large : retenir à tort ne coûte qu'un délai. */
    private fun seraitDoubleToucher(poser: MotionEvent): Boolean =
        resteDansLaZone &&
            poser.eventTime - dernierLever <= delaiDoubleToucher &&
            dernierLever - dernierPoser <= delaiDoubleToucher &&
            hypot(poser.x - xDernierPoser, poser.y - yDernierPoser) <= ecartDoubleToucher

    private fun retenir(event: MotionEvent): Boolean {
        val poser = poserRetenu ?: return traiter(event)
        when (event.actionMasked) {
            MotionEvent.ACTION_MOVE -> {
                mouvementsRetenus += MotionEvent.obtain(event)
                val file = abs(event.y - poser.y) >
                    SEUIL_DEFILEMENT_DP * resources.displayMetrics.density
                if (file) libererPoser(defilement = true)
                return true
            }
            else -> {
                libererPoser(defilement = false)
                return traiter(event)
            }
        }
    }

    /**
     * Transmet enfin le poser retenu, puis les mouvements reçus depuis. Pour un
     * défilement, le poser est redaté : `Editor` compte le délai depuis le
     * dernier lever, et ne voit plus de double toucher.
     */
    private fun libererPoser(defilement: Boolean) {
        val poser = poserRetenu ?: return
        poserRetenu = null
        removeCallbacks(deciderSelection)
        val transmis = if (defilement) {
            poser.redate(maxOf(poser.eventTime, dernierLever + delaiDoubleToucher + 1))
        } else {
            poser
        }
        traiter(transmis)
        if (transmis !== poser) transmis.recycle()
        poser.recycle()
        for (mouvement in mouvementsRetenus) {
            traiter(mouvement)
            mouvement.recycle()
        }
        mouvementsRetenus.clear()
    }

    /** Le même poser, au même endroit, daté de [instant] : `MotionEvent` n'a pas d'accesseur pour l'heure. */
    private fun MotionEvent.redate(instant: Long): MotionEvent = MotionEvent.obtain(
        instant,
        instant,
        action,
        pointerCount,
        Array(pointerCount) { i -> MotionEvent.PointerProperties().also { getPointerProperties(i, it) } },
        Array(pointerCount) { i -> MotionEvent.PointerCoords().also { getPointerCoords(i, it) } },
        metaState,
        buttonState,
        xPrecision,
        yPrecision,
        deviceId,
        edgeFlags,
        source,
        flags,
    )

    private fun traiter(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                extremiteTiree = null
                arreterDefilementSelection()
                selectionAuPoser = bornesSelection()
                dernierPoser = event.eventTime
                xDernierPoser = event.x
                yDernierPoser = event.y
                resteDansLaZone = true
            }
            MotionEvent.ACTION_MOVE -> {
                if (hypot(event.x - xDernierPoser, event.y - yDernierPoser) > zoneToucher) {
                    resteDansLaZone = false
                }
                suivreDoigtSelection(event)
            }
            MotionEvent.ACTION_UP -> dernierLever = event.eventTime
        }
        val traite = super.onTouchEvent(event)
        // Le geste sélectionne dès que, doigt posé, la sélection change et
        // n'est pas vide. Peu importe ce qui l'a ouverte : un appui long, mais
        // aussi — champ déjà actif, clavier ouvert — un double toucher suivi
        // d'un glissé, qui ne passe jamais par `performLongClick`. Constaté
        // sur le banc : armé sur le seul appui long, le défilement ne partait
        // jamais clavier ouvert. Un défilement à la main ne change pas la
        // sélection ; un toucher la vide.
        if (!selectionAuDoigt &&
            (event.actionMasked == MotionEvent.ACTION_DOWN || event.actionMasked == MotionEvent.ACTION_MOVE) &&
            hasSelection() && bornesSelection() != selectionAuPoser
        ) {
            selectionAuDoigt = true
            selectionArmee = false
            yAppui = event.y
            debutAppui = selectionStart
        }
        // Après `super` : Android peut encore retoucher la sélection au lever.
        if (event.actionMasked == MotionEvent.ACTION_UP ||
            event.actionMasked == MotionEvent.ACTION_CANCEL
        ) {
            val tiree = selectionAuDoigt && hasSelection()
            arreterDefilementSelection()
            if (tiree) extremiteTiree = if (selectionStart < debutAppui) selectionStart else selectionEnd
        }
        return traite
    }

    private fun suivreDoigtSelection(event: MotionEvent) {
        if (!selectionAuDoigt || !hasSelection()) return
        // Un appui long près du bord ne doit pas défiler de lui-même : on
        // attend que le doigt ait vraiment commencé à étendre.
        if (!selectionArmee) {
            if (abs(event.y - yAppui) < seuilGlisse) return
            selectionArmee = true
        }
        relancerDefilementSelection(event, event.y)
    }

    /** Retient [mouvement] pour le rejeu, et règle la vitesse sur [y], hauteur du doigt dans le champ. */
    private fun relancerDefilementSelection(mouvement: MotionEvent, y: Float) {
        dernierMouvement?.recycle()
        dernierMouvement = MotionEvent.obtainNoHistory(mouvement)
        val densite = resources.displayMetrics.density
        vitesseSelection = vitesseDefilementBord(
            y = y,
            haut = 0f,
            bas = height.toFloat(),
            bande = BANDE_DEFILEMENT_SELECTION_DP * densite,
            vitesseMax = VITESSE_DEFILEMENT_SELECTION_DP_S * densite,
        )
        if (vitesseSelection != 0f && !defilementLance) {
            defilementLance = true
            resteDefilement = 0f
            instantPrecedent = SystemClock.uptimeMillis()
            postOnAnimation(pasDefilementSelection)
        }
    }

    /** Rejoue le dernier mouvement à qui tient la sélection, pour qu'il la recalcule. */
    private fun rejouer(mouvement: MotionEvent) {
        val poignee = poigneeTenue
        if (poignee == null) {
            super.onTouchEvent(mouvement)
            return
        }
        rejeuPoignee = true
        try {
            poignee.dispatchTouchEvent(mouvement)
        } finally {
            rejeuPoignee = false
        }
    }

    // --- Les poignées ---------------------------------------------------------
    //
    // Une poignée de sélection est une vue d'`Editor` posée dans une fenêtre à
    // elle (`PopupWindow`) : son toucher n'arrive jamais au champ. Depuis
    // Android 10, `WindowInspector` liste les fenêtres de l'application ; on y
    // retrouve la poignée et on lui pose un écouteur qui observe sans rien
    // consommer. Le reste est le même mécanisme que pour le doigt : défiler,
    // puis rejouer à la poignée son dernier mouvement. Elle calcule la
    // sélection depuis les coordonnées d'écran du doigt (`getRawX`, code
    // d'`Editor`, Android 13) : rejouée après un pas, elle sélectionne le texte
    // qui vient de passer sous le doigt.
    //
    // On la reconnaît au nom de sa classe, `Editor$SelectionHandleView` : rien
    // de public ne la désigne. Si une version d'Android le change, on ne la
    // trouve plus, et la poignée retrouve le comportement natif — rien ne casse.

    private var poigneeTenue: View? = null
    private var rejeuPoignee = false
    private val poigneesEcoutees = java.util.WeakHashMap<View, Unit>()
    private val positionChamp = IntArray(2)

    // N'observe que : renvoie toujours faux, la poignée garde son traitement.
    @SuppressLint("ClickableViewAccessibility")
    private val ecouteurPoignee = View.OnTouchListener { poignee, event ->
        if (!rejeuPoignee) suivrePoignee(poignee, event)
        false
    }

    private var essaisRecherchePoignees = 0

    /**
     * `Editor` montre ses poignées un moment après la sélection, et ce moment
     * varie : on cherche jusqu'à les avoir toutes deux, en quelques essais.
     */
    private val chercherPoignees: Runnable = object : Runnable {
        override fun run() {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q || !hasSelection()) return
            if (brancherPoignees() >= 2 || --essaisRecherchePoignees <= 0) return
            postDelayed(this, DELAI_RECHERCHE_POIGNEES_MS)
        }
    }

    /** Pose l'écouteur sur les poignées visibles ; renvoie combien sont écoutées. */
    @RequiresApi(Build.VERSION_CODES.Q)
    private fun brancherPoignees(): Int {
        var ecoutees = 0
        for (racine in WindowInspector.getGlobalWindowViews()) {
            if (racine === rootView) continue
            pourChaqueVue(racine) { vue ->
                if (vue.javaClass.name.endsWith(CLASSE_POIGNEE)) {
                    if (vue !in poigneesEcoutees) {
                        vue.setOnTouchListener(ecouteurPoignee)
                        poigneesEcoutees[vue] = Unit
                    }
                    ecoutees++
                }
            }
        }
        return ecoutees
    }

    private fun pourChaqueVue(vue: View, action: (View) -> Unit) {
        action(vue)
        if (vue is android.view.ViewGroup) {
            for (i in 0 until vue.childCount) pourChaqueVue(vue.getChildAt(i), action)
        }
    }

    private fun suivrePoignee(poignee: View, event: MotionEvent) {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                arreterDefilementSelection()
                // Le champ peut en avoir d'autres à l'écran : n'agir que pour
                // une poignée tenue pendant que celui-ci a une sélection.
                poigneeTenue = if (hasSelection() && isFocused) poignee else null
            }
            MotionEvent.ACTION_MOVE -> if (poigneeTenue === poignee) {
                getLocationOnScreen(positionChamp)
                relancerDefilementSelection(event, event.rawY - positionChamp[1])
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                poigneeTenue = null
                arreterDefilementSelection()
            }
        }
    }

    private fun bornesSelection(): Long =
        (selectionStart.toLong() shl 32) or (selectionEnd.toLong() and 0xFFFFFFFFL)

    private fun arreterDefilementSelection() {
        selectionAuDoigt = false
        vitesseSelection = 0f
        defilementLance = false
        removeCallbacks(pasDefilementSelection)
        dernierMouvement?.recycle()
        dernierMouvement = null
    }

    override fun onDetachedFromWindow() {
        removeCallbacks(chercherPoignees)
        poigneeTenue = null
        removeCallbacks(deciderSelection)
        poserRetenu?.recycle()
        poserRetenu = null
        mouvementsRetenus.forEach { it.recycle() }
        mouvementsRetenus.clear()
        arreterDefilementSelection()
        super.onDetachedFromWindow()
    }

    override fun getAutofillType(): Int =
        if (saisieAutomatique) super.getAutofillType() else AUTOFILL_TYPE_NONE
}
