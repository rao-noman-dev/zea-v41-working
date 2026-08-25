package com.raomuhammadnoman.zea

enum class AppRegistrySource {
    BUILT_IN,
    USER_ALLOWED,
    PRIVATE
}

enum class AppCategory {
    COMMUNICATION,
    SOCIAL,
    PRODUCTIVITY,
    EDUCATION,
    TRAVEL,
    TRANSPORT,
    SHOPPING,
    FOOD,
    HEALTH,
    FITNESS,
    MEDIA,
    ENTERTAINMENT,
    UTILITIES,
    CLOUD_STORAGE,
    DOCUMENTS,
    GOVERNMENT,
    SYSTEM,
    OTHER
}

data class AppRegistryEntry(
    val key: String,
    val displayName: String,
    val aliases: List<String>,
    val packageName: String? = null,
    val systemAction: String? = null,
    val alternatePackageNames: List<String> = emptyList(),
    val launcherActivityName: String? = null,
    val source: AppRegistrySource = AppRegistrySource.BUILT_IN,
    val category: AppCategory = AppCategory.OTHER
)

data class InstalledAppCandidate(
    val displayName: String,
    val packageName: String,
    val launcherActivityName: String
)

data class UserAllowedApp(
    val displayName: String,
    val packageName: String,
    val launcherActivityName: String,
    val aliases: List<String> = emptyList()
)

data class PrivateAppRecord(
    val displayName: String,
    val packageName: String,
    val launcherActivityName: String,
    val aliases: List<String> = emptyList()
)

enum class AllowedAppResolutionStatus {
    RESOLVED,
    NOT_FOUND,
    AMBIGUOUS,
    BLOCKED,
    NOT_LAUNCHABLE
}

data class AllowedAppResolution(
    val requestedName: String,
    val status: AllowedAppResolutionStatus,
    val selectedApp: UserAllowedApp? = null,
    val candidates: List<InstalledAppCandidate> = emptyList(),
    val message: String = ""
)

data class ZeaCommand(
    val rawCommand: String,
    val action: String,
    val appKey: String,
    val appDisplayName: String,
    val status: String,
    val message: String,
    val contactQuery: String = "",
    val messageText: String = ""
)

enum class AppLaunchMethod {
    SYSTEM_ACTION,
    PACKAGE_INTENT_SENDER,
    PACKAGE_LAUNCH_INTENT,
    EXPLICIT_LAUNCHER_COMPONENT,
    MAIN_LAUNCHER_QUERY,
    VERIFIED_APP_URI
}

enum class AppLaunchFailureReason {
    NONE,
    BLOCKED,
    NOT_ALLOWED,
    NOT_INSTALLED,
    NOT_VISIBLE,
    PRIVATE_PROFILE_LOCKED,
    SECURITY_REJECTED,
    NO_COMPATIBLE_HANDLER,
    LAUNCH_FAILED
}

data class LaunchAttempt(
    val method: AppLaunchMethod,
    val success: Boolean,
    val detail: String = ""
)

data class LaunchResult(
    val success: Boolean,
    val message: String,
    val method: AppLaunchMethod? = null,
    val failureReason: AppLaunchFailureReason = AppLaunchFailureReason.NONE,
    val attempts: List<LaunchAttempt> = emptyList()
)

data class ContactResult(
    val name: String,
    val phoneNumber: String,
    val whatsAppDataId: String = ""
)

data class WhatsAppMessageRequest(
    val contactQuery: String,
    val messageText: String
)

enum class SmartWhatsAppResolveStatus {
    READY_TO_PREFILL,
    NEEDS_CONTACT_SELECTION,
    CONTACT_NOT_FOUND,
    MESSAGE_NOT_FOUND,
    LOW_CONFIDENCE,
    NOT_WHATSAPP_MESSAGE
}

enum class SmartWhatsAppConfidence {
    HIGH,
    MEDIUM,
    LOW,
    NONE
}

data class SmartWhatsAppCommandResolution(
    val rawCommand: String,
    val status: SmartWhatsAppResolveStatus,
    val confidence: SmartWhatsAppConfidence,
    val selectedContact: ContactResult? = null,
    val matchingContacts: List<ContactResult> = emptyList(),
    val contactQuery: String = "",
    val matchedContactPhrase: String = "",
    val messageText: String = "",
    val reason: String = ""
)

data class PendingWhatsAppMessage(
    val originalCommand: String,
    val contactQuery: String,
    val contactName: String,
    val phoneNumber: String,
    val messageText: String
)

data class EncryptedPinValue(
    val encryptedPin: String,
    val iv: String
)

data class AppLockConfiguration(
    val enabled: Boolean,
    val relockAfterMillis: Long
)

enum class CommandStrategy(
    val title: String,
    val shortDescription: String,
    val inputLabel: String,
    val placeholder: String,
    val examples: String
) {
    OPEN_APPS(
        title = "Open Apps",
        shortDescription = "Open supported or user-approved apps safely using simple text commands.",
        inputLabel = "App command",
        placeholder = "Example: Open WhatsApp, Chrome, Spotify, or Maps",
        examples = "WhatsApp • open Chrome • Spotify • Maps"
    ),
    SEND_MESSAGE(
        title = "Send Message",
        shortDescription = "Prepare a WhatsApp message for a contact with confirmation before opening WhatsApp.",
        inputLabel = "Message command",
        placeholder = "Example: Tell John: I will meet you tomorrow",
        examples = "Tell John: I will meet you tomorrow • Message John: I am coming"
    )
}
