package eu.ocnotes.data

import org.junit.Assert.assertEquals
import org.junit.Test

/** Numérotation des profils locaux créés sans nom. */
class NomLocalParDefautTest {

    private val base = "Notes locales"

    @Test
    fun `le premier profil local garde le nom de base`() {
        assertEquals(base, nomLocalParDefaut(base, emptyList()))
    }

    @Test
    fun `un ancien profil sans nom occupe le nom de base`() {
        // Les profils créés avant le nommage ont un nom vide, affiché « Notes locales ».
        assertEquals("$base 2", nomLocalParDefaut(base, listOf("")))
    }

    @Test
    fun `on prend le plus petit numero libre`() {
        assertEquals("$base 3", nomLocalParDefaut(base, listOf(base, "$base 2", "$base 4")))
        // Un trou laissé par une suppression est réutilisé.
        assertEquals("$base 2", nomLocalParDefaut(base, listOf(base, "$base 3")))
    }

    @Test
    fun `un nom choisi a la main ne bloque pas le nom de base`() {
        assertEquals(base, nomLocalParDefaut(base, listOf("Travail", "Perso")))
    }

    @Test
    fun `la comparaison ignore casse espaces et espace insecable`() {
        assertEquals("$base 2", nomLocalParDefaut(base, listOf("  notes LOCALES ")))
        assertEquals("$base 3", nomLocalParDefaut(base, listOf(base, "notes  locales 2")))
    }
}
