package eu.ocnotes.data

import android.annotation.SuppressLint
import android.app.backup.BackupAgent
import android.app.backup.BackupDataInput
import android.app.backup.BackupDataOutput
import android.app.backup.FullBackupDataOutput
import android.content.Context
import android.content.pm.ApplicationInfo
import android.os.Build
import android.os.ParcelFileDescriptor
import java.io.File
import java.io.IOException

/**
 * Agent de sauvegarde Android (Auto Backup), sans aucune bibliothèque Google :
 * c'est l'API du système, servie selon le téléphone par le compte Google ou
 * par Seedvault (GrapheneOS, CalyxOS, LineageOS…).
 *
 * Il ne confie au système que les profils locaux et les préférences
 * d'affichage ; le choix des fichiers est décrit dans [SauvegardeProfils].
 * `android:fullBackupOnly="true"` dans le manifeste : sans lui, déclarer un
 * agent ferait basculer l'application dans l'ancienne sauvegarde clé/valeur.
 *
 * Les règles XML (`backup_rules.xml`, `data_extraction_rules.xml`) restent
 * nécessaires : Android les relit à la **restauration** et refuse tout fichier
 * qu'elles n'incluent pas.
 *
 * Elles ne protègent en revanche rien à l'**envoi** : Android n'applique
 * `disableIfNoEncryptionCapabilities` que dans son propre `onFullBackup`, que
 * cet agent remplace. Le refus d'une sauvegarde non chiffrée est donc fait
 * ici, à la main.
 */
class SauvegardeAgent : BackupAgent() {

    /**
     * Crée les dossiers inclus par les règles de restauration avant qu'elle
     * ne commence.
     *
     * Android ne reconnaît une règle `<include path="accounts/">` comme un
     * dossier que si ce dossier existe déjà sur le disque : sinon il la
     * prend pour un fichier, et refuse en silence tout ce qui est dedans.
     * Sur un téléphone neuf, ou après un effacement des données, ni
     * `accounts/` ni `sauvegarde/` n'existent : sans ceci, seules les
     * préférences d'affichage revenaient (constaté le 12/11/2026 par
     * scripts/e2e-emulateur.sh). L'agent est créé avant la restauration :
     * c'est le bon moment.
     */
    override fun onCreate() {
        super.onCreate()
        File(filesDir, AccountRegistry.ACCOUNTS_DIR).mkdirs()
        File(filesDir, SauvegardeProfils.DOSSIER_SAUVEGARDE).mkdirs()
    }

    override fun onFullBackup(data: FullBackupDataOutput) {
        // Avant Android 9 le transport ne dit rien de lui : ni chiffrement de
        // bout en bout, ni transfert direct. Sans preuve, on refuse.
        val drapeaux = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) data.transportFlags else 0
        // Version de développement seulement : accepte aussi le faux drapeau
        // du transport de test (voir DRAPEAU_FAUX_CHIFFREMENT).
        val deboguable = applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0
        if (!SauvegardeProfils.envoiAutorise(drapeaux, deboguable)) {
            SuiviSauvegarde.noterNonChiffree(this)
            // Échouer plutôt que rendre la main : une sauvegarde vide
            // remplacerait la précédente, qui, elle, était chiffrée.
            throw IOException("non chiffree") // i18n-ok : jamais affiché
        }
        val transfertDirect = SauvegardeProfils.estTransfertDirect(drapeaux)

        val locaux = SauvegardeProfils.ecrireInstantane(filesDir)
        val fichiers = SauvegardeProfils.fichiersASauvegarder(filesDir, locaux) +
            listOfNotNull(preferencesAffichage().takeIf { it.isFile })

        // Dépasser le quota ferait échouer la sauvegarde côté système. On
        // échoue avant, sans rien envoyer : la sauvegarde précédente reste
        // en place au lieu d'être remplacée par une archive tronquée.
        val taille = fichiers.sumOf { it.length() }
        if (taille > data.quota) {
            if (!transfertDirect) SuiviSauvegarde.noterQuotaDepasse(this, data.quota)
            throw IOException("quota") // i18n-ok : jamais affiché
        }
        fichiers.forEach { fullBackupFile(it, data) }
        // Un transfert vers un autre téléphone n'est pas une sauvegarde : il
        // ne dit rien de ce qui est à l'abri, et son quota n'est pas le même.
        if (!transfertDirect) SuiviSauvegarde.noterEnvoi(this, data.quota)
    }

    /** Le système a refusé après coup : l'envoi qu'on vient de noter n'a pas eu lieu (voir `etat`). */
    override fun onQuotaExceeded(backupDataBytes: Long, quotaBytes: Long) {
        SuiviSauvegarde.noterQuotaDepasse(this, quotaBytes)
    }

    /**
     * Fin de restauration : les fichiers sont en place, on rattache les
     * profils restaurés au registre. Tourne avant tout lancement normal de
     * l'application, qui relira donc un registre complet.
     */
    override fun onRestoreFinished() {
        SauvegardeProfils.appliquerRestauration(filesDir)
    }

    // Ancienne sauvegarde clé/valeur : inutilisée (fullBackupOnly), mais
    // abstraite dans BackupAgent.
    override fun onBackup(
        oldState: ParcelFileDescriptor?,
        data: BackupDataOutput?,
        newState: ParcelFileDescriptor?,
    ) = Unit

    override fun onRestore(data: BackupDataInput?, appVersionCode: Int, newState: ParcelFileDescriptor?) = Unit

    /** Fichier XML des préférences d'affichage (`shared_prefs/`). */
    private fun preferencesAffichage(): File =
        File(File(dataDir, "shared_prefs"), PreferencesAffichage.FICHIER + ".xml") // i18n-ok
}

/** État de la sauvegarde Android, tel que les Réglages l'affichent. */
data class EtatSauvegarde(
    /** Taille de ce que la sauvegarde emporte, en octets. */
    val octets: Long,
    /** Date (ms depuis l'époque) du dernier dépassement de quota, ou `null`. */
    val quotaDepasseLe: Long?,
    /**
     * Date du dernier envoi confié au système, ou `null` s'il n'y en a jamais
     * eu. Un envoi, pas une preuve d'arrivée : Android ne dit pas à
     * l'application si le transport a abouti.
     */
    val dernierEnvoiLe: Long? = null,
    /** Date du dernier refus faute de chiffrement de bout en bout, ou `null`. */
    val nonChiffreeLe: Long? = null,
    /** Quota annoncé par le transport au dernier passage ; 25 Mo tant qu'on ne l'a pas vu. */
    val quota: Long = SauvegardeProfils.QUOTA_OCTETS,
) {
    /** Vrai s'il faut prévenir l'utilisateur : refus, quota dépassé ou presque atteint. */
    val avertir: Boolean
        get() = nonChiffreeLe != null || quotaDepasseLe != null ||
            octets >= SauvegardeProfils.seuilAvertissement(quota)
}

/**
 * Mémoire de l'agent entre deux sauvegardes : dernier envoi, dernier refus,
 * quota vu. Fichier de préférences à part, jamais sauvegardé lui-même.
 */
class SuiviSauvegarde(context: Context) {

    private val context = context.applicationContext

    /** Lit l'état ; parcourt les dossiers des profils, donc à appeler hors du fil principal. */
    fun etat(): EtatSauvegarde {
        val prefs = prefs(context)
        fun positif(cle: String): Long? = prefs.getLong(cle, 0L).takeIf { it > 0L }
        val depasse = positif(CLE_QUOTA_DEPASSE)
        return EtatSauvegarde(
            octets = SauvegardeProfils.tailleASauvegarder(context.filesDir),
            quotaDepasseLe = depasse,
            // Un dépassement constaté par le système arrive après l'envoi que
            // l'agent vient de noter : cet envoi n'a alors pas eu lieu.
            dernierEnvoiLe = positif(CLE_ENVOI).takeIf { depasse == null },
            nonChiffreeLe = positif(CLE_NON_CHIFFREE),
            quota = positif(CLE_QUOTA) ?: SauvegardeProfils.QUOTA_OCTETS,
        )
    }

    // `commit` et non `apply` : Android arrête le processus de l'agent dès la
    // sauvegarde finie, une écriture différée n'aurait pas le temps d'aboutir.
    @SuppressLint("ApplySharedPref")
    internal companion object {
        private const val FICHIER = "ocnotes_sauvegarde"
        private const val CLE_QUOTA_DEPASSE = "quota_depasse_le"
        private const val CLE_ENVOI = "envoi_le"
        private const val CLE_NON_CHIFFREE = "non_chiffree_le"
        private const val CLE_QUOTA = "quota_octets"

        private fun prefs(context: Context) =
            context.getSharedPreferences(FICHIER, Context.MODE_PRIVATE)

        /** Les fichiers sont partis : les refus précédents ne valent plus. */
        fun noterEnvoi(context: Context, quota: Long) {
            prefs(context).edit()
                .putLong(CLE_ENVOI, System.currentTimeMillis())
                .putLong(CLE_QUOTA, quota)
                .remove(CLE_QUOTA_DEPASSE)
                .remove(CLE_NON_CHIFFREE)
                .commit()
        }

        /** Quota dépassé, constaté par l'agent avant l'envoi ou par le système après. */
        fun noterQuotaDepasse(context: Context, quota: Long) {
            prefs(context).edit()
                .putLong(CLE_QUOTA_DEPASSE, System.currentTimeMillis())
                .putLong(CLE_QUOTA, quota)
                .commit()
        }

        fun noterNonChiffree(context: Context) {
            prefs(context).edit().putLong(CLE_NON_CHIFFREE, System.currentTimeMillis()).commit()
        }
    }
}
