package eu.opennote.ui.editor

import org.junit.Assert.assertEquals
import org.junit.Test

class DefilementEditeurTest {

    @Test
    fun aucunDefilementDansLesDeuxTiersSuperieurs() {
        assertEquals(
            0f,
            calculerDistanceMiseEnVue(
                offset = 530f,
                taille = 70f,
                tailleConteneur = 900f,
            ),
            0.001f,
        )
    }

    @Test
    fun dernierTiersRemonteSeulementJusquADeuxTiers() {
        assertEquals(
            120f,
            calculerDistanceMiseEnVue(
                offset = 700f,
                taille = 20f,
                tailleConteneur = 900f,
            ),
            0.001f,
        )
    }

    @Test
    fun demandeAuDessusRevientAuBordSansRecentrage() {
        assertEquals(
            -30f,
            calculerDistanceMiseEnVue(
                offset = -30f,
                taille = 20f,
                tailleConteneur = 900f,
            ),
            0.001f,
        )
    }

    @Test
    fun grandeZoneVisibleNeSeRecentrePas() {
        assertEquals(
            0f,
            calculerDistanceMiseEnVue(
                offset = 100f,
                taille = 700f,
                tailleConteneur = 900f,
            ),
            0.001f,
        )
    }
}
