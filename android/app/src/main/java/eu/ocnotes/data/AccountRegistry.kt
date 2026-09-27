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

    /**
     * Vrai si ce processus a créé le registre : première installation, ou
     * passage depuis le schéma sans profils. C'est le seul lancement où les
     * restes de l'ancien schéma — travaux WorkManager sans UUID — sont à
     * nettoyer.
     */
    var vientDeMigrer: Boolean = false
        private set

    init {
        filesDir.mkdirs()
        active = loadRegistry() ?: migrateOrCreate()
        profileDir(active.id).mkdirs()
    }

    fun profileDir(id: String = active.id): File {
        require(isProfileId(id)) { "Identifiant de profil invalide" } // i18n-ok
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
            )
        }
    }

    @Synchronized
    private fun updateActive(transform: (AccountProfile) -> AccountProfile) {
        val updated = transform(active)
        writeRegistry(updated)
        active = updated
    }

    /**
     * Le profil actif, ou `null` si le registre n'existe pas encore.
     *
     * Ce code tourne dans `Application.onCreate` : une exception y ferait une
     * boucle de plantage dont on ne sort qu'en effaçant les données de
     * l'application, file d'attente hors ligne comprise. Un registre présent
     * mais illisible est donc reconstruit, jamais fatal.
     */
    private fun loadRegistry(): AccountProfile? {
        val file = File(filesDir, REGISTRY_FILE)
        if (!file.isFile) return null
        return readRegistry(file) ?: rebuildRegistry(file)
    }

    private fun readRegistry(file: File): AccountProfile? = runCatching {
        val registry = json.decodeFromString<RegistryDocument>(file.readText(Charsets.UTF_8))
        registry.accounts
            .takeIf { registry.version == VERSION }
            ?.firstOrNull { it.id == registry.activeAccountId && isProfileId(it.id) }
    }.getOrNull()

    /**
     * Reconstruit le registre depuis les dossiers de profil.
     *
     * Le dossier porte l'UUID, donc aussi le secret et les travaux qui en
     * dépendent : le reprendre tel quel garde la session. L'original est mis de
     * côté plutôt qu'écrasé — un registre d'une version future reste ainsi
     * récupérable après un retour en arrière.
     */
    private fun rebuildRegistry(file: File): AccountProfile {
        runCatching { file.copyTo(File(filesDir, "$REGISTRY_FILE$UNREADABLE_SUFFIX"), overwrite = true) }
        val directory = File(filesDir, ACCOUNTS_DIR).listFiles()
            ?.filter { it.isDirectory && isProfileId(it.name) }
            ?.maxByOrNull { File(it, CONFIG_FILE).lastModified() }
            ?: return migrateOrCreate()
        val profile = profileFromConfig(directory.name, File(directory, CONFIG_FILE))
        migrateLegacyFiles(profile)
        // Un échec d'écriture n'empêche pas de démarrer : le profil est connu,
        // et la reconstruction se refera au lancement suivant.
        runCatching { writeRegistry(profile) }
        return profile
    }

    private fun migrateOrCreate(): AccountProfile {
        val profile = pendingMigration() ?: legacyProfile()
        migrateLegacyFiles(profile)
        writeRegistry(profile)
        File(profileDir(profile.id), MIGRATION_MARKER).delete()
        vientDeMigrer = true
        return profile
    }

    /**
     * Reprend une migration interrompue avant l'écriture du registre. Un
     * marqueur tronqué ne perd que ses métadonnées : l'UUID est le nom du
     * dossier, et la configuration dit le reste.
     */
    private fun pendingMigration(): AccountProfile? {
        val directory = File(filesDir, ACCOUNTS_DIR).listFiles()
            ?.firstOrNull { isProfileId(it.name) && File(it, MIGRATION_MARKER).isFile }
            ?: return null
        return runCatching {
            json.decodeFromString<AccountProfile>(File(directory, MIGRATION_MARKER).readText(Charsets.UTF_8))
        }.getOrNull()?.takeIf { it.id == directory.name }
            ?: profileFromConfig(
                directory.name,
                File(filesDir, CONFIG_FILE).takeIf { it.isFile } ?: File(directory, CONFIG_FILE),
            )
    }

    private fun legacyProfile(): AccountProfile {
        val profile = profileFromConfig(UUID.randomUUID().toString(), File(filesDir, CONFIG_FILE))
        val directory = profileDir(profile.id)
        check(directory.mkdirs() || directory.isDirectory) { "Création du dossier de profil impossible" } // i18n-ok
        writeAtomically(File(directory, MIGRATION_MARKER), json.encodeToString(profile))
        return profile
    }

    /** Profil décrit par un `config.json` du cœur Go ; illisible, il reste vierge. */
    private fun profileFromConfig(id: String, config: File): AccountProfile {
        val metadata = if (config.isFile) runCatching {
            json.parseToJsonElement(config.readText(Charsets.UTF_8)).jsonObject
        }.getOrNull() else null
        fun field(name: String): String = metadata?.get(name)?.jsonPrimitive?.contentOrNull.orEmpty()
        return AccountProfile(
            id = id,
            kind = if (field("mode") == KIND_LOCAL) KIND_LOCAL else KIND_SERVER,
            serverUrl = field("serverUrl"),
            username = field("username"),
            authMode = field("authMode"),
            identityKey = field("identityKey"),
        )
    }

    private fun isProfileId(id: String): Boolean =
        runCatching { UUID.fromString(id).toString() == id }.getOrDefault(false)

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
        const val UNREADABLE_SUFFIX = ".illisible"
        const val ACCOUNTS_DIR = "accounts"
        const val MIGRATION_MARKER = "migration.pending"
        const val CONFIG_FILE = "config.json"
        const val CACHE_DIR = "cache"
        const val KIND_LOCAL = "local"
        const val KIND_SERVER = "server"
    }
}
