package eu.ocnotes.ui.editor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Entrée en fin d'élément de liste. Chaque cas part du texte **avant** la
 * frappe, le curseur marqué `‸`, y insère le saut de ligne comme le ferait le
 * clavier, puis applique [suiteDeListe] : c'est exactement le chemin du champ
 * natif, moins l'`Editable`.
 */
class SuiteDeListeTest {

    /** Texte et curseur après Entrée et la suite éventuelle ; `‸` marque le curseur. */
    private fun entree(avant: String): String {
        val curseur = avant.indexOf('‸')
        val texte = StringBuilder(avant).deleteCharAt(curseur).insert(curseur, '\n').toString()
        val suite = suiteDeListe(texte, curseur)
            ?: return texte.substring(0, curseur + 1) + "‸" + texte.substring(curseur + 1)
        val apres = texte.substring(0, suite.debut) + suite.texte + texte.substring(suite.fin)
        val nouveauCurseur = suite.debut + suite.texte.length
        return apres.substring(0, nouveauCurseur) + "‸" + apres.substring(nouveauCurseur)
    }

    @Test
    fun numeroSuivant() {
        assertEquals("1. a\n2. ‸", entree("1. a‸"))
        assertEquals("9. a\n10. ‸", entree("9. a‸"))
        assertEquals("3) a\n4) ‸", entree("3) a‸"))
    }

    @Test
    fun puceIdentique() {
        assertEquals("- a\n- ‸", entree("- a‸"))
        assertEquals("* a\n* ‸", entree("* a‸"))
        assertEquals("+ a\n+ ‸", entree("+ a‸"))
    }

    @Test
    fun caseACocherToujoursVide() {
        assertEquals("- [ ] a\n- [ ] ‸", entree("- [ ] a‸"))
        assertEquals("- [x] a\n- [ ] ‸", entree("- [x] a‸"))
        assertEquals("* [X] a\n* [ ] ‸", entree("* [X] a‸"))
    }

    @Test
    fun citation() {
        assertEquals("> a\n> ‸", entree("> a‸"))
    }

    @Test
    fun retraitConserve() {
        assertEquals("  - a\n  - ‸", entree("  - a‸"))
        assertEquals("\t1. a\n\t2. ‸", entree("\t1. a‸"))
    }

    @Test
    fun elementVideTermineLaListe() {
        assertEquals("1. a\n‸", entree("1. a\n2. ‸"))
        assertEquals("- a\n‸", entree("- a\n- ‸"))
        assertEquals("- [ ] a\n‸", entree("- [ ] a\n- [ ] ‸"))
        assertEquals("> a\n‸", entree("> a\n> ‸"))
        assertEquals("  - a\n‸", entree("  - a\n  - ‸"))
    }

    @Test
    fun suiteEnMilieuDeDocument() {
        assertEquals("1. a\n2. ‸\nsuite", entree("1. a‸\nsuite"))
    }

    @Test
    fun auMilieuDUneLigneRienDePlus() {
        assertEquals("1. a\n‸b", entree("1. a‸b"))
    }

    @Test
    fun horsListeRienDePlus() {
        assertEquals("texte\n‸", entree("texte‸"))
        assertEquals("# Titre\n‸", entree("# Titre‸"))
        // Sans espace après la marque, ce n'est pas une liste.
        assertEquals("-a\n‸", entree("-a‸"))
        assertEquals("1.a\n‸", entree("1.a‸"))
        assertEquals("---\n‸", entree("---‸"))
        assertEquals("\n‸", entree("‸"))
    }

    @Test
    fun numeroDemesureNEstPasUneListe() {
        assertEquals("1234567890. a\n‸", entree("1234567890. a‸"))
        assertEquals("999999999. a\n1000000000. ‸", entree("999999999. a‸"))
    }

    @Test
    fun dansUnBlocDeCodeRienDePlus() {
        assertEquals("```\n- a\n‸", entree("```\n- a‸"))
        assertEquals("~~~\n1. a\n‸", entree("~~~\n1. a‸"))
        // Après la clôture, la liste reprend ses droits.
        assertEquals("```\nx\n```\n- a\n- ‸", entree("```\nx\n```\n- a‸"))
    }

    @Test
    fun blocDeCode() {
        assertFalse(dansBlocDeCode("a\nb\n", 4))
        assertTrue(dansBlocDeCode("```kotlin\nval a\n", 10))
        assertTrue(dansBlocDeCode("   ```\nx\n", 7))
        // Quatre espaces : c'est un bloc indenté, pas une clôture.
        assertFalse(dansBlocDeCode("    ```\nx\n", 8))
        assertFalse(dansBlocDeCode("```\nx\n```\n", 10))
    }

    @Test
    fun positionInvalide() {
        assertNull(suiteDeListe("1. a", 4))
        assertNull(suiteDeListe("1. a", -1))
        // Le caractère en position n'est pas un saut de ligne.
        assertNull(suiteDeListe("1. ab", 4))
    }
}
