package eu.ocnotes.ui.editor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RemplacementNatifTest {

    @Test
    fun insertionEstReduiteAUnePlageVide() {
        assertEquals(
            RemplacementNatif(6, 6, "**gras** "),
            calculerRemplacementNatif("avant après", "avant **gras** après"),
        )
    }

    @Test
    fun suppressionEtRemplacementGardentPrefixeEtSuffixe() {
        assertEquals(
            RemplacementNatif(6, 12, ""),
            calculerRemplacementNatif("avant milieu après", "avant  après"),
        )
        assertEquals(
            RemplacementNatif(6, 12, "centre"),
            calculerRemplacementNatif("avant milieu après", "avant centre après"),
        )
    }

    @Test
    fun borneNeCoupePasEmojiNiCaractereCombine() {
        val avant = "préfixe 😀 e\u0301 suffixe"
        val apres = "préfixe 😎 e\u0301 suffixe"
        val remplacement = calculerRemplacementNatif(avant, apres)

        assertEquals("😀", avant.substring(remplacement.debut, remplacement.fin))
        assertEquals("😎", remplacement.texte)
        assertFalse(avant[remplacement.debut].isLowSurrogate())
    }

    @Test
    fun documentIdentiqueNeProduitAucunRemplacement() {
        assertTrue(calculerRemplacementNatif("مرحبا 😀", "مرحبا 😀").vide)
    }

    @Test
    fun reponsePerimeeEstRejetee() {
        assertTrue(revisionNativeToujoursCourante(attendue = 8, courante = 8))
        assertFalse(revisionNativeToujoursCourante(attendue = 8, courante = 9))
    }
}
