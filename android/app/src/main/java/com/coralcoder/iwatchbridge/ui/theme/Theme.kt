package com.coralcoder.iwatchbridge.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/**
 * iOS system colours (UIKit semantic colours), so the app reads like the iPhone Watch app
 * rather than a Material app. Values are the published light/dark pairs.
 */
@Immutable
data class IosColors(
    val tint: Color,
    val groupedBackground: Color,
    val cell: Color,
    val separator: Color,
    val label: Color,
    val secondaryLabel: Color,
    val tertiaryLabel: Color,
    val green: Color,
    val red: Color,
    val orange: Color,
    val gray: Color,
    val fill: Color,
)

val LightIos = IosColors(
    tint = Color(0xFF007AFF),
    groupedBackground = Color(0xFFF2F2F7),
    cell = Color.White,
    separator = Color(0x4A3C3C43),
    label = Color.Black,
    secondaryLabel = Color(0x993C3C43),
    tertiaryLabel = Color(0x4D3C3C43),
    green = Color(0xFF34C759),
    red = Color(0xFFFF3B30),
    orange = Color(0xFFFF9500),
    gray = Color(0xFF8E8E93),
    fill = Color(0x33787880),
)

val DarkIos = IosColors(
    tint = Color(0xFF0A84FF),
    groupedBackground = Color.Black,
    cell = Color(0xFF1C1C1E),
    separator = Color(0xA6545458),
    label = Color.White,
    secondaryLabel = Color(0x99EBEBF5),
    tertiaryLabel = Color(0x4DEBEBF5),
    green = Color(0xFF30D158),
    red = Color(0xFFFF453A),
    orange = Color(0xFFFF9F0A),
    gray = Color(0xFF8E8E93),
    fill = Color(0x5C787880),
)

val LocalIos = staticCompositionLocalOf { LightIos }

object Ios {
    val colors: IosColors
        @Composable get() = LocalIos.current
}

@Composable
fun BridgeTheme(dark: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    val ios = if (dark) DarkIos else LightIos
    val material = if (dark) {
        darkColorScheme(
            primary = ios.tint, background = ios.groupedBackground, surface = ios.cell,
            onBackground = ios.label, onSurface = ios.label, error = ios.red,
        )
    } else {
        lightColorScheme(
            primary = ios.tint, background = ios.groupedBackground, surface = ios.cell,
            onBackground = ios.label, onSurface = ios.label, error = ios.red,
        )
    }
    CompositionLocalProvider(LocalIos provides ios) {
        MaterialTheme(colorScheme = material, typography = IosTypography, content = content)
    }
}
