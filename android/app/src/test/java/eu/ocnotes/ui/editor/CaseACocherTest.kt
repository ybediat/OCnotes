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

    // Une tâche coupée par une image arrive en deux morceaux qui portent la
    // même case : ils basculent ensemble, et rien d'autre ne bouge.
    @Test
    fun lesMorceauxDUneTacheBasculentEnsemble() {
        val blocs = listOf(
            NoteBlockDto(kind = BlockKind.TACHE, text = "avant ", source = 2),
            NoteBlockDto(kind = BlockKind.IMAGE, text = "photo"),
            NoteBlockDto(kind = BlockKind.TACHE, text = " après", source = 2),
            NoteBlockDto(kind = BlockKind.TACHE, text = "autre", source = 40),
        )
        val apres = basculerTacheDansBlocs(blocs, 2)
        assertEquals(listOf(true, false, true, false), apres.map { it.checked })
        assertEquals(blocs[3], apres[3])
    }

    // Une tâche coupée par une image : le second morceau ne redessine pas la
    // case, une tâche voisine en a bien une.
    @Test
    fun tacheCoupeeParUneImageNaQuUneCase() {
        val blocs = listOf(
            NoteBlockDto(kind = BlockKind.TACHE, text = "avant", source = 2),
            NoteBlockDto(kind = BlockKind.IMAGE, text = "photo"),
            NoteBlockDto(kind = BlockKind.TACHE, text = "après", source = 2),
            NoteBlockDto(kind = BlockKind.TACHE, text = "voisine", source = 40),
        )
        val suites = grouperPourApercu(blocs).map { (it as ElementApercu.Bloc).suiteDeTache }
        assertEquals(listOf(false, false, true, false), suites)
    }

    // Un toucher sur un aperçu périmé se rejoue sur la même tâche, retrouvée
    // à son rang, même si sa position a bougé.
    @Test
    fun toucherPerimeRetrouveLaTache() {
        val avant = listOf(
            NoteBlockDto(kind = BlockKind.TACHE, text = "pain", source = 2),
            NoteBlockDto(kind = BlockKind.TACHE, text = "lait", source = 13),
        )
        val apres = listOf(
            NoteBlockDto(kind = BlockKind.PARAGRAPHE, text = "ajout", source = 0),
            NoteBlockDto(kind = BlockKind.TACHE, text = "pain", source = 9),
            NoteBlockDto(kind = BlockKind.TACHE, text = "lait", source = 20),
        )
        assertEquals(20, retrouverTache(avant, avant[1], apres)?.source)
    }

    // Texte changé ou état déjà voulu : on ne coche rien plutôt que la
    // mauvaise case.
    @Test
    fun toucherPerimeSansCorrespondanceNeRejoueRien() {
        val avant = listOf(NoteBlockDto(kind = BlockKind.TACHE, text = "pain", source = 2))
        assertNull(retrouverTache(avant, avant[0], listOf(NoteBlockDto(kind = BlockKind.TACHE, text = "pain complet", source = 2))))
        assertNull(retrouverTache(avant, avant[0], listOf(NoteBlockDto(kind = BlockKind.TACHE, text = "pain", source = 2, checked = true))))
        assertNull(retrouverTache(avant, avant[0], emptyList()))
    }

    // Une tâche identique insérée avant la touchée : le rang ne désigne plus
    // sûrement la bonne, rien n'est rejoué.
    @Test
    fun toucherPerimeAvecUneTacheEnDoubleNeRejoueRien() {
        // Deux « lait » ; on touche le second. Une troisième est insérée en
        // tête : au même rang se trouve désormais le premier « lait ».
        val avant = listOf(
            NoteBlockDto(kind = BlockKind.TACHE, text = "lait", source = 2),
            NoteBlockDto(kind = BlockKind.TACHE, text = "lait", source = 13),
        )
        val apres = listOf(
            NoteBlockDto(kind = BlockKind.TACHE, text = "lait", source = 2),
            NoteBlockDto(kind = BlockKind.TACHE, text = "lait", source = 13),
            NoteBlockDto(kind = BlockKind.TACHE, text = "lait", source = 24),
        )
        assertNull(retrouverTache(avant, avant[1], apres))
    }
}
