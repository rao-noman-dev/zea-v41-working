package com.raomuhammadnoman.zea

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.produceState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.graphics.drawable.toBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ZeaAppDetailsScreen(
    packageName: String,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var app by remember { mutableStateOf<ZeaManagedApp?>(null) }
    var isFavorite by remember { mutableStateOf(false) }
    var groups by remember { mutableStateOf<List<ZeaGroup>>(emptyList()) }
    var operationMessage by remember { mutableStateOf("") }
    var showTimeDialog by remember { mutableStateOf(false) }
    var showGroupDialog by remember { mutableStateOf(false) }
    var launchInProgress by remember { mutableStateOf(false) }
    var appVersion by remember { mutableStateOf("") }
    var hiddenSinceEpoch by remember { mutableStateOf(0L) }
    var uninstallProtected by remember { mutableStateOf<Boolean?>(null) }
    var lastActionLabel by remember { mutableStateOf("") }
    var memberProfiles by remember { mutableStateOf<List<String>>(emptyList()) }
    var memberSchedules by remember { mutableStateOf<List<String>>(emptyList()) }
    // Unified identity: the same favorites identity rules used everywhere else,
    // so the favorite state here never drifts from search or list surfaces.
    var favoritePackages by remember { mutableStateOf(emptySet<String>()) }

    fun reload() {
        scope.launch {
            app = zeaManagedAppFromPackage(context, packageName)
            favoritePackages = ZeaFavorites.load(context).toSet()
            isFavorite = ZeaFavorites.isFavorite(context, packageName)
            groups = ZeaGroups.load(context)
            appVersion = withContext(Dispatchers.IO) {
                runCatching {
                    context.packageManager.getPackageInfo(packageName, 0).versionName
                }.getOrNull().orEmpty()
            }
            hiddenSinceEpoch = withContext(Dispatchers.IO) {
                loadTimedHides(context).firstOrNull {
                    it.packageName.equals(packageName, ignoreCase = true)
                }?.hiddenAtEpochMillis ?: 0L
            }
            uninstallProtected = withContext(Dispatchers.IO) {
                ZeaDeviceOwnerController.isUninstallBlocked(context, packageName)
            }
            lastActionLabel = ZeaRecentlyManaged.load(context)
                .filter { it.packageName.equals(packageName, ignoreCase = true) }
                .maxByOrNull { it.epochMillis }
                ?.let { "${it.operation} • ${zeaFormatEpoch(it.epochMillis)}" }
                .orEmpty()
            memberProfiles = ZeaProfiles.load(context)
                .filter { profile ->
                    profile.hiddenPackages.any { it.equals(packageName, ignoreCase = true) } ||
                            profile.timedPackages.keys.any { it.equals(packageName, ignoreCase = true) }
                }
                .map { it.name }
            val groupIds = groups
                .filter { it.memberPackages.any { m -> m.equals(packageName, ignoreCase = true) } }
                .mapTo(mutableSetOf()) { it.id }
            memberSchedules = ZeaSchedules.load(context)
                .filter { schedule ->
                    schedule.targetPackages.any { it.equals(packageName, ignoreCase = true) } ||
                            (schedule.targetGroupId != null && schedule.targetGroupId in groupIds)
                }
                .map { it.name }
        }
    }

    LaunchedEffect(packageName) { reload() }

    fun refresh() = reload()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("App Details") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(onClick = {
                        scope.launch {
                            ZeaFavorites.toggleFavorite(context, packageName)
                            refresh()
                        }
                    }) {
                        Icon(
                            if (isFavorite) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                            contentDescription = "Toggle favorite"
                        )
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp)
                .verticalScroll(rememberScrollState())
        ) {
            val currentApp = app
            if (currentApp == null) {
                Text(
                    text = "App not found or no longer installed.",
                    fontSize = 14.sp,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                )
                return@Column
            }

            if (operationMessage.isNotEmpty()) {
                Text(
                    text = operationMessage,
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                )
                Spacer(modifier = Modifier.height(12.dp))
            }

            Card(
                modifier = Modifier.fillMaxWidth(),
                elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    val iconBitmap by produceState<ImageBitmap?>(initialValue = null, packageName) {
                        value = withContext(Dispatchers.IO) {
                            runCatching {
                                context.packageManager.getApplicationIcon(packageName)
                                    .toBitmap(width = 96, height = 96)
                                    .asImageBitmap()
                            }.getOrNull()
                        }
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        iconBitmap?.let { bitmap ->
                            Image(
                                bitmap = bitmap,
                                contentDescription = "${currentApp.displayName} icon",
                                modifier = Modifier.height(48.dp).padding(end = 12.dp)
                            )
                        }
                        Column {
                            Text(
                                text = currentApp.displayName,
                                fontWeight = FontWeight.Bold,
                                fontSize = 20.sp
                            )
                            Text(
                                text = currentApp.packageName,
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                            )
                        }
                    }
                    Spacer(modifier = Modifier.height(12.dp))
                    ZeaAppDetailRow("State", currentApp.hideMode.name)
                    ZeaAppDetailRow("Favorite", if (isFavorite) "Yes" else "No")
                    ZeaAppDetailRow("System app", if (currentApp.systemApp) "Yes" else "No")
                    ZeaAppDetailRow("Manageable", if (currentApp.manageable) "Yes" else "No")
                    if (currentApp.blockedReason.isNotEmpty()) {
                        ZeaAppDetailRow("Blocked reason", currentApp.blockedReason)
                    }
                    if (currentApp.hideMode == ZeaHideMode.TIMED && currentApp.hiddenUntilEpochMillis > 0) {
                        // Remaining time only exists for the TIMED state; for a
                        // plain permanent hide there is no end to count down to.
                        ZeaAppDetailRow("Hidden until", zeaFormatEpoch(currentApp.hiddenUntilEpochMillis))
                        val remainingMillis =
                            (currentApp.hiddenUntilEpochMillis - System.currentTimeMillis())
                                .coerceAtLeast(0L)
                        val remainingMinutes = (remainingMillis + 30_000L) / 60_000L
                        val hours = remainingMinutes / 60L
                        val mins = remainingMinutes % 60L
                        val remainingLabel = if (hours > 0) "${hours}h ${mins}m" else "${mins}m"
                        ZeaAppDetailRow("Remaining time", remainingLabel)
                    }
                    if (appVersion.isNotEmpty()) {
                        ZeaAppDetailRow("Version", appVersion)
                    }
                    if (currentApp.firstInstallTimeEpochMillis > 0) {
                        ZeaAppDetailRow("Installed", zeaFormatEpoch(currentApp.firstInstallTimeEpochMillis))
                    }
                    if (hiddenSinceEpoch > 0) {
                        ZeaAppDetailRow("Hidden since", zeaFormatEpoch(hiddenSinceEpoch))
                    }
                    uninstallProtected?.let { blocked ->
                        ZeaAppDetailRow(
                            "Uninstall protection",
                            if (blocked) "On" else "Off"
                        )
                    }
                    if (lastActionLabel.isNotEmpty()) {
                        ZeaAppDetailRow("Last ZYRO action", lastActionLabel)
                    }
                    val memberGroups = groups.filter {
                        it.memberPackages.any { m -> m.equals(packageName, ignoreCase = true) }
                    }
                    if (memberGroups.isNotEmpty()) {
                        ZeaAppDetailRow("Groups", memberGroups.joinToString { it.name })
                    }
                    if (memberProfiles.isNotEmpty()) {
                        ZeaAppDetailRow("Profiles", memberProfiles.joinToString())
                    }
                    if (memberSchedules.isNotEmpty()) {
                        ZeaAppDetailRow("Schedules", memberSchedules.joinToString())
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                // Applied-state guards: an action that cannot apply to the
                // current state is disabled instead of no-oping silently.
                Button(
                    enabled = currentApp.manageable && currentApp.hideMode == ZeaHideMode.VISIBLE,
                    onClick = {
                        scope.launch {
                            val outcome = ZeaAppHideService.hideApp(context, currentApp)
                            operationMessage = outcome.message
                            refresh()
                        }
                    }
                ) {
                    Text("Hide")
                }
                Button(
                    enabled = currentApp.hideMode != ZeaHideMode.VISIBLE,
                    onClick = {
                        scope.launch {
                            val outcome = ZeaAppHideService.unhideApp(context, packageName)
                            operationMessage = outcome.message
                            refresh()
                        }
                    }
                ) {
                    Text("Unhide")
                }
                Button(
                    enabled = currentApp.manageable,
                    onClick = { showTimeDialog = true }
                ) {
                    Text("Hide for Time")
                }
            }
            Spacer(modifier = Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = { showGroupDialog = true }) {
                    Text("Add / remove groups")
                }
            }
            if (currentApp.launcherActivityName.isNotEmpty()) {
                Spacer(modifier = Modifier.height(8.dp))
                Button(
                    enabled = !launchInProgress,
                    onClick = {
                        launchInProgress = true
                        scope.launch {
                            // Reuse the verified launcher pipeline instead of a
                            // raw intent: protected launches keep the same
                            // resolution, permission and audit behavior as the
                            // rest of the app.
                            val result = ZeaAppLauncher.launchAppWithTimeout(
                                context,
                                currentApp.packageName,
                                currentApp.displayName
                            )
                            if (!result.success) {
                                operationMessage = result.message
                            }
                            launchInProgress = false
                            refresh()
                        }
                    }
                ) {
                    Text("Launch app")
                }
            }
        }
    }

    if (showGroupDialog) {
        AlertDialog(
            onDismissRequest = { showGroupDialog = false },
            title = { Text("Groups for ${app?.displayName ?: packageName}") },
            text = {
                if (groups.isEmpty()) {
                    Text("No groups yet. Create one from the Groups tab.")
                } else {
                    Column {
                        groups.forEach { group ->
                            val member = group.memberPackages.any {
                                it.equals(packageName, ignoreCase = true)
                            }
                            TextButton(
                                onClick = {
                                    scope.launch {
                                        if (member) {
                                            ZeaGroups.removeMember(context, group.id, packageName)
                                        } else {
                                            ZeaGroups.addMember(context, group.id, packageName)
                                        }
                                        refresh()
                                    }
                                }
                            ) {
                                Text(
                                    (if (member) "✓ " else "") + group.name,
                                    color = if (member) {
                                        MaterialTheme.colorScheme.primary
                                    } else {
                                        MaterialTheme.colorScheme.onSurface
                                    }
                                )
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showGroupDialog = false }) {
                    Text("Done")
                }
            }
        )
    }

    if (showTimeDialog) {
        ZeaTimedHideDialog(
            onDismiss = { showTimeDialog = false },
            onConfirm = { request ->
                showTimeDialog = false
                scope.launch {
                    val outcome = ZeaAppHideService.hideAppForTime(context, app ?: return@launch, request)
                    operationMessage = outcome.message
                    refresh()
                }
            }
        )
    }
}

@Composable
private fun ZeaAppDetailRow(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            text = label,
            fontSize = 13.sp,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
        )
        Text(
            text = value,
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold
        )
    }
}
