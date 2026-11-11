package eu.ocnotes.data

import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.UUID

/**
 * Logique de la sauvegarde Android (Auto Backup : compte Google ou Seedvault),
 * sans dépendance à Android pour pouvoir être testée sur la JVM.
 *
 * # Ce qui part dans la sauvegarde
 *
 * - les dossiers des profils **locaux** : leurs notes n'existent nulle part
 *   ailleurs ;
 * - un instantané du registre réduit à ces profils
 *   ([FICHIER_INSTANTANE]), jamais `accounts.json` lui-même.
 *
 * # Ce qui n'y part jamais
 *
 * - les profils serveur : leur cache est une copie du serveur, et ses notes
 *   n'ont pas à quitter le serveur privé de l'utilisateur pour Google ;
 * - les jetons (`TokenStore`) : chiffrés par une clé Keystore qui ne quitte
 *   pas l'appareil, ils seraient illisibles une fois restaurés.
 *
 * # Pourquoi un instantané plutôt que `accounts.json`
 *
 * Restaurer le registre complet ferait réapparaître des profils serveur sans
 * dossier ni jeton. L'instantané ne liste que des profils locaux, et
 * [appliquerRestauration] le fusionne avec le registre éventuellement déjà
 * présent sur l'appareil, sans rien écraser.
 */
internal object SauvegardeProfils {

    /** Dossier (sous `filesDir`) de l'instantané du registre. */
    const val DOSSIER_SAUVEGARDE = "sauvegarde"

    /** Instantané du registre, réduit aux profils locaux. */
    const val FICHIER_INSTANTANE = "profils-locaux.json"

    /** Quota d'Auto Backup par application, fixé par Android : 25 Mo. */
    const val QUOTA_OCTETS = 25L * 1024 * 1024

    /** Seuil d'avertissement dans les Réglages : 80 % du quota (20 Mo). */
    const val SEUIL_AVERTISSEMENT_OCTETS = QUOTA_OCTETS * 4 / 5

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        prettyPrint = true
    }

    /** Chemin de l'instantané dans [filesDir]. */
    fun instantane(filesDir: File): File =
        File(File(filesDir, DOSSIER_SAUVEGARDE), FICHIER_INSTANTANE)

    /**
     * Profils locaux du registre, ou liste vide si le registre manque ou est
     * illisible. Ne lève jamais : la sauvegarde ne doit pas planter
     * l'application.
     */
    fun profilsLocaux(filesDir: File): List<AccountProfile> {
        val registre = lireRegistre(File(filesDir, AccountRegistry.REGISTRY_FILE)) ?: return emptyList()
        return registre.accounts.filter { it.kind == AccountRegistry.KIND_LOCAL && estIdProfil(it.id) }
    }

    /**
     * Écrit l'instantané des profils locaux et renvoie ces profils.
     *
     * Sans profil local, l'instantané est supprimé : une sauvegarde suivante
     * ne doit pas restaurer des profils que l'utilisateur a retirés.
     */
    fun ecrireInstantane(filesDir: File): List<AccountProfile> {
        val locaux = profilsLocaux(filesDir)
        val cible = instantane(filesDir)
        if (locaux.isEmpty()) {
            cible.delete()
            return emptyList()
        }
        val document = AccountRegistry.RegistryDocument(
            version = AccountRegistry.VERSION,
            activeAccountId = locaux.first().id,
            accounts = locaux,
        )
        ecrireAtomiquement(cible, json.encodeToString(document))
        return locaux
    }

    /**
     * Fichiers à confier à la sauvegarde : l'instantané puis tout le contenu
     * des dossiers des profils [locaux]. Les fichiers temporaires (`.tmp`),
     * restes d'une écriture interrompue, sont laissés de côté.
     */
    fun fichiersASauvegarder(filesDir: File, locaux: List<AccountProfile>): List<File> {
        val instantane = instantane(filesDir)
        val dossierComptes = File(filesDir, AccountRegistry.ACCOUNTS_DIR)
        return buildList {
            if (instantane.isFile) add(instantane)
            locaux.forEach { profil ->
                File(dossierComptes, profil.id).walkTopDown()
                    .filter { it.isFile && !it.name.endsWith(".tmp") }
                    .sortedBy { it.path }
                    .forEach { add(it) }
            }
        }
    }

    /** Taille totale, en octets, de ce que la sauvegarde emporterait. */
    fun tailleASauvegarder(filesDir: File): Long =
        fichiersASauvegarder(filesDir, profilsLocaux(filesDir)).sumOf { it.length() }

    /**
     * Intègre l'instantané restauré au registre de l'appareil, puis le
     * supprime.
     *
     * - Pas de registre lisible (installation neuve) : l'instantané devient
     *   le registre.
     * - Un registre existe déjà : les profils restaurés absents y sont
     *   ajoutés à la fin, le profil actif ne change pas.
     *
     * Un profil dont le dossier n'a pas été restauré est ignoré : il serait
     * vide. Renvoie le nombre de profils ajoutés.
     */
    fun appliquerRestauration(filesDir: File): Int {
        val fichier = instantane(filesDir)
        val restaure = lireRegistre(fichier) ?: run {
            fichier.delete()
            return 0
        }
        val dossierComptes = File(filesDir, AccountRegistry.ACCOUNTS_DIR)
        val restaures = restaure.accounts.filter {
            it.kind == AccountRegistry.KIND_LOCAL && estIdProfil(it.id) && File(dossierComptes, it.id).isDirectory
        }
        val fichierRegistre = File(filesDir, AccountRegistry.REGISTRY_FILE)
        val actuel = lireRegistre(fichierRegistre)

        val ajoutes: Int
        val document = if (actuel == null) {
            ajoutes = restaures.size
            if (restaures.isEmpty()) null
            else AccountRegistry.RegistryDocument(
                version = AccountRegistry.VERSION,
                activeAccountId = restaures.first().id,
                accounts = restaures,
            )
        } else {
            val connus = actuel.accounts.map { it.id }.toSet()
            val nouveaux = restaures.filterNot { it.id in connus }
            ajoutes = nouveaux.size
            if (nouveaux.isEmpty()) null else actuel.copy(accounts = actuel.accounts + nouveaux)
        }
        document?.let { ecrireAtomiquement(fichierRegistre, json.encodeToString(it)) }
        fichier.delete()
        return ajoutes
    }

    /** Registre lu et validé, ou `null` s'il manque ou est illisible. */
    private fun lireRegistre(fichier: File): AccountRegistry.RegistryDocument? {
        if (!fichier.isFile) return null
        return runCatching {
            json.decodeFromString<AccountRegistry.RegistryDocument>(fichier.readText(Charsets.UTF_8))
        }.getOrNull()?.takeIf { doc ->
            doc.version == AccountRegistry.VERSION &&
                doc.accounts.isNotEmpty() &&
                doc.accounts.all { estIdProfil(it.id) } &&
                doc.accounts.any { it.id == doc.activeAccountId }
        }
    }

    /** Même règle que le registre : un UUID sous sa forme canonique. */
    private fun estIdProfil(id: String): Boolean =
        runCatching { UUID.fromString(id).toString() == id }.getOrDefault(false)

    /** Écriture par fichier temporaire puis renommage, comme le registre. */
    private fun ecrireAtomiquement(cible: File, contenu: String) {
        cible.parentFile?.mkdirs()
        val temporaire = File(cible.parentFile, "${cible.name}.tmp")
        FileOutputStream(temporaire).use { flux ->
            flux.write(contenu.toByteArray(Charsets.UTF_8))
            flux.fd.sync()
        }
        try {
            Files.move(
                temporaire.toPath(), cible.toPath(),
                StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING,
            )
        } catch (_: Exception) {
            Files.move(temporaire.toPath(), cible.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
    }
}
