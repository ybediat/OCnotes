package eu.opennote.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

private val Vert = Color(0xFF1F6F5C)
private val VertClair = Color(0xFF6FD9BC)
private val Ardoise = Color(0xFF3F4A54)

val CouleurSignatureClaire = Color(0xFF20434F)
val CouleurSignatureSombre = Color(0xFFE2BAFF)

private val PaletteClaire = lightColorScheme(
    primary = Vert,
    secondary = Ardoise,
)

private val PaletteSombre = darkColorScheme(
    primary = VertClair,
    secondary = Color(0xFFB6C4D2),
)

/**
 * Style du corps de l'éditeur.
 *
 * Une police à chasse fixe : le Markdown est du texte structuré par des
 * caractères (`#`, `-`, `**`), et l'alignement des marqueurs de liste se lit
 * beaucoup mieux ainsi. La hauteur de ligne est généreuse, on écrit dedans.
 */
val StyleEditeur = TextStyle(
    fontFamily = FontFamily.Monospace,
    fontSize = 15.sp,
    lineHeight = 24.sp,
)

private val Typographie = Typography(
    titleLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 20.sp,
    ),
)

@Composable
fun OpenNoteTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    // Material You quand l'appareil sait le faire : les couleurs de
    // l'application suivent le fond d'écran, ce que l'utilisateur attend
    // d'une application système depuis Android 12.
    val colorScheme = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }

        darkTheme -> PaletteSombre
        else -> PaletteClaire
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typographie,
        content = content,
    )
}
