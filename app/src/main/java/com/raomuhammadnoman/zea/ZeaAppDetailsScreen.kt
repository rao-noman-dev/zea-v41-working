package com.raomuhammadnoman.zea

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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch

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

    LaunchedEffect(packageName) {
        app = zeaManagedAppFromPackage(context, packageName)
        isFavorite = ZeaFavorites.isFavorite(context, packageName)
        groups = ZeaGroups.load(context)
    }

    fun refresh() {
        scope.launch {
            app = zeaManagedAppFromPackage(context, packageName)
            isFavorite = ZeaFavorites.isFavorite(context, packageName)
            groups = ZeaGroups.load(context)
        }
    }

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
                    Spacer(modifier = Modifier.height(12.dp))
                    ZeaAppDetailRow("State", currentApp.hideMode.name)
                    ZeaAppDetailRow("Favorite", if (isFavorite) "Yes" else "No")
                    ZeaAppDetailRow("System app", if (currentApp.systemApp) "Yes" else "No")
                    ZeaAppDetailRow("Manageable", if (currentApp.manageable) "Yes" else "No")
                    if (currentApp.blockedReason.isNotEmpty()) {
                        ZeaAppDetailRow("Blocked reason", currentApp.blockedReason)
                    }
                    if (currentApp.hiddenUntilEpochMillis > 0) {
                        ZeaAppDetailRow("Hidden until", zeaFormatEpoch(currentApp.hiddenUntilEpochMillis))
                        val remainingMinutes =
                            ((currentApp.hiddenUntilEpochMillis - System.currentTimeMillis()) / 60_000L)
                                .coerceAtLeast(0L)
                        ZeaAppDetailRow("Remaining time", "$remainingMinutes min")
                    }
                    if (currentApp.firstInstallTimeEpochMillis > 0) {
                        ZeaAppDetailRow("Installed", zeaFormatEpoch(currentApp.firstInstallTimeEpochMillis))
                    }
                    val memberGroups = groups.filter { it.memberPackages.contains(packageName) }
                    if (memberGroups.isNotEmpty()) {
                        ZeaAppDetailRow("Groups", memberGroups.joinToString { it.name })
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = {
                    scope.launch {
                        val outcome = ZeaAppHideService.hideApp(context, currentApp)
                        operationMessage = outcome.message
                        refresh()
                    }
                }) {
                    Text("Hide")
                }
                Button(onClick = {
                    scope.launch {
                        val outcome = ZeaAppHideService.unhideApp(context, packageName)
                        operationMessage = outcome.message
                        refresh()
                    }
                }) {
                    Text("Unhide")
                }
                Button(onClick = { showTimeDialog = true }) {
                    Text("Hide for Time")
                }
            }
            if (currentApp.launcherActivityName.isNotEmpty()) {
                Spacer(modifier = Modifier.height(8.dp))
                Button(onClick = {
                    val launchIntent = context.packageManager.getLaunchIntentForPackage(packageName)
                    if (launchIntent != null) {
                        context.startActivity(launchIntent)
                    } else {
                        operationMessage = "No launchable activity found for this app."
                    }
                }) {
                    Text("Launch app")
                }
            }
        }
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
