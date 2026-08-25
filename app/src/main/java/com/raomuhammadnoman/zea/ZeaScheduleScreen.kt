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
    var editTarget by remember { mutableStateOf<ZeaSchedule?>(null) }

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
                            onEdit = { editTarget = schedule },
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
            existing = null,
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

    editTarget?.let { target ->
        ZeaScheduleCreateDialog(
            targetGroups = targetGroups,
            existing = target,
            onDismiss = { editTarget = null },
            onConfirm = { name, kind, days, start, end, groupId, packages, oneTime ->
                scope.launch {
                    val saved = ZeaSchedules.updateSchedule(
                        context,
                        target.copy(
                            name = name,
                            kind = kind,
                            daysOfWeek = days,
                            startMinuteOfDay = start,
                            endMinuteOfDay = end,
                            targetGroupId = groupId,
                            targetPackages = packages,
                            oneTimeStartEpochMillis = oneTime
                        )
                    )
                    if (saved) {
                        editTarget = null
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
    onEdit: () -> Unit,
    onDelete: () -> Unit
) {
    val now = System.currentTimeMillis()
    val activeEnd = zeaScheduleActiveWindow(schedule, now)
    val nextRun = if (schedule.enabled) zeaScheduleNextRun(schedule, now) else null

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
                Text(
                    text = when {
                        activeEnd != null -> "Active now; ends ${zeaFormatEpoch(activeEnd)}"
                        nextRun != null -> "Next run: ${zeaFormatEpoch(nextRun)}"
                        else -> "Paused"
                    },
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                )
                Row {
                    TextButton(onClick = onEdit) {
                        Text("Edit", fontSize = 12.sp)
                    }
                    TextButton(onClick = onDelete) {
                        Text("Delete", fontSize = 12.sp)
                    }
                }
            }
            Switch(
                checked = schedule.enabled,
                onCheckedChange = { onToggle(it) }
            )
        }
    }
}

/**
 * Create/edit schedule dialog. Every engine-supported kind and target type is
 * reachable here: one-time, daily, weekdays, custom days; group target or
 * individual app targets; start/end times; custom day selection.
 */
@Composable
private fun ZeaScheduleCreateDialog(
    targetGroups: List<ZeaGroup>,
    existing: ZeaSchedule?,
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
    val context = LocalContext.current
    var name by remember { mutableStateOf(existing?.name ?: "") }
    var kind by remember { mutableStateOf(existing?.kind ?: ZeaScheduleKind.DAILY) }
    var startHour by remember { mutableStateOf(((existing?.startMinuteOfDay ?: 540) / 60).toString()) }
    var endHour by remember { mutableStateOf(((existing?.endMinuteOfDay ?: 1020) / 60).toString()) }
    var groupId by remember { mutableStateOf(existing?.targetGroupId) }
    var selectedPackages by remember { mutableStateOf(existing?.targetPackages?.toSet() ?: emptySet()) }
    var selectedDays by remember { mutableStateOf(existing?.daysOfWeek?.toSet() ?: emptySet()) }
    var installedApps by remember { mutableStateOf<List<ZeaManagedApp>>(emptyList()) }

    LaunchedEffect(Unit) {
        installedApps = ZeaAppCatalog.loadManagedApps(context)
            .filter { it.manageable }
            .sortedBy { it.displayName.lowercase() }
    }

    val dayLabels = listOf(
        java.util.Calendar.SUNDAY to "Sun",
        java.util.Calendar.MONDAY to "Mon",
        java.util.Calendar.TUESDAY to "Tue",
        java.util.Calendar.WEDNESDAY to "Wed",
        java.util.Calendar.THURSDAY to "Thu",
        java.util.Calendar.FRIDAY to "Fri",
        java.util.Calendar.SATURDAY to "Sat"
    )

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (existing == null) "Create Schedule" else "Edit Schedule") },
        text = {
            Column(modifier = Modifier.height(460.dp)) {
                LazyColumn(modifier = Modifier.weight(1f)) {
                    item {
                        OutlinedTextField(
                            value = name,
                            onValueChange = { name = it },
                            label = { Text("Name") },
                            singleLine = true
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text("Schedule kind", fontSize = 12.sp)
                        Row {
                            ZeaScheduleKind.entries.forEach { option ->
                                TextButton(onClick = { kind = option }) {
                                    Text(
                                        text = option.label,
                                        fontSize = 11.sp,
                                        fontWeight = if (kind == option) FontWeight.Bold else FontWeight.Normal
                                    )
                                }
                            }
                        }
                        if (kind == ZeaScheduleKind.CUSTOM_DAYS) {
                            Text("Days", fontSize = 12.sp)
                            Row {
                                dayLabels.forEach { (day, label) ->
                                    TextButton(onClick = {
                                        selectedDays = if (selectedDays.contains(day)) {
                                            selectedDays - day
                                        } else {
                                            selectedDays + day
                                        }
                                    }) {
                                        Text(
                                            text = label,
                                            fontSize = 11.sp,
                                            fontWeight = if (selectedDays.contains(day)) FontWeight.Bold else FontWeight.Normal
                                        )
                                    }
                                }
                            }
                        }
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
                            Text("Group target (choose one, or pick apps below)", fontSize = 12.sp)
                            targetGroups.forEach { group ->
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(
                                        text = group.name,
                                        fontWeight = if (groupId == group.id) FontWeight.Bold else FontWeight.Normal,
                                        fontSize = 14.sp
                                    )
                                    TextButton(onClick = {
                                        groupId = if (groupId == group.id) null else group.id
                                    }) {
                                        Text(if (groupId == group.id) "Selected" else "Select")
                                    }
                                }
                            }
                        }
                        if (groupId == null) {
                            Text("Individual apps", fontSize = 12.sp)
                        }
                    }
                    if (groupId == null) {
                        items(installedApps, key = { it.packageName }) { app ->
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                androidx.compose.material3.Checkbox(
                                    checked = selectedPackages.contains(app.packageName),
                                    onCheckedChange = { isChecked ->
                                        selectedPackages = if (isChecked) {
                                            selectedPackages + app.packageName
                                        } else {
                                            selectedPackages - app.packageName
                                        }
                                    }
                                )
                                Column {
                                    Text(app.displayName, fontSize = 13.sp)
                                    Text(
                                        app.packageName,
                                        fontSize = 10.sp,
                                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                                    )
                                }
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
                    val oneTime = if (kind == ZeaScheduleKind.ONE_TIME) {
                        // One-time fires at the next occurrence of the chosen
                        // start time: today if still ahead, otherwise tomorrow.
                        val calendar = java.util.Calendar.getInstance().apply {
                            set(java.util.Calendar.SECOND, 0)
                            set(java.util.Calendar.MILLISECOND, 0)
                            set(java.util.Calendar.HOUR_OF_DAY, start / 60)
                            set(java.util.Calendar.MINUTE, start % 60)
                        }
                        if (calendar.timeInMillis <= System.currentTimeMillis()) {
                            calendar.add(java.util.Calendar.DAY_OF_YEAR, 1)
                        }
                        calendar.timeInMillis
                    } else {
                        0L
                    }
                    onConfirm(
                        name,
                        kind,
                        if (kind == ZeaScheduleKind.CUSTOM_DAYS) selectedDays.sorted() else emptyList(),
                        start,
                        end,
                        groupId,
                        if (groupId == null) selectedPackages.sorted() else emptyList(),
                        oneTime
                    )
                }
            }) {
                Text(if (existing == null) "Create" else "Save")
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
