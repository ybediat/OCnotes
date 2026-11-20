package eu.ocnotes.ui.editor

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EnregistrementNatifTest {

    private val instantane = InstantaneEditeurNatif(
        texte = "accent é, emoji 😀, combinaison e\u0301, arabe مرحبا",
        selection = SelectionEditeurNatif(10, 12),
        revision = 4,
        defilementX = 0,
        defilementY = 120,
    )

    @Test
    fun ouvertureSansModificationNecritPas() {
        assertFalse(doitEnregistrerInstantaneNatif(etat(modifie = false), 4, instantane))
    }

    @Test
    fun noteNonChargeeOuNonModifiableNecritPas() {
        assertFalse(doitEnregistrerInstantaneNatif(etat(charge = false), 4, instantane))
        assertFalse(doitEnregistrerInstantaneNatif(etat(modifiable = false), 4, instantane))
    }

    @Test
    fun revisionPerimeeNecritPas() {
        assertFalse(doitEnregistrerInstantaneNatif(etat(), 5, instantane))
    }

    @Test
    fun seulInstantaneNatifCourantEtModifieEstEnregistrable() {
        assertTrue(doitEnregistrerInstantaneNatif(etat(), 4, instantane))
    }

    // Passer en aperçu enregistre tout de suite ; le délai de 700 ms qui
    // expire ensuite ne doit pas annuler cette écriture pour refaire la même.
    @Test
    fun memeRevisionEnCoursNEstPasRelancee() {
        assertTrue(enregistrementDejaEnCours(actif = true, revisionEnCours = 4, revision = 4))
        // Une révision plus récente remplace l'écriture en cours.
        assertFalse(enregistrementDejaEnCours(actif = true, revisionEnCours = 4, revision = 5))
        // L'écriture est finie (ou annulée) : on peut écrire de nouveau.
        assertFalse(enregistrementDejaEnCours(actif = false, revisionEnCours = 4, revision = 4))
    }

    private fun etat(
        charge: Boolean = true,
        modifiable: Boolean = true,
        modifie: Boolean = true,
    ) = EditorUiState(
        charge = charge,
        modifiable = modifiable,
        modifie = modifie,
    )
}
