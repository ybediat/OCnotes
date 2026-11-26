package eu.ocnotes.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class StockageEpinglesFichiersTest {

    private val idLocal = "11111111-1111-1111-1111-111111111111"
    private val idServeur = "22222222-2222-2222-2222-222222222222"

    /** Crée un `filesDir` temporaire, le passe à [bloc] puis l'efface. */
    private fun avecDossier(bloc: (File) -> Unit) {
        val racine = Files.createTempDirectory("ocnotes-epingles").toFile()
        try {
            bloc(racine)
        } finally {
            racine.deleteRecursively()
        }
    }

    /** Écritures exécutées sur-le-champ : le test lit juste après. */
    private fun stockage(racine: File) =
        StockageEpinglesFichiers(File(racine, AccountRegistry.ACCOUNTS_DIR)) { it.run() }

    private fun dossierProfil(racine: File, id: String): File =
        File(racine, "${AccountRegistry.ACCOUNTS_DIR}/$id").apply { mkdirs() }

    @Test
    fun `les epingles sont rangees dans le dossier du profil et relues`() = avecDossier { racine ->
        dossierProfil(racine, idLocal)
        val chemins = setOf("Courses.md", "Journal/Été 🌞.md")

        stockage(racine).ecrire(idLocal, chemins)

        assertTrue(File(racine, "accounts/$idLocal/epingles.txt").isFile)
        assertEquals(chemins, stockage(racine).lire(idLocal))
    }

    @Test
    fun `aucun fichier veut dire aucune epingle`() = avecDossier { racine ->
        dossierProfil(racine, idLocal)
        assertNull(stockage(racine).lire(idLocal))
    }

    @Test
    fun `rien n'est ecrit pour un profil dont le dossier n'existe plus`() = avecDossier { racine ->
        stockage(racine).ecrire(idLocal, setOf("Courses.md"))

        // Recréer le dossier d'un profil supprimé en ferait un fantôme.
        assertFalse(File(racine, "accounts/$idLocal").exists())
    }

    @Test
    fun `un identifiant qui n'est pas un UUID n'ecrit nulle part`() = avecDossier { racine ->
        dossierProfil(racine, idLocal)
        stockage(racine).ecrire("../$idLocal", setOf("Courses.md"))

        assertFalse(File(racine, "accounts/$idLocal/epingles.txt").exists())
        assertNull(stockage(racine).lire("../$idLocal"))
    }

    @Test
    fun `effacer retire le fichier`() = avecDossier { racine ->
        dossierProfil(racine, idLocal)
        val stockage = stockage(racine)
        stockage.ecrire(idLocal, setOf("Courses.md"))

        stockage.effacer(idLocal)

        assertNull(stockage.lire(idLocal))
    }

    @Test
    fun `un chemin a saut de ligne est ecarte plutot que coupe en deux`() = avecDossier { racine ->
        dossierProfil(racine, idLocal)

        stockage(racine).ecrire(idLocal, setOf("Courses.md", "a\nb.md"))

        assertEquals(setOf("Courses.md"), stockage(racine).lire(idLocal))
    }

    @Test
    fun `seules les epingles des profils locaux partent dans la sauvegarde`() = avecDossier { racine ->
        racine.resolve("accounts.json").writeText(
            """{"version":1,"activeAccountId":"$idServeur","accounts":[""" +
                """{"id":"$idLocal","kind":"local","serverUrl":"","displayName":"Perso"},""" +
                """{"id":"$idServeur","kind":"server","serverUrl":"https://cloud.test","displayName":""}]}""",
        )
        dossierProfil(racine, idLocal).resolve("config.json").writeText("""{"mode":"local"}""")
        dossierProfil(racine, idServeur).resolve("config.json").writeText("""{"mode":"server"}""")
        stockage(racine).ecrire(idLocal, setOf("Courses.md"))
        stockage(racine).ecrire(idServeur, setOf("Secret.md"))

        val locaux = SauvegardeProfils.ecrireInstantane(racine)
        val envoyes = SauvegardeProfils.fichiersASauvegarder(racine, locaux)
            .map { it.relativeTo(racine).invariantSeparatorsPath }

        assertTrue("accounts/$idLocal/epingles.txt" in envoyes)
        assertFalse("accounts/$idServeur/epingles.txt" in envoyes)
    }
}
