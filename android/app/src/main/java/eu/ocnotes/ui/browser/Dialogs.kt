package eu.ocnotes.ui.browser

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.unit.dp
import eu.ocnotes.R
import eu.ocnotes.data.FolderEntryDto
import eu.ocnotes.data.FolderRefDto
import eu.ocnotes.ui.common.BoutonDangereux

/**
 * Boîte de saisie d'une ligne : création de note, de dossier, renommage.
 *
 * Le texte initial arrive présélectionné, pour qu'un renommage puisse être
 * remplacé d'une frappe sans avoir à tout effacer.
 */
@Composable
fun SaisieDialog(
    titre: String,
    label: String,
    valeurInitiale: String,
    libelleValidation: String,
    aide: String? = null,
    onValider: (String) -> Unit,
    onFermer: () -> Unit,
) {
    var valeur by remember {
        mutableStateOf(
            TextFieldValue(valeurInitiale, TextRange(0, valeurInitiale.length)),
        )
    }

    AlertDialog(
        onDismissRequest = onFermer,
        title = { Text(titre) },
        text = {
            Column {
                OutlinedTextField(
                    value = valeur,
                    onValueChange = { valeur = it },
                    label = { Text(label) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                aide?.let {
                    Text(
                        text = it,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    onValider(valeur.text)
                    onFermer()
                },
                enabled = valeur.text.isNotBlank(),
            ) {
                Text(libelleValidation)
            }
        },
        dismissButton = {
            TextButton(onClick = onFermer) { Text(stringResource(R.string.action_annuler)) }
        },
    )
}

/**
 * Création d'une note : un titre, et le dossier où la ranger.
 *
 * # Pourquoi un sélecteur, alors qu'il n'y en avait pas
 *
 * L'application choisissait déjà un dossier — le dossier affiché — mais sans
 * le dire. En arborescence ça se devinait ; en liste plate il n'y a pas de
 * dossier affiché, et la note tomberait n'importe où sans que l'utilisateur
 * puisse le prévoir. Rendre le choix visible dans les deux modes vaut mieux
 * que deux comportements différents selon la page.
 *
 * La destination proposée suit donc le mode : le dossier courant en
 * arborescence, le dernier utilisé en liste plate.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NouvelleNoteDialog(
    dossiers: List<FolderRefDto>,
    dossierPropose: String,
    nomRacine: String,
    onValider: (titre: String, dossier: String) -> Unit,
    onFermer: () -> Unit,
) {
    val choix = remember(dossiers) { dossiers.filter { it.canCreateFile } }
    var titre by remember { mutableStateOf("") }
    var dossier by remember(choix, dossierPropose) {
        mutableStateOf(
            dossierPropose.takeIf { propose -> choix.any { it.path == propose } }
                ?: choix.firstOrNull()?.path.orEmpty(),
        )
    }

    AlertDialog(
        onDismissRequest = onFermer,
        title = { Text(stringResource(R.string.browser_nouvelle_note)) },
        text = {
            Column {
                OutlinedTextField(
                    value = titre,
                    onValueChange = { titre = it },
                    label = { Text(stringResource(R.string.browser_note_label)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )

                SelecteurDossier(
                    dossiers = choix,
                    nomRacine = nomRacine,
                    valeur = dossier,
                    onValeur = { dossier = it },
                    label = stringResource(R.string.browser_note_dossier),
                    modifier = Modifier.padding(top = 12.dp),
                )

                Text(
                    text = stringResource(R.string.browser_note_aide),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    onValider(titre, dossier)
                    onFermer()
                },
                enabled = titre.isNotBlank() && choix.any { it.path == dossier },
            ) {
                Text(stringResource(R.string.action_creer))
            }
        },
        dismissButton = {
            TextButton(onClick = onFermer) { Text(stringResource(R.string.action_annuler)) }
        },
    )
}

/** Création d'un sous-dossier depuis la liste plate : un nom et son parent. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NouveauDossierDialog(
    dossiers: List<FolderRefDto>,
    nomRacine: String,
    onValider: (nom: String, dossierParent: String) -> Unit,
    onFermer: () -> Unit,
) {
    val choix = remember(dossiers) { dossiers.filter { it.canCreateFolder } }
    var nom by remember { mutableStateOf("") }
    var parent by remember(choix) { mutableStateOf(choix.firstOrNull()?.path.orEmpty()) }

    AlertDialog(
        onDismissRequest = onFermer,
        title = { Text(stringResource(R.string.browser_nouveau_dossier)) },
        text = {
            Column {
                OutlinedTextField(
                    value = nom,
                    onValueChange = { nom = it },
                    label = { Text(stringResource(R.string.browser_dossier_label)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                SelecteurDossier(
                    dossiers = choix,
                    nomRacine = nomRacine,
                    valeur = parent,
                    onValeur = { parent = it },
                    label = stringResource(R.string.browser_dossier_parent),
                    modifier = Modifier.padding(top = 12.dp),
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    onValider(nom, parent)
                    onFermer()
                },
                enabled = nom.isNotBlank() && choix.any { it.path == parent },
            ) {
                Text(stringResource(R.string.action_creer))
            }
        },
        dismissButton = {
            TextButton(onClick = onFermer) { Text(stringResource(R.string.action_annuler)) }
        },
    )
}

/**
 * Déplace une note vers un autre dossier.
 *
 * Réservé aux notes : un dossier porte la même action dans son menu, mais son
 * déplacement suit une règle de cache différente — le renommage local d'un
 * dossier ne remet à jour que ses notes déjà chargées, pas celles jamais
 * ouvertes — et n'est pas couvert ici.
 *
 * Le dossier actuel et les dossiers où OpenCloud interdit la création de
 * fichiers sont retirés : chaque choix proposé produit donc un déplacement
 * possible.
 */
@Composable
fun DeplacerDialog(
    entree: FolderEntryDto,
    dossiers: List<FolderRefDto>,
    nomRacine: String,
    onValider: (dossier: String) -> Unit,
    onFermer: () -> Unit,
) {
    val dossierActuel = entree.path.substringBeforeLast('/', "")
    val choix = remember(dossiers, dossierActuel) {
        dossiers.filter { it.canCreateFile && it.path != dossierActuel }
    }
    var dossier by remember(choix) { mutableStateOf(choix.firstOrNull()?.path.orEmpty()) }

    AlertDialog(
        onDismissRequest = onFermer,
        title = { Text(stringResource(R.string.browser_deplacer_titre, entree.display)) },
        text = {
            SelecteurDossier(
                dossiers = choix,
                nomRacine = nomRacine,
                valeur = dossier,
                onValeur = { dossier = it },
                label = stringResource(R.string.browser_note_dossier),
            )
        },
        confirmButton = {
            TextButton(
                onClick = {
                    onValider(dossier)
                    onFermer()
                },
                enabled = choix.any { it.path == dossier },
            ) {
                Text(stringResource(R.string.action_deplacer))
            }
        },
        dismissButton = {
            TextButton(onClick = onFermer) { Text(stringResource(R.string.action_annuler)) }
        },
    )
}

/**
 * Choisit un dossier de destination pour une action groupée : déplacement ou
 * copie. Seul le titre et le libellé du bouton changent.
 *
 * Réservé à une sélection de notes — un dossier a la même règle de cache
 * gênante que dans [DeplacerDialog], et pour la copie sa duplication récursive
 * est hors périmètre ; le ViewModel ne propose donc pas l'action quand la
 * sélection en contient un.
 *
 * La sélection peut venir de dossiers différents (mode liste plate), il n'y a
 * donc pas de « dossier actuel » unique à neutraliser. Seuls les dossiers où
 * OpenCloud autorise la création d'un fichier sont proposés.
 */
@Composable
fun DossierCibleLotDialog(
    titre: String,
    libelleAction: String,
    dossiers: List<FolderRefDto>,
    nomRacine: String,
    onValider: (dossier: String) -> Unit,
    onFermer: () -> Unit,
) {
    val choix = remember(dossiers) { dossiers.filter { it.canCreateFile } }
    var dossier by remember(choix) { mutableStateOf(choix.firstOrNull()?.path.orEmpty()) }

    AlertDialog(
        onDismissRequest = onFermer,
        title = { Text(titre) },
        text = {
            SelecteurDossier(
                dossiers = choix,
                nomRacine = nomRacine,
                valeur = dossier,
                onValeur = { dossier = it },
                label = stringResource(R.string.browser_note_dossier),
            )
        },
        confirmButton = {
            TextButton(
                onClick = {
                    onValider(dossier)
                    onFermer()
                },
                enabled = choix.any { it.path == dossier },
            ) {
                Text(libelleAction)
            }
        },
        dismissButton = {
            TextButton(onClick = onFermer) { Text(stringResource(R.string.action_annuler)) }
        },
    )
}

/**
 * Menu déroulant de choix d'un dossier, partagé par la création et le
 * déplacement.
 *
 * Le dossier de notes a un chemin vide et un nom vide dans [FolderRefDto] : la
 * façade ne choisit pas son libellé, puisque l'écran l'affiche déjà en titre.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SelecteurDossier(
    dossiers: List<FolderRefDto>,
    nomRacine: String,
    valeur: String,
    onValeur: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
) {
    var deroule by remember { mutableStateOf(false) }

    val libelle: (FolderRefDto) -> String = { if (it.path.isEmpty()) nomRacine else it.path }
    val choix = dossiers
    val libelleCourant = choix.firstOrNull { it.path == valeur }?.let(libelle) ?: nomRacine

    ExposedDropdownMenuBox(
        expanded = deroule,
        onExpandedChange = { deroule = it },
        modifier = modifier,
    ) {
        OutlinedTextField(
            value = libelleCourant,
            onValueChange = {},
            readOnly = true,
            label = { Text(label) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = deroule) },
            modifier = Modifier
                .menuAnchor(MenuAnchorType.PrimaryNotEditable)
                .fillMaxWidth(),
        )
        ExposedDropdownMenu(
            expanded = deroule,
            onDismissRequest = { deroule = false },
            modifier = Modifier.heightIn(max = 280.dp),
        ) {
            choix.forEach { ref ->
                DropdownMenuItem(
                    text = { Text(libelle(ref)) },
                    onClick = {
                        onValeur(ref.path)
                        deroule = false
                    },
                )
            }
        }
    }
}

/**
 * Confirmation de suppression.
 *
 * Un dossier part avec tout ce qu'il contient : le dire explicitement, parce
 * que c'est la seule action de l'application qui détruise des données.
 */
@Composable
fun SuppressionDialog(
    nomAffiche: String,
    estDossier: Boolean,
    modeLocal: Boolean,
    onConfirmer: () -> Unit,
    onFermer: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onFermer,
        title = { Text(stringResource(R.string.dialogue_supprimer_titre, nomAffiche)) },
        text = {
            Text(
                stringResource(
                    if (estDossier) {
                        if (modeLocal) {
                            R.string.dialogue_supprimer_dossier_local
                        } else {
                            R.string.dialogue_supprimer_dossier
                        }
                    } else {
                        if (modeLocal) {
                            R.string.dialogue_supprimer_note_local
                        } else {
                            R.string.dialogue_supprimer_note
                        }
                    },
                ),
            )
        },
        confirmButton = {
            BoutonDangereux(
                texte = stringResource(R.string.action_supprimer),
                onClick = {
                    onConfirmer()
                    onFermer()
                },
            )
        },
        dismissButton = {
            TextButton(onClick = onFermer) { Text(stringResource(R.string.action_annuler)) }
        },
    )
}

/**
 * Confirmation d'une suppression groupée.
 *
 * Le texte change dès qu'un dossier est dans le lot : il part avec tout ce
 * qu'il contient, et c'est la seule action de l'application qui détruise des
 * données.
 */
@Composable
fun SuppressionLotDialog(
    nombre: Int,
    contientDossier: Boolean,
    modeLocal: Boolean,
    onConfirmer: () -> Unit,
    onFermer: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onFermer,
        title = {
            Text(pluralStringResource(R.plurals.dialogue_supprimer_lot_titre, nombre, nombre))
        },
        text = {
            Text(
                stringResource(
                    if (contientDossier) {
                        if (modeLocal) {
                            R.string.dialogue_supprimer_lot_dossiers_local
                        } else {
                            R.string.dialogue_supprimer_lot_dossiers
                        }
                    } else {
                        if (modeLocal) {
                            R.string.dialogue_supprimer_lot_notes_local
                        } else {
                            R.string.dialogue_supprimer_lot_notes
                        }
                    },
                ),
            )
        },
        confirmButton = {
            BoutonDangereux(
                texte = stringResource(R.string.action_supprimer),
                onClick = {
                    onConfirmer()
                    onFermer()
                },
            )
        },
        dismissButton = {
            TextButton(onClick = onFermer) { Text(stringResource(R.string.action_annuler)) }
        },
    )
}
