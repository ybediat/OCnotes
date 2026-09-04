package eu.ocnotes.ui.workspace

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import eu.ocnotes.AppContainer
import eu.ocnotes.data.DriveDto
import eu.ocnotes.data.OCnotesException
import eu.ocnotes.data.OCnotesRepository
import eu.ocnotes.ui.common.Texte
import eu.ocnotes.ui.common.texte
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class WorkspaceUiState(
    val chargement: Boolean = true,
    val drives: List<DriveDto> = emptyList(),
    val driveChoisi: String? = null,
    val racine: String = "",
    val validation: Boolean = false,
    val erreur: Texte? = null,
    val termine: Boolean = false,
) {
    val peutValider: Boolean
        get() = !chargement && !validation && driveChoisi != null
}

/**
 * Choix de l'espace de stockage et du dossier de notes.
 *
 * Les espaces inutilisables sont conservés dans la liste, grisés : les faire
 * disparaître laisserait l'utilisateur chercher un espace « Shares » qu'il
 * voit pourtant dans son navigateur web.
 */
class WorkspaceViewModel(
    private val repository: OCnotesRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow(WorkspaceUiState())
    val uiState: StateFlow<WorkspaceUiState> = _uiState.asStateFlow()

    init {
        charger()
    }

    fun charger() {
        _uiState.update { it.copy(chargement = true, erreur = null) }

        viewModelScope.launch {
            try {
                val drives = repository.listDrives()
                val etatCourant = repository.state()

                // Présélection : l'espace déjà retenu, sinon le premier
                // utilisable — dans la pratique l'espace personnel, que le
                // cœur Go place devant.
                val presel = drives.firstOrNull { it.selected && it.usable }
                    ?: drives.firstOrNull { it.usable }

                // Idem : `update` n'accepte pas d'appel suspendu dans sa lambda.
                val racineProposee = etatCourant.root.ifBlank { repository.defaultRoot() }

                _uiState.update {
                    it.copy(
                        chargement = false,
                        drives = drives,
                        driveChoisi = it.driveChoisi ?: presel?.id,
                        racine = it.racine.ifBlank { racineProposee },
                    )
                }
            } catch (e: OCnotesException) {
                _uiState.update { it.copy(chargement = false, erreur = e.texte()) }
            }
        }
    }

    /** Ignore silencieusement un espace inutilisable : la ligne n'est pas cliquable. */
    fun choisirDrive(drive: DriveDto) {
        if (!drive.usable) return
        _uiState.update { it.copy(driveChoisi = drive.id, erreur = null) }
    }

    fun onRacineChange(valeur: String) = _uiState.update { it.copy(racine = valeur, erreur = null) }

    /**
     * Enregistre le choix.
     *
     * Le dossier est créé côté serveur s'il n'existe pas. Une racine vide est
     * acceptée : elle désigne la racine de l'espace, ce qui permet de brancher
     * l'application sur une arborescence de notes déjà en place.
     */
    fun valider() {
        val etat = _uiState.value
        val drive = etat.driveChoisi ?: return

        _uiState.update { it.copy(validation = true, erreur = null) }

        viewModelScope.launch {
            try {
                repository.selectWorkspace(drive, etat.racine.trim())
                _uiState.update { it.copy(validation = false, termine = true) }
            } catch (e: OCnotesException) {
                _uiState.update { it.copy(validation = false, erreur = e.texte()) }
            }
        }
    }

    fun finConsommee() = _uiState.update { it.copy(termine = false) }

    companion object {
        fun factory(container: AppContainer): ViewModelProvider.Factory = viewModelFactory {
            initializer { WorkspaceViewModel(container.repository) }
        }
    }
}
