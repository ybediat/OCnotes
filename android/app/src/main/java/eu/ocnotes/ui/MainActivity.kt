package eu.ocnotes.ui

import android.Manifest
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import eu.ocnotes.R
import eu.ocnotes.appContainer
import eu.ocnotes.data.AppMode
import eu.ocnotes.ui.common.ChargementPleinEcran
import eu.ocnotes.ui.common.CrashReportGate
import eu.ocnotes.ui.common.TiroirApplication
import eu.ocnotes.ui.root.DemarrageState
import eu.ocnotes.ui.root.RootViewModel
import eu.ocnotes.ui.theme.OCnotesTheme
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    /**
     * Permission de notification.
     *
     * Elle ne sert qu'à signaler les conflits de synchronisation. Un refus ne
     * dégrade rien d'essentiel : le bandeau des Réglages reste, et aucune
     * donnée n'est perdue de toute façon.
     */
    private val demandeNotifications =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            demandeNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
        }

        setContent {
            val container = LocalContext.current.appContainer
            val policeInterface by container.preferencesAffichage.policeInterface
                .collectAsStateWithLifecycle()
            OCnotesTheme(police = policeInterface) {
                Surface(modifier = Modifier.fillMaxSize()) {
                    CrashReportGate(appContainer.crashReporter) {
                        val activeSession by container.activeSession.collectAsStateWithLifecycle()
                        key(activeSession.generation) {
                            SessionViewModelScope(activeSession.generation) {
                                OCnotesApp(activeSession.profile.id, activeSession.generation)
                            }
                        }
                        EchecCompteDialog()
                        CompteDejaPresentDialog()
                    }
                }
            }
        }
    }
}

@Composable
private fun OCnotesApp(
    accountId: String,
    generation: Long,
    viewModel: RootViewModel = viewModel(
        key = "root-$accountId-$generation",
        factory = RootViewModel.factory(LocalContext.current.appContainer),
    ),
) {
    val demarrage by viewModel.etat.collectAsStateWithLifecycle()
    val sessionExpiree by viewModel.sessionExpiree.collectAsStateWithLifecycle()
    val navController = rememberNavController()
    val etatTiroir = rememberDrawerState(DrawerValue.Closed)
    val container = LocalContext.current.appContainer
    val scope = rememberCoroutineScope()

    LaunchedEffect(container, accountId) {
        container.repository.mode.collect { mode ->
            container.syncScheduler.setServerEnabled(mode == AppMode.SERVER)
        }
    }

    // Le geste reste réservé au navigateur. Dans l'éditeur, le tiroir Material
    // écoute toute la surface et peut voler un défilement vertical dès que le
    // doigt dérive légèrement à l'horizontale.
    val destination by navController.currentBackStackEntryAsState()
    val route = destination?.destination?.route
    val gestesActifs = gestesTiroirActifs(route)

    // Fil d'Ariane du diagnostic. Une route porte ses arguments — `editeur/{
    // chemin}` — dont aucun n'a à traverser : seul le segment de tête part.
    // L'éditeur affine ensuite avec les mesures du document.
    LaunchedEffect(route) {
        container.crashReporter.noterEcran(route?.substringBefore('/').orEmpty())
    }

    // Un token rejeté en arrière-plan ne se répare pas tout seul : on ramène
    // l'utilisateur à la saisie plutôt que de le laisser devant une liste qui
    // ne se rafraîchit plus.
    LaunchedEffect(sessionExpiree) {
        if (sessionExpiree && demarrage is DemarrageState.Pret) {
            navController.navigate(Routes.CONNEXION) {
                popUpTo(0) { inclusive = true }
            }
        }
    }

    when (val etat = demarrage) {
        DemarrageState.EnCours -> ChargementPleinEcran()

        is DemarrageState.Pret -> TiroirApplication(
            etatTiroir = etatTiroir,
            gestesActifs = gestesActifs,
            onReglages = {
                navController.navigate(Routes.REGLAGES) { launchSingleTop = true }
            },
            onCompte = { id -> container.lancerGesteCompte { activateAccount(id) } },
            onAjouterCompte = { container.lancerGesteCompte { createAccount() } },
            onSupprimerCompte = { id -> container.lancerGesteCompte { deleteAccount(id) } },
        ) {
            OCnotesNavHost(
                navController = navController,
                depart = etat.depart,
                messageDemarrage = etat.message,
                onOuvrirComptes = { scope.launch { etatTiroir.open() } },
            )
        }
    }
}

/**
 * Chaque activation de compte reçoit son propre ViewModelStore.
 *
 * Ce store vit dans [SessionStores], donc dans le store de l'activité : il
 * survit à une rotation, un changement de thème ou de langue, comme les
 * ViewModels d'avant les comptes multiples. Le recréer à chaque recréation
 * d'activité détruirait tous les écrans — et l'éditeur rouvrirait sa note
 * pendant que l'ancien ViewModel enregistre encore son dernier texte.
 */
@Composable
private fun SessionViewModelScope(generation: Long, content: @Composable () -> Unit) {
    val stores: SessionStores = viewModel()
    val owner = remember(generation) {
        val store = stores.pour(generation)
        object : ViewModelStoreOwner {
            override val viewModelStore = store
        }
    }
    CompositionLocalProvider(LocalViewModelStoreOwner provides owner, content = content)
}

/**
 * Détient le store de la génération affichée. Il n'est vidé qu'en changeant de
 * génération — ce qui annule les coroutines des écrans quittés — ou quand
 * l'activité se termine pour de bon.
 */
internal class SessionStores : ViewModel() {
    private var generation: Long? = null
    private var store = ViewModelStore()

    fun pour(generation: Long): ViewModelStore {
        if (generation != this.generation) {
            store.clear()
            store = ViewModelStore()
            this.generation = generation
        }
        return store
    }

    override fun onCleared() {
        store.clear()
    }
}

/** Signale l'échec d'un geste du tiroir sur les comptes. */
@Composable
private fun EchecCompteDialog() {
    val container = LocalContext.current.appContainer
    val echec by container.echecCompte.collectAsStateWithLifecycle()
    if (!echec) return
    AlertDialog(
        onDismissRequest = container::acquitterEchecCompte,
        text = { Text(stringResource(R.string.compte_geste_echec)) },
        confirmButton = {
            TextButton(onClick = container::acquitterEchecCompte) {
                Text(stringResource(R.string.action_fermer))
            }
        },
    )
}

/** Explique pourquoi une connexion a rouvert un profil existant au lieu d'en ajouter un. */
@Composable
private fun CompteDejaPresentDialog() {
    val container = LocalContext.current.appContainer
    val present by container.compteDejaPresent.collectAsStateWithLifecycle()
    if (!present) return
    AlertDialog(
        onDismissRequest = container::acquitterCompteDejaPresent,
        text = { Text(stringResource(R.string.compte_deja_present)) },
        confirmButton = {
            TextButton(onClick = container::acquitterCompteDejaPresent) {
                Text(stringResource(R.string.action_fermer))
            }
        },
    )
}

/** Le tiroir gestuel ne concurrence jamais une surface de saisie. */
internal fun gestesTiroirActifs(route: String?): Boolean = route == Routes.NAVIGATEUR
