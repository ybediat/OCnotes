package eu.ocnotes.ui.workspace

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import eu.ocnotes.AppContainer
import eu.ocnotes.data.AppMode
import eu.ocnotes.data.DriveDto
import eu.ocnotes.data.OCnotesException
import eu.ocnotes.data.OCnotesRepository
import eu.ocnotes.sync.SyncScheduler
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
    val modeLocal: Boolean = false,
    val notesLocales: Int = 0,
    val confirmationBranchement: Boolean = false,
    val annule: Boolean = false,
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
    private val syncScheduler: SyncScheduler,
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
                val modeLocal = etatCourant.mode == AppMode.LOCAL
                val notesLocales = if (modeLocal) repository.listAll().entries.size else 0

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
                        modeLocal = modeLocal,
                        notesLocales = notesLocales,
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
        if (etat.driveChoisi == null) return

        if (etat.modeLocal) {
            _uiState.update { it.copy(confirmationBranchement = true, erreur = null) }
            return
        }

        terminerBranchement(adopt = null)
    }

    fun fermerConfirmation() = _uiState.update { it.copy(confirmationBranchement = false) }

    fun annulerBranchement() {
        if (!_uiState.value.modeLocal || _uiState.value.validation) return
        _uiState.update { it.copy(validation = true, confirmationBranchement = false) }
        viewModelScope.launch {
            try {
                repository.startLocal()
                _uiState.update { it.copy(validation = false, annule = true) }
            } catch (e: OCnotesException) {
                _uiState.update { it.copy(validation = false, erreur = e.texte()) }
            }
        }
    }

    fun annulationConsommee() = _uiState.update { it.copy(annule = false) }

    fun confirmerBranchement(adopt: Boolean) {
        _uiState.update { it.copy(confirmationBranchement = false) }
        terminerBranchement(adopt)
    }

    /** `adopt == null` désigne le premier branchement, sans notes à arbitrer. */
    private fun terminerBranchement(adopt: Boolean?) {
        val etat = _uiState.value
        val drive = etat.driveChoisi ?: return

        _uiState.update { it.copy(validation = true, erreur = null) }

        viewModelScope.launch {
            try {
                if (adopt == null) {
                    repository.selectWorkspace(drive, etat.racine.trim())
                } else {
                    repository.attach(drive, etat.racine.trim(), adopt)
                    syncScheduler.setLocalOnly(false)
                    if (adopt) syncScheduler.syncNow()
                }
                _uiState.update { it.copy(validation = false, termine = true) }
            } catch (e: OCnotesException) {
                _uiState.update { it.copy(validation = false, erreur = e.texte()) }
            }
        }
    }

    fun finConsommee() = _uiState.update { it.copy(termine = false) }

    companion object {
        fun factory(container: AppContainer): ViewModelProvider.Factory = viewModelFactory {
            initializer { WorkspaceViewModel(container.repository, container.syncScheduler) }
        }
    }
}
