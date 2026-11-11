package eu.ocnotes.ui.login

import eu.ocnotes.data.AccountRegistry
import eu.ocnotes.data.ErrorCategory
import eu.ocnotes.data.MAX_NOM_LOCAL
import eu.ocnotes.data.OCnotesException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * Nom donné à un profil local à sa création (« Continuer en local » avec un
 * nom saisi). On branche [demarrerProfilLocal] sur un vrai [AccountRegistry]
 * dans un dossier temporaire : `startLocal` est remplacé par ce qu'il fait au
 * registre (`recordLocal`), `nommerLocal` par `renommerLocal`.
 */
class NommageProfilLocalTest {

    /** Crée un registre vierge dans un dossier temporaire, le passe à [bloc] puis l'efface. */
    private fun avecRegistre(bloc: suspend (File, AccountRegistry) -> Unit) {
        val racine = Files.createTempDirectory("ocnotes-nommage").toFile()
        try {
            runBlocking { bloc(racine, AccountRegistry(racine)) }
        } finally {
            racine.deleteRecursively()
        }
    }

    @Test
    fun `le nom saisi est garde par le profil local cree`() = avecRegistre { racine, registre ->
        val id = registre.active.id

        demarrerProfilLocal(
            "Carnet perso",
            demarrer = { registre.recordLocal(id) },
            nommer = { registre.renommerLocal(id, it) },
        )

        // Relu depuis le disque, comme au prochain lancement.
        val relu = AccountRegistry(racine).active
        assertEquals("local", relu.kind)
        assertEquals("Carnet perso", relu.displayName)
    }

    // Garde-fou de l'ordre : renommerLocal ignore un profil encore « serveur »,
    // le nom serait donc perdu sans erreur si on nommait avant de démarrer.
    @Test
    fun `nommer avant de passer en local perdrait le nom`() = avecRegistre { _, registre ->
        val id = registre.active.id

        registre.renommerLocal(id, "Carnet perso")
        registre.recordLocal(id)

        assertEquals("", registre.active.displayName)
    }

    @Test
    fun `le demarrage passe avant le nommage`() = runBlocking {
        val appels = mutableListOf<String>()

        demarrerProfilLocal(
            "Perso",
            demarrer = { appels += "demarrer" },
            nommer = { appels += "nommer:$it" },
        )

        assertEquals(listOf("demarrer", "nommer:Perso"), appels)
    }

    @Test
    fun `sans nom le profil garde celui qu il a`() = avecRegistre { _, registre ->
        val id = registre.active.id
        registre.recordLocal(id)
        registre.renommerLocal(id, "Ancien nom")
        var nommage = false

        demarrerProfilLocal(
            null,
            demarrer = { registre.recordLocal(id) },
            nommer = { nommage = true },
        )

        assertEquals(false, nommage)
        // recordLocal est rejoué à chaque démarrage local : le nom ne doit pas s'y perdre.
        assertEquals("Ancien nom", registre.active.displayName)
    }

    @Test
    fun `un demarrage en echec ne nomme pas le profil`() = runBlocking {
        var nommage = false

        try {
            demarrerProfilLocal(
                "Perso",
                demarrer = { throw OCnotesException(ErrorCategory.LOCAL, "LOCAL", "disque plein") },
                nommer = { nommage = true },
            )
            fail("l'erreur du démarrage doit remonter jusqu'à l'écran")
        } catch (e: OCnotesException) {
            assertEquals("LOCAL", e.code)
        }
        assertEquals(false, nommage)
    }

    @Test
    fun `le nom est nettoye et borne`() = avecRegistre { _, registre ->
        val id = registre.active.id
        val long = "x".repeat(MAX_NOM_LOCAL + 10)

        demarrerProfilLocal(
            "  $long  ",
            demarrer = { registre.recordLocal(id) },
            nommer = { registre.renommerLocal(id, it) },
        )

        val nom = registre.active.displayName
        assertEquals(MAX_NOM_LOCAL, nom.length)
        assertTrue(nom.all { it == 'x' })
    }

    @Test
    fun `un profil debranche du serveur repart sans nom`() = avecRegistre { _, registre ->
        val id = registre.active.id
        registre.recordAuthenticated(id, "https://cloud.test", "alice", "token", "cle", displayName = "Alice")

        demarrerProfilLocal(
            null,
            demarrer = { registre.recordLocal(id) },
            nommer = { registre.renommerLocal(id, it) },
        )

        // Le nom affiché venait du serveur : il ne doit pas suivre en local.
        assertEquals("", registre.active.displayName)
    }
}
