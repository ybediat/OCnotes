package eu.ocnotes.sync

import eu.ocnotes.data.AppMode

/**
 * Traduit les modes successifs d'un profil en ordres pour [SyncScheduler].
 *
 * Au lancement, le mode vaut UNSET tant que l'état n'est pas relu : cela veut
 * dire « pas encore connu », pas « sans serveur ». Le prendre pour un arrêt
 * annulait tous les travaux du profil — la passe de lancement à peine partie,
 * celle qui suit une écriture, et le calendrier de la passe horaire. Seul un
 * UNSET venu après un mode connu (une déconnexion) coupe la synchronisation.
 *
 * Une instance par collecte : relancer la collecte (changement de profil)
 * repart d'un mode inconnu.
 */
class FiltreModeSynchro {
    private var modeConnu = false

    /**
     * `true` ou `false` : le serveur doit être activé ou coupé. `null` : le
     * mode n'est pas encore connu, rien à faire.
     */
    fun serveurActif(mode: String): Boolean? {
        if (mode == AppMode.UNSET && !modeConnu) return null
        modeConnu = true
        return mode == AppMode.SERVER
    }
}
