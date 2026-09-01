package eu.opennote.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

// Rôles publiés par le design system OpenCloud :
// https://docs.opencloud.eu/design-system/designTokens/colorRoles.html
val CouleurSignatureClaire = Color(0xFF20434F)
val CouleurSignatureSombre = Color(0xFF5CD5FB)

private val PaletteClaire = lightColorScheme(
    primary = Color(0xFF00677F),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFB7EAFF),
    onPrimaryContainer = Color(0xFF001F28),
    inversePrimary = Color(0xFF5CD5FB),
    secondary = Color(0xFF20434F),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFCFE6F1),
    onSecondaryContainer = Color(0xFF071E26),
    tertiary = Color(0xFF5A5C7E),
    onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFE0E0FF),
    onTertiaryContainer = Color(0xFF171937),
    background = Color(0xFFFFFFFF),
    onBackground = Color(0xFF191C1D),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF191C1D),
    surfaceVariant = Color(0xFFDBE4E8),
    onSurfaceVariant = Color(0xFF40484C),
    surfaceTint = Color(0xFF715289),
    inverseSurface = Color(0xFF2E3132),
    inverseOnSurface = Color(0xFFEFF1F2),
    error = Color(0xFFBA1A1A),
    onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFFFFDAD6),
    onErrorContainer = Color(0xFF410002),
    outline = Color(0xFF70787C),
    outlineVariant = Color(0xFFBFC8CC),
    scrim = Color(0xFF000000),
    surfaceBright = Color(0xFFF8F9FB),
    surfaceDim = Color(0xFFD8DADC),
    surfaceContainer = Color(0xFFF6F8FA),
    surfaceContainerHigh = Color(0xFFF2F4F5),
    surfaceContainerHighest = Color(0xFFECEEF0),
    surfaceContainerLow = Color(0xFFFBFCFE),
    surfaceContainerLowest = Color(0xFFFFFFFF),
)

// OpenCloud ne publie actuellement que ses rôles clairs. Cette déclinaison
// sombre conserve les mêmes palettes tonales Material 3 et leurs contrastes.
private val PaletteSombre = darkColorScheme(
    primary = Color(0xFF5CD5FB),
    onPrimary = Color(0xFF003642),
    primaryContainer = Color(0xFF004E60),
    onPrimaryContainer = Color(0xFFB7EAFF),
    inversePrimary = Color(0xFF00677F),
    secondary = Color(0xFFB3CAD5),
    onSecondary = Color(0xFF1E333B),
    secondaryContainer = Color(0xFF354A52),
    onSecondaryContainer = Color(0xFFCFE6F1),
    tertiary = Color(0xFFC3C4EB),
    onTertiary = Color(0xFF2C2E4D),
    tertiaryContainer = Color(0xFF424465),
    onTertiaryContainer = Color(0xFFE0E0FF),
    background = Color(0xFF101415),
    onBackground = Color(0xFFDFE3E4),
    surface = Color(0xFF101415),
    onSurface = Color(0xFFDFE3E4),
    surfaceVariant = Color(0xFF40484C),
    onSurfaceVariant = Color(0xFFBFC8CC),
    surfaceTint = Color(0xFF5CD5FB),
    inverseSurface = Color(0xFFDFE3E4),
    inverseOnSurface = Color(0xFF2E3132),
    error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005),
    errorContainer = Color(0xFF93000A),
    onErrorContainer = Color(0xFFFFDAD6),
    outline = Color(0xFF899296),
    outlineVariant = Color(0xFF40484C),
    scrim = Color(0xFF000000),
    surfaceBright = Color(0xFF363A3B),
    surfaceDim = Color(0xFF101415),
    surfaceContainer = Color(0xFF1D2021),
    surfaceContainerHigh = Color(0xFF272A2B),
    surfaceContainerHighest = Color(0xFF323536),
    surfaceContainerLow = Color(0xFF191C1D),
    surfaceContainerLowest = Color(0xFF0B0F10),
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
    val colorScheme = if (darkTheme) PaletteSombre else PaletteClaire

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typographie,
        content = content,
    )
}
