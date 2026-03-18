package com.heideen.smsbackup.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val LightColors =
    lightColorScheme(
        primary = Color(0xFF1A6EBF),
        onPrimary = Color.White,
        primaryContainer = Color(0xFFD6E4FF),
        error = Color(0xFFB00020),
        onError = Color.White,
        errorContainer = Color(0xFFFFDAD6))

private val DarkColors =
    darkColorScheme(
        primary = Color(0xFF90CAF9),
        onPrimary = Color(0xFF003258),
        primaryContainer = Color(0xFF004A7C),
        error = Color(0xFFCF6679),
        errorContainer = Color(0xFF8C1D18))

@Composable
fun SmsBackupTheme(darkTheme: Boolean = false, content: @Composable () -> Unit) {
  MaterialTheme(colorScheme = if (darkTheme) DarkColors else LightColors, content = content)
}
