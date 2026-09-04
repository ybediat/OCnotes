package eu.ocnotes

import eu.ocnotes.ui.editor.EditorUiState
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Garde-fou de la seule règle qui peut détruire une note en silence : on
 * n'écrit que ce qu'on a su lire.
 *
 * La panne qu'elle protège n'est pas reproductible à la main. Quitter l'écran
 * pendant le chargement demande de viser une fenêtre de quelques dizaines de
 * millisecondes ; le second cas — un chargement qui a échoué — est stable, mais
 * il faut une note absente du cache et un serveur injoignable pour l'atteindre.
 * Dans les deux cas, l'état porte un texte vide, et l'enregistrer effacerait la
 * note sur le serveur.
 *
 * D'où la forme du correctif : la décision est une fonction de l'état, pas une
 * suite d'appels. Elle se vérifie ici sur la JVM, sans appareil, sans
 * Robolectric et sans coroutine — ce que la course, elle, n'aurait jamais
 * permis.
 */
class EditeurEnregistrableTest {

    /** À l'ouverture, le champ est vide parce que rien n'est encore lu. */
    @Test
    fun etatInitialNonEnregistrable() {
        assertFalse(EditorUiState().enregistrable)
    }

    /**
     * Le cas qui effaçait des notes : le chargement est terminé — donc l'écran
     * n'affiche plus de progression — mais il a échoué, et le texte est vide.
     */
    @Test
    fun chargementTermineMaisEchoueNonEnregistrable() {
        val etat = EditorUiState(chargement = false, charge = false)
        assertFalse(etat.enregistrable)
    }

    /** Une note lue s'enregistre : la garde ne doit rien bloquer d'utile. */
    @Test
    fun noteChargeeEnregistrable() {
        val etat = EditorUiState(chargement = false, charge = true)
        assertTrue(etat.enregistrable)
    }

    /**
     * Une note ouverte en lecture seule porte un texte allégé — les images en
     * sont sorties. L'écrire remplacerait la vraie note par sa version amputée.
     */
    @Test
    fun noteNonModifiableNonEnregistrable() {
        val etat = EditorUiState(chargement = false, charge = true, modifiable = false)
        assertFalse(etat.enregistrable)
    }
}
