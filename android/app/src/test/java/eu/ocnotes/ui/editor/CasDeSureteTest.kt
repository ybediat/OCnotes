package eu.ocnotes.ui.editor

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Cas de sûreté de l'éditeur pour la seule transformation Kotlin du texte.
 *
 * **Ce qu'ils prouvent :** une mise en forme renvoyée par Go, réduite par
 * [calculerRemplacementNatif] à un unique `Editable.replace`, rend exactement
 * le texte que Go a calculé — quelle que soit la forme du document et l'endroit
 * de l'action, y compris contre un emoji.
 *
 * **Ce qu'ils ne prouvent pas :** l'aller-retour complet jusqu'au fichier. Le
 * chemin réel traverse encore `openEdit` et `writeEditedNote`, qui vivent en
 * Go et ont leurs propres tests (`TestOpenEditJSONAllerRetour`). La frappe
 * elle-même appartient à l'`EditText` et ne se vérifie que sur appareil.
 *
 * Ces quatre formes sont celles qui éprouvaient l'ancien éditeur virtualisé :
 * elles ont été gardées au retrait de celui-ci, pour que la suppression
 * n'emporte aucun test de sécurité.
 */
class CasDeSureteTest {

    @Test
    fun paragrapheUniqueDemesure() {
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
                append("et une image ![photo](ocnotes-image:").append(index).append(")\n\n")
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
     * Le contrat, sur un document donné : à plusieurs endroits, entourer,
     * retirer puis remplacer un passage — les trois formes que prennent les
     * réponses de `ApplyFormat` — et vérifier que le remplacement minimal,
     * appliqué au texte d'origine, redonne exactement le texte attendu.
     */
    private fun assertAllerRetour(document: String) {
        val offsets = listOf(
            0,
            1,
            document.length / 4,
            document.length / 2,
            (document.length * 3) / 4,
            document.length - 1,
            document.length,
        )

        offsets.forEach { brut ->
            // Une action part toujours d'une sélection valide : jamais au
            // milieu d'une paire de substitution.
            val debut = normaliserOffsetUtf16(document, brut)
            val fin = normaliserOffsetUtf16(document, debut + 12)
            val passage = document.substring(debut, fin)
            val avant = document.substring(0, debut)
            val apres = document.substring(fin)

            listOf(
                "entourer" to "$avant**$passage**$apres",
                "retirer" to avant + apres,
                "remplacer" to "$avant> 😀 $apres",
            ).forEach { (geste, attendu) ->
                assertEquals(
                    "$geste à $brut",
                    attendu,
                    appliquer(document, calculerRemplacementNatif(document, attendu)),
                )
            }
        }
    }

    /** Ce que fait `Editable.replace`, sur une String. */
    private fun appliquer(texte: String, remplacement: RemplacementNatif): String =
        texte.substring(0, remplacement.debut) + remplacement.texte + texte.substring(remplacement.fin)
}
