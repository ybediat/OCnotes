package eu.ocnotes.ui.editor

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * La règle de vitesse partagée par l'éditeur et l'aperçu. Le geste lui-même ne
 * se teste que sur appareil (carnet : `ANDROID-SELECTION-DEFILEMENT`).
 */
class DefilementSelectionTest {

    private fun vitesse(y: Float, bas: Float = 1_000f) =
        vitesseDefilementBord(y = y, haut = 0f, bas = bas, bande = 100f, vitesseMax = 500f)

    @Test
    fun rienAuMilieu() {
        assertEquals(0f, vitesse(100f), 0f)
        assertEquals(0f, vitesse(500f), 0f)
        assertEquals(0f, vitesse(900f), 0f)
    }

    @Test
    fun laVitesseCroitAvecLaProfondeurDansLaBande() {
        assertEquals(125f, vitesse(925f), 0.001f)
        assertEquals(250f, vitesse(950f), 0.001f)
        assertEquals(500f, vitesse(1_000f), 0.001f)
        assertEquals(-250f, vitesse(50f), 0.001f)
        assertEquals(-500f, vitesse(0f), 0.001f)
    }

    /** Le doigt finit souvent sa course sur la barre de format ou le clavier. */
    @Test
    fun auDelaDuBordLaVitessePlafonne() {
        assertEquals(500f, vitesse(1_400f), 0f)
        assertEquals(-500f, vitesse(-300f), 0f)
    }

    /**
     * Clavier ouvert, la zone visible rétrécit : deux bandes pleines s'y
     * toucheraient, et toute position ferait défiler.
     */
    @Test
    fun uneZoneCourteGardeUnMilieuSansDefilement() {
        assertEquals(0f, vitesse(75f, bas = 150f), 0f)
        assertEquals(500f, vitesse(150f, bas = 150f), 0.001f)
        assertEquals(250f, vitesse(125f, bas = 150f), 0.001f)
    }

    @Test
    fun zoneVideOuBandeNulleNeDefilentPas() {
        assertEquals(0f, vitesse(10f, bas = 0f), 0f)
        assertEquals(
            0f,
            vitesseDefilementBord(y = 999f, haut = 0f, bas = 1_000f, bande = 0f, vitesseMax = 500f),
            0f,
        )
    }
}
