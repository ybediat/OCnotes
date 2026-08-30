package eu.opennote

import android.app.Application
import android.content.Context
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import eu.opennote.data.OpenNoteRepository
import eu.opennote.data.PreferencesAffichage
import eu.opennote.data.TokenStore
import eu.opennote.sync.SyncNotifier
import eu.opennote.sync.SyncScheduler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/**
 * Conteneur de dépendances, construit à la main.
 *
 * L'application a cinq objets à durée de vie processus et aucun graphe
 * d'injection à démêler : Hilt coûterait plus cher qu'il ne rapporterait.
 */
class AppContainer(context: Context) {

    val tokenStore = TokenStore(context)

    /** Réglages d'affichage : l'ordre de tri de la liste de notes. */
    val preferencesAffichage = PreferencesAffichage(context)

    /**
     * `filesDir` est le stockage privé de l'application : c'est là que le cœur
     * Go pose son cache et sa configuration. La configuration ne contient
     * aucun secret — un test Go le vérifie.
     */
    val repository = OpenNoteRepository(
        dataDir = context.filesDir.absolutePath,
        tokenStore = tokenStore,
        preferences = preferencesAffichage,
    )

    val syncScheduler = SyncScheduler(context)

    val syncNotifier = SyncNotifier(context)

    /**
     * Portée qui survit aux ViewModels.
     *
     * Un seul usage, et il compte : vider le tampon de l'éditeur quand l'écran
     * disparaît. `viewModelScope` est déjà annulé à ce moment-là, et une
     * frappe des dernières secondes serait perdue.
     */
    val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
}

class OpenNoteApplication : Application() {

    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        container.syncNotifier.ensureChannel()
        container.syncScheduler.schedulePeriodic()

        // Retour au premier plan : c'est le moment où l'utilisateur va
        // regarder ses notes, donc celui où il faut avoir poussé les
        // modifications faites hors connexion.
        ProcessLifecycleOwner.get().lifecycle.addObserver(ForegroundSyncObserver(container))
    }
}

/**
 * Déclenche une passe de synchronisation au retour au premier plan.
 *
 * `onStart` du `ProcessLifecycleOwner` ne se déclenche qu'une fois par retour
 * de l'application entière, pas à chaque activité : une rotation d'écran ne le
 * réveille pas.
 */
private class ForegroundSyncObserver(
    private val container: AppContainer,
) : DefaultLifecycleObserver {

    override fun onStart(owner: LifecycleOwner) {
        container.syncScheduler.syncNow()
    }
}

/** Raccourci vers le conteneur depuis n'importe quel [Context]. */
val Context.appContainer: AppContainer
    get() = (applicationContext as OpenNoteApplication).container
