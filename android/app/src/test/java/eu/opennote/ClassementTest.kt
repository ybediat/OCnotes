package eu.opennote

import eu.opennote.data.FolderEntryDto
import eu.opennote.ui.browser.ModeAffichage
import eu.opennote.ui.browser.Tri
import eu.opennote.ui.browser.classer
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Filtrage et classement de la liste du navigateur.
 *
 * La règle vit dans une fonction sans dépendance Android précisément pour
 * être vérifiée ici : sur la JVM, sans appareil, sans Robolectric. Le reste
 * de l'écran — l'icône du bouton, l'ouverture du champ — n'est que de
 * l'agencement autour de ces quelques lignes.
 */
class ClassementTest {

    private fun dossier(nom: String) =
        FolderEntryDto(path = nom, name = nom, display = nom, isDir = true)

    private fun note(nom: String, date: String = "") =
        FolderEntryDto(path = "$nom.md", name = "$nom.md", display = nom, modTime = date)

    private fun affiches(vararg entrees: FolderEntryDto, recherche: String = "", tri: Tri = Tri.DATE) =
        classer(entrees.toList(), recherche, tri).map { it.display }

    /**
     * Les dossiers n'ont pas de `modTime` : les soumettre au tri par date les
     * tasserait tous à une extrémité. Ils restent en tête, alphabétiques.
     */
    @Test
    fun dossiersEnTeteQuelQueSoitLeTri() {
        val entrees = arrayOf(
            note("zebre", "2026-08-20T10:00:00Z"),
            dossier("Projets"),
            note("alpha", "2026-08-28T10:00:00Z"),
            dossier("Archives"),
        )

        assertEquals(
            listOf("Archives", "Projets", "alpha", "zebre"),
            affiches(*entrees, tri = Tri.DATE).toList(),
        )
        assertEquals(
            listOf("Archives", "Projets", "alpha", "zebre"),
            affiches(*entrees, tri = Tri.NOM).toList(),
        )
    }

    @Test
    fun triParNomIgnoreLaCasse() {
        assertEquals(
            listOf("alpha", "Beta", "gamma"),
            affiches(note("gamma"), note("Beta"), note("alpha"), tri = Tri.NOM),
        )
    }

    @Test
    fun triParDateLaPlusRecenteDAbord() {
        assertEquals(
            listOf("recente", "moyenne", "ancienne"),
            affiches(
                note("ancienne", "2026-01-02T08:00:00Z"),
                note("recente", "2026-08-28T08:00:00Z"),
                note("moyenne", "2026-04-15T08:00:00Z"),
                tri = Tri.DATE,
            ),
        )
    }

    /** Une entrée dont on ignore l'âge se range en fin de liste, pas en tête. */
    @Test
    fun dateAbsenteEnFinDeListe() {
        assertEquals(
            listOf("datee", "sansDate"),
            affiches(note("sansDate"), note("datee", "2026-01-02T08:00:00Z"), tri = Tri.DATE),
        )
    }

    /** Chercher « resume » doit trouver « Résumé ». */
    @Test
    fun rechercheIgnoreCasseEtAccents() {
        assertEquals(
            listOf("Résumé de réunion"),
            affiches(note("Résumé de réunion"), note("Courses"), recherche = "resume"),
        )
    }

    /**
     * Les dossiers sont filtrés comme les notes : dans un dossier qui en
     * contient vingt, les laisser tous passer noierait les deux résultats.
     */
    @Test
    fun rechercheFiltreAussiLesDossiers() {
        assertEquals(
            listOf("Projets", "projet-alpha"),
            affiches(
                dossier("Projets"),
                dossier("Archives"),
                note("projet-alpha"),
                note("courses"),
                recherche = "projet",
            ),
        )
    }

    @Test
    fun rechercheVideNeFiltreRien() {
        assertEquals(
            listOf("Projets", "alpha", "beta"),
            affiches(dossier("Projets"), note("beta"), note("alpha"), recherche = "   ", tri = Tri.NOM),
        )
    }

    /**
     * Le mode d'affichage se relit comme le tri, et retombe sur
     * l'arborescence : c'est le comportement que l'application avait, et une
     * préférence illisible ne doit pas changer la page d'accueil de
     * quelqu'un.
     */
    @Test
    fun modeIllisibleRetombeSurLArborescence() {
        assertEquals(ModeAffichage.ARBORESCENCE, ModeAffichage.DEFAUT)
        assertEquals(ModeAffichage.DEFAUT, ModeAffichage.depuis(null))
        assertEquals(ModeAffichage.DEFAUT, ModeAffichage.depuis("MOSAIQUE"))
        assertEquals(ModeAffichage.LISTE, ModeAffichage.depuis("LISTE"))
        assertEquals(ModeAffichage.LISTE, ModeAffichage.ARBORESCENCE.suivant())
    }

    /** Une préférence absente ou écrite par une version inconnue ne casse rien. */
    @Test
    fun preferenceIllisibleRetombeSurLeDefaut() {
        assertEquals(Tri.DEFAUT, Tri.depuis(null))
        assertEquals(Tri.DEFAUT, Tri.depuis("PAR_COULEUR"))
        assertEquals(Tri.NOM, Tri.depuis("NOM"))
        assertEquals(Tri.DATE, Tri.NOM.suivant())
        assertEquals(Tri.NOM, Tri.DATE.suivant())
    }
}
