package eu.ocnotes.data

import android.content.Context
import java.io.File
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Police utilisée par l'interface et le contenu de lecture. */
enum class PoliceInterface(val valeur: String) {
    LEXEND("lexend"),
    OPENDYSLEXIC("opendyslexic"),
    SYSTEME("systeme");

    companion object {
        fun depuis(valeur: String?): PoliceInterface =
            entries.firstOrNull { it.valeur == valeur } ?: LEXEND
    }
}

/**
 * Paliers de taille du texte en mode lecture, en pourcentage de la taille
 * normale. Des paliers plutôt qu'un curseur : on retombe toujours sur la même
 * taille, et un appui de doigt suffit à passer de l'un à l'autre.
 */
object PaliersLecture {
    val valeurs = listOf(85, 100, 115, 130, 150, 175, 200)
    const val NORMAL = 100

    /** Ramène une valeur quelconque (fichier ancien, valeur abîmée) au palier le plus proche. */
    fun normaliser(pourcent: Int): Int = valeurs.minBy { kotlin.math.abs(it - pourcent) }

    /** Palier suivant, ou le même s'il n'y a rien au-dessus. */
    fun suivant(pourcent: Int): Int =
        valeurs.firstOrNull { it > normaliser(pourcent) } ?: valeurs.last()

    /** Palier précédent, ou le même s'il n'y a rien en dessous. */
    fun precedent(pourcent: Int): Int =
        valeurs.lastOrNull { it < normaliser(pourcent) } ?: valeurs.first()
}

/**
 * Préférences d'affichage : ce qui décrit un écran, pas une session.
 *
 * # Pourquoi ici et pas dans `config.json`
 *
 * Le cœur Go n'a pas à savoir dans quel ordre ni sous quelle forme la liste
 * est dessinée : aucun appel de la façade ne change selon ces réglages. Les
 * faire traverser la frontière élargirait un contrat gelé pour un état qui ne
 * quitte jamais l'écran.
 *
 * # Pourquoi `SharedPreferences`, et pourquoi la lecture est synchrone
 *
 * Quelques valeurs courtes dans un fichier que l'application écrit seule.
 * DataStore ajouterait une dépendance et une machinerie de `Flow` pour ce que
 * trois lignes font ici.
 *
 * La lecture au constructeur est **synchrone**, contrairement à [TokenStore] :
 * celui-ci doit dériver une clé maîtresse au Keystore, ce qui prend du temps
 * et n'a rien à faire sur le thread principal. Ouvrir un fichier de
 * préférences ordinaire, non chiffré et de quelques octets, coûte quelques
 * millisecondes une fois par démarrage. Rendre ces lecteurs `suspend`
 * obligerait chaque écran à afficher un état « je ne sais pas encore »
 * pendant une image, pour rien.
 *
 * # Pourquoi des `StateFlow`
 *
 * Le mode d'affichage se change dans le tiroir et s'applique au navigateur :
 * deux composants qui ne se connaissent pas. Un flux partagé les relie sans
 * qu'aucun ait à prévenir l'autre.
 */
class PreferencesAffichage internal constructor(
    private val stockage: StockagePreferencesAffichage,
    // Les épingles ne vivent pas dans le fichier de préférences : voir
    // [StockageEpinglesFichiers]. En mémoire par défaut, pour les tests.
    private val stockageEpingles: StockageEpingles = StockageEpinglesMemoire(),
) {

    constructor(context: Context) : this(
        context.getSharedPreferences(FICHIER, Context.MODE_PRIVATE),
        StockageEpinglesFichiers(File(context.filesDir, AccountRegistry.ACCOUNTS_DIR)),
    )

    private constructor(
        prefs: android.content.SharedPreferences,
        fichiers: StockageEpinglesFichiers,
    ) : this(StockagePreferencesAndroid(prefs), fichiers) {
        migrerEpinglesDesPreferences(prefs, fichiers)
    }

    private val _tri = MutableStateFlow(stockage.lireChaine(CLE_TRI))
    private val _mode = MutableStateFlow(stockage.lireChaine(CLE_MODE))
    private val _dernierDossier = MutableStateFlow(stockage.lireChaine(CLE_DOSSIER))
    private val _quotaCache = MutableStateFlow(stockage.lireLong(CLE_QUOTA_CACHE, QUOTA_CACHE_DEFAUT))
    private val _garderEcranAllumeLecture = MutableStateFlow(
        stockage.lireBooleen(CLE_GARDER_ECRAN_ALLUME_LECTURE, false),
    )
    private val _garderEcranAllumeEdition = MutableStateFlow(
        stockage.lireBooleen(CLE_GARDER_ECRAN_ALLUME_EDITION, false),
    )
    private val _saisieAutomatiqueEdition = MutableStateFlow(
        stockage.lireBooleen(CLE_SAISIE_AUTOMATIQUE_EDITION, true),
    )
    private val _policeInterface = MutableStateFlow(
        PoliceInterface.depuis(stockage.lireChaine(CLE_POLICE_INTERFACE)),
    )
    // Normalisé dès la lecture : l'écran ne voit jamais qu'un palier connu.
    private val _tailleLecture = MutableStateFlow(
        PaliersLecture.normaliser(
            stockage.lireLong(CLE_TAILLE_LECTURE, PaliersLecture.NORMAL.toLong())
                .coerceIn(0L, 1000L).toInt(),
        ),
    )

    /**
     * Ordre de tri retenu, sous sa forme brute.
     *
     * La chaîne n'est pas interprétée ici : le sens d'un tri appartient à
     * l'écran qui l'applique, et `data` ne dépend pas de `ui`. `null` quand
     * rien n'a jamais été choisi.
     */
    val tri: StateFlow<String?> = _tri.asStateFlow()

    /** Arborescence ou liste plate. Même remarque sur la forme brute. */
    val mode: StateFlow<String?> = _mode.asStateFlow()

    /**
     * Dernier dossier où une note a été créée.
     *
     * Sert de destination proposée en mode liste plate, où il n'y a pas de
     * « dossier courant » dont hériter. `null` désigne le dossier de notes.
     */
    val dernierDossier: StateFlow<String?> = _dernierDossier.asStateFlow()

    /** Limite locale des blobs de notes, en octets ; zéro signifie illimité. */
    val quotaCache: StateFlow<Long> = _quotaCache.asStateFlow()

    /** Vrai s'il faut empêcher la mise en veille de l'écran en lecture (aperçu). */
    val garderEcranAllumeLecture: StateFlow<Boolean> = _garderEcranAllumeLecture.asStateFlow()

    /** Vrai s'il faut empêcher la mise en veille de l'écran en édition. */
    val garderEcranAllumeEdition: StateFlow<Boolean> = _garderEcranAllumeEdition.asStateFlow()

    /**
     * Vrai si le service de saisie automatique du téléphone peut lire le champ
     * d'édition.
     *
     * Aucun coût mesurable à la frappe, dans un sens ou dans l'autre (section
     * 7 bis d'`ARCHITECTURE.md`) : c'est une question de confidentialité. À
     * chaque mise au point de l'éditeur, une session s'ouvre auprès du service
     * — Google, ou un gestionnaire de mots de passe — qui peut recevoir le
     * texte de la note. Autorisé par défaut, comme tout champ Android.
     */
    val saisieAutomatiqueEdition: StateFlow<Boolean> = _saisieAutomatiqueEdition.asStateFlow()

    /** Police de l'interface ; Lexend reste le choix par défaut des installations existantes. */
    val policeInterface: StateFlow<PoliceInterface> = _policeInterface.asStateFlow()

    /**
     * Taille du texte de l'aperçu, en pourcentage ; toujours un des
     * [PaliersLecture]. Commune à toutes les notes.
     */
    val tailleLecture: StateFlow<Int> = _tailleLecture.asStateFlow()

    fun definirTri(valeur: String) = ecrire(CLE_TRI, valeur, _tri)

    fun definirMode(valeur: String) = ecrire(CLE_MODE, valeur, _mode)

    fun definirDernierDossier(valeur: String) = ecrire(CLE_DOSSIER, valeur, _dernierDossier)

    fun definirQuotaCache(valeur: Long) {
        _quotaCache.value = valeur
        stockage.ecrireLong(CLE_QUOTA_CACHE, valeur)
    }

    fun definirGarderEcranAllumeLecture(valeur: Boolean) {
        _garderEcranAllumeLecture.value = valeur
        stockage.ecrireBooleen(CLE_GARDER_ECRAN_ALLUME_LECTURE, valeur)
    }

    fun definirGarderEcranAllumeEdition(valeur: Boolean) {
        _garderEcranAllumeEdition.value = valeur
        stockage.ecrireBooleen(CLE_GARDER_ECRAN_ALLUME_EDITION, valeur)
    }

    fun definirSaisieAutomatiqueEdition(valeur: Boolean) {
        _saisieAutomatiqueEdition.value = valeur
        stockage.ecrireBooleen(CLE_SAISIE_AUTOMATIQUE_EDITION, valeur)
    }

    fun definirPoliceInterface(valeur: PoliceInterface) {
        _policeInterface.value = valeur
        stockage.ecrireChaine(CLE_POLICE_INTERFACE, valeur.valeur)
    }

    fun definirTailleLecture(pourcent: Int) {
        val palier = PaliersLecture.normaliser(pourcent)
        _tailleLecture.value = palier
        stockage.ecrireLong(CLE_TAILLE_LECTURE, palier.toLong())
    }

    // --- Notes épinglées ----------------------------------------------------

    /**
     * Un flux par profil, créé à la première demande puis gardé : relire le
     * disque à chaque recomposition est exactement ce qu'il faut éviter.
     */
    private val epinglesParProfil = ConcurrentHashMap<String, MutableStateFlow<Set<String>>>()

    private fun fluxEpingles(idProfil: String): MutableStateFlow<Set<String>> =
        epinglesParProfil.computeIfAbsent(idProfil) {
            // Normalisé dès la lecture : un fichier abîmé ou écrit à la main ne
            // fait jamais apparaître de chemin vide ni de doublon.
            MutableStateFlow(normaliserEpingles(stockageEpingles.lire(idProfil).orEmpty()))
        }

    /**
     * Chemins des notes épinglées du profil [idProfil], relatifs à son dossier
     * de notes.
     *
     * Par profil, parce qu'un chemin ne veut rien dire hors du sien : deux
     * profils peuvent avoir chacun leur « Courses.md ». Elles sont rangées
     * dans le dossier du profil, pas dans ce fichier de préférences commun :
     * voir [StockageEpinglesFichiers].
     */
    fun epingles(idProfil: String): StateFlow<Set<String>> = fluxEpingles(idProfil).asStateFlow()

    /** Remplace les épingles du profil ; un ensemble vide efface le fichier. */
    fun definirEpingles(idProfil: String, chemins: Set<String>) {
        val propres = normaliserEpingles(chemins)
        val flux = fluxEpingles(idProfil)
        if (flux.value == propres) return
        flux.value = propres
        if (propres.isEmpty()) {
            stockageEpingles.effacer(idProfil)
        } else {
            stockageEpingles.ecrire(idProfil, propres)
        }
    }

    /** Oublie les épingles d'un profil supprimé : ses chemins ne désignent plus rien. */
    fun effacerEpingles(idProfil: String) {
        epinglesParProfil.remove(idProfil)?.value = emptySet()
        stockageEpingles.effacer(idProfil)
    }

    /**
     * Le flux est mis à jour avant l'écriture disque, et `apply()` diffère
     * celle-ci : l'écran se redessine sans attendre le stockage, et un disque
     * lent ne se voit pas sur un appui de bouton.
     */
    private fun ecrire(cle: String, valeur: String, flux: MutableStateFlow<String?>) {
        flux.value = valeur
        stockage.ecrireChaine(cle, valeur)
    }

    internal companion object {
        const val FICHIER = "ocnotes_affichage"
        const val CLE_TRI = "tri_notes"
        const val CLE_MODE = "mode_liste"
        const val CLE_DOSSIER = "dernier_dossier"
        const val CLE_QUOTA_CACHE = "quota_cache_octets"
        const val CLE_GARDER_ECRAN_ALLUME_LECTURE = "garder_ecran_allume_lecture"
        const val CLE_GARDER_ECRAN_ALLUME_EDITION = "garder_ecran_allume_edition"
        const val CLE_SAISIE_AUTOMATIQUE_EDITION = "saisie_automatique_edition"
        const val CLE_POLICE_INTERFACE = "police_interface"
        const val CLE_TAILLE_LECTURE = "taille_texte_lecture"
        const val QUOTA_CACHE_DEFAUT = 250L * 1024 * 1024

        /**
         * Préfixe des anciennes clés d'épingles, dans ce fichier de
         * préférences. Elles n'y sont plus écrites : lues une fois pour
         * migration, puis effacées.
         */
        const val PREFIXE_EPINGLES_ANCIEN = "epingles_"
    }
}

internal interface StockagePreferencesAffichage {
    fun lireChaine(cle: String): String?
    fun lireLong(cle: String, defaut: Long): Long
    fun lireBooleen(cle: String, defaut: Boolean): Boolean
    fun ecrireChaine(cle: String, valeur: String)
    fun ecrireLong(cle: String, valeur: Long)
    fun ecrireBooleen(cle: String, valeur: Boolean)
}

private class StockagePreferencesAndroid(
    private val prefs: android.content.SharedPreferences,
) : StockagePreferencesAffichage {
    override fun lireChaine(cle: String): String? = prefs.getString(cle, null)

    override fun lireLong(cle: String, defaut: Long): Long = prefs.getLong(cle, defaut)

    override fun lireBooleen(cle: String, defaut: Boolean): Boolean = prefs.getBoolean(cle, defaut)

    override fun ecrireChaine(cle: String, valeur: String) {
        prefs.edit().putString(cle, valeur).apply()
    }

    override fun ecrireLong(cle: String, valeur: Long) {
        prefs.edit().putLong(cle, valeur).apply()
    }

    override fun ecrireBooleen(cle: String, valeur: Boolean) {
        prefs.edit().putBoolean(cle, valeur).apply()
    }
}

/**
 * Remet en forme un ensemble lu sur le disque : chemins vides écartés, et
 * barres obliques de tête ou de queue retirées — un chemin de la façade n'en
 * porte jamais, et « /Courses.md » désignerait la même note que
 * « Courses.md ». Les doublons qui en résultent disparaissent avec l'ensemble.
 *
 * La casse, les accents et les espaces internes ne sont **pas** touchés : le
 * serveur distingue « Journal.md » de « journal.md », et une épingle doit
 * désigner exactement la note qu'on a épinglée.
 */
fun normaliserEpingles(brut: Collection<String>): Set<String> =
    brut.asSequence()
        .map { it.trim('/') }
        .filter { it.isNotBlank() }
        .toSet()

// --- Stockage des épingles -------------------------------------------------

/** Épingles d'un profil, rangées à part des préférences d'affichage. */
internal interface StockageEpingles {
    /** Chemins épinglés du profil, ou `null` s'il n'en a jamais eu. */
    fun lire(idProfil: String): Set<String>?
    fun ecrire(idProfil: String, valeur: Set<String>)
    fun effacer(idProfil: String)
}

/** Double en mémoire, pour les tests qui n'ont pas besoin du disque. */
internal class StockageEpinglesMemoire : StockageEpingles {
    val contenu = mutableMapOf<String, Set<String>>()

    override fun lire(idProfil: String): Set<String>? = contenu[idProfil]

    override fun ecrire(idProfil: String, valeur: Set<String>) {
        contenu[idProfil] = valeur.toSet()
    }

    override fun effacer(idProfil: String) {
        contenu.remove(idProfil)
    }
}

/**
 * Épingles rangées dans le dossier du profil : `accounts/<id>/epingles.txt`,
 * un chemin par ligne.
 *
 * # Pourquoi pas dans `ocnotes_affichage`
 *
 * Ce fichier de préférences part **entier** dans la sauvegarde Android, alors
 * que seuls les profils **locaux** doivent y aller. Les épingles d'un profil
 * serveur — des noms de notes — y partaient donc aussi, et restaient pour
 * toujours après une restauration, sans profil pour les effacer.
 *
 * Dans le dossier du profil, tout suit de soi-même : la sauvegarde n'emporte
 * que les dossiers des profils locaux (`SauvegardeProfils`), la restauration
 * les remet à leur place avec le même identifiant, et supprimer le profil
 * supprime son dossier, épingles comprises.
 *
 * # Écritures
 *
 * Hors du fil principal, dans l'ordre, par un seul fil : l'écran a déjà sa
 * valeur dans le flux. Un fichier temporaire puis un renommage, pour qu'une
 * coupure ne laisse jamais un fichier à moitié écrit — et `.tmp` est exclu de
 * la sauvegarde. Rien n'est écrit si le dossier du profil n'existe plus :
 * recréer le dossier d'un profil supprimé ferait un fantôme.
 */
internal class StockageEpinglesFichiers(
    private val dossierComptes: File,
    // Exécute les écritures ; un seul fil, pour garder leur ordre.
    private val executer: (Runnable) -> Unit = Executors.newSingleThreadExecutor()::execute,
) : StockageEpingles {

    override fun lire(idProfil: String): Set<String>? {
        val fichier = fichier(idProfil) ?: return null
        if (!fichier.isFile) return null
        return runCatching { fichier.readLines(Charsets.UTF_8).toSet() }.getOrNull()
    }

    override fun ecrire(idProfil: String, valeur: Set<String>) {
        // Copie prise maintenant : l'appelant peut modifier son ensemble après.
        val lignes = valeur.filterNot { '\n' in it || '\r' in it }.sorted()
        executer(Runnable { ecrireMaintenant(idProfil, lignes) })
    }

    override fun effacer(idProfil: String) {
        executer(Runnable { fichier(idProfil)?.delete() })
    }

    private fun ecrireMaintenant(idProfil: String, lignes: List<String>) {
        val cible = fichier(idProfil) ?: return
        val dossier = cible.parentFile ?: return
        if (!dossier.isDirectory) return
        val temporaire = File(dossier, "$FICHIER_EPINGLES.tmp")
        runCatching {
            temporaire.writeText(lignes.joinToString("\n"), Charsets.UTF_8)
            if (!temporaire.renameTo(cible)) {
                cible.delete()
                temporaire.renameTo(cible)
            }
        }
    }

    /** Fichier du profil, ou `null` pour un identifiant qui n'est pas un UUID. */
    private fun fichier(idProfil: String): File? {
        // Même garde-fou que le registre : un identifiant forgé ne doit pas
        // faire écrire hors de `accounts/`.
        val valide = runCatching { UUID.fromString(idProfil).toString() == idProfil }.getOrDefault(false)
        if (!valide) return null
        return File(File(dossierComptes, idProfil), FICHIER_EPINGLES)
    }

    companion object {
        const val FICHIER_EPINGLES = "epingles.txt"
    }
}

/**
 * Reprend les épingles qu'une première version rangeait dans le fichier de
 * préférences (`epingles_<id>`), puis efface ces clés : elles partaient dans
 * la sauvegarde pour tous les profils.
 */
private fun migrerEpinglesDesPreferences(
    prefs: android.content.SharedPreferences,
    fichiers: StockageEpinglesFichiers,
) {
    val anciennes = prefs.all.filterKeys { it.startsWith(PreferencesAffichage.PREFIXE_EPINGLES_ANCIEN) }
    if (anciennes.isEmpty()) return
    val edition = prefs.edit()
    anciennes.forEach { (cle, valeur) ->
        val idProfil = cle.removePrefix(PreferencesAffichage.PREFIXE_EPINGLES_ANCIEN)
        val chemins = (valeur as? Set<*>)?.filterIsInstance<String>()?.toSet().orEmpty()
        if (chemins.isNotEmpty() && fichiers.lire(idProfil) == null) {
            fichiers.ecrire(idProfil, normaliserEpingles(chemins))
        }
        edition.remove(cle)
    }
    edition.apply()
}
