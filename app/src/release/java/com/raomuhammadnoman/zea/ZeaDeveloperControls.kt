package com.raomuhammadnoman.zea

import androidx.compose.runtime.Composable

/**
 * Phase 2 (P1) - RELEASE stub for the developer surface.
 *
 * The real developer gate, access key, and controls screen live exclusively
 * in app/src/debug. Release builds compile THIS file instead, so production
 * APKs physically contain no developer key, no unlock comparison, and no
 * developer UI - the backdoor is removed from production by construction,
 * not merely hidden.
 *
 * Shared code (MainActivity) may still reference these symbols; in release
 * they are constant-false / no-op so the compiler dead-strips every caller.
 */
val zeaDeveloperControlsEnabled: Boolean
    get() = false

@Composable
fun ZeaDeveloperAccessScreen(
    onBack: () -> Unit,
    onGranted: () -> Unit
) {
    // No developer surface exists in release builds.
    onBack()
}

@Composable
fun ZeaDeveloperControlsScreen(onBack: () -> Unit) {
    // No developer surface exists in release builds.
    onBack()
}
