package eu.ocnotes.data

import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
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
 *   ailleurs. Est local un profil que son cœur Go dit local (`config.json`),
 *   pas seulement le registre — voir [profilsLocaux] ;
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

    /** Seuil d'avertissement dans les Réglages : 80 % du [quota] (20 Mo sur 25). */
    fun seuilAvertissement(quota: Long): Long = quota / 5 * 4

    // Drapeaux du transport, tels que `BackupAgent` les définit
    // (`FLAG_CLIENT_SIDE_ENCRYPTION_ENABLED`, `FLAG_DEVICE_TO_DEVICE_TRANSFER`).
    // Recopiés ici pour que la règle se teste sans Android.
    const val DRAPEAU_CHIFFREMENT = 1
    const val DRAPEAU_TRANSFERT_DIRECT = 2

    /** Vrai pour un transfert direct d'un téléphone à l'autre : rien ne passe par un serveur. */
    fun estTransfertDirect(drapeaux: Int): Boolean = drapeaux and DRAPEAU_TRANSFERT_DIRECT != 0

    /**
     * Vrai si les notes peuvent partir : vers un autre téléphone, ou vers une
     * sauvegarde chiffrée de bout en bout — par le verrouillage d'écran, que
     * l'hébergeur ne connaît pas. Toute autre sauvegarde est refusée : les
     * notes d'un profil local n'ont pas à être lisibles ailleurs.
     */
    fun envoiAutorise(drapeaux: Int): Boolean =
        estTransfertDirect(drapeaux) || drapeaux and DRAPEAU_CHIFFREMENT != 0

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        prettyPrint = true
    }

    /** Chemin de l'instantané dans [filesDir]. */
    fun instantane(filesDir: File): File =
        File(File(filesDir, DOSSIER_SAUVEGARDE), FICHIER_INSTANTANE)

    /**
     * Profils locaux de l'appareil, ou liste vide si le registre manque ou est
     * illisible. Ne lève jamais : la sauvegarde ne doit pas planter
     * l'application.
     *
     * Le registre donne la liste des profils, mais c'est le `config.json` du
     * cœur Go qui dit lesquels sont locaux. Le registre retarde parfois sur
     * lui, dans les deux sens : un profil branché à un serveur peut y être
     * encore dit local — son cache partirait alors dans la sauvegarde — et un
     * registre reconstruit classe un profil local parmi les serveurs — il ne
     * serait plus sauvegardé. La configuration est le point de commit de
     * chaque bascule du cœur ; elle fait foi.
     *
     * Un profil sans configuration lisible n'est pas sauvegardé cette fois :
     * le cœur la réécrit de lui-même au lancement suivant s'il est local.
     */
    fun profilsLocaux(filesDir: File): List<AccountProfile> {
        val registre = lireRegistre(File(filesDir, AccountRegistry.REGISTRY_FILE)) ?: return emptyList()
        val dossierComptes = File(filesDir, AccountRegistry.ACCOUNTS_DIR)
        return registre.accounts
            .filter { estIdProfil(it.id) && coeurLocal(File(dossierComptes, it.id)) }
            .map { profil ->
                if (profil.kind == AccountRegistry.KIND_LOCAL) profil
                // Le registre retardait : l'instantané décrit le profil tel
                // que le cœur le connaît, sans trace d'un serveur.
                else AccountProfile(id = profil.id, kind = AccountRegistry.KIND_LOCAL)
            }
    }

    /** Vrai si le `config.json` de ce dossier de profil déclare le mode local. */
    private fun coeurLocal(dossierProfil: File): Boolean {
        val config = File(dossierProfil, AccountRegistry.CONFIG_FILE)
        if (!config.isFile) return false
        return runCatching {
            json.parseToJsonElement(config.readText(Charsets.UTF_8))
                .jsonObject["mode"]?.jsonPrimitive?.contentOrNull == AccountRegistry.KIND_LOCAL
        }.getOrDefault(false)
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
