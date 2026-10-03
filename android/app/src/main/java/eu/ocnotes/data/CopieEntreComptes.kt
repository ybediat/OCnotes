package eu.ocnotes.data

/** Un profil où l'on peut copier une note, avec les dossiers qui l'acceptent. */
data class DestinationCopie(
    val profil: AccountProfile,
    val dossiers: List<FolderRefDto>,
)

/**
 * Bilan d'une copie vers un autre profil.
 *
 * [premiereErreur] n'est posée que pour une erreur du cœur Go ; une copie relue
 * qui diffère de la source compte comme un échec sans erreur. [cibleDisparue] :
 * le profil cible a été supprimé avant la copie, rien n'a été tenté.
 */
data class ResultatCopie(
    val copiees: Int,
    val echecs: Int,
    val premiereErreur: OCnotesException?,
    val cibleDisparue: Boolean = false,
)
