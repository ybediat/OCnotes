package eu.ocnotes

import android.app.Application
import android.content.Context
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import eu.ocnotes.data.AccountRegistry
import eu.ocnotes.data.OCnotesRepository
import eu.ocnotes.data.PreferencesAffichage
import eu.ocnotes.data.TokenStore
import eu.ocnotes.data.auth.OidcManager
import eu.ocnotes.diagnostic.CrashReporter
import eu.ocnotes.sync.SyncNotifier
import eu.ocnotes.sync.SyncScheduler
import eu.ocnotes.ui.common.nettoyerPartagesExpires
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.io.File

/**
 * Conteneur de dépendances, construit à la main.
 *
 * L'application a quelques objets à durée de vie processus et aucun graphe
 * d'injection à démêler : Hilt coûterait plus cher qu'il ne rapporterait.
 */
class AppContainer(
    context: Context,
    val crashReporter: CrashReporter,
) {

    val accountRegistry = AccountRegistry(context.filesDir)
    val activeAccount = accountRegistry.active
    val tokenStore = TokenStore(context, activeAccount.id)
    val oidcManager = OidcManager(context)

    /** Réglages d'affichage : l'ordre de tri de la liste de notes. */
    val preferencesAffichage = PreferencesAffichage(context)

    /**
     * Chaque profil possède un sous-dossier privé de `filesDir` : le cœur Go
     * n'ouvre que le cache et la configuration du profil actif. La
     * configuration ne contient aucun secret — un test Go le vérifie.
     */
    val repository = OCnotesRepository(
        dataDir = accountRegistry.profileDir().absolutePath,
        accountRegistry = accountRegistry,
        tokenStore = tokenStore,
        oidcManager = oidcManager,
        preferences = preferencesAffichage,
    )

    /**
     * Où « Partager » dépose ses copies au vrai nom, le temps de l'envoi. Seul
     * dossier exposé par le `FileProvider` (`res/xml/chemins_partage.xml`).
     */
    val dossierPartage = File(context.cacheDir, "partage")

    val syncScheduler = SyncScheduler(context, activeAccount.id).also {
        if (accountRegistry.vientDeMigrer) it.annulerTravauxSansProfil()
    }

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

class OCnotesApplication : Application() {

    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        // Premier composant installé : il couvre aussi un échec pendant la
        // construction du dépôt Go ou des autres objets du processus.
        val crashReporter = CrashReporter.install(this)
        container = AppContainer(this, crashReporter)
        container.applicationScope.launch(Dispatchers.IO) {
            nettoyerPartagesExpires(container.dossierPartage)
        }
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
    get() = (applicationContext as OCnotesApplication).container
