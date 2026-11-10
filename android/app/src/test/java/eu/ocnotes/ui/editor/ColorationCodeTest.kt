package eu.ocnotes.ui.editor

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * La coloration signale, elle ne décide de rien : ces cas fixent ce qu'elle
 * repère et, surtout, ce qu'elle laisse en paix (`snake_case`, `~5 minutes`).
 */
class ColorationCodeTest {

    /** Renvoie les fragments repérés, séparés par `|`. */
    private fun code(texte: String): String {
        val trouves = mutableListOf<String>()
        plagesDeCode(texte, 0, texte.length) { d, f -> trouves += texte.substring(d, f) }
        return trouves.joinToString("|")
    }

    @Test
    fun entiteInsecable() {
        assertEquals("&nbsp;", code("a&nbsp;b"))
        assertEquals("&#160;|&amp;", code("a&#160;b &amp; c"))
    }

    @Test
    fun uneEsperluetteSeuleNEstPasUneEntite() {
        assertEquals("", code("Dupont & fils, R&D, a & b;"))
    }

    @Test
    fun balisesHtml() {
        assertEquals("<br>|</div>", code("un<br>deux</div>"))
        assertEquals("", code("a < b et c > d"))
    }

    @Test
    fun marquesDeLigne() {
        assertEquals("##", code("## Titre"))
        assertEquals(">|>", code("> > citation"))
        assertEquals("-|[ ]", code("- [ ] à faire"))
        assertEquals("12.", code("12. étape"))
        assertEquals("-", code("  - sous-élément"))
    }

    @Test
    fun uneMarqueSansEspaceNEstPasUneMarque() {
        assertEquals("", code("#hashtag"))
        assertEquals("", code("-1 degré"))
        assertEquals("", code("2024.pdf"))
        assertEquals("", code("####### sept dièses"))
    }

    @Test
    fun emphaseEtCode() {
        assertEquals("**|**", code("un **mot** ici"))
        assertEquals("*|*", code("un *mot* ici"))
        assertEquals("`|`", code("un `mot` ici"))
        assertEquals("~~|~~", code("un ~~mot~~ ici"))
    }

    @Test
    fun soulignesDeMotNeSontPasDuCode() {
        assertEquals("", code("snake_case_name"))
        assertEquals("_|_", code("un _mot_ ici"))
    }

    @Test
    fun tildeSeulEstDuTexte() {
        assertEquals("", code("environ ~5 minutes"))
    }

    @Test
    fun liensEtImages() {
        assertEquals("[|](https://a.fr/x_(y))", code("[texte](https://a.fr/x_(y))"))
        assertEquals("![|](ocnotes-image:0)", code("![alt](ocnotes-image:0)"))
    }

    @Test
    fun clotureDeBlocDeCode() {
        assertEquals("```kotlin", code("```kotlin"))
        assertEquals("~~~", code("~~~"))
    }

    @Test
    fun plagesContiguesFusionnees() {
        assertEquals("&nbsp;&amp;", code("&nbsp;&amp;"))
    }

    @Test
    fun laFenetreNeLitQueSesBornes() {
        val texte = "**a**\n**b**\n**c**"
        val trouves = mutableListOf<Int>()
        plagesDeCode(texte, 6, 11) { d, _ -> trouves += d }
        assertEquals(listOf(6, 9), trouves)
    }

    @Test
    fun pasDePlageSurLeTexteOrdinaire() {
        assertEquals("", code("Un paragraphe, avec des virgules. Et un point !"))
        assertEquals("", code(""))
    }

    // --- Fenêtre colorée après une modification (decalerFenetre) ---

    @Test
    fun frappeEnFinDeNoteAgranditLaFenetre() {
        // Fenêtre [0, 10) sur une note de 10 caractères : on tape « x » à la fin.
        assertEquals(0 to 11, decalerFenetre(0, 10, 10, 0, 1, 10))
    }

    @Test
    fun noteVideSeColoreDesLaPremiereFrappe() {
        assertEquals(0 to 9, decalerFenetre(0, 0, 0, 0, 9, 0))
    }

    @Test
    fun frappeJusteApresUneFenetreAuMilieuEstIgnoree() {
        // La fenêtre s'arrête à 10 mais le texte continue : hors de l'écran.
        assertEquals(null, decalerFenetre(0, 10, 10, 0, 1, 50))
        assertEquals(null, decalerFenetre(0, 10, 30, 0, 1, 50))
    }

    @Test
    fun frappeAvantLaFenetreLaFaitGlisser() {
        assertEquals(22 to 42, decalerFenetre(20, 40, 5, 0, 2, 100))
        assertEquals(17 to 37, decalerFenetre(20, 40, 5, 3, 0, 100))
    }

    @Test
    fun frappeDansLaFenetreLaRedimensionne() {
        assertEquals(20 to 43, decalerFenetre(20, 40, 25, 0, 3, 100))
        assertEquals(20 to 38, decalerFenetre(20, 40, 25, 2, 0, 100))
    }

    @Test
    fun effacementEnFinDeNoteRetrecitLaFenetre() {
        assertEquals(0 to 9, decalerFenetre(0, 10, 9, 1, 0, 10))
    }
}
