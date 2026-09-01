package eu.opennote.ui.editor

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.unit.dp

/** Mesures légères du champ ; le texte ne traverse jamais cette structure. */
data class EtatDefilementNatif(
    val position: Int = 0,
    val maximum: Int = 0,
    val hauteurVisible: Int = 0,
)

/** Hauteur proportionnelle, avec un minimum qui reste saisissable au doigt. */
internal fun hauteurCurseurRapide(
    hauteurPiste: Int,
    hauteurVisible: Int,
    maximum: Int,
    hauteurMinimum: Float,
): Float {
    if (hauteurPiste <= 0 || hauteurVisible <= 0 || maximum <= 0) {
        return hauteurPiste.coerceAtLeast(0).toFloat()
    }
    val hauteurDocument = hauteurVisible.toLong() + maximum.toLong()
    val proportionnelle = hauteurPiste * (hauteurVisible.toDouble() / hauteurDocument)
    return proportionnelle.toFloat().coerceIn(
        hauteurMinimum.coerceAtMost(hauteurPiste.toFloat()),
        hauteurPiste.toFloat(),
    )
}

/** Position visuelle du curseur dans l'espace encore disponible sur la piste. */
internal fun positionCurseurRapide(
    position: Int,
    maximum: Int,
    hauteurPiste: Int,
    hauteurCurseur: Float,
): Float {
    if (maximum <= 0) return 0f
    val course = (hauteurPiste - hauteurCurseur).coerceAtLeast(0f)
    return course * (position.coerceIn(0, maximum).toFloat() / maximum)
}

/** Le doigt pilote le centre du curseur, puis le résultat est borné aux extrêmes. */
internal fun progressionDepuisContact(
    positionY: Float,
    hauteurPiste: Int,
    hauteurCurseur: Float,
): Float {
    val course = hauteurPiste - hauteurCurseur
    if (course <= 0f) return 0f
    return ((positionY - hauteurCurseur / 2f) / course).coerceIn(0f, 1f)
}

/**
 * Rail fin, avec une zone tactile de 20 dp limitée au bord droit.
 *
 * Il ne consomme aucun geste commencé ailleurs dans le texte. La sémantique
 * `setProgress` permet aussi à un service d'accessibilité de déplacer le
 * document sans simuler un glissement.
 */
@Composable
fun BandeDefilementRapideNatif(
    etat: EtatDefilementNatif,
    description: String?,
    onDefiler: (Float) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (etat.maximum <= 0) return

    var hauteurPiste by remember { mutableIntStateOf(0) }
    val minimumCurseur = with(androidx.compose.ui.platform.LocalDensity.current) {
        36.dp.toPx()
    }
    val hauteurCurseur = hauteurCurseurRapide(
        hauteurPiste = hauteurPiste,
        hauteurVisible = etat.hauteurVisible,
        maximum = etat.maximum,
        hauteurMinimum = minimumCurseur,
    )
    val positionCurseur = positionCurseurRapide(
        position = etat.position,
        maximum = etat.maximum,
        hauteurPiste = hauteurPiste,
        hauteurCurseur = hauteurCurseur,
    )
    val progression = etat.position.coerceIn(0, etat.maximum).toFloat() / etat.maximum
    val couleurPiste = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.32f)
    val couleurCurseur = MaterialTheme.colorScheme.primary.copy(alpha = 0.82f)

    Box(
        modifier = modifier
            .fillMaxHeight()
            .width(20.dp)
            .onSizeChanged { hauteurPiste = it.height }
            .semantics {
                if (description != null) contentDescription = description
                progressBarRangeInfo = ProgressBarRangeInfo(progression, 0f..1f)
                setProgress { cible ->
                    onDefiler(cible.coerceIn(0f, 1f))
                    true
                }
            }
            .pointerInput(etat.maximum, hauteurPiste, hauteurCurseur) {
                if (hauteurPiste <= 0) return@pointerInput
                awaitEachGesture {
                    val debut = awaitFirstDown(requireUnconsumed = false)
                    onDefiler(
                        progressionDepuisContact(
                            positionY = debut.position.y,
                            hauteurPiste = hauteurPiste,
                            hauteurCurseur = hauteurCurseur,
                        ),
                    )
                    debut.consume()

                    while (true) {
                        val changement = awaitPointerEvent().changes
                            .firstOrNull { it.id == debut.id }
                            ?: break
                        if (!changement.pressed) break
                        onDefiler(
                            progressionDepuisContact(
                                positionY = changement.position.y,
                                hauteurPiste = hauteurPiste,
                                hauteurCurseur = hauteurCurseur,
                            ),
                        )
                        changement.consume()
                    }
                }
            },
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val largeurPiste = 1.dp.toPx()
            val largeurCurseur = 3.dp.toPx()
            // Les tout derniers pixels appartiennent parfois au geste système.
            // Le trait reste à droite, mais son centre demeure saisissable.
            val margeDroite = 8.dp.toPx()
            val centreX = size.width - margeDroite - largeurCurseur / 2f

            drawRoundRect(
                color = couleurPiste,
                topLeft = Offset(centreX - largeurPiste / 2f, 0f),
                size = Size(largeurPiste, size.height),
                cornerRadius = CornerRadius(largeurPiste / 2f),
            )
            drawRoundRect(
                color = couleurCurseur,
                topLeft = Offset(centreX - largeurCurseur / 2f, positionCurseur),
                size = Size(largeurCurseur, hauteurCurseur),
                cornerRadius = CornerRadius(largeurCurseur / 2f),
            )
        }
    }
}
