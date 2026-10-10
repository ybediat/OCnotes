package eu.ocnotes.ui.editor

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Le droit de ramener le curseur à l'écran se consomme au recadrage réel, pas
 * à l'appel que `TextView` diffère faute de mise en page à jour. Sinon, une
 * ligne tapée en bas de l'écran restait sous le bord du champ.
 */
class SuiviCurseurTest {

    @Test
    fun sansDroitRienNeDefile() {
        // Un changement de spans pendant un défilement au doigt : la note ne
        // doit pas retomber sur le curseur.
        assertEquals(SuiviCurseur.REFUSER, suiviCurseur(autorise = false, miseEnPageEnAttente = false))
        assertEquals(SuiviCurseur.REFUSER, suiviCurseur(autorise = false, miseEnPageEnAttente = true))
    }

    @Test
    fun miseEnPageEnAttenteLeDroitSurvit() {
        assertEquals(SuiviCurseur.DIFFERER, suiviCurseur(autorise = true, miseEnPageEnAttente = true))
    }

    @Test
    fun miseEnPageAJourLeRecadrageALieu() {
        assertEquals(SuiviCurseur.SUIVRE, suiviCurseur(autorise = true, miseEnPageEnAttente = false))
    }
}
