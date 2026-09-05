package eu.ocnotes.ui.login

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import eu.ocnotes.R
import eu.ocnotes.appContainer
import eu.ocnotes.ui.common.Texte
import eu.ocnotes.ui.common.resoudre
import androidx.compose.ui.platform.LocalContext

/**
 * Écran de connexion : URL du serveur, nom d'utilisateur, App Token.
 *
 * Le texte d'aide sur les App Tokens n'est pas décoratif : personne ne devine
 * qu'il faut créer un jeton dédié plutôt que de saisir son mot de passe, et
 * c'est le premier point de blocage d'une application auto-hébergée.
 */
@Composable
fun LoginScreen(
    onConnecte: (SuiteConnexion) -> Unit,
    modifier: Modifier = Modifier,
    /**
     * Message posé par le démarrage : token refusé, ou serveur injoignable au
     * lancement. Il s'efface dès que l'utilisateur tente une connexion.
     */
    messageInitial: Texte? = null,
    viewModel: LoginViewModel = viewModel(
        factory = LoginViewModel.factory(LocalContext.current.appContainer),
    ),
) {
    val etat by viewModel.uiState.collectAsStateWithLifecycle()

    LaunchedEffect(etat.suite) {
        etat.suite?.let {
            viewModel.suiteConsommee()
            onConnecte(it)
        }
    }

    var tokenVisible by remember { mutableStateOf(false) }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Spacer(Modifier.height(24.dp))

        Text(
            text = stringResource(R.string.app_name),
            style = MaterialTheme.typography.headlineMedium,
        )
        Text(
            text = stringResource(R.string.login_accroche),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Spacer(Modifier.height(8.dp))

        OutlinedTextField(
            value = etat.serverUrl,
            onValueChange = viewModel::onServerUrlChange,
            label = { Text(stringResource(R.string.login_serveur_label)) },
            placeholder = { Text(stringResource(R.string.login_serveur_exemple)) },
            singleLine = true,
            enabled = !etat.enCours,
            keyboardOptions = KeyboardOptions(
                keyboardType = KeyboardType.Uri,
                imeAction = ImeAction.Next,
            ),
            modifier = Modifier.fillMaxWidth(),
        )

        OutlinedTextField(
            value = etat.username,
            onValueChange = viewModel::onUsernameChange,
            label = { Text(stringResource(R.string.login_utilisateur_label)) },
            singleLine = true,
            enabled = !etat.enCours,
            // Une erreur d'authentification vise les identifiants : on marque
            // les deux champs concernés plutôt que d'afficher un message seul.
            isError = etat.erreurEstAuth,
            keyboardOptions = KeyboardOptions(
                keyboardType = KeyboardType.Text,
                imeAction = ImeAction.Next,
            ),
            modifier = Modifier.fillMaxWidth(),
        )

        OutlinedTextField(
            value = etat.appToken,
            onValueChange = viewModel::onAppTokenChange,
            label = { Text(stringResource(R.string.login_token_label)) },
            singleLine = true,
            enabled = !etat.enCours,
            isError = etat.erreurEstAuth,
            visualTransformation = if (tokenVisible) {
                VisualTransformation.None
            } else {
                PasswordVisualTransformation()
            },
            keyboardOptions = KeyboardOptions(
                keyboardType = KeyboardType.Password,
                imeAction = ImeAction.Done,
            ),
            trailingIcon = {
                IconButton(onClick = { tokenVisible = !tokenVisible }) {
                    Icon(
                        imageVector = if (tokenVisible) {
                            Icons.Default.VisibilityOff
                        } else {
                            Icons.Default.Visibility
                        },
                        contentDescription = stringResource(
                            if (tokenVisible) {
                                R.string.login_token_masquer
                            } else {
                                R.string.login_token_afficher
                            },
                        ),
                    )
                }
            },
            modifier = Modifier.fillMaxWidth(),
        )

        AideAppToken()

        (etat.erreur ?: messageInitial.takeIf { !etat.enCours })?.resoudre()?.let { message ->
            Surface(
                color = MaterialTheme.colorScheme.errorContainer,
                shape = MaterialTheme.shapes.medium,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    text = message,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                    modifier = Modifier.padding(12.dp),
                )
            }
        }

        Button(
            onClick = viewModel::connecter,
            enabled = etat.peutValider,
            modifier = Modifier.fillMaxWidth(),
        ) {
            if (etat.enCours) {
                CircularProgressIndicator(
                    strokeWidth = 2.dp,
                    modifier = Modifier.height(18.dp),
                    color = MaterialTheme.colorScheme.onPrimary,
                )
            } else {
                // Un échec réseau ne demande aucune correction : le libellé
                // invite à réessayer, pas à recommencer la saisie.
                Text(
                    stringResource(
                        if (etat.erreur != null && !etat.erreurEstAuth) {
                            R.string.login_reessayer
                        } else {
                            R.string.login_connecter
                        },
                    ),
                )
            }
        }

        if (etat.configurationChargee && !etat.serveurEnregistre) {
            OutlinedButton(
                onClick = viewModel::continuerEnLocal,
                enabled = !etat.enCours,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    stringResource(
                        if (etat.depuisModeLocal) {
                            R.string.login_rester_local
                        } else {
                            R.string.login_continuer_local
                        },
                    ),
                )
            }

            Text(
                text = stringResource(
                    if (etat.depuisModeLocal) {
                        R.string.login_rester_local_aide
                    } else {
                        R.string.login_continuer_local_aide
                    },
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        Spacer(Modifier.height(24.dp))
    }
}

/**
 * Où trouver un App Token.
 *
 * Formulé comme un chemin de menu exact, pas comme un principe : c'est ce dont
 * on a besoin quand on a l'écran d'OpenCloud sous les yeux.
 */
@Composable
private fun AideAppToken(modifier: Modifier = Modifier) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = MaterialTheme.shapes.medium,
        modifier = modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(
                text = stringResource(R.string.login_aide_titre),
                style = MaterialTheme.typography.titleSmall,
            )
            Text(
                text = stringResource(R.string.login_aide_chemin),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = stringResource(R.string.login_aide_revocation),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
