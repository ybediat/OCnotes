package eu.ocnotes.data

import android.app.backup.BackupAgent
import android.app.backup.BackupDataInput
import android.app.backup.BackupDataOutput
import android.app.backup.FullBackupDataOutput
import android.content.Context
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
 */
class SauvegardeAgent : BackupAgent() {

    override fun onFullBackup(data: FullBackupDataOutput) {
        val locaux = SauvegardeProfils.ecrireInstantane(filesDir)
        val fichiers = SauvegardeProfils.fichiersASauvegarder(filesDir, locaux) +
            listOfNotNull(preferencesAffichage().takeIf { it.isFile })

        // Dépasser le quota ferait échouer la sauvegarde côté système. On
        // échoue avant, sans rien envoyer : la sauvegarde précédente reste
        // en place au lieu d'être remplacée par une archive tronquée.
        val taille = fichiers.sumOf { it.length() }
        if (taille > data.quota) {
            SuiviSauvegarde.noterQuotaDepasse(this)
            throw IOException("quota") // i18n-ok : jamais affiché
        }
        fichiers.forEach { fullBackupFile(it, data) }
        SuiviSauvegarde.noterQuotaRespecte(this)
    }

    override fun onQuotaExceeded(backupDataBytes: Long, quotaBytes: Long) {
        SuiviSauvegarde.noterQuotaDepasse(this)
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
) {
    val quota: Long get() = SauvegardeProfils.QUOTA_OCTETS

    /** Vrai s'il faut prévenir l'utilisateur : quota dépassé ou presque atteint. */
    val avertir: Boolean
        get() = quotaDepasseLe != null || octets >= SauvegardeProfils.SEUIL_AVERTISSEMENT_OCTETS
}

/**
 * Mémoire de l'agent entre deux sauvegardes : le dernier dépassement de quota.
 * Fichier de préférences à part, jamais sauvegardé lui-même.
 */
class SuiviSauvegarde(context: Context) {

    private val context = context.applicationContext

    /** Lit l'état ; parcourt les dossiers des profils, donc à appeler hors du fil principal. */
    fun etat(): EtatSauvegarde {
        val prefs = prefs(context)
        val depasse = prefs.getLong(CLE_QUOTA_DEPASSE, 0L).takeIf { it > 0L }
        return EtatSauvegarde(
            octets = SauvegardeProfils.tailleASauvegarder(context.filesDir),
            quotaDepasseLe = depasse,
        )
    }

    internal companion object {
        private const val FICHIER = "ocnotes_sauvegarde"
        private const val CLE_QUOTA_DEPASSE = "quota_depasse_le"

        private fun prefs(context: Context) =
            context.getSharedPreferences(FICHIER, Context.MODE_PRIVATE)

        fun noterQuotaDepasse(context: Context) {
            prefs(context).edit().putLong(CLE_QUOTA_DEPASSE, System.currentTimeMillis()).commit()
        }

        fun noterQuotaRespecte(context: Context) {
            prefs(context).edit().remove(CLE_QUOTA_DEPASSE).commit()
        }
    }
}
