package com.raomuhammadnoman.zea

import android.content.Context
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/**
 * The slim strip that sits under a list's search field while nothing is
 * selected. It only offers a "Select" entry point; tapping it opens the
 * choice between selecting every visible app (search and filters respected)
 * and entering manual selection mode.
 */
@Composable
fun ZeaSelectionEntryBar(
    totalCount: Int,
    onSelectAll: () -> Unit,
    onSelectManually: () -> Unit
) {
    var menuOpen by remember { mutableStateOf(false) }

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 20.dp, end = 20.dp, bottom = 10.dp),
        shape = RoundedCornerShape(14.dp),
        color = ZeaColors.CardBackground,
        border = BorderStroke(1.dp, ZeaColors.CardBorder)
    ) {
        Row(
            modifier = Modifier.padding(start = 14.dp, end = 6.dp, top = 2.dp, bottom = 2.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "Select apps",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold
            )

            Spacer(modifier = Modifier.weight(1f))

            Box {
                TextButton(onClick = { menuOpen = true }) {
                    Text("Select")
                }

                DropdownMenu(
                    expanded = menuOpen,
                    onDismissRequest = { menuOpen = false }
                ) {
                    DropdownMenuItem(
                        text = { Text("Select All ($totalCount)") },
                        onClick = {
                            menuOpen = false
                            onSelectAll()
                        }
                    )

                    DropdownMenuItem(
                        text = { Text("Select Manually") },
                        onClick = {
                            menuOpen = false
                            onSelectManually()
                        }
                    )
                }
            }
        }
    }
}

/**
 * The active-selection strip. Shows the running count plus a one-tap action
 * on every selected app and a cancel that exits selection mode entirely.
 */
@Composable
fun ZeaBulkSelectBar(
    selectedCount: Int,
    actionLabel: String,
    onAction: () -> Unit,
    onClear: () -> Unit,
    filteredCount: Int? = null
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 20.dp, end = 20.dp, bottom = 10.dp),
        shape = RoundedCornerShape(14.dp),
        color = ZeaColors.CardBackground,
        border = BorderStroke(1.dp, ZeaColors.CardBorder)
    ) {
        Row(
            modifier = Modifier.padding(start = 14.dp, end = 6.dp, top = 2.dp, bottom = 2.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = if (filteredCount != null) {
                    "$selectedCount of $filteredCount selected"
                } else {
                    "$selectedCount selected"
                },
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold
            )

            Spacer(modifier = Modifier.weight(1f))

            TextButton(
                onClick = onAction,
                enabled = selectedCount > 0
            ) {
                Text(actionLabel)
            }

            TextButton(onClick = onClear) {
                Text("Cancel")
            }
        }
    }
}

/** Long-press menu while a bulk selection is active on the All Apps screen. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ZeaBulkActionSheet(
    appCount: Int,
    onHideAllNow: () -> Unit,
    onHideAllForTime: () -> Unit,
    onDismiss: () -> Unit
) {
    val sheetState = rememberModalBottomSheetState()

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surface
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(start = 20.dp, end = 20.dp, bottom = 20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                text = "$appCount apps selected",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold
            )

            Text(
                text = "Choose an action and it will be applied to every selected app.",
                style = MaterialTheme.typography.bodySmall,
                color = ZeaColors.SecondaryText
            )

            Spacer(modifier = Modifier.height(4.dp))

            ZeaBulkActionRow(
                icon = ZeaIcons.Hidden,
                title = "Hide All Now",
                subtitle = "Hide every selected app immediately",
                onClick = onHideAllNow
            )

            ZeaBulkActionRow(
                icon = ZeaIcons.Timed,
                title = "Hide All For Time",
                subtitle = "Hide every selected app for one shared duration",
                onClick = onHideAllForTime
            )

            OutlinedButton(
                onClick = onDismiss,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(28.dp)
            ) {
                Text("Cancel")
            }
        }
    }
}

@Composable
private fun ZeaBulkActionRow(
    icon: ImageVector,
    title: String,
    subtitle: String,
    onClick: () -> Unit
) {
    val shape = RoundedCornerShape(18.dp)

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .clickable(onClick = onClick),
        shape = shape,
        color = ZeaColors.CardBackground,
        border = BorderStroke(1.dp, ZeaColors.CardBorder)
    ) {
        Row(
            modifier = Modifier.padding(14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .clip(CircleShape)
                    .background(ZeaColors.IconContainer),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    modifier = Modifier.size(22.dp)
                )
            }

            Spacer(modifier = Modifier.width(14.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold
                )

                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = ZeaColors.SecondaryText
                )
            }

            Icon(
                imageVector = ZeaIcons.Chevron,
                contentDescription = null,
                tint = ZeaColors.SecondaryText
            )
        }
    }
}

@Composable
fun ZeaBulkHideConfirmDialog(
    apps: List<ZeaManagedApp>,
    firstProtectedApp: Boolean,
    usageAccessGranted: Boolean,
    inProgress: Boolean,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = { if (!inProgress) onDismiss() },
        icon = {
            Icon(
                imageVector = ZeaIcons.Protection,
                contentDescription = null,
                modifier = Modifier.size(40.dp),
                tint = MaterialTheme.colorScheme.primary
            )
        },
        title = {
            Text(text = "Hide ${apps.size} apps?")
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    text = zeaBulkSampleNames(apps.map { it.displayName }),
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold
                )

                Text(
                    // TEMPORARY BRAND NAME: "Zyro" is not the permanent app name and can be replaced with the original/permanent name "Zea Assistant" at any time.
                    text = "Every listed app will be hidden from your launcher and protected from uninstall. You can unhide them anytime from Zyro.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = ZeaColors.BodyText
                )

                if (!usageAccessGranted) {
                    ZeaBulkWarning(
                        // TEMPORARY BRAND NAME: "Zyro" is not the permanent app name and can be replaced with the original/permanent name "Zea Assistant" at any time.
                        text = "Usage Access is not granted yet, so Zyro cannot open these apps while they are hidden."
                    )
                }

                if (firstProtectedApp) {
                    ZeaBulkWarning(
                        // TEMPORARY BRAND NAME: "Zyro" is not the permanent app name and can be replaced with the original/permanent name "Zea Assistant" at any time.
                        text = "This is the first app Zyro will protect. While any app stays protected, Android blocks app installs and updates for this user, including updates to Zyro itself."
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = onConfirm,
                enabled = !inProgress,
                shape = RoundedCornerShape(28.dp)
            ) {
                Text(if (inProgress) "Hiding..." else "Hide ${apps.size} Apps")
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
fun ZeaBulkUnhideConfirmDialog(
    count: Int,
    sampleNames: String,
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
            Text(text = "Unhide $count apps?")
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    text = sampleNames,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold
                )

                Text(
                    text = "Every selected app will become visible in the launcher again.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = ZeaColors.BodyText
                )
            }
        },
        confirmButton = {
            Button(
                onClick = onConfirm,
                enabled = !inProgress,
                shape = RoundedCornerShape(28.dp)
            ) {
                Text(if (inProgress) "Unhiding..." else "Unhide $count Apps")
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
fun ZeaBulkProgressDialog(label: String) {
    AlertDialog(
        onDismissRequest = {},
        title = {
            Text(text = label)
        },
        text = {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                CircularProgressIndicator(modifier = Modifier.size(28.dp))
                Text(
                    text = "Working, please wait...",
                    style = MaterialTheme.typography.bodyMedium,
                    color = ZeaColors.BodyText
                )
            }
        },
        confirmButton = {}
    )
}

@Composable
fun ZeaBulkOutcomeDialog(
    successCount: Int,
    failures: List<String>,
    actionLabel: String,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = {
            Icon(
                imageVector = if (failures.isEmpty()) ZeaIcons.Confirm else ZeaIcons.Cancel,
                contentDescription = null,
                modifier = Modifier.size(40.dp),
                tint = if (failures.isEmpty()) {
                    ZeaColors.StatusVisible
                } else {
                    ZeaColors.StatusBlocked
                }
            )
        },
        title = {
            Text(
                text = if (failures.isEmpty()) {
                    "$successCount apps ${actionLabel}"
                } else {
                    "$successCount done, ${failures.size} failed"
                }
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (failures.isEmpty()) {
                    Text(
                        text = "Everything completed successfully.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = ZeaColors.BodyText
                    )
                } else {
                    Text(
                        text = "These apps could not be processed:",
                        style = MaterialTheme.typography.bodySmall,
                        color = ZeaColors.SecondaryText
                    )

                    Column(
                        modifier = Modifier.verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        failures.forEach { failure ->
                            Text(
                                text = "• $failure",
                                style = MaterialTheme.typography.bodySmall,
                                color = ZeaColors.BannerErrorText
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = onDismiss,
                shape = RoundedCornerShape(28.dp)
            ) {
                Text("OK")
            }
        }
    )
}

@Composable
private fun ZeaBulkWarning(text: String) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        color = ZeaColors.BannerWarningBackground
    ) {
        Text(
            text = text,
            modifier = Modifier.padding(12.dp),
            style = MaterialTheme.typography.bodySmall,
            color = ZeaColors.BannerWarningText
        )
    }
}

internal fun zeaBulkSampleNames(names: List<String>): String {
    if (names.isEmpty()) return ""
    val shown = names.take(4)
    val suffix = if (names.size > shown.size) {
        " and ${names.size - shown.size} more"
    } else {
        ""
    }
    return shown.joinToString(separator = ", ") + suffix
}

private const val zeaBulkRetryPasses = 3
private const val zeaBulkPassDelayMillis = 1200L
private const val zeaBulkStepDelayMillis = 150L

internal suspend fun runBulkHide(
    context: Context,
    apps: List<ZeaManagedApp>,
    request: ZeaTimedHideRequest?,
    existingJournal: ZeaBatchJournalRecord? = null,
    onProgress: (done: Int, total: Int) -> Unit
): Pair<Int, List<String>> = withContext(Dispatchers.IO) {
    val expectedOperation = if (request != null) {
        ZeaBatchJournal.OPERATION_TIMED_HIDE
    } else {
        ZeaBatchJournal.OPERATION_HIDE
    }
    val journal = if (existingJournal != null) {
        val active = ZeaBatchJournal.readActive(context)
        if (
            active?.batchId == existingJournal.batchId &&
            active.operation == expectedOperation
        ) {
            active
        } else {
            null
        }
    } else {
        ZeaBatchJournal.start(
            context = context,
            operation = expectedOperation,
            targetPackages = apps.map { it.packageName },
            timedRequest = request
        )
    }
    if (journal == null) {
        return@withContext 0 to listOf(
            "An earlier batch was interrupted or the recovery journal no longer matches this operation. Resolve the active batch from Home, then try again."
        )
    }

    val total = journal.targets.size
    var done = journal.processed.size
    val processedKeys = journal.processed
        .mapTo(mutableSetOf()) { it.lowercase() }
    var pending = apps.filterNot { app ->
        app.packageName.lowercase() in processedKeys
    }
    val failures = mutableListOf<String>()
    var journalWriteFailed = false

    onProgress(done, total)

    bulkPasses@ for (pass in 0 until zeaBulkRetryPasses) {
        if (pending.isEmpty()) break
        if (pass > 0) delay(zeaBulkPassDelayMillis)

        val stillPending = mutableListOf<ZeaManagedApp>()

        for (app in pending) {
            delay(zeaBulkStepDelayMillis)

            val outcome = if (request != null) {
                ZeaAppHideService.hideAppForTime(context, app, request)
            } else {
                ZeaAppHideService.hideApp(context, app)
            }

            if (outcome.success) {
                val journaled = ZeaBatchJournal.markProcessed(
                    context,
                    journal.batchId,
                    app.packageName
                )
                if (!journaled) {
                    failures.add(
                        "${app.displayName}: state changed successfully, but durable batch progress could not be saved. The batch was left open for recovery."
                    )
                    journalWriteFailed = true
                    break@bulkPasses
                }
                processedKeys.add(app.packageName.lowercase())
                done++
                onProgress(done, total)
            } else if (pass == zeaBulkRetryPasses - 1) {
                failures.add("${app.displayName}: ${outcome.message}")
            } else {
                stillPending.add(app)
            }
        }

        pending = stillPending
    }

    if (!journalWriteFailed) {
        val finalJournal = ZeaBatchJournal.readActive(context)
        if (finalJournal?.batchId == journal.batchId && ZeaBatchJournal.allTargetsProcessed(finalJournal)) {
            if (!ZeaBatchJournal.complete(context, journal.batchId)) {
                failures.add(
                    "All target states were processed, but the durable batch journal could not be closed. It was retained for safe recovery."
                )
            }
        }
    }

    done to failures
}

internal suspend fun runBulkUnhide(
    context: Context,
    targets: List<ZeaManagedApp>,
    existingJournal: ZeaBatchJournalRecord? = null,
    onProgress: (done: Int, total: Int) -> Unit
): Pair<Int, List<String>> = withContext(Dispatchers.IO) {
    val journal = if (existingJournal != null) {
        val active = ZeaBatchJournal.readActive(context)
        if (
            active?.batchId == existingJournal.batchId &&
            active.operation == ZeaBatchJournal.OPERATION_UNHIDE
        ) {
            active
        } else {
            null
        }
    } else {
        ZeaBatchJournal.start(
            context = context,
            operation = ZeaBatchJournal.OPERATION_UNHIDE,
            targetPackages = targets.map { it.packageName }
        )
    }
    if (journal == null) {
        return@withContext 0 to listOf(
            "An earlier batch was interrupted or the recovery journal no longer matches this operation. Resolve the active batch from Home, then try again."
        )
    }

    val total = journal.targets.size
    var done = journal.processed.size
    val processedKeys = journal.processed
        .mapTo(mutableSetOf()) { it.lowercase() }
    var pending = targets.filterNot { app ->
        app.packageName.lowercase() in processedKeys
    }
    val failures = mutableListOf<String>()
    var journalWriteFailed = false

    onProgress(done, total)

    bulkPasses@ for (pass in 0 until zeaBulkRetryPasses) {
        if (pending.isEmpty()) break
        if (pass > 0) delay(zeaBulkPassDelayMillis)

        val stillPending = mutableListOf<ZeaManagedApp>()

        for (app in pending) {
            delay(zeaBulkStepDelayMillis)

            val outcome = ZeaAppHideService.unhideApp(context, app.packageName)

            if (outcome.success) {
                val journaled = ZeaBatchJournal.markProcessed(
                    context,
                    journal.batchId,
                    app.packageName
                )
                if (!journaled) {
                    failures.add(
                        "${app.displayName}: state changed successfully, but durable batch progress could not be saved. The batch was left open for recovery."
                    )
                    journalWriteFailed = true
                    break@bulkPasses
                }
                processedKeys.add(app.packageName.lowercase())
                done++
                onProgress(done, total)
            } else if (pass == zeaBulkRetryPasses - 1) {
                failures.add("${app.displayName}: ${outcome.message}")
            } else {
                stillPending.add(app)
            }
        }

        pending = stillPending
    }

    if (!journalWriteFailed) {
        val finalJournal = ZeaBatchJournal.readActive(context)
        if (finalJournal?.batchId == journal.batchId && ZeaBatchJournal.allTargetsProcessed(finalJournal)) {
            if (!ZeaBatchJournal.complete(context, journal.batchId)) {
                failures.add(
                    "All target states were processed, but the durable batch journal could not be closed. It was retained for safe recovery."
                )
            }
        }
    }

    done to failures
}
