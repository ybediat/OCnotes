package eu.ocnotes.data

import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
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
    /**
     * Nom lisible du compte. `username` ne convient pas à l'affichage : en
     * OIDC, c'est le subject, un identifiant opaque. Vide tant qu'aucune
     * connexion ne l'a fourni.
     */
    val displayName: String = "",
)

/** Longueur maximale du nom d'un profil local. */
const val MAX_NOM_LOCAL = 40

/** Un profil ne doit réveiller WorkManager que s'il désigne un serveur utilisable. */
internal val AccountProfile.syncEnabled: Boolean
    get() = kind == "server" && serverUrl.isNotBlank()

/**
 * Profil qu'aucune connexion n'a rempli : tout juste ajouté, ou vidé par une
 * déconnexion. Seul un tel profil peut être retiré sans que l'utilisateur l'ait
 * demandé — un profil local porte la seule copie de ses notes.
 */
internal val AccountProfile.vierge: Boolean
    get() = kind == "server" && serverUrl.isBlank() && identityKey.isBlank()

data class AccountRegistryState(
    val active: AccountProfile,
    val accounts: List<AccountProfile>,
)

/**
 * Registre des profils de l'appareil.
 *
 * Chaque compte garde sa configuration et son cache derrière une frontière
 * par UUID. Le registre retient tous les profils et lequel est actif.
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

    @Volatile
    var accounts: List<AccountProfile>
        private set

    private lateinit var mutableState: MutableStateFlow<AccountRegistryState>
    val state: StateFlow<AccountRegistryState> get() = mutableState.asStateFlow()

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
        val registry = loadRegistry() ?: migrateOrCreate()
        active = registry.active
        accounts = registry.accounts
        accounts.forEach { profileDir(it.id).mkdirs() }
        mutableState = MutableStateFlow(AccountRegistryState(active, accounts))
    }

    fun profileDir(id: String = active.id): File {
        require(isProfileId(id)) { "Identifiant de profil invalide" } // i18n-ok
        val accounts = File(filesDir, ACCOUNTS_DIR)
        val result = File(accounts, id)
        require(result.canonicalFile.parentFile == accounts.canonicalFile) { "Dossier de profil invalide" } // i18n-ok
        return result
    }

    suspend fun recordAuthenticated(
        accountId: String,
        serverUrl: String,
        username: String,
        authMode: String,
        identityKey: String,
        displayName: String? = null,
    ) =
        withContext(Dispatchers.IO) {
            updateAccount(accountId) {
                it.copy(
                    kind = KIND_SERVER,
                    serverUrl = serverUrl,
                    username = username,
                    authMode = authMode,
                    identityKey = identityKey,
                    displayName = displayName ?: it.displayName,
                )
            }
        }

    /**
     * Autre profil déjà connecté à la même identité, ou `null`.
     *
     * Une clé vide ne désigne personne : un profil vierge, local ou déconnecté
     * n'a pas d'identité à comparer. La clé est opaque, on ne fait que
     * l'égaler.
     */
    fun profilDeMemeIdentite(identityKey: String, sauf: String): AccountProfile? =
        if (identityKey.isBlank()) null
        else accounts.firstOrNull { it.id != sauf && it.identityKey == identityKey }

    suspend fun recordLocal(accountId: String) = withContext(Dispatchers.IO) {
        updateAccount(accountId) {
            it.copy(
                kind = KIND_LOCAL,
                serverUrl = "",
                username = "",
                authMode = "",
                identityKey = "",
                // Appelé à chaque démarrage local : le nom choisi par
                // l'utilisateur ne doit pas s'y perdre. Un profil qui
                // devient local (débranchement) repart sans nom.
                displayName = if (it.kind == KIND_LOCAL) it.displayName else "",
            )
        }
    }

    /**
     * Nomme un profil local. Libellé Android pur, jamais transmis au cœur Go ;
     * vide, le tiroir retombe sur « Notes locales ». Sans effet sur un profil
     * serveur, dont le nom vient de la session.
     */
    suspend fun renommerLocal(accountId: String, nom: String) = withContext(Dispatchers.IO) {
        updateAccount(accountId) {
            if (it.kind == KIND_LOCAL) it.copy(displayName = nom.trim().take(MAX_NOM_LOCAL)) else it
        }
    }

    suspend fun recordDisconnected(accountId: String) = withContext(Dispatchers.IO) {
        updateAccount(accountId) {
            it.copy(
                kind = KIND_SERVER,
                serverUrl = "",
                username = "",
                authMode = "",
                identityKey = "",
                displayName = "",
            )
        }
    }

    /** Crée un profil vierge et le rend actif sans toucher aux profils existants. */
    suspend fun createAndActivate(): AccountProfile = withContext(Dispatchers.IO) {
        createAndActivateLocked()
    }

    /** Rend actif un profil existant. Ses fichiers et son secret restent intacts. */
    suspend fun activate(id: String): AccountProfile = withContext(Dispatchers.IO) {
        activateLocked(id)
    }

    /**
     * Retire un profil du registre et renvoie le profil qui doit rester actif.
     * Si c'était le dernier, un profil vierge est créé : l'application garde
     * toujours un emplacement valide vers lequel revenir.
     */
    suspend fun remove(id: String): AccountProfile = withContext(Dispatchers.IO) {
        removeLocked(id)
    }

    @Synchronized
    private fun updateAccount(id: String, transform: (AccountProfile) -> AccountProfile) {
        val current = accounts.firstOrNull { it.id == id }
            ?: throw IllegalArgumentException("Profil inconnu") // i18n-ok
        val updated = transform(current)
        check(updated.id == id) { "L'identifiant du profil ne peut pas changer" } // i18n-ok
        // Rien à écrire : la revalidation de chaque démarrage repasse ici.
        if (updated == current) return
        val updatedAccounts = accounts.map { if (it.id == id) updated else it }
        writeRegistry(active.id, updatedAccounts)
        accounts = updatedAccounts
        if (active.id == id) active = updated
        publishState()
    }

    @Synchronized
    private fun createAndActivateLocked(): AccountProfile {
        val profile = AccountProfile(id = UUID.randomUUID().toString(), kind = KIND_SERVER)
        val directory = profileDir(profile.id)
        check(directory.mkdirs()) { "Création du dossier de profil impossible" } // i18n-ok
        val updatedAccounts = accounts + profile
        try {
            writeRegistry(profile.id, updatedAccounts)
        } catch (error: Exception) {
            // Le dossier est encore vide : ne pas laisser un faux profil que
            // la reconstruction prendrait pour un compte réel.
            directory.delete()
            throw error
        }
        accounts = updatedAccounts
        active = profile
        publishState()
        return profile
    }

    @Synchronized
    private fun activateLocked(id: String): AccountProfile {
        require(isProfileId(id)) { "Identifiant de profil invalide" } // i18n-ok
        val profile = accounts.firstOrNull { it.id == id }
            ?: throw IllegalArgumentException("Profil inconnu") // i18n-ok
        if (profile.id == active.id) return profile
        check(profileDir(profile.id).isDirectory) { "Dossier de profil absent" } // i18n-ok
        writeRegistry(profile.id, accounts)
        active = profile
        publishState()
        return profile
    }

    @Synchronized
    private fun removeLocked(id: String): AccountProfile {
        require(isProfileId(id)) { "Identifiant de profil invalide" } // i18n-ok
        require(accounts.any { it.id == id }) { "Profil inconnu" } // i18n-ok

        val remaining = accounts.filterNot { it.id == id }.toMutableList()
        var replacementDirectory: File? = null
        if (remaining.isEmpty()) {
            val replacement = AccountProfile(UUID.randomUUID().toString(), KIND_SERVER)
            val directory = profileDir(replacement.id)
            check(directory.mkdirs()) { "Création du dossier de profil impossible" } // i18n-ok
            replacementDirectory = directory
            remaining += replacement
        }
        val nextActive = if (active.id == id) remaining.first() else active
        try {
            writeRegistry(nextActive.id, remaining)
        } catch (error: Exception) {
            replacementDirectory?.delete()
            throw error
        }
        accounts = remaining.toList()
        active = nextActive
        publishState()
        return nextActive
    }

    private fun publishState() {
        if (::mutableState.isInitialized) {
            mutableState.value = AccountRegistryState(active, accounts)
        }
    }

    /**
     * Le profil actif, ou `null` si le registre n'existe pas encore.
     *
     * Ce code tourne dans `Application.onCreate` : une exception y ferait une
     * boucle de plantage dont on ne sort qu'en effaçant les données de
     * l'application, file d'attente hors ligne comprise. Un registre présent
     * mais illisible est donc reconstruit, jamais fatal.
     */
    private fun loadRegistry(): LoadedRegistry? {
        val file = File(filesDir, REGISTRY_FILE)
        if (!file.isFile) return null
        return readRegistry(file) ?: rebuildRegistry(file)
    }

    private fun readRegistry(file: File): LoadedRegistry? = runCatching {
        val registry = json.decodeFromString<RegistryDocument>(file.readText(Charsets.UTF_8))
        val ids = registry.accounts.map { it.id }
        val valid = registry.version == VERSION &&
            registry.accounts.isNotEmpty() &&
            ids.distinct().size == ids.size &&
            ids.all(::isProfileId)
        if (!valid) return@runCatching null
        val active = registry.accounts.firstOrNull { it.id == registry.activeAccountId }
            ?: return@runCatching null
        LoadedRegistry(active, registry.accounts)
    }.getOrNull()

    /**
     * Reconstruit le registre depuis les dossiers de profil.
     *
     * Le dossier porte l'UUID, donc aussi le secret et les travaux qui en
     * dépendent : le reprendre tel quel garde la session. L'original est mis de
     * côté plutôt qu'écrasé — un registre d'une version future reste ainsi
     * récupérable après un retour en arrière.
     */
    private fun rebuildRegistry(file: File): LoadedRegistry {
        runCatching { file.copyTo(File(filesDir, "$REGISTRY_FILE$UNREADABLE_SUFFIX"), overwrite = true) }
        val directories = File(filesDir, ACCOUNTS_DIR).listFiles()
            ?.filter { it.isDirectory && isProfileId(it.name) }
            ?.takeIf { it.isNotEmpty() }
            ?: return migrateOrCreate()
        val profiles = directories.map { profileFromConfig(it.name, File(it, CONFIG_FILE)) }
        val activeDirectory = directories.maxByOrNull { File(it, CONFIG_FILE).lastModified() }
            ?: return migrateOrCreate()
        val active = profiles.first { it.id == activeDirectory.name }
        migrateLegacyFiles(active)
        // Un échec d'écriture n'empêche pas de démarrer : le profil est connu,
        // et la reconstruction se refera au lancement suivant.
        runCatching { writeRegistry(active.id, profiles) }
        return LoadedRegistry(active, profiles)
    }

    private fun migrateOrCreate(): LoadedRegistry {
        val profile = pendingMigration() ?: legacyProfile()
        migrateLegacyFiles(profile)
        writeRegistry(profile.id, listOf(profile))
        File(profileDir(profile.id), MIGRATION_MARKER).delete()
        vientDeMigrer = true
        return LoadedRegistry(profile, listOf(profile))
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

    private fun writeRegistry(activeAccountId: String, accounts: List<AccountProfile>) {
        val registry = RegistryDocument(
            version = VERSION,
            activeAccountId = activeAccountId,
            accounts = accounts,
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

    private data class LoadedRegistry(
        val active: AccountProfile,
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
