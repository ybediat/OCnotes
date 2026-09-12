package eu.ocnotes.ui.editor

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import eu.ocnotes.R
import eu.ocnotes.data.FormatAction

/**
 * Barre d'outils de mise en forme.
 *
 * **La liste des actions n'est pas codée en dur** : elle arrive de
 * `FormatActionsJSON()`, dans l'ordre voulu par le cœur Go. Ce fichier ne fait
 * que décorer des identifiants — une action ajoutée côté Go apparaît ici sans
 * modification, avec son identifiant brut comme libellé jusqu'à ce qu'on lui
 * en donne un joli.
 *
 * Chaque action est une bascule : la réappliquer retire la mise en forme.
 *
 * Pas de fond ni de boutons pleins : posée juste au-dessus du clavier, la
 * barre n'a besoin que d'un filet pour marquer sa limite avec le texte. Un
 * bandeau plein rempli de pilules pleines empilait deux teintes de gris pour
 * dix boutons — plus lourd que ce qu'une barre d'outils doit peser.
 */
@Composable
fun FormatToolbar(
    actions: List<FormatAction>,
    onAction: (FormatAction) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (actions.isEmpty()) return

    Column(modifier = modifier.fillMaxWidth()) {
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

        Row(
            modifier = Modifier
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 4.dp, vertical = 2.dp),
            horizontalArrangement = Arrangement.spacedBy(2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            actions.forEachIndexed { index, action ->
                val apparence = apparenceDe(action)

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

                TextButton(
                    onClick = { onAction(action) },
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
                    modifier = Modifier.semantics { contentDescription = apparence.description },
                ) {
                    Text(
                        text = apparence.libelle,
                        fontWeight = apparence.graisse,
                        fontFamily = apparence.police,
                        textDecoration = apparence.decoration,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
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
 * Décoration d'un bouton.
 *
 * Le libellé montre l'effet quand c'est possible — le bouton « gras » est
 * écrit en gras — parce qu'un bouton de mise en forme se reconnaît plus vite
 * qu'il ne se lit.
 */
private data class Apparence(
    val libelle: String,
    val description: String,
    val graisse: FontWeight? = null,
    val police: FontFamily? = null,
    val decoration: TextDecoration? = null,
)

/**
 * Décoration d'une action donnée.
 *
 * Composable pour lire des ressources, pas pour dessiner : le libellé d'un
 * bouton est un texte comme un autre — « G » pour gras devient « B » pour
 * bold. [Apparence] porte donc des chaînes déjà rédigées et non des
 * identifiants de ressource : le repli affiche l'identifiant reçu du cœur Go,
 * qui par définition n'en a pas.
 */
@Composable
private fun apparenceDe(action: FormatAction): Apparence = when (action.id) {
    "bold" -> Apparence(
        libelle = stringResource(R.string.format_gras_libelle),
        description = stringResource(R.string.format_gras),
        graisse = FontWeight.Bold,
    )

    "italic" -> Apparence(
        libelle = stringResource(R.string.format_italique_libelle),
        description = stringResource(R.string.format_italique),
        graisse = FontWeight.Light,
    )

    "strikethrough" -> Apparence(
        libelle = stringResource(R.string.format_barre_libelle),
        description = stringResource(R.string.format_barre),
        decoration = TextDecoration.LineThrough,
    )

    "code" -> Apparence(
        libelle = stringResource(R.string.format_code_libelle),
        description = stringResource(R.string.format_code),
        police = FontFamily.Monospace,
    )

    // Seul bouton dont le libellé est le mot lui-même.
    "link" -> stringResource(R.string.format_lien).let { Apparence(it, it) }

    "h1" -> Apparence(
        libelle = stringResource(R.string.format_titre1_libelle),
        description = stringResource(R.string.format_titre1),
        graisse = FontWeight.Bold,
    )

    "h2" -> Apparence(
        libelle = stringResource(R.string.format_titre2_libelle),
        description = stringResource(R.string.format_titre2),
        graisse = FontWeight.SemiBold,
    )

    "h3" -> Apparence(
        libelle = stringResource(R.string.format_titre3_libelle),
        description = stringResource(R.string.format_titre3),
        graisse = FontWeight.Medium,
    )

    "bullet" -> Apparence(
        libelle = stringResource(R.string.format_puces_libelle),
        description = stringResource(R.string.format_puces),
    )

    "numbered" -> Apparence(
        libelle = stringResource(R.string.format_numerotee_libelle),
        description = stringResource(R.string.format_numerotee),
    )

    "task" -> Apparence(
        libelle = stringResource(R.string.format_case_libelle),
        description = stringResource(R.string.format_case),
        police = FontFamily.Monospace,
    )

    "quote" -> Apparence(
        libelle = stringResource(R.string.format_citation_libelle),
        description = stringResource(R.string.format_citation),
        police = FontFamily.Monospace,
    )

    "codeblock" -> Apparence(
        libelle = stringResource(R.string.format_bloc_code_libelle),
        description = stringResource(R.string.format_bloc_code),
        police = FontFamily.Monospace,
    )

    // Action inconnue de cette version de l'interface : on l'affiche quand
    // même, avec son identifiant. Mieux vaut un bouton laid qu'une action
    // disponible et invisible.
    else -> Apparence(action.id, action.id)
}
