package eu.ocnotes.ui.editor

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.FormatListBulleted
import androidx.compose.material.icons.outlined.Checklist
import androidx.compose.material.icons.outlined.Code
import androidx.compose.material.icons.outlined.DataObject
import androidx.compose.material.icons.outlined.FormatBold
import androidx.compose.material.icons.outlined.FormatItalic
import androidx.compose.material.icons.outlined.FormatListNumbered
import androidx.compose.material.icons.outlined.FormatQuote
import androidx.compose.material.icons.outlined.FormatStrikethrough
import androidx.compose.material.icons.outlined.Link
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PlainTooltip
import androidx.compose.material3.Text
import androidx.compose.material3.TooltipBox
import androidx.compose.material3.TooltipDefaults
import androidx.compose.material3.VerticalDivider
import androidx.compose.material3.rememberTooltipState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import eu.ocnotes.R
import eu.ocnotes.data.FormatAction

/**
 * Barre d'outils de mise en forme.
 *
 * **La liste des actions n'est pas codée en dur** : elle arrive de
 * `FormatActionsJSON()`, dans l'ordre voulu par le cœur Go. Ce fichier ne fait
 * que décorer des identifiants — une action ajoutée côté Go apparaît ici sans
 * modification, avec son identifiant brut comme libellé jusqu'à ce qu'on lui
 * donne une icône.
 *
 * Chaque bouton montre le résultat (une icône) plutôt que la syntaxe : la
 * syntaxe Markdown reste à portée de doigt dans la bulle de l'appui long, qui
 * donne le nom de l'action et ce qu'elle écrit.
 *
 * Chaque action est une bascule : la réappliquer retire la mise en forme. Les
 * boutons des mises en forme présentes sous le curseur ([actives], voir
 * [misesEnFormeActives]) sont surlignés, pour qu'on voie qu'un appui retire.
 *
 * Pas de fond de barre : posée juste au-dessus du clavier, elle n'a besoin que
 * d'un filet pour marquer sa limite avec le texte.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FormatToolbar(
    actions: List<FormatAction>,
    onAction: (FormatAction) -> Unit,
    modifier: Modifier = Modifier,
    actives: () -> Set<String> = { emptySet() },
) {
    if (actions.isEmpty()) return

    // Lu ici, et non chez l'appelant : un mouvement du curseur qui change les
    // mises en forme actives ne recompose que la barre, pas l'écran.
    val activesCourantes = actives()

    Column(modifier = modifier.fillMaxWidth()) {
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

        Row(
            modifier = Modifier
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(0.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            actions.forEachIndexed { index, action ->
                val apparence = apparenceDe(action)
                val active = action.id in activesCourantes

                // Un filet entre deux groupes d'actions plutôt qu'entre
                // chaque bouton : de quoi repérer la bonne zone d'un coup
                // d'œil sans transformer la barre en grille.
                if (index > 0 && categorieDe(action.id) != categorieDe(actions[index - 1].id)) {
                    VerticalDivider(
                        modifier = Modifier
                            .height(24.dp)
                            .padding(horizontal = 2.dp),
                        color = MaterialTheme.colorScheme.outlineVariant,
                    )
                }

                // L'appui long ouvre la bulle sans déclencher l'action.
                TooltipBox(
                    positionProvider = TooltipDefaults.rememberPlainTooltipPositionProvider(),
                    tooltip = { PlainTooltip { Text(apparence.aide) } },
                    state = rememberTooltipState(),
                    // Focusable, la bulle prendrait le focus du champ et
                    // fermerait le clavier.
                    focusable = false,
                ) {
                    IconButton(
                        onClick = { onAction(action) },
                        colors = if (active) {
                            IconButtonDefaults.iconButtonColors(
                                containerColor = MaterialTheme.colorScheme.secondaryContainer,
                                contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                            )
                        } else {
                            IconButtonDefaults.iconButtonColors(
                                contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        },
                        modifier = Modifier.semantics {
                            contentDescription = apparence.description
                            selected = active
                        },
                    ) {
                        if (apparence.icone != null) {
                            Icon(imageVector = apparence.icone, contentDescription = null)
                        } else {
                            // Titres (pas d'icône H1 dans la bibliothèque) et
                            // actions inconnues : le libellé texte, la taille
                            // décroissante des titres montrant leur niveau.
                            Text(
                                text = apparence.libelle,
                                fontWeight = apparence.graisse,
                                fontSize = apparence.taille,
                                maxLines = 1,
                            )
                        }
                    }
                }
            }
        }
    }
}

/** Regroupement purement visuel : où poser un filet entre deux boutons. */
private enum class Categorie { EN_LIGNE, TITRE, BLOC }

private fun categorieDe(id: String): Categorie = when (id) {
    "bold", "italic", "strikethrough", "code", "link" -> Categorie.EN_LIGNE
    "h1", "h2", "h3" -> Categorie.TITRE
    else -> Categorie.BLOC
}

/**
 * Décoration d'un bouton : une icône, ou à défaut un libellé texte.
 *
 * [syntaxe] est ce que l'action écrit, montré dans la bulle d'aide. Elle n'est
 * pas traduite : c'est du Markdown, le même dans toutes les langues.
 */
private data class Apparence(
    val description: String,
    val syntaxe: String,
    val icone: ImageVector? = null,
    val libelle: String = "",
    val graisse: FontWeight? = null,
    val taille: androidx.compose.ui.unit.TextUnit = 14.sp,
) {
    /** Texte de la bulle : « Gras · **…** ». */
    val aide: String get() = if (syntaxe.isEmpty()) description else "$description · $syntaxe"  // i18n-ok : deux valeurs et un séparateur
}

/**
 * Décoration d'une action donnée.
 *
 * Composable pour lire des ressources, pas pour dessiner. [Apparence] porte
 * des chaînes déjà rédigées et non des identifiants de ressource : le repli
 * affiche l'identifiant reçu du cœur Go, qui par définition n'en a pas.
 */
@Composable
private fun apparenceDe(action: FormatAction): Apparence = when (action.id) {
    "bold" -> Apparence(stringResource(R.string.format_gras), "**…**", Icons.Outlined.FormatBold)
    "italic" -> Apparence(stringResource(R.string.format_italique), "*…*", Icons.Outlined.FormatItalic)
    "strikethrough" -> Apparence(stringResource(R.string.format_barre), "~~…~~", Icons.Outlined.FormatStrikethrough)
    "code" -> Apparence(stringResource(R.string.format_code), "`…`", Icons.Outlined.Code)
    "link" -> Apparence(stringResource(R.string.format_lien), "[…](url)", Icons.Outlined.Link)

    "h1" -> Apparence(
        description = stringResource(R.string.format_titre1),
        syntaxe = "# …",
        libelle = stringResource(R.string.format_titre1_libelle),
        graisse = FontWeight.Bold,
        taille = 16.sp,
    )

    "h2" -> Apparence(
        description = stringResource(R.string.format_titre2),
        syntaxe = "## …",
        libelle = stringResource(R.string.format_titre2_libelle),
        graisse = FontWeight.SemiBold,
        taille = 14.sp,
    )

    "h3" -> Apparence(
        description = stringResource(R.string.format_titre3),
        syntaxe = "### …",
        libelle = stringResource(R.string.format_titre3_libelle),
        graisse = FontWeight.Medium,
        taille = 12.sp,
    )

    "bullet" -> Apparence(
        stringResource(R.string.format_puces), "- …", Icons.AutoMirrored.Outlined.FormatListBulleted,
    )
    "numbered" -> Apparence(stringResource(R.string.format_numerotee), "1. …", Icons.Outlined.FormatListNumbered)
    "task" -> Apparence(stringResource(R.string.format_case), "- [ ] …", Icons.Outlined.Checklist)
    "quote" -> Apparence(stringResource(R.string.format_citation), "> …", Icons.Outlined.FormatQuote)
    "codeblock" -> Apparence(stringResource(R.string.format_bloc_code), "```", Icons.Outlined.DataObject)

    // Action inconnue de cette version de l'interface : on l'affiche quand
    // même, avec son identifiant. Mieux vaut un bouton laid qu'une action
    // disponible et invisible.
    else -> Apparence(description = action.id, syntaxe = "", libelle = action.id)
}
