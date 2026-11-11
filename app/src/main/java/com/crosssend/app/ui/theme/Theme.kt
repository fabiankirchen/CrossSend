package com.crosssend.app.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MotionScheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp

private val DarkColorScheme = darkColorScheme(
    primary = Color(0xFFD0BCFF),
    onPrimary = Color(0xFF381E72),
    primaryContainer = Color(0xFF4F378B),
    onPrimaryContainer = Color(0xFFEADDFF),
    secondary = Color(0xFFCCC2DC),
    onSecondary = Color(0xFF332D41),
    secondaryContainer = Color(0xFF4A4458),
    onSecondaryContainer = Color(0xFFE8DEF8),
    tertiary = Color(0xFFEFB8C8),
    onTertiary = Color(0xFF492532),
    tertiaryContainer = Color(0xFF633B48),
    onTertiaryContainer = Color(0xFFFFD8E4),
    background = Color(0xFF141218),
    onBackground = Color(0xFFE6E0E9),
    surface = Color(0xFF141218),
    onSurface = Color(0xFFE6E0E9),
    surfaceVariant = Color(0xFF49454F),
    onSurfaceVariant = Color(0xFFCAC4D0),
    surfaceContainer = Color(0xFF211F26),
    surfaceContainerLow = Color(0xFF1D1B20),
    surfaceContainerHigh = Color(0xFF2B2930),
    surfaceContainerHighest = Color(0xFF36343B),
    outline = Color(0xFF938F99),
    outlineVariant = Color(0xFF49454F)
)

private val LightColorScheme = lightColorScheme(
    primary = Color(0xFF6750A4),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFEADDFF),
    onPrimaryContainer = Color(0xFF21005D),
    secondary = Color(0xFF625B71),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFE8DEF8),
    onSecondaryContainer = Color(0xFF1D192B),
    tertiary = Color(0xFF7D5260),
    onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFFFD8E4),
    onTertiaryContainer = Color(0xFF31111D),
    background = Color(0xFFFEF7FF),
    onBackground = Color(0xFF1D1B20),
    surface = Color(0xFFFEF7FF),
    onSurface = Color(0xFF1D1B20),
    surfaceVariant = Color(0xFFE7E0EC),
    onSurfaceVariant = Color(0xFF49454E),
    surfaceContainer = Color(0xFFF3EDF7),
    surfaceContainerLow = Color(0xFFF7F2FA),
    surfaceContainerHigh = Color(0xFFECE6F0),
    surfaceContainerHighest = Color(0xFFE6E0E9),
    outline = Color(0xFF79747E),
    outlineVariant = Color(0xFFCAC4D0)
)

data class StatusColorScheme(
    val synced: Color,
    val onSynced: Color,
    val syncedContainer: Color,
    val onSyncedContainer: Color,
    val pending: Color,
    val onPending: Color,
    val pendingContainer: Color,
    val onPendingContainer: Color
)

val LightStatusColors = StatusColorScheme(
    synced = Color(0xFF2E6C2F),
    onSynced = Color(0xFFFFFFFF),
    syncedContainer = Color(0xFFD6F5D6),
    onSyncedContainer = Color(0xFF072107),
    pending = Color(0xFFB35A00),
    onPending = Color(0xFFFFFFFF),
    pendingContainer = Color(0xFFFFE0B2),
    onPendingContainer = Color(0xFF3B1800)
)

val DarkStatusColors = StatusColorScheme(
    synced = Color(0xFF81D784),
    onSynced = Color(0xFF05390B),
    syncedContainer = Color(0xFF144D19),
    onSyncedContainer = Color(0xFFB4F2B7),
    pending = Color(0xFFFFB74D),
    onPending = Color(0xFF4D2400),
    pendingContainer = Color(0xFF5E2E00),
    onPendingContainer = Color(0xFFFFDCC1)
)

val LocalStatusColors = androidx.compose.runtime.staticCompositionLocalOf { LightStatusColors }

val MaterialTheme.statusColors: StatusColorScheme
    @Composable
    get() = LocalStatusColors.current

private val ExpressiveShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(24.dp),
    extraLarge = RoundedCornerShape(32.dp)
)

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun CrossSendTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = true,
    content: @Composable () -> Unit
) {
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        darkTheme -> DarkColorScheme
        else -> LightColorScheme
    }

    val statusColors = if (darkTheme) DarkStatusColors else LightStatusColors

    androidx.compose.runtime.CompositionLocalProvider(LocalStatusColors provides statusColors) {
        MaterialTheme(
            colorScheme = colorScheme,
            motionScheme = MotionScheme.expressive(),
            shapes = ExpressiveShapes,
            typography = Typography,
            content = content
        )
    }
}
