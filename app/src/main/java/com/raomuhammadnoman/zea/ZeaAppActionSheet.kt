package com.raomuhammadnoman.zea

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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import java.text.DateFormat
import java.util.Date

/**
 * Long press menu for a single app.
 *
 * Only actions that can actually be carried out are enabled, so the sheet never
 * offers a route that ends in an apology.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ZeaAppActionSheet(
    app: ZeaManagedApp,
    onHideApp: () -> Unit,
    onHideForTime: (() -> Unit)?,
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
            Row(verticalAlignment = Alignment.CenterVertically) {
                ZeaAppIcon(packageName = app.packageName)

                Spacer(modifier = Modifier.width(14.dp))

                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = app.displayName,
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )

                    Text(
                        text = app.packageName,
                        style = MaterialTheme.typography.bodySmall,
                        color = ZeaColors.SecondaryText,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            Spacer(modifier = Modifier.height(4.dp))

            ZeaAppActionRow(
                icon = ZeaIcons.Hidden,
                title = "Hide App",
                subtitle = "Hide this app now",
                onClick = onHideApp
            )

            ZeaAppActionRow(
                icon = ZeaIcons.Timed,
                title = "Hide For Time",
                subtitle = if (onHideForTime == null) {
                    "Not available yet"
                } else {
                    "Hide this app for a selected duration"
                },
                onClick = onHideForTime
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
private fun ZeaAppActionRow(
    icon: ImageVector,
    title: String,
    subtitle: String,
    onClick: (() -> Unit)?
) {
    val enabled = onClick != null
    val shape = RoundedCornerShape(18.dp)
    val contentAlpha = if (enabled) 1f else 0.45f

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .then(
                if (onClick == null) Modifier else Modifier.clickable(onClick = onClick)
            ),
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
                    modifier = Modifier.size(22.dp),
                    tint = MaterialTheme.colorScheme.onSurface.copy(alpha = contentAlpha)
                )
            }

            Spacer(modifier = Modifier.width(14.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = contentAlpha)
                )

                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = ZeaColors.SecondaryText.copy(alpha = contentAlpha)
                )
            }

            Icon(
                imageVector = ZeaIcons.Chevron,
                contentDescription = null,
                tint = ZeaColors.SecondaryText.copy(alpha = contentAlpha)
            )
        }
    }
}

/**
 * Final confirmation before an app disappears from the launcher.
 *
 * When this is the first app Zea protects, the dialog also states that Android
 * installs and updates stop for this user, because that side effect reaches far
 * beyond the app being hidden and cannot be discovered afterwards.
 */
@Composable
fun ZeaHideConfirmDialog(
    app: ZeaManagedApp,
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
            Text(
                text = "Hide ${app.displayName}?",
                style = MaterialTheme.typography.headlineSmall
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    // TEMPORARY BRAND NAME: "Zyro" is not the permanent app name and can be replaced with the original/permanent name "Zea Assistant" at any time.
                    text = "${app.displayName} will be hidden from your launcher and app list, and protected from uninstall. You can unhide it anytime from Zyro.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = ZeaColors.BodyText
                )

                if (!usageAccessGranted) {
                    ZeaHideWarning(
                        // TEMPORARY BRAND NAME: "Zyro" is not the permanent app name and can be replaced with the original/permanent name "Zea Assistant" at any time.
                        text = "Usage Access is not granted yet, so Zyro cannot open this app while it is hidden. Grant it from Private Apps first if you still need to use the app."
                    )
                }

                if (firstProtectedApp) {
                    ZeaHideWarning(
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
                Text(if (inProgress) "Hiding..." else "Hide App")
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ZeaHideForTimeSheet(
    displayName: String,
    firstProtectedApp: Boolean,
    usageAccessGranted: Boolean,
    inProgress: Boolean,
    onConfirm: (ZeaTimedHideRequest) -> Unit,
    onDismiss: () -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var amountText by remember { mutableStateOf("") }
    var selectedUnit by remember { mutableStateOf(ZeaTimeUnit.HOURS) }

    val request = remember(amountText, selectedUnit) {
        zeaTimedHideRequest(amountText, selectedUnit)
    }
    val endPreview = remember(request) {
        request?.let { value ->
            DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT)
                .format(Date(value.endEpochMillis))
        }
    }

    ModalBottomSheet(
        onDismissRequest = { if (!inProgress) onDismiss() },
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surface
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(start = 20.dp, end = 20.dp, bottom = 20.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                text = "Hide $displayName For",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold
            )

            Text(
                text = "Set how long this app stays hidden.",
                style = MaterialTheme.typography.bodyMedium,
                color = ZeaColors.SecondaryText
            )

            Spacer(modifier = Modifier.height(4.dp))

            OutlinedTextField(
                value = amountText,
                onValueChange = { value ->
                    amountText = value.filter { it.isDigit() }.take(6)
                },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Duration") },
                placeholder = { Text("Enter a number") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                isError = amountText.isNotEmpty() && request == null,
                enabled = !inProgress,
                singleLine = true,
                shape = RoundedCornerShape(16.dp)
            )

            Text(
                text = "Unit",
                style = MaterialTheme.typography.bodyMedium,
                color = ZeaColors.SecondaryText
            )

            LazyRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(ZeaTimeUnit.entries) { unit ->
                    FilterChip(
                        selected = unit == selectedUnit,
                        onClick = { selectedUnit = unit },
                        label = { Text(unit.label) },
                        enabled = !inProgress
                    )
                }
            }

            when {
                request != null -> Text(
                    text = "$displayName will unhide automatically on $endPreview.",
                    style = MaterialTheme.typography.bodySmall,
                    color = ZeaColors.BodyText
                )
                amountText.isNotEmpty() -> Text(
                    text = "Enter a duration greater than zero.",
                    style = MaterialTheme.typography.bodySmall,
                    color = ZeaColors.BannerErrorText
                )
            }

            if (!usageAccessGranted) {
                ZeaHideWarning(
                    // TEMPORARY BRAND NAME: "Zyro" is not the permanent app name and can be replaced with the original/permanent name "Zea Assistant" at any time.
                    text = "Usage Access is not granted yet, so Zyro cannot open this app while it is hidden. Grant it from Private Apps first if you still need to use the app."
                )
            }

            if (firstProtectedApp) {
                ZeaHideWarning(
                    // TEMPORARY BRAND NAME: "Zyro" is not the permanent app name and can be replaced with the original/permanent name "Zea Assistant" at any time.
                    text = "This is the first app Zyro will protect. While any app stays protected, Android blocks app installs and updates for this user, including updates to Zyro itself."
                )
            }

            Button(
                onClick = { request?.let(onConfirm) },
                enabled = !inProgress && request != null,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(28.dp)
            ) {
                Text(if (inProgress) "Hiding..." else "Confirm")
            }

            OutlinedButton(
                onClick = onDismiss,
                enabled = !inProgress,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(28.dp)
            ) {
                Text("Cancel")
            }
        }
    }
}

@Composable
private fun ZeaHideWarning(text: String) {
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
