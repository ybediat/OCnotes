package eu.opennote.ui.common

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

/** Indicateur d'attente centré, pour un écran encore vide. */
@Composable
fun ChargementPleinEcran(modifier: Modifier = Modifier) {
    Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        CircularProgressIndicator()
    }
}

/**
 * Bandeau d'information ou d'avertissement, pleine largeur.
 *
 * Sert aux deux messages persistants de l'application : la vue servie depuis
 * le cache, et l'incident de synchronisation.
 */
@Composable
fun Bandeau(
    texte: String,
    modifier: Modifier = Modifier,
    icone: ImageVector = Icons.Default.Info,
    couleurFond: Color = MaterialTheme.colorScheme.secondaryContainer,
    couleurTexte: Color = MaterialTheme.colorScheme.onSecondaryContainer,
) {
    Surface(color = couleurFond, modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Icon(icone, contentDescription = null, tint = couleurTexte)
            Text(
                text = texte,
                style = MaterialTheme.typography.bodySmall,
                color = couleurTexte,
            )
        }
    }
}

/**
 * Bandeau du mode hors connexion.
 *
 * Formulé comme un constat, pas comme une erreur : la liste vient du cache,
 * elle est utilisable, elle peut simplement être incomplète.
 */
@Composable
fun BandeauCache(modifier: Modifier = Modifier) {
    Bandeau(
        texte = "Serveur injoignable — vue reconstituée depuis le cache. " +
            "Elle peut être incomplète.",
        icone = Icons.Default.CloudOff,
        couleurFond = MaterialTheme.colorScheme.tertiaryContainer,
        couleurTexte = MaterialTheme.colorScheme.onTertiaryContainer,
        modifier = modifier,
    )
}

/** Pastille signalant une modification locale pas encore synchronisée. */
@Composable
fun PastilleEnAttente(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .size(9.dp)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.primary),
    )
}

/** Écran vide explicite : un dossier sans notes n'est pas une panne. */
@Composable
fun EtatVide(
    titre: String,
    detail: String,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = titre,
            style = MaterialTheme.typography.titleMedium,
            textAlign = TextAlign.Center,
        )
        Text(
            text = detail,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 8.dp),
        )
    }
}
