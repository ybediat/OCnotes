package eu.opennote.data

import mobile.Mobile

/**
 * Catégorie d'erreur remontée par la façade Go.
 *
 * `gomobile` ne transmet qu'un message : l'erreur typée ne franchit pas la
 * frontière. Le client Go préfixe donc ses messages par une étiquette entre
 * crochets (`opencloud: [AUTH] …`), et la façade expose des prédicats pour la
 * lire. **On ne cherche jamais de texte français dans le message.**
 */
enum class ErrorCategory {
    /** Token invalide ou expiré : redemander la saisie, ne pas réessayer. */
    AUTH,

    /** Le serveur a une version plus récente. */
    CONFLICT,

    /** Note ou dossier absent. */
    NOT_FOUND,

    /** Le serveur n'a pas pu être joint : la requête n'a jamais abouti. */
    OFFLINE,

    /** Autre erreur serveur. */
    HTTP,

    /** Erreur du cœur : validation de nom, cache, état local. */
    LOCAL,
}

/**
 * Erreur normalisée de la couche Go, telle que l'interface la manipule.
 *
 * [rawMessage] garde le message d'origine, utile en journalisation et comme
 * repli d'affichage ; [category] pilote la réaction de l'interface ; [code]
 * porte l'étiquette exacte, plus fine que la catégorie, qui sert à choisir la
 * formulation.
 */
class OpenNoteException(
    val category: ErrorCategory,
    val code: String,
    val rawMessage: String,
    cause: Throwable? = null,
) : Exception(rawMessage, cause) {

    val isAuth: Boolean get() = category == ErrorCategory.AUTH

    /** Vrai quand réessayer plus tard a une chance d'aboutir. */
    val isRetryable: Boolean
        get() = category == ErrorCategory.HTTP || category == ErrorCategory.OFFLINE

    companion object {
        /**
         * Classe une exception venue du binding gomobile.
         *
         * Une exception déjà normalisée est renvoyée telle quelle, pour que
         * l'appel puisse être encapsulé sans perdre la catégorie.
         */
        fun from(t: Throwable): OpenNoteException {
            if (t is OpenNoteException) return t
            val message = t.message.orEmpty()
            val code = Mobile.errorCode(message)
            return OpenNoteException(categorieDuCode(code), code, message, t)
        }
    }
}

/**
 * Traduit un code d'étiquette en catégorie.
 *
 * Sert au champ `errorCode` de `SyncJSON`, où l'incident arrive dans une
 * réponse réussie plutôt que dans une exception. Tout ce qui n'est pas un code
 * de transport est local : la façade renvoie aussi les étiquettes du cœur
 * (`NAME_TOO_LONG`, `STORAGE_IO`…), qui n'ont pas de catégorie propre.
 */
fun categorieDuCode(code: String): ErrorCategory = when (code) {
    "AUTH" -> ErrorCategory.AUTH
    "CONFLICT" -> ErrorCategory.CONFLICT
    "NOTFOUND" -> ErrorCategory.NOT_FOUND
    "OFFLINE" -> ErrorCategory.OFFLINE
    "HTTP" -> ErrorCategory.HTTP
    else -> ErrorCategory.LOCAL
}
