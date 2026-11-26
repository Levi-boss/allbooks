package app.allbooks

import android.os.Build
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

/**
 * Тема застосунку.
 * dark    – темна тема;
 * amoled  – чисто чорний фон у темній темі (економить батарею на OLED);
 * dynamic – кольори з шпалер (Material You, Android 12+).
 */
@Composable
fun AllBooksTheme(
    dark: Boolean,
    amoled: Boolean = false,
    dynamic: Boolean = false,
    content: @Composable () -> Unit
) {
    val context = LocalContext.current
    val useDynamic = dynamic && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S

    var scheme: ColorScheme = when {
        useDynamic && dark -> dynamicDarkColorScheme(context)
        useDynamic -> dynamicLightColorScheme(context)
        dark -> darkColorScheme()
        else -> lightColorScheme()
    }

    if (dark && amoled) {
        scheme = scheme.copy(
            background = Color.Black,
            surface = Color.Black,
            surfaceContainer = Color(0xFF0B0B0B),
            surfaceContainerLow = Color.Black
        )
    }

    MaterialTheme(colorScheme = scheme, content = content)
}