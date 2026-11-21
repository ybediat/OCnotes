package eu.ocnotes.ui.editor

import eu.ocnotes.data.BlockKind
import eu.ocnotes.data.NoteBlockDto
import eu.ocnotes.data.NoteSpanDto
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

// Un paragraphe trop long arrive de Go en morceaux : seul l'affichage de la
// jointure est l'affaire de l'aperçu. Le retour final d'un morceau suivi d'une
// suite disparaît à l'écran, ses spans sont ramenés en deçà.
class ApercuSuiteTest {

    private fun morceau(texte: String, suite: Boolean, vararg spans: NoteSpanDto) =
        NoteBlockDto(kind = BlockKind.PARAGRAPHE, text = texte, cont = suite, spans = spans.toList())

    @Test
    fun leMorceauSuiviPerdSonRetourFinalALAffichage() {
        val blocs = listOf(
            morceau("un\ndeux\n", suite = false, NoteSpanDto(start = 3, end = 8, style = "bold")),
            morceau("trois", suite = true),
        )
        val elements = grouperPourApercu(blocs)

        val premier = elements[0] as ElementApercu.Bloc
        assertTrue(premier.avantSuite)
        assertEquals("un\ndeux", premier.bloc.text)
        // Le gras couvrait « deux\n » : il s'arrête désormais à « deux ».
        assertEquals(listOf(NoteSpanDto(start = 3, end = 7, style = "bold")), premier.bloc.spans)

        val second = elements[1] as ElementApercu.Bloc
        assertFalse(second.avantSuite)
        assertEquals("trois", second.bloc.text)
    }

    @Test
    fun unSpanReduitAuRetourDisparait() {
        val bloc = morceau("ab\n", suite = false, NoteSpanDto(start = 2, end = 3, style = "italic"))
        assertTrue(sansRetourFinal(bloc).spans.isEmpty())
    }

    @Test
    fun unBlocSansSuiteResteTelQuel() {
        val bloc = morceau("fin\n", suite = false)
        val element = grouperPourApercu(listOf(bloc)).single() as ElementApercu.Bloc
        assertFalse(element.avantSuite)
        assertSame(bloc, element.bloc)
    }

    @Test
    fun leSautDuPlanViseToujoursLeBonElement() {
        // Chaque morceau est un élément : indiceElement reste aligné sur
        // grouperPourApercu.
        val blocs = listOf(
            morceau("a\n", suite = false),
            morceau("b\n", suite = true),
            morceau("c", suite = true),
            NoteBlockDto(kind = BlockKind.TITRE, text = "Titre", level = 1),
        )
        val elements = grouperPourApercu(blocs)
        assertEquals(4, elements.size)
        assertEquals("Titre", (elements[indiceElement(blocs, 3)] as ElementApercu.Bloc).bloc.text)
    }
}
