package eu.ocnotes.ui.common

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import eu.ocnotes.R
import eu.ocnotes.appContainer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.util.UUID

/**
 * Crée un sous-dossier propre à ce partage, avant d'y déposer ses copies.
 *
 * Pourquoi recopier : le cache local nomme ses fichiers par une empreinte
 * SHA-256 du chemin — il n'y a pas de `journal.md` sur le disque à désigner. On
 * en recrée un au vrai nom, exposé par le `FileProvider` déclaré au manifeste.
 * Chaque partage reste isolé des suivants : l'application destinataire peut
 * ainsi continuer à lire ses URI pendant qu'un autre partage est préparé.
 *
 * La copie elle-même est faite par le cœur Go (`App.exportFile`), octet pour
 * octet : un `.docx` ou un `.odt` ne survivrait pas à un passage par une
 * chaîne Kotlin.
 */
suspend fun preparerDossierPartage(dossier: File): File = withContext(Dispatchers.IO) {
    if (!dossier.exists() && !dossier.mkdirs()) {
        throw IOException("Impossible de créer le dossier de partage") // i18n-ok : erreur technique
    }
    if (!dossier.isDirectory) {
        throw IOException("Le chemin de partage n'est pas un dossier") // i18n-ok : erreur technique
    }

    nettoyerPartagesExpires(dossier)

    repeat(NOMBRE_ESSAIS_CREATION) {
        val partage = File(dossier, UUID.randomUUID().toString())
        if (partage.mkdir()) return@withContext partage
        if (!partage.exists()) {
            throw IOException("Impossible de créer un partage") // i18n-ok : erreur technique
        }
    }
    throw IOException("Impossible de réserver un dossier de partage unique") // i18n-ok : erreur technique
}

/** Supprime les partages vieux d'au moins une heure, sans toucher aux récents. */
fun nettoyerPartagesExpires(
    dossier: File,
    maintenant: Long = System.currentTimeMillis(),
) {
    val expiration = maintenant - DUREE_VIE_PARTAGE_MS
    dossier.listFiles()
        ?.filter { it.lastModified() <= expiration }
        ?.forEach(File::deleteRecursively)
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

    val container = context.appContainer
    val dossiers = fichiers.mapNotNull { it.parentFile }
        .filter { it.parentFile == container.dossierPartage }
        .distinct()
    if (dossiers.isNotEmpty()) {
        container.applicationScope.launch {
            delay(DUREE_VIE_PARTAGE_MS)
            withContext(Dispatchers.IO) {
                dossiers.forEach(File::deleteRecursively)
            }
        }
    }
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

/**
 * Les extensions viennent de Go (`internal/notes`), qui décide de ce qu'est
 * une note. Ici ne se choisit qu'une étiquette MIME, et le repli est le plus
 * inoffensif : un fichier inconnu part en texte brut, que tout destinataire
 * sait ouvrir.
 */
private fun typeDe(nom: String): String {
    val extension = nom.substringAfterLast('.', "").lowercase()
    return when (extension) {
        "md", "markdown", "mdown", "mkd" -> TYPE_MARKDOWN
        "docx" -> TYPE_DOCX
        "odt" -> TYPE_ODT
        else -> TYPE_TEXTE_BRUT
    }
}

private const val PREFIXE_TEXTE = "text/"
private const val TYPE_MARKDOWN = "text/markdown"
private const val TYPE_TEXTE_BRUT = "text/plain"
private const val TYPE_TEXTE_GENERIQUE = PREFIXE_TEXTE + "*"
private const val TYPE_QUELCONQUE = "*/" + "*"
private const val TYPE_DOCX = "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
private const val TYPE_ODT = "application/vnd.oasis.opendocument.text"
private const val DUREE_VIE_PARTAGE_MS = 60 * 60 * 1_000L
private const val NOMBRE_ESSAIS_CREATION = 3

/**
 * Le serveur OpenCloud accepte dans un nom des caractères qu'un système de
 * fichiers refuse (`/` surtout). On ne garde qu'un segment sûr ; un nom vidé
 * par le nettoyage retombe sur un nom par défaut plutôt que sur une exception.
 */
private fun nomSur(nom: String): String =
    nom.replace('/', '_').replace('\\', '_').trim().ifBlank { "note.md" }
