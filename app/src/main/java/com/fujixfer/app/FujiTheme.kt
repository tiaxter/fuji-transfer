package com.fujixfer.app

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.colorResource

/**
 * Material 3 theme of the Compose screens. The colours are the same `md_*` roles the Views use (res/values,
 * values-night and values-v31 for the wallpaper colours), read with colorResource, so a screen written in Compose
 * looks like its neighbours and follows dark mode and Material You without a second palette to keep in sync.
 * lightColorScheme only supplies the roles the app does not define (inverse, scrim...): all the ones in use are set.
 */
@Composable
fun FujiTheme(content: @Composable () -> Unit) {
    val scheme = lightColorScheme(
        primary = colorResource(R.color.md_primary),
        onPrimary = colorResource(R.color.md_on_primary),
        primaryContainer = colorResource(R.color.md_primary_container),
        onPrimaryContainer = colorResource(R.color.md_on_primary_container),
        secondaryContainer = colorResource(R.color.md_secondary_container),
        onSecondaryContainer = colorResource(R.color.md_on_secondary_container),
        tertiaryContainer = colorResource(R.color.md_tertiary_container),
        onTertiaryContainer = colorResource(R.color.md_on_tertiary_container),
        background = colorResource(R.color.md_background),
        onBackground = colorResource(R.color.md_on_surface),
        surface = colorResource(R.color.md_background),
        onSurface = colorResource(R.color.md_on_surface),
        onSurfaceVariant = colorResource(R.color.md_on_surface_variant),
        outline = colorResource(R.color.md_outline),
        outlineVariant = colorResource(R.color.md_outline_variant),
        error = colorResource(R.color.md_error),
        errorContainer = colorResource(R.color.md_error_container),
        onErrorContainer = colorResource(R.color.md_on_error_container),
        surfaceContainerLowest = colorResource(R.color.md_surface_lowest),
        surfaceContainerLow = colorResource(R.color.md_surface_low),
        surfaceContainer = colorResource(R.color.md_surface_container),
        surfaceContainerHigh = colorResource(R.color.md_surface_high),
        surfaceContainerHighest = colorResource(R.color.md_surface_highest),
        // the snackbar after "Immich": the opposite of the surface, in light and in dark
        inverseSurface = colorResource(R.color.md_on_surface),
        inverseOnSurface = colorResource(R.color.md_background),
        inversePrimary = colorResource(R.color.md_primary_container),
    )
    MaterialTheme(colorScheme = scheme, content = content)
}
