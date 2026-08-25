# ZYRO — FINAL PHASE 1 + PHASE 2 + PHASE 3 CLOSURE PASS REPORT

Date: 2026-08-26
Commit range: b0bddd4..HEAD (closure pass)

## Verification results

- Unit tests: **110 total, 0 failures** (`:app:testDebugUnitTest`)
- Debug build: `assembleDebug` BUILD SUCCESSFUL
- Release build: `assembleRelease` BUILD SUCCESSFUL (externalRelease signing config resolves)
- Signing hygiene: no keystore in VCS (`git ls-files | grep keystore` → only `keystore.properties.example` template); `.gitignore` covers `keystore.properties`, `*.keystore`, `*.jks`

## Issues fixed in this pass

### 1. Schedule engine — cross-midnight active-window bug (RED → GREEN)
`zeaScheduleActiveWindow` only evaluated TODAY's start, so a 22:00→06:00
window at 03:00 the next day reported "not active". This silently broke
missed-window recovery, overlap ownership, and delayed-start detection.
Now evaluates today AND yesterday candidate starts (DST-safe Calendar
stepping) and returns the end of whichever window contains `now`.

### 2. Delayed START reconciliation (RED → GREEN)
A START alarm that fired after its own window had already ended previously
hid the app until the NEXT cycle's end (potentially ~23 hours). New pure
decision function `zeaScheduleFirePlan` (ZeaScheduleEngine.kt) classifies
every fire as HIDE / SKIP / EXPIRED; the runtime (`onFire`) skips stale
starts and re-arms the next cycle instead.

### 3. Schedule END prior-state ownership (RED → GREEN)
END previously unhid every target blindly, destroying manual state captured
before the schedule. New per-schedule ownership ledger (persisted in
`app_schedule_ownership_v1`) captures `previousMode` + `previousTimedEnd`
at START; END consults the pure `zeaScheduleEndPlan` and:
- UNHIDE when the schedule owned the state,
- leaves pre-hidden apps hidden (RESTORE_HIDDEN),
- re-arms the ORIGINAL timer end for pre-timed apps (RESTORE_TIMED),
- skips packages still owned by another active schedule (overlap).

### 4. Overlapping schedules (partially broken → fixed)
Overlap detection relied on the broken cross-midnight active-window check;
fixed by (1). Ownership capture is first-writer-wins per cycle so a second
overlapping schedule never clobbers the original manual state.

### 5. Honest schedule activation status (RED → GREEN)
`createSchedule`/`updateSchedule` now validate via `zeaScheduleIsValid`
(rejects blank names, out-of-range minutes, empty targets, CUSTOM_DAYS with
zero selected days) and report failure when the required alarms cannot be
armed (rearmWithResult) instead of silently pretending success.

### 6. All Apps coherent catalog (RED → GREEN)
The screen pre-filtered the catalog to VISIBLE apps, making the Hidden/Timed
filters permanently empty. The full reconciled catalog now feeds the screen;
filters/sorts operate on top. Name sort is now always alphabetical even when
a recency filter reordered the input list.

### 7. Safe Undo exact-state ownership + bulk undo (RED → GREEN)
Undo snapshots now record the exact APPLIED state (`appliedMode`,
`appliedTimedEndEpochMillis`). `zeaUndoIsSafe` refuses reversal when another
operation changed the mode or re-armed a different timer end. Bulk undo
(`recordBulk`/`performBulkUndo`) reverses each package independently with
refused/failed/reversed reporting.

### 8. App Details completeness (partial → complete)
Remaining time is only shown for TIMED state and formatted as h/m; favorite
state uses the shared favorites identity; Hide/Unhide actions are guarded by
the current applied state; "Launch app" routes through the verified
`ZeaAppLauncher.launchAppWithTimeout` pipeline instead of a raw intent.

### 9. Global Search privacy gate (RED → GREEN)
Apps registered in the private-app registry are excluded from global search
results regardless of query.

### 10. Group batch truth + stale cleanup (verified already correct)
Group hide/unhide/timed operations run inside the durable Phase-1 batch
journal; uninstalled members are pruned from the group and reported as
failed with "No longer installed"; schedule stale targets are pruned at
rearm/fire time. No code change required.

## New tests (31 added, ZeaPhase3ClosureLogicTest)
Cross-midnight active window (5), delayed-start fire plans (6), END
prior-state decisions (6), schedule validation (5), undo applied-state
safety gate (5), name-sort-after-recency-filter (1), plus 3 updated legacy
expectations in ZeaPhase3LogicTest.

## Not started (per directive)
Phase 4 and Phase 5 remain untouched.
