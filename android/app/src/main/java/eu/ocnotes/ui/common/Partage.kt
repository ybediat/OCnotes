package eu.ocnotes.ui.common

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import eu.ocnotes.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Une note à joindre : son nom de fichier réel et son contenu déjà lu.
 *
 * Le contenu vient du cache local (`repository.readNote`), la lecture est donc
 * faite en amont, dans le ViewModel — cette couche ne fait que l'écrire sur
 * disque et lancer le sélecteur d'application.
 */
data class FichierPartage(val nomFichier: String, val contenu: String)

/**
 * Écrit les notes dans `cacheDir/partage/` et ouvre le sélecteur d'application
 * (mail, messagerie, stockage…) avec les fichiers en pièce jointe.
 *
 * Pourquoi recopier : le cache local nomme ses fichiers par une empreinte
 * SHA-256 du chemin — il n'y a pas de `journal.md` sur le disque à désigner. On
 * en recrée un au vrai nom, exposé par le `FileProvider` déclaré au manifeste.
 * Le sous-dossier est vidé à chaque appel : ces copies ne servent que le temps
 * de l'envoi.
 *
 * L'appelant est un `LaunchedEffect`, donc une coroutine : l'écriture disque
 * part sur `Dispatchers.IO`, et `getString` (une ressource, pas un composable)
 * s'y appelle sans risque.
 */
suspend fun partagerFichiers(context: Context, fichiers: List<FichierPartage>) {
    if (fichiers.isEmpty()) return

    val uris = withContext(Dispatchers.IO) {
        val dossier = File(context.cacheDir, "partage").apply {
            deleteRecursively()
            mkdirs()
        }
        fichiers.map { fichier ->
            val cible = File(dossier, nomSur(fichier.nomFichier))
            cible.writeText(fichier.contenu)
            FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", cible)
        }
    }

    val type = typeCommun(fichiers.map { it.nomFichier })
    val intent = if (uris.size == 1) {
        Intent(Intent.ACTION_SEND).apply {
            this.type = type
            putExtra(Intent.EXTRA_STREAM, uris.first())
        }
    } else {
        Intent(Intent.ACTION_SEND_MULTIPLE).apply {
            this.type = type
            putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList<Uri>(uris))
        }
    }
    intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)

    context.startActivity(
        Intent.createChooser(intent, context.getString(R.string.browser_partager_via)).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        },
    )
}

/**
 * Type MIME de la pièce jointe.
 *
 * Un `.md` n'a pas de type universel : les clients mail attendent
 * « text-slash-markdown », « text-slash-plain » pour un `.txt`. Un lot
 * mélangeant les deux retombe sur le type générique du texte — ils n'ont pas
 * de type commun plus précis.
 */
private fun typeCommun(noms: List<String>): String {
    val types = noms.map { if (it.endsWith(".txt")) TYPE_TEXTE_BRUT else TYPE_MARKDOWN }.toSet()
    return types.singleOrNull() ?: TYPE_TEXTE_GENERIQUE
}

private const val TYPE_MARKDOWN = "text/markdown"
private const val TYPE_TEXTE_BRUT = "text/plain"
private const val TYPE_TEXTE_GENERIQUE = "text/" + "*"

/**
 * Le serveur OpenCloud accepte dans un nom des caractères qu'un système de
 * fichiers refuse (`/` surtout). On ne garde qu'un segment sûr ; un nom vidé
 * par le nettoyage retombe sur un nom par défaut plutôt que sur une exception.
 */
private fun nomSur(nom: String): String =
    nom.replace('/', '_').replace('\\', '_').trim().ifBlank { "note.md" }
