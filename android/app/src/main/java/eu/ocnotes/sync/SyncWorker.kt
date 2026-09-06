package eu.ocnotes.sync

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import eu.ocnotes.OCnotesApplication
import eu.ocnotes.data.ErrorCategory
import eu.ocnotes.data.OCnotesException

/**
 * Une passe de synchronisation, exécutée par WorkManager.
 *
 * Le travail se résume à trois gestes : rouvrir la session avec le token du
 * Keystore, appeler `SyncJSON`, et notifier les conflits.
 *
 * `CoroutineWorker.doWork` s'exécute déjà hors du thread principal, mais le
 * dépôt rebascule quand même sur `Dispatchers.IO` : c'est sa règle, pas celle
 * de l'appelant.
 */
class SyncWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val container = (applicationContext as OCnotesApplication).container

        // Pas de session récupérable : rien à synchroniser, et surtout rien à
        // signaler. L'utilisateur se reconnectera, ce n'est pas un échec.
        if (!container.repository.ensureSession()) return Result.success()

        return try {
            val report = container.repository.sync()

            if (report.conflicts.isNotEmpty()) {
                container.syncNotifier.notifyConflicts(report.conflicts)
            }

            // L'inventaire vient de changer : on vient de pousser. Le
            // reconstruire maintenant, pendant qu'il y a du réseau, est ce qui
            // permet à la liste plate de s'ouvrir instantanément la prochaine
            // fois — y compris dans le métro. L'échec est sans conséquence :
            // l'inventaire précédent reste servi.
            container.repository.refreshIndex()

            // `SyncJSON` ne lève pas sur panne réseau : l'incident est dans le
            // champ `error`, à côté de ce qui a tout de même été poussé, et sa
            // catégorie arrive toute extraite dans `errorCode`. De quoi
            // distinguer un serveur muet — on réessaiera — d'un token expiré,
            // qui ne se répare qu'à la main.
            when {
                !report.hasError -> Result.success()

                report.categorieErreur == ErrorCategory.AUTH -> {
                    // Le message Go peut contenir une URL ou un chemin. Le
                    // code stable suffit au diagnostic et ne révèle rien.
                    Log.w(TAG, "token refusé pendant la synchronisation (${report.errorCode})")
                    container.repository.invalidateSession()
                    Result.failure()
                }

                report.remaining > 0 -> {
                    Log.i(
                        TAG,
                        "passe partielle : ${report.pushed} envoyée(s), " +
                            "${report.remaining} en attente",
                    )
                    Result.retry()
                }

                else -> Result.success()
            }
        } catch (e: OCnotesException) {
            // Seuls deux cas arrivent ici : aucun espace de travail choisi, ou
            // un token devenu invalide. Réessayer ne servirait à rien, il faut
            // un geste de l'utilisateur.
            Log.w(TAG, "synchronisation abandonnée (${e.category}/${e.code})")
            if (e.category == ErrorCategory.AUTH) {
                container.repository.invalidateSession()
            }
            Result.failure()
        }
    }

    private companion object {
        const val TAG = "OCnotesSync"
    }
}
