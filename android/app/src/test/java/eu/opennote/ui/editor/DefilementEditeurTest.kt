package eu.opennote.ui.editor

import org.junit.Assert.assertEquals
import org.junit.Test

class DefilementEditeurTest {

    @Test
    fun aucunDefilementQuandLeCurseurEstVisible() {
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
    fun dernierTiersResteEnPlaceTantQuIlEstVisible() {
        assertEquals(
            0f,
            calculerDistanceMiseEnVue(
                offset = 700f,
                taille = 20f,
                tailleConteneur = 900f,
            ),
            0.001f,
        )
    }

    @Test
    fun curseurRecouvertRemonteJusteAuBordVisible() {
        assertEquals(
            70f,
            calculerDistanceMiseEnVue(
                offset = 700f,
                taille = 20f,
                tailleConteneur = 650f,
            ),
            0.001f,
        )
    }

    @Test
    fun aucunDefilementQuandLeClavierEstDejaDeplie() {
        assertEquals(
            0f,
            calculerDistanceMiseEnVue(
                offset = 700f,
                taille = 20f,
                tailleConteneur = 650f,
                clavierDejaDeplie = true,
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
