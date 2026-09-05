package eu.ocnotes.ui.common

import eu.ocnotes.R
import eu.ocnotes.data.ErrorCategory
import eu.ocnotes.data.OCnotesException
import mobile.Mobile

/**
 * Ce que l'interface doit dire d'une erreur du cœur.
 *
 * Vit dans `ui/` et non dans `data/` : c'est une décision de formulation, pas
 * une propriété de l'erreur. `data.OCnotesException` reste ainsi une donnée
 * pure — catégorie, code, message brut — que rien n'oblige à connaître `R`.
 *
 * Renvoie un [Texte], pas une chaîne : un ViewModel n'a pas de `Context`, et
 * la langue doit être celle de l'affichage au moment où le message est lu, pas
 * celle du moment où l'erreur est survenue.
 *
 * # Le repli n'est pas un détail
 *
 * Un code sans formulation retombe sur le message Go, débarrassé de son
 * préfixe technique. C'est du français sur un téléphone anglophone — un défaut
 * visible, donc réparable, là où une chaîne vide serait un écran muet. Le cœur
 * peut ainsi étiqueter une nouvelle règle sans que l'interface soit modifiée
 * dans la même passe.
 */
fun OCnotesException.texte(): Texte = when (category) {
    ErrorCategory.AUTH -> Texte.de(R.string.err_auth)
    ErrorCategory.CONFLICT -> Texte.de(R.string.err_conflit)
    ErrorCategory.NOT_FOUND -> Texte.de(R.string.err_introuvable)
    ErrorCategory.OFFLINE -> Texte.de(R.string.err_hors_ligne)
    ErrorCategory.HTTP -> Texte.de(R.string.err_http)
    ErrorCategory.LOCAL -> texteLocal(code) ?: repli(rawMessage)
}

/**
 * Formulation d'une règle du cœur Go, choisie sur son étiquette.
 *
 * Les deux paramètres viennent de la façade et **ne sont pas recopiés** dans
 * `strings.xml` : la borne de longueur et la liste de caractères interdits
 * vivent dans `internal/notes`, et une valeur dupliquée ici divergerait au
 * premier ajustement.
 */
private fun texteLocal(code: String): Texte? = when (code) {
    "NAME_EMPTY" -> Texte.de(R.string.err_nom_vide)
    "NAME_RESERVED" -> Texte.de(R.string.err_nom_reserve)
    "NAME_TOO_LONG" -> Texte.de(R.string.err_nom_trop_long, Mobile.maxNameBytes())
    "NAME_FORBIDDEN_CHARS" ->
        Texte.de(R.string.err_nom_caracteres_interdits, Mobile.forbiddenNameChars())

    "NAME_CONTROL_CHAR" -> Texte.de(R.string.err_nom_caractere_controle)
    "NAME_SPACE_EDGE" -> Texte.de(R.string.err_nom_espaces)
    "NAME_TRAILING_DOT" -> Texte.de(R.string.err_nom_point_final)
    "NAME_LEADING_DOT" -> Texte.de(R.string.err_nom_point_initial)
    "NAME_RESERVED_DEVICE" -> Texte.de(R.string.err_nom_reserve_windows)
    "NAME_NO_SLOT" -> Texte.de(R.string.err_nom_aucun_libre)
    "ROOT_IMMUTABLE" -> Texte.de(R.string.err_racine_protegee)
    "MOVE_INTO_SELF" -> Texte.de(R.string.err_deplacement_dans_soi)
    "STRUCTURAL_OFFLINE_FOLDER" -> Texte.de(R.string.err_dossier_hors_ligne)
    "PATH_EMPTY" -> Texte.de(R.string.err_chemin_vide)
    "READONLY" -> Texte.de(R.string.err_lecture_seule)
    "UNSUPPORTED" -> Texte.de(R.string.err_format_non_pris_en_charge)
    "DOC_INVALID" -> Texte.de(R.string.err_document_invalide)
    "DOC_TOO_LARGE" -> Texte.de(R.string.err_document_trop_volumineux)
    "FILE_TOO_LARGE" -> Texte.de(R.string.err_fichier_trop_volumineux)
    "STORAGE_IO" -> Texte.de(R.string.err_stockage)
    "CONTENT_NOT_CACHED" -> Texte.de(R.string.err_contenu_a_telecharger)
    "CONTENT_MISSING" -> Texte.de(R.string.err_contenu_perdu)
    "SERVER_URL_MISSING" -> Texte.de(R.string.err_url_serveur_manquante)
    "SERVER_URL_INVALID" -> Texte.de(R.string.err_url_serveur_invalide)
    "USERNAME_MISSING" -> Texte.de(R.string.err_utilisateur_manquant)
    "LOCAL_MODE" -> Texte.de(R.string.err_mode_local)
    "QUOTA_TOO_LOW" -> Texte.de(R.string.err_quota_local)
    "PENDING_CHANGES" -> Texte.de(R.string.err_modifications_attente)
    else -> null
}

/**
 * Message Go débarrassé de son préfixe de paquet et de son étiquette.
 *
 * « notes: [NAME_XYZ] le nom … » devient « le nom … ». Un message vide, ou
 * réduit à sa mécanique, laisse place à une phrase générique.
 */
private fun repli(rawMessage: String): Texte {
    val sansEtiquette = rawMessage.substringAfter(']', rawMessage).trim()
    val nu = sansEtiquette.substringAfter(": ", sansEtiquette).trim()
    return if (nu.isBlank()) Texte.de(R.string.err_inconnue) else Texte.brut(nu)
}
