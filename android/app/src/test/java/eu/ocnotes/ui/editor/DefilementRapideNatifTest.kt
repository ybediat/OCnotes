package eu.ocnotes.ui.editor

import org.junit.Assert.assertEquals
import org.junit.Test

class DefilementRapideNatifTest {

    @Test
    fun curseurEstProportionnelAvecUnMinimum() {
        assertEquals(
            100f,
            hauteurCurseurRapide(
                hauteurPiste = 1_000,
                hauteurVisible = 100,
                maximum = 900,
                hauteurMinimum = 36f,
            ),
            0.001f,
        )
        assertEquals(
            36f,
            hauteurCurseurRapide(
                hauteurPiste = 1_000,
                hauteurVisible = 10,
                maximum = 99_990,
                hauteurMinimum = 36f,
            ),
            0.001f,
        )
    }

    @Test
    fun positionSuitLeDebutLeMilieuEtLaFin() {
        assertEquals(0f, positionCurseurRapide(0, 900, 1_000, 100f), 0.001f)
        assertEquals(450f, positionCurseurRapide(450, 900, 1_000, 100f), 0.001f)
        assertEquals(900f, positionCurseurRapide(900, 900, 1_000, 100f), 0.001f)
    }

    @Test
    fun contactPiloteLeCentreEtResteBorne() {
        assertEquals(0f, progressionDepuisContact(-20f, 1_000, 100f), 0.001f)
        assertEquals(0.5f, progressionDepuisContact(500f, 1_000, 100f), 0.001f)
        assertEquals(1f, progressionDepuisContact(1_020f, 1_000, 100f), 0.001f)
    }

    @Test
    fun unSimpleToucherNeLancePasLeDefilement() {
        assertEquals(false, glisserDemarreDefilement(deplacementY = 0f, seuil = 8f))
        assertEquals(false, glisserDemarreDefilement(deplacementY = 8f, seuil = 8f))
        assertEquals(false, glisserDemarreDefilement(deplacementY = -5f, seuil = 8f))
    }

    @Test
    fun unGlisserVersLeHautOuLeBasLanceLeDefilement() {
        assertEquals(true, glisserDemarreDefilement(deplacementY = 9f, seuil = 8f))
        assertEquals(true, glisserDemarreDefilement(deplacementY = -40f, seuil = 8f))
    }
}
