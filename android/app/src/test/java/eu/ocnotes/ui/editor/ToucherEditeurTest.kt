package eu.ocnotes.ui.editor

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Le vide sous la dernière tranche doit ouvrir la saisie — et lui seul.
 *
 * La règle est extraite du composable pour la même raison que la politique de
 * défilement : elle décide d'un déplacement du curseur, et un déplacement de
 * curseur qui se trompe de mille lignes ne se voit pas dans une capture
 * d'écran.
 */
class ToucherEditeurTest {

    @Test
    fun sousLaFinDuDocumentLaSaisieSOuvre() {
        assertTrue(
            toucheSousLeTexte(
                positionY = 1_400f,
                basDernierElement = 900f,
                dernierEstFinDuDocument = true,
            ),
        )
    }

    @Test
    fun surLeTexteRienNeSePasse() {
        // La tranche gère elle-même ce toucher, et pose le curseur au caractère
        // visé. Le détecteur du fond ne doit pas doubler ce geste.
        assertFalse(
            toucheSousLeTexte(
                positionY = 400f,
                basDernierElement = 900f,
                dernierEstFinDuDocument = true,
            ),
        )
    }

    @Test
    fun dansUneNoteLongueLeVideNEnvoiePasAlaFin() {
        // Le dernier élément visible n'est pas le dernier du document : on est
        // au milieu d'une grande note, et rien ne justifie d'aller à la fin.
        assertFalse(
            toucheSousLeTexte(
                positionY = 2_000f,
                basDernierElement = 900f,
                dernierEstFinDuDocument = false,
            ),
        )
    }

    @Test
    fun justeSousLaDerniereLigneCompteCommeLeVide() {
        assertTrue(
            toucheSousLeTexte(
                positionY = 901f,
                basDernierElement = 900f,
                dernierEstFinDuDocument = true,
            ),
        )
    }
}
