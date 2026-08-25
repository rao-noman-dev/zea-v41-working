package com.raomuhammadnoman.zea

import android.util.Log
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/**
 * Landing screen of the Apps section.
 *
 * An entry only becomes tappable once the screen behind it exists, so nothing
 * here offers an action that would silently do nothing.
 */
@Composable
fun ZeaAppsHubScreen(
    onNavigate: (ZeaAppsRoute) -> Unit
) {
    val context = LocalContext.current
    var allAppsCount by remember { mutableStateOf<Int?>(null) }
    var hiddenAppsCount by remember { mutableStateOf(0) }
    var timedHiddenAppsCount by remember { mutableStateOf(0) }
    var menuExpanded by remember { mutableStateOf(false) }
    var isRefreshing by remember { mutableStateOf(false) }
    var refreshTick by remember { mutableStateOf(0) }

    LaunchedEffect(refreshTick) {
        try {
            val refreshed = ZeaPhase1Stability.refresh(context, "apps_hub")
            val apps = if (refreshed.duplicateSkipped || (!refreshed.success && refreshed.apps.isEmpty())) {
                ZeaAppCatalog.loadManagedApps(context)
            } else {
                refreshed.apps
            }
            if (!refreshed.success && !refreshed.duplicateSkipped) {
                Log.w("ZeaPTR", refreshed.message)
            }
            allAppsCount = apps.count { app -> app.hideMode == ZeaHideMode.VISIBLE }
            hiddenAppsCount = apps.count { app -> app.hideMode == ZeaHideMode.HIDDEN }
            timedHiddenAppsCount = apps.count { app -> app.hideMode == ZeaHideMode.TIMED }
        } finally {
            isRefreshing = false
        }
    }

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background
    ) {
        ZeaPullToRefreshLayout(
            isRefreshing = isRefreshing,
            onRefresh = {
                Log.i("ZeaPTR", "hub onRefresh gesture received")
                if (!isRefreshing) {
                    isRefreshing = true
                    refreshTick++
                }
            },
            modifier = Modifier.fillMaxSize()
        ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .padding(
                    start = 20.dp,
                    end = 20.dp,
                    top = 16.dp,
                    bottom = 20.dp
                )
                .verticalScroll(rememberScrollState())
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Apps",
                    style = MaterialTheme.typography.headlineLarge,
                    modifier = Modifier.weight(1f)
                )

                IconButton(
                    onClick = {},
                    enabled = false
                ) {
                    Icon(
                        imageVector = ZeaIcons.Search,
                        contentDescription = "Search apps"
                    )
                }

                Box {
                    IconButton(onClick = { menuExpanded = true }) {
                        Icon(
                            imageVector = ZeaIcons.Overflow,
                            contentDescription = "Apps options"
                        )
                    }

                    DropdownMenu(
                        expanded = menuExpanded,
                        onDismissRequest = { menuExpanded = false }
                    ) {
                        DropdownMenuItem(
                            text = { Text("All Apps") },
                            leadingIcon = {
                                Icon(
                                    imageVector = ZeaIcons.AppsGrid,
                                    contentDescription = null
                                )
                            },
                            onClick = {
                                menuExpanded = false
                                onNavigate(ZeaAppsRoute.ALL_APPS)
                            }
                        )
                        DropdownMenuItem(
                            text = { Text("Hidden Apps") },
                            leadingIcon = {
                                Icon(
                                    imageVector = ZeaIcons.Hidden,
                                    contentDescription = null
                                )
                            },
                            onClick = {
                                menuExpanded = false
                                onNavigate(ZeaAppsRoute.HIDDEN_APPS)
                            }
                        )
                        DropdownMenuItem(
                            text = { Text("Timed Hidden Apps") },
                            leadingIcon = {
                                Icon(
                                    imageVector = ZeaIcons.Timed,
                                    contentDescription = null
                                )
                            },
                            onClick = {
                                menuExpanded = false
                                onNavigate(ZeaAppsRoute.TIMED_HIDDEN_APPS)
                            }
                        )
                        ZeaHomeMenuPlaceholder(
                            label = "Settings",
                            icon = ZeaIcons.Settings
                        )
                    }
                }
            }

            Text(
                text = "Manage supported apps",
                style = MaterialTheme.typography.bodyMedium,
                color = ZeaColors.SecondaryText
            )

            Spacer(modifier = Modifier.height(20.dp))

            ZeaAppsHubCard(
                icon = ZeaIcons.AppsGrid,
                title = "All Apps",
                subtitle = "View all supported apps",
                count = allAppsCount,
                onClick = { onNavigate(ZeaAppsRoute.ALL_APPS) }
            )

            Spacer(modifier = Modifier.height(12.dp))

            ZeaAppsHubCard(
                icon = ZeaIcons.Hidden,
                title = "Hidden Apps",
                subtitle = "Apps currently hidden",
                count = hiddenAppsCount,
                onClick = { onNavigate(ZeaAppsRoute.HIDDEN_APPS) }
            )

            Spacer(modifier = Modifier.height(12.dp))

            ZeaAppsHubCard(
                icon = ZeaIcons.Timed,
                title = "Timed Hidden Apps",
                subtitle = "Apps hidden for a set time",
                count = timedHiddenAppsCount,
                onClick = { onNavigate(ZeaAppsRoute.TIMED_HIDDEN_APPS) }
            )
        }
        }
    }
}

@Composable
private fun ZeaAppsHubCard(
    icon: ImageVector,
    title: String,
    subtitle: String,
    count: Int?,
    onClick: (() -> Unit)?
) {
    val cardShape = RoundedCornerShape(20.dp)

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clip(cardShape)
            .then(
                if (onClick == null) {
                    Modifier
                } else {
                    Modifier.clickable(onClick = onClick)
                }
            ),
        shape = cardShape,
        color = ZeaColors.CardBackground,
        border = BorderStroke(1.dp, ZeaColors.CardBorder)
    ) {
        Row(
            modifier = Modifier.padding(
                start = 16.dp,
                end = 16.dp,
                top = 18.dp,
                bottom = 18.dp
            ),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Surface(
                modifier = Modifier.size(52.dp),
                shape = RoundedCornerShape(16.dp),
                color = ZeaColors.IconContainer
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = icon,
                        contentDescription = null,
                        tint = ZeaColors.StatusTimed,
                        modifier = Modifier.size(26.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.width(16.dp))

            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.Center
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold
                )

                Spacer(modifier = Modifier.height(2.dp))

                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = ZeaColors.SecondaryText
                )
            }

            if (count != null) {
                Spacer(modifier = Modifier.width(12.dp))

                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = ZeaColors.BadgeBackground
                ) {
                    Text(
                        text = count.toString(),
                        style = MaterialTheme.typography.labelLarge,
                        color = ZeaColors.StatusTimed,
                        modifier = Modifier.padding(
                            horizontal = 12.dp,
                            vertical = 6.dp
                        )
                    )
                }
            }

            Spacer(modifier = Modifier.width(8.dp))

            Icon(
                imageVector = ZeaIcons.Chevron,
                contentDescription = null,
                tint = ZeaColors.SecondaryText
            )
        }
    }
}
