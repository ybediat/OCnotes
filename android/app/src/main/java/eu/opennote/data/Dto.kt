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

/**
 * Un dossier proposé comme destination, tel que `App.foldersJSON()` le rend.
 *
 * Le dossier de notes lui-même a un `path` vide et un `name` vide : la façade
 * ne choisit pas son libellé, parce que l'interface l'affiche déjà en titre.
 */
@Serializable
data class FolderRefDto(
    val path: String = "",
    val name: String = "",
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
    val operation: String = "write",
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

/**
 * Une portion mise en forme dans le texte d'un [NoteBlockDto].
 *
 * [start] et [end] sont en **unités de code UTF-16**, comme partout ailleurs à
 * la frontière : ce sont exactement les indices de `String` en Kotlin, donc ils
 * se posent tels quels dans un `AnnotatedString`. Aucune conversion, dans
 * aucun sens.
 */
@Serializable
data class NoteSpanDto(
    val start: Int = 0,
    val end: Int = 0,
    val style: String = "",
    /** Destination, uniquement pour [SpanStyleId.LIEN]. */
    val href: String = "",
)

/**
 * Un bloc d'affichage de l'aperçu, tel que renvoyé par `renderNoteJSON`.
 *
 * Le modèle est **plat** : l'imbrication tient dans [depth] (listes) et
 * [quote] (citations). Il n'y a pas d'arbre à descendre pour dessiner une
 * liste, et les champs qui ne servent pas au [kind] du bloc restent à zéro.
 */
@Serializable
data class NoteBlockDto(
    val kind: String = "",
    val text: String = "",
    val spans: List<NoteSpanDto> = emptyList(),
    /** Titre : 1 à 6. */
    val level: Int = 0,
    /** Imbrication de liste, 0 au premier niveau. */
    val depth: Int = 0,
    /** Imbrication de citation, 0 hors citation. */
    val quote: Int = 0,
    /** Liste numérotée : le numéro à **afficher**, pas le rang. */
    val number: Int = 0,
    val checked: Boolean = false,
    /** Bloc de code : le langage annoncé, s'il y en a un. */
    val lang: String = "",
    val cells: List<String> = emptyList(),
    /** Ligne de tableau : c'est l'en-tête. */
    val header: Boolean = false,
)

/** Valeurs du champ `kind` d'un [NoteBlockDto]. */
object BlockKind {
    const val PARAGRAPHE = "paragraph"
    const val TITRE = "heading"
    const val PUCE = "bullet"
    const val NUMEROTE = "ordered"
    const val TACHE = "task"
    const val CODE = "code"
    const val TRAIT = "rule"
    const val IMAGE = "image"
    const val LIGNE_TABLEAU = "tablerow"

    /** Fichier non interprété — un .txt — rendu tel quel, en un seul bloc. */
    const val BRUT = "plain"
}

/** Valeurs du champ `style` d'un [NoteSpanDto]. */
object SpanStyleId {
    const val GRAS = "bold"
    const val ITALIQUE = "italic"
    const val BARRE = "strike"
    const val CODE = "code"
    const val LIEN = "link"

    /**
     * Souligné.
     *
     * Absent du Markdown, courant dans un traitement de texte : il ne vient
     * que des `.docx` et `.odt`, jamais d'une note. Une note ne peut donc pas
     * en produire, et le supprimer ici ne casserait rien de visible — jusqu'au
     * premier document ouvert.
     */
    const val SOULIGNE = "underline"
}

/**
 * Réponse de `App.prepareEditJSON(...)` : une note prête pour le champ de
 * saisie.
 *
 * [text] porte les données en ligne remplacées par des jetons courts, et
 * [images] ce qui en a été retiré. **Les deux vont ensemble** : enregistrer
 * [text] sans repasser par `restoreImages` écrirait le texte allégé sur le
 * serveur, et l'image serait perdue dans la vraie note, sans message.
 *
 * [editable] reste faux quand le texte allégé porte encore un mot démesuré —
 * un fichier qui n'a rien à voir avec une image. La note s'ouvre alors en
 * aperçu seul : le champ de saisie ne survivrait pas à sa mise en page.
 */
@Serializable
data class PreparedEditDto(
    val text: String = "",
    val images: List<String> = emptyList(),
    val editable: Boolean = true,
    /** Plus longue suite de caractères sans espace, en unités UTF-16. */
    val longestWord: Int = 0,
)
