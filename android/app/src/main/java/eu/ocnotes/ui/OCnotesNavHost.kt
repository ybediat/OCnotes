package eu.ocnotes.ui

import android.net.Uri
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.navArgument
import eu.ocnotes.ui.browser.BrowserScreen
import eu.ocnotes.ui.common.Texte
import eu.ocnotes.ui.editor.EditorScreen
import eu.ocnotes.ui.login.LoginScreen
import eu.ocnotes.ui.login.SuiteConnexion
import eu.ocnotes.ui.root.Depart
import eu.ocnotes.ui.settings.SettingsScreen
import eu.ocnotes.ui.workspace.WorkspaceScreen

object Routes {
    const val CONNEXION = "connexion"
    const val ESPACE = "espace"
    const val NAVIGATEUR = "navigateur"
    const val REGLAGES = "reglages"

    const val ARG_CHEMIN = "chemin"
    const val EDITEUR = "editeur/{$ARG_CHEMIN}"

    /**
     * Construit la route de l'éditeur.
     *
     * Un chemin de note contient des `/`, et peut contenir à peu près
     * n'importe quoi d'autre : le serveur accepte espaces, accents, `?`, `#`,
     * `%`, emoji. `Uri.encode` est donc obligatoire, et la navigation décode
     * l'argument pour nous.
     */
    fun editeur(chemin: String) = "editeur/${Uri.encode(chemin)}"
}

fun Depart.route(): String = when (this) {
    Depart.CONNEXION -> Routes.CONNEXION
    Depart.CHOIX_ESPACE -> Routes.ESPACE
    Depart.NAVIGATEUR -> Routes.NAVIGATEUR
}

@Composable
fun OCnotesNavHost(
    navController: NavHostController,
    depart: Depart,
    messageDemarrage: Texte?,
    modifier: Modifier = Modifier,
) {
    NavHost(
        navController = navController,
        startDestination = depart.route(),
        modifier = modifier,
    ) {
        composable(Routes.CONNEXION) {
            LoginScreen(
                messageInitial = messageDemarrage,
                onConnecte = { suite ->
                    val cible = when (suite) {
                        SuiteConnexion.CHOIX_ESPACE -> Routes.ESPACE
                        SuiteConnexion.NAVIGATEUR -> Routes.NAVIGATEUR
                    }
                    navController.navigate(cible) {
                        // On ne revient pas à l'écran de connexion par le
                        // bouton retour : la session est ouverte.
                        popUpTo(Routes.CONNEXION) { inclusive = true }
                    }
                },
            )
        }

        composable(Routes.ESPACE) {
            WorkspaceScreen(
                onEspaceChoisi = {
                    navController.navigate(Routes.NAVIGATEUR) {
                        popUpTo(Routes.ESPACE) { inclusive = true }
                    }
                },
            )
        }

        composable(Routes.NAVIGATEUR) {
            BrowserScreen(
                onOuvrirNote = { chemin -> navController.navigate(Routes.editeur(chemin)) },
            )
        }

        composable(
            route = Routes.EDITEUR,
            arguments = listOf(navArgument(Routes.ARG_CHEMIN) { type = NavType.StringType }),
        ) { entree ->
            val chemin = entree.arguments?.getString(Routes.ARG_CHEMIN).orEmpty()
            EditorScreen(
                chemin = chemin,
                onRetour = { navController.popBackStack() },
            )
        }

        composable(Routes.REGLAGES) {
            SettingsScreen(
                onRetour = { navController.popBackStack() },
                onDeconnecte = {
                    navController.navigate(Routes.CONNEXION) {
                        // Après une déconnexion, toute la pile est périmée :
                        // le cache est vide et la session fermée.
                        popUpTo(0) { inclusive = true }
                    }
                },
            )
        }
    }
}
