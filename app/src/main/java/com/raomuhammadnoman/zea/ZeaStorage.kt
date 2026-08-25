package com.raomuhammadnoman.zea

import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.security.KeyStore
import java.security.MessageDigest
import java.security.SecureRandom
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec

internal object ZeaStorageContract {
    const val PREFERENCES_NAME = "zea_local_storage_v09_full"

    const val LAST_COMMAND = "last_command"
    const val LAST_RESULT = "last_result"
    const val COMMAND_LOGS = "command_logs"
    const val FAILED_ACTIONS = "failed_actions"
    const val FAVORITE_CONTACTS = "favorite_contacts"
    const val ALLOWED_APPS_TEXT = "allowed_apps"
    const val BLOCKED_APPS = "blocked_apps"
    const val USER_ALLOWED_APPS_JSON = "user_allowed_apps_json_v1"
    const val PRIVATE_APPS_JSON = "private_apps_json_v1"
    const val TIMED_HIDES_JSON = "timed_hides_json_v1"

    const val LEGACY_ADMIN_PIN_HASH = "admin_pin_hash"
    const val ADMIN_PIN_HASH = "admin_pin_hash_v2"
    const val ADMIN_PIN_SALT = "admin_pin_salt_v2"
    const val ADMIN_PIN_ITERATIONS = "admin_pin_iterations_v2"
    const val USER_PIN_ENCRYPTED = "user_pin_encrypted"
    const val USER_PIN_IV = "user_pin_iv"

    const val APP_LOCK_ENABLED = "temporary_app_lock_enabled"
    const val APP_LOCK_RELOCK_MILLIS = "temporary_app_lock_relock_millis"

    const val EMPTY_LAST_COMMAND = "No command saved yet."
    const val EMPTY_LAST_RESULT = "No result saved yet."
    const val EMPTY_COMMAND_LOGS = "No command logs yet."
    const val EMPTY_FAILED_ACTIONS = "No failed actions yet."

    const val USER_ALLOWED_APPS_SCHEMA_VERSION = 1
    const val PRIVATE_APPS_SCHEMA_VERSION = 1
    const val MAX_USER_ALLOWED_APPS = 100
    const val MAX_PRIVATE_APPS = 1000
    const val MAX_APP_NAME_LENGTH = 120
    const val MAX_PACKAGE_NAME_LENGTH = 255
    const val MAX_ACTIVITY_NAME_LENGTH = 500
    const val MAX_ALIAS_COUNT = 12

    const val PIN_KEYSTORE_ALIAS = "zea_user_pin_key_v1"
    const val PIN_DERIVATION_ALGORITHM = "PBKDF2WithHmacSHA256"
    const val PIN_DERIVATION_ITERATIONS = 120_000
    const val MAXIMUM_PIN_DERIVATION_ITERATIONS = 1_000_000
    const val PIN_DERIVATION_KEY_LENGTH_BITS = 256
    const val PIN_SALT_LENGTH_BYTES = 16
    const val MINIMUM_PIN_LENGTH = 4
    const val MAXIMUM_PIN_LENGTH = 128

    const val DEFAULT_APP_LOCK_ENABLED = true
    const val DEFAULT_APP_LOCK_RELOCK_MILLIS = 0L
    const val MAXIMUM_APP_LOCK_RELOCK_MILLIS = 86_400_000L
}

private data class PinCredential(
    val salt: ByteArray,
    val hash: ByteArray,
    val iterations: Int
)

fun getZeaPrefs(context: Context): SharedPreferences {
    return context.applicationContext.getSharedPreferences(
        ZeaStorageContract.PREFERENCES_NAME,
        Context.MODE_PRIVATE
    )
}

fun getCurrentTimeText(): String {
    return SimpleDateFormat(
        "dd MMM yyyy, hh:mm a",
        Locale.getDefault()
    ).format(Date())
}

/**
 * Keywords an earlier revision seeded the blocked list with. Blocking apps by
 * financial category has been withdrawn, so a stored copy of exactly this seed
 * is dropped on read; otherwise an existing install would keep banking apps
 * blocked after updating, with no settings screen left to clear it from.
 */
private val legacyFinancialBlocklistSeed = setOf(
    "bank",
    "banking",
    "payment",
    "payments",
    "wallet",
    "finance",
    "trading",
    "crypto",
    "jazzcash",
    "easypaisa",
    "sadapay",
    "nayapay"
)

private fun isLegacyFinancialBlocklist(value: String): Boolean {
    val entries = splitSettingsList(value)
        .map { entry -> entry.trim().lowercase(Locale.ROOT) }
        .filter(String::isNotEmpty)
        .toSet()

    return entries == legacyFinancialBlocklistSeed
}

fun loadLastCommand(context: Context): String {
    return readPreferenceString(
        context = context,
        key = ZeaStorageContract.LAST_COMMAND,
        defaultValue = ZeaStorageContract.EMPTY_LAST_COMMAND
    )
}

fun loadLastResult(context: Context): String {
    return readPreferenceString(
        context = context,
        key = ZeaStorageContract.LAST_RESULT,
        defaultValue = ZeaStorageContract.EMPTY_LAST_RESULT
    )
}

fun loadCommandLogs(context: Context): String {
    return readPreferenceString(
        context = context,
        key = ZeaStorageContract.COMMAND_LOGS,
        defaultValue = ZeaStorageContract.EMPTY_COMMAND_LOGS
    )
}

fun loadFailedActions(context: Context): String {
    return readPreferenceString(
        context = context,
        key = ZeaStorageContract.FAILED_ACTIONS,
        defaultValue = ZeaStorageContract.EMPTY_FAILED_ACTIONS
    )
}

fun loadFavoriteContacts(context: Context): String {
    return readPreferenceString(
        context = context,
        key = ZeaStorageContract.FAVORITE_CONTACTS,
        defaultValue = ""
    )
}

fun loadAllowedApps(context: Context): String {
    return readPreferenceString(
        context = context,
        key = ZeaStorageContract.ALLOWED_APPS_TEXT,
        defaultValue = ""
    )
}

fun loadBlockedApps(context: Context): String {
    val storedValue = readPreferenceString(
        context = context,
        key = ZeaStorageContract.BLOCKED_APPS,
        defaultValue = ""
    )

    if (isLegacyFinancialBlocklist(storedValue)) {
        getZeaPrefs(context).edit()
            .putString(ZeaStorageContract.BLOCKED_APPS, "")
            .apply()

        return ""
    }

    return storedValue
}

/**
 * Retained for legacy PIN migration. New PINs are stored with a salted PBKDF2
 * credential and are never persisted as this unsalted digest.
 */
fun hashAdminPin(pin: String): String {
    return MessageDigest.getInstance("SHA-256")
        .digest(pin.trim().toByteArray(Charsets.UTF_8))
        .toHexString()
}

fun isAdminPinSet(context: Context): Boolean {
    val preferences = getZeaPrefs(context)
    val hasVersionedCredential =
        preferences.readStringSafely(ZeaStorageContract.ADMIN_PIN_HASH).isNotBlank() &&
                preferences.readStringSafely(ZeaStorageContract.ADMIN_PIN_SALT).isNotBlank()

    return hasVersionedCredential ||
            preferences.readStringSafely(ZeaStorageContract.LEGACY_ADMIN_PIN_HASH).isNotBlank()
}

const val ZEA_SELF_CLEAR_LOCK_EXTRA = "zea_self_clear_lock"

const val ZEA_SELF_REMOVE_OWNER_EXTRA = "zea_self_remove_owner"

/**
 * Removes this app's Device Owner role and disables its device admin so the
 * package can be uninstalled like any normal app afterwards. Only the app
 * itself may perform this operation while it still owns the role.
 */
fun selfRemoveDeviceOwner(context: Context): Boolean {
    return try {
        val policyManager = context.getSystemService(
            Context.DEVICE_POLICY_SERVICE
        ) as? DevicePolicyManager
        val adminComponent = ComponentName(
            context,
            ZeaDeviceAdminReceiver::class.java
        )

        policyManager?.clearDeviceOwnerApp(context.packageName)

        try {
            if (policyManager != null && policyManager.isAdminActive(adminComponent)) {
                policyManager.removeActiveAdmin(adminComponent)
            }
        } catch (_: RuntimeException) {
            // Admin may already be gone once ownership cleared.
        }

        true
    } catch (_: RuntimeException) {
        false
    }
}

/**
 * Wipes only the lock credentials and the onboarding flags so the very next
 * launch shows the original Create-PIN flow. Deliberately leaves Device
 * Owner provisioning, hidden-app records, lock-mode state, and every other
 * stored setting untouched.
 */
fun selfClearLockState(context: Context): Boolean {
    return try {
        getZeaPrefs(context).edit()
            .remove(ZeaStorageContract.ADMIN_PIN_HASH)
            .remove(ZeaStorageContract.ADMIN_PIN_SALT)
            .remove(ZeaStorageContract.ADMIN_PIN_ITERATIONS)
            .remove(ZeaStorageContract.USER_PIN_ENCRYPTED)
            .remove(ZeaStorageContract.USER_PIN_IV)
            .remove(ZeaStorageContract.LEGACY_ADMIN_PIN_HASH)
            .commit()

        context.getSharedPreferences(
            "zyro_onboarding_state",
            Context.MODE_PRIVATE
        ).edit().clear().commit()

        true
    } catch (_: RuntimeException) {
        false
    }
}

fun saveAdminPin(context: Context, pin: String): Boolean {
    val cleanPin = pin.trim()

    if (cleanPin.length !in ZeaStorageContract.MINIMUM_PIN_LENGTH..ZeaStorageContract.MAXIMUM_PIN_LENGTH) {
        return false
    }

    return try {
        val credential = createPinCredential(cleanPin)
        val encryptedValue = encryptUserPin(cleanPin)

        getZeaPrefs(context).edit()
            .putString(
                ZeaStorageContract.ADMIN_PIN_HASH,
                Base64.encodeToString(credential.hash, Base64.NO_WRAP)
            )
            .putString(
                ZeaStorageContract.ADMIN_PIN_SALT,
                Base64.encodeToString(credential.salt, Base64.NO_WRAP)
            )
            .putInt(
                ZeaStorageContract.ADMIN_PIN_ITERATIONS,
                credential.iterations
            )
            .putString(
                ZeaStorageContract.USER_PIN_ENCRYPTED,
                encryptedValue.encryptedPin
            )
            .putString(
                ZeaStorageContract.USER_PIN_IV,
                encryptedValue.iv
            )
            .remove(ZeaStorageContract.LEGACY_ADMIN_PIN_HASH)
            .commit()
    } catch (_: Exception) {
        false
    }
}

fun verifyAdminPin(context: Context, pin: String): Boolean {
    val cleanPin = pin.trim()

    if (cleanPin.isBlank()) {
        return false
    }

    val preferences = getZeaPrefs(context)
    val versionedHash = preferences.readStringSafely(
        ZeaStorageContract.ADMIN_PIN_HASH
    )
    val versionedSalt = preferences.readStringSafely(
        ZeaStorageContract.ADMIN_PIN_SALT
    )

    if (versionedHash.isNotBlank() && versionedSalt.isNotBlank()) {
        return verifyVersionedPin(
            pin = cleanPin,
            encodedHash = versionedHash,
            encodedSalt = versionedSalt,
            iterations = preferences.readIntSafely(
                key = ZeaStorageContract.ADMIN_PIN_ITERATIONS,
                defaultValue = ZeaStorageContract.PIN_DERIVATION_ITERATIONS
            )
        )
    }

    val legacyHash = preferences.readStringSafely(
        ZeaStorageContract.LEGACY_ADMIN_PIN_HASH
    )

    if (legacyHash.isBlank()) {
        return false
    }

    val legacyMatch = constantTimeEquals(
        left = legacyHash.lowercase(Locale.ROOT),
        right = hashAdminPin(cleanPin)
    )

    if (legacyMatch) {
        saveAdminPin(context, cleanPin)
    }

    return legacyMatch
}

fun adminPinStatusText(context: Context): String {
    return if (isAdminPinSet(context)) {
        "User PIN is set."
    } else {
        "User PIN is not set yet."
    }
}

fun getOrCreateUserPinSecretKey(): SecretKey {
    val keyStore = KeyStore.getInstance("AndroidKeyStore")
    keyStore.load(null)

    val existingKey = keyStore.getKey(
        ZeaStorageContract.PIN_KEYSTORE_ALIAS,
        null
    )

    if (existingKey is SecretKey) {
        return existingKey
    }

    val keyGenerator = KeyGenerator.getInstance(
        KeyProperties.KEY_ALGORITHM_AES,
        "AndroidKeyStore"
    )
    val keySpec = KeyGenParameterSpec.Builder(
        ZeaStorageContract.PIN_KEYSTORE_ALIAS,
        KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
    )
        .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
        .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
        .setRandomizedEncryptionRequired(true)
        .build()

    keyGenerator.init(keySpec)
    return keyGenerator.generateKey()
}

fun encryptUserPin(pin: String): EncryptedPinValue {
    val cipher = Cipher.getInstance("AES/GCM/NoPadding")
    cipher.init(Cipher.ENCRYPT_MODE, getOrCreateUserPinSecretKey())

    val encryptedBytes = cipher.doFinal(pin.toByteArray(Charsets.UTF_8))

    return EncryptedPinValue(
        encryptedPin = Base64.encodeToString(encryptedBytes, Base64.NO_WRAP),
        iv = Base64.encodeToString(cipher.iv, Base64.NO_WRAP)
    )
}

fun decryptUserPin(
    encryptedPin: String,
    iv: String
): String {
    val cipher = Cipher.getInstance("AES/GCM/NoPadding")
    val ivBytes = Base64.decode(iv, Base64.NO_WRAP)
    val encryptedBytes = Base64.decode(encryptedPin, Base64.NO_WRAP)

    cipher.init(
        Cipher.DECRYPT_MODE,
        getOrCreateUserPinSecretKey(),
        GCMParameterSpec(128, ivBytes)
    )

    return String(cipher.doFinal(encryptedBytes), Charsets.UTF_8)
}

fun canRevealSavedUserPin(context: Context): Boolean {
    val preferences = getZeaPrefs(context)

    return preferences
        .readStringSafely(ZeaStorageContract.USER_PIN_ENCRYPTED)
        .isNotBlank() &&
            preferences
                .readStringSafely(ZeaStorageContract.USER_PIN_IV)
                .isNotBlank()
}

fun revealSavedUserPin(context: Context): String {
    val preferences = getZeaPrefs(context)
    val encryptedPin = preferences.readStringSafely(
        ZeaStorageContract.USER_PIN_ENCRYPTED
    )
    val iv = preferences.readStringSafely(
        ZeaStorageContract.USER_PIN_IV
    )

    if (encryptedPin.isBlank() || iv.isBlank()) {
        return ""
    }

    return try {
        decryptUserPin(encryptedPin, iv)
    } catch (_: Exception) {
        ""
    }
}

fun saveLastCommand(context: Context, command: String) {
    getZeaPrefs(context).edit()
        .putString(ZeaStorageContract.LAST_COMMAND, command)
        .apply()
}

fun saveLastResult(context: Context, result: String) {
    getZeaPrefs(context).edit()
        .putString(ZeaStorageContract.LAST_RESULT, result)
        .apply()
}

fun appendLimitedLog(
    oldLog: String,
    emptyText: String,
    newEntry: String,
    maxEntries: Int = 100
): String {
    val safeMaximum = maxEntries.coerceAtLeast(1)
    val currentEntries = if (oldLog == emptyText || oldLog.isBlank()) {
        emptyList()
    } else {
        oldLog.split("\n\n")
    }

    return sequenceOf(newEntry)
        .plus(currentEntries.asSequence())
        .take(safeMaximum)
        .joinToString("\n\n")
}

fun saveCommandLog(
    context: Context,
    rawCommand: String,
    status: String,
    action: String,
    summary: String
) {
    val newEntry = """
        Time: ${getCurrentTimeText()}
        Command: $rawCommand
        Status: $status
        Action: $action
        Summary: $summary
    """.trimIndent()

    saveLogValue(
        context = context,
        key = ZeaStorageContract.COMMAND_LOGS,
        emptyText = ZeaStorageContract.EMPTY_COMMAND_LOGS,
        newEntry = newEntry
    )
}

fun saveFailedAction(
    context: Context,
    rawCommand: String,
    action: String,
    reason: String
) {
    val newEntry = """
        Time: ${getCurrentTimeText()}
        Command: $rawCommand
        Action: $action
        Reason: $reason
    """.trimIndent()

    saveLogValue(
        context = context,
        key = ZeaStorageContract.FAILED_ACTIONS,
        emptyText = ZeaStorageContract.EMPTY_FAILED_ACTIONS,
        newEntry = newEntry
    )
}

fun saveFavoriteContacts(context: Context, value: String) {
    getZeaPrefs(context).edit()
        .putString(ZeaStorageContract.FAVORITE_CONTACTS, value.trim())
        .apply()
}

fun saveAllowedApps(context: Context, value: String) {
    getZeaPrefs(context).edit()
        .putString(ZeaStorageContract.ALLOWED_APPS_TEXT, value.trim())
        .apply()
    ZeaAppLookupCache.invalidate("allowed apps text changed")
}

fun saveBlockedApps(context: Context, value: String) {
    getZeaPrefs(context).edit()
        .putString(ZeaStorageContract.BLOCKED_APPS, value.trim())
        .apply()
}

fun loadUserAllowedApps(context: Context): List<UserAllowedApp> {
    val storedJson = readPreferenceString(
        context = context,
        key = ZeaStorageContract.USER_ALLOWED_APPS_JSON,
        defaultValue = ""
    )

    if (storedJson.isBlank()) {
        return emptyList()
    }

    return try {
        decodeUserAllowedApps(storedJson)
    } catch (_: JSONException) {
        emptyList()
    } catch (_: RuntimeException) {
        emptyList()
    }
}

fun saveUserAllowedApps(
    context: Context,
    userAllowedApps: List<UserAllowedApp>
): Boolean {
    return commitAllowedAppsState(
        preferences = getZeaPrefs(context),
        allowedAppsText = null,
        userAllowedApps = sanitizeUserAllowedApps(userAllowedApps)
    )
}

suspend fun saveAllowedAppsState(
    context: Context,
    allowedAppsText: String,
    resolutions: List<AllowedAppResolution>
): Boolean = withContext(Dispatchers.IO) {
    val resolvedApps = resolutions
        .asSequence()
        .filter { resolution ->
            resolution.status == AllowedAppResolutionStatus.RESOLVED
        }
        .mapNotNull(AllowedAppResolution::selectedApp)
        .toList()
    val safeApps = sanitizeUserAllowedApps(resolvedApps)

    commitAllowedAppsState(
        preferences = getZeaPrefs(context),
        allowedAppsText = allowedAppsText.trim(),
        userAllowedApps = safeApps
    )
}

fun loadUserAllowedRegistryEntries(context: Context): List<AppRegistryEntry> {
    return ZeaInstalledApps.buildUserAllowedRegistryEntries(
        loadUserAllowedApps(context)
    )
}

fun loadPrivateApps(context: Context): List<PrivateAppRecord> {
    val storedJson = readPreferenceString(
        context = context,
        key = ZeaStorageContract.PRIVATE_APPS_JSON,
        defaultValue = ""
    )

    if (storedJson.isBlank()) {
        return emptyList()
    }

    return try {
        decodePrivateApps(storedJson)
    } catch (_: JSONException) {
        emptyList()
    } catch (_: RuntimeException) {
        emptyList()
    }
}

fun savePrivateApps(
    context: Context,
    privateApps: List<PrivateAppRecord>
): Boolean {
    val safeApps = sanitizePrivateApps(privateApps)
    val committed = getZeaPrefs(context).edit()
        .putString(
            ZeaStorageContract.PRIVATE_APPS_JSON,
            encodePrivateApps(safeApps)
        )
        .commit()

    if (committed) {
        ZeaPrivateAppLookupCache.invalidate("private app registry changed")
        ZeaAppLookupCache.invalidate("private app registry changed")
    }

    return committed
}

/**
 * Deadlines for apps hidden for a fixed duration.
 *
 * Kept separate from the private app registry because a timed hide is an
 * instruction to release the app later, while the registry only records what is
 * protected right now.
 */
fun loadTimedHides(context: Context): List<ZeaTimedHideRecord> {
    val storedJson = readPreferenceString(
        context = context,
        key = ZeaStorageContract.TIMED_HIDES_JSON,
        defaultValue = ""
    )

    if (storedJson.isBlank()) {
        return emptyList()
    }

    return try {
        val array = JSONArray(storedJson)

        (0 until array.length())
            .asSequence()
            .mapNotNull { index -> array.optJSONObject(index) }
            .mapNotNull { entry ->
                val packageName = entry.optString("packageName").trim()
                val until = entry.optLong("hiddenUntilEpochMillis", 0L)

                if (packageName.isBlank() || until <= 0L) {
                    null
                } else {
                    ZeaTimedHideRecord(
                        packageName = packageName,
                        displayName = entry.optString("displayName").trim()
                            .ifBlank { packageName },
                        hiddenAtEpochMillis = entry.optLong("hiddenAtEpochMillis", 0L),
                        hiddenUntilEpochMillis = until
                    )
                }
            }
            .distinctBy { record -> record.packageName.lowercase(Locale.ROOT) }
            .toList()
    } catch (_: JSONException) {
        emptyList()
    } catch (_: RuntimeException) {
        emptyList()
    }
}

fun saveTimedHides(
    context: Context,
    records: List<ZeaTimedHideRecord>
): Boolean {
    val array = JSONArray()

    records
        .distinctBy { record -> record.packageName.lowercase(Locale.ROOT) }
        .forEach { record ->
            array.put(
                JSONObject()
                    .put("packageName", record.packageName)
                    .put("displayName", record.displayName)
                    .put("hiddenAtEpochMillis", record.hiddenAtEpochMillis)
                    .put("hiddenUntilEpochMillis", record.hiddenUntilEpochMillis)
            )
        }

    return getZeaPrefs(context).edit()
        .putString(ZeaStorageContract.TIMED_HIDES_JSON, array.toString())
        .commit()
}

fun loadAppLockConfiguration(context: Context): AppLockConfiguration {
    val preferences = getZeaPrefs(context)
    val relockAfterMillis = preferences.readLongSafely(
        key = ZeaStorageContract.APP_LOCK_RELOCK_MILLIS,
        defaultValue = ZeaStorageContract.DEFAULT_APP_LOCK_RELOCK_MILLIS
    ).coerceIn(
        minimumValue = 0L,
        maximumValue = ZeaStorageContract.MAXIMUM_APP_LOCK_RELOCK_MILLIS
    )

    return AppLockConfiguration(
        enabled = preferences.readBooleanSafely(
            key = ZeaStorageContract.APP_LOCK_ENABLED,
            defaultValue = ZeaStorageContract.DEFAULT_APP_LOCK_ENABLED
        ),
        relockAfterMillis = relockAfterMillis
    )
}

fun saveAppLockConfiguration(
    context: Context,
    configuration: AppLockConfiguration
): Boolean {
    val safeRelockDelay = configuration.relockAfterMillis.coerceIn(
        minimumValue = 0L,
        maximumValue = ZeaStorageContract.MAXIMUM_APP_LOCK_RELOCK_MILLIS
    )

    return getZeaPrefs(context).edit()
        .putBoolean(
            ZeaStorageContract.APP_LOCK_ENABLED,
            configuration.enabled
        )
        .putLong(
            ZeaStorageContract.APP_LOCK_RELOCK_MILLIS,
            safeRelockDelay
        )
        .commit()
}

fun clearLogs(context: Context) {
    getZeaPrefs(context).edit()
        .putString(
            ZeaStorageContract.LAST_COMMAND,
            ZeaStorageContract.EMPTY_LAST_COMMAND
        )
        .putString(
            ZeaStorageContract.LAST_RESULT,
            ZeaStorageContract.EMPTY_LAST_RESULT
        )
        .putString(
            ZeaStorageContract.COMMAND_LOGS,
            ZeaStorageContract.EMPTY_COMMAND_LOGS
        )
        .putString(
            ZeaStorageContract.FAILED_ACTIONS,
            ZeaStorageContract.EMPTY_FAILED_ACTIONS
        )
        .apply()
}

fun clearSettings(context: Context) {
    getZeaPrefs(context).edit()
        .putString(ZeaStorageContract.FAVORITE_CONTACTS, "")
        .putString(ZeaStorageContract.ALLOWED_APPS_TEXT, "")
        .putString(ZeaStorageContract.BLOCKED_APPS, "")
        .remove(ZeaStorageContract.USER_ALLOWED_APPS_JSON)
        .apply()
}

fun splitSettingsList(value: String): List<String> {
    return value
        .split(',', '\n')
        .asSequence()
        .map(String::trim)
        .filter(String::isNotBlank)
        .distinctBy(::normalizeSettingValue)
        .toList()
}

fun appMatchesSetting(
    app: AppRegistryEntry,
    settingItem: String
): Boolean {
    val normalizedSetting = normalizeSettingValue(settingItem)

    if (normalizedSetting.isBlank()) {
        return false
    }

    val settingTokens = normalizedSetting.split(' ').filter(String::isNotBlank)
    val compactSetting = normalizedSetting.replace(" ", "")

    return sequenceOf(app.key, app.displayName)
        .plus(app.aliases.asSequence())
        .map(::normalizeSettingValue)
        .filter(String::isNotBlank)
        .any { appName ->
            val appTokens = appName.split(' ').filter(String::isNotBlank)

            appName == normalizedSetting ||
                    appName.replace(" ", "") == compactSetting ||
                    appTokens.containsAll(settingTokens)
        }
}

fun isAppBlockedBySettings(
    context: Context,
    app: AppRegistryEntry
): Boolean {
    if (!ZeaSafetyPolicy.evaluateRegistryEntry(app).allowed) {
        return true
    }

    return splitSettingsList(loadBlockedApps(context)).any { blockedItem ->
        appMatchesSetting(app, blockedItem)
    }
}

fun isAppAllowedBySettings(
    context: Context,
    app: AppRegistryEntry
): Boolean {
    val allowedItems = splitSettingsList(loadAllowedApps(context))

    return allowedItems.isEmpty() || allowedItems.any { allowedItem ->
        appMatchesSetting(app, allowedItem)
    }
}

fun settingsSummaryText(context: Context): String {
    val verifiedApps = loadUserAllowedApps(context)
        .joinToString(", ") { app -> app.displayName }
        .ifBlank { "None" }

    return """
        Favorite Contacts:
        ${loadFavoriteContacts(context).ifBlank { "Not set" }}

        Allowed Apps:
        ${loadAllowedApps(context).ifBlank { "Empty = all configured apps allowed except blocked apps" }}

        Verified User Apps:
        $verifiedApps

        Blocked Apps:
        ${loadBlockedApps(context).ifBlank { "None" }}
    """.trimIndent()
}

private fun readPreferenceString(
    context: Context,
    key: String,
    defaultValue: String
): String {
    return getZeaPrefs(context).readStringSafely(
        key = key,
        defaultValue = defaultValue
    )
}

private fun SharedPreferences.readStringSafely(
    key: String,
    defaultValue: String = ""
): String {
    return try {
        getString(key, defaultValue) ?: defaultValue
    } catch (_: ClassCastException) {
        defaultValue
    }
}

private fun SharedPreferences.readIntSafely(
    key: String,
    defaultValue: Int
): Int {
    return try {
        getInt(key, defaultValue)
    } catch (_: ClassCastException) {
        defaultValue
    }
}

private fun SharedPreferences.readLongSafely(
    key: String,
    defaultValue: Long
): Long {
    return try {
        getLong(key, defaultValue)
    } catch (_: ClassCastException) {
        defaultValue
    }
}

private fun SharedPreferences.readBooleanSafely(
    key: String,
    defaultValue: Boolean
): Boolean {
    return try {
        getBoolean(key, defaultValue)
    } catch (_: ClassCastException) {
        defaultValue
    }
}

private fun createPinCredential(pin: String): PinCredential {
    val salt = ByteArray(ZeaStorageContract.PIN_SALT_LENGTH_BYTES)
        .also(SecureRandom()::nextBytes)

    return PinCredential(
        salt = salt,
        hash = derivePinHash(
            pin = pin,
            salt = salt,
            iterations = ZeaStorageContract.PIN_DERIVATION_ITERATIONS
        ),
        iterations = ZeaStorageContract.PIN_DERIVATION_ITERATIONS
    )
}

private fun derivePinHash(
    pin: String,
    salt: ByteArray,
    iterations: Int
): ByteArray {
    val safeIterations = iterations.coerceIn(
        minimumValue = 1,
        maximumValue = ZeaStorageContract.MAXIMUM_PIN_DERIVATION_ITERATIONS
    )
    val keySpec = PBEKeySpec(
        pin.toCharArray(),
        salt,
        safeIterations,
        ZeaStorageContract.PIN_DERIVATION_KEY_LENGTH_BITS
    )

    return try {
        SecretKeyFactory
            .getInstance(ZeaStorageContract.PIN_DERIVATION_ALGORITHM)
            .generateSecret(keySpec)
            .encoded
    } finally {
        keySpec.clearPassword()
    }
}

private fun verifyVersionedPin(
    pin: String,
    encodedHash: String,
    encodedSalt: String,
    iterations: Int
): Boolean {
    return try {
        val expectedHash = Base64.decode(encodedHash, Base64.NO_WRAP)
        val salt = Base64.decode(encodedSalt, Base64.NO_WRAP)
        val actualHash = derivePinHash(
            pin = pin,
            salt = salt,
            iterations = iterations
        )

        MessageDigest.isEqual(expectedHash, actualHash)
    } catch (_: Exception) {
        false
    }
}

private fun constantTimeEquals(
    left: String,
    right: String
): Boolean {
    return MessageDigest.isEqual(
        left.toByteArray(Charsets.UTF_8),
        right.toByteArray(Charsets.UTF_8)
    )
}

private fun ByteArray.toHexString(): String {
    return joinToString(separator = "") { byte ->
        "%02x".format(byte.toInt() and 0xff)
    }
}

private fun saveLogValue(
    context: Context,
    key: String,
    emptyText: String,
    newEntry: String
) {
    val currentLog = readPreferenceString(
        context = context,
        key = key,
        defaultValue = emptyText
    )
    val updatedLog = appendLimitedLog(
        oldLog = currentLog,
        emptyText = emptyText,
        newEntry = newEntry
    )

    getZeaPrefs(context).edit()
        .putString(key, updatedLog)
        .apply()
}


private fun commitAllowedAppsState(
    preferences: SharedPreferences,
    allowedAppsText: String?,
    userAllowedApps: List<UserAllowedApp>
): Boolean {
    val editor = preferences.edit()
        .putString(
            ZeaStorageContract.USER_ALLOWED_APPS_JSON,
            encodeUserAllowedApps(userAllowedApps)
        )

    if (allowedAppsText != null) {
        editor.putString(
            ZeaStorageContract.ALLOWED_APPS_TEXT,
            allowedAppsText
        )
    }

    val committed = editor.commit()
    if (committed) {
        ZeaAppLookupCache.invalidate("user allowed registry changed")
    }
    return committed
}

private fun sanitizeUserAllowedApps(
    userAllowedApps: List<UserAllowedApp>
): List<UserAllowedApp> {
    return userAllowedApps
        .asSequence()
        .mapNotNull(::sanitizeUserAllowedApp)
        .distinctBy { app -> app.packageName.lowercase(Locale.ROOT) }
        .take(ZeaStorageContract.MAX_USER_ALLOWED_APPS)
        .sortedWith(
            compareBy<UserAllowedApp> { app ->
                normalizeSettingValue(app.displayName)
            }.thenBy { app ->
                app.packageName.lowercase(Locale.ROOT)
            }
        )
        .toList()
}

private fun sanitizeUserAllowedApp(
    app: UserAllowedApp
): UserAllowedApp? {
    val cleanDisplayName = app.displayName
        .trim()
        .take(ZeaStorageContract.MAX_APP_NAME_LENGTH)
    val cleanPackageName = app.packageName
        .trim()
        .take(ZeaStorageContract.MAX_PACKAGE_NAME_LENGTH)
    val cleanActivityName = app.launcherActivityName
        .trim()
        .take(ZeaStorageContract.MAX_ACTIVITY_NAME_LENGTH)
    val cleanAliases = app.aliases
        .asSequence()
        .map(String::trim)
        .filter(String::isNotBlank)
        .map { alias -> alias.take(ZeaStorageContract.MAX_APP_NAME_LENGTH) }
        .filterNot { alias ->
            normalizeSettingValue(alias) == normalizeSettingValue(cleanDisplayName)
        }
        .distinctBy(::normalizeSettingValue)
        .take(ZeaStorageContract.MAX_ALIAS_COUNT)
        .toList()
    val sanitizedApp = UserAllowedApp(
        displayName = cleanDisplayName,
        packageName = cleanPackageName,
        launcherActivityName = cleanActivityName,
        aliases = cleanAliases
    )

    return sanitizedApp.takeIf { candidate ->
        ZeaSafetyPolicy.evaluateUserAllowedApp(candidate).allowed
    }
}

private fun sanitizePrivateApps(
    privateApps: List<PrivateAppRecord>
): List<PrivateAppRecord> {
    return privateApps
        .asSequence()
        .mapNotNull(::sanitizePrivateApp)
        .distinctBy { app -> app.packageName.lowercase(Locale.ROOT) }
        .take(ZeaStorageContract.MAX_PRIVATE_APPS)
        .sortedWith(
            compareBy<PrivateAppRecord> { app ->
                normalizeSettingValue(app.displayName)
            }.thenBy { app ->
                app.packageName.lowercase(Locale.ROOT)
            }
        )
        .toList()
}

private fun sanitizePrivateApp(
    app: PrivateAppRecord
): PrivateAppRecord? {
    val cleanDisplayName = app.displayName
        .trim()
        .take(ZeaStorageContract.MAX_APP_NAME_LENGTH)
    val cleanPackageName = app.packageName
        .trim()
        .take(ZeaStorageContract.MAX_PACKAGE_NAME_LENGTH)
    val cleanActivityName = app.launcherActivityName
        .trim()
        .take(ZeaStorageContract.MAX_ACTIVITY_NAME_LENGTH)
    val cleanAliases = app.aliases
        .asSequence()
        .map(String::trim)
        .filter(String::isNotBlank)
        .map { alias -> alias.take(ZeaStorageContract.MAX_APP_NAME_LENGTH) }
        .filterNot { alias ->
            normalizeSettingValue(alias) == normalizeSettingValue(cleanDisplayName)
        }
        .distinctBy(::normalizeSettingValue)
        .take(ZeaStorageContract.MAX_ALIAS_COUNT)
        .toList()
    val sanitizedApp = PrivateAppRecord(
        displayName = cleanDisplayName,
        packageName = cleanPackageName,
        launcherActivityName = cleanActivityName,
        aliases = cleanAliases
    )
    val safetyApp = UserAllowedApp(
        displayName = sanitizedApp.displayName,
        packageName = sanitizedApp.packageName,
        launcherActivityName = sanitizedApp.launcherActivityName,
        aliases = sanitizedApp.aliases
    )

    return sanitizedApp.takeIf {
        ZeaSafetyPolicy.evaluateUserAllowedApp(safetyApp).allowed
    }
}

private fun encodePrivateApps(
    privateApps: List<PrivateAppRecord>
): String {
    val appArray = JSONArray()

    privateApps.forEach { app ->
        val aliasArray = JSONArray()
        app.aliases.forEach(aliasArray::put)

        appArray.put(
            JSONObject()
                .put("displayName", app.displayName)
                .put("packageName", app.packageName)
                .put("launcherActivityName", app.launcherActivityName)
                .put("aliases", aliasArray)
        )
    }

    return JSONObject()
        .put("schemaVersion", ZeaStorageContract.PRIVATE_APPS_SCHEMA_VERSION)
        .put("apps", appArray)
        .toString()
}

private fun decodePrivateApps(json: String): List<PrivateAppRecord> {
    val root = JSONObject(json)
    val schemaVersion = root.optInt("schemaVersion", 0)

    if (schemaVersion != ZeaStorageContract.PRIVATE_APPS_SCHEMA_VERSION) {
        return emptyList()
    }

    val appArray = root.optJSONArray("apps") ?: return emptyList()
    val decodedApps = buildList {
        val itemCount = minOf(
            appArray.length(),
            ZeaStorageContract.MAX_PRIVATE_APPS
        )

        for (index in 0 until itemCount) {
            val appObject = appArray.optJSONObject(index) ?: continue
            val aliasArray = appObject.optJSONArray("aliases")
            val aliases = buildList {
                val aliasCount = minOf(
                    aliasArray?.length() ?: 0,
                    ZeaStorageContract.MAX_ALIAS_COUNT
                )

                for (aliasIndex in 0 until aliasCount) {
                    val alias = aliasArray
                        ?.optString(aliasIndex, "")
                        .orEmpty()
                        .trim()

                    if (alias.isNotBlank()) {
                        add(alias)
                    }
                }
            }

            add(
                PrivateAppRecord(
                    displayName = appObject.optString("displayName", ""),
                    packageName = appObject.optString("packageName", ""),
                    launcherActivityName = appObject.optString(
                        "launcherActivityName",
                        ""
                    ),
                    aliases = aliases
                )
            )
        }
    }

    return sanitizePrivateApps(decodedApps)
}

private fun encodeUserAllowedApps(
    userAllowedApps: List<UserAllowedApp>
): String {
    val appArray = JSONArray()

    userAllowedApps.forEach { app ->
        val aliasArray = JSONArray()
        app.aliases.forEach(aliasArray::put)

        appArray.put(
            JSONObject()
                .put("displayName", app.displayName)
                .put("packageName", app.packageName)
                .put("launcherActivityName", app.launcherActivityName)
                .put("aliases", aliasArray)
        )
    }

    return JSONObject()
        .put("schemaVersion", ZeaStorageContract.USER_ALLOWED_APPS_SCHEMA_VERSION)
        .put("apps", appArray)
        .toString()
}

private fun decodeUserAllowedApps(json: String): List<UserAllowedApp> {
    val root = JSONObject(json)
    val schemaVersion = root.optInt("schemaVersion", 0)

    if (schemaVersion != ZeaStorageContract.USER_ALLOWED_APPS_SCHEMA_VERSION) {
        return emptyList()
    }

    val appArray = root.optJSONArray("apps") ?: return emptyList()
    val decodedApps = buildList {
        val itemCount = minOf(
            appArray.length(),
            ZeaStorageContract.MAX_USER_ALLOWED_APPS
        )

        for (index in 0 until itemCount) {
            val appObject = appArray.optJSONObject(index) ?: continue
            val aliasArray = appObject.optJSONArray("aliases")
            val aliases = buildList {
                val aliasCount = minOf(
                    aliasArray?.length() ?: 0,
                    ZeaStorageContract.MAX_ALIAS_COUNT
                )

                for (aliasIndex in 0 until aliasCount) {
                    val alias = aliasArray
                        ?.optString(aliasIndex, "")
                        .orEmpty()
                        .trim()

                    if (alias.isNotBlank()) {
                        add(alias)
                    }
                }
            }

            add(
                UserAllowedApp(
                    displayName = appObject.optString("displayName", ""),
                    packageName = appObject.optString("packageName", ""),
                    launcherActivityName = appObject.optString(
                        "launcherActivityName",
                        ""
                    ),
                    aliases = aliases
                )
            )
        }
    }

    return sanitizeUserAllowedApps(decodedApps)
}

private fun normalizeSettingValue(value: String): String {
    return value
        .lowercase(Locale.ROOT)
        .split(Regex("[^\\p{L}\\p{N}]+"))
        .filter(String::isNotBlank)
        .joinToString(" ")
}
