package eu.opennote.sync

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import eu.opennote.R
import eu.opennote.data.ConflictDto
import eu.opennote.ui.common.Texte
import eu.opennote.ui.common.resoudre

/**
 * Notifie l'utilisateur des conflits de synchronisation.
 *
 * Un conflit n'est pas une perte : la version distante est devenue la note de
 * référence, et la version locale a été recopiée à côté sous `copyPath`. Mais
 * l'utilisateur ouvrirait sa note et y trouverait un texte qu'il n'a pas
 * écrit, sans comprendre. **Il faut le lui dire**, c'est le seul événement de
 * synchronisation qui mérite une notification.
 */
class SyncNotifier(private val context: Context) {

    fun ensureChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            context.getString(R.string.sync_channel_name),
            NotificationManager.IMPORTANCE_DEFAULT,
        ).apply {
            description = context.getString(R.string.sync_channel_description)
        }
        NotificationManagerCompat.from(context).createNotificationChannel(channel)
    }

    /**
     * Affiche une notification récapitulative des conflits d'une passe.
     *
     * Sans la permission `POST_NOTIFICATIONS` (Android 13+), on ne tente rien.
     * Le bandeau de l'écran Réglages reste alors le seul canal — c'est
     * acceptable, la donnée n'est pas perdue.
     */
    fun notifyConflicts(conflicts: List<ConflictDto>) {
        if (conflicts.isEmpty()) return

        // La vérification est écrite ici, en toutes lettres, plutôt que
        // déléguée à une méthode voisine : `MissingPermission` ne suit pas les
        // appels, et une garde qu'il ne voit pas fait échouer `lintDebug` tout
        // entier — donc aussi `MissingTranslation`, qui partage la tâche et
        // dont c'est le seul contrôle automatique.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS,
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            return
        }

        ensureChannel()

        // Une notification est postée maintenant, pour être lue maintenant :
        // c'est le seul endroit de l'application où rédiger tout de suite est
        // la bonne chose à faire. Ailleurs, `Texte` reste non résolu jusqu'à
        // l'affichage, pour que la langue suive celle de l'appareil.
        val res = context.resources
        val rassurance = Texte.de(R.string.sync_conflits_rassurance).resoudre(res)
        val titre = Texte.pluriel(R.plurals.sync_conflits_titre, conflicts.size).resoudre(res)

        val detail = buildString {
            append(rassurance)
            conflicts.take(MAX_LIGNES).forEach { conflit ->
                append(Texte.de(R.string.sync_conflits_ligne, nomAffichable(conflit.copyPath)).resoudre(res))
            }
            val reste = conflicts.size - MAX_LIGNES
            if (reste > 0) {
                append(Texte.pluriel(R.plurals.sync_conflits_reste, reste).resoudre(res))
            }
        }

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_warning)
            .setContentTitle(titre)
            .setContentText(rassurance)
            .setStyle(NotificationCompat.BigTextStyle().bigText(detail))
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(true)
            .build()

        NotificationManagerCompat.from(context).notify(NOTIFICATION_ID, notification)
    }

    /** Dernier segment du chemin, sans l'extension `.md`. */
    private fun nomAffichable(path: String): String =
        path.substringAfterLast('/').removeSuffix(".md")

    private companion object {
        const val CHANNEL_ID = "opennote-sync"
        const val NOTIFICATION_ID = 1001
        const val MAX_LIGNES = 5
    }
}
