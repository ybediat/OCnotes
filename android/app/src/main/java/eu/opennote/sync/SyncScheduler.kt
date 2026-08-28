package eu.opennote.sync

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit

/**
 * Décide **quand** synchroniser.
 *
 * La façade Go ne lance aucune goroutine périodique : elle exécute une passe
 * quand on la lui demande. C'est Android qui pilote, parce que lui seul
 * connaît l'état de la batterie, du réseau et du cycle de vie.
 *
 * Trois déclencheurs, trois travaux uniques distincts pour qu'ils ne
 * s'annulent pas les uns les autres :
 *
 *  - [schedulePeriodic] — filet de sécurité horaire, contraint au réseau ;
 *  - [syncNow] — retour au premier plan, ou geste explicite de l'utilisateur ;
 *  - [syncAfterWrite] — après une écriture, **avec anti-rebond**.
 */
class SyncScheduler(context: Context) {

    private val workManager = WorkManager.getInstance(context.applicationContext)

    private val networkRequired = Constraints.Builder()
        .setRequiredNetworkType(NetworkType.CONNECTED)
        .build()

    /**
     * Installe la passe périodique.
     *
     * `KEEP` : si un travail du même nom existe déjà, on ne le réinstalle pas —
     * sinon chaque lancement de l'application repousserait la prochaine
     * exécution et la synchronisation périodique n'aurait jamais lieu.
     */
    fun schedulePeriodic() {
        val request = PeriodicWorkRequestBuilder<SyncWorker>(PERIOD_HOURS, TimeUnit.HOURS)
            .setConstraints(networkRequired)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 5, TimeUnit.MINUTES)
            .build()

        workManager.enqueueUniquePeriodicWork(
            WORK_PERIODIC,
            ExistingPeriodicWorkPolicy.KEEP,
            request,
        )
    }

    /**
     * Déclenche une passe immédiate.
     *
     * `KEEP` : deux gestes rapprochés ne lancent pas deux passes concurrentes
     * sur le même cache.
     */
    fun syncNow() {
        val request = OneTimeWorkRequestBuilder<SyncWorker>()
            .setConstraints(networkRequired)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .build()

        workManager.enqueueUniqueWork(WORK_NOW, ExistingWorkPolicy.KEEP, request)
    }

    /**
     * Déclenche une passe après une écriture, avec anti-rebond.
     *
     * L'éditeur enregistre souvent ; synchroniser à chaque frappe serait
     * absurde. Le délai initial et `REPLACE` sur un nom unique forment un
     * anti-rebond complet : chaque nouvelle écriture remplace la demande en
     * attente, et la passe ne part que [DEBOUNCE_SECONDS] secondes après la
     * **dernière** écriture.
     */
    fun syncAfterWrite() {
        val request = OneTimeWorkRequestBuilder<SyncWorker>()
            .setConstraints(networkRequired)
            .setInitialDelay(DEBOUNCE_SECONDS, TimeUnit.SECONDS)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .build()

        workManager.enqueueUniqueWork(WORK_DEBOUNCED, ExistingWorkPolicy.REPLACE, request)
    }

    /** Annule tout travail en cours ou programmé. Appelé à la déconnexion. */
    fun cancelAll() {
        workManager.cancelUniqueWork(WORK_PERIODIC)
        workManager.cancelUniqueWork(WORK_NOW)
        workManager.cancelUniqueWork(WORK_DEBOUNCED)
    }

    private companion object {
        const val WORK_PERIODIC = "opennote-sync-periodique"
        const val WORK_NOW = "opennote-sync-immediate"
        const val WORK_DEBOUNCED = "opennote-sync-apres-ecriture"

        const val PERIOD_HOURS = 1L
        const val DEBOUNCE_SECONDS = 20L
    }
}
