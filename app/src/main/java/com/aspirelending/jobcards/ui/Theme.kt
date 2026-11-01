package com.aspirelending.jobcards.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

// Extracted from the Aspire Lending logo
object Aspire {
    val Deep = Color(0xFF04401A)      // wordmark green
    val Mid = Color(0xFF00A020)       // "LENDING LIMITED" green
    val Bright = Color(0xFF0BD52F)    // swoosh / highlight
    val Night = Color(0xFF02150A)     // dark surface derived from Deep
    val NightRaised = Color(0xFF082A14)
    val Mist = Color(0xFFF2F7F3)
}

private val Dark = darkColorScheme(
    primary = Aspire.Bright, onPrimary = Aspire.Night,
    secondary = Aspire.Mid, tertiary = Color(0xFF7CF29A),
    background = Aspire.Night, surface = Aspire.NightRaised,
    onBackground = Color(0xFFE6F2E9), onSurface = Color(0xFFE6F2E9),
    surfaceVariant = Color(0xFF0E3A1D), outline = Color(0xFF2E6B42)
)
private val Light = lightColorScheme(
    primary = Aspire.Deep, onPrimary = Color.White,
    secondary = Aspire.Mid, tertiary = Aspire.Bright,
    background = Aspire.Mist, surface = Color.White,
    onBackground = Color(0xFF0B1A10), onSurface = Color(0xFF0B1A10),
    surfaceVariant = Color(0xFFDCEBDF), outline = Color(0xFF7FA088)
)

@Composable
fun AspireTheme(content: @Composable () -> Unit) =
    MaterialTheme(colorScheme = if (isSystemInDarkTheme()) Dark else Light, content = content)
