package com.raomuhammadnoman.zea

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import android.Manifest
import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.key
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.BorderStroke
import androidx.fragment.app.FragmentActivity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.delay
import java.util.concurrent.atomic.AtomicBoolean


private const val ZEA_LAUNCH_LOG_TAG = "ZeaLaunch"

private data class ZeaLocalPanelsSnapshot(
    val favoriteContacts: String,
    val allowedApps: String,
    val blockedApps: String,
    val adminPinStatus: String,
    val lastCommand: String,
    val lastResult: String,
    val commandLogs: String,
    val failedActions: String
)

private data class ZeaDisplayContact(
    val name: String,
    val phoneNumber: String
)

private data class ZeaAuditContact(
    val name: String,
    val phoneNumber: String,
    val phoneAvailable: Boolean,
    val whatsAppAvailable: Boolean
)

private data class ZeaContactAuditData(
    val totalContacts: Int,
    val whatsAppLinkedContacts: Int,
    val contacts: List<ZeaAuditContact>
)

private data class ZeaResultViewData(
    val rawCommand: String = "",
    val status: String = "",
    val action: String = "",
    val contactQuery: String = "",
    val selectedContact: ZeaDisplayContact? = null,
    val matchedContactPhrase: String = "",
    val confidence: String = "",
    val resolverReason: String = "",
    val launchSuccess: String = "",
    val launchMessage: String = "",
    val matchingContacts: List<ZeaDisplayContact> = emptyList(),
    val messageText: String = "",
    val contactAudit: ZeaContactAuditData? = null,
    val plainMessage: String = ""
)

private data class ZeaResolverOutcome(
    val status: String,
    val summary: String,
    val details: List<String>,
    val failed: Boolean
)

private fun zeaLineValue(
    lines: List<String>,
    key: String
): String {
    return lines.firstOrNull { line ->
        line.trim().startsWith("$key:", ignoreCase = true)
    }
        ?.substringAfter(":")
        ?.trim()
        ?: ""
}

private fun zeaSectionLines(
    lines: List<String>,
    sectionName: String
): List<String> {
    val sectionStartIndex = lines.indexOfFirst { line ->
        line.trim().equals("$sectionName:", ignoreCase = true)
    }

    if (sectionStartIndex < 0) {
        return emptyList()
    }

    val sectionHeaders = setOf(
        "selected contact:",
        "matched contact phrase:",
        "confidence:",
        "resolver reason:",
        "launch success:",
        "matching contacts:",
        "message:",
        "contact lookup count:"
    )

    return lines
        .drop(sectionStartIndex + 1)
        .takeWhile { line ->
            val cleaned = line.trim().lowercase()
            cleaned.isBlank() || cleaned !in sectionHeaders
        }
        .map { line -> line.trim() }
        .filter { line -> line.isNotBlank() }
}

private fun zeaParseContacts(
    lines: List<String>
): List<ZeaDisplayContact> {
    val contacts = mutableListOf<ZeaDisplayContact>()
    var currentName = ""
    var currentPhone = ""

    fun flushDisplayContact() {
        if (currentName.isNotBlank()) {
            contacts.add(
                ZeaDisplayContact(
                    name = currentName.trim(),
                    phoneNumber = currentPhone.trim()
                )
            )
        }

        currentName = ""
        currentPhone = ""
    }

    lines.forEach { line ->
        val cleaned = line.trim()
        val match = Regex("^\\d+\\.\\s*(.+)$").find(cleaned)

        if (match != null) {
            flushDisplayContact()
            currentName = match.groupValues[1].trim()
        } else if (currentName.isNotBlank() && currentPhone.isBlank()) {
            currentPhone = cleaned
        }
    }

    flushDisplayContact()

    return contacts.distinctBy { contact ->
        "${contact.name.lowercase()}|${contact.phoneNumber.filter { it.isDigit() }.takeLast(10)}"
    }
}

private fun zeaParseSelectedContact(
    lines: List<String>
): ZeaDisplayContact? {
    val selectedLines = zeaSectionLines(lines, "Selected Contact")

    if (selectedLines.isEmpty()) {
        return null
    }

    return ZeaDisplayContact(
        name = selectedLines.getOrNull(0).orEmpty(),
        phoneNumber = selectedLines.getOrNull(1).orEmpty()
    )
}

private fun zeaAuditPhoneKey(phoneNumber: String): String {
    val digits = buildString {
        phoneNumber.forEach { character ->
            if (character.isDigit()) {
                append(character)
            }
        }
    }

    return digits.takeLast(minOf(10, digits.length))
}

private fun zeaIsNumberOnlyName(name: String): Boolean {
    val cleanName = name.trim()

    if (cleanName.isBlank()) {
        return true
    }

    return cleanName.none { character ->
        character.isLetter()
    }
}

private fun zeaParseContactAudit(
    lines: List<String>
): ZeaContactAuditData? {
    val hasAuditSection = lines.any { line ->
        line.equals("Contacts Audit", ignoreCase = true)
    }

    if (!hasAuditSection) {
        return null
    }

    val totalContacts = zeaLineValue(lines, "Total contacts loaded")
        .toIntOrNull()
        ?: 0

    val whatsAppLinkedContacts = zeaLineValue(lines, "WhatsApp-linked contacts detected")
        .toIntOrNull()
        ?: 0

    val savedContactsStartIndex = lines.indexOfFirst { line ->
        line.equals("Saved contacts:", ignoreCase = true)
    }

    if (savedContactsStartIndex < 0) {
        return ZeaContactAuditData(
            totalContacts = totalContacts,
            whatsAppLinkedContacts = whatsAppLinkedContacts,
            contacts = emptyList()
        )
    }

    val contacts = mutableListOf<ZeaAuditContact>()

    var currentName = ""
    var currentPhone = ""
    var currentWhatsAppAvailable = false

    fun flushAuditContact() {
        if (currentName.isNotBlank()) {
            contacts.add(
                ZeaAuditContact(
                    name = currentName.trim(),
                    phoneNumber = currentPhone.trim(),
                    phoneAvailable = true,
                    whatsAppAvailable = currentWhatsAppAvailable
                )
            )
        }

        currentName = ""
        currentPhone = ""
        currentWhatsAppAvailable = false
    }

    lines
        .drop(savedContactsStartIndex + 1)
        .forEach { line ->
            val cleaned = line.trim()
            val contactNameMatch = Regex("^\\d+\\.\\s*(.+)$").find(cleaned)

            when {
                contactNameMatch != null -> {
                    flushAuditContact()
                    currentName = contactNameMatch.groupValues[1].trim()
                }

                cleaned.startsWith("Number:", ignoreCase = true) -> {
                    currentPhone = cleaned.substringAfter(":").trim()
                }

                cleaned.startsWith("WhatsApp linked:", ignoreCase = true) -> {
                    val value = cleaned.substringAfter(":").trim()
                    currentWhatsAppAvailable = value.equals("Yes", ignoreCase = true)
                }
            }
        }

    flushAuditContact()

    val distinctContacts = contacts
        .distinctBy { contact ->
            val phoneKey = zeaAuditPhoneKey(contact.phoneNumber)
            val nameKey = contact.name.lowercase().trim()

            if (phoneKey.isNotBlank()) {
                "$nameKey|$phoneKey"
            } else {
                "$nameKey|${contact.phoneNumber}"
            }
        }
        .sortedWith(
            compareBy<ZeaAuditContact> { contact ->
                zeaIsNumberOnlyName(contact.name)
            }.thenBy { contact ->
                contact.name.lowercase()
            }
        )

    return ZeaContactAuditData(
        totalContacts = if (totalContacts > 0) totalContacts else distinctContacts.size,
        whatsAppLinkedContacts = whatsAppLinkedContacts,
        contacts = distinctContacts
    )
}

private fun zeaParseResultText(resultText: String): ZeaResultViewData {
    val lines = resultText
        .lines()
        .map { line -> line.trim() }
        .filter { line -> line.isNotBlank() }

    val rawCommand = zeaLineValue(lines, "Raw Command")
    val status = zeaLineValue(lines, "Status")
    val action = zeaLineValue(lines, "Action")
    val contactQuery = zeaLineValue(lines, "Contact Query")

    val matchedContactPhrase = zeaSectionLines(lines, "Matched Contact Phrase")
        .firstOrNull()
        .orEmpty()

    val confidence = zeaSectionLines(lines, "Confidence")
        .firstOrNull()
        .orEmpty()

    val resolverReason = zeaSectionLines(lines, "Resolver Reason")
        .joinToString(" ")

    val launchSuccess = zeaLineValue(lines, "Launch Success")

    val launchMessage = lines
        .dropWhile { line -> !line.startsWith("Launch Success:", ignoreCase = true) }
        .drop(1)
        .firstOrNull()
        .orEmpty()

    val matchingContacts = zeaParseContacts(
        zeaSectionLines(lines, "Matching Contacts")
    )
    val messageText = zeaSectionLines(lines, "Message")
        .firstOrNull()
        .orEmpty()

    val contactAudit = zeaParseContactAudit(lines)

    val plainMessage = when {
        contactAudit != null ->
            ""
        lines.any { it.contains("Multiple matching contacts were found", ignoreCase = true) } ->
            "Multiple matching contacts were found. Please select the correct contact or type the full saved contact name."

        lines.any { it.contains("Contact not found", ignoreCase = true) } ->
            "Contact not found."

        else ->
            lines
                .filterNot { line ->
                    line.startsWith("Raw Command:", ignoreCase = true) ||
                            line.startsWith("Status:", ignoreCase = true) ||
                            line.startsWith("Action:", ignoreCase = true) ||
                            line.startsWith("Contact Query:", ignoreCase = true)
                }
                .take(2)
                .joinToString(" ")
    }

    return ZeaResultViewData(
        rawCommand = rawCommand,
        status = status,
        action = action,
        contactQuery = contactQuery,
        selectedContact = zeaParseSelectedContact(lines),
        matchedContactPhrase = matchedContactPhrase,
        confidence = confidence,
        resolverReason = resolverReason,
        launchSuccess = launchSuccess,
        launchMessage = launchMessage,
        matchingContacts = matchingContacts,
        messageText = messageText,
        contactAudit = contactAudit,
        plainMessage = plainMessage
    )
}
class MainActivity : FragmentActivity() {
    private var autoLockScreenOffReceiver: BroadcastReceiver? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Self-clear hook: launching with this extra wipes only the lock
        // credentials and onboarding flags so the next screen is the very
        // first Create-PIN flow again. Device Owner state, hidden-app
        // records, and every other setting stay untouched.
        if (intent?.getBooleanExtra(ZEA_SELF_CLEAR_LOCK_EXTRA, false) == true) {
            selfClearLockState(this)
            // A wiped app must never come back with security still off.
            ZeaSecurityState.resetToDefaults(this)
        }

        // Self-remove hook: launching with this extra drops the Device Owner
        // role and disables the device admin so the app can be uninstalled
        // like a normal package.
        if (intent?.getBooleanExtra(ZEA_SELF_REMOVE_OWNER_EXTRA, false) == true) {
            selfRemoveDeviceOwner(this)
        }

        // Central security switches (persisted) must be loaded before any
        // gate or screen reads them.
        ZeaSecurityState.load(this)

        // Phase 2 auto-lock: restore the chosen policy, hook process
        // lifecycle, and listen for screen-off events.
        ZeaAutoLock.load(this)
        ZeaAutoLock.attach(this)
        ProcessLifecycleOwner.get().lifecycle.addObserver(ZeaAutoLock.processObserver)
        autoLockScreenOffReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                if (intent.action == Intent.ACTION_SCREEN_OFF) {
                    ZeaAutoLock.onScreenOff(context)
                }
            }
        }
        registerReceiver(
            autoLockScreenOffReceiver,
            IntentFilter(Intent.ACTION_SCREEN_OFF)
        )

        setContent {
            // A bump of the auto-lock epoch recreates the whole gate so the
            // session re-derives from scratch and lands on the PIN screen.
            key(ZeaAutoLock.lockEpoch) {
                ZeaAppLockGate(activity = this) {
                    ZeaApp()
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        ZeaDeviceOwnerController.uiInForeground = true
    }

    override fun onStop() {
        ZeaDeviceOwnerController.uiInForeground = false
        ZeaDeviceOwnerController.flushPendingLauncherRefresh(applicationContext)
        super.onStop()
    }

    override fun onDestroy() {
        autoLockScreenOffReceiver?.let { receiver ->
            try {
                unregisterReceiver(receiver)
            } catch (_: RuntimeException) {
                // Receiver may already be unregistered during teardown.
            }
        }
        autoLockScreenOffReceiver = null
        super.onDestroy()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        // The owner keep-alive binding can resurrect a force-stopped task
        // before an adb relaunch lands, so the launch lands here instead of
        // onCreate. Honor the maintenance extras on that path too and
        // rebuild the whole gate against the freshly wiped state.
        val handled = run {
            var any = false
            if (intent?.getBooleanExtra(ZEA_SELF_CLEAR_LOCK_EXTRA, false) == true) {
                selfClearLockState(this)
                any = true
            }
            if (intent?.getBooleanExtra(ZEA_SELF_REMOVE_OWNER_EXTRA, false) == true) {
                selfRemoveDeviceOwner(this)
                any = true
            }
            any
        }
        if (handled) {
            recreate()
        }
    }
}

@Composable
private fun ZeaResultPanel(
    resultText: String,
    onMatchingContactClick: (ZeaDisplayContact, ZeaResultViewData) -> Unit = { _, _ -> }
) {
    val data = remember(resultText) {
        zeaParseResultText(resultText)
    }
    var visibleAuditCount by rememberSaveable(resultText) {
        mutableStateOf(3)
    }

    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(26.dp),
        color = Color(0xFFE9ECFF),
        border = BorderStroke(1.dp, Color(0xFFCCD3FF))
    ) {
        CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
            Column(
                modifier = Modifier.padding(18.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                Text(
                    text = "Result",
                    style = MaterialTheme.typography.titleLarge.copy(
                        fontWeight = FontWeight.Bold,
                        textDirection = TextDirection.Ltr
                    ),
                    color = Color(0xFF1F2430)
                )

                if (data.status.isNotBlank()) {
                    ZeaStatusChip(status = data.status)
                }

                ZeaInfoRow(label = "Raw Command", value = data.rawCommand)
                ZeaInfoRow(label = "Action", value = data.action)
                ZeaInfoRow(label = "Contact Query", value = data.contactQuery)

                if (data.plainMessage.isNotBlank()) {
                    Text(
                        text = data.plainMessage,
                        style = MaterialTheme.typography.bodyMedium.copy(
                            textDirection = TextDirection.Ltr
                        ),
                        color = Color(0xFF374151)
                    )
                }
                data.contactAudit?.let { auditData ->
                    ZeaContactAuditSection(
                        auditData = auditData,
                        visibleCount = visibleAuditCount,
                        onShowMore = {
                            visibleAuditCount = (visibleAuditCount + 5)
                                .coerceAtMost(auditData.contacts.size)
                        },
                        onShowLess = {
                            visibleAuditCount = 3
                        }
                    )
                }

                data.selectedContact?.let { contact ->
                    ZeaContactCard(
                        index = 1,
                        contact = contact,
                        title = "Selected Contact"
                    )
                }

                if (data.matchingContacts.isNotEmpty()) {
                    Text(
                        text = "Matching Contacts",
                        style = MaterialTheme.typography.titleMedium.copy(
                            fontWeight = FontWeight.Bold,
                            textDirection = TextDirection.Ltr
                        ),
                        color = Color(0xFF1F2430)
                    )

                    data.matchingContacts.forEachIndexed { index, contact ->
                        ZeaContactCard(
                            index = index + 1,
                            contact = contact,
                            title = "",
                            onClick = {
                                onMatchingContactClick(contact, data)
                            }
                        )
                    }
                }

                ZeaInfoRow(label = "Matched Phrase", value = data.matchedContactPhrase)
                ZeaInfoRow(label = "Confidence", value = data.confidence)
                ZeaInfoRow(label = "Resolver Reason", value = data.resolverReason)

                if (data.launchSuccess.isNotBlank()) {
                    ZeaInfoRow(
                        label = "Launch",
                        value = if (data.launchMessage.isNotBlank()) {
                            "${data.launchSuccess} - ${data.launchMessage}"
                        } else {
                            data.launchSuccess
                        }
                    )
                }
            }
        }
    }
}

@Composable
private fun ZeaContactAuditSection(
    auditData: ZeaContactAuditData,
    visibleCount: Int,
    onShowMore: () -> Unit,
    onShowLess: () -> Unit
) {
    val visibleContacts = auditData.contacts.take(visibleCount)
    val phoneOnlyCount = auditData.contacts.count { contact ->
        contact.phoneAvailable && !contact.whatsAppAvailable
    }

    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(
            text = "Contacts Audit",
            style = MaterialTheme.typography.titleMedium.copy(
                fontWeight = FontWeight.Bold,
                textDirection = TextDirection.Ltr
            ),
            color = Color(0xFF1F2430)
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            ZeaAuditMiniCard(
                label = "Total",
                value = auditData.totalContacts.toString(),
                modifier = Modifier.weight(1f)
            )

            ZeaAuditMiniCard(
                label = "WhatsApp",
                value = auditData.whatsAppLinkedContacts.toString(),
                modifier = Modifier.weight(1f)
            )

            ZeaAuditMiniCard(
                label = "Phone Only",
                value = phoneOnlyCount.toString(),
                modifier = Modifier.weight(1f)
            )
        }

        Text(
            text = "Saved Contacts",
            style = MaterialTheme.typography.titleSmall.copy(
                fontWeight = FontWeight.Bold,
                textDirection = TextDirection.Ltr
            ),
            color = Color(0xFF6D5EA8)
        )

        if (visibleContacts.isEmpty()) {
            Text(
                text = "No contacts were available for display.",
                style = MaterialTheme.typography.bodyMedium.copy(
                    textDirection = TextDirection.Ltr
                ),
                color = Color(0xFF5F6368)
            )
        } else {
            visibleContacts.forEachIndexed { index, contact ->
                ZeaAuditContactCard(
                    index = index + 1,
                    contact = contact
                )
            }
        }

        if (auditData.contacts.size > 3) {
            if (visibleCount < auditData.contacts.size) {
                OutlinedButton(
                    onClick = onShowMore,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(18.dp)
                ) {
                    Text("Show More")
                }
            } else {
                OutlinedButton(
                    onClick = onShowLess,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(18.dp)
                ) {
                    Text("Show Less")
                }
            }
        }
    }
}

@Composable
private fun ZeaAuditMiniCard(
    label: String,
    value: String,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(18.dp),
        color = Color(0xFFFFFFFF)
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = value,
                style = MaterialTheme.typography.titleMedium.copy(
                    fontWeight = FontWeight.Bold,
                    textDirection = TextDirection.Ltr
                ),
                color = Color(0xFF5E35B1)
            )

            Spacer(modifier = Modifier.height(3.dp))

            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall.copy(
                    fontWeight = FontWeight.SemiBold,
                    textDirection = TextDirection.Ltr
                ),
                color = Color(0xFF5F6368)
            )
        }
    }
}

@Composable
private fun ZeaAuditContactCard(
    index: Int,
    contact: ZeaAuditContact
) {
    ZeaContactSurfaceCard(
        index = index,
        name = contact.name,
        phoneNumber = contact.phoneNumber,
        footer = {
            Spacer(modifier = Modifier.height(8.dp))

            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                ZeaSmallStatusPill(
                    label = "Phone",
                    active = contact.phoneAvailable
                )

                ZeaSmallStatusPill(
                    label = "WhatsApp",
                    active = contact.whatsAppAvailable
                )
            }
        }
    )
}

@Composable
private fun ZeaSmallStatusPill(
    label: String,
    active: Boolean
) {
    val backgroundColor = if (active) {
        Color(0xFFE8F5E9)
    } else {
        Color(0xFFFFEBEE)
    }

    val textColor = if (active) {
        Color(0xFF1B5E20)
    } else {
        Color(0xFFB71C1C)
    }

    val symbol = if (active) {
        "Yes"
    } else {
        "No"
    }

    Surface(
        shape = RoundedCornerShape(50.dp),
        color = backgroundColor
    ) {
        Text(
            text = "$label $symbol",
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
            style = MaterialTheme.typography.labelMedium.copy(
                fontWeight = FontWeight.Bold,
                textDirection = TextDirection.Ltr
            ),
            color = textColor
        )
    }
}

@Composable
private fun ZeaStatusChip(status: String) {
    val normalized = status.lowercase()

    val backgroundColor = when {
        normalized == "success" -> Color(0xFFE8F5E9)
        normalized.contains("selection") -> Color(0xFFFFF8E1)
        normalized == "error" -> Color(0xFFFFEBEE)
        else -> Color(0xFFE3F2FD)
    }

    val textColor = when {
        normalized == "success" -> Color(0xFF1B5E20)
        normalized.contains("selection") -> Color(0xFF7A4F01)
        normalized == "error" -> Color(0xFFB71C1C)
        else -> Color(0xFF0D47A1)
    }

    val displayText = when {
        normalized == "success" -> "Success"
        normalized.contains("selection") -> "Needs Contact Selection"
        normalized == "error" -> "Error"
        else -> status.replace("_", " ")
    }

    Surface(
        shape = RoundedCornerShape(50.dp),
        color = backgroundColor
    ) {
        Text(
            text = displayText,
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 7.dp),
            style = MaterialTheme.typography.bodyMedium.copy(
                fontWeight = FontWeight.SemiBold,
                textDirection = TextDirection.Ltr
            ),
            color = textColor
        )
    }
}

@Composable
private fun ZeaInfoRow(
    label: String,
    value: String
) {
    if (value.isBlank()) {
        return
    }

    Column(
        modifier = Modifier.fillMaxWidth()
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium.copy(
                fontWeight = FontWeight.Bold,
                textDirection = TextDirection.Ltr
            ),
            color = Color(0xFF6D5EA8)
        )

        Spacer(modifier = Modifier.height(3.dp))

        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium.copy(
                textDirection = TextDirection.Ltr
            ),
            color = Color(0xFF202124)
        )
    }
}

@Composable
private fun ZeaContactCard(
    index: Int,
    contact: ZeaDisplayContact,
    title: String,
    onClick: (() -> Unit)? = null
) {
    ZeaContactSurfaceCard(
        index = index,
        name = contact.name,
        phoneNumber = contact.phoneNumber,
        title = title,
        onClick = onClick,
        footer = if (onClick == null) {
            null
        } else {
            {
                Spacer(modifier = Modifier.height(6.dp))

                Text(
                    text = "Tap to select",
                    style = MaterialTheme.typography.labelMedium.copy(
                        fontWeight = FontWeight.SemiBold,
                        textDirection = TextDirection.Ltr
                    ),
                    color = Color(0xFF5E35B1)
                )
            }
        }
    )
}

@Composable
private fun ZeaContactSurfaceCard(
    index: Int,
    name: String,
    phoneNumber: String,
    title: String = "",
    onClick: (() -> Unit)? = null,
    footer: (@Composable () -> Unit)? = null
) {
    val cardModifier = if (onClick == null) {
        Modifier.fillMaxWidth()
    } else {
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
    }

    Surface(
        modifier = cardModifier,
        shape = RoundedCornerShape(20.dp),
        color = Color(0xFFFFFFFF)
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Surface(
                modifier = Modifier.size(38.dp),
                shape = CircleShape,
                color = Color(0xFFEDE7F6)
            ) {
                Box(
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = index.toString(),
                        style = MaterialTheme.typography.bodyMedium.copy(
                            fontWeight = FontWeight.Bold,
                            textDirection = TextDirection.Ltr
                        ),
                        color = Color(0xFF5E35B1)
                    )
                }
            }

            Spacer(modifier = Modifier.width(12.dp))

            Column(
                modifier = Modifier.weight(1f)
            ) {
                if (title.isNotBlank()) {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.labelMedium.copy(
                            fontWeight = FontWeight.Bold,
                            textDirection = TextDirection.Ltr
                        ),
                        color = Color(0xFF6D5EA8)
                    )

                    Spacer(modifier = Modifier.height(4.dp))
                }

                Text(
                    text = name.ifBlank { "Unnamed Contact" },
                    style = MaterialTheme.typography.bodyLarge.copy(
                        fontWeight = FontWeight.Bold,
                        textDirection = TextDirection.Ltr
                    ),
                    color = Color(0xFF202124)
                )

                if (phoneNumber.isNotBlank()) {
                    Spacer(modifier = Modifier.height(3.dp))

                    Text(
                        text = phoneNumber,
                        style = MaterialTheme.typography.bodyMedium.copy(
                            textDirection = TextDirection.Ltr
                        ),
                        color = Color(0xFF5F6368)
                    )
                }

                footer?.invoke()
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ZeaApp() {
    val context = LocalContext.current
    val activity = context as FragmentActivity
    val coroutineScope = rememberCoroutineScope()
    val runCommandGate = remember { AtomicBoolean(false) }
    val privateAppsOperationGate = remember { AtomicBoolean(false) }

    var commandText by remember { mutableStateOf("") }
    var commandInlineErrorText by remember { mutableStateOf("") }
    var selectedCommandStrategy by remember { mutableStateOf(CommandStrategy.OPEN_APPS) }
    var pendingWhatsAppMessage by remember { mutableStateOf<PendingWhatsAppMessage?>(null) }
    var isAppLaunchInProgress by remember { mutableStateOf(false) }
    var adminPinInput by remember { mutableStateOf("") }
    var clearActionPinInput by remember { mutableStateOf("") }
    var adminPinStatusValue by remember { mutableStateOf(adminPinStatusText(context)) }
    var appsRouteName by rememberSaveable { mutableStateOf("") }
    var showAboutScreen by rememberSaveable { mutableStateOf(false) }
    var showDeveloperAccess by rememberSaveable { mutableStateOf(false) }
    var showDeveloperControls by rememberSaveable { mutableStateOf(false) }
    var showSettingsScreen by rememberSaveable { mutableStateOf(false) }
    var showDiagnostics by rememberSaveable { mutableStateOf(false) }
    var homeMenuExpanded by remember { mutableStateOf(false) }

    var favoriteContactsInput by remember { mutableStateOf(loadFavoriteContacts(context)) }
    var allowedAppsInput by remember { mutableStateOf(loadAllowedApps(context)) }
    var blockedAppsInput by remember { mutableStateOf(loadBlockedApps(context)) }

    var lastCommandText by remember { mutableStateOf(loadLastCommand(context)) }
    var lastResultText by remember { mutableStateOf(loadLastResult(context)) }
    var commandLogsText by remember { mutableStateOf(loadCommandLogs(context)) }
    var failedActionsText by remember { mutableStateOf(loadFailedActions(context)) }
    var passedActivityVisibleCount by remember { mutableStateOf(3) }
    var failedActivityVisibleCount by remember { mutableStateOf(3) }
    var topStatusMessage by remember { mutableStateOf("") }
    var topStatusType by remember { mutableStateOf("success") }
    var topStatusId by remember { mutableStateOf(0) }
    var privateAppsOperationInProgress by remember { mutableStateOf(false) }
    var privateAppInput by remember { mutableStateOf("") }
    var privateAppsStatus by remember { mutableStateOf("") }
    var privateApps by remember { mutableStateOf<List<PrivateAppRecord>>(emptyList()) }
    var isRefreshingHome by remember { mutableStateOf(false) }
    var interruptedBatch by remember { mutableStateOf<ZeaBatchJournalRecord?>(null) }
    var interruptedBatchReview by remember { mutableStateOf(false) }
    var interruptedBatchBusy by remember { mutableStateOf(false) }
    var homeTotalApps by remember { mutableStateOf(0) }
    var homeHiddenCount by remember { mutableStateOf(0) }
    var homeTimedCount by remember { mutableStateOf(0) }
    var homeOwnerActive by remember { mutableStateOf(false) }
    var homeStatsVisible by remember { mutableStateOf(false) }
    var homeStatsShowToken by remember { mutableStateOf(0) }

    suspend fun refreshHomeStats() {
        try {
            val ownerActive = ZeaDeviceOwnerController.isDeviceOwner(context)
            val apps = ZeaAppCatalog.loadManagedApps(context)
            homeTotalApps = apps.size
            homeHiddenCount = apps.count { app ->
                app.hideMode != ZeaHideMode.VISIBLE
            }
            homeTimedCount = apps.count { app ->
                app.hideMode == ZeaHideMode.TIMED &&
                        app.hiddenUntilEpochMillis > System.currentTimeMillis()
            }
            homeOwnerActive = ownerActive
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            Log.w(ZEA_LAUNCH_LOG_TAG, "home stats refresh failed safely", error)
        }
    }

    LaunchedEffect(Unit) {
        refreshHomeStats()
    }

    // The stats card is a brief confirmation of a refresh, never permanent
    // chrome. Bumping the token restarts this effect, so a fresh pull during
    // the visible window simply extends it instead of hiding early.
    LaunchedEffect(homeStatsShowToken) {
        if (homeStatsShowToken == 0) {
            return@LaunchedEffect
        }
        delay(2500)
        homeStatsVisible = false
    }

    // TEMPORARY BRAND NAME: "Zyro" is not the permanent app name and can be replaced with the original/permanent name "Zea Assistant" at any time.
    var resultText by rememberSaveable {
        mutableStateOf(
            """
        Zyro v1.0
        First Stable Personal Release

        Type a command and Zyro will choose the right action.

        Open Apps: configured apps plus verified Allowed Apps
        Private Apps: Device Owner hidden-at-rest protection on a dedicated managed test device
        Send Message: Tell John: I will meet you tomorrow

        Note: WhatsApp messages are pre-filled only. You still press Send manually.
    """.trimIndent()
        )
    }

    fun showTopStatus(
        message: String,
        type: String = "success"
    ) {
        topStatusMessage = message
        topStatusType = type
        topStatusId += 1
    }

    fun splitAuditList(value: String): List<String> {
        return value
            .split(",", "\n")
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .distinctBy { it.lowercase() }
    }

    fun formatAuditList(items: List<String>): String {
        return items.joinToString(", ")
    }

    fun buildAddedRemovedSummary(
        label: String,
        oldItems: List<String>,
        newItems: List<String>
    ): List<String> {
        val oldMap = oldItems.associateBy { it.lowercase() }
        val newMap = newItems.associateBy { it.lowercase() }

        val addedItems = newMap
            .filterKeys { key -> key !in oldMap }
            .values
            .toList()

        val removedItems = oldMap
            .filterKeys { key -> key !in newMap }
            .values
            .toList()

        val changes = mutableListOf<String>()

        if (addedItems.isNotEmpty()) {
            changes.add("$label added: ${formatAuditList(addedItems)}.")
        }

        if (removedItems.isNotEmpty()) {
            changes.add("$label removed: ${formatAuditList(removedItems)}.")
        }

        return changes
    }

    fun loadResolverContactsForCommand(
        context: Context,
        parsedContactQuery: String
    ): List<ContactResult> {
        val contacts = mutableListOf<ContactResult>()

        if (parsedContactQuery.isNotBlank()) {
            contacts.addAll(searchContacts(context, parsedContactQuery))
        }

        contacts.addAll(loadAllContactsForAudit(context, maxResults = 500))

        return contacts
            .distinctBy { contact ->
                "${contact.name.lowercase()}-${contact.phoneNumber}"
            }
            .take(500)
    }


    fun readLocalPanelsSnapshot(): ZeaLocalPanelsSnapshot {
        return ZeaLocalPanelsSnapshot(
            favoriteContacts = loadFavoriteContacts(context),
            allowedApps = loadAllowedApps(context),
            blockedApps = loadBlockedApps(context),
            adminPinStatus = adminPinStatusText(context),
            lastCommand = loadLastCommand(context),
            lastResult = loadLastResult(context),
            commandLogs = loadCommandLogs(context),
            failedActions = loadFailedActions(context)
        )
    }

    fun applyLocalPanelsSnapshot(snapshot: ZeaLocalPanelsSnapshot) {
        favoriteContactsInput = snapshot.favoriteContacts
        allowedAppsInput = snapshot.allowedApps
        blockedAppsInput = snapshot.blockedApps
        adminPinStatusValue = snapshot.adminPinStatus
        lastCommandText = snapshot.lastCommand
        lastResultText = snapshot.lastResult
        commandLogsText = snapshot.commandLogs
        failedActionsText = snapshot.failedActions
    }

    fun refreshLocalPanels() {
        coroutineScope.launch {
            val snapshot = withContext(Dispatchers.IO) {
                readLocalPanelsSnapshot()
            }
            applyLocalPanelsSnapshot(snapshot)
        }
    }

    fun persistExecutionResult(
        rawCommand: String,
        status: String,
        action: String,
        summary: String,
        fullResult: String,
        failed: Boolean
    ) {
        saveLastCommand(context, rawCommand)
        saveLastResult(context, fullResult)
        saveCommandLog(
            context = context,
            rawCommand = rawCommand,
            status = status,
            action = action,
            summary = summary
        )

        if (failed) {
            saveFailedAction(
                context = context,
                rawCommand = rawCommand,
                action = action,
                reason = summary
            )
        }
    }

    fun applyExecutionResultUi(
        summary: String,
        fullResult: String,
        failed: Boolean,
        snapshot: ZeaLocalPanelsSnapshot? = null
    ) {
        resultText = fullResult
        commandInlineErrorText = if (failed) {
            "Command failed: $summary"
        } else {
            ""
        }
        snapshot?.let(::applyLocalPanelsSnapshot)
    }

    fun saveExecutionResult(
        rawCommand: String,
        status: String,
        action: String,
        summary: String,
        fullResult: String,
        failed: Boolean,
        finishAssistantTaskAfterSave: Boolean = false
    ) {
        applyExecutionResultUi(
            summary = summary,
            fullResult = fullResult,
            failed = failed
        )

        coroutineScope.launch {
            val snapshot = withContext(Dispatchers.IO) {
                persistExecutionResult(
                    rawCommand = rawCommand,
                    status = status,
                    action = action,
                    summary = summary,
                    fullResult = fullResult,
                    failed = failed
                )
                readLocalPanelsSnapshot()
            }
            applyLocalPanelsSnapshot(snapshot)
            if (finishAssistantTaskAfterSave) {
                Log.i(
                    ZEA_LAUNCH_LOG_TAG,
                    // TEMPORARY BRAND NAME: "Zyro" is not the permanent app name and can be replaced with the original/permanent name "Zea Assistant" at any time.
                    "successful target launch; finishing Zyro task action=$action"
                )
                activity.finishAndRemoveTask()
            }
        }
    }

    suspend fun saveExecutionResultAndWait(
        rawCommand: String,
        status: String,
        action: String,
        summary: String,
        fullResult: String,
        failed: Boolean
    ) {
        val persistenceStarted = SystemClock.elapsedRealtime()
        Log.i(ZEA_LAUNCH_LOG_TAG, "result persistence start action=$action")

        val snapshot = withContext(Dispatchers.IO) {
            persistExecutionResult(
                rawCommand = rawCommand,
                status = status,
                action = action,
                summary = summary,
                fullResult = fullResult,
                failed = failed
            )
            readLocalPanelsSnapshot()
        }

        Log.i(
            ZEA_LAUNCH_LOG_TAG,
            "result persistence end action=$action elapsedMs=${SystemClock.elapsedRealtime() - persistenceStarted}"
        )

        applyExecutionResultUi(
            summary = summary,
            fullResult = fullResult,
            failed = failed,
            snapshot = snapshot
        )
    }

    suspend fun <T> runIoPhase(
        phase: String,
        block: () -> T
    ): T {
        val started = SystemClock.elapsedRealtime()
        Log.i(ZEA_LAUNCH_LOG_TAG, "$phase start")
        return try {
            withContext(Dispatchers.IO) { block() }
        } finally {
            Log.i(
                ZEA_LAUNCH_LOG_TAG,
                "$phase end elapsedMs=${SystemClock.elapsedRealtime() - started}"
            )
        }
    }

    fun finishAssistantAfterSuccessfulLaunch(action: String) {
        Log.i(
            ZEA_LAUNCH_LOG_TAG,
            // TEMPORARY BRAND NAME: "Zyro" is not the permanent app name and can be replaced with the original/permanent name "Zea Assistant" at any time.
            "successful target launch; finishing Zyro task action=$action"
        )
        activity.finishAndRemoveTask()
    }

    suspend fun executeAppLaunch(
        parsedCommand: ZeaCommand,
        resolvedEntry: AppRegistryEntry? = null,
        operationStartedElapsedRealtime: Long? = null,
        privateAccess: Boolean = false
    ) {
        val launchStarted = SystemClock.elapsedRealtime()
        val launchResult = try {
            if (resolvedEntry != null && privateAccess) {
                ZeaAppLauncher.launchPrivateResolvedEntryWithTimeout(
                    context = context,
                    entry = resolvedEntry,
                    operationStartedElapsedRealtime = operationStartedElapsedRealtime
                )
            } else if (resolvedEntry != null) {
                ZeaAppLauncher.launchResolvedEntryWithTimeout(
                    context = context,
                    entry = resolvedEntry,
                    operationStartedElapsedRealtime = operationStartedElapsedRealtime
                )
            } else {
                ZeaAppLauncher.launchAppWithTimeout(
                    context = context,
                    appKey = parsedCommand.appKey,
                    displayName = parsedCommand.appDisplayName,
                    operationStartedElapsedRealtime = operationStartedElapsedRealtime
                )
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            Log.e(ZEA_LAUNCH_LOG_TAG, "launch chain failed safely", error)
            LaunchResult(
                success = false,
                message = "${parsedCommand.appDisplayName} could not be opened safely.",
                failureReason = AppLaunchFailureReason.LAUNCH_FAILED
            )
        }

        Log.i(
            ZEA_LAUNCH_LOG_TAG,
            "launch chain result success=${launchResult.success} elapsedMs=${SystemClock.elapsedRealtime() - launchStarted}"
        )

        val fullResult = """
            Raw Command: ${parsedCommand.rawCommand}
            Status: ${if (launchResult.success) parsedCommand.status else "error"}
            Action: ${parsedCommand.action}
            App: ${parsedCommand.appDisplayName}

            Launch Success: ${launchResult.success}
            ${launchResult.message}
        """.trimIndent()

        saveExecutionResultAndWait(
            rawCommand = parsedCommand.rawCommand,
            status = if (launchResult.success) parsedCommand.status else "error",
            action = parsedCommand.action,
            summary = launchResult.message,
            fullResult = fullResult,
            failed = !launchResult.success
        )

        if (launchResult.success) {
            finishAssistantAfterSuccessfulLaunch(parsedCommand.action)
        }
    }

    suspend fun executePrivateAppCommand(
        request: PrivateAppOpenRequest,
        operationStartedElapsedRealtime: Long
    ) {
        val record = request.record

        if (record == null) {
            val message = "${request.requestedName.ifBlank { "The requested app" }} is not configured as a Device Owner private app."
            val fullResult = """
                Raw Command: ${request.rawCommand}
                Status: error
                Action: open_private_app

                $message
            """.trimIndent()

            saveExecutionResultAndWait(
                rawCommand = request.rawCommand,
                status = "error",
                action = "open_private_app",
                summary = message,
                fullResult = fullResult,
                failed = true
            )
            return
        }

        Log.i(
            ZEA_DEVICE_OWNER_LOG_TAG,
            "authenticated private launch requested package=${record.packageName} operationAgeMs=${SystemClock.elapsedRealtime() - operationStartedElapsedRealtime}"
        )

        val result = ZeaDeviceOwnerController.launchPrivateApp(context, record)
        val fullResult = """
            Raw Command: ${request.rawCommand}
            Status: ${if (result.success) "success" else "error"}
            Action: open_private_app
            App: ${record.displayName}

            Launch Success: ${result.success}
            ${result.message}
        """.trimIndent()

        saveExecutionResultAndWait(
            rawCommand = request.rawCommand,
            status = if (result.success) "success" else "error",
            action = "open_private_app",
            summary = result.message,
            fullResult = fullResult,
            failed = !result.success
        )

        if (result.success) {
            finishAssistantAfterSuccessfulLaunch("open_private_app")
        }
    }

    fun rejectProtectedAction(
        rawCommand: String,
        fullResult: String,
        reason: String,
        bannerMessage: String,
        bannerType: String
    ) {
        saveLastResult(context, fullResult)
        saveFailedAction(
            context = context,
            rawCommand = rawCommand,
            action = rawCommand,
            reason = reason
        )
        resultText = fullResult
        showTopStatus(bannerMessage, bannerType)
        refreshLocalPanels()
    }


    val contactsPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        val permissionResult = if (granted) {
            "Contacts permission granted. Please run the command again."
        } else {
            "Contacts permission denied. Contact search, WhatsApp chat, and message confirmation will not work."
        }

        resultText = permissionResult
        showTopStatus(
            message = permissionResult,
            type = if (granted) "success" else "error"
        )
        saveLastResult(context, permissionResult)
        refreshLocalPanels()
    }
    suspend fun ensureContactsPermission(command: ZeaCommand): Boolean {
        if (hasContactsPermission(context)) {
            return true
        }

        contactsPermissionLauncher.launch(Manifest.permission.READ_CONTACTS)

        val commandDetails = buildList {
            add("Raw Command: ${command.rawCommand}")
            add("Status: permission_required")
            add("Action: ${command.action}")

            if (command.contactQuery.isNotBlank()) {
                add("Contact Query: ${command.contactQuery}")
            }

            if (command.messageText.isNotBlank()) {
                add("Message: ${command.messageText}")
            }
        }.joinToString("\n")

        val fullResult = """
            $commandDetails

            Contacts permission required. Permission popup sent.
        """.trimIndent()

        saveExecutionResultAndWait(
            rawCommand = command.rawCommand,
            status = "permission_required",
            action = command.action,
            summary = "Contacts permission required.",
            fullResult = fullResult,
            failed = true
        )

        return false
    }

    suspend fun saveMissingResolvedContact(command: ZeaCommand) {
        pendingWhatsAppMessage = null

        val fullResult = buildList {
            add("Raw Command: ${command.rawCommand}")
            add("Status: error")
            add("Action: ${command.action}")

            if (command.contactQuery.isNotBlank()) {
                add("Contact Query: ${command.contactQuery}")
            }

            if (command.messageText.isNotBlank()) {
                add("Message: ${command.messageText}")
            }

            add("")
            add("Command failed because selected contact was missing after resolver success.")
        }.joinToString("\n")

        saveExecutionResultAndWait(
            rawCommand = command.rawCommand,
            status = "error",
            action = command.action,
            summary = "Command failed. Contact selection was missing.",
            fullResult = fullResult,
            failed = true
        )
    }

    suspend fun saveNonReadyWhatsAppResolution(
        command: ZeaCommand,
        resolution: SmartWhatsAppCommandResolution,
        contactLookupCount: Int
    ): Boolean {
        if (resolution.status == SmartWhatsAppResolveStatus.READY_TO_PREFILL) {
            return false
        }

        pendingWhatsAppMessage = null

        val contactQuery = resolution.contactQuery.ifBlank { command.contactQuery }
        val messageText = resolution.messageText.ifBlank { command.messageText }
        val selectedContact = resolution.selectedContact

        val outcome = when (resolution.status) {
            SmartWhatsAppResolveStatus.NEEDS_CONTACT_SELECTION -> ZeaResolverOutcome(
                status = "needs_contact_selection",
                summary = "Multiple contacts found. Please select the correct contact or type the full saved contact name.",
                details = buildList {
                    add("Multiple matching contacts were found.")
                    add("Please select the correct contact or type the full saved contact name.")
                    add("")
                    add("Matched Contact Phrase:")
                    add(resolution.matchedContactPhrase)
                    add("")
                    add("Matching Contacts:")
                    add(contactsToText(resolution.matchingContacts))
                },
                failed = true
            )

            SmartWhatsAppResolveStatus.CONTACT_NOT_FOUND -> ZeaResolverOutcome(
                status = "error",
                summary = "Contact not found.",
                details = listOf(
                    "Command failed because no saved contact matched the command.",
                    "",
                    "Resolver Reason:",
                    resolution.reason,
                    "",
                    "Contact lookup count:",
                    contactLookupCount.toString()
                ),
                failed = true
            )

            SmartWhatsAppResolveStatus.MESSAGE_NOT_FOUND -> ZeaResolverOutcome(
                status = "error",
                summary = "Message text is missing.",
                details = listOf(
                    "Contact was found, but message text was missing.",
                    "",
                    "Selected Contact:",
                    selectedContact?.name ?: "Unknown",
                    selectedContact?.phoneNumber.orEmpty(),
                    "",
                    "Resolver Reason:",
                    resolution.reason
                ),
                failed = true
            )

            SmartWhatsAppResolveStatus.LOW_CONFIDENCE -> ZeaResolverOutcome(
                status = "low_confidence",
                summary = "Command was not clear enough.",
                details = listOf(
                    // TEMPORARY BRAND NAME: "Zyro" is not the permanent app name and can be replaced with the original/permanent name "Zea Assistant" at any time.
                    "Zyro could not understand the command confidently.",
                    "",
                    "Resolver Reason:",
                    resolution.reason
                ),
                failed = false
            )

            SmartWhatsAppResolveStatus.NOT_WHATSAPP_MESSAGE -> ZeaResolverOutcome(
                status = "error",
                summary = "WhatsApp message command not recognized.",
                details = listOf(
                    "This does not look like a WhatsApp message command.",
                    "",
                    "Resolver Reason:",
                    resolution.reason
                ),
                failed = true
            )

            SmartWhatsAppResolveStatus.READY_TO_PREFILL -> return false
        }

        val fullResult = buildList {
            add("Raw Command: ${command.rawCommand}")
            add("Status: ${outcome.status}")
            add("Action: ${command.action}")

            if (contactQuery.isNotBlank()) {
                add("Contact Query: $contactQuery")
            }

            if (messageText.isNotBlank()) {
                add("Message: $messageText")
            }

            add("")
            addAll(outcome.details)
        }.joinToString("\n")

        saveExecutionResultAndWait(
            rawCommand = command.rawCommand,
            status = outcome.status,
            action = command.action,
            summary = outcome.summary,
            fullResult = fullResult,
            failed = outcome.failed
        )

        return true
    }

    suspend fun selectedResolvedContact(
        command: ZeaCommand,
        resolution: SmartWhatsAppCommandResolution,
        contactLookupCount: Int
    ): ContactResult? {
        if (saveNonReadyWhatsAppResolution(
                command = command,
                resolution = resolution,
                contactLookupCount = contactLookupCount
            )
        ) {
            return null
        }

        return resolution.selectedContact ?: run {
            saveMissingResolvedContact(command)
            null
        }
    }

    fun appendWhatsAppLaunchOutcome(
        lines: MutableList<String>,
        launchResult: LaunchResult,
        includePrefillNote: Boolean
    ) {
        lines.add("")
        lines.add("Launch Success: ${launchResult.success}")
        lines.add(launchResult.message)

        if (includePrefillNote) {
            lines.add("")
            lines.add("Note: WhatsApp message box should be pre-filled. You still press Send manually.")
        }
    }

    suspend fun saveResolvedWhatsAppLaunch(
        command: ZeaCommand,
        resolution: SmartWhatsAppCommandResolution,
        contact: ContactResult,
        launchResult: LaunchResult,
        includePrefillNote: Boolean
    ) {
        val fullResult = buildList {
            add("Raw Command: ${command.rawCommand}")
            add("Status: ${if (launchResult.success) command.status else "error"}")
            add("Action: ${command.action}")
            add("Contact Query: ${resolution.contactQuery.ifBlank { command.contactQuery }}")
            add("")
            add("Selected Contact:")
            add(contact.name)
            add(contact.phoneNumber)
            add("")
            add("Matched Contact Phrase:")
            add(resolution.matchedContactPhrase)

            if (resolution.messageText.isNotBlank()) {
                add("")
                add("Message:")
                add(resolution.messageText)
            }

            add("")
            add("Confidence:")
            add(resolution.confidence.toString())
            add("")
            add("Resolver Reason:")
            add(resolution.reason)
            appendWhatsAppLaunchOutcome(this, launchResult, includePrefillNote)
        }.joinToString("\n")

        saveExecutionResultAndWait(
            rawCommand = command.rawCommand,
            status = if (launchResult.success) command.status else "error",
            action = command.action,
            summary = launchResult.message,
            fullResult = fullResult,
            failed = !launchResult.success
        )

        if (launchResult.success) {
            finishAssistantAfterSuccessfulLaunch(command.action)
        }
    }

    fun saveSelectedContactLaunch(
        data: ZeaResultViewData,
        contact: ZeaDisplayContact,
        action: String,
        launchResult: LaunchResult,
        messageText: String = "",
        includePrefillNote: Boolean = false
    ) {
        val fullResult = buildList {
            add("Raw Command: ${data.rawCommand}")
            add("Status: ${if (launchResult.success) "success" else "error"}")
            add("Action: $action")
            add("Contact Query: ${data.contactQuery}")
            add("")
            add("Selected Contact:")
            add(contact.name)
            add(contact.phoneNumber)

            if (messageText.isNotBlank()) {
                add("")
                add("Message:")
                add(messageText)
            }

            add("")
            add("Matched Contact Phrase:")
            add(data.matchedContactPhrase.ifBlank { data.contactQuery })
            add("")
            add("Confidence:")
            add("SELECTED")
            add("")
            add("Resolver Reason:")
            add("User selected a matching contact from the result list.")
            appendWhatsAppLaunchOutcome(this, launchResult, includePrefillNote)
        }.joinToString("\n")

        saveExecutionResult(
            rawCommand = data.rawCommand.ifBlank { "contact_selection" },
            status = if (launchResult.success) "success" else "error",
            action = action,
            summary = launchResult.message,
            fullResult = fullResult,
            failed = !launchResult.success,
            finishAssistantTaskAfterSave = launchResult.success
        )
    }


    suspend fun executeRunCommand(
        currentInput: String,
        strategySnapshot: CommandStrategy,
        operationStartedElapsedRealtime: Long
    ) {
        if (isConfirmCommand(currentInput)) {
            val pending = pendingWhatsAppMessage

            if (pending == null) {
                val fullResult = """
                    Status: error
                    Action: confirm

                    No pending message found.
                    Create a message command first.
                    Example: Tell John: I will meet you tomorrow
                """.trimIndent()

                saveExecutionResultAndWait(
                    rawCommand = currentInput,
                    status = "error",
                    action = "confirm",
                    summary = "No pending message found.",
                    fullResult = fullResult,
                    failed = true
                )
            } else {
                val launchResult = runIoPhase("WhatsApp prefill launch") {
                    openWhatsAppMessagePrefill(
                        context,
                        pending.phoneNumber,
                        pending.messageText
                    )
                }

                val fullResult = """
                    Raw Command: ${pending.originalCommand}
                    Status: confirmed
                    Action: prefill_whatsapp_message
                    Contact Query: ${pending.contactQuery}
                    Message: ${pending.messageText}

                    Selected Contact:
                    ${pending.contactName}
                    ${pending.phoneNumber}

                    Launch Success: ${launchResult.success}
                    ${launchResult.message}

                    Note: WhatsApp message box should be pre-filled. You still press Send manually.
                """.trimIndent()

                saveExecutionResultAndWait(
                    rawCommand = currentInput,
                    status = if (launchResult.success) "confirmed" else "error",
                    action = "prefill_whatsapp_message",
                    summary = launchResult.message,
                    fullResult = fullResult,
                    failed = !launchResult.success
                )

                pendingWhatsAppMessage = null

                if (launchResult.success) {
                    finishAssistantAfterSuccessfulLaunch("prefill_whatsapp_message")
                }
            }
        } else if (isCancelCommand(currentInput)) {
            val pending = pendingWhatsAppMessage

            val fullResult = if (pending == null) {
                """
                    Status: cancelled
                    Action: cancel

                    No pending message found.
                """.trimIndent()
            } else {
                pendingWhatsAppMessage = null

                """
                    Status: cancelled
                    Action: cancel

                    Pending message cancelled.

                    Contact:
                    ${pending.contactName}

                    Message:
                    ${pending.messageText}
                """.trimIndent()
            }

            saveExecutionResultAndWait(
                rawCommand = currentInput,
                status = "cancelled",
                action = "cancel",
                summary = "Pending message cancelled or no pending message found.",
                fullResult = fullResult,
                failed = false
            )
        } else {
            val privateAppRequest = withContext(Dispatchers.IO) {
                resolvePrivateAppOpenCommand(context, currentInput)
            }

            if (privateAppRequest != null) {
                executePrivateAppCommand(
                    request = privateAppRequest,
                    operationStartedElapsedRealtime = operationStartedElapsedRealtime
                )
                return
            }

            val fastPathTarget = withContext(Dispatchers.IO) {
                resolveSimpleAppOpenCommand(context, currentInput)
            }

            if (fastPathTarget != null) {
                executeAppLaunch(
                    parsedCommand = fastPathTarget.command,
                    resolvedEntry = fastPathTarget.entry,
                    operationStartedElapsedRealtime = operationStartedElapsedRealtime
                )
                return
            }

            val parsedCommand = runIoPhase("command parsing fallback") {
                parseZeaCommand(context, currentInput, strategySnapshot)
            }

            if (parsedCommand.status == "success" && parsedCommand.action == "open_app") {
                executeAppLaunch(
                    parsedCommand = parsedCommand,
                    operationStartedElapsedRealtime = operationStartedElapsedRealtime
                )
            } else if (parsedCommand.status == "success" && parsedCommand.action == "search_contact") {
                if (ensureContactsPermission(parsedCommand)) {
                    val contacts = runIoPhase("contact search") {
                        searchContacts(context, parsedCommand.contactQuery)
                    }

                    val fullResult = """
                        Raw Command: ${parsedCommand.rawCommand}
                        Status: ${if (contacts.isEmpty()) "error" else parsedCommand.status}
                        Action: ${parsedCommand.action}
                        Contact Query: ${parsedCommand.contactQuery}

                        Results:
                        ${contactsToText(contacts)}
                    """.trimIndent()

                    saveExecutionResultAndWait(
                        rawCommand = parsedCommand.rawCommand,
                        status = if (contacts.isEmpty()) "error" else parsedCommand.status,
                        action = parsedCommand.action,
                        summary = if (contacts.isEmpty()) "Contact not found." else "Contact search completed.",
                        fullResult = fullResult,
                        failed = contacts.isEmpty()
                    )
                }
            } else if (parsedCommand.status == "success" && parsedCommand.action == "show_contacts_audit") {
                if (ensureContactsPermission(parsedCommand)) {
                    val contacts = runIoPhase("contacts audit loading") {
                        loadAllContactsForAudit(
                            context = context,
                            maxResults = 500
                        )
                    }

                    val auditText = contactsAuditToText(contacts)

                    val fullResult = """
                        Raw Command: ${parsedCommand.rawCommand}
                        Status: ${if (contacts.isEmpty()) "error" else "success"}
                        Action: ${parsedCommand.action}

                        $auditText
                    """.trimIndent()

                    saveExecutionResultAndWait(
                        rawCommand = parsedCommand.rawCommand,
                        status = if (contacts.isEmpty()) "error" else "success",
                        action = parsedCommand.action,
                        summary = auditText,
                        fullResult = fullResult,
                        failed = contacts.isEmpty()
                    )
                }
            } else if (parsedCommand.status == "success" && parsedCommand.action == "open_whatsapp_chat") {
                if (ensureContactsPermission(parsedCommand)) {
                    val contacts = runIoPhase("WhatsApp contact loading") {
                        loadResolverContactsForCommand(
                            context = context,
                            parsedContactQuery = parsedCommand.contactQuery
                        )
                    }

                    val resolution = runIoPhase("WhatsApp contact resolution") {
                        resolveSmartWhatsAppChatCommand(
                            parsedCommand.rawCommand,
                            contacts
                        )
                    }

                    selectedResolvedContact(
                        parsedCommand,
                        resolution,
                        contacts.size
                    )?.let { selectedContact ->
                        val launchResult = runIoPhase("WhatsApp chat launch") {
                            openWhatsAppChat(
                                context = context,
                                phoneNumber = selectedContact.phoneNumber,
                                whatsAppDataId = selectedContact.whatsAppDataId
                            )
                        }

                        saveResolvedWhatsAppLaunch(
                            command = parsedCommand,
                            resolution = resolution,
                            contact = selectedContact,
                            launchResult = launchResult,
                            includePrefillNote = false
                        )
                    }
                }
            } else if (parsedCommand.status == "success" && parsedCommand.action == "prefill_whatsapp_message") {
                if (ensureContactsPermission(parsedCommand)) {
                    val contacts = runIoPhase("WhatsApp contact loading") {
                        loadResolverContactsForCommand(
                            context = context,
                            parsedContactQuery = parsedCommand.contactQuery
                        )
                    }

                    val resolution = runIoPhase("WhatsApp message resolution") {
                        resolveSmartWhatsAppPrefillCommand(
                            rawCommand = parsedCommand.rawCommand,
                            parsedContactQuery = parsedCommand.contactQuery,
                            parsedMessageText = parsedCommand.messageText,
                            contacts = contacts
                        )
                    }

                    selectedResolvedContact(
                        parsedCommand,
                        resolution,
                        contacts.size
                    )?.let { selectedContact ->
                        pendingWhatsAppMessage = null

                        val launchResult = runIoPhase("WhatsApp prefill launch") {
                            openWhatsAppMessagePrefill(
                                context = context,
                                phoneNumber = selectedContact.phoneNumber,
                                messageText = resolution.messageText
                            )
                        }

                        saveResolvedWhatsAppLaunch(
                            command = parsedCommand,
                            resolution = resolution,
                            contact = selectedContact,
                            launchResult = launchResult,
                            includePrefillNote = true
                        )
                    }
                }
            } else {
                val fullResult = """
                    Raw Command: ${parsedCommand.rawCommand}
                    Status: ${parsedCommand.status}
                    Action: ${parsedCommand.action}
                    App: ${parsedCommand.appDisplayName}

                    ${parsedCommand.message}
                """.trimIndent()

                saveExecutionResultAndWait(
                    rawCommand = parsedCommand.rawCommand,
                    status = parsedCommand.status,
                    action = parsedCommand.action,
                    summary = parsedCommand.message,
                    fullResult = fullResult,
                    failed = parsedCommand.status != "success"
                )
            }
        }
    }


    LaunchedEffect(Unit) {
        val storedPrivateApps = withContext(Dispatchers.IO) {
            ZeaPrivateAppLookupCache.warm(context)
            ZeaAppLookupCache.warm(context)
            ZeaPrivateAppLookupCache.apps(context)
        }
        privateApps = storedPrivateApps
        privateAppsStatus = if (storedPrivateApps.isEmpty()) {
            "No private apps have been added."
        } else {
            "Private Apps are available in this authenticated Assistant session."
        }
        interruptedBatch = withContext(Dispatchers.IO) {
            ZeaBatchJournal.readActive(context)
        }
    }

    LaunchedEffect(appsRouteName) {
        if (appsRouteName.isBlank()) {
            interruptedBatch = withContext(Dispatchers.IO) {
                ZeaBatchJournal.readActive(context)
            }
        }
    }

    LaunchedEffect(topStatusId) {
        if (topStatusMessage.isNotBlank()) {
            delay(9000)
            topStatusMessage = ""
        }
    }

    MaterialTheme {
        val mainSurfaceModifier = Modifier.fillMaxSize()

        val appsRoute = zeaAppsRouteOf(appsRouteName)
        if (appsRoute != null) {
            ZeaAppsNavigationHost(
                route = appsRoute,
                onNavigate = { target -> appsRouteName = target.name },
                onBack = {
                    appsRouteName = zeaAppsParentOf(appsRoute)?.name.orEmpty()
                }
            )
            return@MaterialTheme
        }

        if (showSettingsScreen) {
            // Screenshot-protected Settings (FLAG_SECURE inside).
            ZeaSettingsScreen(onBack = { showSettingsScreen = false })
            return@MaterialTheme
        }

        if (showDiagnostics) {
            // Screenshot-protected diagnostics + emergency recovery.
            ZeaDiagnosticsScreen(onBack = { showDiagnostics = false })
            return@MaterialTheme
        }

        if (showAboutScreen) {
            // Screenshot-protected About screen (FLAG_SECURE inside).
            ZeaAboutScreen(onBack = { showAboutScreen = false })
            return@MaterialTheme
        }

        if (showDeveloperAccess) {
            // Screenshot-protected developer key gate (FLAG_SECURE inside).
            ZeaDeveloperAccessScreen(
                onBack = { showDeveloperAccess = false },
                onGranted = {
                    showDeveloperAccess = false
                    showDeveloperControls = true
                }
            )
            return@MaterialTheme
        }

        if (showDeveloperControls) {
            // Screenshot-protected developer-only placeholder (FLAG_SECURE inside).
            ZeaDeveloperControlsScreen(onBack = { showDeveloperControls = false })
            return@MaterialTheme
        }

        Surface(
            modifier = mainSurfaceModifier,
            color = MaterialTheme.colorScheme.background
        ) {
            val activeInterruptedBatch = interruptedBatch
            if (activeInterruptedBatch != null && !interruptedBatchBusy) {
                ZeaInterruptedBatchDialog(
                    context = context,
                    record = activeInterruptedBatch,
                    reviewMode = interruptedBatchReview,
                    onToggleReview = { interruptedBatchReview = !interruptedBatchReview },
                    onResume = {
                        interruptedBatchBusy = true
                        coroutineScope.launch {
                            try {
                                resumeInterruptedBatch(
                                    context = context,
                                    record = activeInterruptedBatch
                                )
                            } finally {
                                interruptedBatch = withContext(Dispatchers.IO) {
                                    ZeaBatchJournal.readActive(context)
                                }
                                interruptedBatchReview = false
                                interruptedBatchBusy = false
                                refreshHomeStats()
                                homeStatsShowToken++
                                homeStatsVisible = true
                            }
                        }
                    },
                    onAbandon = {
                        interruptedBatchBusy = true
                        coroutineScope.launch {
                            try {
                                withContext(Dispatchers.IO) {
                                    // Same recovery invariant as Resume: verified
                                    // target truth must converge bookkeeping before
                                    // the record is resolved.
                                    reconcileInterruptedBatchBookkeeping(
                                        context,
                                        activeInterruptedBatch
                                    )
                                    ZeaDeviceOwnerController.reconcileHiddenState(
                                        context,
                                        "batch_journal_abandoned"
                                    )
                                    ZeaBatchJournal.abandon(context, activeInterruptedBatch.batchId)
                                }
                            } finally {
                                interruptedBatch = withContext(Dispatchers.IO) {
                                    ZeaBatchJournal.readActive(context)
                                }
                                interruptedBatchReview = false
                                interruptedBatchBusy = false
                                refreshHomeStats()
                                homeStatsShowToken++
                                homeStatsVisible = true
                            }
                        }
                    },
                    onDismissResolved = {
                        // An unresolved durable journal is a hard gate. Do not
                        // hide it from the current session merely because the
                        // dialog received a back/outside-dismiss request.
                        interruptedBatchReview = false
                    }
                )
            }

            ZeaPullToRefreshLayout(
                isRefreshing = isRefreshingHome,
                onRefresh = {
                    if (!isRefreshingHome) {
                        isRefreshingHome = true
                        coroutineScope.launch {
                        try {
                            val stabilityRefresh = ZeaPhase1Stability.refresh(
                                context,
                                "home_pull_to_refresh"
                            )
                            if (!stabilityRefresh.success && !stabilityRefresh.duplicateSkipped) {
                                Log.w(ZEA_LAUNCH_LOG_TAG, stabilityRefresh.message)
                            }
                            withContext(Dispatchers.IO) {
                                ZeaPrivateAppLookupCache.warm(context)
                                ZeaAppLookupCache.warm(context)
                            }
                            privateApps = ZeaPrivateAppLookupCache.apps(context)
                            privateAppsStatus = if (privateApps.isEmpty()) {
                                "No private apps have been added."
                            } else {
                                "Private Apps are available in this authenticated Assistant session."
                            }
                            refreshHomeStats()
                            homeStatsShowToken++
                            homeStatsVisible = true
                        } catch (error: CancellationException) {
                            throw error
                        } catch (error: Exception) {
                            Log.w(ZEA_LAUNCH_LOG_TAG, "home pull-to-refresh failed safely", error)
                        } finally {
                            isRefreshingHome = false
                        }
                        }
                    }
                },
                modifier = Modifier.fillMaxSize()
            ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .statusBarsPadding()
                    .padding(
                        start = 20.dp,
                        end = 20.dp,
                        top = 16.dp,
                        bottom = 20.dp
                    )
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.Top
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        // TEMPORARY BRAND NAME: "Zyro" is not the permanent app name and can be replaced with the original/permanent name "Zea Assistant" at any time.
                        text = "Zyro v1.0",
                        style = MaterialTheme.typography.headlineMedium,
                        modifier = Modifier.weight(1f)
                    )

                    Box {
                        IconButton(onClick = { homeMenuExpanded = true }) {
                            Icon(
                                imageVector = ZeaIcons.Overflow,
                                contentDescription = "More options"
                            )
                        }

                        DropdownMenu(
                            expanded = homeMenuExpanded,
                            onDismissRequest = { homeMenuExpanded = false }
                        ) {
                            DropdownMenuItem(
                                text = { Text("Apps") },
                                leadingIcon = {
                                    Icon(
                                        imageVector = ZeaIcons.AppsGrid,
                                        contentDescription = null
                                    )
                                },
                                onClick = {
                                    homeMenuExpanded = false
                                    appsRouteName = ZeaAppsRoute.HUB.name
                                }
                            )

                            DropdownMenuItem(
                                text = { Text("Settings") },
                                leadingIcon = {
                                    Icon(
                                        imageVector = ZeaIcons.Settings,
                                        contentDescription = null
                                    )
                                },
                                onClick = {
                                    homeMenuExpanded = false
                                    showSettingsScreen = true
                                }
                            )
                            DropdownMenuItem(
                                text = { Text("Diagnostics") },
                                leadingIcon = {
                                    Icon(
                                        imageVector = ZeaIcons.Diagnostics,
                                        contentDescription = null
                                    )
                                },
                                onClick = {
                                    homeMenuExpanded = false
                                    showDiagnostics = true
                                }
                            )
                            ZeaHomeMenuPlaceholder(
                                label = "Help & Support",
                                icon = ZeaIcons.Help
                            )
                            DropdownMenuItem(
                                text = { Text("About Zyro") },
                                leadingIcon = {
                                    Icon(
                                        imageVector = ZeaIcons.About,
                                        contentDescription = null
                                    )
                                },
                                onClick = {
                                    homeMenuExpanded = false
                                    showAboutScreen = true
                                }
                            )

                            if (zeaDeveloperControlsEnabled) {
                                DropdownMenuItem(
                                    text = { Text("Developer Controls") },
                                    leadingIcon = {
                                        Icon(
                                            imageVector = ZeaIcons.Developer,
                                            contentDescription = null
                                        )
                                    },
                                    onClick = {
                                        homeMenuExpanded = false
                                        showDeveloperAccess = true
                                    }
                                )
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(10.dp))

                Text(
                    text = "First Stable Personal Release",
                    style = MaterialTheme.typography.bodyMedium
                )

                Spacer(modifier = Modifier.height(16.dp))

                AnimatedVisibility(
                    visible = homeStatsVisible,
                    enter = fadeIn(animationSpec = tween(250)),
                    exit = fadeOut(animationSpec = tween(400))
                ) {
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(16.dp),
                        color = Color(0xFFF3F7FC),
                        border = BorderStroke(1.dp, Color(0xFFD7E3F4))
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            ZeaHomeStatItem(label = "Apps", value = "$homeTotalApps")
                            ZeaHomeStatItem(label = "Hidden", value = "$homeHiddenCount")
                            ZeaHomeStatItem(label = "Timed", value = "$homeTimedCount")
                            ZeaHomeStatItem(
                                label = "Owner",
                                value = if (homeOwnerActive) "On" else "Off"
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(10.dp))

                ZeaProtectionHealthCard(
                    onOpenDiagnostics = { showDiagnostics = true }
                )

                Spacer(modifier = Modifier.height(16.dp))

                if (topStatusMessage.isNotBlank()) {
                    val bannerBackground = when (topStatusType) {
                        "success" -> Color(0xFFE8F5E9)
                        "warning" -> Color(0xFFFFF8E1)
                        "error" -> Color(0xFFFFEBEE)
                        else -> Color(0xFFE3F2FD)
                    }

                    val bannerTextColor = when (topStatusType) {
                        "success" -> Color(0xFF1B5E20)
                        "warning" -> Color(0xFF7A4F01)
                        "error" -> Color(0xFFB71C1C)
                        else -> Color(0xFF0D47A1)
                    }

                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(16.dp),
                        color = bannerBackground
                    ) {
                        Text(
                            text = topStatusMessage,
                            color = bannerTextColor,
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.padding(12.dp)
                        )
                    }

                    Spacer(modifier = Modifier.height(16.dp))
                } else {
                    Spacer(modifier = Modifier.height(6.dp))
                }

                Text(
                    // TEMPORARY BRAND NAME: "Zyro" is not the permanent app name and can be replaced with the original/permanent name "Zea Assistant" at any time.
                    text = "Type a command and Zyro will choose the right action.",
                    style = MaterialTheme.typography.bodySmall
                )

                Spacer(modifier = Modifier.height(16.dp))

                Spacer(modifier = Modifier.height(18.dp))

                OutlinedTextField(
                    value = commandText,
                    onValueChange = {
                        commandText = it
                        commandInlineErrorText = ""
                    },
                    label = { Text("Command") },
                    placeholder = {
                        Text("Example: Open an allowed app or Tell John: I will meet you tomorrow")
                    },
                    modifier = Modifier.fillMaxWidth()
                )

                if (commandInlineErrorText.isNotBlank()) {
                    Spacer(modifier = Modifier.height(8.dp))

                    Text(
                        text = commandInlineErrorText,
                        color = Color(0xFFD32F2F),
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.fillMaxWidth()
                    )
                }

                Spacer(modifier = Modifier.height(16.dp))


                Button(
                    onClick = {
                        if (!runCommandGate.compareAndSet(false, true)) {
                            Log.i(ZEA_LAUNCH_LOG_TAG, "click received; duplicate gate rejected")
                            return@Button
                        }

                        val operationStarted = SystemClock.elapsedRealtime()
                        Log.i(ZEA_LAUNCH_LOG_TAG, "click received; duplicate gate acquired")

                        isAppLaunchInProgress = true
                        commandInlineErrorText = ""

                        val currentInput = commandText.trim()
                        val strategySnapshot = selectedCommandStrategy

                        coroutineScope.launch {
                            try {
                                executeRunCommand(
                                    currentInput = currentInput,
                                    strategySnapshot = strategySnapshot,
                                    operationStartedElapsedRealtime = operationStarted
                                )
                            } catch (error: CancellationException) {
                                Log.w(ZEA_LAUNCH_LOG_TAG, "command operation cancelled", error)
                                throw error
                            } catch (error: Exception) {
                                Log.e(ZEA_LAUNCH_LOG_TAG, "command operation failed safely", error)
                                // TEMPORARY BRAND NAME: "Zyro" is not the permanent app name and can be replaced with the original/permanent name "Zea Assistant" at any time.
                                val safeResult = "Command failed safely. Zyro remains available."
                                try {
                                    saveExecutionResultAndWait(
                                        rawCommand = currentInput,
                                        status = "error",
                                        action = "run_command",
                                        summary = safeResult,
                                        fullResult = safeResult,
                                        failed = true
                                    )
                                } catch (persistenceError: Exception) {
                                    Log.e(
                                        ZEA_LAUNCH_LOG_TAG,
                                        "safe failure persistence also failed",
                                        persistenceError
                                    )
                                    resultText = safeResult
                                    commandInlineErrorText = "Command failed: $safeResult"
                                }
                            } finally {
                                runCommandGate.set(false)
                                isAppLaunchInProgress = false
                                Log.i(
                                    ZEA_LAUNCH_LOG_TAG,
                                    "total operation end elapsedMs=${SystemClock.elapsedRealtime() - operationStarted}"
                                )
                            }
                        }
                    },
                    enabled = !isAppLaunchInProgress,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(if (isAppLaunchInProgress) "Opening..." else "Run Command")
                }

                Spacer(modifier = Modifier.height(24.dp))

                ZeaResultPanel(
                    resultText = resultText,
                    onMatchingContactClick = { contact, data ->
                        when (data.action) {
                            "open_whatsapp_chat" -> {
                                val launchResult = openWhatsAppChat(
                                    context = context,
                                    phoneNumber = contact.phoneNumber,
                                    whatsAppDataId = ""
                                )

                                saveSelectedContactLaunch(
                                    data = data,
                                    contact = contact,
                                    action = "open_whatsapp_chat",
                                    launchResult = launchResult
                                )
                            }

                            "prefill_whatsapp_message" -> {
                                if (data.messageText.isBlank()) {
                                    val fullResult = """
                                        Raw Command: ${data.rawCommand}
                                        Status: error
                                        Action: prefill_whatsapp_message
                                        Contact Query: ${data.contactQuery}

                                        Message text was missing after contact selection.
                                    """.trimIndent()

                                    saveExecutionResult(
                                        rawCommand = data.rawCommand.ifBlank { "contact_selection" },
                                        status = "error",
                                        action = "prefill_whatsapp_message",
                                        summary = "Message text was missing after contact selection.",
                                        fullResult = fullResult,
                                        failed = true
                                    )
                                } else {
                                    val launchResult = openWhatsAppMessagePrefill(
                                        context = context,
                                        phoneNumber = contact.phoneNumber,
                                        messageText = data.messageText
                                    )

                                    saveSelectedContactLaunch(
                                        data = data,
                                        contact = contact,
                                        action = "prefill_whatsapp_message",
                                        launchResult = launchResult,
                                        messageText = data.messageText,
                                        includePrefillNote = true
                                    )
                                }
                            }
                        }
                    }
                )
            }
            }
        }
    }
}

@Composable
private fun ZeaHomeStatItem(
    label: String,
    value: String
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = value,
            style = MaterialTheme.typography.titleMedium.copy(
                fontWeight = FontWeight.Bold
            ),
            color = Color(0xFF1A3C6E)
        )

        Spacer(modifier = Modifier.height(2.dp))

        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = Color(0xFF5F6368)
        )
    }
}

private suspend fun resumeInterruptedBatch(
    context: Context,
    record: ZeaBatchJournalRecord
) {
    withContext(Dispatchers.IO) {
        var active = ZeaBatchJournal.readActive(context) ?: return@withContext
        if (active.batchId != record.batchId) {
            Log.w(
                ZEA_LAUNCH_LOG_TAG,
                "interrupted batch changed before resume requested=${record.batchId} active=${active.batchId}"
            )
            return@withContext
        }

        // First reconcile target truth with bookkeeping. Any target whose full
        // operation state already landed becomes an idempotent NoOp and is
        // durably marked processed in the SAME journal before we continue.
        val verifiedNoOp = reconcileInterruptedBatchBookkeeping(context, active)
        for (packageName in verifiedNoOp) {
            if (!ZeaBatchJournal.markProcessed(context, active.batchId, packageName)) {
                Log.e(
                    ZEA_LAUNCH_LOG_TAG,
                    "interrupted batch resume stopped: could not persist reconciled progress package=$packageName"
                )
                return@withContext
            }
        }

        active = ZeaBatchJournal.readActive(context) ?: return@withContext
        if (ZeaBatchJournal.allTargetsProcessed(active)) {
            ZeaBatchJournal.complete(context, active.batchId)
            return@withContext
        }

        // v1 timed journals did not persist their duration/end timestamp. They
        // must NEVER be resumed as permanent hides. Preserve any target that
        // has a real timed record; safely release unresolved targets, then
        // archive the legacy journal as abandoned.
        if (active.isTimedHide && active.timedRequestOrNull() == null) {
            val resolved = resolveLegacyTimedBatchWithoutMetadata(context, active)
            if (resolved) {
                ZeaBatchJournal.abandon(context, active.batchId)
            }
            return@withContext
        }

        // A timed batch whose original end has already passed must converge to
        // visible, not re-hide for a newly invented duration. Reconciliation
        // above handles fully/partially protected targets; finish any visible
        // bookkeeping gaps here and close only if all targets are durable.
        val timedRequest = active.timedRequestOrNull()
        if (active.isTimedHide && timedRequest != null && timedRequest.endEpochMillis <= System.currentTimeMillis()) {
            val remaining = active.targets.filterNot { target ->
                active.processed.any { it.equals(target, ignoreCase = true) }
            }
            for (target in remaining) {
                if (!convergeInterruptedTargetToVisible(context, target)) {
                    Log.e(ZEA_LAUNCH_LOG_TAG, "expired timed batch could not release package=$target")
                    return@withContext
                }
                if (!ZeaBatchJournal.markProcessed(context, active.batchId, target)) {
                    return@withContext
                }
            }
            val finalRecord = ZeaBatchJournal.readActive(context)
            if (finalRecord?.batchId == active.batchId && ZeaBatchJournal.allTargetsProcessed(finalRecord)) {
                ZeaBatchJournal.complete(context, active.batchId)
            }
            return@withContext
        }

        // Refresh once before catalog lookup so uninstall/stale-registry cleanup
        // and timer expiry cannot make the resume target list stale. Recovery
        // is fail-closed: if reconciliation is already running or ends with an
        // unresolved invariant, the user can retry Resume after it settles.
        val refreshResult = ZeaPhase1Stability.refresh(context, "interrupted_batch_resume")
        if (!refreshResult.success || refreshResult.duplicateSkipped) {
            Log.w(
                ZEA_LAUNCH_LOG_TAG,
                "interrupted batch resume deferred: ${refreshResult.message}"
            )
            return@withContext
        }
        active = ZeaBatchJournal.readActive(context) ?: return@withContext

        val remainingPackages = active.targets.filterNot { target ->
            active.processed.any { it.equals(target, ignoreCase = true) }
        }
        if (remainingPackages.isEmpty()) {
            ZeaBatchJournal.complete(context, active.batchId)
            return@withContext
        }

        val catalog = ZeaAppCatalog.loadManagedApps(context)
        val resumeTargets = mutableListOf<ZeaManagedApp>()
        for (target in remainingPackages) {
            val app = catalog.firstOrNull { candidate ->
                candidate.packageName.equals(target, ignoreCase = true)
            }
            if (app != null) {
                resumeTargets.add(app)
                continue
            }

            if (!ZeaDeviceOwnerController.isPackageInstalled(context, target)) {
                // The target ceased to exist. Stale durable state is pruned by
                // the stability refresh, so the batch can record this target as
                // resolved without attempting a policy mutation on a ghost app.
                if (!ZeaBatchJournal.markProcessed(context, active.batchId, target)) {
                    return@withContext
                }
            } else {
                Log.e(
                    ZEA_LAUNCH_LOG_TAG,
                    "interrupted batch target is installed but absent from managed catalog package=$target"
                )
                return@withContext
            }
        }

        active = ZeaBatchJournal.readActive(context) ?: return@withContext
        if (ZeaBatchJournal.allTargetsProcessed(active)) {
            ZeaBatchJournal.complete(context, active.batchId)
            return@withContext
        }
        if (resumeTargets.isEmpty()) {
            return@withContext
        }

        when (active.operation) {
            ZeaBatchJournal.OPERATION_UNHIDE -> {
                runBulkUnhide(
                    context = context,
                    targets = resumeTargets,
                    existingJournal = active
                ) { _, _ -> }
            }

            ZeaBatchJournal.OPERATION_HIDE -> {
                runBulkHide(
                    context = context,
                    apps = resumeTargets,
                    request = null,
                    existingJournal = active
                ) { _, _ -> }
            }

            ZeaBatchJournal.OPERATION_TIMED_HIDE -> {
                val request = active.timedRequestOrNull() ?: return@withContext
                runBulkHide(
                    context = context,
                    apps = resumeTargets,
                    request = request,
                    existingJournal = active
                ) { _, _ -> }
            }
        }
    }
}

/**
 * Safe migration for historical timed journals whose v1 schema did not store
 * the requested duration. A target with a real timed row is preserved as-is;
 * an unresolved target is converged to visible. We never invent a duration or
 * silently convert a timed batch into permanent hide.
 */
private suspend fun resolveLegacyTimedBatchWithoutMetadata(
    context: Context,
    record: ZeaBatchJournalRecord
): Boolean {
    val timers = loadTimedHides(context).associateBy { it.packageName.lowercase() }
    val processedKeys = record.processed.mapTo(mutableSetOf()) { it.lowercase() }

    record.targets.forEach { target ->
        if (target.lowercase() in processedKeys) return@forEach

        val timer = timers[target.lowercase()]
        val hidden = ZeaDeviceOwnerController.isHidden(context, target)
        val blocked = ZeaDeviceOwnerController.isUninstallBlocked(context, target)
        val preservedTimedState =
            timer != null &&
                timer.hiddenUntilEpochMillis > System.currentTimeMillis() &&
                hidden == true &&
                blocked == true &&
                ZeaAppHideService.syncBookkeepingToVerifiedHiddenState(context, target)

        val resolved = preservedTimedState || convergeInterruptedTargetToVisible(context, target)
        if (!resolved) {
            return false
        }
        if (!ZeaBatchJournal.markProcessed(context, record.batchId, target)) {
            return false
        }
    }
    return true
}

private fun convergeInterruptedTargetToVisible(
    context: Context,
    packageName: String
): Boolean {
    val hidden = ZeaDeviceOwnerController.isHidden(context, packageName)
    val blocked = ZeaDeviceOwnerController.isUninstallBlocked(context, packageName)
    if (hidden == null || blocked == null) return false

    val policyClear = if (!hidden && !blocked) {
        true
    } else {
        ZeaAppHideService.repairPartialVisibleState(context, packageName)
    }
    if (!policyClear) return false

    val verifyHidden = ZeaDeviceOwnerController.isHidden(context, packageName)
    val verifyBlocked = ZeaDeviceOwnerController.isUninstallBlocked(context, packageName)
    if (verifyHidden != false || verifyBlocked != false) return false

    return ZeaAppHideService.syncBookkeepingToVerifiedVisibleState(context, packageName)
}

/**
 * Step-3 recovery invariant: a VERIFIED target platform state must equal Zyro
 * bookkeeping. Probes every unprocessed target's complete operation target —
 * hidden state AND uninstall-block protection (plus durable timer evidence for
 * timed targets) — then synchronizes missing bookkeeping for fully verified
 * targets without re-applying Device Owner mutations. Partial states are
 * repaired through the established transaction policies and re-verified.
 * Returns the packages now verified-and-synchronized; Resume durably marks
 * those packages before processing anything else.
 */
private suspend fun reconcileInterruptedBatchBookkeeping(
    context: Context,
    record: ZeaBatchJournalRecord
): List<String> = withContext(Dispatchers.IO) {
    val verified = mutableListOf<String>()
    val isUnhide = record.operation == ZeaBatchJournal.OPERATION_UNHIDE
    val isTimed = record.operation == ZeaBatchJournal.OPERATION_TIMED_HIDE
    val timedRequest = record.timedRequestOrNull()
    val timedExpired = isTimed && timedRequest != null &&
        timedRequest.endEpochMillis <= System.currentTimeMillis()

    record.targets.forEach { target ->
        if (record.processed.any { it.equals(target, ignoreCase = true) }) {
            return@forEach
        }

        if (!ZeaDeviceOwnerController.isPackageInstalled(context, target)) {
            // No platform target remains. The common refresh/prune path removes
            // any stale bookkeeping; caller can journal this as resolved after
            // the refresh step.
            return@forEach
        }

        var actualHidden = ZeaDeviceOwnerController.isHidden(context, target)
        var actualBlocked = ZeaDeviceOwnerController.isUninstallBlocked(context, target)
        if (actualHidden == null || actualBlocked == null) {
            return@forEach
        }

        val expectsVisible = isUnhide || timedExpired
        if (expectsVisible) {
            if (actualHidden || actualBlocked) {
                if (!ZeaAppHideService.repairPartialVisibleState(context, target)) {
                    return@forEach
                }
                actualHidden = ZeaDeviceOwnerController.isHidden(context, target)
                actualBlocked = ZeaDeviceOwnerController.isUninstallBlocked(context, target)
            }
            if (actualHidden == false && actualBlocked == false) {
                if (ZeaAppHideService.syncBookkeepingToVerifiedVisibleState(context, target)) {
                    verified.add(target)
                }
            }
            return@forEach
        }

        // Hide/timed-hide expects both policy bits. A complete visible state is
        // simply pending and will go through the normal production transaction.
        if (actualHidden != actualBlocked) {
            if (!ZeaAppHideService.repairPartialHiddenState(context, target)) {
                return@forEach
            }
            actualHidden = ZeaDeviceOwnerController.isHidden(context, target)
            actualBlocked = ZeaDeviceOwnerController.isUninstallBlocked(context, target)
        }

        if (actualHidden == true && actualBlocked == true) {
            if (!isTimed) {
                if (ZeaAppHideService.syncBookkeepingToVerifiedHiddenState(context, target)) {
                    verified.add(target)
                }
                return@forEach
            }

            val timedRecord = loadTimedHides(context).firstOrNull { stored ->
                stored.packageName.equals(target, ignoreCase = true)
            }
            if (timedRecord != null && timedRecord.hiddenUntilEpochMillis > System.currentTimeMillis()) {
                if (ZeaAppHideService.syncBookkeepingToVerifiedHiddenState(context, target)) {
                    verified.add(target)
                }
            } else {
                // Partial timed transaction: hidden policy landed but its timer
                // did not. Roll back to visible and leave it pending so a v2
                // journal with metadata can re-run the timed transaction.
                ZeaAppHideService.rollbackUnfinishedTimedHide(context, target)
            }
        }
    }
    verified
}

private data class ZeaBatchClassifiedTarget(
    val packageName: String,
    val classification: String
)

private suspend fun classifyInterruptedBatch(
    context: Context,
    record: ZeaBatchJournalRecord
): List<ZeaBatchClassifiedTarget> = withContext(Dispatchers.IO) {
    val timedOperation = record.operation == ZeaBatchJournal.OPERATION_TIMED_HIDE
    val timedRequest = record.timedRequestOrNull()
    val timedExpired = timedOperation && timedRequest != null &&
        timedRequest.endEpochMillis <= System.currentTimeMillis()
    val expectedHidden = record.operation != ZeaBatchJournal.OPERATION_UNHIDE && !timedExpired

    record.targets.map { target ->
        val processed = record.processed.any { it.equals(target, ignoreCase = true) }
        val actualHidden = ZeaDeviceOwnerController.isHidden(context, target)
        val actualBlocked = ZeaDeviceOwnerController.isUninstallBlocked(context, target)
        val timedRecord = if (timedOperation) {
            loadTimedHides(context).firstOrNull { stored ->
                stored.packageName.equals(target, ignoreCase = true)
            }
        } else {
            null
        }

        val timerMatches = when {
            !timedOperation -> true
            timedExpired -> timedRecord == null
            timedRequest == null -> timedRecord != null &&
                timedRecord.hiddenUntilEpochMillis > System.currentTimeMillis()
            else -> timedRecord != null &&
                timedRecord.hiddenUntilEpochMillis == timedRequest.endEpochMillis
        }

        val matches = when {
            actualHidden == null || actualBlocked == null -> null
            else ->
                actualHidden == expectedHidden &&
                    actualBlocked == expectedHidden &&
                    timerMatches
        }
        val label = when {
            matches == null -> if (processed) "Unclassified" else "Remaining"
            matches && processed -> "Completed"
            matches && !processed -> "NoOp"
            !matches && processed -> "Failed"
            else -> "Remaining"
        }
        ZeaBatchClassifiedTarget(packageName = target, classification = label)
    }
}

@Composable
private fun ZeaInterruptedBatchDialog(
    context: Context,
    record: ZeaBatchJournalRecord,
    reviewMode: Boolean,
    onToggleReview: () -> Unit,
    onResume: () -> Unit,
    onAbandon: () -> Unit,
    onDismissResolved: () -> Unit
) {
    val done = record.processed.size
    val total = record.targets.size
    if (!reviewMode) {
        AlertDialog(
            onDismissRequest = onDismissResolved,
            title = {
                Text("Interrupted batch found")
            },
            text = {
                Column {
                    Text(
                        "A ${record.operation} batch of $total apps was interrupted after $done finished. " +
                            "Nothing was assumed from the record; states are verified on Resume or Review."
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        "Resume finishes only what is still pending. Abandon keeps current app states and closes the record.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            },
            confirmButton = {
                Button(onClick = onResume) {
                    Text("Resume Remaining")
                }
            },
            dismissButton = {
                Row {
                    TextButton(onClick = onToggleReview) {
                        Text("Review Details")
                    }
                    TextButton(onClick = onAbandon) {
                        Text("Abandon Record")
                    }
                }
            }
        )
    } else {
        var classified by remember(record.batchId) {
            mutableStateOf<List<ZeaBatchClassifiedTarget>>(emptyList())
        }
        LaunchedEffect(record.batchId) {
            classified = classifyInterruptedBatch(context, record)
        }
        AlertDialog(
            onDismissRequest = onDismissResolved,
            title = { Text("Batch details") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf("Completed", "NoOp", "Remaining", "Failed", "Unclassified").forEach { group ->
                        val items = classified.filter { it.classification == group }
                        if (items.isNotEmpty()) {
                            Text(
                                "$group (${items.size})",
                                fontWeight = FontWeight.SemiBold
                            )
                            items.take(6).forEach { item ->
                                Text(
                                    item.packageName,
                                    style = MaterialTheme.typography.bodySmall,
                                    modifier = Modifier.padding(start = 12.dp)
                                )
                            }
                            if (items.size > 6) {
                                Text(
                                    "…and ${items.size - 6} more",
                                    style = MaterialTheme.typography.bodySmall,
                                    modifier = Modifier.padding(start = 12.dp)
                                )
                            }
                        }
                    }
                    if (classified.isEmpty()) {
                        Text("Verifying current device state…")
                    }
                }
            },
            confirmButton = {
                Button(onClick = onResume) { Text("Resume Remaining") }
            },
            dismissButton = {
                Row {
                    TextButton(onClick = onAbandon) { Text("Abandon Record") }
                    TextButton(onClick = onToggleReview) { Text("Back") }
                }
            }
        )
    }
}
