package com.raomuhammadnoman.zea

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.HelpOutline
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.FactCheck
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Fingerprint
import androidx.compose.material.icons.filled.HealthAndSafety
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.SortByAlpha
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material.icons.filled.Warning
import androidx.compose.ui.graphics.vector.ImageVector

/**
 * Single place where every icon the Apps screens draw is resolved, so a missing
 * or renamed vector fails once at compile time here rather than in each screen.
 */
object ZeaIcons {
    val AppsGrid: ImageVector get() = Icons.Filled.GridView
    val PrivateApps: ImageVector get() = Icons.Filled.Lock
    val Logs: ImageVector get() = Icons.AutoMirrored.Filled.List
    val Settings: ImageVector get() = Icons.Filled.Settings
    val Help: ImageVector get() = Icons.AutoMirrored.Filled.HelpOutline
    val About: ImageVector get() = Icons.Filled.Info
    val Developer: ImageVector get() = Icons.Filled.Code
    val Copy: ImageVector get() = Icons.Filled.ContentCopy
    val Fingerprint: ImageVector get() = Icons.Filled.Fingerprint

    val Back: ImageVector get() = Icons.AutoMirrored.Filled.ArrowBack
    val Search: ImageVector get() = Icons.Filled.Search
    val Overflow: ImageVector get() = Icons.Filled.MoreVert
    val Chevron: ImageVector get() = Icons.Filled.ChevronRight

    val Hidden: ImageVector get() = Icons.Filled.VisibilityOff
    val Visible: ImageVector get() = Icons.Filled.Visibility
    val Timed: ImageVector get() = Icons.Filled.Schedule
    val Protection: ImageVector get() = Icons.Filled.Shield

    val Refresh: ImageVector get() = Icons.Filled.Refresh
    val SortByName: ImageVector get() = Icons.Filled.SortByAlpha
    val Confirm: ImageVector get() = Icons.Filled.Check
    val Cancel: ImageVector get() = Icons.Filled.Close

    val Timer: ImageVector get() = Icons.Filled.Timer
    val Health: ImageVector get() = Icons.Filled.HealthAndSafety
    val Diagnostics: ImageVector get() = Icons.Filled.FactCheck
    val Recovery: ImageVector get() = Icons.Filled.Build
    val Warning: ImageVector get() = Icons.Filled.Warning
}
