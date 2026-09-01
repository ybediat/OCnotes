package eu.opennote.ui.editor

import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Cas de sûreté de l'éditeur pour les interactions critiques.
 *
 * **Ce qu'ils prouvent :** le découpage, l'activation d'une fenêtre et la
 * matérialisation rendent le document au caractère près, quelle que soit la
 * forme du texte — et une frappe n'ajoute que ce qu'on a tapé, là où on l'a
 * tapé.
 *
 * **Ce qu'ils ne prouvent pas :** l'aller-retour complet jusqu'au fichier. Le
 * chemin réel traverse encore `prepareEdit`, `restoreImages` et `writeNote`,
 * dont les deux premiers vivent en Go et ont leurs propres tests. Le maillon
 * que ces tests-ci couvrent est celui qui n'en avait aucun : la machine
 * d'édition Kotlin.
 *
 * La différence n'est pas rhétorique. C'est le seul chemin du dépôt qui peut
 * détruire des données en silence : une image sortie du texte et non restituée
 * part sur le serveur remplacée par son jeton, sans un message.
 */
class CasDeSureteTest {

    @Test
    fun paragrapheUniqueDemesure() {
        // Aucun retour à la ligne : c'est le budget UTF-16 seul qui borne.
        assertAllerRetour("mot ".repeat(20_000).trim())
    }

    @Test
    fun listeEtBlocDeCodeDePlusDeCinqCentsLignes() {
        val document = buildString {
            repeat(600) { append("- élément ").append(it).append('\n') }
            append("\n```kotlin\n")
            repeat(500) { append("    val ligne").append(it).append(" = ").append(it).append('\n') }
            append("```\n")
        }
        assertAllerRetour(document)
    }

    @Test
    fun accentsEmojiEtImagesAllegees() {
        // Le jeton remplace une image que `PrepareEdit` a sortie du texte. Il
        // est ici du texte comme un autre : l'éditeur ne doit pas le connaître.
        val document = buildString {
            repeat(200) { index ->
                append("Paragraphe ").append(index)
                append(" — accents é è ê ç ù, emoji 😀🇫🇷👨‍👩‍👧, ")
                append("et une image ![photo](opennote-image:").append(index).append(")\n\n")
            }
        }
        assertAllerRetour(document)
    }

    @Test
    fun texteBrutVolumineux() {
        // Un .txt : de longues lignes, aucun balisage, et des tabulations.
        val document = buildString {
            repeat(400) { index ->
                append("Ligne ").append(index).append('\t')
                append("Une phrase ordinaire, sans le moindre marqueur Markdown, ")
                append("répétée pour faire du volume. ".repeat(6))
                append('\n')
            }
        }
        assertAllerRetour(document)
    }

    /**
     * Le contrat, sur un document donné : le découper puis le rassembler ne
     * change rien, activer une fenêtre n'importe où ne change rien, et taper un
     * caractère n'ajoute que ce caractère, à l'endroit du curseur.
     */
    private fun assertAllerRetour(document: String) {
        val tranches = decouperDocument(document)
        assertEquals(
            "le découpage seul ne rend pas le document",
            document,
            tranches.joinToString("") { it.texteDe(document) },
        )
        tranches.forEach { tranche ->
            val texte = tranche.texteDe(document)
            assertTrue(
                "tranche de ${texte.length} unités",
                texte.length <= MAX_UTF16_EDITEUR,
            )
        }

        val offsets = listOf(
            0,
            1,
            document.length / 4,
            document.length / 2,
            (document.length * 3) / 4,
            document.length - 1,
            document.length,
        )

        offsets.forEach { offset ->
            val ouvert = activerFenetre(etatInitial(document, tranches), offset)
            assertEquals(
                "activer à $offset a modifié le document",
                document,
                materialiser(ouvert),
            )

            // Le curseur réel, pas celui qu'on a demandé : une borne posée au
            // milieu d'une paire de substitution recule sur son début.
            val active = ouvert.tranches[ouvert.focus]
            val curseur = active.debut + ouvert.valeur.selection.end
            val texteActif = ouvert.valeur.text
            val local = ouvert.valeur.selection.end
            val frappe = modifierFenetre(
                ouvert,
                TextFieldValue(
                    texteActif.substring(0, local) + "Z" + texteActif.substring(local),
                    TextRange(local + 1),
                ),
            )

            assertEquals(
                "frappe à $offset (curseur réel $curseur)",
                document.substring(0, curseur) + "Z" + document.substring(curseur),
                materialiser(frappe),
            )
        }
    }

    private fun etatInitial(document: String, tranches: List<TrancheEditeur>) = EditorUiState(
        document = document,
        tranches = tranches,
        focus = -1,
        valeur = TextFieldValue(),
    )
}
