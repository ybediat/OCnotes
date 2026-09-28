package eu.ocnotes.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import eu.ocnotes.R

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

// Police de l'interface, hors éditeur (voir StyleEditeur ci-dessus).
private val PoliceOCnotes = FontFamily(
    Font(R.font.lexend_thin, FontWeight.Thin),
    Font(R.font.lexend_extralight, FontWeight.ExtraLight),
    Font(R.font.lexend_light, FontWeight.Light),
    Font(R.font.lexend_regular, FontWeight.Normal),
    Font(R.font.lexend_medium, FontWeight.Medium),
    Font(R.font.lexend_semibold, FontWeight.SemiBold),
    Font(R.font.lexend_bold, FontWeight.Bold),
    Font(R.font.lexend_extrabold, FontWeight.ExtraBold),
    Font(R.font.lexend_black, FontWeight.Black),
)

private val Typographie = Typography().let { defaut ->
    Typography(
        displayLarge = defaut.displayLarge.copy(fontFamily = PoliceOCnotes),
        displayMedium = defaut.displayMedium.copy(fontFamily = PoliceOCnotes),
        displaySmall = defaut.displaySmall.copy(fontFamily = PoliceOCnotes),
        headlineLarge = defaut.headlineLarge.copy(fontFamily = PoliceOCnotes),
        headlineMedium = defaut.headlineMedium.copy(fontFamily = PoliceOCnotes),
        headlineSmall = defaut.headlineSmall.copy(fontFamily = PoliceOCnotes),
        titleLarge = defaut.titleLarge.copy(
            fontFamily = PoliceOCnotes,
            fontWeight = FontWeight.SemiBold,
            fontSize = 20.sp,
        ),
        titleMedium = defaut.titleMedium.copy(fontFamily = PoliceOCnotes),
        titleSmall = defaut.titleSmall.copy(fontFamily = PoliceOCnotes),
        bodyLarge = defaut.bodyLarge.copy(fontFamily = PoliceOCnotes),
        bodyMedium = defaut.bodyMedium.copy(fontFamily = PoliceOCnotes),
        bodySmall = defaut.bodySmall.copy(fontFamily = PoliceOCnotes),
        labelLarge = defaut.labelLarge.copy(fontFamily = PoliceOCnotes),
        labelMedium = defaut.labelMedium.copy(fontFamily = PoliceOCnotes),
        labelSmall = defaut.labelSmall.copy(fontFamily = PoliceOCnotes),
    )
}

// Un cran plus rond que les valeurs par défaut de Material 3 (4/8/12/16/28dp) :
// de quoi adoucir cartes, menus et dialogues sans sombrer dans le tout-pilule.
private val Formes = Shapes(
    extraSmall = RoundedCornerShape(6.dp),
    small = RoundedCornerShape(10.dp),
    medium = RoundedCornerShape(14.dp),
    large = RoundedCornerShape(20.dp),
    extraLarge = RoundedCornerShape(28.dp),
)

@Composable
fun OCnotesTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val colorScheme = if (darkTheme) PaletteSombre else PaletteClaire

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typographie,
        shapes = Formes,
        content = content,
    )
}
