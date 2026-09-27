package eu.ocnotes

import android.app.Application
import android.content.Context
import android.util.Log
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import eu.ocnotes.data.AccountRegistry
import eu.ocnotes.data.AccountProfile
import eu.ocnotes.data.OCnotesRepository
import eu.ocnotes.data.PreferencesAffichage
import eu.ocnotes.data.TokenStore
import eu.ocnotes.data.auth.OidcManager
import eu.ocnotes.diagnostic.CrashReporter
import eu.ocnotes.sync.SyncNotifier
import eu.ocnotes.sync.SyncScheduler
import eu.ocnotes.ui.common.nettoyerPartagesExpires
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.util.concurrent.ConcurrentHashMap

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

    private val context = context.applicationContext
    val accountRegistry = AccountRegistry(this.context.filesDir)
    val oidcManager = OidcManager(context)

    /** Réglages d'affichage : l'ordre de tri de la liste de notes. */
    val preferencesAffichage = PreferencesAffichage(this.context)

    val syncNotifier = SyncNotifier(this.context)

    /**
     * Ce qu'un profil possède en propre. Il n'en existe **qu'un par profil**
     * pour toute la vie du processus : deux cœurs Go ouverts sur le même
     * dossier réécrivent chacun l'index entier depuis leur mémoire, et le
     * dernier qui enregistre efface la file d'attente de l'autre.
     */
    private class AccountRuntime(
        val tokenStore: TokenStore,
        val repository: OCnotesRepository,
        val syncScheduler: SyncScheduler,
    )

    private val runtimes = ConcurrentHashMap<String, AccountRuntime>()

    @Volatile
    private var activeId = accountRegistry.active.id
    private val switchMutex = Mutex()
    private val accountLocks = ConcurrentHashMap<String, Mutex>()
    data class ActiveSession(val profile: AccountProfile, val generation: Long)

    private var generation = 0L
    private val mutableActiveSession = MutableStateFlow(ActiveSession(accountRegistry.active, generation))
    val activeSession: StateFlow<ActiveSession> = mutableActiveSession.asStateFlow()

    val tokenStore: TokenStore get() = runtimeFor(activeId).tokenStore
    val repository: OCnotesRepository get() = runtimeFor(activeId).repository
    val syncScheduler: SyncScheduler get() = runtimeFor(activeId).syncScheduler

    /**
     * Le runtime du profil, créé au premier besoin puis gardé. Sa construction
     * ne touche ni au disque ni au réseau : le cœur Go ne s'ouvre qu'au premier
     * appel du dépôt.
     */
    private fun runtimeFor(id: String): AccountRuntime = runtimes.computeIfAbsent(id) {
        val tokenStore = TokenStore(context, id)
        AccountRuntime(
            tokenStore = tokenStore,
            repository = OCnotesRepository(
                dataDir = accountRegistry.profileDir(id).absolutePath,
                accountId = id,
                accountRegistry = accountRegistry,
                tokenStore = tokenStore,
                oidcManager = oidcManager,
                preferences = preferencesAffichage,
            ),
            syncScheduler = SyncScheduler(context, id),
        )
    }

    /**
     * Fournit au Worker le dépôt du profil demandé, actif ou non — le même que
     * celui des écrans, jamais un second. Le verrou reste acquis pendant toute
     * la passe : une suppression du même profil attend donc sa fin.
     */
    suspend fun <T> withAccountRepository(
        accountId: String,
        operation: suspend (OCnotesRepository) -> T,
    ): T? = accountLock(accountId).withLock {
        if (accountRegistry.accounts.none { it.id == accountId }) return@withLock null
        operation(runtimeFor(accountId).repository)
    }

    /** Installe le travail périodique de chaque compte serveur enregistré. */
    fun scheduleAllAccounts() {
        accountRegistry.accounts
            .filter { it.kind != "local" && it.serverUrl.isNotBlank() }
            .forEach { runtimeFor(it.id).syncScheduler.schedulePeriodic() }
    }

    private fun accountLock(id: String): Mutex = accountLocks.computeIfAbsent(id) { Mutex() }

    /**
     * Change de profil sans effacer la session, le cache ou le secret quittés.
     * N'attend aucune passe : le profil repris garde son dépôt, passe en cours
     * comprise.
     */
    suspend fun activateAccount(id: String) = switchMutex.withLock {
        if (id == activeId) return@withLock
        val profile = accountRegistry.activate(id)
        afficher(profile)
    }

    /** Ajoute un emplacement de compte vierge et l'ouvre sur la connexion. */
    suspend fun createAccount() = switchMutex.withLock {
        afficher(accountRegistry.createAndActivate())
    }

    /**
     * Supprime secret, cache, configuration et travaux du profil.
     *
     * L'ordre compte. Le cœur Go est vidé d'abord : `Disconnect` annule et
     * attend une passe en cours, et un échec à ce stade laisse le profil
     * intact. Le registre ensuite, puis l'interface quitte le profil. Le
     * dossier en dernier : son effacement est un ménage, et un échec n'y doit
     * pas faire croire que la suppression a échoué.
     */
    suspend fun deleteAccount(id: String) = switchMutex.withLock {
        accountLock(id).withLock {
            require(accountRegistry.accounts.any { it.id == id }) { "Profil inconnu" } // i18n-ok
            val supprime = runtimeFor(id)
            supprime.syncScheduler.cancelAll()
            supprime.repository.disconnect()
            runCatching { supprime.tokenStore.destroy() }

            val suivant = accountRegistry.remove(id)
            runtimes.remove(id)
            if (id == activeId) afficher(suivant)

            val dossier = accountRegistry.profileDir(id)
            withContext(Dispatchers.IO) { runCatching { dossier.deleteRecursively() } }
        }
        accountLocks.remove(id)
    }

    /** Fait du profil celui de l'interface. Appelé sous [switchMutex]. */
    private fun afficher(profile: AccountProfile) {
        activeId = profile.id
        runtimeFor(profile.id).syncScheduler.schedulePeriodic()
        generation += 1
        mutableActiveSession.value = ActiveSession(profile, generation)
    }

    private val mutableEchecCompte = MutableStateFlow(false)

    /** Vrai quand le dernier geste sur les comptes a échoué ; l'interface le signale. */
    val echecCompte: StateFlow<Boolean> = mutableEchecCompte.asStateFlow()

    fun acquitterEchecCompte() {
        mutableEchecCompte.value = false
    }

    /**
     * Exécute un geste du tiroir — activer, ajouter, supprimer un compte.
     *
     * Dans [applicationScope] et non dans une portée d'écran : le geste change
     * la génération affichée, donc détruit la composition qui l'a lancé, et une
     * suppression interrompue à mi-chemin laisserait un dossier orphelin. Une
     * exception y est rattrapée : laissée filer, elle tuerait le processus.
     */
    fun lancerGesteCompte(geste: suspend AppContainer.() -> Unit) {
        applicationScope.launch {
            try {
                geste()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Le type seul : un message peut porter un chemin ou une URL.
                Log.w(TAG, "geste de compte abandonné (${e.javaClass.simpleName})") // i18n-ok
                mutableEchecCompte.value = true
            }
        }
    }

    /**
     * Où « Partager » dépose ses copies au vrai nom, le temps de l'envoi. Seul
     * dossier exposé par le `FileProvider` (`res/xml/chemins_partage.xml`).
     */
    val dossierPartage = File(context.cacheDir, "partage")

    init {
        if (accountRegistry.vientDeMigrer) syncScheduler.annulerTravauxSansProfil()
    }

    /**
     * Portée qui survit aux ViewModels.
     *
     * Un seul usage, et il compte : vider le tampon de l'éditeur quand l'écran
     * disparaît. `viewModelScope` est déjà annulé à ce moment-là, et une
     * frappe des dernières secondes serait perdue.
     */
    val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private companion object {
        const val TAG = "OCnotes"
    }
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
        container.scheduleAllAccounts()

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
