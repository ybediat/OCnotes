package eu.ocnotes

import android.app.Application
import android.content.ContentResolver
import android.content.Context
import android.util.Log
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import eu.ocnotes.data.AccountRegistry
import eu.ocnotes.data.AccountProfile
import eu.ocnotes.data.AppMode
import eu.ocnotes.data.DestinationCopie
import eu.ocnotes.data.EnjeuSuppression
import eu.ocnotes.data.NoteRefDto
import eu.ocnotes.data.OCnotesException
import eu.ocnotes.data.RestoreOutcome
import eu.ocnotes.data.ResultatCopie
import eu.ocnotes.data.OCnotesRepository
import eu.ocnotes.data.PreferencesAffichage
import eu.ocnotes.data.TokenStore
import eu.ocnotes.data.syncEnabled
import eu.ocnotes.data.vierge
import eu.ocnotes.data.auth.OidcManager
import eu.ocnotes.diagnostic.CrashReporter
import eu.ocnotes.sync.SyncNotifier
import eu.ocnotes.sync.SyncScheduler
import eu.ocnotes.ui.common.nettoyerPartagesExpires
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
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
        val profile = accountRegistry.accounts.firstOrNull { profile -> profile.id == id }
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
            syncScheduler = SyncScheduler(
                context,
                id,
                initialServerEnabled = profile?.syncEnabled == true,
            ),
        )
    }

    /**
     * Fournit au Worker le dépôt du profil demandé, actif ou non — le même que
     * celui des écrans, jamais un second. Le verrou reste acquis pendant toute
     * la passe : une suppression du même profil attend donc sa fin.
     *
     * [onAttente] est appelé, une fois et avant d'attendre, si le verrou est
     * déjà pris : l'appelant qui a une interface peut dire pourquoi rien ne se
     * passe encore.
     */
    suspend fun <T> withAccountRepository(
        accountId: String,
        onAttente: () -> Unit = {},
        operation: suspend (OCnotesRepository) -> T,
    ): T? {
        val verrou = accountLock(accountId)
        if (!verrou.tryLock()) {
            onAttente()
            verrou.lock()
        }
        return try {
            if (accountRegistry.accounts.none { it.id == accountId }) {
                null
            } else {
                operation(runtimeFor(accountId).repository)
            }
        } finally {
            verrou.unlock()
        }
    }

    /**
     * Lit dans le dépôt d'un profil sans attendre la fin d'une passe.
     *
     * Verrou libre : la lecture le prend, comme [withAccountRepository]. Verrou
     * pris — une passe, ou une suppression — : le runtime existe donc déjà, et
     * la lecture se fait à côté, comme l'écran du profil actif lit pendant que
     * le Worker synchronise. Jamais de [runtimeFor] sans verrou : il
     * recréerait le runtime d'un profil en cours de suppression. Une lecture
     * qui croise une suppression échoue ; l'appelant doit le tolérer.
     *
     * Réservé aux lectures. Une écriture passe par [withAccountRepository].
     */
    private suspend fun <T> lireSansAttendre(
        accountId: String,
        lecture: suspend (OCnotesRepository) -> T,
    ): T? {
        val verrou = accountLock(accountId)
        if (verrou.tryLock()) {
            try {
                if (accountRegistry.accounts.none { it.id == accountId }) return null
                return lecture(runtimeFor(accountId).repository)
            } finally {
                verrou.unlock()
            }
        }
        val runtime = runtimes[accountId] ?: return null
        return lecture(runtime.repository)
    }

    /**
     * Ce que la suppression du profil détruirait : toutes ses notes s'il est
     * local, sinon les écritures, créations et renommages jamais envoyés. Lu
     * dans le cœur Go du profil, actif ou non, sans réseau. Sans verrou de
     * compte — il reste tenu pendant toute une passe, et le dialogue attendrait
     * la fin d'une synchronisation.
     *
     * Le mode vient du cœur, pas du registre : `recordLocal` n'est qu'un
     * nettoyage au mieux, et un registre reconstruit sans `config.json` classe
     * un profil local parmi les serveurs vierges. Or un profil local n'a jamais
     * rien en file — le registre seul l'aurait laissé supprimer sans la case.
     *
     * `null` si la lecture échoue : l'appelant doit alors supposer le pire.
     */
    suspend fun enjeuSuppression(id: String): EnjeuSuppression? =
        runCatching { runtimeFor(id).repository.state() }.getOrNull()
            ?.let { EnjeuSuppression(local = it.mode == AppMode.LOCAL, enAttente = it.pending) }

    /**
     * Les autres profils qui peuvent recevoir une copie, avec leurs dossiers.
     *
     * Chacun est remonté sans réseau (`restore`) : un profil sans session — un
     * emplacement vierge, un serveur sans espace choisi — n'est pas une
     * destination. Lu par [lireSansAttendre] : un dialogue ne doit pas tourner
     * jusqu'à la fin de la synchronisation d'un autre compte. `restore` n'y
     * écrit qu'en ouvrant une session, et un profil dont une passe tient le
     * verrou a déjà la sienne.
     */
    suspend fun destinationsCopie(): List<DestinationCopie> =
        accountRegistry.accounts.filter { it.id != activeId }.mapNotNull { profil ->
            try {
                lireSansAttendre(profil.id) { cible ->
                    when (cible.restore()) {
                        RestoreOutcome.LOCALE, RestoreOutcome.PRETE ->
                            cible.folders().filter { it.canCreateFile }
                                .takeIf { it.isNotEmpty() }
                                ?.let { DestinationCopie(profil, it) }
                        else -> null
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: OCnotesException) {
                null
            }
        }

    /**
     * Copie des notes de [source] vers un autre profil, sans rien supprimer.
     *
     * Lecture par le dépôt source, création par celui du profil cible
     * (`runtimeFor`) : jamais un cœur Go partagé. Chaque copie est relue dans la
     * cible et comparée au texte lu à la source avant d'être comptée ; une
     * copie qui diffère est un échec, pas un succès à moitié. Un nom pris reçoit
     * un suffixe, comme une création ordinaire : aucune note de la cible n'est
     * écrasée.
     *
     * Le texte passe par `ReadNote` : fins de ligne ramenées à « \n » et
     * encodage UTF-8 dans la copie, ce qui ne change pas ce qu'on lit. Le nom
     * passe entier, extension comprise : `CreateNoteJSON` garde une extension
     * modifiable, et un `.txt` reste un `.txt` — l'utilisateur a demandé une
     * copie, pas une conversion (même principe que `notes.WithExtensionOf`).
     *
     * Le travail tourne dans [applicationScope], pas dans la portée de
     * l'appelant : changer de compte vide le `ViewModelStore` de l'écran, et
     * c'est le geste naturel juste après une copie — aller voir dans la cible.
     * Annulée, la copie s'arrêtait au milieu, sans message ni synchronisation
     * programmée ; relancée, elle produisait des doublons « (2) ». L'appelant
     * annulé ne reçoit pas le bilan ; la copie, elle, va au bout.
     *
     * [onAttente] signale que le profil cible est occupé — une passe de
     * synchronisation tient son verrou — et que la copie attend sa fin.
     */
    suspend fun copierVersCompte(
        source: OCnotesRepository,
        chemins: List<String>,
        destinationId: String,
        dossier: String,
        onAttente: () -> Unit = {},
    ): ResultatCopie = applicationScope.async {
        var copiees = 0
        var echecs = 0
        var premiere: OCnotesException? = null
        val fait = withAccountRepository(destinationId, onAttente) { cible ->
            try {
                for (chemin in chemins) {
                    try {
                        val texte = source.readNote(chemin)
                        val nom = chemin.substringAfterLast('/')
                        val copie = creerCopie(cible, dossier, nom, texte)
                        if (cible.readNote(copie.path) == texte) {
                            copiees++
                        } else {
                            echecs++
                        }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: OCnotesException) {
                        echecs++
                        if (premiere == null) premiere = e
                    }
                }
            } finally {
                // Même interrompue, une copie déjà écrite doit partir.
                if (copiees > 0) runtimeFor(destinationId).syncScheduler.syncAfterLocalChange()
            }
        }
        if (fait == null) {
            ResultatCopie(0, chemins.size, null, cibleDisparue = true)
        } else {
            ResultatCopie(copiees, echecs, premiere)
        }
    }.await()

    /**
     * Crée la copie d'une note dans [cible].
     *
     * Le serveur accepte des noms que la création refuse (`? * : < > |`, point
     * initial…) : une note lisible ne doit pas devenir impossible à copier. Sur
     * un refus de nom, le nom passe par `SuggestName` puis la création est
     * retentée une fois. Jamais avant : `SanitizeName` n'est pas idempotente sur
     * un nom valide (carnet : `EXPORT-ZIP`), elle changerait des noms sains.
     */
    private suspend fun creerCopie(
        cible: OCnotesRepository,
        dossier: String,
        nom: String,
        texte: String,
    ): NoteRefDto = try {
        cible.createNote(dossier, nom, texte)
    } catch (e: OCnotesException) {
        if (!e.code.startsWith("NAME_")) throw e
        val assaini = cible.suggestName(nom)
        if (assaini == nom) throw e
        cible.createNote(dossier, assaini, texte)
    }

    /** Installe le travail périodique de chaque compte serveur enregistré. */
    fun scheduleAllAccounts() {
        accountRegistry.accounts
            .filter { it.syncEnabled }
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

    private val mutableCompteDejaPresent = MutableStateFlow(false)

    /** Vrai quand une connexion a été ramenée vers un profil existant ; l'interface le signale. */
    val compteDejaPresent: StateFlow<Boolean> = mutableCompteDejaPresent.asStateFlow()

    fun acquitterCompteDejaPresent() {
        mutableCompteDejaPresent.value = false
    }

    /**
     * Une connexion a révélé une identité déjà enregistrée : on rouvre le profil
     * qui la porte et on retire le profil vide qui venait d'être créé.
     *
     * Deux étapes séquentielles, jamais imbriquées : [switchMutex] n'est pas
     * réentrant. On active d'abord, pour que la suppression n'ait pas à choisir
     * elle-même le profil suivant.
     */
    suspend fun adopterCompteExistant(existantId: String, nouveauId: String) {
        activateAccount(existantId)
        // Seul un profil vierge part sans qu'on l'ait demandé : la suppression
        // n'a pas de retour. Un autre — profil serveur dont la configuration a
        // été perdue, par exemple — reste dans le tiroir, où l'utilisateur le
        // supprimera lui-même, avec la confirmation d'usage, s'il le veut.
        //
        // « Vierge » au registre ne suffit pas, il faut aussi un cœur sans rien
        // en file. Un profil local qui a perdu sa configuration et son registre
        // ensemble passe au registre pour vierge, et la connexion qui mène ici
        // vient d'adopter ses notes (`connectClient`) : elles sont en file, et
        // nulle part ailleurs.
        val vierge = accountRegistry.accounts.firstOrNull { it.id == nouveauId }?.vierge == true
        val enjeu = enjeuSuppression(nouveauId)
        if (vierge && enjeu != null && !enjeu.local && enjeu.enAttente == 0) {
            deleteAccount(nouveauId)
        }
        mutableCompteDejaPresent.value = true
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
        runtimeFor(profile.id).syncScheduler.setServerEnabled(profile.syncEnabled)
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

    /**
     * Où Go écrit l'archive d'export avant qu'elle soit copiée vers
     * l'emplacement choisi. **Pas** `partage/` : c'est le seul dossier que le
     * `FileProvider` expose, et l'archive contient toutes les notes en clair.
     */
    val dossierExport = File(context.cacheDir, "export")

    val contentResolver: ContentResolver get() = context.contentResolver

    init {
        if (accountRegistry.vientDeMigrer) syncScheduler.annulerTravauxSansProfil()
    }

    /**
     * Portée qui survit aux ViewModels.
     *
     * Pour ce qu'un écran qui disparaît ne doit pas interrompre : vider le
     * tampon de l'éditeur — `viewModelScope` est déjà annulé à ce moment-là, et
     * une frappe des dernières secondes serait perdue —, un geste sur les
     * comptes, une copie vers un autre compte.
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
