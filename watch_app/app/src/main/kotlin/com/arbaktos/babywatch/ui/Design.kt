package com.arbaktos.babywatch.ui

import androidx.compose.ui.graphics.Color

/**
 * Design tokens from the Squish handoff ("Dusk → Dawn" palette).
 * Source of truth: design_handoff_huckleberry_watch/README.md §Design Tokens.
 */
data class Palette(
    val bgFrom: Color,
    val bgTo: Color,
    val blobFrom: Color,
    val blobTo: Color,
    val glow: Color,
)

val AwakePalette = Palette(
    bgFrom = Color(0xFF4A2742),
    bgTo = Color(0xFF1C0D1E),
    blobFrom = Color(0xFFFFD9BD),
    blobTo = Color(0xFFFB8AA3),
    glow = Color(0xFFFB8AA3).copy(alpha = 0.55f),
)

val SleepingPalette = Palette(
    bgFrom = Color(0xFF1C1746),
    bgTo = Color(0xFF08071F),
    blobFrom = Color(0xFFA89DFF),
    blobTo = Color(0xFF5E4FD8),
    glow = Color(0xFF6A5BE6).copy(alpha = 0.6f),
)

val PausedPalette = Palette(
    bgFrom = Color(0xFF1A1830),
    bgTo = Color(0xFF0C0B18),
    blobFrom = Color(0xFFA39EC2),
    blobTo = Color(0xFF565279),
    glow = Color(0xFF565279).copy(alpha = 0.45f),
)

fun paletteFor(phase: Phase): Palette = when (phase) {
    Phase.SLEEPING -> SleepingPalette
    Phase.PAUSED -> PausedPalette
    else -> AwakePalette // AWAKE, LOADING, ERROR share the warm backdrop
}

/** Timer formats per the handoff §Screen/View. */
fun formatAwake(sec: Double): String {
    val s = sec.toLong()
    if (s < 60) return "just now"
    val h = s / 3600
    val m = (s % 3600) / 60
    return if (h == 0L) "${m}m" else "${h}h ${m}m"
}

fun formatSleep(sec: Double): String {
    val s = sec.toLong()
    val h = s / 3600
    val m = (s % 3600) / 60
    val ss = s % 60
    return if (h == 0L) "%d:%02d".format(m, ss) else "%d:%02d:%02d".format(h, m, ss)
}
