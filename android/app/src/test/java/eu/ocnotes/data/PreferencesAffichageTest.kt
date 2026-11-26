package eu.ocnotes.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
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

    // --- Notes épinglées ----------------------------------------------------

    @Test
    fun `aucune epingle par defaut`() {
        assertEquals(emptySet<String>(), PreferencesAffichage(StockageMemoire()).epingles("p1").value)
    }

    @Test
    fun `les epingles sont publiees et persistent par profil`() {
        val epingles = StockageEpinglesMemoire()
        val preferences = PreferencesAffichage(StockageMemoire(), epingles)

        preferences.definirEpingles("p1", setOf("Courses.md"))
        preferences.definirEpingles("p2", setOf("Journal/Mai.md"))

        assertEquals(setOf("Courses.md"), preferences.epingles("p1").value)
        // Une autre instance relit le stockage : chaque profil retrouve les siennes.
        val relues = PreferencesAffichage(StockageMemoire(), epingles)
        assertEquals(setOf("Courses.md"), relues.epingles("p1").value)
        assertEquals(setOf("Journal/Mai.md"), relues.epingles("p2").value)
    }

    @Test
    fun `les epingles ne vont pas dans le fichier de preferences`() {
        val stockage = StockageMemoire()
        val preferences = PreferencesAffichage(stockage, StockageEpinglesMemoire())

        preferences.definirEpingles("p1", setOf("Courses.md"))

        // Ce fichier part entier dans la sauvegarde, profils serveur compris.
        assertTrue(stockage.estVide())
    }

    @Test
    fun `une valeur abimee est normalisee a la lecture`() {
        val epingles = StockageEpinglesMemoire()
        epingles.contenu["p1"] = setOf("", " ", "/Courses.md", "Courses.md")

        assertEquals(setOf("Courses.md"), PreferencesAffichage(StockageMemoire(), epingles).epingles("p1").value)
    }

    @Test
    fun `retirer la derniere epingle efface le fichier`() {
        val epingles = StockageEpinglesMemoire()
        val preferences = PreferencesAffichage(StockageMemoire(), epingles)
        preferences.definirEpingles("p1", setOf("Courses.md"))

        preferences.definirEpingles("p1", emptySet())

        assertFalse("p1" in epingles.contenu)
    }

    @Test
    fun `supprimer un profil efface ses epingles et seulement les siennes`() {
        val epingles = StockageEpinglesMemoire()
        val preferences = PreferencesAffichage(StockageMemoire(), epingles)
        preferences.definirEpingles("p1", setOf("Courses.md"))
        preferences.definirEpingles("p2", setOf("Courses.md"))
        val flux = preferences.epingles("p1")

        preferences.effacerEpingles("p1")

        // L'écran qui observait encore le flux voit la rubrique se vider.
        assertEquals(emptySet<String>(), flux.value)
        assertFalse("p1" in epingles.contenu)
        assertEquals(emptySet<String>(), PreferencesAffichage(StockageMemoire(), epingles).epingles("p1").value)
        assertEquals(setOf("Courses.md"), PreferencesAffichage(StockageMemoire(), epingles).epingles("p2").value)
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

    fun estVide(): Boolean = chaines.isEmpty() && longs.isEmpty() && booleens.isEmpty()
}
