package eu.ocnotes.ui

import android.Manifest
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.Surface
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
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
            OCnotesTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    CrashReportGate(appContainer.crashReporter) {
                        val container = LocalContext.current.appContainer
                        val activeSession by container.activeSession.collectAsStateWithLifecycle()
                        key(activeSession.generation) {
                            SessionViewModelScope {
                                OCnotesApp(activeSession.profile.id, activeSession.generation)
                            }
                        }
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
            container.syncScheduler.setLocalOnly(mode == AppMode.LOCAL)
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
            onCompte = { id -> scope.launch { container.activateAccount(id) } },
            onAjouterCompte = { scope.launch { container.createAccount() } },
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
 * Chaque activation de compte reçoit son propre ViewModelStore. Le vider au
 * changement annule les coroutines et libère les anciens dépôts : revenir sur
 * un compte reconstruit alors une session unique sur son dossier.
 */
@Composable
private fun SessionViewModelScope(content: @Composable () -> Unit) {
    val owner = remember {
        object : ViewModelStoreOwner {
            override val viewModelStore = ViewModelStore()
        }
    }
    DisposableEffect(owner) {
        onDispose { owner.viewModelStore.clear() }
    }
    CompositionLocalProvider(LocalViewModelStoreOwner provides owner, content = content)
}

/** Le tiroir gestuel ne concurrence jamais une surface de saisie. */
internal fun gestesTiroirActifs(route: String?): Boolean = route == Routes.NAVIGATEUR
