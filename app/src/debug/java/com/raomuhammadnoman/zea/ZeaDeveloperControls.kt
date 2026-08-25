package com.raomuhammadnoman.zea

import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.fragment.app.FragmentActivity

/**
 * Single source of truth for the fixed permanent developer access key.
 * This key is intentionally hardcoded, never generated, rotated, or changed
 * automatically. It is a plain developer convenience gate, NOT a
 * cryptographically secure secret against APK reverse engineering.
 *
 * Phase 2 (P1): this entire file lives in the DEBUG source set. Release
 * builds compile a stub instead (app/src/release) that contains no key, no
 * gate, and no controls - the developer surface is physically absent from
 * production APKs.
 *
 * Never expose the full key in normal UI, logs, analytics, or debug messages.
 */
const val DEVELOPER_ACCESS_KEY = "ZyroDevAccessKey7Q2MX"

/**
 * Compile-time gate for every developer-only surface. Always true in this
 * debug source set; the release stub compiles it to false so every
 * developer entry point in shared code is dead-stripped from production.
 */
val zeaDeveloperControlsEnabled: Boolean
    get() = true

/**
 * 17-character helper key ("ZyroDevAccessKeyX") derived from the full key so
 * the four secret characters exist only once in the entire codebase. An
 * authorized developer inserts the missing four characters right before the
 * final "X" of this helper to reconstruct the full key.
 */
val DEVELOPER_HELPER_KEY: String
    get() = DEVELOPER_ACCESS_KEY.removeRange(
        DEVELOPER_ACCESS_KEY.length - 5,
        DEVELOPER_ACCESS_KEY.length - 1
    )

/**
 * Developer-only authentication screen. Normal Zyro PIN never grants access
 * here; only the exact fixed developer key does. The input stays masked, has
 * NO submit/continue/login button, and validates automatically the moment it
 * reaches exactly the full key length. Errors appear only after the input is
 * complete, never before.
 */
@Composable
fun ZeaDeveloperAccessScreen(
    onBack: () -> Unit,
    onGranted: () -> Unit
) {
    val activity = LocalContext.current as? FragmentActivity
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current

    var devKeyInput by remember { mutableStateOf("") }
    var showIncorrectError by remember { mutableStateOf(false) }

    // Developer gate content must never leak into screenshots, recordings,
    // or the recents overview preview.
    DisposableEffect(activity) {
        val window = activity?.window
        window?.setFlags(
            android.view.WindowManager.LayoutParams.FLAG_SECURE,
            android.view.WindowManager.LayoutParams.FLAG_SECURE
        )
        onDispose {
            window?.clearFlags(
                android.view.WindowManager.LayoutParams.FLAG_SECURE
            )
        }
    }

    BackHandler(onBack = onBack)

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 22.dp)
                .padding(bottom = 28.dp)
        ) {
            Spacer(modifier = Modifier.height(18.dp))

            Text(
                text = "Developer Controls",
                fontSize = 26.sp,
                fontWeight = FontWeight.Bold
            )

            Spacer(modifier = Modifier.height(10.dp))

            Text(
                text = "This feature is only for developers. If you are an authorized developer, enter your developer access key.",
                fontSize = 14.sp,
                lineHeight = 20.sp,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.75f)
            )

            Spacer(modifier = Modifier.height(22.dp))

            OutlinedTextField(
                value = devKeyInput,
                onValueChange = { raw ->
                    val value = raw.take(DEVELOPER_ACCESS_KEY.length)
                    // Any edit resets a previous incorrect-key error; the
                    // error may only exist while the input is complete.
                    showIncorrectError = false
                    devKeyInput = value
                    if (value.length == DEVELOPER_ACCESS_KEY.length) {
                        if (value == DEVELOPER_ACCESS_KEY) {
                            onGranted()
                        } else {
                            showIncorrectError = true
                        }
                    }
                },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Developer access key") },
                placeholder = { Text("Paste helper key and insert 4 characters") },
                visualTransformation = PasswordVisualTransformation(),
                singleLine = true,
                isError = showIncorrectError,
                supportingText = if (showIncorrectError) {
                    { Text("Incorrect developer access key.") }
                } else {
                    null
                }
            )

            Spacer(modifier = Modifier.height(14.dp))

            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)
            ) {
                Row(
                    modifier = Modifier.padding(start = 16.dp, end = 6.dp, top = 6.dp, bottom = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Helper Key",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 1.2.sp,
                            color = MaterialTheme.colorScheme.primary
                        )

                        Spacer(modifier = Modifier.height(3.dp))

                        Text(
                            text = DEVELOPER_HELPER_KEY,
                            fontSize = 15.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                    }

                    IconButton(onClick = {
                        // Copies exactly the helper key, never the full key.
                        clipboard.setText(AnnotatedString(DEVELOPER_HELPER_KEY))
                        Toast.makeText(context, "Helper key copied", Toast.LENGTH_SHORT).show()
                    }) {
                        Icon(
                            imageVector = Icons.Filled.ContentCopy,
                            contentDescription = "Copy helper key"
                        )
                    }
                }
            }
        }
    }
}

/**
 * Developer-only interior. Categories group the developer-specific controls
 * that were removed from the normal Home UI. Each category embeds its
 * existing panel untouched, so all behavior stays identical.
 */
@Composable
fun ZeaDeveloperControlsScreen(onBack: () -> Unit) {
    val activity = LocalContext.current as? FragmentActivity

    var showOwnerManagement by remember { mutableStateOf(false) }

    DisposableEffect(activity) {
        val window = activity?.window
        window?.setFlags(
            android.view.WindowManager.LayoutParams.FLAG_SECURE,
            android.view.WindowManager.LayoutParams.FLAG_SECURE
        )
        onDispose {
            window?.clearFlags(
                android.view.WindowManager.LayoutParams.FLAG_SECURE
            )
        }
    }

    if (showOwnerManagement) {
        BackHandler { showOwnerManagement = false }
    } else {
        BackHandler(onBack = onBack)
    }

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .padding(horizontal = 22.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(bottom = 28.dp)
            ) {
                if (!showOwnerManagement) {
                    Spacer(modifier = Modifier.height(18.dp))

                    Text(
                        text = "Developer Controls",
                        fontSize = 26.sp,
                        fontWeight = FontWeight.Bold
                    )

                    Spacer(modifier = Modifier.height(10.dp))

                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(16.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)
                    ) {
                        Column(modifier = Modifier.padding(horizontal = 18.dp, vertical = 16.dp)) {
                            Text(
                                text = "Developer access granted.",
                                fontSize = 15.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(22.dp))

                    Text(
                        text = "CATEGORIES",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 1.2.sp,
                        color = MaterialTheme.colorScheme.primary
                    )

                    Spacer(modifier = Modifier.height(8.dp))

                    Surface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { showOwnerManagement = true },
                        shape = RoundedCornerShape(16.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)
                    ) {
                        Column(modifier = Modifier.padding(horizontal = 18.dp, vertical = 16.dp)) {
                            Text(
                                text = "Device Owner & App Management",
                                fontSize = 15.sp,
                                fontWeight = FontWeight.SemiBold
                            )

                            Spacer(modifier = Modifier.height(4.dp))

                            Text(
                                text = "Private Apps — Device Owner panel: App Hide, Reconcile, Emergency Unhide + Push, and Protection Install Lock controls.",
                                fontSize = 12.sp,
                                lineHeight = 17.sp,
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                            )
                        }
                    }
                } else {
                    Spacer(modifier = Modifier.height(10.dp))

                    Row(verticalAlignment = Alignment.CenterVertically) {
                        IconButton(onClick = { showOwnerManagement = false }) {
                            Icon(
                                imageVector = Icons.Filled.ArrowBack,
                                contentDescription = "Back"
                            )
                        }

                        Text(
                            text = "Device Owner & App Management",
                            fontSize = 20.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }

                    Spacer(modifier = Modifier.height(6.dp))

                    Text(
                        text = "Developer-only Device Owner app management. All controls behave exactly as before.",
                        fontSize = 13.sp,
                        lineHeight = 18.sp,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.75f)
                    )

                    Spacer(modifier = Modifier.height(14.dp))

                    ZeaDeviceOwnerPrivateAppsPanel()

                    Spacer(modifier = Modifier.height(24.dp))
                }
            }
        }
    }
}
