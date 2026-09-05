package eu.ocnotes.data

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import mobile.Mobile
import mobile.App as GoApp

/**
 * Issue d'une tentative de remontée de session hors ligne.
 */
enum class RestoreOutcome {
    /** Rien d'enregistré, ou pas de token : écran de connexion. */
    AUCUNE_SESSION,

    /** Bibliothèque montée, l'utilisateur peut aller à ses notes. */
    PRETE,

    /** Le stockage local est prêt ; aucun token ni serveur n'est nécessaire. */
    LOCALE,

    /**
     * Un compte est enregistré mais aucun espace n'a été choisi. Il faut
     * `Connect` puis la sélection d'espace — et donc du réseau.
     */
    SANS_ESPACE,
}

/** Issue de la validation en ligne du token. */
enum class ValidationSession {
    VALIDEE,

    /** Le serveur a refusé le token : il faut une nouvelle saisie. */
    TOKEN_REFUSE,

    /** Serveur injoignable. Sans conséquence : le cache prend le relais. */
    HORS_LIGNE,
}

/**
 * Seul point de contact avec la façade Go.
 *
 * Trois responsabilités, et rien d'autre :
 *
 *  - **basculer sur [Dispatchers.IO]** — tous les appels de la façade sont
 *    bloquants, y compris ceux qui parlent au réseau ;
 *  - **traduire le JSON** en objets Kotlin (voir `Dto.kt`) ;
 *  - **normaliser les erreurs** en [OCnotesException], catégorie comprise.
 *
 * Aucune règle métier ici : elle vit en Go, sous `internal/`, où elle est
 * testée. Aucun ViewModel ne touche `mobile.App` directement.
 */
class OCnotesRepository(
    private val dataDir: String,
    private val tokenStore: TokenStore,
    private val preferences: PreferencesAffichage,
) {

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    private val initMutex = Mutex()
    private val sessionMutex = Mutex()
    /** Empêche une passe WorkManager de chevaucher une transition de stockage. */
    private val operationMutex = Mutex()

    @Volatile
    private var goApp: GoApp? = null

    /**
     * Vrai quand la façade Go est utilisable : bibliothèque distante montée,
     * ou Store ouvert comme stockage définitif en mode local.
     *
     * Distinct de `AppStateDto.connected`, qui signifie seulement qu'un
     * serveur et un compte sont enregistrés : après un redémarrage, il faut
     * toujours repasser le token à la façade.
     */
    @Volatile
    var sessionOpen: Boolean = false
        private set

    private val _sessionValidee = MutableStateFlow(false)

    /**
     * Vrai une fois le token vérifié auprès du serveur par `Connect`.
     *
     * `Restore` ouvre une session utilisable sans réseau, mais sans preuve que
     * le token soit encore bon. Les écrans surveillent ce drapeau pour se
     * rafraîchir quand la validation finit par aboutir.
     */
    val sessionValidee: StateFlow<Boolean> = _sessionValidee.asStateFlow()

    private val _pendingCount = MutableStateFlow(0)

    /** Nombre d'opérations en attente, observable par les écrans. */
    val pendingCount: StateFlow<Int> = _pendingCount.asStateFlow()

    private val _lastSync = MutableStateFlow<SyncResultDto?>(null)

    /** Résultat de la dernière passe de synchronisation, quelle qu'en soit l'issue. */
    val lastSync: StateFlow<SyncResultDto?> = _lastSync.asStateFlow()

    private val _sessionExpired = MutableStateFlow(false)

    private val _mode = MutableStateFlow(AppMode.UNSET)

    /** Mode courant, partagé par les écrans qui doivent adapter leurs gestes. */
    val mode: StateFlow<String> = _mode.asStateFlow()

    /**
     * Passe à vrai quand le serveur a refusé le token en arrière-plan.
     *
     * L'interface le surveille pour ramener l'utilisateur à l'écran de
     * connexion : un token expiré ne se répare pas en réessayant.
     */
    val sessionExpired: StateFlow<Boolean> = _sessionExpired.asStateFlow()

    /** Marque la session comme perdue. Ne touche pas au token enregistré :
     * l'écran de connexion le repropose, le nom d'utilisateur et l'URL avec. */
    fun invalidateSession() {
        sessionOpen = false
        _sessionValidee.value = false
        _sessionExpired.value = true
    }

    /** Acquitte le signal, une fois l'utilisateur ramené à l'écran de connexion. */
    fun acknowledgeSessionExpired() {
        _sessionExpired.value = false
    }

    // --- Plomberie ---------------------------------------------------------

    /**
     * Ouvre l'application Go, une seule fois pour la vie du processus.
     *
     * `NewApp` lit le cache et la configuration sur disque : c'est du travail
     * bloquant, jamais du réseau.
     */
    private suspend fun app(): GoApp {
        goApp?.let { return it }
        return initMutex.withLock {
            goApp ?: withContext(Dispatchers.IO) {
                try {
                    Mobile.newApp(dataDir)
                } catch (t: Throwable) {
                    throw OCnotesException.from(t)
                }
            }.also {
                // Le réglage appartient aux préférences Android, jamais au
                // compte. Il est appliqué dès l'ouverture du cache.
                // Un cache déjà rempli de brouillons peut rester au-dessus
                // d'un quota abaissé : l'application doit tout de même
                // démarrer pour laisser l'utilisateur synchroniser ce travail.
                runCatching { it.setCacheQuota(preferences.quotaCache.value) }
                goApp = it
            }
        }
    }

    /** Exécute un appel de la façade hors du thread principal, erreur normalisée. */
    private suspend fun <T> call(block: (GoApp) -> T): T {
        val instance = app()
        return withContext(Dispatchers.IO) {
            try {
                block(instance)
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                throw OCnotesException.from(t)
            }
        }
    }

    // --- État et session ---------------------------------------------------

    suspend fun state(): AppStateDto {
        val result: AppStateDto = json.decodeFromString(call { it.stateJSON() })
        _mode.value = result.mode
        return result
    }

    suspend fun cacheState(): CacheStateDto =
        json.decodeFromString(call { it.cacheStateJSON() })

    suspend fun setCacheQuota(quota: Long) {
        call { it.setCacheQuota(quota) }
    }

    suspend fun pruneCache() {
        call { it.pruneCache() }
    }

    /** Choisit le stockage exclusivement local au premier lancement. */
    suspend fun startLocal() {
        call { it.startLocal() }
        retainCoreQuota()
        tokenStore.clear()
        sessionOpen = true
        _sessionValidee.value = false
        _sessionExpired.value = false
        _pendingCount.value = 0
        _mode.value = AppMode.LOCAL
    }

    /**
     * Ouvre une session et enregistre le token.
     *
     * `Connect` fait un appel réseau réel pour valider les identifiants : une
     * erreur `AUTH` signifie que le token est refusé, une erreur `HTTP` que le
     * serveur n'a pas répondu. Le token n'est écrit qu'en cas de succès.
     */
    suspend fun connect(serverUrl: String, username: String, appToken: String) {
        val token = appToken.trim()
        call { it.connect(serverUrl.trim(), username.trim(), token) }
        tokenStore.saveAppToken(token)
        sessionOpen = true
        _sessionValidee.value = true
        _sessionExpired.value = false
        refreshPending()
    }

    /**
     * Remonte la session depuis la configuration, **sans aucun appel réseau**.
     *
     * C'est le chemin de démarrage normal, et il fonctionne hors connexion :
     * `Restore` reconstruit le client et la bibliothèque à partir de ce que la
     * configuration a retenu, sans rien demander au serveur. L'utilisateur
     * ouvre ses notes dans le métro.
     *
     * Ne jamais démarrer par [connect] : celui-ci valide les identifiants en
     * ligne, échoue sans réseau, et laisse alors la bibliothèque nulle — ce
     * qui fait échouer jusqu'aux appels capables de se replier sur le cache.
     */
    suspend fun restore(): RestoreOutcome = sessionMutex.withLock {
        if (sessionOpen) {
            return@withLock if (_mode.value == AppMode.LOCAL) {
                RestoreOutcome.LOCALE
            } else {
                RestoreOutcome.PRETE
            }
        }

        val current = state()
        if (current.mode == AppMode.LOCAL) {
            // NewApp a déjà rouvert le Store persistant. Le mode local n'a ni
            // bibliothèque distante à monter, ni secret à récupérer.
            sessionOpen = true
            _sessionValidee.value = false
            _sessionExpired.value = false
            _pendingCount.value = 0
            return@withLock RestoreOutcome.LOCALE
        }
        val token = tokenStore.appToken()
        if (!current.connected || token == null) return@withLock RestoreOutcome.AUCUNE_SESSION

        try {
            call { it.restore(token) }
            sessionOpen = true
            _sessionExpired.value = false
            refreshPending()
            RestoreOutcome.PRETE
        } catch (e: CancellationException) {
            throw e
        } catch (_: OCnotesException) {
            // Aucun réseau n'entre en jeu ici : le seul échec possible est
            // l'absence d'espace enregistré. Il faut alors passer par Connect
            // puis la sélection d'espace.
            RestoreOutcome.SANS_ESPACE
        }
    }

    /**
     * Valide le token auprès du serveur, en arrière-plan.
     *
     * Appelé après un [restore] réussi. Un refus `AUTH` est le seul cas qui
     * mérite d'interrompre l'utilisateur ; une panne réseau se laisse ignorer,
     * l'application reste utilisable sur le cache.
     *
     * Un `Connect` réussi remonte aussi la bibliothèque côté Go : les listings
     * suivants repartent du serveur.
     */
    suspend fun validerSession(): ValidationSession {
        val current = state()
        val token = tokenStore.appToken() ?: return ValidationSession.TOKEN_REFUSE

        return try {
            call { it.connect(current.serverUrl, current.username, token) }
            sessionOpen = true
            _sessionValidee.value = true
            _sessionExpired.value = false
            refreshPending()
            ValidationSession.VALIDEE
        } catch (e: CancellationException) {
            throw e
        } catch (e: OCnotesException) {
            if (e.category == ErrorCategory.AUTH) {
                invalidateSession()
                ValidationSession.TOKEN_REFUSE
            } else {
                ValidationSession.HORS_LIGNE
            }
        }
    }

    /**
     * Garantit que la bibliothèque est montée, sans propager d'échec.
     *
     * Forme adaptée aux tâches de fond. Elle ne passe pas par `Connect` : la
     * synchronisation n'a besoin que d'une bibliothèque montée, et `Restore`
     * la lui donne sans aller-retour réseau supplémentaire.
     */
    suspend fun ensureSession(): Boolean = try {
        restore() == RestoreOutcome.PRETE
    } catch (e: CancellationException) {
        throw e
    } catch (_: OCnotesException) {
        false
    }

    /**
     * Efface session, configuration, cache **et** token.
     *
     * Le token part après `disconnect()` : si l'effacement côté Go échoue, on
     * n'a pas laissé une configuration orpheline sans secret pour s'y
     * reconnecter.
     */
    suspend fun disconnect() {
        operationMutex.withLock {
            call { it.disconnect() }
            tokenStore.clear()
            sessionOpen = false
            _sessionValidee.value = false
            _pendingCount.value = 0
            _lastSync.value = null
            _mode.value = AppMode.UNSET
        }
    }

    // --- Espaces -----------------------------------------------------------

    suspend fun listDrives(): List<DriveDto> =
        json.decodeFromString(call { it.listDrivesJSON() })

    suspend fun selectWorkspace(driveId: String, root: String) {
        call { it.selectWorkspace(driveId, root) }
    }

    /** Termine le branchement commencé par [connect] depuis le mode local. */
    suspend fun attach(driveId: String, root: String, adopt: Boolean): AttachResultDto {
        return operationMutex.withLock {
            val request = json.encodeToString(AttachRequestDto(driveId, root, adopt))
            val result: AttachResultDto = json.decodeFromString(call { it.attachJSON(request) })
            sessionOpen = true
            _sessionValidee.value = true
            _sessionExpired.value = false
            _mode.value = AppMode.SERVER
            refreshPending()
            result
        }
    }

    suspend fun detachPlan(): DetachPlanDto = operationMutex.withLock {
        json.decodeFromString(call { it.detachPlanJSON() })
    }

    suspend fun downloadBatch(max: Int = 25): DownloadReportDto = operationMutex.withLock {
        json.decodeFromString(call { it.downloadBatchJSON(max.toLong()) })
    }

    /** Coupe le serveur une fois les écritures poussées et les contenus rapatriés. */
    suspend fun detach(): DetachResultDto {
        return operationMutex.withLock {
            val result: DetachResultDto = json.decodeFromString(call { it.detachJSON() })
            retainCoreQuota()
            tokenStore.clear()
            sessionOpen = true
            _sessionValidee.value = false
            _sessionExpired.value = false
            _pendingCount.value = 0
            _lastSync.value = null
            _mode.value = AppMode.LOCAL
            result
        }
    }

    /**
     * Le cœur relève le seuil à l'entrée en mode local. Sans recopier cette
     * valeur, le prochain démarrage réappliquerait l'ancienne préférence
     * Android (250 Mio par défaut) et annulerait silencieusement ce garde-fou.
     */
    private suspend fun retainCoreQuota() {
        val adjusted = cacheState().quota
        if (preferences.quotaCache.value != adjusted) {
            preferences.definirQuotaCache(adjusted)
        }
    }

    /** Nom de dossier proposé au premier démarrage (`Notes`). */
    suspend fun defaultRoot(): String = withContext(Dispatchers.IO) { Mobile.defaultRoot() }

    // --- Navigation --------------------------------------------------------

    /**
     * Contenu d'un dossier, dossiers d'abord — l'ordre vient de Go, on ne le
     * retrie pas.
     *
     * Un `fromCache` à vrai signale un listing servi hors connexion.
     */
    suspend fun listFolder(dir: String): FolderListingDto =
        json.decodeFromString(call { it.listFolderJSON(dir) })

    /**
     * Inventaire complet, à plat : toutes les notes, aucun dossier.
     *
     * Le dossier de chaque note se lit dans son `path`, dont il est le
     * préfixe — la façade n'ajoute pas de champ pour ça.
     */
    suspend fun listAll(): FolderListingDto =
        json.decodeFromString(call { it.listAllJSON() })

    /**
     * Reconstruit l'inventaire sans rien renvoyer.
     *
     * Pour le travailleur de synchronisation : il vient de pousser, donc
     * l'inventaire du serveur a changé, mais personne ne regarde l'écran.
     * Un échec ne remonte pas — l'inventaire précédent reste valable, et une
     * synchronisation réussie ne doit pas être rapportée en échec pour ça.
     */
    suspend fun refreshIndex() {
        try {
            call { it.refreshIndex() }
        } catch (_: OCnotesException) {
            // Sans conséquence : le prochain affichage réessaiera.
        }
    }

    /**
     * Tous les dossiers connus, pour choisir une destination.
     *
     * Servi depuis le cache, sans réseau : un sélecteur doit s'ouvrir tout de
     * suite. La première entrée, de chemin vide, est le dossier de notes.
     */
    suspend fun folders(): List<FolderRefDto> =
        json.decodeFromString(call { it.foldersJSON() })

    suspend fun readNote(notePath: String): String = call { it.readNote(notePath) }

    /**
     * Enregistre une note.
     *
     * **Ne peut pas échouer faute de réseau** : l'écriture va dans le cache
     * local et l'opération est empilée dans une file persistée. Ne jamais
     * afficher d'erreur réseau ici — il n'y en a pas.
     */
    suspend fun writeNote(notePath: String, content: String) {
        call { it.writeNote(notePath, content) }
        refreshPending()
    }

    suspend fun refreshNote(notePath: String) {
        call { it.refreshNote(notePath) }
    }

    suspend fun createNote(dir: String, name: String, content: String): NoteRefDto =
        json.decodeFromString(call { it.createNoteJSON(dir, name, content) })

    suspend fun createFolder(dir: String, name: String): NoteRefDto =
        json.decodeFromString(call { it.createFolderJSON(dir, name) })

    /** Renomme et renvoie le **nouveau chemin**, que l'appelant doit adopter. */
    suspend fun rename(itemPath: String, newName: String): String =
        call { it.rename(itemPath, newName) }

    /** Déplace vers un autre dossier et renvoie le **nouveau chemin**. */
    suspend fun move(itemPath: String, targetDir: String): String =
        call { it.move(itemPath, targetDir) }

    /**
     * Duplique une note vers un autre dossier et renvoie la copie créée.
     *
     * Réservé aux notes côté façade : un dossier ou un document est refusé. Le
     * contenu copié est celui que l'utilisateur voit — sa version locale non
     * synchronisée le cas échéant. Une copie dans le dossier d'origine reçoit
     * un suffixe « (2) », comme une création qui bute sur un nom pris.
     */
    suspend fun copy(itemPath: String, targetDir: String): NoteRefDto =
        json.decodeFromString(call { it.copyJSON(itemPath, targetDir) })

    suspend fun delete(itemPath: String) {
        call { it.delete(itemPath) }
        refreshPending()
    }

    /** Transforme un titre saisi en nom de fichier valide. */
    suspend fun suggestName(title: String): String = call { it.suggestName(title) }

    /** Titre à afficher : celui écrit dans le contenu, sinon le nom du fichier. */
    suspend fun titleOf(name: String, content: String): String =
        call { it.titleOf(name, content) }

    // --- Synchronisation ---------------------------------------------------

    /**
     * Exécute une passe de synchronisation.
     *
     * **Une panne réseau n'est pas une exception** : elle arrive dans le champ
     * `error` du résultat, à côté de ce qui a tout de même été propagé. Seule
     * l'absence d'espace de travail fait lever.
     */
    suspend fun sync(): SyncResultDto {
        return operationMutex.withLock {
            val result: SyncResultDto = json.decodeFromString(call { it.syncJSON() })
            _lastSync.value = result
            _pendingCount.value = result.remaining
            result
        }
    }

    /** Conflits ouverts, conservés par le cœur Go au-delà d'une passe Sync. */
    suspend fun conflicts(): List<ConflictDto> =
        json.decodeFromString(call { it.conflictsJSON() })

    /** Applique une décision explicite, protégée côté Go par l'ETag mémorisé. */
    suspend fun resolveConflict(id: String, resolution: String): ResolveConflictResultDto {
        return operationMutex.withLock {
            val request = json.encodeToString(ResolveConflictRequestDto(id, resolution))
            json.decodeFromString(call { it.resolveConflictJSON(request) })
        }
    }

    suspend fun refreshPending() {
        _pendingCount.value = call { it.pendingCount().toInt() }
    }

    // --- Mise en forme -----------------------------------------------------

    /**
     * Liste des actions de la barre d'outils, dans l'ordre voulu par Go.
     *
     * L'interface ne code aucune action en dur : ajouter une action côté Go
     * suffit à la faire apparaître.
     */
    suspend fun formatActions(): List<FormatAction> {
        val ids: List<String> = json.decodeFromString(call { it.formatActionsJSON() })
        return ids.map(::FormatAction)
    }

    /**
     * Applique une action de mise en forme.
     *
     * [start] et [end] sont les bornes de `TextFieldValue.selection`, en
     * unités de code UTF-16, transmises **sans aucune conversion**. Le
     * résultat se réapplique de la même façon. Convertir en octets casserait
     * le curseur dès la première note accentuée : un « é » fait 2 octets mais
     * 1 unité UTF-16, un emoji en fait 4 pour 2 unités.
     *
     * Une sélection inversée (glissée de droite à gauche, `start > end`) est
     * remise à l'endroit par le cœur Go : rien à faire ici non plus.
     */
    suspend fun applyFormat(
        text: String,
        start: Int,
        end: Int,
        action: FormatAction,
    ): FormatResultDto {
        val request = json.encodeToString(
            FormatRequestDto(text = text, start = start, end = end, action = action.id),
        )
        return json.decodeFromString(call { it.applyFormatJSON(request) })
    }

    // --- Aperçu -------------------------------------------------------------

    /**
     * Blocs d'affichage d'une note, pour l'aperçu en lecture seule.
     *
     * Appel **pur** : ni réseau, ni cache, ni session. L'aperçu marche donc
     * hors connexion, et sur un brouillon que l'utilisateur vient de taper
     * sans l'avoir enregistré — on passe le texte affiché, pas le chemin.
     *
     * Le [name] compte autant que le contenu : c'est lui qui décide si le
     * texte est interprété comme du Markdown ou rendu tel quel.
     */
    suspend fun renderNote(name: String, content: String): List<NoteBlockDto> =
        json.decodeFromString(call { it.renderNoteJSON(name, content) })

    /**
     * Blocs d'affichage d'un document Office, lu et analysé côté Go.
     *
     * Le fichier ne traverse jamais la frontière comme une chaîne : un .docx
     * ou un .odt est une archive binaire. Comme [readNote], cet appel relit le
     * serveur quand il est disponible et retombe sur le cache hors connexion.
     */
    suspend fun renderFile(filePath: String): List<NoteBlockDto> =
        json.decodeFromString(call { it.renderFileJSON(filePath) })

    /**
     * Vrai pour un fichier affiché tel quel, sans interprétation.
     *
     * La question se pose avant qu'il y ait des blocs à regarder — un fichier
     * vide n'en produit aucun — d'où cet appel séparé. La liste des extensions
     * reste en Go : la redériver ici la ferait diverger au premier format
     * ajouté. Appel synchrone et sans effet, il ne touche ni disque ni réseau.
     */
    fun isPlainText(name: String): Boolean = Mobile.isPlainText(name)

    /** Vrai pour un document Office lisible, mais jamais modifiable. */
    fun isDocument(name: String): Boolean = Mobile.isDocument(name)

    /**
     * Allège une note avant de l'ouvrir dans un champ de saisie.
     *
     * Une image insérée depuis l'interface web d'OpenCloud est un
     * `data:image/jpeg;base64,…` de plusieurs dizaines de milliers de
     * caractères **sans une espace**. Confié tel quel à un `TextField`, ce
     * pavé fait tuer l'application par le système : le moteur de retour à la
     * ligne d'Android cherche des points de coupure là où il n'y en a aucun,
     * en mémoire native, hors du tas Java — d'où une mort de processus sans la
     * moindre exception à lire.
     *
     * Le résultat doit toujours repartir par [restoreImages] avant écriture.
     */
    suspend fun prepareEdit(name: String, content: String): PreparedEditDto =
        json.decodeFromString(call { it.prepareEditJSON(name, content) })

    /**
     * Remet les données en ligne à la place de leurs jetons.
     *
     * **À appeler avant chaque écriture.** Un jeton effacé par l'utilisateur ne
     * revient pas : supprimer le repère d'une image, c'est supprimer l'image,
     * et c'est le seul geste dont il dispose pour ça depuis un téléphone.
     */
    suspend fun restoreImages(text: String, images: List<String>): String {
        if (images.isEmpty()) return text
        val encodees = json.encodeToString(images)
        return call { it.restoreImages(text, encodees) }
    }
}
