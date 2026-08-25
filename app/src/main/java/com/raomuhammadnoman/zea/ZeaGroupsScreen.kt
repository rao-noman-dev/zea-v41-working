package com.raomuhammadnoman.zea

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Group
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
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
fun ZeaGroupsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var groups by remember { mutableStateOf<List<ZeaGroup>>(emptyList()) }
    var showCreateDialog by remember { mutableStateOf(false) }
    var renameTarget by remember { mutableStateOf<ZeaGroup?>(null) }
    var deleteTarget by remember { mutableStateOf<ZeaGroup?>(null) }
    var membersTarget by remember { mutableStateOf<ZeaGroup?>(null) }
    var operationMessage by remember { mutableStateOf("") }

    LaunchedEffect(Unit) {
        groups = ZeaGroups.load(context)
    }

    fun refresh() {
        scope.launch {
            groups = ZeaGroups.load(context)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("App Groups") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(onClick = { showCreateDialog = true }) {
                        Icon(Icons.Default.Add, contentDescription = "Create group")
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

            if (groups.isEmpty()) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(
                            Icons.Default.Group,
                            contentDescription = null,
                            modifier = Modifier.size(64.dp),
                            tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f)
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                        Text(
                            text = "No groups yet. Create one to manage apps in bulk.",
                            fontSize = 14.sp,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                        )
                    }
                }
            } else {
                LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    items(groups, key = { it.id }) { group ->
                        ZeaGroupCard(
                            group = group,
                            onRename = { renameTarget = group },
                            onDelete = { deleteTarget = group },
                            onManageMembers = { membersTarget = group },
                            onHideGroup = {
                                scope.launch {
                                    val result = ZeaGroups.hideGroup(context, group.id)
                                    operationMessage = "${result.succeeded.size} hidden, ${result.failed.size} failed."
                                    refresh()
                                }
                            },
                            onUnhideGroup = {
                                scope.launch {
                                    val result = ZeaGroups.unhideGroup(context, group.id)
                                    operationMessage = "${result.succeeded.size} unhidden, ${result.failed.size} failed."
                                    refresh()
                                }
                            },
                            onHideForTime = { request ->
                                scope.launch {
                                    val result = ZeaGroups.hideGroupForTime(context, group.id, request)
                                    operationMessage = "${result.succeeded.size} hidden for ${request.label}, ${result.failed.size} failed."
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
        ZeaGroupNameDialog(
            title = "Create Group",
            initialValue = "",
            onDismiss = { showCreateDialog = false },
            onConfirm = { name ->
                scope.launch {
                    val created = ZeaGroups.createGroup(context, name)
                    if (created != null) {
                        showCreateDialog = false
                        refresh()
                    } else {
                        operationMessage = "A group with this name already exists or the name is invalid."
                    }
                }
            }
        )
    }

    renameTarget?.let { target ->
        ZeaGroupNameDialog(
            title = "Rename Group",
            initialValue = target.name,
            onDismiss = { renameTarget = null },
            onConfirm = { name ->
                scope.launch {
                    val renamed = ZeaGroups.renameGroup(context, target.id, name)
                    if (renamed) {
                        renameTarget = null
                        refresh()
                    } else {
                        operationMessage = "A group with this name already exists or the name is invalid."
                    }
                }
            }
        )
    }

    deleteTarget?.let { target ->
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text("Delete \"${target.name}\"?") },
            text = {
                Text("Deleting the group removes its membership. Apps stay in their current protection state.")
            },
            confirmButton = {
                TextButton(onClick = {
                    scope.launch {
                        ZeaGroups.deleteGroup(context, target.id)
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

    membersTarget?.let { target ->
        ZeaGroupMembersDialog(
            group = target,
            onDismiss = { membersTarget = null },
            onSaved = { savedCount ->
                membersTarget = null
                operationMessage = "Group now has $savedCount app(s)."
                refresh()
            }
        )
    }
}

@Composable
private fun ZeaGroupCard(
    group: ZeaGroup,
    onRename: () -> Unit,
    onDelete: () -> Unit,
    onManageMembers: () -> Unit,
    onHideGroup: () -> Unit,
    onUnhideGroup: () -> Unit,
    onHideForTime: (ZeaTimedHideRequest) -> Unit
) {
    var showMenu by remember { mutableStateOf(false) }
    var showTimeDialog by remember { mutableStateOf(false) }

    Card(
        modifier = Modifier.fillMaxWidth(),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = group.name,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 16.sp
                    )
                    Text(
                        text = "${group.memberPackages.size} app(s)",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                    )
                }
                IconButton(onClick = { showMenu = true }) {
                    Icon(Icons.Default.MoreVert, contentDescription = "Group actions")
                }
                DropdownMenu(
                    expanded = showMenu,
                    onDismissRequest = { showMenu = false }
                ) {
                    DropdownMenuItem(
                        text = { Text("Manage Apps") },
                        onClick = {
                            showMenu = false
                            onManageMembers()
                        }
                    )
                    DropdownMenuItem(
                        text = { Text("Hide All") },
                        onClick = {
                            showMenu = false
                            onHideGroup()
                        }
                    )
                    DropdownMenuItem(
                        text = { Text("Unhide All") },
                        onClick = {
                            showMenu = false
                            onUnhideGroup()
                        }
                    )
                    DropdownMenuItem(
                        text = { Text("Hide for Time") },
                        onClick = {
                            showMenu = false
                            showTimeDialog = true
                        }
                    )
                    DropdownMenuItem(
                        text = { Text("Rename") },
                        onClick = {
                            showMenu = false
                            onRename()
                        }
                    )
                    DropdownMenuItem(
                        text = { Text("Delete") },
                        onClick = {
                            showMenu = false
                            onDelete()
                        }
                    )
                }
            }
        }
    }

    if (showTimeDialog) {
        ZeaTimedHideDialog(
            onDismiss = { showTimeDialog = false },
            onConfirm = { request ->
                showTimeDialog = false
                onHideForTime(request)
            }
        )
    }
}

@Composable
private fun ZeaGroupNameDialog(
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
                label = { Text("Group name") },
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

@Composable
private fun ZeaGroupMembersDialog(
    group: ZeaGroup,
    onDismiss: () -> Unit,
    onSaved: (Int) -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var allApps by remember { mutableStateOf<List<ZeaManagedApp>>(emptyList()) }
    var checked by remember { mutableStateOf(group.memberPackages.toSet()) }
    var saving by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        allApps = ZeaAppCatalog.loadManagedApps(context)
            .sortedBy { it.displayName.lowercase() }
    }

    AlertDialog(
        onDismissRequest = { if (!saving) onDismiss() },
        title = { Text("Apps in \"${group.name}\"") },
        text = {
            if (allApps.isEmpty()) {
                Text("Loading apps…", fontSize = 13.sp)
            } else {
                LazyColumn(modifier = Modifier.height(360.dp)) {
                    items(allApps, key = { it.packageName }) { app ->
                        val isChecked = checked.contains(app.packageName)
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable(enabled = !saving) {
                                    checked = if (isChecked) {
                                        checked - app.packageName
                                    } else {
                                        checked + app.packageName
                                    }
                                }
                                .padding(vertical = 6.dp)
                        ) {
                            Text(
                                text = if (isChecked) "☑" else "☐",
                                fontSize = 16.sp,
                                modifier = Modifier.width(28.dp)
                            )
                            Column {
                                Text(
                                    text = app.displayName,
                                    fontSize = 14.sp,
                                    fontWeight = FontWeight.Medium
                                )
                                Text(
                                    text = app.packageName,
                                    fontSize = 11.sp,
                                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = !saving,
                onClick = {
                    saving = true
                    scope.launch {
                        // Stale members (uninstalled) are dropped automatically
                        // because only installed packages can stay checked.
                        val installed = allApps.map { it.packageName }.toSet()
                        val members = checked.filter { it in installed }
                        val saved = ZeaGroups.setMembers(context, group.id, members)
                        saving = false
                        if (saved) onSaved(members.size) else onDismiss()
                    }
                }
            ) {
                Text(if (saving) "Saving…" else "Save")
            }
        },
        dismissButton = {
            TextButton(enabled = !saving, onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}

@Composable
internal fun ZeaTimedHideDialog(
    onDismiss: () -> Unit,
    onConfirm: (ZeaTimedHideRequest) -> Unit
) {
    var amount by remember { mutableStateOf("1") }
    var unit by remember { mutableStateOf(ZeaTimeUnit.HOURS) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Hide for Time") },
        text = {
            Column {
                OutlinedTextField(
                    value = amount,
                    onValueChange = { amount = it.filter { c -> c.isDigit() } },
                    label = { Text("Amount") },
                    singleLine = true
                )
                Spacer(modifier = Modifier.height(12.dp))
                ZeaTimeUnit.values().forEach { option ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { unit = option }
                            .padding(vertical = 4.dp)
                    ) {
                        Text(
                            text = option.label,
                            fontWeight = if (unit == option) FontWeight.Bold else FontWeight.Normal
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val request = zeaTimedHideRequest(amount, unit)
                if (request != null) {
                    onConfirm(request)
                }
            }) {
                Text("Hide")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}
