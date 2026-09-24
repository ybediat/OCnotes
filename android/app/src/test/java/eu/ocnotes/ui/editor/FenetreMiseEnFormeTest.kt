package eu.ocnotes.ui.editor

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * La règle de fenêtre doit rester celle que prouve, côté Go,
 * `TestFenetreEquivautAuDocumentEntier` (`fenetreLignes`). Ces cas en fixent
 * les bords : début et fin de document, lignes vides, sélection inversée,
 * sélection qui s'arrête au début d'une ligne.
 */
class FenetreMiseEnFormeTest {

    private fun fenetre(texte: String, debut: Int, fin: Int = debut): String =
        fenetreMiseEnForme(texte, debut, fin).let { texte.substring(it.debut, it.fin) }

    @Test
    fun uneLigneDeContexteDeChaqueCote() {
        assertEquals("b\nc\nd", fenetre("a\nb\nc\nd\ne", 4))
    }

    @Test
    fun auDebutEtALaFinDuDocumentLaFenetreSArreteAuBord() {
        assertEquals("a\nb", fenetre("a\nb\nc", 0))
        assertEquals("b\nc", fenetre("a\nb\nc", 5))
        assertEquals("seule", fenetre("seule", 2))
        assertEquals("", fenetre("", 0))
    }

    @Test
    fun uneSelectionSurPlusieursLignesLesPrendToutes() {
        assertEquals("a\nb\nc\nd\ne", fenetre("a\nb\nc\nd\ne\nf", 2, 6))
    }

    @Test
    fun uneSelectionInverseeDonneLaMemeFenetre() {
        val texte = "a\nb\nc\nd\ne\nf"
        assertEquals(fenetre(texte, 2, 6), fenetre(texte, 6, 2))
    }

    @Test
    fun lesLignesVidesComptentCommeLignesDeContexte() {
        // Curseur sur « x » : la ligne vide d'avant et celle d'après suffisent.
        assertEquals("\nx\n", fenetre("a\n\nx\n\nb", 3))
    }

    @Test
    fun unCurseurEnDebutDeLigneRegardeLaLignePrecedente() {
        // Le délimiteur « ``` » précédent doit être vu par le bloc de code.
        assertEquals("```\ncode\n```", fenetre("avant\n```\ncode\n```\naprès", 10))
    }

    @Test
    fun lesBornesHorsDuTexteSontRamenees() {
        assertEquals("b\nc", fenetre("a\nb\nc", 99))
        assertEquals("a\nb", fenetre("a\nb\nc", -3))
    }

    @Test
    fun uneFenetreNeCoupeJamaisUnePaireUtf16() {
        val texte = "😀\n😀x\n😀"
        val bornes = fenetreMiseEnForme(texte, 4, 4)
        assertEquals(BornesFenetre(0, texte.length), bornes)
    }
}
