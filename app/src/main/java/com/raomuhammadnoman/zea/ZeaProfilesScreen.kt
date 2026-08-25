package com.raomuhammadnoman.zea

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.PrivacyTip
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
fun ZeaProfilesScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var profiles by remember { mutableStateOf<List<ZeaProfile>>(emptyList()) }
    var showCreateDialog by remember { mutableStateOf(false) }
    var renameTarget by remember { mutableStateOf<ZeaProfile?>(null) }
    var deleteTarget by remember { mutableStateOf<ZeaProfile?>(null) }
    var editTarget by remember { mutableStateOf<ZeaProfile?>(null) }
    var operationMessage by remember { mutableStateOf("") }

    LaunchedEffect(Unit) {
        profiles = ZeaProfiles.load(context)
    }

    fun refresh() {
        scope.launch {
            profiles = ZeaProfiles.load(context)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Privacy Profiles") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(onClick = { showCreateDialog = true }) {
                        Icon(Icons.Default.Add, contentDescription = "Create profile")
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
        ) {
            if (operationMessage.isNotEmpty()) {
                Text(
                    text = operationMessage,
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                )
                Spacer(modifier = Modifier.height(12.dp))
            }

            if (profiles.isEmpty()) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(
                            Icons.Default.PrivacyTip,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f)
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                        Text(
                            text = "No profiles. Capture the current state as a preset.",
                            fontSize = 14.sp,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                        )
                    }
                }
            } else {
                LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    items(profiles, key = { it.id }) { profile ->
                        ZeaProfileCard(
                            profile = profile,
                            onActivate = {
                                scope.launch {
                                    val result = ZeaProfiles.activateProfile(context, profile.id)
                                    val failures = result.hiddenFailed.size + result.timedFailed.size
                                    operationMessage = "${result.hiddenSucceeded.size} hidden, ${result.timedSucceeded.size} timed; $failures failures."
                                    refresh()
                                }
                            },
                            onDeactivate = {
                                scope.launch {
                                    val result = ZeaProfiles.deactivateProfile(context, profile.id)
                                    val failures = result.unhiddenFailed.size + result.timedFailed.size
                                    operationMessage = "Deactivated; ${result.unhiddenSucceeded.size} unhidden, " +
                                            "${result.timedSucceeded.size} timed restored, " +
                                            "${result.skipped.size} skipped (independent state), $failures failures."
                                    refresh()
                                }
                            },
                            onEdit = { editTarget = profile },
                            onRename = { renameTarget = profile },
                            onDelete = { deleteTarget = profile },
                            onDuplicate = {
                                scope.launch {
                                    ZeaProfiles.duplicateProfile(context, profile.id, "${profile.name} copy")
                                    refresh()
                                }
                            }
                        )
                    }
                }
            }
        }
    }

    if (showCreateDialog) {
        ZeaProfileNameDialog(
            title = "Create Profile",
            initialValue = "",
            onDismiss = { showCreateDialog = false },
            onConfirm = { name ->
                scope.launch {
                    val created = ZeaProfiles.captureCurrentState(context, name)
                    if (created != null) {
                        showCreateDialog = false
                        refresh()
                    } else {
                        operationMessage = "A profile with this name already exists or the name is invalid."
                    }
                }
            }
        )
    }

    renameTarget?.let { target ->
        ZeaProfileNameDialog(
            title = "Rename Profile",
            initialValue = target.name,
            onDismiss = { renameTarget = null },
            onConfirm = { name ->
                scope.launch {
                    val renamed = ZeaProfiles.renameProfile(context, target.id, name)
                    if (renamed) {
                        renameTarget = null
                        refresh()
                    } else {
                        operationMessage = "A profile with this name already exists or the name is invalid."
                    }
                }
            }
        )
    }

    deleteTarget?.let { target ->
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text("Delete \"${target.name}\"?") },
            text = { Text("Deleting only removes the saved profile. Apps stay in their current state.") },
            confirmButton = {
                TextButton(onClick = {
                    scope.launch {
                        val deleted = ZeaProfiles.deleteProfile(context, target.id)
                        if (deleted) {
                            deleteTarget = null
                            refresh()
                        } else {
                            deleteTarget = null
                            operationMessage = "Profile is active; deactivate it before deleting."
                        }
                    }
                }) {
                    Text("Delete", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { deleteTarget = null }) {
                    Text("Cancel")
                }
            }
        )
    }

    editTarget?.let { target ->
        // Editing membership while the profile owns state changes would orphan
        // the ownership snapshot. Rename stays allowed; membership edits wait
        // until the profile is deactivated.
        if (target.isActive) {
            AlertDialog(
                onDismissRequest = { editTarget = null },
                title = { Text("Profile is active") },
                text = { Text("Membership cannot be edited while the profile is active. Deactivate it first, then edit.") },
                confirmButton = {
                    TextButton(onClick = { editTarget = null }) { Text("OK") }
                }
            )
        } else {
            ZeaProfileEditDialog(
                profile = target,
                onDismiss = { editTarget = null },
                onSave = { hidden, timed ->
                    scope.launch {
                        val updated = target.copy(
                            hiddenPackages = hidden,
                            timedPackages = timed
                        )
                        ZeaProfiles.updateProfile(context, updated)
                        editTarget = null
                        operationMessage = "Profile membership updated. Activate to apply."
                        refresh()
                    }
                }
            )
        }
    }
}

@Composable
private fun ZeaProfileCard(
    profile: ZeaProfile,
    onActivate: () -> Unit,
    onDeactivate: () -> Unit,
    onEdit: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
    onDuplicate: () -> Unit
    ) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = profile.name,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 16.sp
                )
                Text(
                    text = "${profile.hiddenPackages.size} hidden, ${profile.timedPackages.size} timed" +
                            if (profile.isActive) " — active" else "",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                )
                Row {
                    TextButton(onClick = onActivate) {
                        Text("Activate")
                    }
                    TextButton(onClick = onDeactivate) {
                        Text("Deactivate")
                    }
                    TextButton(onClick = onEdit) {
                        Text("Edit")
                    }
                    TextButton(onClick = onRename) {
                        Text("Rename")
                    }
                    TextButton(onClick = onDuplicate) {
                        Text("Duplicate")
                    }
                    TextButton(onClick = onDelete) {
                        Text("Delete")
                    }
                }
            }
        }
    }
}

@Composable
private fun ZeaProfileNameDialog(
    title: String,
    initialValue: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit
) {
    var value by remember { mutableStateOf(initialValue) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = value,
                onValueChange = { value = it },
                label = { Text("Profile name") },
                singleLine = true
            )
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(value) }) {
                Text("Save")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}

/**
 * Edit Profile flow: the user can actually change the profile's membership.
 * Hidden members are toggled with checkboxes over the installed app catalog;
 * timed members are listed with their stored deadline and can be removed.
 * Editing changes membership only — it never re-applies the profile.
 */
@Composable
private fun ZeaProfileEditDialog(
    profile: ZeaProfile,
    onDismiss: () -> Unit,
    onSave: (hidden: List<String>, timed: Map<String, Long>) -> Unit
) {
    val context = LocalContext.current
    var apps by remember { mutableStateOf<List<ZeaManagedApp>>(emptyList()) }
    var hiddenSelection by remember { mutableStateOf(profile.hiddenPackages.toSet()) }
    var timedSelection by remember { mutableStateOf(profile.timedPackages) }

    LaunchedEffect(profile.id) {
        apps = ZeaAppCatalog.loadManagedApps(context)
            .filter { it.manageable }
            .sortedBy { it.displayName.lowercase() }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Edit \"${profile.name}\"") },
        text = {
            Column(modifier = Modifier.height(420.dp)) {
                Text(
                    text = "Check apps this profile hides. Timed members keep their deadline and can be removed below.",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                )
                Spacer(modifier = Modifier.height(8.dp))
                LazyColumn(modifier = Modifier.weight(1f)) {
                    items(apps, key = { it.packageName }) { app ->
                        val isTimed = timedSelection.containsKey(app.packageName)
                        val checked = hiddenSelection.contains(app.packageName) || isTimed
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 2.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            androidx.compose.material3.Checkbox(
                                checked = checked,
                                onCheckedChange = { isChecked ->
                                    if (isChecked) {
                                        hiddenSelection = hiddenSelection + app.packageName
                                    } else {
                                        hiddenSelection = hiddenSelection - app.packageName
                                        timedSelection = timedSelection - app.packageName
                                    }
                                }
                            )
                            Column {
                                Text(app.displayName, fontSize = 14.sp)
                                Text(
                                    text = if (isTimed) {
                                        "timed until ${zeaFormatEpoch(timedSelection[app.packageName] ?: 0L)}"
                                    } else {
                                        app.packageName
                                    },
                                    fontSize = 11.sp,
                                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                onSave(
                    hiddenSelection.filter { !timedSelection.containsKey(it) }.sorted(),
                    timedSelection.filterKeys { key ->
                        hiddenSelection.contains(key) || apps.any { it.packageName == key }
                    }
                )
            }) {
                Text("Save")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}
