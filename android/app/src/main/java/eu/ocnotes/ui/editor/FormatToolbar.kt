package eu.ocnotes.ui.editor

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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
 */
@Composable
fun FormatToolbar(
    actions: List<FormatAction>,
    onAction: (FormatAction) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (actions.isEmpty()) return

    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        modifier = modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 8.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            actions.forEach { action ->
                val apparence = apparenceDe(action)

                FilledTonalButton(
                    onClick = { onAction(action) },
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                    modifier = Modifier.semantics { contentDescription = apparence.description },
                ) {
                    Text(
                        text = apparence.libelle,
                        fontWeight = apparence.graisse,
                        fontFamily = apparence.police,
                        textDecoration = apparence.decoration,
                    )
                }
            }
        }
    }
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
