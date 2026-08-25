package com.raomuhammadnoman.zea

import android.content.Intent
import android.util.Log
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.rememberCoroutineScope
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class ZeaAppsSort {
    NAME,
    STATUS,
    RECENTLY_INSTALLED,
    RECENTLY_HIDDEN,
    RECENTLY_UNHIDDEN
}

enum class ZeaAppsFilter {
    ALL,
    VISIBLE,
    HIDDEN,
    TIMED,
    SYSTEM_APPS,
    USER_APPS,
    PROTECTED,
    UNPROTECTED,
    RECENTLY_INSTALLED,
    RECENTLY_MANAGED
}

/**
 * Full list of the apps Zea can see, with search, sorting and long press
 * management.
 *
 * Hiding runs through [ZeaAppHideService], so this screen only owns the
 * question and the outcome message; the protection transaction and its
 * recovery stay in one place.
 */
@Composable
fun ZeaAllAppsScreen(
    onBack: () -> Unit,
    onNavigate: (ZeaAppsRoute) -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var apps by remember { mutableStateOf<List<ZeaManagedApp>?>(null) }
    var query by rememberSaveable { mutableStateOf("") }
    var sort by rememberSaveable { mutableStateOf(ZeaAppsSort.NAME) }
    var filter by rememberSaveable { mutableStateOf(ZeaAppsFilter.ALL) }
    var recentlyManaged by remember { mutableStateOf<List<ZeaRecentlyManagedEntry>>(emptyList()) }
    var favoritePackages by remember { mutableStateOf<Set<String>>(emptySet()) }
    var menuExpanded by remember { mutableStateOf(false) }
    var reloadToken by remember { mutableStateOf(0) }
    val searchFocus = remember { FocusRequester() }

    var sheetApp by remember { mutableStateOf<ZeaManagedApp?>(null) }
    var confirmApp by remember { mutableStateOf<ZeaManagedApp?>(null) }
    var durationApp by remember { mutableStateOf<ZeaManagedApp?>(null) }
    var hideInProgress by remember { mutableStateOf(false) }
    var outcomeMessage by remember { mutableStateOf("") }
    var outcomeSuccess by remember { mutableStateOf(true) }
    var firstProtectedApp by remember { mutableStateOf(true) }
    var usageAccessGranted by remember { mutableStateOf(true) }

    var selectedPackages by remember { mutableStateOf(emptySet<String>()) }
    var manualSelecting by remember { mutableStateOf(false) }
    var showBulkSheet by remember { mutableStateOf(false) }
    var showBulkHideConfirm by remember { mutableStateOf(false) }
    var showBulkTimeSheet by remember { mutableStateOf(false) }
    var bulkProgress by remember { mutableStateOf<Pair<Int, Int>?>(null) }
    var bulkOutcome by remember { mutableStateOf<Pair<Int, List<String>>?>(null) }
    var isRefreshingList by remember { mutableStateOf(false) }

    LaunchedEffect(reloadToken) {
        recentlyManaged = ZeaRecentlyManaged.load(context)
        favoritePackages = ZeaFavorites.load(context).toSet()
        try {
            // Every reload runs the same Phase-1 reconciliation pipeline, so
            // post-mutation and pull-to-refresh renders cannot use stale state.
            val refreshed = ZeaPhase1Stability.refresh(context, "all_apps")
            val refreshedApps = if (refreshed.duplicateSkipped || (!refreshed.success && refreshed.apps.isEmpty())) {
                ZeaAppCatalog.loadManagedApps(context)
            } else {
                refreshed.apps
            }
            val visibleApps = refreshedApps.filter { app -> app.hideMode == ZeaHideMode.VISIBLE }
            apps = visibleApps
            val validPackages = visibleApps.mapTo(mutableSetOf()) { it.packageName }
            val prunedSelection = selectedPackages.filterTo(mutableSetOf()) { it in validPackages }
            if (prunedSelection != selectedPackages) {
                selectedPackages = prunedSelection
                if (prunedSelection.isEmpty()) {
                    manualSelecting = false
                    showBulkSheet = false
                    showBulkHideConfirm = false
                    showBulkTimeSheet = false
                }
            }
            if (!refreshed.success && !refreshed.duplicateSkipped) {
                Log.w("ZeaPTR", refreshed.message)
            }
            firstProtectedApp = ZeaAppHideService.isFirstHiddenApp(context)
            usageAccessGranted = withContext(Dispatchers.IO) {
                ZeaDeviceOwnerController.readUiState(context).usageAccessGranted
            }
        } finally {
            isRefreshingList = false
        }
    }

    val loadedApps = apps
    val shownApps = remember(loadedApps, query, sort, filter, recentlyManaged) {
        filterAndSortApps(
            apps = loadedApps.orEmpty(),
            query = query,
            sort = sort,
            filter = filter,
            recentlyManaged = recentlyManaged
        )
    }

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background
    ) {
        ZeaPullToRefreshLayout(
            isRefreshing = isRefreshingList,
            onRefresh = {
                Log.i("ZeaPTR", "allapps onRefresh gesture received")
                if (!isRefreshingList && !hideInProgress && bulkProgress == null) {
                    isRefreshingList = true
                    reloadToken++
                } else {
                    Log.i("ZeaPTR", "allapps refresh skipped: busy=$isRefreshingList hide=$hideInProgress bulk=${bulkProgress != null}")
                }
            },
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
        ) {
        // A single scrollable container (like the home screen) keeps
        // pull-to-refresh alive from every part of the screen, including the
        // search field and empty states, where a bare LazyColumn would swallow
        // or ignore the drag.
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 8.dp, end = 8.dp, top = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = onBack) {
                    Icon(
                        imageVector = ZeaIcons.Back,
                        contentDescription = "Back"
                    )
                }

                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "All Apps",
                        style = MaterialTheme.typography.headlineSmall
                    )

                    Text(
                        text = if (loadedApps == null) {
                            "Loading apps"
                        } else {
                            "${loadedApps.size} apps"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = ZeaColors.SecondaryText
                    )
                }

                IconButton(onClick = { searchFocus.requestFocus() }) {
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
                            text = { Text("Sort by Name") },
                            leadingIcon = {
                                Icon(
                                    imageVector = ZeaIcons.SortByName,
                                    contentDescription = null
                                )
                            },
                            trailingIcon = {
                                if (sort == ZeaAppsSort.NAME) {
                                    Icon(
                                        imageVector = ZeaIcons.Confirm,
                                        contentDescription = null
                                    )
                                }
                            },
                            onClick = {
                                menuExpanded = false
                                sort = ZeaAppsSort.NAME
                            }
                        )
                        DropdownMenuItem(
                            text = { Text("Sort by Status") },
                            leadingIcon = {
                                Icon(
                                    imageVector = ZeaIcons.Protection,
                                    contentDescription = null
                                )
                            },
                            trailingIcon = {
                                if (sort == ZeaAppsSort.STATUS) {
                                    Icon(
                                        imageVector = ZeaIcons.Confirm,
                                        contentDescription = null
                                    )
                                }
                            },
                            onClick = {
                                menuExpanded = false
                                sort = ZeaAppsSort.STATUS
                            }
                        )

                        // Phase 3: Filter options
                        DropdownMenuItem(
                            text = { Text("Filter: All Apps") },
                            leadingIcon = {
                                if (filter == ZeaAppsFilter.ALL) {
                                    Icon(
                                        imageVector = ZeaIcons.Confirm,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.primary
                                    )
                                }
                            },
                            onClick = {
                                filter = ZeaAppsFilter.ALL
                            }
                        )
                        DropdownMenuItem(
                            text = { Text("Filter: Visible Only") },
                            leadingIcon = {
                                if (filter == ZeaAppsFilter.VISIBLE) {
                                    Icon(
                                        imageVector = ZeaIcons.Confirm,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.primary
                                    )
                                }
                            },
                            onClick = {
                                filter = ZeaAppsFilter.VISIBLE
                            }
                        )
                        DropdownMenuItem(
                            text = { Text("Filter: Hidden Only") },
                            leadingIcon = {
                                if (filter == ZeaAppsFilter.HIDDEN) {
                                    Icon(
                                        imageVector = ZeaIcons.Confirm,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.primary
                                    )
                                }
                            },
                            onClick = {
                                filter = ZeaAppsFilter.HIDDEN
                            }
                        )
                        DropdownMenuItem(
                            text = { Text("Filter: Timed Only") },
                            leadingIcon = {
                                if (filter == ZeaAppsFilter.TIMED) {
                                    Icon(
                                        imageVector = ZeaIcons.Confirm,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.primary
                                    )
                                }
                            },
                            onClick = {
                                filter = ZeaAppsFilter.TIMED
                            }
                        )
                        DropdownMenuItem(
                            text = { Text("Filter: System Apps") },
                            leadingIcon = {
                                if (filter == ZeaAppsFilter.SYSTEM_APPS) {
                                    Icon(
                                        imageVector = ZeaIcons.Confirm,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.primary
                                    )
                                }
                            },
                            onClick = {
                                filter = ZeaAppsFilter.SYSTEM_APPS
                            }
                        )
                        DropdownMenuItem(
                            text = { Text("Filter: User Apps") },
                            leadingIcon = {
                                if (filter == ZeaAppsFilter.USER_APPS) {
                                    Icon(
                                        imageVector = ZeaIcons.Confirm,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.primary
                                    )
                                }
                            },
                            onClick = {
                                filter = ZeaAppsFilter.USER_APPS
                            }
                        )
                        DropdownMenuItem(
                            text = { Text("Filter: Protected") },
                            leadingIcon = {
                                if (filter == ZeaAppsFilter.PROTECTED) {
                                    Icon(
                                        imageVector = ZeaIcons.Confirm,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.primary
                                    )
                                }
                            },
                            onClick = {
                                filter = ZeaAppsFilter.PROTECTED
                            }
                        )
                        DropdownMenuItem(
                            text = { Text("Filter: Unprotected") },
                            leadingIcon = {
                                if (filter == ZeaAppsFilter.UNPROTECTED) {
                                    Icon(
                                        imageVector = ZeaIcons.Confirm,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.primary
                                    )
                                }
                            },
                            onClick = {
                                filter = ZeaAppsFilter.UNPROTECTED
                            }
                        )
                        DropdownMenuItem(
                            text = { Text("Filter: Recently Managed") },
                            leadingIcon = {
                                if (filter == ZeaAppsFilter.RECENTLY_MANAGED) {
                                    Icon(
                                        imageVector = ZeaIcons.Confirm,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.primary
                                    )
                                }
                            },
                            onClick = {
                                filter = ZeaAppsFilter.RECENTLY_MANAGED
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

            OutlinedTextField(
                value = query,
                onValueChange = { value -> query = value },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 20.dp, end = 20.dp, top = 8.dp, bottom = 12.dp)
                    .focusRequester(searchFocus),
                placeholder = { Text("Search apps...") },
                leadingIcon = {
                    Icon(
                        imageVector = ZeaIcons.Search,
                        contentDescription = null
                    )
                },
                trailingIcon = {
                    if (query.isNotEmpty()) {
                        IconButton(onClick = { query = "" }) {
                            Icon(
                                imageVector = ZeaIcons.Cancel,
                                contentDescription = "Clear search"
                            )
                        }
                    }
                },
                singleLine = true,
                shape = RoundedCornerShape(28.dp)
            )

            if (shownApps.isNotEmpty()) {
                val selectionActive = selectedPackages.isNotEmpty() || manualSelecting
                if (selectionActive) {
                    ZeaBulkSelectBar(
                        selectedCount = selectedPackages.size,
                        filteredCount = shownApps.size,
                        actionLabel = "Hide",
                        onAction = { showBulkSheet = true },
                        onClear = {
                            selectedPackages = emptySet()
                            manualSelecting = false
                        }
                    )
                } else {
                    ZeaSelectionEntryBar(
                        totalCount = shownApps.size,
                        onSelectAll = {
                            selectedPackages = shownApps.map { it.packageName }.toSet()
                        },
                        onSelectManually = { manualSelecting = true }
                    )
                }
            }

            if (outcomeMessage.isNotEmpty()) {
                ZeaHideOutcomeBanner(
                    message = outcomeMessage,
                    success = outcomeSuccess,
                    onDismiss = { outcomeMessage = "" }
                )
            }

            if (loadedApps == null) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(32.dp),
                    contentAlignment = Alignment.TopCenter
                ) {
                    CircularProgressIndicator()
                }
            } else {
                if (shownApps.isEmpty()) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(32.dp),
                        contentAlignment = Alignment.TopCenter
                    ) {
                        Text(
                            text = "No app matches \"$query\".",
                            style = MaterialTheme.typography.bodyMedium,
                            color = ZeaColors.SecondaryText
                        )
                    }
                } else {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        shownApps.forEach { app ->
                            val selectionActive = selectedPackages.isNotEmpty() || manualSelecting
                        ZeaAppRow(
                            app = app,
                            selectionActive = selectionActive,
                            isSelected = app.packageName in selectedPackages,
                            isFavorite = app.packageName in favoritePackages,
                            onLaunch = {
                                if (selectionActive) {
                                    selectedPackages = if (app.packageName in selectedPackages) {
                                        selectedPackages - app.packageName
                                    } else {
                                        selectedPackages + app.packageName
                                    }
                                } else {
                                    val intent = context.packageManager
                                        .getLaunchIntentForPackage(app.packageName)
                                    if (intent == null) {
                                        outcomeSuccess = false
                                        outcomeMessage =
                                            "${app.displayName} cannot be opened from here."
                                    } else {
                                        context.startActivity(
                                            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                        )
                                    }
                                }
                            },
                            onLongPress = {
                                if (selectionActive) {
                                    showBulkSheet = true
                                } else {
                                    sheetApp = app
                                }
                            }
                            )
                        }
                    }
                }
            }
        }
        }
    }

    val activeSheetApp = sheetApp
    if (activeSheetApp != null) {
        ZeaAppActionSheet(
            app = activeSheetApp,
            onHideApp = {
                sheetApp = null
                confirmApp = activeSheetApp
            },
            onHideForTime = {
                sheetApp = null
                durationApp = activeSheetApp
            },
            onDismiss = { sheetApp = null }
        )
    }

    val activeConfirmApp = confirmApp
    if (activeConfirmApp != null) {
        ZeaHideConfirmDialog(
            app = activeConfirmApp,
            firstProtectedApp = firstProtectedApp,
            usageAccessGranted = usageAccessGranted,
            inProgress = hideInProgress,
            onConfirm = {
                if (!hideInProgress) {
                    hideInProgress = true
                    scope.launch {
                        val outcome = ZeaAppHideService.hideApp(context, activeConfirmApp)

                        hideInProgress = false
                        confirmApp = null
                        outcomeSuccess = outcome.success
                        outcomeMessage = outcome.message
                        reloadToken++
                    }
                }
            },
            onDismiss = { confirmApp = null }
        )
    }

    val activeDurationApp = durationApp
    if (activeDurationApp != null) {
        ZeaHideForTimeSheet(
            displayName = activeDurationApp.displayName,
            firstProtectedApp = firstProtectedApp,
            usageAccessGranted = usageAccessGranted,
            inProgress = hideInProgress,
            onConfirm = { request ->
                if (!hideInProgress) {
                    hideInProgress = true
                    scope.launch {
                        val outcome = ZeaAppHideService.hideAppForTime(
                            context,
                            activeDurationApp,
                            request
                        )

                        hideInProgress = false
                        durationApp = null
                        outcomeSuccess = outcome.success
                        outcomeMessage = outcome.message
                        reloadToken++
                    }
                }
            },
            onDismiss = { durationApp = null }
        )
    }

    if (showBulkSheet && selectedPackages.isNotEmpty()) {
        ZeaBulkActionSheet(
            appCount = selectedPackages.size,
            onHideAllNow = {
                showBulkSheet = false
                showBulkHideConfirm = true
            },
            onHideAllForTime = {
                showBulkSheet = false
                showBulkTimeSheet = true
            },
            onDismiss = { showBulkSheet = false }
        )
    }

    if (showBulkHideConfirm) {
        val bulkTargets = shownApps.filter { it.packageName in selectedPackages }
        ZeaBulkHideConfirmDialog(
            apps = bulkTargets,
            firstProtectedApp = firstProtectedApp,
            usageAccessGranted = usageAccessGranted,
            inProgress = bulkProgress != null,
            onConfirm = {
                if (bulkProgress == null) {
                    scope.launch {
                        val request: ZeaTimedHideRequest? = null
                        val result = runBulkHide(context, bulkTargets, request) { done, total ->
                            bulkProgress = done to total
                        }
                        bulkProgress = null
                        showBulkHideConfirm = false
                        selectedPackages = emptySet()
                        manualSelecting = false
                        bulkOutcome = result
                        reloadToken++
                    }
                }
            },
            onDismiss = { if (bulkProgress == null) showBulkHideConfirm = false }
        )
    }

    if (showBulkTimeSheet) {
        ZeaHideForTimeSheet(
            displayName = "${selectedPackages.size} selected apps",
            firstProtectedApp = firstProtectedApp,
            usageAccessGranted = usageAccessGranted,
            inProgress = bulkProgress != null,
            onConfirm = { request ->
                if (bulkProgress == null) {
                    scope.launch {
                        val targets = shownApps.filter { it.packageName in selectedPackages }
                        val result = runBulkHide(context, targets, request) { done, total ->
                            bulkProgress = done to total
                        }
                        bulkProgress = null
                        showBulkTimeSheet = false
                        selectedPackages = emptySet()
                        manualSelecting = false
                        bulkOutcome = result
                        reloadToken++
                    }
                }
            },
            onDismiss = { if (bulkProgress == null) showBulkTimeSheet = false }
        )
    }

    val activeBulkProgress = bulkProgress
    if (activeBulkProgress != null) {
        ZeaBulkProgressDialog(
            label = "Hiding ${activeBulkProgress.first} of ${activeBulkProgress.second}"
        )
    }

    val activeBulkOutcome = bulkOutcome
    if (activeBulkOutcome != null) {
        ZeaBulkOutcomeDialog(
            successCount = activeBulkOutcome.first,
            failures = activeBulkOutcome.second,
            actionLabel = "hidden",
            onDismiss = { bulkOutcome = null }
        )
    }
}

@Composable
private fun ZeaHideOutcomeBanner(
    message: String,
    success: Boolean,
    onDismiss: () -> Unit
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 20.dp, end = 20.dp, bottom = 12.dp),
        shape = RoundedCornerShape(12.dp),
        color = if (success) {
            ZeaColors.BannerSuccessBackground
        } else {
            ZeaColors.BannerErrorBackground
        }
    ) {
        Row(
            modifier = Modifier.padding(start = 12.dp, top = 10.dp, bottom = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = message,
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.bodySmall,
                color = if (success) {
                    ZeaColors.BannerSuccessText
                } else {
                    ZeaColors.BannerErrorText
                }
            )

            IconButton(onClick = onDismiss) {
                Icon(
                    imageVector = ZeaIcons.Cancel,
                    contentDescription = "Dismiss message"
                )
            }
        }
    }
}

@Composable
private fun ZeaAppsMessage(content: @Composable () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        contentAlignment = Alignment.TopCenter
    ) {
        content()
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ZeaAppRow(
    app: ZeaManagedApp,
    selectionActive: Boolean,
    isSelected: Boolean,
    isFavorite: Boolean,
    onLaunch: () -> Unit,
    onLongPress: () -> Unit
) {
    val rowShape = RoundedCornerShape(16.dp)

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clip(rowShape)
            .combinedClickable(
                onClick = onLaunch,
                onLongClick = onLongPress
            ),
        shape = rowShape,
        color = if (isSelected) ZeaColors.IconContainer else ZeaColors.CardBackground,
        border = BorderStroke(
            1.dp,
            if (isSelected) MaterialTheme.colorScheme.primary else ZeaColors.CardBorder
        )
    ) {
        Row(
            modifier = Modifier.padding(
                start = 14.dp,
                end = 14.dp,
                top = 12.dp,
                bottom = 12.dp
            ),
            verticalAlignment = Alignment.CenterVertically
        ) {
            ZeaAppIcon(packageName = app.packageName)

            Spacer(modifier = Modifier.width(14.dp))

            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (isFavorite) {
                        Text(
                            text = "★ ",
                            color = Color(0xFFB8860B),
                            style = MaterialTheme.typography.titleMedium
                        )
                    }
                    Text(
                        text = app.displayName,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                Text(
                    text = app.packageName,
                    style = MaterialTheme.typography.bodySmall,
                    color = ZeaColors.SecondaryText,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )

                Spacer(modifier = Modifier.height(6.dp))

                // The status column already names a protected app, so the row
                // only tags where the app came from.
                ZeaAppTag(
                    label = if (app.systemApp) "System App" else "User App"
                )
            }

            Spacer(modifier = Modifier.width(12.dp))

            Text(
                text = statusLabel(app),
                style = MaterialTheme.typography.labelLarge,
                color = statusColor(app)
            )

            if (selectionActive) {
                Spacer(modifier = Modifier.width(8.dp))

                Checkbox(
                    checked = isSelected,
                    onCheckedChange = null
                )
            }
        }
    }
}

@Composable
private fun ZeaAppTag(label: String) {
    Surface(
        shape = RoundedCornerShape(8.dp),
        color = ZeaColors.BadgeBackground
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = ZeaColors.StatusTimed,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
        )
    }
}

@Composable
internal fun ZeaAppIcon(packageName: String) {
    val context = LocalContext.current
    var icon by remember(packageName) {
        mutableStateOf(ZeaAppIconLoader.cached(packageName))
    }

    LaunchedEffect(packageName) {
        if (icon == null) {
            icon = withContext(Dispatchers.IO) {
                ZeaAppIconLoader.load(context, packageName)
            }
        }
    }

    val loadedIcon = icon

    Surface(
        modifier = Modifier.size(48.dp),
        shape = RoundedCornerShape(12.dp),
        color = if (loadedIcon == null) ZeaColors.IconContainer else Color.Transparent
    ) {
        if (loadedIcon != null) {
            Image(
                bitmap = loadedIcon,
                contentDescription = null,
                modifier = Modifier.size(48.dp)
            )
        }
    }
}

private fun statusLabel(app: ZeaManagedApp): String = when {
    !app.manageable -> "Protected"
    app.hideMode == ZeaHideMode.HIDDEN -> "Hidden"
    app.hideMode == ZeaHideMode.TIMED -> "Timed"
    else -> "Visible"
}

private fun statusColor(app: ZeaManagedApp): Color = when {
    !app.manageable -> ZeaColors.StatusBlocked
    app.hideMode == ZeaHideMode.HIDDEN -> ZeaColors.StatusHidden
    app.hideMode == ZeaHideMode.TIMED -> ZeaColors.StatusTimed
    else -> ZeaColors.StatusVisible
}

/** Hidden and timed apps sort first, because those are the ones being managed. */
private fun statusRank(app: ZeaManagedApp): Int = when {
    app.hideMode == ZeaHideMode.HIDDEN -> 0
    app.hideMode == ZeaHideMode.TIMED -> 1
    !app.manageable -> 3
    else -> 2
}

internal fun filterAndSortApps(
    apps: List<ZeaManagedApp>,
    query: String,
    sort: ZeaAppsSort,
    filter: ZeaAppsFilter = ZeaAppsFilter.ALL,
    recentlyManaged: List<ZeaRecentlyManagedEntry> = emptyList()
): List<ZeaManagedApp> {
    val trimmedQuery = query.trim().lowercase(Locale.ROOT)

    val filtered = when (filter) {
        ZeaAppsFilter.ALL -> apps
        ZeaAppsFilter.VISIBLE -> apps.filter { it.hideMode == ZeaHideMode.VISIBLE }
        ZeaAppsFilter.HIDDEN -> apps.filter { it.hideMode == ZeaHideMode.HIDDEN }
        ZeaAppsFilter.TIMED -> apps.filter { it.hideMode == ZeaHideMode.TIMED }
        ZeaAppsFilter.SYSTEM_APPS -> apps.filter { it.systemApp }
        ZeaAppsFilter.USER_APPS -> apps.filter { !it.systemApp }
        ZeaAppsFilter.PROTECTED -> apps.filter { it.hideMode != ZeaHideMode.VISIBLE }
        ZeaAppsFilter.UNPROTECTED -> apps.filter { it.hideMode == ZeaHideMode.VISIBLE }
        ZeaAppsFilter.RECENTLY_INSTALLED -> apps.sortedByDescending { it.packageName.hashCode() }
        ZeaAppsFilter.RECENTLY_MANAGED -> {
            val recentOrder = recentlyManaged
                .sortedByDescending { it.epochMillis }
                .map { it.packageName }
                .distinct()
            apps.sortedByDescending { app ->
                recentOrder.indexOf(app.packageName).takeIf { it >= 0 } ?: Int.MIN_VALUE
            }
        }
    }

    val matching = if (trimmedQuery.isEmpty()) {
        filtered
    } else {
        filtered.filter { app ->
            app.displayName.lowercase(Locale.ROOT).contains(trimmedQuery) ||
                    app.packageName.lowercase(Locale.ROOT).contains(trimmedQuery)
        }
    }

    return when (sort) {
        // The catalog already hands back a name-ordered list.
        ZeaAppsSort.NAME -> matching
        ZeaAppsSort.STATUS -> matching.sortedWith(
            compareBy(
                { app -> statusRank(app) },
                { app -> app.displayName.lowercase(Locale.ROOT) }
            )
        )
        ZeaAppsSort.RECENTLY_INSTALLED -> matching.sortedByDescending { it.packageName.hashCode() }
        ZeaAppsSort.RECENTLY_HIDDEN -> matching.sortedByDescending { app ->
            recentlyManaged
                .firstOrNull { it.packageName == app.packageName && it.operation == "Hide" }
                ?.epochMillis ?: 0L
        }
        ZeaAppsSort.RECENTLY_UNHIDDEN -> matching.sortedByDescending { app ->
            recentlyManaged
                .firstOrNull { it.packageName == app.packageName && it.operation == "Unhide" }
                ?.epochMillis ?: 0L
        }
    }
}
