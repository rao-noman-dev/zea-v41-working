package com.raomuhammadnoman.zea

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner

/**
 * Phase 2 (P1) - configurable auto-lock.
 *
 * Controls when the global launch gate re-locks after a successful unlock.
 * The per-section gates (Hidden Apps / Timed) already re-lock on ON_STOP and
 * are intentionally unaffected; this policy governs the GLOBAL gate only.
 *
 * Options and their usability impact:
 *  - IMMEDIATELY: re-locks the moment Zyro loses focus (even a dialog or a
 *    transient overlay). Most secure, most interruptions.
 *  - AFTER_30_SECONDS / AFTER_1_MINUTE / AFTER_5_MINUTES: returning within
 *    the window skips the PIN; longer background stays require it.
 *  - SCREEN_OFF: re-locks when the device screen turns off, even if Zyro is
 *    still the foreground app.
 *  - LEAVES_FOREGROUND: re-locks whenever Zyro is fully backgrounded.
 */
enum class ZeaAutoLockOption(
    val storageKey: String,
    val label: String,
    val description: String
) {
    IMMEDIATELY(
        storageKey = "immediately",
        label = "Immediately",
        description = "Locks the moment Zyro loses focus. Maximum protection, but any interruption asks for the PIN again."
    ),
    AFTER_30_SECONDS(
        storageKey = "after_30_seconds",
        label = "After 30 seconds",
        description = "Quick app switches stay unlocked; after 30 seconds away the PIN is required again."
    ),
    AFTER_1_MINUTE(
        storageKey = "after_1_minute",
        label = "After 1 minute",
        description = "A comfortable window for short breaks before the PIN is required again."
    ),
    AFTER_5_MINUTES(
        storageKey = "after_5_minutes",
        label = "After 5 minutes",
        description = "The most relaxed option: the PIN is only required after five minutes away."
    ),
    SCREEN_OFF(
        storageKey = "screen_off",
        label = "When screen turns off",
        description = "Locks as soon as the device screen turns off, even if Zyro was open."
    ),
    LEAVES_FOREGROUND(
        storageKey = "leaves_foreground",
        label = "When Zyro leaves foreground",
        description = "Locks whenever you switch away from Zyro to another app or the home screen."
    );

    companion object {
        fun fromStorageKey(key: String?): ZeaAutoLockOption =
            entries.firstOrNull { option -> option.storageKey == key } ?: DEFAULT

        val DEFAULT: ZeaAutoLockOption = LEAVES_FOREGROUND
    }
}

/**
 * Milliseconds of background time after which a timed option re-locks.
 * Returns null for event-driven options (immediate / screen-off / leaves
 * foreground) which are handled by lifecycle or broadcast events instead.
 */
fun zeaAutoLockTimeoutMillis(option: ZeaAutoLockOption): Long? = when (option) {
    ZeaAutoLockOption.AFTER_30_SECONDS -> 30_000L
    ZeaAutoLockOption.AFTER_1_MINUTE -> 60_000L
    ZeaAutoLockOption.AFTER_5_MINUTES -> 5L * 60_000L
    ZeaAutoLockOption.IMMEDIATELY,
    ZeaAutoLockOption.SCREEN_OFF,
    ZeaAutoLockOption.LEAVES_FOREGROUND -> null
}

/**
 * Pure foreground-return policy. [armed] is set when the app backgrounds
 * under an event-driven option; timed options compare the elapsed background
 * time against their timeout. Kept side-effect free for unit tests.
 */
fun zeaAutoLockShouldRelock(
    option: ZeaAutoLockOption,
    armed: Boolean,
    backgroundedAtEpochMillis: Long,
    nowEpochMillis: Long
): Boolean {
    if (armed) return true
    val timeout = zeaAutoLockTimeoutMillis(option) ?: return false
    if (backgroundedAtEpochMillis <= 0L) return false
    return nowEpochMillis - backgroundedAtEpochMillis >= timeout
}

object ZeaAutoLock {
    private const val PREFS_NAME = "zyro_auto_lock_v1"
    private const val KEY_OPTION = "auto_lock_option"

    /** Compose-observed selected option; persisted across restarts. */
    var option by mutableStateOf(ZeaAutoLockOption.DEFAULT)
        private set

    /**
     * Compose-observed lock epoch. Every auto-lock trigger increments it,
     * which recreates the global gate and forces a fresh PIN check. Read by
     * MainActivity's setContent via key().
     */
    var lockEpoch by mutableIntStateOf(0)
        private set

    /** Set when an event-driven option arms a relock while backgrounded. */
    private var relockArmed = false

    /** Background timestamp used by the timed options. */
    private var backgroundedAtEpochMillis = 0L

    private fun prefs(context: Context): SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun load(context: Context) {
        option = ZeaAutoLockOption.fromStorageKey(
            prefs(context).getString(KEY_OPTION, null)
        )
    }

    fun setOption(context: Context, newOption: ZeaAutoLockOption) {
        option = newOption
        prefs(context).edit().putString(KEY_OPTION, newOption.storageKey).apply()
        // A policy change discards any stale arming/background bookkeeping so
        // the new option starts from a clean slate.
        relockArmed = false
        backgroundedAtEpochMillis = 0L
    }

    /**
     * Performs the actual relock: clears the task-scoped session grant and
     * bumps the observed epoch so the gate recomposes into ENTER_PIN. Never
     * locks when no PIN exists or when the master security switch is off -
     * both states intentionally run without authentication.
     */
    fun performRelock(context: Context) {
        if (!ZeaSecurityState.securityEnabled) return
        if (!isAdminPinSet(context)) return
        if (zeaGateSessionUnlockedTaskId == null) return
        zeaGateSessionUnlockedTaskId = null
        lockEpoch++
    }

    /**
     * Process-lifecycle observer driving the event and timed policies.
     * SCREEN_OFF is handled separately via a broadcast receiver because the
     * process stays foregrounded when the screen turns off.
     */
    val processObserver = object : DefaultLifecycleObserver {
        override fun onPause(owner: LifecycleOwner) {
            if (option == ZeaAutoLockOption.IMMEDIATELY) {
                relockArmed = true
            }
        }

        override fun onStop(owner: LifecycleOwner) {
            backgroundedAtEpochMillis = System.currentTimeMillis()
            when (option) {
                ZeaAutoLockOption.IMMEDIATELY,
                ZeaAutoLockOption.LEAVES_FOREGROUND -> relockArmed = true
                else -> Unit
            }
        }

        override fun onStart(owner: LifecycleOwner) {
            val context = zeaAutoLockAppContext ?: return
            val shouldRelock = zeaAutoLockShouldRelock(
                option = option,
                armed = relockArmed,
                backgroundedAtEpochMillis = backgroundedAtEpochMillis,
                nowEpochMillis = System.currentTimeMillis()
            )
            relockArmed = false
            backgroundedAtEpochMillis = 0L
            if (shouldRelock) {
                performRelock(context)
            }
        }
    }

    /** Application context retained while the observer is registered. */
    private var zeaAutoLockAppContext: Context? = null

    fun attach(context: Context) {
        zeaAutoLockAppContext = context.applicationContext
    }

    /** Screen-off events arrive here from MainActivity's dynamic receiver. */
    fun onScreenOff(context: Context) {
        if (option == ZeaAutoLockOption.SCREEN_OFF) {
            performRelock(context)
        }
    }
}
