package eu.ocnotes.ui.editor

import org.junit.Assert.assertEquals
import org.junit.Test

class OffsetsUtf16Test {

    @Test
    fun uneBorneNeCoupeJamaisUnePaireUtf16() {
        // L'offset 2 tombe entre les deux unités UTF-16 de l'emoji.
        assertEquals(1, normaliserOffsetUtf16("a😀b", 2))
    }

    @Test
    fun lesBornesDuDocumentSontRespectees() {
        assertEquals(0, normaliserOffsetUtf16("abc", -4))
        assertEquals(3, normaliserOffsetUtf16("abc", 99))
        assertEquals(0, normaliserOffsetUtf16("", 5))
    }

    @Test
    fun unOffsetHorsPaireResteInchange() {
        assertEquals(1, normaliserOffsetUtf16("a😀b", 1))
        assertEquals(3, normaliserOffsetUtf16("a😀b", 3))
        assertEquals(4, normaliserOffsetUtf16("a😀b", 4))
    }
}
