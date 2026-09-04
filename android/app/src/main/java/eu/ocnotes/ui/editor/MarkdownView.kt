package eu.ocnotes.ui.editor

import androidx.compose.foundation.background
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckBox
import androidx.compose.material.icons.filled.CheckBoxOutlineBlank
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import eu.ocnotes.R
import eu.ocnotes.data.BlockKind
import eu.ocnotes.data.NoteBlockDto
import eu.ocnotes.data.SpanStyleId
import eu.ocnotes.ui.theme.StyleEditeur

/**
 * Aperçu d'une note, en lecture seule.
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

    SelectionContainer {
        LazyColumn(
            modifier = modifier.fillMaxSize(),
            contentPadding = PaddingValues(horizontal = 20.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            itemsIndexed(elements) { _, element ->
                when (element) {
                    is ElementApercu.Bloc -> Bloc(element.bloc)
                    is ElementApercu.Tableau -> Tableau(element.lignes)
                }
            }
        }
    }
}

/** Un élément de la liste visible, avec les tableaux reconstitués. */
private sealed interface ElementApercu {
    data class Bloc(val bloc: NoteBlockDto) : ElementApercu
    data class Tableau(val lignes: List<NoteBlockDto>) : ElementApercu
}

/** Réunit uniquement les lignes consécutives d'un même tableau. */
private fun grouperPourApercu(blocs: List<NoteBlockDto>): List<ElementApercu> {
    val elements = mutableListOf<ElementApercu>()
    var index = 0
    while (index < blocs.size) {
        val bloc = blocs[index]
        if (bloc.kind != BlockKind.LIGNE_TABLEAU) {
            elements += ElementApercu.Bloc(bloc)
            index++
            continue
        }

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
private fun Bloc(bloc: NoteBlockDto) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(IntrinsicSize.Min)
            .padding(start = (bloc.depth * RETRAIT_LISTE_DP).dp),
    ) {
        // Une barre par niveau de citation : une citation dans une citation
        // se voit, sans qu'aucun texte n'ait à le dire.
        repeat(bloc.quote) {
            Box(
                Modifier
                    .padding(end = 10.dp)
                    .width(3.dp)
                    .fillMaxHeight()
                    .background(
                        MaterialTheme.colorScheme.outlineVariant,
                        RoundedCornerShape(2.dp),
                    ),
            )
        }

        Column(Modifier.weight(1f)) {
            when (bloc.kind) {
                BlockKind.TITRE -> Titre(bloc)
                BlockKind.PUCE -> Puce(bloc, marqueur = "•") // i18n-ok
                BlockKind.NUMEROTE -> Puce(bloc, marqueur = "${bloc.number}.") // i18n-ok
                BlockKind.TACHE -> Tache(bloc)
                BlockKind.CODE -> BlocDeCode(bloc)
                BlockKind.TRAIT -> HorizontalDivider(Modifier.padding(vertical = 8.dp))
                BlockKind.IMAGE -> Image(bloc)
                BlockKind.LIGNE_TABLEAU -> LigneTableau(bloc)
                BlockKind.SAUT_DE_PAGE -> SautDePage()
                BlockKind.BRUT -> Text(bloc.text, style = StyleEditeur)
                else -> Text(enrichi(bloc), style = MaterialTheme.typography.bodyLarge)
            }
        }
    }
}

@Composable
private fun Titre(bloc: NoteBlockDto) {
    val typo = MaterialTheme.typography
    val style = when (bloc.level) {
        1 -> typo.headlineSmall
        2 -> typo.titleLarge
        3 -> typo.titleMedium
        else -> typo.titleSmall
    }
    Text(
        text = enrichi(bloc),
        style = style.copy(fontWeight = FontWeight.SemiBold),
        modifier = Modifier.padding(top = 6.dp, bottom = 2.dp),
    )
}

@Composable
private fun Puce(bloc: NoteBlockDto, marqueur: String) {
    Row(Modifier.fillMaxWidth()) {
        Text(
            text = marqueur,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier
                .width(LARGEUR_MARQUEUR_DP.dp)
                .padding(end = 6.dp),
        )
        Text(enrichi(bloc), style = MaterialTheme.typography.bodyLarge)
    }
}

/**
 * Une tâche, cochée ou non.
 *
 * La case est décorative : l'aperçu est en lecture seule, et une case
 * cliquable qui modifierait la note ferait mentir le mode. `contentDescription`
 * reste nul pour la même raison — l'état est déjà porté par le texte barré.
 */
@Composable
private fun Tache(bloc: NoteBlockDto) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
        Icon(
            imageVector = if (bloc.checked) Icons.Default.CheckBox else Icons.Default.CheckBoxOutlineBlank,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier
                .width(LARGEUR_MARQUEUR_DP.dp)
                .padding(end = 6.dp, top = 2.dp),
        )
        Text(
            text = enrichi(bloc),
            style = MaterialTheme.typography.bodyLarge,
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
private fun BlocDeCode(bloc: NoteBlockDto) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = RoundedCornerShape(6.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            Modifier
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 12.dp, vertical = 10.dp),
        ) {
            Text(
                text = bloc.text,
                style = StyleEditeur,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
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
private fun Image(bloc: NoteBlockDto) {
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
                text = bloc.text.ifBlank { stringResource(R.string.apercu_image) },
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
private fun Tableau(lignes: List<NoteBlockDto>) {
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
            lignes.forEach { ligne ->
                val fond = if (ligne.header) {
                    Modifier.background(MaterialTheme.colorScheme.surfaceVariant)
                } else {
                    Modifier
                }
                Row(fond) {
                    repeat(colonnes) { index ->
                        Text(
                            text = ligne.cells.getOrElse(index) { "" },
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
private fun enrichi(bloc: NoteBlockDto): AnnotatedString {
    if (bloc.spans.isEmpty()) return AnnotatedString(bloc.text)

    val couleurLien = MaterialTheme.colorScheme.primary
    val fondCode = MaterialTheme.colorScheme.surfaceVariant
    // Un jaune surligneur, à faible opacité pour que le texte reste lisible et
    // que le thème sombre l'atténue de lui-même. La couleur du document ne
    // traverse pas la façade : « marqué » est la seule information portée.
    val fondSurligne = Color(0xFFFFF176).copy(alpha = 0.40f)

    return buildAnnotatedString {
        append(bloc.text)
        val fin = bloc.text.length

        bloc.spans.forEach { span ->
            val debut = span.start.coerceIn(0, fin)
            val terme = span.end.coerceIn(debut, fin)
            if (debut == terme) return@forEach

            if (span.style == SpanStyleId.LIEN) {
                if (span.href.isNotBlank()) {
                    // addLink ouvre la destination tout seul, via le
                    // LocalUriHandler : rien à câbler côté écran.
                    addLink(
                        url = LinkAnnotation.Url(
                            url = span.href,
                            styles = TextLinkStyles(
                                style = SpanStyle(
                                    color = couleurLien,
                                    textDecoration = TextDecoration.Underline,
                                ),
                            ),
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
    }
}

/** Retrait d'un niveau de liste. */
private const val RETRAIT_LISTE_DP = 20

/** Gouttière du marqueur, pour que les textes s'alignent entre eux. */
private const val LARGEUR_MARQUEUR_DP = 26

/** Largeur lisible d'une cellule : au-delà, le tableau se fait défiler. */
private const val LARGEUR_CELLULE_TABLEAU_DP = 152
