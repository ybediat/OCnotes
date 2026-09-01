package eu.opennote.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

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
) {

    constructor(context: Context) : this(
        StockagePreferencesAndroid(
            context.getSharedPreferences(FICHIER, Context.MODE_PRIVATE),
        ),
    )

    private val _tri = MutableStateFlow(stockage.lireChaine(CLE_TRI))
    private val _mode = MutableStateFlow(stockage.lireChaine(CLE_MODE))
    private val _dernierDossier = MutableStateFlow(stockage.lireChaine(CLE_DOSSIER))
    private val _quotaCache = MutableStateFlow(stockage.lireLong(CLE_QUOTA_CACHE, QUOTA_CACHE_DEFAUT))
    private val _moteurEdition = MutableStateFlow(
        MoteurEdition.depuis(stockage.lireChaine(CLE_MOTEUR_EDITION)),
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

    /** Moteur choisi pour les prochaines sessions d'édition. */
    val moteurEdition: StateFlow<MoteurEdition> = _moteurEdition.asStateFlow()

    fun definirTri(valeur: String) = ecrire(CLE_TRI, valeur, _tri)

    fun definirMode(valeur: String) = ecrire(CLE_MODE, valeur, _mode)

    fun definirDernierDossier(valeur: String) = ecrire(CLE_DOSSIER, valeur, _dernierDossier)

    fun definirQuotaCache(valeur: Long) {
        _quotaCache.value = valeur
        stockage.ecrireLong(CLE_QUOTA_CACHE, valeur)
    }

    fun definirMoteurEdition(valeur: MoteurEdition) {
        _moteurEdition.value = valeur
        stockage.ecrireChaine(CLE_MOTEUR_EDITION, valeur.valeurPersistante)
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

    private companion object {
        const val FICHIER = "opennote_affichage"
        const val CLE_TRI = "tri_notes"
        const val CLE_MODE = "mode_liste"
        const val CLE_DOSSIER = "dernier_dossier"
        const val CLE_QUOTA_CACHE = "quota_cache_octets"
        const val CLE_MOTEUR_EDITION = "moteur_edition"
        const val QUOTA_CACHE_DEFAUT = 250L * 1024 * 1024
    }
}

internal interface StockagePreferencesAffichage {
    fun lireChaine(cle: String): String?
    fun lireLong(cle: String, defaut: Long): Long
    fun ecrireChaine(cle: String, valeur: String)
    fun ecrireLong(cle: String, valeur: Long)
}

private class StockagePreferencesAndroid(
    private val prefs: android.content.SharedPreferences,
) : StockagePreferencesAffichage {
    override fun lireChaine(cle: String): String? = prefs.getString(cle, null)

    override fun lireLong(cle: String, defaut: Long): Long = prefs.getLong(cle, defaut)

    override fun ecrireChaine(cle: String, valeur: String) {
        prefs.edit().putString(cle, valeur).apply()
    }

    override fun ecrireLong(cle: String, valeur: Long) {
        prefs.edit().putLong(cle, valeur).apply()
    }
}
