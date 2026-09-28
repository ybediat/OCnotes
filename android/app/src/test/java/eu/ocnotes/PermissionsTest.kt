package eu.ocnotes

import eu.ocnotes.data.ErrorCategory
import eu.ocnotes.data.FolderEntryDto
import eu.ocnotes.data.FolderRefDto
import eu.ocnotes.data.categorieDuCode
import eu.ocnotes.ui.browser.BrowserUiState
import eu.ocnotes.ui.browser.ModeAffichage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PermissionsTest {

    @Test
    fun refusLocalEtRefusServeurOntLaMemeCategorie() {
        assertEquals(ErrorCategory.FORBIDDEN, categorieDuCode("PERMISSION_DENIED"))
        assertEquals(ErrorCategory.FORBIDDEN, categorieDuCode("FORBIDDEN"))
    }

    @Test
    fun actionsDeSelectionRespectentSourceEtDestination() {
        val note = FolderEntryDto(
            path = "note.md",
            name = "note.md",
            display = "note",
            canMove = false,
            canDelete = false,
        )
        val sansDestination = BrowserUiState(entrees = listOf(note), selection = setOf(note.path))
        assertFalse(sansDestination.peutDeplacerSelection)
        assertFalse(sansDestination.peutCopierSelection)
        assertFalse(sansDestination.peutSupprimerSelection)
        assertTrue(sansDestination.peutPartagerSelection)

        val avecDestination = sansDestination.copy(
            dossiers = listOf(FolderRefDto(path = "cible", canCreateFile = true)),
        )
        assertFalse(avecDestination.peutDeplacerSelection)
        assertTrue(avecDestination.peutCopierSelection)
    }

    @Test
    fun creationDeDossierEnListePlateRespecteLesPermissionsDesParents() {
        val sansDestination = BrowserUiState(mode = ModeAffichage.LISTE)
        assertFalse(sansDestination.peutCreerDossier)

        val lectureSeule = sansDestination.copy(
            dossiers = listOf(FolderRefDto(path = "archives", canCreateFolder = false)),
        )
        assertFalse(lectureSeule.peutCreerDossier)

        val avecDestination = lectureSeule.copy(
            dossiers = lectureSeule.dossiers + FolderRefDto(
                path = "projets",
                canCreateFolder = true,
            ),
        )
        assertTrue(avecDestination.peutCreerDossier)
    }
}
