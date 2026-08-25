# Zyro Phase 1 Stability - Implementation Handoff

## Delivery state

This package contains the Phase 1 stability implementation pass on top of the supplied `zea-v41-working` project.

**Build identity:** versionCode `108`, versionName `1.39-phase1-stability`.

This handoff means **source implementation is complete for the current Phase 1 scope**. It does **not** certify Phase 1 as officially closed. Physical-device build, runtime, interruption, reboot, private-session, scale, launcher, and evidence gates still have to pass on the target Vivo device before Phase 2 is allowed.

## Phase 1 implementation coverage

### 1. Private launch / re-hide reliability
- Private-session monitor now requests intent redelivery after OS process loss.
- Existing fail-closed screen-off, usage-access, monitor failure, and re-hide paths are preserved.

### 2. Service / runtime lifecycle
- Package lifecycle monitoring now includes package removal.
- Removed packages are pruned from private/timed/pending/session bookkeeping.

### 3. Transactional Hide / Unhide
- Hide, timed hide, and unhide now perform final state verification before returning success.
- Final verification checks registry, timer state, Device Owner hidden state, uninstall block, and a freshly reloaded app catalog.
- One bounded repair pass is allowed before a transaction fails safely.
- Failed registry removal now restores the original protected contract immediately instead of leaving a visible-but-managed split state.
- Timed-hide alarm scheduling failure triggers rollback instead of leaving an untimed hidden app.
- Standard/App-Lock mode now follows the same verified transaction contract: raw block state, timed deadline, registry persistence, rollback, and final visibility are checked before success.

### 4. Registry / DPM / UI reconciliation
- Added `ZeaPhase1Stability` as the shared reconciliation/verification coordinator.
- Stale package records and timer/private mismatches are reconciled conservatively.
- Global install-lock and pending re-hide state are included in the invariant check. Pending emergency re-hide state keeps the install lock closed even if the durable registry is temporarily empty.
- Standard/App-Lock storage is reconciled without promoting a temporary hide into a permanent block.

### 5. Pull-to-refresh
- Home, App Hub, All Apps, Hidden Apps, and Timed Hidden Apps use the same Phase 1 reconciliation pipeline.
- Duplicate refresh work is gated process-wide.
- Loading flags clear in `finally` paths.
- Valid selection/search state is retained and ghost selections are pruned after reload.

### 6. Count consistency
- Added executable count invariants shared by runtime verification and unit tests.
- Corrected CHK09 so protected count is `Hidden + Timed` and is compared with the durable private registry instead of the old unreliable third-party package enumeration.
- CHK09 reports `NOT VERIFIED` instead of a false RED when prerequisite data cannot be read.

### 7. Freeze / stuck resilience
- Policy/reconciliation work remains off the main UI thread.
- Bulk workflows retain progress callbacks and bounded retry passes.
- Durable journal failures stop the batch instead of continuing with unrecorded state.

### 8. Timed-hide rollback / restoration
- Alarm scheduling is verified.
- Expiry restoration gets an immediate retry and then a delayed recovery alarm when needed.
- Expired unresolved timers remain recoverable instead of silently becoming permanent hides.
- Interrupted timed batches now persist their original end time and label.
- Legacy timed journals without timing metadata are never resumed as permanent hides.

### 9. Restart / process death / reboot safety
- Active bulk journals remain durable and are re-read on Home return.
- Resume works in the same journal rather than abandon-then-create, eliminating the recovery crash gap.
- Timed alarms continue to re-arm after boot/package replacement.
- Private-session monitor uses `START_REDELIVER_INTENT` for OS process recovery.

### 10. Automated regression foundation
- Added pure Phase 1 invariant unit tests under `app/src/test`.
- Corrected the existing PowerShell CHK09 runtime harness.
- Removed persisted PIN automation from Phase 1 tooling; runtime scripts now stop at an explicit human unlock checkpoint when authentication is required.
- Added `phase1-evidence/tools/phase1-build-and-unit.ps1` for a deterministic Windows build/unit-test checkpoint.

## Durable batch contract

The batch journal now:
- supports `hide`, `unhide`, and `timed_hide` explicitly;
- stores timed-hide end time/label in schema v2 while decoding schema-v1 records safely;
- deduplicates target packages;
- persists each processed target idempotently;
- refuses to mark packages outside the target set;
- refuses `completed` closure until every target is durably processed;
- archives completion/abandon evidence before clearing the active slot;
- remains active when a state operation succeeds but progress cannot be journaled;
- blocks a new batch while an unresolved active journal exists.

## Source-level checks completed in this environment

- Pure Kotlin Phase 1 invariant compilation: PASS.
- Pure invariant execution: `PHASE1_PURE_INVARIANTS_GREEN`.
- All 13 added Phase-1 unit-test logic cases executed locally through a lightweight JUnit-compatible runner: `PHASE1_13_UNIT_LOGIC_CHECKS_GREEN`.
- Kotlin parser-oriented scan of all modified Kotlin files: no `expecting`, unclosed-string, unclosed-comment, or unexpected-token errors detected.

A full Android Gradle build is intentionally not claimed here. This sandbox does not have the project Android SDK, and the Gradle wrapper distribution is not cached; the configuration probe reached the wrapper bootstrap but could not fetch `gradle-9.3.1-bin.zip` because outbound network access is unavailable. The authoritative Android build should therefore run on the Windows development machine.

## First Windows validation checkpoint

From the project root:

```powershell
powershell -ExecutionPolicy Bypass -File .\phase1-evidence\tools\phase1-build-and-unit.ps1
```

Equivalent direct command:

```powershell
.\gradlew.bat :app:testDebugUnitTest :app:assembleDebug --stacktrace
```

Expected APK after a successful build:

```text
app\build\outputs\apk\debug\app-debug.apk
```

Do not install/reset/clear the target device automatically as part of this script. Installation and Device Owner preservation should remain an explicit controlled step.

## Runtime verification checkpoint after the build is on the device

Run the corrected count gate from an unlocked Zyro Apps Hub state:

```powershell
powershell -ExecutionPolicy Bypass -File .\phase1-evidence\reg1\reg1-runner.ps1 -Only CHK09
```

Then continue the master Phase 1 runtime matrix: interrupted batch recovery, 30 private-session app runs, five-screen refresh, count transitions, 10/50/100/200 scale, timed-hide lifecycle, process death/restart/reboot, launcher state, and the final execution ledger.

## Release gate

**Phase 2 remains blocked.**

The final status may become `PHASE 1 - 100% OFFICIALLY COMPLETE` only after the new build and every mandatory runtime/evidence gate pass on the target device with no unresolved critical/high Phase 1 blocker.
