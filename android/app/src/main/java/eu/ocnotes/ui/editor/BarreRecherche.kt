package eu.ocnotes.ui.editor

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import eu.ocnotes.R

/**
 * Couleurs de la recherche, communes à la saisie et à l'aperçu : un orangé
 * léger pour les occurrences, plus franc pour la courante.
 *
 * Ni le jaune du surligné Markdown (`enrichi`), qu'on confondrait avec une
 * occurrence, ni la teinte de la sélection : une occurrence n'est pas une
 * sélection, rien ne la copie.
 */
internal val FOND_OCCURRENCE = Color(0xFFFFB74D).copy(alpha = 0.35f)
internal val FOND_OCCURRENCE_COURANTE = Color(0xFFFF9800).copy(alpha = 0.75f)

/**
 * La barre de recherche, qui remplace la barre de titre tant qu'elle est
 * ouverte : le champ, le compteur, précédent, suivant, fermer.
 *
 * Le champ prend le focus à l'ouverture, clavier compris. « Rechercher » du
 * clavier passe à l'occurrence suivante. Aucun résultat : le compteur dit
 * « 0 » et le champ prend la couleur d'erreur, sans message surgissant.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun BarreRecherche(
    recherche: RechercheOuverte,
    onTerme: (String) -> Unit,
    onPrecedente: () -> Unit,
    onSuivante: () -> Unit,
    onFermer: () -> Unit,
) {
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    // Le texte du champ vit ici, à jour à chaque frappe ; le ViewModel n'en
    // reçoit qu'une copie. Lu à travers un `StateFlow`, il arrive une image
    // plus tard, et une frappe rapide perdait des lettres. Après une rotation,
    // il repart du terme gardé par le ViewModel, curseur en fin.
    var saisie by remember { mutableStateOf(TextFieldValue(recherche.terme, TextRange(recherche.terme.length))) }
    TopAppBar(
        title = {
            TextField(
                value = saisie,
                onValueChange = {
                    saisie = it
                    if (it.text != recherche.terme) onTerme(it.text)
                },
                placeholder = { Text(stringResource(R.string.recherche_invite)) },
                singleLine = true,
                isError = recherche.aucune,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { onSuivante() }),
                // Le champ se fond dans la barre : ni fond ni trait, sauf le
                // trait d'erreur quand rien n'est trouvé.
                colors = TextFieldDefaults.colors(
                    focusedContainerColor = Color.Transparent,
                    unfocusedContainerColor = Color.Transparent,
                    errorContainerColor = Color.Transparent,
                    focusedIndicatorColor = Color.Transparent,
                    unfocusedIndicatorColor = Color.Transparent,
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .focusRequester(focus),
            )
        },
        actions = {
            Compteur(recherche)
            val actif = recherche.total > 0
            IconButton(onClick = onPrecedente, enabled = actif) {
                Icon(Icons.Default.KeyboardArrowUp, contentDescription = stringResource(R.string.recherche_precedente))
            }
            IconButton(onClick = onSuivante, enabled = actif) {
                Icon(Icons.Default.KeyboardArrowDown, contentDescription = stringResource(R.string.recherche_suivante))
            }
            IconButton(onClick = onFermer) {
                Icon(Icons.Default.Close, contentDescription = stringResource(R.string.recherche_fermer))
            }
        },
    )
}

/** « 3/17 », « 3/5000+ » au plafond, « 0 » sans résultat ; rien tant que rien n'est cherché. */
@Composable
private fun Compteur(recherche: RechercheOuverte) {
    if (recherche.termeCherche.isNullOrEmpty() || recherche.terme.isEmpty()) return
    val (texte, description) = if (recherche.total == 0) {
        stringResource(R.string.recherche_compteur_zero) to stringResource(R.string.recherche_aucune_occurrence)
    } else {
        val rang = recherche.indice + 1
        stringResource(
            if (recherche.plafond) R.string.recherche_compteur_plafond else R.string.recherche_compteur,
            rang,
            recherche.total,
        ) to stringResource(R.string.recherche_compteur_description, rang, recherche.total)
    }
    Text(
        text = texte,
        style = MaterialTheme.typography.labelLarge,
        color = if (recherche.total == 0) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.semantics { contentDescription = description },
    )
}
