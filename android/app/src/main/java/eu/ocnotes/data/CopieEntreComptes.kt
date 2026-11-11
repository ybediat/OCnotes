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

/**
 * Ce dont la copie a besoin du profil cible : créer, relire, assainir un nom.
 *
 * Interface plutôt que [OCnotesRepository] directement, pour que la logique de
 * [copierNotes] se teste sans cœur Go (`CopieEntreComptesTest`).
 */
internal interface CibleCopie {
    suspend fun createNote(dir: String, name: String, content: String): NoteRefDto
    suspend fun readNote(notePath: String): String
    suspend fun suggestName(title: String): String
}

/** Adaptateur : un dépôt de profil vu comme cible de copie. */
internal fun OCnotesRepository.commeCibleCopie(): CibleCopie = object : CibleCopie {
    override suspend fun createNote(dir: String, name: String, content: String) =
        this@commeCibleCopie.createNote(dir, name, content)
    override suspend fun readNote(notePath: String) = this@commeCibleCopie.readNote(notePath)
    override suspend fun suggestName(title: String) = this@commeCibleCopie.suggestName(title)
}

/**
 * Copie [chemins] (lus par [lireSource]) dans le [dossier] de [cible].
 *
 * Chaque copie est relue dans la cible et comparée au texte source avant
 * d'être comptée ; une copie qui diffère est un échec sans erreur. Une erreur
 * du cœur ([OCnotesException]) compte comme un échec, la première est gardée
 * pour le message. L'annulation n'est jamais avalée.
 *
 * [apresCopie] reçoit le nombre de copies écrites, même si la boucle est
 * interrompue (annulation, erreur inattendue) : une copie déjà écrite doit
 * partir à la synchronisation.
 */
internal suspend fun copierNotes(
    chemins: List<String>,
    dossier: String,
    lireSource: suspend (String) -> String,
    cible: CibleCopie,
    apresCopie: (copiees: Int) -> Unit = {},
): ResultatCopie {
    var copiees = 0
    var echecs = 0
    var premiere: OCnotesException? = null
    try {
        for (chemin in chemins) {
            try {
                val texte = lireSource(chemin)
                // Le nom passe entier, extension comprise : copie, pas conversion.
                val nom = chemin.substringAfterLast('/')
                val copie = creerCopie(cible, dossier, nom, texte)
                if (cible.readNote(copie.path) == texte) copiees++ else echecs++
            } catch (e: kotlin.coroutines.cancellation.CancellationException) {
                throw e
            } catch (e: OCnotesException) {
                echecs++
                if (premiere == null) premiere = e
            }
        }
    } finally {
        apresCopie(copiees)
    }
    return ResultatCopie(copiees, echecs, premiere)
}

/**
 * Crée la copie d'une note dans [cible].
 *
 * Le serveur accepte des noms que la création refuse (`? * : < > |`, point
 * initial…) : une note lisible ne doit pas devenir impossible à copier. Sur
 * un refus de nom, le nom passe par `SuggestName` puis la création est
 * retentée une fois. Jamais avant : `SanitizeName` n'est pas idempotente sur
 * un nom valide (carnet : `EXPORT-ZIP`), elle changerait des noms sains.
 */
private suspend fun creerCopie(
    cible: CibleCopie,
    dossier: String,
    nom: String,
    texte: String,
): NoteRefDto = try {
    cible.createNote(dossier, nom, texte)
} catch (e: OCnotesException) {
    if (!e.code.startsWith("NAME_")) throw e
    val assaini = cible.suggestName(nom)
    if (assaini == nom) throw e
    cible.createNote(dossier, assaini, texte)
}
