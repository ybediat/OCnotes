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
 * Vide `dossier` et le recrée, avant d'y déposer les copies d'un partage.
 *
 * Pourquoi recopier : le cache local nomme ses fichiers par une empreinte
 * SHA-256 du chemin — il n'y a pas de `journal.md` sur le disque à désigner. On
 * en recrée un au vrai nom, exposé par le `FileProvider` déclaré au manifeste.
 * Le dossier est vidé à chaque partage : ces copies ne servent que le temps de
 * l'envoi.
 *
 * La copie elle-même est faite par le cœur Go (`App.exportFile`), octet pour
 * octet : un `.docx` ou un `.odt` ne survivrait pas à un passage par une
 * chaîne Kotlin.
 */
suspend fun preparerDossierPartage(dossier: File): File = withContext(Dispatchers.IO) {
    dossier.apply {
        deleteRecursively()
        mkdirs()
    }
}

/**
 * Chemin de la copie de `nom` dans `dossier`, sans écraser une copie déjà
 * posée.
 *
 * En liste plate, deux notes de dossiers différents peuvent porter le même
 * nom : sans cette précaution, la seconde remplaçait la première, et le
 * destinataire recevait deux fois le même fichier. `pris` est l'ensemble des
 * noms déjà attribués dans ce partage ; il est complété au passage.
 */
fun cibleLibre(dossier: File, nom: String, pris: MutableSet<String>): File {
    val sur = nomSur(nom)
    val point = sur.lastIndexOf('.').takeIf { it > 0 } ?: sur.length
    val base = sur.substring(0, point)
    val extension = sur.substring(point)
    var candidat = sur
    var rang = 2
    while (!pris.add(candidat.lowercase())) {
        candidat = "$base ($rang)$extension" // i18n-ok : un nom de fichier, pas un texte affiché
        rang++
    }
    return File(dossier, candidat)
}

/**
 * Ouvre le sélecteur d'application (mail, messagerie, stockage…) avec ces
 * fichiers en pièce jointe.
 *
 * Les fichiers sont déjà écrits, sous le dossier exposé par le
 * `FileProvider` : il ne reste qu'à les désigner. `getString` est une
 * ressource, pas un composable : il s'appelle ici sans risque.
 */
fun partagerFichiers(context: Context, fichiers: List<File>) {
    if (fichiers.isEmpty()) return

    val uris = fichiers.map {
        FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", it)
    }
    val type = typeCommun(fichiers.map { it.name })
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
 * mélangeant les deux retombe sur le type générique du texte ; un lot où
 * figure un document, sur le type quelconque — ils n'ont pas de type commun
 * plus précis.
 */
internal fun typeCommun(noms: List<String>): String {
    val types = noms.map(::typeDe).toSet()
    types.singleOrNull()?.let { return it }
    return if (types.all { it.startsWith(PREFIXE_TEXTE) }) TYPE_TEXTE_GENERIQUE else TYPE_QUELCONQUE
}

private fun typeDe(nom: String): String {
    val extension = nom.substringAfterLast('.', "").lowercase()
    return when (extension) {
        "txt" -> TYPE_TEXTE_BRUT
        "docx" -> TYPE_DOCX
        "odt" -> TYPE_ODT
        else -> TYPE_MARKDOWN
    }
}

private const val PREFIXE_TEXTE = "text/"
private const val TYPE_MARKDOWN = "text/markdown"
private const val TYPE_TEXTE_BRUT = "text/plain"
private const val TYPE_TEXTE_GENERIQUE = PREFIXE_TEXTE + "*"
private const val TYPE_QUELCONQUE = "*/" + "*"
private const val TYPE_DOCX = "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
private const val TYPE_ODT = "application/vnd.oasis.opendocument.text"

/**
 * Le serveur OpenCloud accepte dans un nom des caractères qu'un système de
 * fichiers refuse (`/` surtout). On ne garde qu'un segment sûr ; un nom vidé
 * par le nettoyage retombe sur un nom par défaut plutôt que sur une exception.
 */
private fun nomSur(nom: String): String =
    nom.replace('/', '_').replace('\\', '_').trim().ifBlank { "note.md" }
