package eu.ocnotes.ui

import eu.ocnotes.ui.common.cibleLibre
import eu.ocnotes.ui.common.nettoyerPartagesExpires
import eu.ocnotes.ui.common.preparerDossierPartage
import eu.ocnotes.ui.common.typeCommun
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * Noms et types des pièces jointes d'un partage.
 *
 * L'intent lui-même ne se teste pas hors appareil ; ce qui décide de ce que
 * reçoit le destinataire, si.
 */
class PartageTest {

    @get:Rule
    val temporaire = TemporaryFolder()

    private val dossier = File("partage")

    @Test
    fun chaquePartageObtientSonPropreDossier() = runBlocking {
        val racine = temporaire.newFolder("partage")

        val premier = preparerDossierPartage(racine)
        File(premier, "note.md").writeText("premier")
        val second = preparerDossierPartage(racine)

        assertNotEquals(premier, second)
        assertTrue(File(premier, "note.md").exists())
        assertTrue(second.isDirectory)
    }

    @Test
    fun leNettoyageNeSupprimeQueLesPartagesExpires() {
        val racine = temporaire.newFolder("partage")
        val maintenant = 10_000_000L
        val expire = File(racine, "expire").apply { mkdir() }
        val recent = File(racine, "recent").apply { mkdir() }
        File(expire, "ancien.md").writeText("ancien")
        File(recent, "recent.md").writeText("recent")
        expire.setLastModified(maintenant - 60 * 60 * 1_000L)
        recent.setLastModified(maintenant - 59 * 60 * 1_000L)

        nettoyerPartagesExpires(racine, maintenant)

        assertFalse(expire.exists())
        assertTrue(recent.exists())
    }

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
        assertEquals("text/plain", typeCommun(listOf("config.yaml", "export.csv")))
        assertEquals("text/markdown", typeCommun(listOf("a.markdown", "b.MD")))
        assertEquals(
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
            typeCommun(listOf("rapport.DOCX")),
        )
        assertEquals("application/vnd.oasis.opendocument.text", typeCommun(listOf("rapport.odt")))
        assertEquals("text/" + "*", typeCommun(listOf("a.md", "b.txt")))
        assertEquals("text/" + "*", typeCommun(listOf("a.md", "config.yaml")))
        assertEquals("*/" + "*", typeCommun(listOf("a.md", "rapport.odt")))
    }
}
