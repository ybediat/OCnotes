package eu.ocnotes.ui.editor

import eu.ocnotes.data.BlockKind
import eu.ocnotes.data.NoteBlockDto
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CaseACocherTest {

    @Test
    fun caseVideSeCoche() {
        assertEquals(RemplacementNatif(3, 4, "x"), basculerCase("- [ ] pain", 2))
    }

    @Test
    fun caseCocheeSeDecoche() {
        assertEquals(RemplacementNatif(3, 4, " "), basculerCase("- [x] pain", 2))
    }

    // Le « X » majuscule se décoche, mais n'est jamais normalisé en « x » :
    // la bascule remplace un caractère par un caractère, rien d'autre.
    @Test
    fun caseCocheeMajusculeSeDecoche() {
        assertEquals(RemplacementNatif(3, 4, " "), basculerCase("- [X] pain", 2))
    }

    @Test
    fun positionHorsBornesNeDonneRien() {
        assertNull(basculerCase("- [ ] pain", -1))
        assertNull(basculerCase("- [ ] pain", 8))
        assertNull(basculerCase("- [ ] pain", 100))
        // Le crochet ouvrant est là, mais le texte s'arrête avant le fermant.
        assertNull(basculerCase("- [ ", 2))
    }

    @Test
    fun positionSurAutreChoseNeDonneRien() {
        // Un caractère à côté : le texte a bougé depuis le rendu.
        assertNull(basculerCase("- [ ] pain", 3))
        assertNull(basculerCase("- [ ] pain", 0))
        // Un crochet, mais pas une case.
        assertNull(basculerCase("- [lien] pain", 2))
        assertNull(basculerCase("- [y] pain", 2))
    }

    @Test
    fun texteVideNeDonneRien() {
        assertNull(basculerCase("", 0))
    }

    // La position est en unités UTF-16 : un emoji en vaut deux.
    @Test
    fun positionEnUnitesUtf16() {
        val texte = "😀\n- [ ] pain"
        assertEquals(RemplacementNatif(6, 7, "x"), basculerCase(texte, 5))
    }

    // L'aperçu suit la bascule sans nouveau rendu : seule la tâche visée
    // change, et seulement son état.
    @Test
    fun seuleLaTacheViseeBasculeDansLesBlocs() {
        val blocs = listOf(
            NoteBlockDto(kind = BlockKind.TACHE, text = "pain", source = 2),
            NoteBlockDto(kind = BlockKind.TACHE, text = "lait", source = 13, checked = true),
            NoteBlockDto(kind = BlockKind.PARAGRAPHE, text = "suite", source = 2),
        )
        val apres = basculerTacheDansBlocs(blocs, 13)
        assertEquals(blocs[0], apres[0])
        assertEquals(blocs[1].copy(checked = false), apres[1])
        assertEquals(blocs[2], apres[2])
        // Un paragraphe qui commence à la même position n'est pas une case.
        assertEquals(blocs[2], basculerTacheDansBlocs(blocs, 2)[2])
        // Une position inconnue ne touche à rien.
        assertEquals(blocs, basculerTacheDansBlocs(blocs, 99))
    }
}
