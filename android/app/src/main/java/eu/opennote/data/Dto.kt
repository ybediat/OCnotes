package eu.opennote.data

import kotlinx.serialization.Serializable

/**
 * Miroirs Kotlin des formats JSON de `docs/FACADE.md`.
 *
 * **Tout champ a une valeur par défaut**, pour que le contrat puisse gagner un
 * champ sans casser cette couche ; `ignoreUnknownKeys` gère le sens inverse.
 *
 * Les listes, elles, ne sont pas nullables : la façade garantit `entries` et
 * `conflicts` comme tableaux, jamais `null`, et un test Go le vérifie. Kotlin
 * n'a donc qu'une seule forme à gérer pour un dossier vide.
 */

/** Réponse de `App.stateJSON()`. */
@Serializable
data class AppStateDto(
    val connected: Boolean = false,
    val hasWorkspace: Boolean = false,
    val serverUrl: String = "",
    val username: String = "",
    val driveId: String = "",
    val driveName: String = "",
    val root: String = "",
    val lastPath: String = "",
    val pending: Int = 0,
)

/** Un élément de `App.listDrivesJSON()`. */
@Serializable
data class DriveDto(
    val id: String = "",
    val name: String = "",
    val type: String = "",
    /**
     * Faux pour l'agrégat virtuel « Shares ». L'entrée est renvoyée quand
     * même : elle s'affiche grisée avec une explication, elle ne disparaît
     * pas.
     */
    val usable: Boolean = false,
    val selected: Boolean = false,
)

/** Une ligne du navigateur. */
@Serializable
data class FolderEntryDto(
    val path: String = "",
    val name: String = "",
    /** Nom sans extension, à afficher tel quel. */
    val display: String = "",
    val isDir: Boolean = false,
    val size: Long = 0,
    /** RFC 3339 UTC, ou chaîne vide pour un dossier. */
    val modTime: String = "",
    /** Modification locale pas encore poussée : mérite une pastille. */
    val pending: Boolean = false,
)

/** Réponse de `App.listFolderJSON(dir)`. */
@Serializable
data class FolderListingDto(
    val path: String = "",
    val entries: List<FolderEntryDto> = emptyList(),
    /** Vrai quand le réseau manquait : la vue peut être incomplète. */
    val fromCache: Boolean = false,
)

/** Réponse de `App.createNoteJSON(...)` et `App.createFolderJSON(...)`. */
@Serializable
data class NoteRefDto(
    val path: String = "",
    val name: String = "",
    val display: String = "",
)

/** Une note dont la version locale a été mise de côté. */
@Serializable
data class ConflictDto(
    val path: String = "",
    val copyPath: String = "",
)

/** Réponse de `App.syncJSON()`. */
@Serializable
data class SyncResultDto(
    val pushed: Int = 0,
    val deleted: Int = 0,
    val moved: Int = 0,
    val conflicts: List<ConflictDto> = emptyList(),
    val remaining: Int = 0,
    /**
     * Message d'une panne réseau. Ce n'est **pas** un échec d'appel : ce qui a
     * été poussé l'est, le reste attend.
     */
    val error: String = "",
    /**
     * Catégorie de cette erreur, déjà extraite par la façade — inutile
     * d'analyser [error]. Vide quand la passe s'est bien passée.
     */
    val errorCode: String = "",
) {
    val hasError: Boolean get() = error.isNotBlank()

    /** Sur `AUTH`, ne pas replanifier : c'est le token qu'il faut renouveler. */
    val categorieErreur: ErrorCategory get() = categorieDuCode(errorCode)
}

/**
 * Requête de `App.applyFormatJSON(...)`.
 *
 * [start] et [end] sont en **unités de code UTF-16**, exactement comme
 * `TextRange` de Compose. Ils voyagent tels quels, sans la moindre conversion.
 */
@Serializable
data class FormatRequestDto(
    val text: String,
    val start: Int,
    val end: Int,
    val action: String,
)

/** Réponse de `App.applyFormatJSON(...)`, même unités. */
@Serializable
data class FormatResultDto(
    val text: String = "",
    val start: Int = 0,
    val end: Int = 0,
)

/**
 * Identifiant d'action de mise en forme, tel que renvoyé par
 * `App.formatActionsJSON()`. Le type existe pour que le compilateur empêche de
 * confondre une action avec un chemin de note — la liste elle-même n'est
 * jamais codée en dur.
 */
@JvmInline
value class FormatAction(val id: String)

/** Le champ `type` d'un drive, pour l'explication affichée à l'utilisateur. */
object DriveType {
    const val PERSONAL = "personal"
    const val PROJECT = "project"
    const val VIRTUAL = "virtual"
    const val MOUNTPOINT = "mountpoint"
}
