package eu.ocnotes.data

import android.content.ContentResolver
import android.net.Uri
import android.provider.DocumentsContract
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException

/** Issue d'un export, hors erreur du cœur (qui part en [OCnotesException]). */
sealed interface ResultatExport {
    data class Termine(val resultat: ExportResultatDto) : ResultatExport

    /** Pas de place pour préparer l'archive ; rien n'a été écrit. */
    data object EspaceInsuffisant : ResultatExport

    /** La copie vers l'emplacement choisi a échoué ; le document a été retiré. */
    data object EchecCopie : ResultatExport
}

/**
 * Exporte les notes locales dans l'archive que l'utilisateur a choisi
 * d'enregistrer.
 *
 * Go écrit l'archive dans [dossier] ; cette classe la copie vers l'[Uri] du
 * sélecteur de documents, puis supprime le temporaire **quoi qu'il arrive**.
 * Elle ne lit jamais l'archive ni ne la journalise : elle contient toutes les
 * notes en clair, et ni son contenu ni son chemin n'ont rien à faire dans un
 * journal.
 *
 * Un document créé par le sélecteur existe déjà, vide, quand on arrive ici : si
 * quoi que ce soit échoue ensuite, il est supprimé au mieux. Une archive
 * tronquée ne doit pas passer pour une sauvegarde.
 */
class ExportLocal(
    private val repository: OCnotesRepository,
    private val dossier: File,
    private val resolver: ContentResolver,
) {

    suspend fun exporter(cible: Uri): ResultatExport = withContext(Dispatchers.IO) {
        var reussi = false
        try {
            // L'export occupe temporairement la place des notes une fois de
            // plus : l'archive ne dépasse jamais l'occupation du stockage.
            val usage = repository.cacheState().usage
            val libre = (dossier.parentFile ?: dossier).usableSpace
            if (libre < usage + MARGE) return@withContext ResultatExport.EspaceInsuffisant

            // Un processus tué en pleine écriture laisse son temporaire.
            dossier.deleteRecursively()
            dossier.mkdirs()
            val temporaire = File(dossier, "sortie.zip")

            val resultat = repository.exportZip(temporaire)

            val sortie = resolver.openOutputStream(cible, "wt")
                ?: return@withContext ResultatExport.EchecCopie
            try {
                sortie.use { out -> temporaire.inputStream().use { it.copyTo(out) } }
            } catch (e: CancellationException) {
                throw e
            } catch (_: IOException) {
                return@withContext ResultatExport.EchecCopie
            } catch (_: SecurityException) {
                return@withContext ResultatExport.EchecCopie
            }

            reussi = true
            ResultatExport.Termine(resultat)
        } finally {
            dossier.deleteRecursively()
            if (!reussi) supprimerDocument(cible)
        }
    }

    private fun supprimerDocument(cible: Uri) {
        try {
            DocumentsContract.deleteDocument(resolver, cible)
        } catch (_: Exception) {
            // Meilleur effort : tous les fournisseurs ne savent pas supprimer.
        }
    }

    private companion object {
        /** Marge au-dessus de l'occupation : index, en-têtes, système. */
        const val MARGE = 16L * 1024 * 1024
    }
}
