package com.sticam.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.sticam.R

// ── Sticam color palette ──────────────────────────────────────────────────────

/** Pure OLED black — every pixel off saves battery. */
val Black = Color(0xFF000000)

/** Terminal green for metric readouts. */
val TermGreen = Color(0xFF00FF41)
val TermGreenDim = Color(0xFF00C032)
val TermGreenMuted = Color(0xFF004D18)

/** Accent amber for warnings / active highlights. */
val Amber = Color(0xFFFFB300)
val AmberDim = Color(0xFF996A00)

/** Danger red for clipping / overexposure indicators. */
val DangerRed = Color(0xFFFF3030)

/** Subtle surface for panels — barely visible on OLED. */
val Surface900 = Color(0xFF080808)
val Surface800 = Color(0xFF101010)
val Surface700 = Color(0xFF1A1A1A)

/** Divider / border stroke. */
val Stroke = Color(0xFF1E3020)       // very dark green tint
val StrokeActive = Color(0xFF00FF41) // full green when active

val SticamColors = darkColorScheme(
    primary         = TermGreen,
    onPrimary       = Black,
    secondary       = Amber,
    onSecondary     = Black,
    background      = Black,
    onBackground    = TermGreen,
    surface         = Surface900,
    onSurface       = TermGreen,
    surfaceVariant  = Surface800,
    error           = DangerRed,
    outline         = Stroke,
)

// ── Typography ────────────────────────────────────────────────────────────────

// Lalezar font for titles and main UI controls
val Lalezar = FontFamily(Font(R.font.lalezar))

// Monospace for the HUD readouts
val Monospace = FontFamily.Monospace

@Composable
fun SticamTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = SticamColors,
        content = content
    )
}
