package eu.ocnotes.ui.browser

import eu.ocnotes.data.FolderEntryDto
import eu.ocnotes.data.ResultatTexteDto
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Recherche dans le texte, côté Kotlin : le repli des noms, tenu au même
 * fichier de cas que Go, et la répartition des résultats entre la liste et la
 * section « Dans le texte ».
 */
class RechercheTest {

    /** Le fichier, cherché depuis le dossier de travail de Gradle (android/app) ou plus haut. */
    private fun fichierCas(): File {
        var dossier: File? = File("").absoluteFile
        while (dossier != null) {
            val candidat = File(dossier, "internal/search/testdata/cas-repli.txt")
            if (candidat.isFile) return candidat
            dossier = dossier.parentFile
        }
        error("cas-repli.txt introuvable")
    }

    /** Remplace les \uXXXX et \t du fichier par leur caractère. */
    private fun deChappe(s: String): String =
        Regex("""\\u([0-9A-Fa-f]{4})""")
            .replace(s.replace("\\t", "\t")) { it.groupValues[1].toInt(16).toChar().toString() }

    /**
     * Rejoue `internal/search/testdata/cas-repli.txt`, rejoué aussi par
     * `TestCasRepliPartages` en Go : une requête doit trouver un nom (ici) et
     * un texte (Go) de la même façon. Sans normalisation des lignes : le
     * fichier écrit ses accents décomposés en \u, exprès.
     */
    @Test
    fun repliCommunAvecGo() {
        var cas = 0
        fichierCas().readLines().forEachIndexed { i, ligne ->
            if (ligne.isBlank() || ligne.startsWith("#")) return@forEachIndexed
            val champs = ligne.split('¦')
            assertEquals("ligne ${i + 1} : 2 champs attendus", 2, champs.size)
            val entree = deChappe(champs[0].trim(' '))
            val attendu = deChappe(champs[1].trim(' '))
            assertEquals("ligne ${i + 1} : $entree", attendu, replie(entree))
            cas++
        }
        assertTrue("aucun cas lu", cas > 0)
    }

    private fun note(chemin: String) =
        FolderEntryDto(path = chemin, name = chemin.substringAfterLast('/'), display = chemin.substringAfterLast('/').removeSuffix(".md"))

    private fun resultat(chemin: String) =
        ResultatTexteDto(path = chemin, name = chemin.substringAfterLast('/'), display = chemin, count = 1, excerpt = "…")

    /** Une note déjà listée par son nom n'est pas répétée : elle gagne son extrait. */
    @Test
    fun noteListeeParSonNomGagneSonExtrait() {
        val r = repartirRecherche(
            affichees = listOf(note("Facture.md")),
            resultats = listOf(resultat("Facture.md"), resultat("Maison.md")),
        )
        assertEquals(setOf("Facture.md"), r.extraits.keys)
        assertEquals(listOf("Maison.md"), r.dansLeTexte.map { it.path })
    }

    /** Les résultats d'un sous-dossier vont dans la section, dans l'ordre de Go. */
    @Test
    fun resultatsHorsListeDansLaSection() {
        val r = repartirRecherche(
            affichees = listOf(note("Courses.md")),
            resultats = listOf(resultat("Projets/A.md"), resultat("Projets/2026/B.md"), resultat("C.md")),
        )
        assertTrue(r.extraits.isEmpty())
        assertEquals(listOf("Projets/A.md", "Projets/2026/B.md", "C.md"), r.dansLeTexte.map { it.path })
    }

    @Test
    fun sansResultatRienARepartir() {
        val r = repartirRecherche(listOf(note("A.md")), emptyList())
        assertTrue(r.extraits.isEmpty() && r.dansLeTexte.isEmpty())
    }
}
