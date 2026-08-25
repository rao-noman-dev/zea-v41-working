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
                                    operationMessage = "Deactivated; ${result.hiddenSucceeded.size} unhidden, ${result.hiddenFailed.size} failures."
                                    refresh()
                                }
                            },
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
                        ZeaProfiles.deleteProfile(context, target.id)
                        deleteTarget = null
                        refresh()
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
}

@Composable
private fun ZeaProfileCard(
    profile: ZeaProfile,
    onActivate: () -> Unit,
    onDeactivate: () -> Unit,
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
                    text = "${profile.hiddenPackages.size} hidden, ${profile.timedPackages.size} timed",
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
