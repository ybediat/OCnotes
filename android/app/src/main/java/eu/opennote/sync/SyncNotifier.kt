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
     * Sans la permission `POST_NOTIFICATIONS` (Android 13+), on ne tente rien :
     * `NotificationManagerCompat.notify` lèverait une `SecurityException`. Le
     * bandeau de l'écran Réglages reste alors le seul canal — c'est acceptable,
     * la donnée n'est pas perdue.
     */
    fun notifyConflicts(conflicts: List<ConflictDto>) {
        if (conflicts.isEmpty() || !canNotify()) return

        ensureChannel()

        val titre = if (conflicts.size == 1) {
            "Une note modifiée sur deux appareils"
        } else {
            "${conflicts.size} notes modifiées sur deux appareils"
        }

        val detail = buildString {
            append("Votre version a été conservée à côté, rien n'est perdu.\n")
            conflicts.take(MAX_LIGNES).forEach { conflit ->
                append("\n• ").append(nomAffichable(conflit.copyPath))
            }
            if (conflicts.size > MAX_LIGNES) {
                append("\n• …et ").append(conflicts.size - MAX_LIGNES).append(" autre(s)")
            }
        }

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_warning)
            .setContentTitle(titre)
            .setContentText("Votre version a été conservée à côté, rien n'est perdu.")
            .setStyle(NotificationCompat.BigTextStyle().bigText(detail))
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(true)
            .build()

        NotificationManagerCompat.from(context).notify(NOTIFICATION_ID, notification)
    }

    private fun canNotify(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return true
        return ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.POST_NOTIFICATIONS,
        ) == PackageManager.PERMISSION_GRANTED
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
