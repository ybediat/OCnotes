package eu.ocnotes.ui.root

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import eu.ocnotes.AppContainer
import eu.ocnotes.R
import eu.ocnotes.data.OCnotesRepository
import eu.ocnotes.data.RestoreOutcome
import eu.ocnotes.data.ValidationSession
import eu.ocnotes.ui.common.Texte
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** Écran par lequel l'application doit commencer. */
enum class Depart { CONNEXION, CHOIX_ESPACE, NAVIGATEUR }

sealed interface DemarrageState {
    data object EnCours : DemarrageState

    data class Pret(
        val depart: Depart,
        /** Message à afficher au premier écran, s'il y a quelque chose à dire. */
        val message: Texte? = null,
    ) : DemarrageState
}

/**
 * Décide de l'écran de départ.
 *
 * La séquence de démarrage et son ordre sont le point
 * important :
 *
 *  1. `stateJSON()` — si `connected` est faux, écran de connexion ;
 *  2. sinon `restore(token)`, **sans réseau**, et on va droit aux notes ;
 *  3. **ensuite seulement**, `connect(...)` en arrière-plan pour valider le
 *     token et rafraîchir ;
 *  4. si `restore` échoue faute d'espace enregistré, il faut `connect` puis la
 *     sélection d'espace — et donc du réseau.
 *
 * Ne jamais démarrer par `connect` : il valide les identifiants en ligne, donc
 * il échoue sans réseau, et laisse la bibliothèque nulle. Un utilisateur déjà
 * configuré qui ouvre l'application dans le métro doit voir ses notes, pas un
 * formulaire.
 */
class RootViewModel(
    private val repository: OCnotesRepository,
) : ViewModel() {

    private val _etat = MutableStateFlow<DemarrageState>(DemarrageState.EnCours)
    val etat: StateFlow<DemarrageState> = _etat.asStateFlow()

    /** Passe à vrai si le serveur rejette le token, au démarrage ou plus tard. */
    val sessionExpiree: StateFlow<Boolean> = repository.sessionExpired

    init {
        demarrer()
    }

    fun demarrer() {
        _etat.value = DemarrageState.EnCours

        viewModelScope.launch {
            when (repository.restore()) {
                RestoreOutcome.PRETE -> {
                    // Les notes d'abord, la validation ensuite : l'écran
                    // s'affiche sans attendre le réseau.
                    _etat.value = DemarrageState.Pret(Depart.NAVIGATEUR)
                    validerEnArrierePlan()
                }

                RestoreOutcome.AUCUNE_SESSION ->
                    _etat.value = DemarrageState.Pret(Depart.CONNEXION)

                // Un compte est enregistré mais aucun espace n'a été choisi :
                // ce cas-là exige vraiment le réseau, on ne peut pas le
                // contourner.
                RestoreOutcome.SANS_ESPACE -> terminerConfiguration()
            }
        }
    }

    /**
     * Valide le token pendant que l'utilisateur consulte déjà ses notes.
     *
     * Un refus `AUTH` lève [OCnotesRepository.sessionExpired], que
     * l'activité surveille pour ramener à la saisie. Une panne réseau ne
     * produit rien : le bandeau « vue reconstituée depuis le cache » du
     * navigateur dit déjà ce qu'il faut.
     */
    private fun validerEnArrierePlan() {
        viewModelScope.launch { repository.validerSession() }
    }

    /** Compte enregistré mais sans espace de travail : la connexion est requise. */
    private suspend fun terminerConfiguration() {
        _etat.value = when (repository.validerSession()) {
            ValidationSession.VALIDEE -> {
                val apres = repository.state()
                DemarrageState.Pret(
                    if (apres.hasWorkspace) Depart.NAVIGATEUR else Depart.CHOIX_ESPACE,
                )
            }

            ValidationSession.TOKEN_REFUSE -> DemarrageState.Pret(
                depart = Depart.CONNEXION,
                message = Texte.de(R.string.demarrage_token_refuse),
            )

            ValidationSession.HORS_LIGNE -> DemarrageState.Pret(
                depart = Depart.CONNEXION,
                message = Texte.de(R.string.demarrage_espace_hors_ligne),
            )
        }
    }

    companion object {
        fun factory(container: AppContainer): ViewModelProvider.Factory = viewModelFactory {
            initializer { RootViewModel(container.repository) }
        }
    }
}
