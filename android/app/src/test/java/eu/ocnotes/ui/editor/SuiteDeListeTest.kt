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
        val r = suite.remplacement
        val apres = texte.substring(0, r.debut) + r.texte + texte.substring(r.fin)
        return apres.substring(0, suite.curseur) + "‸" + apres.substring(suite.curseur)
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
    fun citationImbriqueeOuListeCitee() {
        assertEquals("> > a\n> > ‸", entree("> > a‸"))
        assertEquals("> - a\n> - ‸", entree("> - a‸"))
        assertEquals("> 2. a\n> 3. ‸", entree("> 2. a‸"))
        assertEquals("> - [x] a\n> - [ ] ‸", entree("> - [x] a‸"))
    }

    @Test
    fun ligneDeSeparationNEstPasUnePuce() {
        assertEquals("* * *\n‸", entree("* * *‸"))
        assertEquals("- - -\n‸", entree("- - -‸"))
        assertEquals("***\n‸", entree("***‸"))
        // Deux marques ne font pas une séparation : c'est une puce.
        assertEquals("* *\n* ‸", entree("* *‸"))
        // Une marque différente au milieu non plus.
        assertEquals("* - *\n* ‸", entree("* - *‸"))
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
    fun lesNumerosSuivantsSontDecales() {
        assertEquals("1. a\n2. ‸\n3. b\n4. c", entree("1. a‸\n2. b\n3. c"))
        assertEquals("3) a\n4) ‸\n5) b", entree("3) a‸\n4) b"))
        // 9 → 10 : le numéro s'allonge sans rien abîmer autour.
        assertEquals("8. a\n9. ‸\n10. b\n11. c", entree("8. a‸\n9. b\n10. c"))
        // Retrait conservé, sous-éléments et suite de paragraphe enjambés.
        assertEquals(
            "  1. a\n  2. ‸\n     - sous\n     suite\n  3. b",
            entree("  1. a‸\n     - sous\n     suite\n  2. b"),
        )
    }

    @Test
    fun laSuiteSArreteAuBordDeLaListe() {
        // Ligne vide : une autre liste commence.
        assertEquals("1. a\n2. ‸\n3. b\n\n3. c", entree("1. a‸\n2. b\n\n3. c"))
        // Moins en retrait : on sort de la sous-liste.
        assertEquals("  1. a\n  2. ‸\n  3. b\n4. x", entree("  1. a‸\n  2. b\n4. x"))
        // Autre délimiteur : autre liste.
        assertEquals("1. a\n2. ‸\n2) b", entree("1. a‸\n2) b"))
        // Numérotation non consécutive, faite à la main : laissée telle quelle.
        assertEquals("1. a\n2. ‸\n1. b\n1. c", entree("1. a‸\n1. b\n1. c"))
        assertEquals("1. a\n2. ‸\n3. b\n7. c", entree("1. a‸\n2. b\n7. c"))
        // Une puce n'est pas renumérotée.
        assertEquals("- a\n- ‸\n- b", entree("- a‸\n- b"))
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
