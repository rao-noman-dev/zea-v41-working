package com.raomuhammadnoman.zea

import java.util.Calendar
import java.util.Locale

enum class ZeaHideMode {
    VISIBLE,
    HIDDEN,
    TIMED
}

enum class ZeaTimeUnit(val label: String) {
    SECONDS("Seconds"),
    MINUTES("Minutes"),
    HOURS("Hours"),
    DAYS("Days"),
    WEEKS("Weeks"),
    MONTHS("Months"),
    YEARS("Years")
}

/**
 * A confirmed Hide For Time request: when the app comes back and a
 * human-readable name for the chosen duration, such as "90 minutes".
 */
data class ZeaTimedHideRequest(
    val label: String,
    val endEpochMillis: Long
)

private const val ZEA_MAX_TIME_AMOUNT = 999_999L

/**
 * Turns a free-form amount plus unit into an absolute end time. Months and
 * years are calendar-based, so "1 Month" from 31 January lands on 28 February
 * instead of a fixed 30-day guess.
 */
fun zeaTimedHideRequest(
    amountText: String,
    unit: ZeaTimeUnit,
    nowEpochMillis: Long = System.currentTimeMillis()
): ZeaTimedHideRequest? {
    val amount = amountText.trim().toLongOrNull() ?: return null
    if (amount <= 0L || amount > ZEA_MAX_TIME_AMOUNT) {
        return null
    }

    val calendar = Calendar.getInstance()
    calendar.timeInMillis = nowEpochMillis
    calendar.add(
        when (unit) {
            ZeaTimeUnit.SECONDS -> Calendar.SECOND
            ZeaTimeUnit.MINUTES -> Calendar.MINUTE
            ZeaTimeUnit.HOURS -> Calendar.HOUR_OF_DAY
            ZeaTimeUnit.DAYS -> Calendar.DAY_OF_MONTH
            ZeaTimeUnit.WEEKS -> Calendar.WEEK_OF_YEAR
            ZeaTimeUnit.MONTHS -> Calendar.MONTH
            ZeaTimeUnit.YEARS -> Calendar.YEAR
        },
        amount.toInt()
    )

    val endEpochMillis = calendar.timeInMillis
    if (endEpochMillis <= nowEpochMillis) {
        return null
    }

    return ZeaTimedHideRequest(
        label = zeaDurationLabel(amount, unit),
        endEpochMillis = endEpochMillis
    )
}

fun zeaDurationLabel(amount: Long, unit: ZeaTimeUnit): String {
    val unitName = unit.name.lowercase(Locale.ROOT).removeSuffix("s")
    return if (amount == 1L) {
        "$amount $unitName"
    } else {
        "$amount ${unitName}s"
    }
}

data class ZeaManagedApp(
    val displayName: String,
    val packageName: String,
    val launcherActivityName: String,
    val systemApp: Boolean = false,
    val hideMode: ZeaHideMode = ZeaHideMode.VISIBLE,
    val hiddenUntilEpochMillis: Long = 0L,
    val manageable: Boolean = true,
    val blockedReason: String = ""
)

/** An app hidden until [hiddenUntilEpochMillis], after which Zea releases it. */
data class ZeaTimedHideRecord(
    val packageName: String,
    val displayName: String,
    val hiddenAtEpochMillis: Long,
    val hiddenUntilEpochMillis: Long
)
