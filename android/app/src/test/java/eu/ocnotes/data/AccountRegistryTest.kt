package eu.ocnotes.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files
import kotlinx.coroutines.runBlocking

class AccountRegistryTest {

    @Test
    fun `seul un profil serveur configure autorise la synchronisation`() {
        assertFalse(AccountProfile("local", "local").syncEnabled)
        assertFalse(AccountProfile("vierge", "server").syncEnabled)
        assertFalse(AccountProfile("inconnu", "inconnu", "https://cloud.test").syncEnabled)
        assertTrue(
            AccountProfile(
                id = "serveur",
                kind = "server",
                serverUrl = "https://cloud.test",
            ).syncEnabled,
        )
    }

    @Test
    fun `une installation serveur conserve son cache et son compte`() {
        val root = Files.createTempDirectory("ocnotes-accounts").toFile()
        try {
            root.resolve("config.json").writeText(
                """{"version":2,"mode":"server","serverUrl":"https://cloud.test","username":"alice"}""",
            )
            root.resolve("cache").mkdirs()
            root.resolve("cache/index.json").writeText("file-persistante")

            val registry = AccountRegistry(root)
            val profile = registry.active

            assertEquals("https://cloud.test", profile.serverUrl)
            assertEquals("alice", profile.username)
            assertFalse(root.resolve("config.json").exists())
            assertFalse(root.resolve("cache").exists())
            assertTrue(registry.profileDir().resolve("config.json").isFile)
            assertEquals("file-persistante", registry.profileDir().resolve("cache/index.json").readText())

            val reopened = AccountRegistry(root)
            assertEquals(profile.id, reopened.active.id)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `un profil local est déplacé sans demander de reconnexion`() {
        val root = Files.createTempDirectory("ocnotes-local").toFile()
        try {
            root.resolve("config.json").writeText("""{"version":2,"mode":"local"}""")
            root.resolve("cache").mkdirs()

            val registry = AccountRegistry(root)

            assertEquals("local", registry.active.kind)
            assertTrue(registry.profileDir().resolve("cache").isDirectory)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `une installation vierge reçoit directement un profil isolé`() {
        val root = Files.createTempDirectory("ocnotes-fresh").toFile()
        try {
            val registry = AccountRegistry(root)

            assertTrue(root.resolve("accounts.json").isFile)
            assertTrue(registry.profileDir().isDirectory)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `une authentification achevée met à jour le registre persistant`() = runBlocking {
        val root = Files.createTempDirectory("ocnotes-identity").toFile()
        try {
            val registry = AccountRegistry(root)
            registry.recordAuthenticated(
                registry.active.id, "https://cloud.test", "subject-1", "oidc", "identity-key",
            )

            val reopened = AccountRegistry(root)
            assertEquals(registry.active.id, reopened.active.id)
            assertEquals("https://cloud.test", reopened.active.serverUrl)
            assertEquals("subject-1", reopened.active.username)
            assertEquals("oidc", reopened.active.authMode)
            assertEquals("identity-key", reopened.active.identityKey)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `plusieurs profils sont conservés et peuvent etre actives`() = runBlocking {
        val root = Files.createTempDirectory("ocnotes-multi").toFile()
        try {
            val registry = AccountRegistry(root)
            val first = registry.active
            registry.recordAuthenticated(
                first.id,
                "https://premier.test",
                "alice",
                "app_token",
                "identity-alice",
            )

            val second = registry.createAndActivate()
            registry.recordAuthenticated(
                second.id,
                "https://second.test",
                "bob",
                "oidc",
                "identity-bob",
            )
            registry.recordAuthenticated(
                first.id,
                "https://premier.test",
                "alice-modifiee",
                "app_token",
                "identity-alice",
            )

            assertEquals(2, registry.accounts.size)
            assertEquals(second.id, registry.active.id)
            assertEquals("alice-modifiee", registry.accounts.first { it.id == first.id }.username)
            assertTrue(registry.profileDir(first.id).isDirectory)
            assertTrue(registry.profileDir(second.id).isDirectory)

            registry.activate(first.id)
            assertEquals("alice-modifiee", registry.active.username)
            assertEquals("bob", registry.accounts.first { it.id == second.id }.username)

            val reopened = AccountRegistry(root)
            assertEquals(first.id, reopened.active.id)
            assertEquals(2, reopened.accounts.size)
            assertEquals(
                setOf("identity-alice", "identity-bob"),
                reopened.accounts.map { it.identityKey }.toSet(),
            )
        } finally {
            root.deleteRecursively()
        }
    }

    @Test(expected = IllegalArgumentException::class)
    fun `un profil inconnu ne peut pas devenir actif`() = runBlocking {
        val root = Files.createTempDirectory("ocnotes-inconnu").toFile()
        try {
            AccountRegistry(root).activate("0f8fad5b-d9cb-469f-a165-70867728950e")
            Unit
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `la reconstruction retrouve tous les dossiers de profils`() = runBlocking {
        val root = Files.createTempDirectory("ocnotes-rebuild-multi").toFile()
        try {
            val registry = AccountRegistry(root)
            val first = registry.active
            val second = registry.createAndActivate()
            registry.profileDir(first.id).resolve("config.json").writeText(
                """{"version":2,"mode":"server","serverUrl":"https://one.test","username":"alice"}""",
            )
            registry.profileDir(second.id).resolve("config.json").writeText(
                """{"version":2,"mode":"server","serverUrl":"https://two.test","username":"bob"}""",
            )
            root.resolve("accounts.json").writeText("{illisible")

            val rebuilt = AccountRegistry(root)

            assertEquals(setOf(first.id, second.id), rebuilt.accounts.map { it.id }.toSet())
            assertEquals(setOf("alice", "bob"), rebuilt.accounts.map { it.username }.toSet())
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `supprimer le profil actif conserve les autres comptes`() = runBlocking {
        val root = Files.createTempDirectory("ocnotes-remove").toFile()
        try {
            val registry = AccountRegistry(root)
            val first = registry.active
            val second = registry.createAndActivate()

            val next = registry.remove(second.id)

            assertEquals(first.id, next.id)
            assertEquals(first.id, registry.active.id)
            assertEquals(listOf(first.id), registry.accounts.map { it.id })
            assertEquals(first.id, AccountRegistry(root).active.id)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `supprimer le dernier profil cree un profil vierge de remplacement`() = runBlocking {
        val root = Files.createTempDirectory("ocnotes-remove-last").toFile()
        try {
            val registry = AccountRegistry(root)
            val removed = registry.active

            val replacement = registry.remove(removed.id)

            assertTrue(replacement.id != removed.id)
            assertEquals("", replacement.serverUrl)
            assertEquals(listOf(replacement.id), registry.accounts.map { it.id })
            assertTrue(registry.profileDir(replacement.id).isDirectory)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `supprimer un profil inactif ne change pas le compte courant`() = runBlocking {
        val root = Files.createTempDirectory("ocnotes-remove-inactive").toFile()
        try {
            val registry = AccountRegistry(root)
            val first = registry.active
            val second = registry.createAndActivate()
            registry.activate(first.id)

            val current = registry.remove(second.id)

            assertEquals(first.id, current.id)
            assertEquals(first.id, registry.active.id)
            assertEquals(listOf(first.id), registry.accounts.map { it.id })
        } finally {
            root.deleteRecursively()
        }
    }

    // Le subject OIDC est un identifiant opaque : le tiroir montre le nom
    // affiché, qui ne doit ni se perdre quand le serveur ne le fournit pas, ni
    // survivre au compte.
    @Test
    fun `le nom affiché se conserve puis disparaît avec le compte`() = runBlocking {
        val root = Files.createTempDirectory("ocnotes-nom").toFile()
        try {
            val registry = AccountRegistry(root)
            val id = registry.active.id
            registry.recordAuthenticated(id, "https://cloud.test", "a1b2", "oidc", "cle", displayName = "Élodie")
            registry.recordAuthenticated(id, "https://cloud.test", "a1b2", "oidc", "cle")

            assertEquals("Élodie", AccountRegistry(root).active.displayName)

            registry.recordDisconnected(id)
            assertEquals("", AccountRegistry(root).active.displayName)
        } finally {
            root.deleteRecursively()
        }
    }

    // Le registre est lu dans Application.onCreate : une exception y ferait une
    // boucle de plantage dont on ne sort qu'en effaçant les données — file
    // d'attente hors ligne comprise.
    @Test
    fun `un registre illisible est reconstruit depuis le dossier du profil`() {
        for (contenu in listOf("{", """{"version":99,"activeAccountId":"x","accounts":[]}""")) {
            val root = Files.createTempDirectory("ocnotes-illisible").toFile()
            try {
                val origine = AccountRegistry(root)
                origine.profileDir().resolve("config.json").writeText(
                    """{"version":2,"mode":"server","serverUrl":"https://cloud.test","username":"alice"}""",
                )
                origine.profileDir().resolve("cache").mkdirs()
                root.resolve("accounts.json").writeText(contenu)

                val reprise = AccountRegistry(root)

                assertEquals(contenu, origine.active.id, reprise.active.id)
                assertEquals("https://cloud.test", reprise.active.serverUrl)
                assertEquals("alice", reprise.active.username)
                assertTrue(reprise.profileDir().resolve("cache").isDirectory)
                assertEquals(contenu, root.resolve("accounts.json.illisible").readText())
                assertEquals(reprise.active.id, AccountRegistry(root).active.id)
            } finally {
                root.deleteRecursively()
            }
        }
    }

    // Le nettoyage des travaux WorkManager sans UUID n'a lieu qu'une fois.
    @Test
    fun `seul le lancement qui crée le registre signale une migration`() {
        val root = Files.createTempDirectory("ocnotes-une-fois").toFile()
        try {
            assertTrue(AccountRegistry(root).vientDeMigrer)
            assertFalse(AccountRegistry(root).vientDeMigrer)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `un registre illisible sans profil donne un profil neuf`() {
        val root = Files.createTempDirectory("ocnotes-illisible-vide").toFile()
        try {
            root.resolve("accounts.json").writeText("pas du json")

            val registry = AccountRegistry(root)

            assertTrue(registry.profileDir().isDirectory)
            assertEquals(registry.active.id, AccountRegistry(root).active.id)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `une migration interrompue au marqueur illisible reprend quand même`() {
        val root = Files.createTempDirectory("ocnotes-marqueur").toFile()
        try {
            root.resolve("config.json").writeText(
                """{"version":2,"mode":"server","serverUrl":"https://cloud.test","username":"alice"}""",
            )
            val id = "0f8fad5b-d9cb-469f-a165-70867728950e"
            root.resolve("accounts/$id").mkdirs()
            root.resolve("accounts/$id/migration.pending").writeText("{tronqu")

            val registry = AccountRegistry(root)

            assertEquals(id, registry.active.id)
            assertEquals("alice", registry.active.username)
            assertTrue(registry.profileDir().resolve("config.json").isFile)
            assertFalse(root.resolve("config.json").exists())
        } finally {
            root.deleteRecursively()
        }
    }
}
