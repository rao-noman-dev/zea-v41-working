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
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
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
fun ZeaScheduleScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var schedules by remember { mutableStateOf<List<ZeaSchedule>>(emptyList()) }
    var targetGroups by remember { mutableStateOf<List<ZeaGroup>>(emptyList()) }
    var showCreateDialog by remember { mutableStateOf(false) }
    var deleteTarget by remember { mutableStateOf<ZeaSchedule?>(null) }

    LaunchedEffect(Unit) {
        schedules = ZeaSchedules.load(context)
        targetGroups = ZeaGroups.load(context)
    }

    fun refresh() {
        scope.launch {
            schedules = ZeaSchedules.load(context)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Schedules") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(onClick = { showCreateDialog = true }) {
                        Icon(Icons.Default.Add, contentDescription = "Create schedule")
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
            if (schedules.isEmpty()) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(
                            Icons.Default.Schedule,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f)
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                        Text(
                            text = "No schedules. Create one to automate hide/unhide windows.",
                            fontSize = 14.sp,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                        )
                    }
                }
            } else {
                LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    items(schedules, key = { it.id }) { schedule ->
                        ZeaScheduleCard(
                            schedule = schedule,
                            onToggle = { enabled ->
                                scope.launch {
                                    ZeaSchedules.setEnabled(context, schedule.id, enabled)
                                    refresh()
                                }
                            },
                            onDelete = { deleteTarget = schedule }
                        )
                    }
                }
            }
        }
    }

    if (showCreateDialog) {
        ZeaScheduleCreateDialog(
            targetGroups = targetGroups,
            onDismiss = { showCreateDialog = false },
            onConfirm = { name, kind, days, start, end, groupId, packages, oneTime ->
                scope.launch {
                    val created = ZeaSchedules.createSchedule(
                        context, name, kind, days, start, end, groupId, packages, oneTime
                    )
                    if (created != null) {
                        showCreateDialog = false
                        refresh()
                    }
                }
            }
        )
    }

    deleteTarget?.let { target ->
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text("Delete \"${target.name}\"?") },
            text = { Text("Deleting only removes the schedule. Apps stay in their current state.") },
            confirmButton = {
                TextButton(onClick = {
                    scope.launch {
                        ZeaSchedules.deleteSchedule(context, target.id)
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
private fun ZeaScheduleCard(
    schedule: ZeaSchedule,
    onToggle: (Boolean) -> Unit,
    onDelete: () -> Unit
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
                    text = schedule.name,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 16.sp
                )
                Text(
                    text = "${schedule.kind.label} • ${zeaMinuteOfDayLabel(schedule.startMinuteOfDay)} → ${zeaMinuteOfDayLabel(schedule.endMinuteOfDay)}",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                )
                Text(
                    text = if (schedule.targetGroupId != null) "Group target" else "${schedule.targetPackages.size} app(s)",
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                )
            }
            Switch(
                checked = schedule.enabled,
                onCheckedChange = { onToggle(it) }
            )
            IconButton(onClick = onDelete) {
                Text("Delete", fontSize = 12.sp)
            }
        }
    }
}

@Composable
private fun ZeaScheduleCreateDialog(
    targetGroups: List<ZeaGroup>,
    onDismiss: () -> Unit,
    onConfirm: (
        name: String,
        kind: ZeaScheduleKind,
        days: List<Int>,
        start: Int,
        end: Int,
        groupId: String?,
        packages: List<String>,
        oneTime: Long
    ) -> Unit
) {
    var name by remember { mutableStateOf("") }
    var kind by remember { mutableStateOf(ZeaScheduleKind.DAILY) }
    var startHour by remember { mutableStateOf("9") }
    var endHour by remember { mutableStateOf("17") }
    var groupId by remember { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Create Schedule") },
        text = {
            Column {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Name") },
                    singleLine = true
                )
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedTextField(
                    value = startHour,
                    onValueChange = { startHour = it.filter { c -> c.isDigit() } },
                    label = { Text("Hide at hour (0-23)") },
                    singleLine = true
                )
                OutlinedTextField(
                    value = endHour,
                    onValueChange = { endHour = it.filter { c -> c.isDigit() } },
                    label = { Text("Unhide at hour (0-23)") },
                    singleLine = true
                )
                Spacer(modifier = Modifier.height(8.dp))
                if (targetGroups.isNotEmpty()) {
                    Text("Group (optional)", fontSize = 12.sp)
                    targetGroups.forEach { group ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = group.name,
                                fontWeight = if (groupId == group.id) FontWeight.Bold else FontWeight.Normal,
                                fontSize = 14.sp
                            )
                            TextButton(onClick = { groupId = group.id }) {
                                Text("Select")
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val start = startHour.toIntOrNull()?.coerceIn(0, 23)?.times(60)
                val end = endHour.toIntOrNull()?.coerceIn(0, 23)?.times(60)
                if (start != null && end != null) {
                    onConfirm(name, kind, emptyList(), start, end, groupId, emptyList(), 0L)
                }
            }) {
                Text("Create")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}

private fun zeaMinuteOfDayLabel(minuteOfDay: Int): String {
    val hours = minuteOfDay / 60
    val minutes = minuteOfDay % 60
    val amPm = if (hours < 12) "AM" else "PM"
    val displayHour = if (hours % 12 == 0) 12 else hours % 12
    return "$displayHour:${minutes.toString().padStart(2, '0')} $amPm"
}
