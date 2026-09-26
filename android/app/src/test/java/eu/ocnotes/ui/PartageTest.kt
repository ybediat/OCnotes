package eu.ocnotes.ui

import eu.ocnotes.ui.common.cibleLibre
import eu.ocnotes.ui.common.typeCommun
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

/**
 * Noms et types des pièces jointes d'un partage.
 *
 * L'intent lui-même ne se teste pas hors appareil ; ce qui décide de ce que
 * reçoit le destinataire, si.
 */
class PartageTest {

    private val dossier = File("partage")

    /**
     * En liste plate, deux notes de dossiers différents portent souvent le
     * même nom. Sans renumérotation, la seconde copie écrasait la première et
     * le destinataire recevait deux fois le même fichier.
     */
    @Test
    fun deuxNomsIdentiquesDonnentDeuxCopiesDistinctes() {
        val pris = mutableSetOf<String>()
        val noms = listOf("journal.md", "journal.md", "Journal.md", "rapport.docx", "rapport.docx")
            .map { cibleLibre(dossier, it, pris).name }

        assertEquals(
            listOf("journal.md", "journal (2).md", "Journal (3).md", "rapport.docx", "rapport (2).docx"),
            noms,
        )
    }

    @Test
    fun unNomSansExtensionOuDangereuxResteUtilisable() {
        val pris = mutableSetOf<String>()
        assertEquals("a_b.md", cibleLibre(dossier, "a/b.md", pris).name)
        assertEquals("LISEZMOI", cibleLibre(dossier, "LISEZMOI", pris).name)
        assertEquals("LISEZMOI (2)", cibleLibre(dossier, "LISEZMOI", pris).name)
        assertEquals("note.md", cibleLibre(dossier, "  ", pris).name)
    }

    @Test
    fun leTypeSuitLeFormatEtRetombeSurLePlusGeneral() {
        assertEquals("text/markdown", typeCommun(listOf("a.md", "b.md")))
        assertEquals("text/plain", typeCommun(listOf("a.txt")))
        assertEquals(
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
            typeCommun(listOf("rapport.DOCX")),
        )
        assertEquals("application/vnd.oasis.opendocument.text", typeCommun(listOf("rapport.odt")))
        assertEquals("text/" + "*", typeCommun(listOf("a.md", "b.txt")))
        assertEquals("*/" + "*", typeCommun(listOf("a.md", "rapport.odt")))
    }
}
