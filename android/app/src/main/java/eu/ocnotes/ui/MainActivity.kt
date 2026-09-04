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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import eu.ocnotes.appContainer
import eu.ocnotes.ui.common.ChargementPleinEcran
import eu.ocnotes.ui.common.TiroirApplication
import eu.ocnotes.ui.root.DemarrageState
import eu.ocnotes.ui.root.RootViewModel
import eu.ocnotes.ui.theme.OCnotesTheme

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
                    OCnotesApp()
                }
            }
        }
    }
}

@Composable
private fun OCnotesApp(
    viewModel: RootViewModel = viewModel(
        factory = RootViewModel.factory(LocalContext.current.appContainer),
    ),
) {
    val demarrage by viewModel.etat.collectAsStateWithLifecycle()
    val sessionExpiree by viewModel.sessionExpiree.collectAsStateWithLifecycle()
    val navController = rememberNavController()
    val etatTiroir = rememberDrawerState(DrawerValue.Closed)

    // Le geste reste réservé au navigateur. Dans l'éditeur, le tiroir Material
    // écoute toute la surface et peut voler un défilement vertical dès que le
    // doigt dérive légèrement à l'horizontale.
    val destination by navController.currentBackStackEntryAsState()
    val gestesActifs = gestesTiroirActifs(destination?.destination?.route)

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
        ) {
            OCnotesNavHost(
                navController = navController,
                depart = etat.depart,
                messageDemarrage = etat.message,
            )
        }
    }
}

/** Le tiroir gestuel ne concurrence jamais une surface de saisie. */
internal fun gestesTiroirActifs(route: String?): Boolean = route == Routes.NAVIGATEUR
