package eu.ocnotes.data

import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.UUID

/** Profil de stockage local, identifié par un UUID sans rapport avec le serveur. */
@Serializable
data class AccountProfile(
    val id: String,
    val kind: String,
    val serverUrl: String = "",
    val username: String = "",
    val authMode: String = "",
    val identityKey: String = "",
    val needsReauthentication: Boolean = false,
)

/**
 * Registre des profils de l'appareil.
 *
 * Cette première version n'en présente encore qu'un à l'interface, mais place
 * déjà sa configuration et son cache derrière une frontière par UUID.
 */
class AccountRegistry(private val filesDir: File) {

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        prettyPrint = true
    }

    @Volatile
    var active: AccountProfile
        private set

    init {
        filesDir.mkdirs()
        active = loadRegistry() ?: migrateOrCreate()
        require(UUID.fromString(active.id).toString() == active.id) { "Identifiant de profil invalide" } // i18n-ok
        profileDir(active.id).mkdirs()
    }

    fun profileDir(id: String = active.id): File {
        require(UUID.fromString(id).toString() == id) { "Identifiant de profil invalide" } // i18n-ok
        val accounts = File(filesDir, ACCOUNTS_DIR)
        val result = File(accounts, id)
        require(result.canonicalFile.parentFile == accounts.canonicalFile) { "Dossier de profil invalide" } // i18n-ok
        return result
    }

    suspend fun recordAuthenticated(
        serverUrl: String,
        username: String,
        authMode: String,
        identityKey: String,
    ) =
        withContext(Dispatchers.IO) {
            updateActive {
                it.copy(
                    kind = KIND_SERVER,
                    serverUrl = serverUrl,
                    username = username,
                    authMode = authMode,
                    identityKey = identityKey,
                    needsReauthentication = false,
                )
            }
        }

    suspend fun recordLocal() = withContext(Dispatchers.IO) {
        updateActive {
            it.copy(
                kind = KIND_LOCAL,
                serverUrl = "",
                username = "",
                authMode = "",
                identityKey = "",
                needsReauthentication = false,
            )
        }
    }

    suspend fun recordDisconnected() = withContext(Dispatchers.IO) {
        updateActive {
            it.copy(
                kind = KIND_SERVER,
                serverUrl = "",
                username = "",
                authMode = "",
                identityKey = "",
                needsReauthentication = false,
            )
        }
    }

    @Synchronized
    private fun updateActive(transform: (AccountProfile) -> AccountProfile) {
        val updated = transform(active)
        writeRegistry(updated)
        active = updated
    }

    private fun loadRegistry(): AccountProfile? {
        val file = File(filesDir, REGISTRY_FILE)
        if (!file.isFile) return null
        val registry = json.decodeFromString<RegistryDocument>(file.readText(Charsets.UTF_8))
        check(registry.version == VERSION) { "Version de registre inconnue" } // i18n-ok
        return registry.accounts.firstOrNull { it.id == registry.activeAccountId }
            ?: error("Le profil actif est absent du registre") // i18n-ok
    }

    private fun migrateOrCreate(): AccountProfile {
        val profile = pendingMigration() ?: legacyProfile()
        migrateLegacyFiles(profile)
        writeRegistry(profile)
        File(profileDir(profile.id), MIGRATION_MARKER).delete()
        return profile
    }

    /** Reprend une migration interrompue avant l'écriture du registre. */
    private fun pendingMigration(): AccountProfile? {
        val accounts = File(filesDir, ACCOUNTS_DIR)
        return accounts.listFiles()
            ?.firstOrNull { File(it, MIGRATION_MARKER).isFile }
            ?.let { directory ->
                json.decodeFromString<AccountProfile>(
                    File(directory, MIGRATION_MARKER).readText(Charsets.UTF_8),
                )
            }
    }

    private fun legacyProfile(): AccountProfile {
        val config = File(filesDir, CONFIG_FILE)
        val metadata = if (config.isFile) runCatching {
            json.parseToJsonElement(config.readText(Charsets.UTF_8)).jsonObject
        }.getOrNull() else null
        fun field(name: String): String = metadata?.get(name)?.jsonPrimitive?.contentOrNull.orEmpty()
        val mode = field("mode")
        val profile = AccountProfile(
            id = UUID.randomUUID().toString(),
            kind = if (mode == KIND_LOCAL) KIND_LOCAL else KIND_SERVER,
            serverUrl = field("serverUrl"),
            username = field("username"),
            authMode = field("authMode"),
            identityKey = field("identityKey"),
            // Le nouveau stockage de secrets est lié à l'UUID. Une ancienne
            // session serveur devra donc être authentifiée de nouveau.
            needsReauthentication = metadata != null && mode != KIND_LOCAL,
        )
        val directory = profileDir(profile.id)
        check(directory.mkdirs() || directory.isDirectory) { "Création du dossier de profil impossible" } // i18n-ok
        writeAtomically(File(directory, MIGRATION_MARKER), json.encodeToString(profile))
        return profile
    }

    private fun migrateLegacyFiles(profile: AccountProfile) {
        val destination = profileDir(profile.id)
        moveIfPresent(File(filesDir, CONFIG_FILE), File(destination, CONFIG_FILE))
        moveIfPresent(File(filesDir, CACHE_DIR), File(destination, CACHE_DIR))
    }

    private fun moveIfPresent(source: File, destination: File) {
        if (!source.exists() || destination.exists()) return
        destination.parentFile?.mkdirs()
        try {
            Files.move(source.toPath(), destination.toPath(), StandardCopyOption.ATOMIC_MOVE)
        } catch (_: Exception) {
            Files.move(source.toPath(), destination.toPath())
        }
    }

    private fun writeRegistry(profile: AccountProfile) {
        val registry = RegistryDocument(
            version = VERSION,
            activeAccountId = profile.id,
            accounts = listOf(profile),
        )
        writeAtomically(File(filesDir, REGISTRY_FILE), json.encodeToString(registry))
    }

    private fun writeAtomically(target: File, content: String) {
        target.parentFile?.mkdirs()
        val temporary = File(target.parentFile, "${target.name}.tmp")
        FileOutputStream(temporary).use { stream ->
            stream.write(content.toByteArray(Charsets.UTF_8))
            stream.fd.sync()
        }
        try {
            Files.move(
                temporary.toPath(), target.toPath(),
                StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING,
            )
        } catch (_: Exception) {
            Files.move(temporary.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
    }

    @Serializable
    private data class RegistryDocument(
        val version: Int,
        val activeAccountId: String,
        val accounts: List<AccountProfile>,
    )

    private companion object {
        const val VERSION = 1
        const val REGISTRY_FILE = "accounts.json"
        const val ACCOUNTS_DIR = "accounts"
        const val MIGRATION_MARKER = "migration.pending"
        const val CONFIG_FILE = "config.json"
        const val CACHE_DIR = "cache"
        const val KIND_LOCAL = "local"
        const val KIND_SERVER = "server"
    }
}
