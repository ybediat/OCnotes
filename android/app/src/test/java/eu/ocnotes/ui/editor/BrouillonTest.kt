package eu.ocnotes.ui.editor

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** « Brouillon local, envoi programmé » : annoncé tant qu'il reste à écrire ou à envoyer. */
class BrouillonTest {

    @Test
    fun resteAnnonceTantQueLaFrappeNestPasEcrite() {
        assertTrue(brouillonApres(EditorUiState(brouillon = true, modifie = true), enAttente = 0))
    }

    @Test
    fun resteAnnonceEntreLEcritureEtLEnvoi() {
        // L'enregistrement local toutes les 700 ms ne fait plus clignoter le sous-titre.
        assertTrue(brouillonApres(EditorUiState(brouillon = true, modifie = false), enAttente = 1))
    }

    @Test
    fun sEteintQuandLaPasseVideLaFile() {
        assertFalse(brouillonApres(EditorUiState(brouillon = true, modifie = false), enAttente = 0))
    }

    @Test
    fun uneFileNonVideNeLeRallumePas() {
        // D'autres notes en attente ne font pas d'une note intacte un brouillon.
        assertFalse(brouillonApres(EditorUiState(brouillon = false, modifie = false), enAttente = 3))
    }
}
