package com.openminis.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// Accent: iOS blue, desaturated. [T-android-accent-blue-parity]
//
// Was a teal (#2E8B8B / #4DD9D9) that predated iOS settling on blue. The hue
// now comes from iOS Assets.xcassets/AccentColor.colorset (sRGB components
// r0.212 g0.525 b0.933 -> #3686EE light, r0.329 g0.565 b0.894 -> #5490E4 dark),
// but iOS's saturation (84% / 73%) read as glaring on Android's darker
// surfaces, so SATURATION is dialled back ~30% with hue and lightness kept:
//   light  #3686EE  S84% L57%  ->  #528AD2  S59% L57%
//   dark   #5490E4  S73% L61%  ->  #6A94CE  S51% L61%
//
// Lightness is deliberately NOT raised, which is the other way to "lighten".
// It would have softened dark mode further but pushed light-mode contrast on
// white from 3.62 to 2.62 — below WCAG AA's 4.5 for text. Desaturating keeps
// dark mode at 5.93 (passing) and leaves light mode where it was.
//
// Names keep the `Teal` prefix only to avoid churning 90+ call sites; the
// value is the contract, not the name.
private val TealPrimary = Color(0xFF528AD2)
private val TealOnPrimary = Color(0xFFFFFFFF)
private val TealPrimaryContainer = Color(0xFFB2DFDB)
private val TealOnPrimaryContainer = Color(0xFF00332F)
private val TealSecondary = Color(0xFF4A6360)
private val TealOnSecondary = Color(0xFFFFFFFF)
private val TealSecondaryContainer = Color(0xFFCCE8E4)
private val TealOnSecondaryContainer = Color(0xFF05201D)
private val TealTertiary = Color(0xFF46617A)
private val TealOnTertiary = Color(0xFFFFFFFF)
private val TealTertiaryContainer = Color(0xFFCDE5FF)
private val TealOnTertiaryContainer = Color(0xFF001D32)
private val TealBackground = Color(0xFFF5FAFA)
private val TealOnBackground = Color(0xFF171D1C)
private val TealSurface = Color(0xFFF5FAFA)
private val TealOnSurface = Color(0xFF171D1C)
private val TealSurfaceVariant = Color(0xFFDAE5E2)
private val TealOnSurfaceVariant = Color(0xFF3F4947)
private val TealOutline = Color(0xFF6F7977)

private val TealDarkPrimary = Color(0xFF6A94CE)
private val TealDarkOnPrimary = Color(0xFF003737)
private val TealDarkPrimaryContainer = Color(0xFF1A6B6B)
private val TealDarkOnPrimaryContainer = Color(0xFFB2DFDB)
private val TealDarkSecondary = Color(0xFFB1CCC8)
private val TealDarkOnSecondary = Color(0xFF1C3532)
private val TealDarkSecondaryContainer = Color(0xFF334B48)
private val TealDarkOnSecondaryContainer = Color(0xFFCCE8E4)
private val TealDarkBackground = Color(0xFF0E1514)
private val TealDarkOnBackground = Color(0xFFDEE4E2)
private val TealDarkSurface = Color(0xFF0E1514)
private val TealDarkOnSurface = Color(0xFFDEE4E2)
private val TealDarkSurfaceVariant = Color(0xFF3F4947)
private val TealDarkOnSurfaceVariant = Color(0xFFBEC9C6)
private val TealDarkOutline = Color(0xFF899390)

// Hark 3.5 Luminous & Glacial Clarity palette
// Light: Porcelain #F8FAFC, pure white cards #FFFFFF, crisp outline #E2E8F0
// Dark: Glacial Midnight #0A0E17, slate-900 cards #111827, ice-blue outline 0x2638BDF8
private val LuminousBg = Color(0xFFF8FAFC)
private val LuminousCard = Color(0xFFFFFFFF)
private val LuminousCardElevated = Color(0xFFF1F5F9)
private val LuminousOutline = Color(0xFFE2E8F0)

private val GlacialDarkBg = Color(0xFF0A0E17)
private val GlacialDarkCard = Color(0xFF111827)
private val GlacialDarkCardElevated = Color(0xFF1E293B)
private val GlacialDarkOutline = Color(0x2638BDF8)

private val LightColorScheme = lightColorScheme(
    primary = Color(0xFF2563EB),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFDBEAFE),
    onPrimaryContainer = Color(0xFF1E40AF),
    secondary = Color(0xFF475569),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFF1F5F9),
    onSecondaryContainer = Color(0xFF0F172A),
    tertiary = Color(0xFF0D9488),
    onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFCCFBF1),
    onTertiaryContainer = Color(0xFF115E59),
    background = LuminousBg,
    onBackground = Color(0xFF0F172A),
    surface = LuminousBg,
    onSurface = Color(0xFF0F172A),
    surfaceVariant = LuminousCard,
    onSurfaceVariant = Color(0xFF334155),
    surfaceContainerLowest = LuminousBg,
    surfaceContainerLow = LuminousCard,
    surfaceContainer = LuminousCard,
    surfaceContainerHigh = LuminousCardElevated,
    surfaceContainerHighest = LuminousOutline,
    outline = LuminousOutline,
    outlineVariant = LuminousOutline,
)

private val DarkColorScheme = darkColorScheme(
    primary = Color(0xFF38BDF8),
    onPrimary = Color(0xFF0A0E17),
    primaryContainer = Color(0xFF0369A1),
    onPrimaryContainer = Color(0xFFE0F2FE),
    secondary = Color(0xFF94A3B8),
    onSecondary = Color(0xFF0A0E17),
    secondaryContainer = Color(0xFF1E293B),
    onSecondaryContainer = Color(0xFFF1F5F9),
    tertiary = Color(0xFF2DD4BF),
    onTertiary = Color(0xFF0A0E17),
    tertiaryContainer = Color(0xFF134E4A),
    onTertiaryContainer = Color(0xFFCCFBF1),
    background = GlacialDarkBg,
    onBackground = Color(0xFFF8FAFC),
    surface = GlacialDarkBg,
    onSurface = Color(0xFFF8FAFC),
    surfaceVariant = GlacialDarkCard,
    onSurfaceVariant = Color(0xFFCBD5E1),
    surfaceContainerLowest = GlacialDarkBg,
    surfaceContainerLow = GlacialDarkCard,
    surfaceContainer = GlacialDarkCard,
    surfaceContainerHigh = GlacialDarkCardElevated,
    surfaceContainerHighest = Color(0xFF334155),
    outline = GlacialDarkOutline,
    outlineVariant = GlacialDarkOutline,
)

// App-wide FAB accent color (warm beige, matching iOS New Chat button).
// Reads from ChatPalette so it follows the in-app theme override (theme_mode pref),
// not android.isSystemInDarkTheme(), which only tracks the system setting.
@Composable
fun minisFabColor(): Color = LocalChatPalette.current.fabAccent

// App-wide shape system — larger corners for a modern, friendly feel
// DropdownMenu uses extraSmall, Dialog uses extraLarge, BottomSheet uses extraLarge
private val MinisShapes = Shapes(
    extraSmall = RoundedCornerShape(12.dp),   // DropdownMenu, Tooltip, OutlinedTextField default
    small = RoundedCornerShape(12.dp),        // Chip, TextField
    medium = RoundedCornerShape(20.dp),       // Card, Snackbar
    large = RoundedCornerShape(24.dp),        // NavigationDrawer
    extraLarge = RoundedCornerShape(28.dp),   // Dialog, BottomSheet
)

@Composable
fun MinisTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    fontScale: Float = 1f,
    content: @Composable () -> Unit,
) {
    val colorScheme = if (darkTheme) DarkColorScheme else LightColorScheme
    val typography = scaledTypography(fontScale)
    val chatPalette = if (darkTheme) DarkChatPalette else LightChatPalette

    MaterialTheme(
        colorScheme = colorScheme,
        shapes = MinisShapes,
        typography = typography,
    ) {
        CompositionLocalProvider(LocalChatPalette provides chatPalette, content = content)
    }
}

private fun TextStyle.scale(factor: Float): TextStyle =
    if (factor == 1f) this else copy(fontSize = fontSize * factor)

private fun scaledTypography(factor: Float): Typography {
    val base = Typography()
    return Typography(
        displayLarge = base.displayLarge.scale(factor),
        displayMedium = base.displayMedium.scale(factor),
        displaySmall = base.displaySmall.scale(factor),
        headlineLarge = base.headlineLarge.scale(factor),
        headlineMedium = base.headlineMedium.scale(factor),
        headlineSmall = base.headlineSmall.scale(factor),
        titleLarge = base.titleLarge.scale(factor),
        titleMedium = base.titleMedium.scale(factor),
        titleSmall = base.titleSmall.scale(factor),
        bodyLarge = base.bodyLarge.scale(factor),
        bodyMedium = base.bodyMedium.scale(factor),
        bodySmall = base.bodySmall.scale(factor),
        labelLarge = base.labelLarge.scale(factor),
        labelMedium = base.labelMedium.scale(factor),
        labelSmall = base.labelSmall.scale(factor),
    )
}

/**
 * Obsidian Canvas Design System Tokens.
 * Grounded in ui-craft quantitative research of 2,600 mobile screens.
 */
object ObsidianTokens {
    // Surfaces & Elevation (Glacial Clarity)
    val CanvasBase = Color(0xFF0A0E17)
    val SurfaceLevel1 = Color(0xFF111827)
    val SurfaceLevel2 = Color(0xFF1E293B)
    val HairlineBorder = Color(0x2638BDF8)
    val HairlineBorderLight = Color(0xFFE2E8F0)

    // Text & Content High Contrast
    val TextPrimary = Color(0xFFF8FAFC)
    val TextSecondary = Color(0xFFCBD5E1)
    val TextTertiary = Color(0xFF94A3B8)

    // Signals & Accents
    val CobaltSignal = Color(0xFF2563EB)
    val TerminalGreen = Color(0xFF10B981)
    val AmberSignal = Color(0xFFF59E0B)
    val ErrorSignal = Color(0xFFEF4444)

    // Layout & Geometry
    val ScreenGutter = 16.dp
    val Gutter = ScreenGutter
    val HeaderHeight = 44.dp
    val SectionTitleToContent = 12.dp
    val SectionGroupLabelToContent = 8.dp
    val SectionToSectionGap = 24.dp
    val RowDenseHeight = 44.dp
    val RowStandardHeight = 52.dp
    val CardCornerRadius = 16.dp
    val CardCornerRadiusSmall = 12.dp
    val SheetCornerRadius = 24.dp
    val GrabberWidth = 36.dp
    val GrabberHeight = 4.dp
    val ChipHeight = 30.dp
}

/**
 * ui-craft standard spacing, sizing, and typography tokens.
 * Mapped to ObsidianTokens for clean backwards compatibility.
 */
object UiCraftTokens {
    val ScreenGutter = ObsidianTokens.ScreenGutter
    val Gutter = ObsidianTokens.Gutter
    val HeaderHeight = ObsidianTokens.HeaderHeight
    val SectionTitleToContent = ObsidianTokens.SectionTitleToContent
    val SectionGroupLabelToContent = ObsidianTokens.SectionGroupLabelToContent
    val SectionToSectionGap = ObsidianTokens.SectionToSectionGap
    val RowDenseHeight = ObsidianTokens.RowDenseHeight
    val RowStandardHeight = ObsidianTokens.RowStandardHeight
    val CardCornerRadius = ObsidianTokens.CardCornerRadius
    val CardCornerRadiusSmall = ObsidianTokens.CardCornerRadiusSmall
    val SheetCornerRadius = ObsidianTokens.SheetCornerRadius
    val GrabberWidth = ObsidianTokens.GrabberWidth
    val GrabberHeight = ObsidianTokens.GrabberHeight
    val ChipHeight = ObsidianTokens.ChipHeight
}

/**
 * Tabular figures (tnum) extension for typography.
 * Prevents jitter/jumping when numbers (token count, elapsed time, tok/s) refresh rapidly.
 */
fun TextStyle.withTabularNumbers(): TextStyle = copy(
    fontFeatureSettings = "tnum"
)

/**
 * ui-craft Rule 15: Brand Atmosphere Glow.
 * A gentle vertical brand gradient (~380dp) under the top status bar.
 * Gives identity and depth without painting surfaces heavily.
 */
@Composable
fun BrandAtmosphereGlow(
    modifier: Modifier = Modifier,
    height: Dp = 380.dp,
    glowColor: Color = MaterialTheme.colorScheme.primary,
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(height)
            .drawBehind {
                val brush = Brush.verticalGradient(
                    0.0f to glowColor.copy(alpha = 0.12f),
                    0.3f to glowColor.copy(alpha = 0.05f),
                    0.6f to glowColor.copy(alpha = 0.02f),
                    1.0f to Color.Transparent,
                )
                drawRect(brush = brush)
            }
    )
}

