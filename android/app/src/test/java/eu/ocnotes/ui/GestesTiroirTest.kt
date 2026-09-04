package eu.ocnotes.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GestesTiroirTest {

    @Test
    fun gesteActifSeulementDansLeNavigateur() {
        assertTrue(gestesTiroirActifs(Routes.NAVIGATEUR))
        assertFalse(gestesTiroirActifs(Routes.EDITEUR))
        assertFalse(gestesTiroirActifs(Routes.REGLAGES))
        assertFalse(gestesTiroirActifs(null))
    }
}
