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
 * Trois valeurs courtes dans un fichier que l'application écrit seule.
 * DataStore ajouterait une dépendance et une machinerie de `Flow` pour ce que
 * trois lignes font ici.
 *
 * La lecture au constructeur est **synchrone**, contrairement à [TokenStore] :
 * celui-ci doit dériver une clé maîtresse au Keystore, ce qui prend du temps
 * et n'a rien à faire sur le thread principal. Ouvrir un fichier de
 * préférences ordinaire, non chiffré et de quelques octets, coûte quelques
 * millisecondes une fois par démarrage. Rendre ces trois lecteurs `suspend`
 * obligerait chaque écran à afficher un état « je ne sais pas encore »
 * pendant une image, pour rien.
 *
 * # Pourquoi des `StateFlow`
 *
 * Le mode d'affichage se change dans le tiroir et s'applique au navigateur :
 * deux composants qui ne se connaissent pas. Un flux partagé les relie sans
 * qu'aucun ait à prévenir l'autre.
 */
class PreferencesAffichage(context: Context) {

    private val prefs = context.getSharedPreferences(FICHIER, Context.MODE_PRIVATE)

    private val _tri = MutableStateFlow(prefs.getString(CLE_TRI, null))
    private val _mode = MutableStateFlow(prefs.getString(CLE_MODE, null))
    private val _dernierDossier = MutableStateFlow(prefs.getString(CLE_DOSSIER, null))

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

    fun definirTri(valeur: String) = ecrire(CLE_TRI, valeur, _tri)

    fun definirMode(valeur: String) = ecrire(CLE_MODE, valeur, _mode)

    fun definirDernierDossier(valeur: String) = ecrire(CLE_DOSSIER, valeur, _dernierDossier)

    /**
     * Le flux est mis à jour avant l'écriture disque, et `apply()` diffère
     * celle-ci : l'écran se redessine sans attendre le stockage, et un disque
     * lent ne se voit pas sur un appui de bouton.
     */
    private fun ecrire(cle: String, valeur: String, flux: MutableStateFlow<String?>) {
        flux.value = valeur
        prefs.edit().putString(cle, valeur).apply()
    }

    private companion object {
        const val FICHIER = "opennote_affichage"
        const val CLE_TRI = "tri_notes"
        const val CLE_MODE = "mode_liste"
        const val CLE_DOSSIER = "dernier_dossier"
    }
}
