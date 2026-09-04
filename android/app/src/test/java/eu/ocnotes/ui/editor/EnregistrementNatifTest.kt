package eu.ocnotes.ui.editor

import eu.ocnotes.data.MoteurEdition
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
        assertFalse(doitEnregistrerInstantaneNatif(etat(modifie = false), instantane))
    }

    @Test
    fun noteNonChargeeOuNonModifiableNecritPas() {
        assertFalse(doitEnregistrerInstantaneNatif(etat(charge = false), instantane))
        assertFalse(doitEnregistrerInstantaneNatif(etat(modifiable = false), instantane))
    }

    @Test
    fun revisionPerimeeNecritPas() {
        assertFalse(doitEnregistrerInstantaneNatif(etat(revision = 5), instantane))
    }

    @Test
    fun seulInstantaneNatifCourantEtModifieEstEnregistrable() {
        assertTrue(doitEnregistrerInstantaneNatif(etat(), instantane))
        assertFalse(
            doitEnregistrerInstantaneNatif(
                etat(moteur = MoteurEdition.VIRTUALISE),
                instantane,
            ),
        )
    }

    private fun etat(
        charge: Boolean = true,
        modifiable: Boolean = true,
        modifie: Boolean = true,
        revision: Long = 4,
        moteur: MoteurEdition = MoteurEdition.NATIF,
    ) = EditorUiState(
        charge = charge,
        modifiable = modifiable,
        modifie = modifie,
        revision = revision,
        moteurEdition = moteur,
    )
}
