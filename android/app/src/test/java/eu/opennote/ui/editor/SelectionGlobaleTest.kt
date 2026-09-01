package eu.opennote.ui.editor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SelectionGlobaleTest {

    @Test
    fun documentEntierEtDocumentVideSontRepresentesParDeuxOffsets() {
        val document = "début 😀 fin"

        val documentEntier = SelectionGlobale.creer(
            document,
            ancre = -20,
            mobile = document.length + 20,
        )
        val documentVide = SelectionGlobale.creer("", ancre = -1, mobile = 1)

        assertEquals(0, documentEntier.ancre)
        assertEquals(document.length, documentEntier.mobile)
        assertEquals(0, documentVide.ancre)
        assertEquals(0, documentVide.mobile)
    }

    @Test
    fun sensDesAncresEstConserve() {
        val selection = SelectionGlobale.creer("0123456789", ancre = 8, mobile = 3)

        assertEquals(8, selection.ancre)
        assertEquals(3, selection.mobile)
        assertEquals(3, selection.debut)
        assertEquals(8, selection.fin)
        assertTrue(selection.inversee)
        assertFalse(selection.vide)
    }

    @Test
    fun uneBorneNeCoupeJamaisUnePaireUtf16() {
        val document = "a😀b"

        val versLaDroite = SelectionGlobale.creer(document, ancre = 0, mobile = 2)
        val versLaGauche = SelectionGlobale.creer(document, ancre = 2, mobile = 0)

        // L'offset 2 tombe entre les deux unités UTF-16 de l'emoji.
        assertEquals(1, versLaDroite.mobile)
        assertEquals(1, versLaGauche.ancre)
        assertTrue(versLaGauche.inversee)
    }

    @Test
    fun intersectionPartielleAvecPlusieursTranches() {
        val selection = SelectionGlobale.creer("x".repeat(18), ancre = 2, mobile = 12)
        val tranches = listOf(
            TrancheEditeur(0, 4),
            TrancheEditeur(4, 9),
            TrancheEditeur(9, 14),
            TrancheEditeur(14, 18),
        )

        assertEquals(
            listOf(
                PlageSelectionLocale(2, 4),
                PlageSelectionLocale(0, 5),
                PlageSelectionLocale(0, 3),
                null,
            ),
            tranches.map(selection::intersectionAvec),
        )
    }

    @Test
    fun intersectionCompleteEtSelectionInverseeDonnentLaMemePlage() {
        val tranche = TrancheEditeur(10, 20)

        assertEquals(
            PlageSelectionLocale(0, 10),
            SelectionGlobale.creer("x".repeat(25), ancre = 5, mobile = 25)
                .intersectionAvec(tranche),
        )
        assertEquals(
            PlageSelectionLocale(2, 8),
            SelectionGlobale.creer("x".repeat(25), ancre = 18, mobile = 12)
                .intersectionAvec(tranche),
        )
    }

    @Test
    fun uneFrontiereCommuneEtUneSelectionVideNeDessinentRien() {
        assertNull(
            SelectionGlobale.creer("x".repeat(10), ancre = 0, mobile = 5)
                .intersectionAvec(TrancheEditeur(5, 10)),
        )
        assertNull(
            SelectionGlobale.creer("x".repeat(10), ancre = 7, mobile = 7)
                .intersectionAvec(TrancheEditeur(5, 10)),
        )
    }

    @Test
    fun sortieEffondreeAccepteUneAnciennePlagePlusGrandeQueLaFenetre() {
        val document = "ligne de texte\n".repeat(200)
        val selection = SelectionGlobale.creer(document, ancre = 0, mobile = document.length)

        val offset = selection.offsetEffondre(document)
        val montage = monterFenetre(document, offset)

        assertEquals(document.length, offset)
        assertEquals(montage.selectionDebut, montage.selectionFin)
    }

    @Test
    fun sortieVersUnOffsetToucheNormaliseLaBorneUtf16() {
        val document = "a😀b"
        val selection = SelectionGlobale.creer(document, ancre = 0, mobile = document.length)

        assertEquals(1, selection.offsetEffondre(document, offsetSouhaite = 2))
    }
}
