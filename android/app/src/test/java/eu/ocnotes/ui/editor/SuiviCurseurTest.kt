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

/**
 * La marge basse rognée sert au dessin ; le recadrage sur la dernière ligne lit
 * la marge réelle, pour que l'aération de fin de note reste visible à la frappe.
 */
class MargeBasseTest {

    @Test
    fun enTempsNormalLaMargeEstRognee() {
        assertEquals(22, margeBasseLue(reelle = 132, rognage = 22, finDeNote = false))
    }

    @Test
    fun enFinDeNoteLaMargeEntiereEstLue() {
        assertEquals(132, margeBasseLue(reelle = 132, rognage = 22, finDeNote = true))
    }

    @Test
    fun sansRognageLaMargeReelleReste() {
        assertEquals(132, margeBasseLue(reelle = 132, rognage = Int.MAX_VALUE, finDeNote = false))
    }
}
