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

    /** Autre erreur serveur, y compris une coupure réseau. */
    HTTP,

    /** Message sans étiquette : validation de nom, cache, état local. */
    LOCAL,
}

/**
 * Erreur normalisée de la couche Go, telle que l'interface la manipule.
 *
 * [rawMessage] garde le message d'origine, utile en journalisation et dans un
 * éventuel écran de détail ; [category] pilote la réaction de l'interface.
 */
class OpenNoteException(
    val category: ErrorCategory,
    val rawMessage: String,
    cause: Throwable? = null,
) : Exception(rawMessage, cause) {

    val isAuth: Boolean get() = category == ErrorCategory.AUTH

    /** Vrai quand réessayer plus tard a une chance d'aboutir. */
    val isRetryable: Boolean get() = category == ErrorCategory.HTTP

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
            val category = when {
                Mobile.isAuthError(message) -> ErrorCategory.AUTH
                Mobile.isConflictError(message) -> ErrorCategory.CONFLICT
                Mobile.isNotFoundError(message) -> ErrorCategory.NOT_FOUND
                Mobile.errorCode(message) == "HTTP" -> ErrorCategory.HTTP
                else -> ErrorCategory.LOCAL
            }
            return OpenNoteException(category, message, t)
        }
    }
}

/**
 * Traduit un code de catégorie déjà extrait par la façade.
 *
 * Sert au champ `errorCode` de `SyncJSON`, où l'incident arrive dans une
 * réponse réussie plutôt que dans une exception : rien à analyser, la façade a
 * déjà fait le travail.
 */
fun categorieDuCode(code: String): ErrorCategory = when (code) {
    "AUTH" -> ErrorCategory.AUTH
    "CONFLICT" -> ErrorCategory.CONFLICT
    "NOTFOUND" -> ErrorCategory.NOT_FOUND
    "HTTP" -> ErrorCategory.HTTP
    else -> ErrorCategory.LOCAL
}

/**
 * Message affichable pour l'utilisateur.
 *
 * Les messages locaux (validation de nom, par exemple) sont déjà rédigés en
 * français par le cœur Go et sont plus précis que ce qu'on saurait écrire
 * ici : on les laisse passer. Les autres catégories sont reformulées, parce
 * qu'un `HTTP 502` sur une URL WebDAV n'apprend rien à personne.
 */
fun OpenNoteException.userMessage(): String = when (category) {
    ErrorCategory.AUTH ->
        "Identifiants refusés. Vérifiez le nom d'utilisateur et l'App Token, " +
            "qui a pu expirer."

    ErrorCategory.CONFLICT ->
        "Cette note a été modifiée ailleurs entre-temps."

    ErrorCategory.NOT_FOUND ->
        "Introuvable sur le serveur. La note ou le dossier a peut-être été " +
            "supprimé depuis un autre appareil."

    ErrorCategory.HTTP ->
        "Le serveur est injoignable pour le moment."

    ErrorCategory.LOCAL ->
        rawMessage.substringAfter("mobile: ").ifBlank { "Opération impossible." }
}
