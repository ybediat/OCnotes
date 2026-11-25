package eu.ocnotes.ui.browser

import eu.ocnotes.data.FolderEntryDto
import eu.ocnotes.data.normaliserEpingles
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

/**
 * Règles des notes épinglées : une épingle suit sa note, disparaît avec elle,
 * et ne s'affiche que si la note est dans l'inventaire.
 */
class EpinglesTest {

    private fun note(chemin: String, date: String = "") = FolderEntryDto(
        path = chemin,
        name = chemin.substringAfterLast('/'),
        display = chemin.substringAfterLast('/').removeSuffix(".md"),
        modTime = date,
    )

    private fun dossier(chemin: String) = FolderEntryDto(
        path = chemin,
        name = chemin.substringAfterLast('/'),
        display = chemin.substringAfterLast('/'),
        isDir = true,
    )

    // --- Lecture -----------------------------------------------------------

    @Test
    fun `la lecture ecarte les chemins vides et les doublons`() {
        val lu = normaliserEpingles(listOf("", "  ", "/", "Courses.md", "/Courses.md", "Journal/Mai.md/"))

        assertEquals(setOf("Courses.md", "Journal/Mai.md"), lu)
    }

    @Test
    fun `la lecture ne touche ni la casse ni les accents`() {
        val lu = normaliserEpingles(listOf("Journal.md", "journal.md", "Été/Idée.md"))

        assertEquals(setOf("Journal.md", "journal.md", "Été/Idée.md"), lu)
    }

    // --- Renommage et déplacement -------------------------------------------

    @Test
    fun `renommer une note deplace son epingle et rien d'autre`() {
        val epingles = setOf("Courses.md", "Journal/Mai.md")

        val apres = apresRenommage(epingles, "Courses.md", "Marché.md", estDossier = false)

        assertEquals(setOf("Marché.md", "Journal/Mai.md"), apres)
    }

    @Test
    fun `deplacer une note emporte son epingle`() {
        val apres = apresRenommage(setOf("Courses.md"), "Courses.md", "Maison/Courses.md", estDossier = false)

        assertEquals(setOf("Maison/Courses.md"), apres)
    }

    @Test
    fun `renommer une note non epinglee ne change rien`() {
        val epingles = setOf("Courses.md")

        assertEquals(epingles, apresRenommage(epingles, "Autre.md", "Encore.md", estDossier = false))
    }

    @Test
    fun `une note renommee ne fait pas suivre les notes de meme prefixe`() {
        // « Journal » n'est pas un dossier ici : `Journal/Mai.md` ne doit pas
        // bouger quand la note `Journal` est renommée.
        val epingles = setOf("Journal", "Journal/Mai.md")

        val apres = apresRenommage(epingles, "Journal", "Carnet", estDossier = false)

        assertEquals(setOf("Carnet", "Journal/Mai.md"), apres)
    }

    @Test
    fun `renommer un dossier fait suivre les trois epingles situees dessous`() {
        val epingles = setOf(
            "Notes/a/un.md",
            "Notes/a/deux.md",
            "Notes/a/sous/trois.md",
            "Ailleurs.md",
        )

        val apres = apresRenommage(epingles, "Notes/a", "Notes/b", estDossier = true)

        assertEquals(
            setOf("Notes/b/un.md", "Notes/b/deux.md", "Notes/b/sous/trois.md", "Ailleurs.md"),
            apres,
        )
    }

    @Test
    fun `un prefixe trompeur n'est pas un dossier parent`() {
        // `Notes/ab` commence par `Notes/a` sans être dessous.
        val epingles = setOf("Notes/ab/x.md", "Notes/a/y.md")

        val apres = apresRenommage(epingles, "Notes/a", "Notes/z", estDossier = true)

        assertEquals(setOf("Notes/ab/x.md", "Notes/z/y.md"), apres)
    }

    @Test
    fun `un chemin a emoji et accents suit son dossier`() {
        val epingles = setOf("Été 😀/Idée du soir.md")

        val apres = apresRenommage(epingles, "Été 😀", "Hiver ❄️", estDossier = true)

        assertEquals(setOf("Hiver ❄️/Idée du soir.md"), apres)
    }

    @Test
    fun `aucune epingle reste aucune epingle`() {
        assertEquals(emptySet<String>(), apresRenommage(emptySet(), "a.md", "b.md", estDossier = false))
        assertEquals(emptySet<String>(), apresSuppression(emptySet(), "a.md", estDossier = false))
        assertEquals(emptySet<String>(), elaguer(emptySet(), setOf("a.md")))
        assertEquals(emptyList<FolderEntryDto>(), sectionEpinglees(emptySet(), listOf(note("a.md")), Tri.NOM))
    }

    @Test
    fun `l'ensemble passe n'est jamais modifie`() {
        val epingles = setOf("a.md")

        apresRenommage(epingles, "a.md", "b.md", estDossier = false)
        apresSuppression(epingles, "a.md", estDossier = false)

        assertEquals(setOf("a.md"), epingles)
        // Rien à faire : le même objet revient, l'appelant n'écrit pas.
        assertSame(epingles, apresRenommage(epingles, "a.md", "a.md", estDossier = false))
    }

    // --- Suppression ---------------------------------------------------------

    @Test
    fun `supprimer une note retire son epingle`() {
        val apres = apresSuppression(setOf("Courses.md", "Journal.md"), "Courses.md", estDossier = false)

        assertEquals(setOf("Journal.md"), apres)
    }

    @Test
    fun `supprimer un dossier retire les epingles dessous et pas celles d'a cote`() {
        val epingles = setOf("Notes/a/un.md", "Notes/a/sous/deux.md", "Notes/ab/trois.md")

        val apres = apresSuppression(epingles, "Notes/a", estDossier = true)

        assertEquals(setOf("Notes/ab/trois.md"), apres)
    }

    @Test
    fun `supprimer une note ne retire pas les epingles de meme prefixe`() {
        val apres = apresSuppression(setOf("Journal", "Journal/Mai.md"), "Journal", estDossier = false)

        assertEquals(setOf("Journal/Mai.md"), apres)
    }

    // --- Élagage ---------------------------------------------------------------

    @Test
    fun `l'elagage ne garde que les notes connues`() {
        val apres = elaguer(setOf("Courses.md", "Renommée sur le web.md"), setOf("Courses.md", "Autre.md"))

        assertEquals(setOf("Courses.md"), apres)
    }

    // --- Rubrique du tiroir ------------------------------------------------------

    @Test
    fun `la rubrique ecarte les orphelines et les dossiers`() {
        val inventaire = listOf(note("Courses.md"), note("Journal/Mai.md"), dossier("Journal"))
        val epingles = setOf("Courses.md", "Journal", "Disparue.md")

        val section = sectionEpinglees(epingles, inventaire, Tri.NOM)

        assertEquals(listOf("Courses.md"), section.map { it.path })
    }

    @Test
    fun `la rubrique suit le tri par nom`() {
        val inventaire = listOf(note("b/Zèbre.md"), note("alpha.md"), note("a/Milieu.md"))
        val epingles = inventaire.mapTo(HashSet()) { it.path }

        val section = sectionEpinglees(epingles, inventaire, Tri.NOM)

        assertEquals(listOf("alpha", "Milieu", "Zèbre"), section.map { it.display })
    }

    @Test
    fun `la rubrique suit le tri par date, la plus recente d'abord`() {
        val inventaire = listOf(
            note("vieille.md", "2026-01-01T10:00:00Z"),
            note("Journal/recente.md", "2026-11-20T10:00:00Z"),
            note("moyenne.md", "2026-06-01T10:00:00Z"),
        )
        val epingles = inventaire.mapTo(HashSet()) { it.path }

        val section = sectionEpinglees(epingles, inventaire, Tri.DATE)

        assertEquals(listOf("recente", "moyenne", "vieille"), section.map { it.display })
    }

    @Test
    fun `une ligne du tiroir porte son dossier, ou le dossier de notes a la racine`() {
        val lignes = lignesTiroir(listOf(note("Courses.md"), note("Été/Journal/Mai.md")), "Notes")

        assertEquals(
            listOf(
                EpingleeTiroir("Courses.md", "Courses", "Notes"),
                EpingleeTiroir("Été/Journal/Mai.md", "Mai", "Été/Journal"),
            ),
            lignes,
        )
    }
}
