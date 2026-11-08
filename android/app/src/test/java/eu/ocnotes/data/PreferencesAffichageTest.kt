package eu.ocnotes.data

import org.junit.Assert.assertEquals
import org.junit.Test

class PreferencesAffichageTest {

    @Test
    fun `lexend est la police par defaut`() {
        val preferences = PreferencesAffichage(StockageMemoire())

        assertEquals(PoliceInterface.LEXEND, preferences.policeInterface.value)
    }

    @Test
    fun `le choix de police est publie et persiste`() {
        val stockage = StockageMemoire()
        val preferences = PreferencesAffichage(stockage)

        preferences.definirPoliceInterface(PoliceInterface.OPENDYSLEXIC)

        assertEquals(PoliceInterface.OPENDYSLEXIC, preferences.policeInterface.value)
        assertEquals(
            PoliceInterface.OPENDYSLEXIC,
            PreferencesAffichage(stockage).policeInterface.value,
        )
    }

    @Test
    fun `une ancienne valeur inconnue retombe sur lexend`() {
        val stockage = StockageMemoire(chaines = mutableMapOf("police_interface" to "inconnue"))

        assertEquals(
            PoliceInterface.LEXEND,
            PreferencesAffichage(stockage).policeInterface.value,
        )
    }

    @Test
    fun `la taille de lecture vaut cent pour cent par defaut`() {
        assertEquals(100, PreferencesAffichage(StockageMemoire()).tailleLecture.value)
    }

    @Test
    fun `la taille de lecture est publiee et persiste`() {
        val stockage = StockageMemoire()
        PreferencesAffichage(stockage).definirTailleLecture(150)

        assertEquals(150, PreferencesAffichage(stockage).tailleLecture.value)
    }

    @Test
    fun `une taille hors palier est ramenee au palier le plus proche`() {
        val stockage = StockageMemoire()
        val preferences = PreferencesAffichage(stockage)

        preferences.definirTailleLecture(140)
        assertEquals(130, preferences.tailleLecture.value)

        // Une valeur abîmée dans le fichier ne doit pas casser l'écran.
        stockage.ecrireLong("taille_texte_lecture", 99_999)
        assertEquals(200, PreferencesAffichage(stockage).tailleLecture.value)
    }

    @Test
    fun `les paliers suivant et precedent s arretent aux bornes`() {
        assertEquals(115, PaliersLecture.suivant(100))
        assertEquals(85, PaliersLecture.precedent(100))
        assertEquals(200, PaliersLecture.suivant(200))
        assertEquals(85, PaliersLecture.precedent(85))
    }
}

private class StockageMemoire(
    private val chaines: MutableMap<String, String> = mutableMapOf(),
) : StockagePreferencesAffichage {
    private val longs = mutableMapOf<String, Long>()
    private val booleens = mutableMapOf<String, Boolean>()

    override fun lireChaine(cle: String): String? = chaines[cle]

    override fun lireLong(cle: String, defaut: Long): Long = longs[cle] ?: defaut

    override fun lireBooleen(cle: String, defaut: Boolean): Boolean = booleens[cle] ?: defaut

    override fun ecrireChaine(cle: String, valeur: String) {
        chaines[cle] = valeur
    }

    override fun ecrireLong(cle: String, valeur: Long) {
        longs[cle] = valeur
    }

    override fun ecrireBooleen(cle: String, valeur: Boolean) {
        booleens[cle] = valeur
    }
}
