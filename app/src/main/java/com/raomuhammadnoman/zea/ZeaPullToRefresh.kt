package com.raomuhammadnoman.zea

import androidx.compose.foundation.layout.BoxScope
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/**
 * Shared pull-down-to-refresh container for the Apps screens. Wraps a screen's
 * content in the standard Material refresh layout so every list reloads with
 * the same gesture and indicator as the home screen.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ZeaPullToRefreshLayout(
    isRefreshing: Boolean,
    onRefresh: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit
) {
    PullToRefreshBox(
        isRefreshing = isRefreshing,
        onRefresh = onRefresh,
        modifier = modifier,
        content = content
    )
}
