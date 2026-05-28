package guru.freberg.lenscast.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

private val DarkColors = darkColorScheme(
    primary           = Violet400,
    onPrimary         = Ink900,
    primaryContainer  = Violet600,
    onPrimaryContainer = Smoke100,
    secondary         = Coral,
    onSecondary       = Ink900,
    background        = Ink900,
    onBackground      = Smoke100,
    surface           = Ink800,
    onSurface         = Smoke100,
    surfaceVariant    = Ink700,
    onSurfaceVariant  = Smoke200,
    surfaceContainer  = Ink700,
    surfaceContainerHigh = Ink600,
    outline           = Ink600,
)

private val LightColors = lightColorScheme(
    primary                 = Violet600,
    onPrimary               = Color(0xFFFFFFFF),
    primaryContainer        = PrimaryContainerLight,
    onPrimaryContainer      = OnPrimaryContainerLight,
    secondary               = Coral,
    onSecondary             = Color(0xFFFFFFFF),
    background              = SurfaceLight,
    onBackground            = OnSurfaceLight,
    surface                 = SurfaceLight,
    onSurface               = OnSurfaceLight,
    surfaceVariant          = SurfaceVariantLight,
    onSurfaceVariant        = OnSurfaceVariantLight,
    surfaceContainer        = SurfaceContainerLight,
    surfaceContainerHigh    = SurfaceContainerHighLight,
    surfaceContainerHighest = SurfaceContainerHighestLight,
    outline                 = OutlineLight,
)

@Composable
fun LenscastTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = true,
    content: @Composable () -> Unit,
) {
    val ctx = LocalContext.current
    val supportsDynamic = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
    val scheme = when {
        dynamicColor && supportsDynamic && darkTheme -> dynamicDarkColorScheme(ctx)
        dynamicColor && supportsDynamic && !darkTheme -> dynamicLightColorScheme(ctx)
        darkTheme -> DarkColors
        else -> LightColors
    }
    MaterialTheme(
        colorScheme = scheme,
        typography = LenscastTypography,
        content = content,
    )
}
