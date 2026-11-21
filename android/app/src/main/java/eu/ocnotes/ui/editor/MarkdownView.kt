package eu.ocnotes.ui.editor

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.net.Uri
import android.os.SystemClock
import android.view.MotionEvent
import android.view.Window
import android.widget.Toast
import androidx.annotation.MainThread
import androidx.compose.foundation.background
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckBox
import androidx.compose.material.icons.filled.CheckBoxOutlineBlank
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.AwaitPointerEventScope
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.LocalPinnableContainer
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import eu.ocnotes.R
import eu.ocnotes.data.BlockKind
import eu.ocnotes.data.NoteBlockDto
import eu.ocnotes.data.SpanStyleId
import eu.ocnotes.ui.theme.StyleEditeur
import kotlinx.coroutines.flow.collectLatest

/**
 * Aperçu d'une note, en lecture — à une exception près : la case d'une tâche
 * se coche d'un toucher quand [onBasculerTache] n'est pas nul (voir [Tache]).
 *
 * # Pourquoi du Compose natif, et pas un WebView
 *
 * Tout le travail d'analyse est fait en Go et arrive ici sous forme d'une
 * liste plate de blocs produite par `RenderNoteJSON`. Ce
 * fichier ne fait que choisir un style par bloc : il n'y a aucune règle de
 * Markdown ici, donc rien qui mériterait un test instrumenté.
 *
 * En échange, l'aperçu hérite de la typographie Material3, du thème sombre et
 * de la sélection de texte sans une ligne pour eux. Un WebView aurait demandé
 * de réécrire les trois en CSS, à côté du reste de l'application.
 */
@Composable
fun VueMarkdown(
    blocs: List<NoteBlockDto>,
    modifier: Modifier = Modifier,
    echelle: Float = 1f,
    /** Reçoit la tâche dont la case est touchée ; nul sur une note non modifiable. */
    onBasculerTache: ((NoteBlockDto) -> Unit)? = null,
    /**
     * L'état de la liste, levé quand l'écran doit la faire défiler — le saut
     * du plan — ou savoir ce qu'elle montre. Ses indices sont ceux des
     * éléments, pas des blocs : voir [indiceElement].
     */
    etatListe: LazyListState = rememberLazyListState(),
    /**
     * Les occurrences d'une recherche ouverte, `null` sinon. Chaque élément ne
     * lit que les siennes, au moment où la liste le dessine : la note entière
     * n'est jamais recomposée pour surligner.
     */
    surlignage: SurlignageApercu? = null,
    /** Reçoit la mise en page du texte qui porte l'occurrence courante. */
    positionOccurrence: PositionOccurrence? = null,
) {
    // La loupe agit en multipliant l'échelle de police : tout le texte de
    // l'aperçu est en `sp`, donc titres, listes, code et tableaux grossissent
    // ensemble sans qu'aucun bloc ait à le savoir. La densité, elle, ne bouge
    // pas : marges, icônes et défilement de sélection restent en `dp`.
    val densite = LocalDensity.current
    CompositionLocalProvider(
        LocalDensity provides Density(densite.density, densite.fontScale * echelle),
    ) {
        ContenuMarkdown(blocs, modifier, onBasculerTache, etatListe, surlignage, positionOccurrence)
    }
}

@Composable
private fun ContenuMarkdown(
    blocs: List<NoteBlockDto>,
    modifier: Modifier,
    onBasculerTache: ((NoteBlockDto) -> Unit)?,
    etatListe: LazyListState,
    surlignage: SurlignageApercu?,
    positionOccurrence: PositionOccurrence?,
) {
    if (blocs.isEmpty()) {
        Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(
                text = stringResource(R.string.apercu_vide),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        return
    }

    // Les lignes d'un tableau arrivent volontairement à plat depuis Go, comme
    // les autres blocs. Les regrouper ici permet au tableau entier de partager
    // un défilement horizontal et la même largeur de colonnes.
    val elements = grouperPourApercu(blocs)
    // Un défilement horizontal par bloc de code découpé, partagé par ses
    // morceaux. Gardé hors des éléments de la liste : un morceau qui sort de
    // l'écran puis revient retrouve la position des autres.
    val defilementsCode = remember(blocs) { HashMap<Int, DefilementCode>() }

    // Un lien touché n'est jamais ouvert d'office : sa destination réelle est
    // montrée d'abord, parce que le libellé affiché peut dire autre chose.
    var lienAConfirmer by remember { mutableStateOf<String?>(null) }
    lienAConfirmer?.let { ConfirmationLien(it, onFermer = { lienAConfirmer = null }) }

    // Vrai depuis le premier appui long (qui peut ouvrir une sélection) jusqu'au
    // prochain toucher simple (qui l'efface) : voir [Epingle].
    var selectionPossible by remember { mutableStateOf(false) }

    // Le doigt qui étend une sélection, jamais lu pendant la composition : il
    // change à chaque mouvement, et seul [defilerPendantSelection] s'en sert.
    val doigt = remember { mutableStateOf<Float?>(null) }
    val reperes = remember { ReperesListe() }
    val suivi = remember { SuiviSelection() }
    val vue = LocalView.current
    val densite = LocalDensity.current.density
    DisposableEffect(vue) {
        val fenetre = vue.context.activite()?.window
        val origine = fenetre?.callback
        val enveloppe = origine?.let { EnveloppeFenetre(it, suivi) }
        if (enveloppe != null) fenetre.callback = enveloppe
        onDispose {
            // Si quelqu'un a enveloppé la fenêtre après nous, on reste en
            // place : `suivi` inactif, l'enveloppe ne fait que transmettre.
            if (fenetre != null && fenetre.callback === enveloppe) fenetre.callback = origine
            suivi.oublier()
        }
    }
    LaunchedEffect(etatListe, densite) {
        snapshotFlow { doigt.value != null }.collectLatest { actif ->
            if (actif) defilerPendantSelection(etatListe, reperes, suivi, densite) { doigt.value }
        }
    }

    CompositionLocalProvider(LocalOuvrirLien provides { lienAConfirmer = it }) {
        SelectionContainer {
            LazyColumn(
                state = etatListe,
                modifier = modifier
                    .fillMaxSize()
                    .onGloballyPositioned { reperes.coordonnees = it }
                    .pointerInput(Unit) {
                        suivreSelection(
                            possible = { selectionPossible = it },
                            appuiLong = { reperes.retenirOrigine(it) },
                            doigt = { doigt.value = it },
                            decalage = { suivi.decalage },
                        )
                    },
                contentPadding = PaddingValues(horizontal = 20.dp, vertical = 16.dp),
            ) {
                itemsIndexed(
                    items = elements,
                    contentType = { _, element ->
                        when (element) {
                            is ElementApercu.Bloc -> element.bloc.kind
                            is ElementApercu.Tableau -> "tableau"
                        }
                    }
                ) { index, element ->
                    Epingle(actif = selectionPossible)
                    DisposableEffect(index) { onDispose { reperes.elements.remove(index) } }
                    // L'écart entre deux blocs, sauf avant un morceau qui
                    // continue le précédent : un paragraphe découpé par Go doit
                    // se lire d'un seul tenant. L'écart est hors du Box repéré,
                    // pour que sa position reste celle du contenu.
                    Column {
                        if (index > 0 && !(element is ElementApercu.Bloc && element.bloc.cont)) {
                            Spacer(Modifier.height(ECART_BLOCS_DP.dp))
                        }
                        // Seul l'élément de l'occurrence courante rapporte la
                        // mise en page de son texte (voir [PositionOccurrence]).
                        val rapport: (TextLayoutResult) -> Unit = if (
                            positionOccurrence != null && surlignage?.courante?.element == index
                        ) {
                            { mise -> positionOccurrence.retenir(index, mise) }
                        } else {
                            RAPPORT_MUET
                        }
                        Box(Modifier.onPlaced { reperes.elements[index] = it }) {
                            CompositionLocalProvider(LocalRapportOccurrence provides rapport) {
                                when (element) {
                                    is ElementApercu.Bloc -> {
                                        // Seul un bloc de code découpé partage son
                                        // défilement ; les autres gardent le leur.
                                        val decoupe = element.bloc.cont || element.avantSuite
                                        val defilement = if (element.bloc.kind == BlockKind.CODE && decoupe) {
                                            defilementsCode.getOrPut(element.tete) { DefilementCode() }
                                        } else {
                                            null
                                        }
                                        Bloc(
                                            element.bloc,
                                            element.avantSuite,
                                            onBasculerTache,
                                            element.suiteDeTache,
                                            defilement,
                                            surlignage?.marques(index),
                                        )
                                    }
                                    is ElementApercu.Tableau -> Tableau(element.lignes) { ligne, colonne ->
                                        surlignage?.marques(index, ligne, colonne)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * Garde l'élément de liste en composition tant qu'une sélection est possible.
 *
 * La sélection retient l'identifiant de ses deux extrémités. Si le bloc d'une
 * extrémité sort de la composition parce qu'on a défilé, étendre la sélection
 * cherche un identifiant disparu et Compose lève `NoSuchElementException:
 * Cannot find value for key N` (`SelectionManager.getSelectionLayout`), ce qui
 * tue l'application. Constaté en Compose 1.7, 1.8.3 et 1.11.4. Un élément
 * épinglé ne quitte plus la composition ; il est relâché quand la sélection
 * s'efface ou que l'élément est retiré de la liste.
 *
 * Une `Column` défilante règlerait aussi le défaut, mais compose toute la note
 * d'un coup : 11 s d'ouverture et 66 % d'images en retard sur 295 ko, contre
 * 0,75 s (carnet : `ANDROID-APERCU-SELECTION`).
 */
@Composable
private fun Epingle(actif: Boolean) {
    val conteneur = LocalPinnableContainer.current
    DisposableEffect(conteneur, actif) {
        val prise = if (actif) conteneur?.pin() else null
        onDispose { prise?.release() }
    }
}

/**
 * Coordonnées de la liste et de ses éléments, posées à la mise en page, lues
 * par le défilement pendant une sélection.
 */
private class ReperesListe {
    var coordonnees: LayoutCoordinates? = null

    /** Éléments composés, par index. Un élément épinglé hors de l'écran y reste. */
    val elements = HashMap<Int, LayoutCoordinates>()

    /** L'élément où l'appui long a ouvert la sélection, et sa hauteur d'alors. */
    private var origine: LayoutCoordinates? = null
    private var hautOrigine = 0f

    /** Retient l'élément sous [y], hauteur dans la liste, au moment de l'appui long. */
    fun retenirOrigine(y: Float) {
        origine = null
        val liste = coordonnees?.takeIf { it.isAttached } ?: return
        for (element in elements.values) {
            if (!element.isAttached) continue
            val haut = liste.localPositionOf(element, Offset.Zero).y
            if (y >= haut && y < haut + element.size.height) {
                origine = element
                hautOrigine = haut
                return
            }
        }
    }

    /**
     * De combien l'élément d'origine est monté depuis l'appui long, tel que
     * Compose l'a *placé* — voir [SuiviSelection] ; `null` s'il n'est plus là.
     */
    fun montee(): Float? {
        val liste = coordonnees?.takeIf { it.isAttached } ?: return null
        val element = origine?.takeIf { it.isAttached } ?: return null
        return hautOrigine - liste.localPositionOf(element, Offset.Zero).y
    }
}

private enum class DebutGeste { TOUCHER, DEFILEMENT }

/**
 * Signale quand une sélection de texte devient possible, ou s'efface, et suit
 * le doigt qui l'étend.
 *
 * Compose n'expose ni la sélection ni son registre (types internes) : on
 * déduit son existence du geste. Un appui long l'ouvre ; un toucher simple la
 * ferme. Un défilement ne change rien. Ne consomme aucun événement. Le cas
 * « sélection effacée autrement » (copier, retour) laisse l'épinglage actif
 * jusqu'au prochain toucher : un peu de mémoire, jamais un plantage.
 *
 * Après l'appui long, [doigt] reçoit la hauteur du doigt dans la liste jusqu'au
 * lever, puis `null` — pas avant que le doigt ait quitté l'endroit de l'appui,
 * pour qu'un appui long près du bord ne fasse pas défiler de lui-même. Les
 * événements arrivent décalés de [decalage] (voir [SuiviSelection]) : on le
 * retire pour rendre la vraie position.
 */
private suspend fun PointerInputScope.suivreSelection(
    possible: (Boolean) -> Unit,
    appuiLong: (Float) -> Unit,
    doigt: (Float?) -> Unit,
    decalage: () -> Float,
) {
    awaitEachGesture {
        val appui = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
        val debut = withTimeoutOrNull(viewConfiguration.longPressTimeoutMillis) {
            debutGeste(appui, viewConfiguration.touchSlop)
        }
        when (debut) {
            // Un toucher pris par un enfant — la case d'une tâche — n'efface
            // pas la sélection de Compose, qui ignore un lever consommé. Il ne
            // doit donc pas relâcher l'épinglage non plus : la sélection
            // survivrait sans lui, et le plantage qu'il évite reviendrait. Le
            // même lever repasse ici à la passe finale, après les enfants.
            DebutGeste.TOUCHER -> {
                val consomme = awaitPointerEvent(PointerEventPass.Final).changes
                    .any { it.id == appui.id && it.isConsumed }
                if (!consomme) possible(false)
            }
            DebutGeste.DEFILEMENT -> Unit
            null -> {
                possible(true)
                appuiLong(appui.position.y - decalage())
                try {
                    suivreDoigt(appui, viewConfiguration.touchSlop) { y -> doigt(y - decalage()) }
                } finally {
                    doigt(null)
                }
            }
        }
    }
}

/** Attend que le doigt se lève (toucher) ou s'éloigne (défilement). */
private suspend fun AwaitPointerEventScope.debutGeste(
    appui: PointerInputChange,
    seuil: Float,
): DebutGeste {
    while (true) {
        val change = awaitPointerEvent(PointerEventPass.Initial).changes
            .firstOrNull { it.id == appui.id } ?: return DebutGeste.DEFILEMENT
        if (!change.pressed) return DebutGeste.TOUCHER
        if ((change.position - appui.position).getDistance() > seuil) return DebutGeste.DEFILEMENT
    }
}

private suspend fun AwaitPointerEventScope.suivreDoigt(
    appui: PointerInputChange,
    seuil: Float,
    hauteur: (Float) -> Unit,
) {
    var arme = false
    while (true) {
        val change = awaitPointerEvent(PointerEventPass.Initial).changes
            .firstOrNull { it.id == appui.id } ?: return
        if (!change.pressed) return
        if (!arme && (change.position - appui.position).getDistance() < seuil) continue
        arme = true
        hauteur(change.position.y)
    }
}

/**
 * Fait défiler la liste tant que le doigt qui sélectionne reste près d'un bord
 * (règle de vitesse : [vitesseDefilementBord]).
 */
private suspend fun defilerPendantSelection(
    etatListe: LazyListState,
    reperes: ReperesListe,
    suivi: SuiviSelection,
    densite: Float,
    doigt: () -> Float?,
) {
    val bande = BANDE_DEFILEMENT_SELECTION_DP * densite
    val vitesseMax = VITESSE_DEFILEMENT_SELECTION_DP_S * densite
    var precedent = withFrameNanos { it }
    while (true) {
        val maintenant = withFrameNanos { it }
        // Borné : une image sautée ne doit pas faire bondir le texte.
        val secondes = ((maintenant - precedent) / 1e9f).coerceIn(0f, 0.05f)
        precedent = maintenant
        val y = doigt() ?: return
        // Mesuré à chaque image, défilement ou non : la mise en page qui suit
        // un pas n'a lieu qu'après lui, et le dernier pas avant la sortie de
        // bande doit lui aussi être compté.
        reperes.montee()?.let(suivi::recaler)
        val coordonnees = reperes.coordonnees?.takeIf { it.isAttached } ?: continue
        val vitesse = vitesseDefilementBord(
            y = y,
            haut = 0f,
            bas = coordonnees.size.height.toFloat(),
            bande = bande,
            vitesseMax = vitesseMax,
        )
        if (vitesse == 0f) continue
        etatListe.scrollBy(vitesse * secondes)
    }
}

/**
 * Fait suivre au geste de sélection le texte qui défile sous lui.
 *
 * Compose 1.7 suit le glissé qui prolonge un appui long dans le repère du
 * `Text` où l'appui a commencé, en additionnant les déplacements du doigt.
 * Quand la liste défile, ce `Text` monte avec elle, et la position calculée
 * avec lui : doigt immobile, la sélection restait collée au texte au lieu de
 * s'étendre — constaté sur le banc, la poignée à mi-écran sous un doigt posé en
 * bas.
 *
 * Il faut donc que le geste voie le doigt descendre d'autant que ce `Text` est
 * monté. Tant que dure la sélection au doigt, chaque événement de la fenêtre
 * est décalé de [decalage] ; et à chaque image, le dernier événement réel est
 * rejoué avec le décalage du moment, pour qu'un doigt immobile étende quand
 * même la sélection. C'est le mécanisme de Compose qui sélectionne : on ne fait
 * que lui dire où est le doigt par rapport au texte.
 *
 * Le décalage est la montée du `Text` d'origine *telle que Compose l'a placé*
 * ([ReperesListe.montee]), pas la somme des pas de défilement. Sorti de
 * l'écran, un élément épinglé n'est plus replacé : ses coordonnées se figent,
 * et c'est avec elles que Compose convertit la position du geste. Relevé sur le
 * banc : défilement de 1 283 px, montée figée à 1 114 ; compter la somme
 * mettait la fin de sélection 400 px sous le doigt.
 *
 * Le décalage porte sur les coordonnées brutes *et* locales d'un événement
 * reconstruit, jamais sur un `offsetLocation` seul, qui laisse les brutes
 * intactes. Compose lit les deux : il déduit la position de la fenêtre de leur
 * écart (`AndroidComposeView.recalculateWindowPosition`), la relit parfois à
 * l'écran, et écarte un mouvement dont les brutes n'ont pas bougé
 * (`isPositionChanged`). Constaté sur le banc avec un décalage partiel : les
 * rejeux étaient jetés en silence, puis le décalage se perdait par moments, et
 * la sélection retombait d'autant au-dessus du doigt. Décalées ensemble, les deux
 * coordonnées gardent la fenêtre à sa vraie place, quelle que soit la façon dont
 * Compose la calcule. Le décalage revient à zéro au lever du doigt, et à chaque
 * nouvel appui.
 */
private class SuiviSelection {
    var decalage = 0f
        private set
    private var origine: Window.Callback? = null
    private var dernier: MotionEvent? = null

    @MainThread
    fun distribuer(event: MotionEvent, suite: Window.Callback): Boolean {
        origine = suite
        if (event.actionMasked == MotionEvent.ACTION_DOWN) oublier()
        dernier?.recycle()
        dernier = MotionEvent.obtainNoHistory(event)
        val traite = if (decalage == 0f) {
            suite.dispatchTouchEvent(event)
        } else {
            val decale = event.decale(decalage, event.eventTime)
            suite.dispatchTouchEvent(decale).also { decale.recycle() }
        }
        val action = event.actionMasked
        if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) oublier()
        return traite
    }

    /**
     * Le texte d'origine est désormais monté de [nouveau] px sous le doigt : on
     * rejoue le dernier mouvement réel avec ce décalage.
     */
    @MainThread
    fun recaler(nouveau: Float) {
        val suite = origine ?: return
        val mouvement = dernier?.takeIf { it.actionMasked == MotionEvent.ACTION_MOVE } ?: return
        if (nouveau == decalage) return
        decalage = nouveau
        val rejoue = mouvement.decale(decalage, SystemClock.uptimeMillis())
        suite.dispatchTouchEvent(rejoue)
        rejoue.recycle()
    }

    @MainThread
    fun oublier() {
        decalage = 0f
        dernier?.recycle()
        dernier = null
    }
}

/** Reçoit les touchers de la fenêtre avant l'activité, pour [SuiviSelection]. */
private class EnveloppeFenetre(
    private val origine: Window.Callback,
    private val suivi: SuiviSelection,
) : Window.Callback by origine {
    override fun dispatchTouchEvent(event: MotionEvent): Boolean = suivi.distribuer(event, origine)
}

/**
 * Copie de l'événement, déplacée de [dy] vers le bas, coordonnées brutes
 * comprises (voir [SuiviSelection]).
 *
 * `MotionEvent.obtain` pose des brutes égales aux locales : on construit donc
 * en coordonnées d'écran, puis `offsetLocation` ramène les locales dans le
 * repère de la fenêtre sans toucher aux brutes.
 */
private fun MotionEvent.decale(dy: Float, instant: Long): MotionEvent {
    val fenetreX = rawX - x
    val fenetreY = rawY - y
    val proprietes = Array(pointerCount) { i ->
        MotionEvent.PointerProperties().also { getPointerProperties(i, it) }
    }
    val coordonnees = Array(pointerCount) { i ->
        MotionEvent.PointerCoords().also {
            getPointerCoords(i, it)
            it.x += fenetreX
            it.y += fenetreY + dy
        }
    }
    return MotionEvent.obtain(
        downTime,
        instant,
        action,
        pointerCount,
        proprietes,
        coordonnees,
        metaState,
        buttonState,
        xPrecision,
        yPrecision,
        deviceId,
        edgeFlags,
        source,
        flags,
    ).apply { offsetLocation(-fenetreX, -fenetreY) }
}

private tailrec fun Context.activite(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.activite()
    else -> null
}

/**
 * Reçoit la destination d'un lien touché. Fournie par [VueMarkdown] plutôt que
 * passée de bloc en bloc : seul [enrichi] s'en sert, tout au fond.
 */
private val LocalOuvrirLien = staticCompositionLocalOf<(String) -> Unit> { {} }

/**
 * Où se trouve, dans la liste de l'aperçu, l'occurrence courante d'une
 * recherche.
 *
 * Go découpe un long paragraphe en morceaux, mais un morceau peut encore
 * dépasser l'écran : amener l'élément en haut laissait l'occurrence dessous,
 * hors de vue (constaté sur la note de 295 ko, occurrence 3799/3800). Le texte
 * qui porte l'occurrence courante rapporte donc sa mise en page, et l'écran en
 * tire la hauteur de la ligne dans l'élément. Rien n'est retenu pour les
 * autres textes, ni pour les cellules d'un tableau, dont les lignes sont
 * courtes.
 */
class PositionOccurrence {
    var element: Int = -1
        private set
    var mise: TextLayoutResult? = null
        private set

    fun retenir(element: Int, mise: TextLayoutResult) {
        this.element = element
        this.mise = mise
    }

    /** Haut de la ligne de [position] dans le texte de [element], `null` sans mise en page. */
    fun hautDeLigne(element: Int, position: Int): Int? {
        val texte = mise?.takeIf { this.element == element } ?: return null
        val borne = position.coerceIn(0, texte.layoutInput.text.length)
        return texte.getLineTop(texte.getLineForOffset(borne)).toInt()
    }
}

private val RAPPORT_MUET: (TextLayoutResult) -> Unit = {}

/** Le rapport de mise en page de l'élément en cours de composition (voir [PositionOccurrence]). */
private val LocalRapportOccurrence = staticCompositionLocalOf { RAPPORT_MUET }

/** Le rapport, pour le seul texte qui porte l'occurrence courante. */
@Composable
private fun rapportOccurrence(marques: MarquesTexte?): (TextLayoutResult) -> Unit =
    if (marques?.courante != null) LocalRapportOccurrence.current else RAPPORT_MUET

/**
 * Demande confirmation avant d'ouvrir un lien de l'aperçu.
 *
 * Le libellé d'un lien est choisi par l'auteur de la note, qui peut être
 * quelqu'un d'autre dans un espace partagé : « https://banque.fr » peut mener
 * ailleurs, et un `tel:` ou le schéma d'une autre application se déclenche en
 * un toucher. On affiche donc l'hôte, puis la destination entière. Les liens
 * vers un fichier de l'appareil n'arrivent pas jusqu'ici : Go les a déjà
 * rendus en texte (`markdown.OpenableLink`).
 */
@Composable
private fun ConfirmationLien(url: String, onFermer: () -> Unit) {
    val uriHandler = LocalUriHandler.current
    val context = LocalContext.current
    val echec = stringResource(R.string.apercu_lien_echec)
    val hote = remember(url) { Uri.parse(url).host }
    // Borné : une destination de milliers de caractères sans espace est
    // justement ce qui fait tomber le moteur de mise en page.
    val affichee = if (url.length > MAX_URL_AFFICHEE) url.take(MAX_URL_AFFICHEE) + "…" else url

    AlertDialog(
        onDismissRequest = onFermer,
        title = { Text(stringResource(R.string.apercu_lien_titre)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.apercu_lien_intro))
                if (!hote.isNullOrBlank()) {
                    Text(
                        text = hote,
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.Bold,
                    )
                }
                Text(
                    text = affichee,
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    onFermer()
                    // Aucune application ne gère ce schéma : Compose lève au
                    // lieu d'ignorer, et l'aperçu ne doit pas tomber pour ça.
                    try {
                        uriHandler.openUri(url)
                    } catch (_: RuntimeException) {
                        Toast.makeText(context, echec, Toast.LENGTH_SHORT).show()
                    }
                },
            ) { Text(stringResource(R.string.apercu_lien_ouvrir)) }
        },
        dismissButton = {
            TextButton(onClick = onFermer) { Text(stringResource(R.string.action_annuler)) }
        },
    )
}

private const val MAX_URL_AFFICHEE = 500
/** Écart vertical entre deux blocs de l'aperçu ; nul avant un morceau de suite. */
private const val ECART_BLOCS_DP = 10
private const val LARGEUR_BARRE_DP = 3
private const val PAS_BARRE_DP = 13 // barre de 3 dp + 10 dp d'air

/** Un élément de la liste visible, avec les tableaux reconstitués. */
internal sealed interface ElementApercu {
    /**
     * Un bloc, ou un morceau de bloc découpé par Go.
     *
     * [avantSuite] : le bloc suivant le continue (voir `NoteBlockDto.cont`).
     *
     * [suiteDeTache] : morceau d'une tâche coupée par une image, qui n'est pas
     * le premier. Il porte la même case que le premier morceau (même position
     * source) et ne la redessine pas : une tâche, une case.
     *
     * [tete] : indice, dans la liste des éléments, du premier morceau du bloc
     * découpé par Go auquel celui-ci appartient (lui-même s'il n'est pas une
     * suite). Les morceaux d'un même bloc de code s'en servent pour défiler
     * ensemble à l'horizontale.
     */
    data class Bloc(
        val bloc: NoteBlockDto,
        val avantSuite: Boolean = false,
        val suiteDeTache: Boolean = false,
        val tete: Int = 0,
    ) : ElementApercu
    data class Tableau(val lignes: List<NoteBlockDto>) : ElementApercu
}

/**
 * Indice, dans la liste de l'aperçu, de l'élément qui dessine le bloc
 * [indiceBloc] — les lignes d'un tableau n'y font qu'un élément.
 *
 * Doit suivre exactement [grouperPourApercu] : un saut du plan qui tomberait
 * un élément trop haut montrerait le tableau d'avant au lieu du titre.
 * `PlanNoteTest` les confronte. Hors bornes : ramené au premier ou au dernier
 * élément, plutôt qu'un saut dans le vide.
 */
internal fun indiceElement(blocs: List<NoteBlockDto>, indiceBloc: Int): Int {
    val borne = indiceBloc.coerceIn(0, (blocs.size - 1).coerceAtLeast(0))
    var element = -1
    for (index in 0..borne) {
        if (index >= blocs.size) break
        // Une ligne de tableau précédée d'une autre ne commence pas d'élément.
        val suiteDeTableau = index > 0 &&
            blocs[index].kind == BlockKind.LIGNE_TABLEAU &&
            blocs[index - 1].kind == BlockKind.LIGNE_TABLEAU
        if (!suiteDeTableau) element++
    }
    return element.coerceAtLeast(0)
}

/**
 * Retire, pour l'affichage seulement, le retour à la ligne qui termine un
 * morceau suivi d'une suite.
 *
 * Go coupe un long bloc juste après un « \n », qu'il garde pour que les
 * morceaux recollés rendent le texte exact. Affiché tel quel, ce retour
 * ajouterait une ligne vide à la jointure.
 *
 * Limite connue, non vérifiée sur appareil : `SelectionContainer` recolle
 * les textes de plusieurs `Text` sans séparateur, à notre connaissance. Une
 * sélection copiée à cheval sur une jointure perdrait alors ce retour, et les
 * deux lignes s'y retrouveraient collées.
 */
internal fun sansRetourFinal(bloc: NoteBlockDto): NoteBlockDto {
    if (!bloc.text.endsWith('\n')) return bloc
    val fin = bloc.text.length - 1
    return bloc.copy(
        text = bloc.text.substring(0, fin),
        spans = bloc.spans.mapNotNull { span ->
            val debut = span.start.coerceAtMost(fin)
            val terme = span.end.coerceAtMost(fin)
            if (debut < terme) span.copy(start = debut, end = terme) else null
        },
    )
}

/**
 * Réunit uniquement les lignes consécutives d'un même tableau, marque le
 * morceau que le suivant continue, et repère les morceaux d'une tâche coupée
 * par une image qui suivent le premier (voir [ElementApercu.Bloc]).
 */
internal fun grouperPourApercu(blocs: List<NoteBlockDto>): List<ElementApercu> {
    val elements = mutableListOf<ElementApercu>()
    // Position de la case de la dernière tâche dessinée. Go donne la même
    // position à tous les morceaux d'une tâche coupée par une image ; deux
    // tâches distinctes n'en partagent jamais une.
    var sourceTache: Int? = null
    // Indice du premier morceau du bloc découpé en cours.
    var tete = 0
    var index = 0
    while (index < blocs.size) {
        val bloc = blocs[index]
        if (bloc.kind != BlockKind.LIGNE_TABLEAU) {
            val avantSuite = blocs.getOrNull(index + 1)?.cont == true
            val suiteDeTache = bloc.kind == BlockKind.TACHE && bloc.source != null && bloc.source == sourceTache
            if (bloc.kind == BlockKind.TACHE) {
                sourceTache = bloc.source
            } else if (bloc.kind != BlockKind.IMAGE) {
                // Seule une image sépare les morceaux d'une même tâche.
                sourceTache = null
            }
            if (!bloc.cont) tete = elements.size
            elements += ElementApercu.Bloc(
                if (avantSuite) sansRetourFinal(bloc) else bloc,
                avantSuite,
                suiteDeTache,
                tete,
            )
            index++
            continue
        }
        sourceTache = null

        val lignes = mutableListOf<NoteBlockDto>()
        while (index < blocs.size && blocs[index].kind == BlockKind.LIGNE_TABLEAU) {
            lignes += blocs[index]
            index++
        }
        elements += ElementApercu.Tableau(lignes)
    }
    return elements
}

/**
 * Pose le cadre commun — retrait de liste, barres de citation — puis délègue
 * le contenu selon le `kind`.
 */
@Composable
private fun Bloc(
    bloc: NoteBlockDto,
    avantSuite: Boolean,
    onBasculerTache: ((NoteBlockDto) -> Unit)?,
    suiteDeTache: Boolean = false,
    defilementCode: DefilementCode? = null,
    marques: MarquesTexte? = null,
) {
    val couleurBarre = MaterialTheme.colorScheme.outlineVariant
    Row(
        modifier = Modifier
            .fillMaxWidth()
            // Les barres de citation se dessinent, elles ne se mesurent pas :
            // `height(IntrinsicSize.Min)` donne sa hauteur au bloc en contrainte
            // fixe, et Compose lève IllegalArgumentException au-delà de
            // 262 143 px — un seul paragraphe démesuré tuait l'application.
            .drawBehind {
                repeat(bloc.quote) { niveau ->
                    val x = (bloc.depth * RETRAIT_LISTE_DP + niveau * PAS_BARRE_DP).dp.toPx()
                    drawRoundRect(
                        color = couleurBarre,
                        topLeft = Offset(x, 0f),
                        size = Size(LARGEUR_BARRE_DP.dp.toPx(), size.height),
                        cornerRadius = CornerRadius(2.dp.toPx()),
                    )
                }
            }
            .padding(start = (bloc.depth * RETRAIT_LISTE_DP + bloc.quote * PAS_BARRE_DP).dp),
    ) {
        Column(Modifier.weight(1f)) {
            when (bloc.kind) {
                BlockKind.TITRE -> Titre(bloc, marques)
                // Une suite garde le retrait du texte, sans répéter le
                // marqueur ni la case.
                BlockKind.PUCE -> Puce(bloc, marqueur = if (bloc.cont) "" else "•", marques) // i18n-ok
                BlockKind.NUMEROTE -> Puce(bloc, marqueur = if (bloc.cont) "" else "${bloc.number}.", marques) // i18n-ok
                // Une suite (bloc découpé, ou tâche coupée par une image) n'a
                // pas de case : elle est sur le premier morceau.
                BlockKind.TACHE -> {
                    val avecCase = !bloc.cont && !suiteDeTache
                    Tache(bloc, if (avecCase) onBasculerTache else null, avecCase = avecCase, marques = marques)
                }
                BlockKind.CODE -> BlocDeCode(bloc, avantSuite, defilementCode, marques)
                BlockKind.TRAIT -> HorizontalDivider(Modifier.padding(vertical = 8.dp))
                BlockKind.IMAGE -> Image(bloc, marques)
                BlockKind.LIGNE_TABLEAU -> LigneTableau(bloc)
                BlockKind.SAUT_DE_PAGE -> SautDePage()
                BlockKind.BRUT -> Text(
                    avecMarques(bloc.text, marques),
                    style = StyleEditeur,
                    onTextLayout = rapportOccurrence(marques),
                )
                else -> Text(
                    enrichi(bloc, marques),
                    style = MaterialTheme.typography.bodyLarge,
                    onTextLayout = rapportOccurrence(marques),
                )
            }
        }
    }
}

@Composable
private fun Titre(bloc: NoteBlockDto, marques: MarquesTexte?) {
    val typo = MaterialTheme.typography
    val style = when (bloc.level) {
        1 -> typo.headlineSmall
        2 -> typo.titleLarge
        3 -> typo.titleMedium
        else -> typo.titleSmall
    }
    Text(
        text = enrichi(bloc, marques),
        style = style.copy(fontWeight = FontWeight.SemiBold),
        onTextLayout = rapportOccurrence(marques),
        modifier = Modifier.padding(top = 6.dp, bottom = 2.dp),
    )
}

@Composable
private fun Puce(bloc: NoteBlockDto, marqueur: String, marques: MarquesTexte?) {
    Row(Modifier.fillMaxWidth()) {
        Text(
            text = marqueur,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier
                .width(LARGEUR_MARQUEUR_DP.dp)
                .padding(end = 6.dp),
        )
        Text(
            enrichi(bloc, marques),
            style = MaterialTheme.typography.bodyLarge,
            onTextLayout = rapportOccurrence(marques),
        )
    }
}

/**
 * Une tâche, cochée ou non.
 *
 * La case est le **seul** geste d'écriture de l'aperçu (décision du
 * 15/11/2026, qui renverse la case décorative d'avant). L'exception est
 * tenable parce que la bascule remplace un caractère par un caractère dans le
 * champ masqué sous l'aperçu : les positions des autres blocs restent justes,
 * l'annulation et l'enregistrement suivent le chemin d'une frappe (carnet :
 * `ANDROID-APERCU-CASES`).
 *
 * Elle reste décorative sans [onBasculerTache] (note non modifiable) ou sans
 * position source (bloc dont Go n'a pas pu garantir la case). Seule la case
 * réagit : le libellé garde l'appui long qui ouvre la sélection. La case garde
 * sa largeur, et c'est Compose qui étend la zone de toucher à 48 dp autour
 * d'elle, sans déplacer le texte.
 */
@Composable
private fun Tache(
    bloc: NoteBlockDto,
    onBasculerTache: ((NoteBlockDto) -> Unit)?,
    avecCase: Boolean = true,
    marques: MarquesTexte? = null,
) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
        val forme = Modifier
            .width(LARGEUR_MARQUEUR_DP.dp)
            .padding(end = 6.dp, top = 2.dp)
        val icone = if (bloc.checked) Icons.Default.CheckBox else Icons.Default.CheckBoxOutlineBlank
        if (!avecCase) {
            // Suite d'une tâche découpée : la case est sur le premier morceau.
            Spacer(forme)
        } else if (onBasculerTache != null && bloc.source != null) {
            val libelle = stringResource(R.string.apercu_case_tache, bloc.text)
            Icon(
                imageVector = icone,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier
                    .toggleable(
                        value = bloc.checked,
                        role = Role.Checkbox,
                        onValueChange = { onBasculerTache(bloc) },
                    )
                    .semantics { contentDescription = libelle }
                    .then(forme),
            )
        } else {
            // `contentDescription` nul : l'état est déjà porté par le texte barré.
            Icon(
                imageVector = icone,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = forme,
            )
        }
        Text(
            text = enrichi(bloc, marques),
            style = MaterialTheme.typography.bodyLarge,
            onTextLayout = rapportOccurrence(marques),
            textDecoration = if (bloc.checked) TextDecoration.LineThrough else null,
            color = if (bloc.checked) {
                MaterialTheme.colorScheme.onSurfaceVariant
            } else {
                MaterialTheme.colorScheme.onSurface
            },
        )
    }
}

/**
 * Un bloc de code, qui défile horizontalement plutôt que de se replier.
 *
 * Une ligne de code coupée au milieu ne veut plus rien dire ; mieux vaut la
 * faire défiler que la réorganiser.
 */
@Composable
private fun BlocDeCode(bloc: NoteBlockDto, avantSuite: Boolean, partage: DefilementCode?, marques: MarquesTexte?) {
    // Un bloc découpé par Go garde un fond continu : ni coin arrondi ni marge
    // à la jointure de deux morceaux.
    val haut = if (bloc.cont) 0.dp else 6.dp
    val bas = if (avantSuite) 0.dp else 6.dp
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = RoundedCornerShape(topStart = haut, topEnd = haut, bottomStart = bas, bottomEnd = bas),
        modifier = Modifier.fillMaxWidth(),
    ) {
        val propre = rememberScrollState()
        val densite = LocalDensity.current
        val largeurCommune = if (partage != null) {
            with(densite) { partage.largeur.intValue.toDp() }
        } else {
            0.dp
        }
        Column(
            Modifier
                .horizontalScroll(partage?.etat ?: propre)
                // Morceaux d'un bloc découpé : tous prennent la largeur du plus
                // large déjà vu. Leurs limites de défilement sont alors les
                // mêmes, et l'état commun n'est jamais ramené en arrière par
                // un morceau plus étroit. La largeur ne fait que croître.
                .then(
                    if (partage != null) {
                        Modifier
                            .onSizeChanged { taille ->
                                if (taille.width > partage.largeur.intValue) partage.largeur.intValue = taille.width
                            }
                            .widthIn(min = largeurCommune)
                    } else {
                        Modifier
                    },
                )
                .padding(
                    start = 12.dp,
                    end = 12.dp,
                    top = if (bloc.cont) 0.dp else 10.dp,
                    bottom = if (avantSuite) 0.dp else 10.dp,
                ),
        ) {
            Text(
                text = avecMarques(bloc.text, marques),
                style = StyleEditeur,
                onTextLayout = rapportOccurrence(marques),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * Défilement horizontal commun aux morceaux d'un bloc de code découpé par Go.
 *
 * Avec un défilement par morceau, faire glisser une ligne longue ne déplaçait
 * que ses 40 lignes : les lignes ne s'alignaient plus aux jointures.
 * [largeur] retient la plus grande largeur de contenu vue parmi les morceaux.
 */
internal class DefilementCode {
    val etat = ScrollState(0)
    val largeur = mutableIntStateOf(0)
}

/**
 * Repère à la place d'une image.
 *
 * La source n'arrive jamais jusqu'ici : l'éditeur web d'OpenCloud insère les
 * images en `data:image/jpeg;base64,…`, et le cœur Go ne fait traverser que le
 * texte alternatif. Afficher l'image demanderait de décoder ce base64 — un
 * chantier à part, pas une ligne de plus.
 */
@Composable
private fun Image(bloc: NoteBlockDto, marques: MarquesTexte?) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = RoundedCornerShape(6.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = Icons.Outlined.Image,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(end = 10.dp),
            )
            Text(
                // Le libellé de repli n'est pas dans le texte cherché : il n'a
                // pas de marques.
                text = if (bloc.text.isBlank()) {
                    AnnotatedString(stringResource(R.string.apercu_image))
                } else {
                    avecMarques(bloc.text, marques)
                },
                style = MaterialTheme.typography.bodyMedium,
                fontStyle = FontStyle.Italic,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** Repli défensif : VueMarkdown groupe normalement toutes les lignes. */
@Composable
private fun LigneTableau(bloc: NoteBlockDto) {
    Tableau(listOf(bloc))
}

/**
 * Repère d'un saut de page d'un `.docx` ou d'un `.odt`.
 *
 * Ni un simple trait — un saut de page n'est pas une séparation thématique —
 * ni une page blanche : l'aperçu rend du texte structuré, pas une mise en
 * page. Un libellé encadré de filets, avec de l'air au-dessus et en dessous,
 * dit « la suite était sur une autre page » sans prétendre la reproduire.
 */
@Composable
private fun SautDePage() {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        HorizontalDivider(Modifier.weight(1f))
        Text(
            text = stringResource(R.string.apercu_saut_de_page),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 12.dp),
        )
        HorizontalDivider(Modifier.weight(1f))
    }
}

/**
 * Tableau léger, pensé pour une largeur de téléphone.
 *
 * Les colonnes ont une largeur fixe, donc ne s'écrasent pas quand un tableau
 * de bureau comporte beaucoup de colonnes. Le défilement est purement Compose
 * et n'ajoute ni analyse, ni conversion, ni cache de document.
 */
@Composable
private fun Tableau(
    lignes: List<NoteBlockDto>,
    /** Les marques de la cellule (ligne, colonne), `null` sans recherche. */
    marques: (Int, Int) -> MarquesTexte? = { _, _ -> null },
) {
    if (lignes.isEmpty()) return

    val colonnes = lignes.maxOf { it.cells.size }.coerceAtLeast(1)
    val bordure = MaterialTheme.colorScheme.outlineVariant

    Surface(
        color = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(6.dp),
        border = BorderStroke(1.dp, bordure),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.horizontalScroll(rememberScrollState())) {
            lignes.forEachIndexed { rang, ligne ->
                val fond = if (ligne.header) {
                    Modifier.background(MaterialTheme.colorScheme.surfaceVariant)
                } else {
                    Modifier
                }
                Row(fond) {
                    repeat(colonnes) { index ->
                        Text(
                            text = avecMarques(ligne.cells.getOrElse(index) { "" }, marques(rang, index)),
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = if (ligne.header) FontWeight.SemiBold else FontWeight.Normal,
                            modifier = Modifier
                                .width(LARGEUR_CELLULE_TABLEAU_DP.dp)
                                .border(1.dp, bordure)
                                .padding(horizontal = 10.dp, vertical = 8.dp),
                        )
                    }
                }
            }
        }
    }
}

/**
 * Applique les spans d'un bloc à son texte.
 *
 * # Les bornes ne subissent aucune conversion
 *
 * Go les compte en unités de code UTF-16, qui sont exactement les indices de
 * `String` en Kotlin. `bloc.text.length` et `span.end` parlent donc la même
 * langue : « é » vaut 1 des deux côtés, « 😀 » vaut 2 des deux côtés.
 *
 * Le `coerceIn` n'est pas une conversion déguisée, c'est un garde-fou : une
 * borne hors du texte ferait lever `addStyle`, et un aperçu qui plante est
 * pire qu'un gras au mauvais endroit.
 */
@Composable
private fun enrichi(bloc: NoteBlockDto, marques: MarquesTexte? = null): AnnotatedString {
    if (bloc.spans.isEmpty()) return avecMarques(bloc.text, marques)

    val couleurLien = MaterialTheme.colorScheme.primary
    val ouvrirLien = LocalOuvrirLien.current
    val fondCode = MaterialTheme.colorScheme.surfaceVariant
    // Un jaune surligneur, à faible opacité pour que le texte reste lisible et
    // que le thème sombre l'atténue de lui-même. La couleur du document ne
    // traverse pas la façade : « marqué » est la seule information portée.
    val fondSurligne = Color(0xFFFFF176).copy(alpha = 0.40f)

    return remember(bloc, marques) {
        buildAnnotatedString {
            append(bloc.text)
            val fin = bloc.text.length

            bloc.spans.forEach { span ->
                val debut = span.start.coerceIn(0, fin)
                val terme = span.end.coerceIn(debut, fin)
                if (debut == terme) return@forEach

                if (span.style == SpanStyleId.LIEN) {
                    if (span.href.isNotBlank()) {
                        // L'écouteur remplace l'ouverture directe par le
                        // LocalUriHandler : VueMarkdown demande d'abord confirmation.
                        val href = span.href
                        addLink(
                            url = LinkAnnotation.Url(
                                url = href,
                                styles = TextLinkStyles(
                                    style = SpanStyle(
                                        color = couleurLien,
                                        textDecoration = TextDecoration.Underline,
                                    ),
                                ),
                                linkInteractionListener = { ouvrirLien(href) },
                            ),
                            start = debut,
                            end = terme,
                        )
                    }
                    return@forEach
                }

                val style = when (span.style) {
                    SpanStyleId.GRAS -> SpanStyle(fontWeight = FontWeight.Bold)
                    SpanStyleId.ITALIQUE -> SpanStyle(fontStyle = FontStyle.Italic)
                    SpanStyleId.BARRE -> SpanStyle(textDecoration = TextDecoration.LineThrough)
                    SpanStyleId.SOULIGNE -> SpanStyle(textDecoration = TextDecoration.Underline)
                    SpanStyleId.SURLIGNE -> SpanStyle(background = fondSurligne)
                    SpanStyleId.CODE -> SpanStyle(
                        fontFamily = FontFamily.Monospace,
                        background = fondCode,
                    )
                    // Un style inconnu vient d'un cœur Go plus récent que cette
                    // interface : on affiche le texte sans décor plutôt que rien.
                    else -> return@forEach
                }
                addStyle(style, debut, terme)
            }
            // Les occurrences en dernier : leur fond passe par-dessus celui
            // d'un code en ligne ou d'un surligné.
            ajouterMarques(marques, fin)
        }
    }
}

/** Un texte sans mise en forme, avec les fonds des occurrences d'une recherche. */
@Composable
private fun avecMarques(texte: String, marques: MarquesTexte?): AnnotatedString {
    if (marques == null) return AnnotatedString(texte)
    return remember(texte, marques) {
        buildAnnotatedString {
            append(texte)
            ajouterMarques(marques, texte.length)
        }
    }
}

/**
 * Les fonds des occurrences, la courante plus franche. Bornes ramenées dans le
 * texte, comme les spans de Go : une plage périmée ne doit pas faire lever
 * `addStyle`.
 */
private fun AnnotatedString.Builder.ajouterMarques(marques: MarquesTexte?, fin: Int) {
    if (marques == null) return
    marques.plages.forEach { plage ->
        val debut = plage.first.coerceIn(0, fin)
        val terme = (plage.last + 1).coerceIn(debut, fin)
        if (debut == terme) return@forEach
        val fond = if (plage == marques.courante) FOND_OCCURRENCE_COURANTE else FOND_OCCURRENCE
        addStyle(SpanStyle(background = fond), debut, terme)
    }
}

/** Retrait d'un niveau de liste. */
private const val RETRAIT_LISTE_DP = 20

/** Gouttière du marqueur, pour que les textes s'alignent entre eux. */
private const val LARGEUR_MARQUEUR_DP = 26

/** Largeur lisible d'une cellule : au-delà, le tableau se fait défiler. */
private const val LARGEUR_CELLULE_TABLEAU_DP = 152
