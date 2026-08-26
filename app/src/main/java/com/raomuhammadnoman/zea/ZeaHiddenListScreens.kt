package com.raomuhammadnoman.zea

import android.util.Log
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.util.Locale
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Permanent hidden list. Timed hides are kept off this screen so the two
 * lists never show the same app twice.
 */
@Composable
fun ZeaHiddenAppsScreen(
    onBack: () -> Unit,
    onNavigate: (ZeaAppsRoute) -> Unit,
    onOpenDetails: (String) -> Unit = {}
) {
    ZeaHiddenListScreen(
        title = "Hidden Apps",
        emptyMessage = "No apps are hidden.",
        bannerTitle = "Permanently Hidden",
        bannerBody = "These apps are hidden and won't appear in the launcher or app drawer.",
        bannerIcon = ZeaIcons.Hidden,
        footerText = "Tap an app to open it. Long-press to manage.",
        mode = ZeaHideMode.HIDDEN,
        onBack = onBack,
        onNavigate = onNavigate,
        onOpenDetails = onOpenDetails
    )
}

/**
 * Apps waiting for their timer. Remaining time is shown on every row so the
 * automatic restore is visible without opening another screen.
 */
@Composable
fun ZeaTimedHiddenAppsScreen(
    onBack: () -> Unit,
    onNavigate: (ZeaAppsRoute) -> Unit,
    onOpenDetails: (String) -> Unit = {}
) {
    ZeaHiddenListScreen(
        title = "Timed Hidden Apps",
        emptyMessage = "No apps are hidden for a set time.",
        bannerTitle = "Hidden for a set time",
        bannerBody = "These apps are currently hidden and will automatically unhide when the timer ends.",
        bannerIcon = ZeaIcons.Timed,
        footerText = "Apps will unhide automatically when the timer ends.",
        mode = ZeaHideMode.TIMED,
        onBack = onBack,
        onNavigate = onNavigate,
        onOpenDetails = onOpenDetails
    )
}

@Composable
private fun ZeaHiddenListScreen(
    title: String,
    emptyMessage: String,
    bannerTitle: String,
    bannerBody: String,
    bannerIcon: ImageVector,
    footerText: String,
    mode: ZeaHideMode,
    onBack: () -> Unit,
    onNavigate: (ZeaAppsRoute) -> Unit,
    onOpenDetails: (String) -> Unit = {}
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var apps by remember { mutableStateOf<List<ZeaManagedApp>?>(null) }
    var query by rememberSaveable { mutableStateOf("") }
    var menuExpanded by remember { mutableStateOf(false) }
    var reloadToken by remember { mutableIntStateOf(0) }
    var clockToken by remember { mutableIntStateOf(0) }
    var confirmApp by remember { mutableStateOf<ZeaManagedApp?>(null) }
    var timedManageApp by remember { mutableStateOf<ZeaManagedApp?>(null) }
    var launchInProgress by remember { mutableStateOf(false) }
    var unhideInProgress by remember { mutableStateOf(false) }
    var outcomeMessage by remember { mutableStateOf("") }
    var outcomeSuccess by remember { mutableStateOf(true) }

    var selectedPackages by remember { mutableStateOf(emptySet<String>()) }
    var manualSelecting by remember { mutableStateOf(false) }
    var showBulkUnhideConfirm by remember { mutableStateOf(false) }
    var bulkProgress by remember { mutableStateOf<Pair<Int, Int>?>(null) }
    var bulkOutcome by remember { mutableStateOf<Pair<Int, List<String>>?>(null) }
    var isRefreshingList by remember { mutableStateOf(false) }

    LaunchedEffect(reloadToken) {
        try {
            val refreshed = ZeaPhase1Stability.refresh(
                context,
                if (mode == ZeaHideMode.TIMED) "timed_hidden_apps" else "hidden_apps"
            )
            val refreshedApps = if (refreshed.duplicateSkipped || (!refreshed.success && refreshed.apps.isEmpty())) {
                ZeaAppCatalog.loadManagedApps(context)
            } else {
                refreshed.apps
            }
            val modeApps = refreshedApps.filter { app -> app.hideMode == mode }
            apps = modeApps
            val validPackages = modeApps.mapTo(mutableSetOf()) { it.packageName }
            val prunedSelection = selectedPackages.filterTo(mutableSetOf()) { it in validPackages }
            if (prunedSelection != selectedPackages) {
                selectedPackages = prunedSelection
                if (prunedSelection.isEmpty()) {
                    manualSelecting = false
                    showBulkUnhideConfirm = false
                }
            }
            if (!refreshed.success && !refreshed.duplicateSkipped) {
                Log.w("ZeaPTR", refreshed.message)
            }
        } finally {
            isRefreshingList = false
        }
    }

    val loadedApps = apps

    // Under about a minute the row counts down every second; otherwise a
    // slower refresh keeps the minute-level labels honest without burning
    // battery on screens where nothing expires soon. Permanent hides carry a
    // zero timestamp, so only timed rows ever drive the fast clock.
    val soonestRemaining = if (mode == ZeaHideMode.TIMED) {
        loadedApps
            ?.mapNotNull { app ->
                if (app.hiddenUntilEpochMillis > 0L) {
                    app.hiddenUntilEpochMillis - System.currentTimeMillis()
                } else {
                    null
                }
            }
            ?.minOrNull()
            ?: Long.MAX_VALUE
    } else {
        Long.MAX_VALUE
    }
    val tickMillis = if (soonestRemaining <= 65_000L) 1_000L else 30_000L

    LaunchedEffect(tickMillis) {
        while (true) {
            delay(tickMillis)
            clockToken++
        }
    }

    // The moment a timer runs out, reload so the app leaves this list and
    // returns to All Apps instead of waiting for a manual refresh. The
    // cooldown keeps a failing release from turning into a reload storm.
    var lastAutoReloadAt by remember { mutableStateOf(0L) }
    LaunchedEffect(clockToken, loadedApps) {
        if (mode != ZeaHideMode.TIMED || loadedApps == null) return@LaunchedEffect
        val now = System.currentTimeMillis()
        val hasExpired = loadedApps.any { app ->
            app.hiddenUntilEpochMillis > 0L && app.hiddenUntilEpochMillis <= now
        }
        if (hasExpired && now - lastAutoReloadAt >= 5_000L) {
            lastAutoReloadAt = now
            reloadToken++
        }
    }

    val shownApps = remember(loadedApps, query) {
        filterHiddenApps(loadedApps.orEmpty(), query)
    }

    // Opening a hidden app runs the fail-closed private launch pipeline: the
    // app is unhidden, launched under monitor supervision, and re-hidden as
    // soon as it leaves the foreground. The pipeline runs on a process-wide
    // dispatcher because the auth gate unmounts this screen the moment the
    // launched app takes the foreground, and a screen-bound scope would
    // cancel the monitor and force a fail-closed re-hide mid-launch.
    val launchHiddenApp: (ZeaManagedApp) -> Unit = { target ->
        if (!launchInProgress) {
            launchInProgress = true
            ZeaDeviceOwnerController.ZeaPrivateLaunchDispatcher.scope.launch {
                try {
                    val record = loadPrivateApps(context).firstOrNull { stored ->
                        stored.packageName.equals(target.packageName, ignoreCase = true)
                    }
                    val outcome = if (record == null) {
                        ZeaDeviceOwnerOperationResult(
                            false,
                            "${target.displayName} has no protection record, so it cannot be opened safely."
                        )
                    } else {
                        ZeaDeviceOwnerController.launchPrivateApp(context, record)
                    }
                    outcomeSuccess = outcome.success
                    outcomeMessage = when {
                        !outcome.success -> outcome.message
                        outcome.message.isBlank() -> "Opening ${target.displayName}. It will hide again automatically."
                        else -> outcome.message
                    }
                } finally {
                    launchInProgress = false
                }
            }
        }
    }

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background
    ) {
        ZeaPullToRefreshLayout(
            isRefreshing = isRefreshingList,
            onRefresh = {
                Log.i("ZeaPTR", "hiddenlist mode=$mode onRefresh gesture received")
                if (!isRefreshingList &&
                    !launchInProgress &&
                    !unhideInProgress &&
                    bulkProgress == null
                ) {
                    isRefreshingList = true
                    reloadToken++
                } else {
                    Log.i("ZeaPTR", "hiddenlist refresh skipped: busy=$isRefreshingList launch=$launchInProgress unhide=$unhideInProgress bulk=${bulkProgress != null}")
                }
            },
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
        ) {
        // A single scrollable container (like the home screen) keeps
        // pull-to-refresh alive from every part of the screen, including the
        // empty states, where a bare LazyColumn would swallow or ignore the drag.
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
                        text = title,
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
                        if (mode != ZeaHideMode.HIDDEN) {
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
                        }
                        if (mode != ZeaHideMode.TIMED) {
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
                        }
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
                    .padding(start = 20.dp, end = 20.dp, top = 8.dp, bottom = 12.dp),
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

            if (mode == ZeaHideMode.HIDDEN && shownApps.isNotEmpty()) {
                val selectionActive = selectedPackages.isNotEmpty() || manualSelecting
                if (selectionActive) {
                    ZeaBulkSelectBar(
                        selectedCount = selectedPackages.size,
                        actionLabel = "Unhide",
                        onAction = { showBulkUnhideConfirm = true },
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
                ZeaListOutcomeBanner(
                    message = outcomeMessage,
                    success = outcomeSuccess,
                    onDismiss = { outcomeMessage = "" }
                )
            }

            ZeaInfoBanner(
                icon = bannerIcon,
                title = bannerTitle,
                body = bannerBody
            )

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
                            text = if (query.isBlank()) {
                                emptyMessage
                            } else {
                                "No app matches \"$query\"."
                            },
                            style = MaterialTheme.typography.bodyMedium,
                            color = ZeaColors.SecondaryText
                        )
                    }
                } else {
                    Column(
                        modifier = Modifier.padding(
                            start = 20.dp,
                            end = 20.dp,
                            bottom = 12.dp
                        ),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        shownApps.forEach { app ->
                            val selectionActive = mode == ZeaHideMode.HIDDEN &&
                                    (selectedPackages.isNotEmpty() || manualSelecting)
                            ZeaHiddenAppRow(
                                app = app,
                                remainingLabel = if (mode == ZeaHideMode.TIMED) {
                                    clockToken
                                    formatTimedHideRemaining(app.hiddenUntilEpochMillis)
                                } else {
                                    null
                                },
                                interactive = mode == ZeaHideMode.HIDDEN,
                                selectionActive = selectionActive,
                                isSelected = app.packageName in selectedPackages,
                                onTap = {
                                    if (selectionActive) {
                                        selectedPackages = if (app.packageName in selectedPackages) {
                                            selectedPackages - app.packageName
                                        } else {
                                            selectedPackages + app.packageName
                                        }
                                    } else {
                                        launchHiddenApp(app)
                                    }
                                },
                                onManage = {
                                    if (selectionActive) {
                                        showBulkUnhideConfirm = true
                                    } else if (mode == ZeaHideMode.TIMED) {
                                        timedManageApp = app
                                    } else {
                                        confirmApp = app
                                    }
                                }
                            )
                        }
                    }
                }
            }

            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 20.dp, end = 20.dp, bottom = 20.dp, top = 8.dp),
                shape = RoundedCornerShape(16.dp),
                color = ZeaColors.InfoBannerBackground
            ) {
                Row(
                    modifier = Modifier.padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = if (mode == ZeaHideMode.TIMED) {
                            ZeaIcons.Protection
                        } else {
                            ZeaIcons.About
                        },
                        contentDescription = null,
                        tint = ZeaColors.StatusTimed
                    )
                    Spacer(modifier = Modifier.width(10.dp))
                    Text(
                        text = footerText,
                        style = MaterialTheme.typography.bodySmall,
                        color = ZeaColors.BodyText
                    )
                }
            }
        }
        }
    }

    val activeConfirmApp = confirmApp
    if (activeConfirmApp != null && mode == ZeaHideMode.HIDDEN) {
        ZeaUnhideConfirmDialog(
            app = activeConfirmApp,
            timed = mode == ZeaHideMode.TIMED,
            inProgress = unhideInProgress,
            onConfirm = {
                if (!unhideInProgress) {
                    unhideInProgress = true
                    scope.launch {
                        val outcome = ZeaAppHideService.unhideApp(
                            context,
                            activeConfirmApp.packageName
                        )
                        unhideInProgress = false
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

    // Phase 3: Timed apps management dialog (extend/reduce/change end/cancel/convert)
    val activeTimedManageApp = timedManageApp
    if (activeTimedManageApp != null && mode == ZeaHideMode.TIMED) {
        ZeaTimedManageDialog(
            app = activeTimedManageApp,
            onDismiss = { timedManageApp = null },
            onExtend = { deltaMinutes ->
                scope.launch {
                    val newEnd = activeTimedManageApp.hiddenUntilEpochMillis + deltaMinutes * 60_000L
                    val outcome = ZeaAppHideService.hideAppForTime(
                        context,
                        activeTimedManageApp,
                        ZeaTimedHideRequest(
                            label = "extended by $deltaMinutes min",
                            endEpochMillis = newEnd
                        )
                    )
                    timedManageApp = null
                    outcomeSuccess = outcome.success
                    outcomeMessage = outcome.message
                    reloadToken++
                }
            },
            onReduce = { deltaMinutes ->
                scope.launch {
                    val newEnd = activeTimedManageApp.hiddenUntilEpochMillis - deltaMinutes * 60_000L
                    if (newEnd <= System.currentTimeMillis()) {
                        val outcome = ZeaAppHideService.unhideApp(context, activeTimedManageApp.packageName)
                        timedManageApp = null
                        outcomeSuccess = outcome.success
                        outcomeMessage = outcome.message
                        reloadToken++
                    } else {
                        val outcome = ZeaAppHideService.hideAppForTime(
                            context,
                            activeTimedManageApp,
                            ZeaTimedHideRequest(
                                label = "reduced by $deltaMinutes min",
                                endEpochMillis = newEnd
                            )
                        )
                        timedManageApp = null
                        outcomeSuccess = outcome.success
                        outcomeMessage = outcome.message
                        reloadToken++
                    }
                }
            },
            onChangeEnd = { newEndEpoch ->
                scope.launch {
                    val outcome = ZeaAppHideService.hideAppForTime(
                        context,
                        activeTimedManageApp,
                        ZeaTimedHideRequest(
                            label = "new end time",
                            endEpochMillis = newEndEpoch
                        )
                    )
                    timedManageApp = null
                    outcomeSuccess = outcome.success
                    outcomeMessage = outcome.message
                    reloadToken++
                }
            },
            onCancelTimer = {
                scope.launch {
                    val outcome = ZeaAppHideService.unhideApp(context, activeTimedManageApp.packageName)
                    timedManageApp = null
                    outcomeSuccess = outcome.success
                    outcomeMessage = outcome.message
                    reloadToken++
                }
            },
            onConvertToPermanent = {
                scope.launch {
                    val outcome = ZeaAppHideService.convertTimedHideToPermanent(
                        context,
                        activeTimedManageApp.packageName
                    )
                    timedManageApp = null
                    outcomeSuccess = outcome.success
                    outcomeMessage = outcome.message
                    reloadToken++
                }
            },
            onViewDetails = {
                val pkg = activeTimedManageApp.packageName
                timedManageApp = null
                onOpenDetails(pkg)
            }
        )
    }

    if (showBulkUnhideConfirm && mode == ZeaHideMode.HIDDEN) {
        val bulkTargets = shownApps.filter { it.packageName in selectedPackages }
        ZeaBulkUnhideConfirmDialog(
            count = bulkTargets.size,
            sampleNames = zeaBulkSampleNames(bulkTargets.map { it.displayName }),
            inProgress = bulkProgress != null,
            onConfirm = {
                if (bulkProgress == null) {
                    scope.launch {
                        val result = runBulkUnhide(context, bulkTargets) { done, total ->
                            bulkProgress = done to total
                        }
                        bulkProgress = null
                        showBulkUnhideConfirm = false
                        selectedPackages = emptySet()
                        manualSelecting = false
                        bulkOutcome = result
                        reloadToken++
                    }
                }
            },
            onDismiss = { if (bulkProgress == null) showBulkUnhideConfirm = false }
        )
    }

    val activeBulkProgress = bulkProgress
    if (activeBulkProgress != null) {
        ZeaBulkProgressDialog(
            label = "Unhiding ${activeBulkProgress.first} of ${activeBulkProgress.second}"
        )
    }

    val activeBulkOutcome = bulkOutcome
    if (activeBulkOutcome != null) {
        var undoAvailable by remember { mutableStateOf(false) }
        LaunchedEffect(activeBulkOutcome) {
            undoAvailable = ZeaUndo.canUndoBulk(context)
        }
        ZeaBulkOutcomeDialog(
            successCount = activeBulkOutcome.first,
            failures = activeBulkOutcome.second,
            actionLabel = "unhidden",
            onDismiss = { bulkOutcome = null },
            onUndo = if (undoAvailable) {
                {
                    scope.launch {
                        val undo = ZeaUndo.performBulkUndo(context)
                        bulkOutcome = null
                        outcomeMessage = "Undo: ${undo.reversed.size} restored, " +
                                "${undo.refused.size} skipped (state changed), ${undo.failed.size} failed"
                        outcomeSuccess = undo.failed.isEmpty()
                        reloadToken++
                    }
                }
            } else {
                null
            }
        )
    }
}

@Composable
private fun ZeaInfoBanner(
    icon: ImageVector,
    title: String,
    body: String
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 20.dp, end = 20.dp, bottom = 12.dp),
        shape = RoundedCornerShape(16.dp),
        color = ZeaColors.InfoBannerBackground
    ) {
        Row(
            modifier = Modifier.padding(14.dp),
            verticalAlignment = Alignment.Top
        ) {
            Surface(
                modifier = Modifier.size(40.dp),
                shape = RoundedCornerShape(12.dp),
                color = ZeaColors.IconContainer
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = icon,
                        contentDescription = null,
                        tint = ZeaColors.StatusTimed
                    )
                }
            }

            Spacer(modifier = Modifier.width(12.dp))

            Column {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = body,
                    style = MaterialTheme.typography.bodySmall,
                    color = ZeaColors.SecondaryText
                )
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ZeaHiddenAppRow(
    app: ZeaManagedApp,
    remainingLabel: String?,
    interactive: Boolean,
    selectionActive: Boolean,
    isSelected: Boolean,
    onTap: () -> Unit,
    onManage: () -> Unit
) {
    val rowShape = RoundedCornerShape(16.dp)

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clip(rowShape)
            .combinedClickable(
                enabled = interactive,
                onClick = onTap,
                onLongClick = onManage
            ),
        shape = rowShape,
        color = if (isSelected) ZeaColors.IconContainer else ZeaColors.CardBackground,
        border = BorderStroke(
            1.dp,
            if (isSelected) MaterialTheme.colorScheme.primary else ZeaColors.CardBorder
        )
    ) {
        Row(
            modifier = Modifier.padding(14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            ZeaAppIcon(packageName = app.packageName)

            Spacer(modifier = Modifier.width(14.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = app.displayName,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )

                if (remainingLabel != null) {
                    Text(
                        text = "Remaining $remainingLabel",
                        style = MaterialTheme.typography.bodySmall,
                        color = ZeaColors.StatusTimed
                    )
                } else {
                    Text(
                        text = app.packageName,
                        style = MaterialTheme.typography.bodySmall,
                        color = ZeaColors.SecondaryText,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            Spacer(modifier = Modifier.width(8.dp))

            if (selectionActive) {
                Checkbox(
                    checked = isSelected,
                    onCheckedChange = null
                )
            } else if (remainingLabel == null) {
                Text(
                    text = "Hidden",
                    style = MaterialTheme.typography.labelLarge,
                    color = ZeaColors.StatusHidden
                )
            } else {
                Icon(
                    imageVector = ZeaIcons.Chevron,
                    contentDescription = null,
                    tint = ZeaColors.SecondaryText
                )
            }
        }
    }
}

@Composable
private fun ZeaTimedManageDialog(
    app: ZeaManagedApp,
    onDismiss: () -> Unit,
    onExtend: (Long) -> Unit,
    onReduce: (Long) -> Unit,
    onChangeEnd: (Long) -> Unit,
    onCancelTimer: () -> Unit,
    onConvertToPermanent: () -> Unit,
    onViewDetails: () -> Unit
) {
    var newEndHour by remember { mutableStateOf("") }
    var newEndMinute by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Manage ${app.displayName}") },
        text = {
            Column {
                Text(
                    text = "Current end: ${zeaFormatEpoch(app.hiddenUntilEpochMillis)}",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                )
                Spacer(modifier = Modifier.height(12.dp))
                TextButton(onClick = { onExtend(15) }) {
                    Text("Extend by 15 minutes")
                }
                TextButton(onClick = { onExtend(60) }) {
                    Text("Extend by 1 hour")
                }
                TextButton(onClick = { onReduce(15) }) {
                    Text("Reduce by 15 minutes")
                }
                TextButton(onClick = { onReduce(60) }) {
                    Text("Reduce by 1 hour")
                }
                Spacer(modifier = Modifier.height(8.dp))
                Text("Change end time", fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = newEndHour,
                        onValueChange = { newEndHour = it.filter { c -> c.isDigit() }.take(2) },
                        label = { Text("Hour") },
                        singleLine = true,
                        modifier = Modifier.weight(1f)
                    )
                    Spacer(modifier = Modifier.height(0.dp))
                    OutlinedTextField(
                        value = newEndMinute,
                        onValueChange = { newEndMinute = it.filter { c -> c.isDigit() }.take(2) },
                        label = { Text("Minute") },
                        singleLine = true,
                        modifier = Modifier.weight(1f)
                    )
                    TextButton(onClick = {
                        val hour = newEndHour.toIntOrNull()?.coerceIn(0, 23)
                        val minute = (newEndMinute.toIntOrNull() ?: 0).coerceIn(0, 59)
                        if (hour != null) {
                            val calendar = java.util.Calendar.getInstance().apply {
                                set(java.util.Calendar.SECOND, 0)
                                set(java.util.Calendar.MILLISECOND, 0)
                                set(java.util.Calendar.HOUR_OF_DAY, hour)
                                set(java.util.Calendar.MINUTE, minute)
                            }
                            if (calendar.timeInMillis <= System.currentTimeMillis()) {
                                calendar.add(java.util.Calendar.DAY_OF_YEAR, 1)
                            }
                            onChangeEnd(calendar.timeInMillis)
                        }
                    }) {
                        Text("Apply")
                    }
                }
                Spacer(modifier = Modifier.height(8.dp))
                TextButton(onClick = onCancelTimer) {
                    Text("Cancel timer (unhide now)")
                }
                TextButton(onClick = onConvertToPermanent) {
                    Text("Convert to permanent hidden")
                }
                TextButton(onClick = onViewDetails) {
                    Text("View details")
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("Close")
            }
        }
    )
}

@Composable
private fun ZeaUnhideConfirmDialog(
    app: ZeaManagedApp,
    timed: Boolean,
    inProgress: Boolean,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = { if (!inProgress) onDismiss() },
        icon = {
            Icon(
                imageVector = ZeaIcons.Visible,
                contentDescription = null,
                modifier = Modifier.size(40.dp),
                tint = MaterialTheme.colorScheme.primary
            )
        },
        title = {
            Text("Unhide ${app.displayName}?")
        },
        text = {
            Text(
                text = if (timed) {
                    "${app.displayName} will become visible in the launcher now, instead of waiting for the timer to end."
                } else {
                    "${app.displayName} will become visible in the launcher and app list again."
                },
                style = MaterialTheme.typography.bodyMedium,
                color = ZeaColors.BodyText
            )
        },
        confirmButton = {
            Button(
                onClick = onConfirm,
                enabled = !inProgress,
                shape = RoundedCornerShape(28.dp)
            ) {
                Text(if (inProgress) "Unhiding..." else "Unhide")
            }
        },
        dismissButton = {
            TextButton(
                onClick = onDismiss,
                enabled = !inProgress
            ) {
                Text("Cancel")
            }
        }
    )
}

@Composable
private fun ZeaListOutcomeBanner(
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

internal fun formatTimedHideRemaining(
    untilEpochMillis: Long,
    nowEpochMillis: Long = System.currentTimeMillis()
): String {
    val remaining = (untilEpochMillis - nowEpochMillis).coerceAtLeast(0L)
    if (remaining <= 0L) {
        return "Ending"
    }

    val days = remaining / 86_400_000L
    val hours = (remaining % 86_400_000L) / 3_600_000L
    val totalSeconds = remaining / 1_000L

    return when {
        days > 0L && hours > 0L -> "${days}d ${hours}h"
        days > 0L -> "${days}d"
        hours > 0L && totalSeconds >= 3_600L -> "${hours}h ${(totalSeconds % 3_600L) / 60L}m"
        hours > 0L -> "${hours}h"
        totalSeconds > 60L -> "${totalSeconds / 60L}m"
        else -> "${totalSeconds}s"
    }
}

private fun filterHiddenApps(
    apps: List<ZeaManagedApp>,
    query: String
): List<ZeaManagedApp> {
    val trimmedQuery = query.trim().lowercase(Locale.ROOT)
    if (trimmedQuery.isEmpty()) {
        return apps
    }

    return apps.filter { app ->
        app.displayName.lowercase(Locale.ROOT).contains(trimmedQuery) ||
                app.packageName.lowercase(Locale.ROOT).contains(trimmedQuery)
    }
}
