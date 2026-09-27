package eu.ocnotes.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files
import kotlinx.coroutines.runBlocking

class AccountRegistryTest {

    @Test
    fun `une installation serveur conserve son cache et exige une reconnexion`() {
        val root = Files.createTempDirectory("ocnotes-accounts").toFile()
        try {
            root.resolve("config.json").writeText(
                """{"version":2,"mode":"server","serverUrl":"https://cloud.test","username":"alice"}""",
            )
            root.resolve("cache").mkdirs()
            root.resolve("cache/index.json").writeText("file-persistante")

            val registry = AccountRegistry(root)
            val profile = registry.active

            assertTrue(profile.needsReauthentication)
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
            assertFalse(registry.active.needsReauthentication)
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
            assertFalse(registry.active.needsReauthentication)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `une authentification achevée met à jour le registre persistant`() = runBlocking {
        val root = Files.createTempDirectory("ocnotes-identity").toFile()
        try {
            val registry = AccountRegistry(root)
            registry.recordAuthenticated("https://cloud.test", "subject-1", "oidc", "identity-key")

            val reopened = AccountRegistry(root)
            assertEquals(registry.active.id, reopened.active.id)
            assertEquals("https://cloud.test", reopened.active.serverUrl)
            assertEquals("subject-1", reopened.active.username)
            assertEquals("oidc", reopened.active.authMode)
            assertEquals("identity-key", reopened.active.identityKey)
            assertFalse(reopened.active.needsReauthentication)
        } finally {
            root.deleteRecursively()
        }
    }
}
