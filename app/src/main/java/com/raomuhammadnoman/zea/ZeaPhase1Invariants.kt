package com.raomuhammadnoman.zea

/**
 * Pure Phase-1 invariants shared by runtime verification and local unit tests.
 * Keeping these rules free of Android dependencies makes count semantics
 * executable in CI instead of existing only as comments in a device harness.
 */
internal data class ZeaPhase1CountSnapshot(
    val visible: Int,
    val hidden: Int,
    val timed: Int,
    val registryProtected: Int,
    val timerRecords: Int
) {
    val catalogTotal: Int
        get() = visible + hidden + timed
}

internal fun zeaPhase1CountIssues(snapshot: ZeaPhase1CountSnapshot): List<String> {
    val issues = mutableListOf<String>()
    if (
        snapshot.visible < 0 ||
        snapshot.hidden < 0 ||
        snapshot.timed < 0 ||
        snapshot.registryProtected < 0 ||
        snapshot.timerRecords < 0
    ) {
        issues.add("count values must never be negative")
        return issues
    }

    val catalogProtected = snapshot.hidden + snapshot.timed
    if (catalogProtected != snapshot.registryProtected) {
        issues.add(
            "catalog protected count=$catalogProtected registry count=${snapshot.registryProtected}"
        )
    }
    if (snapshot.timed != snapshot.timerRecords) {
        issues.add(
            "catalog timed count=${snapshot.timed} timer count=${snapshot.timerRecords}"
        )
    }
    if (snapshot.timerRecords > snapshot.registryProtected) {
        issues.add(
            "timer count=${snapshot.timerRecords} exceeds protected registry count=${snapshot.registryProtected}"
        )
    }
    return issues.distinct()
}
