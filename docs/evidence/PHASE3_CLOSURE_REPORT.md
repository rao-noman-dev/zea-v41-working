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


---

# Addendum — FINAL Phase 3 Blocker Correction Pass (2026-08-26)

An independent review found Phase 1/2 code substantially complete but identified
remaining Phase 3 implementation/state-management blockers. All were fixed and
verified in this pass.

## Blockers fixed

### 1. Profile ownership (ZeaProfiles.kt)
- (A) Timed membership now takes precedence: an app listed in both hidden and
  timed collections is applied once, as TIMED — never both modes at once.
- (B) Hidden-before + profile-timed: deactivation now actively RESTORES the
  permanent hide (via pure `zeaProfileDeactivatePlan`), instead of leaving the
  profile timer alive to expose the app later.
- (C) Timed-before + profile-hidden: deactivation restores the ORIGINAL
  deadline while still in the future; expired deadlines restore visible
  (leaving the app hidden past its own deadline would be dishonest).
- (D) Ownership is claimed ONLY when the member actually reaches the applied
  state — a failed application no longer fabricates a restoration claim.
- (E) `updateProfile` never overwrites the persisted ownership snapshot:
  editing membership of an active profile cannot orphan recovery metadata.
- (F) Delete of an active profile remains blocked (ownership must be consumed
  via deactivate first) — verified, kept.
- (G) Failed restores RETAIN their ownership so deactivation can be retried;
  ownership is consumed only for clean restores or conscious releases.
- (H) Repeated activation is idempotent: existing ownership entries are never
  overwritten by a re-activation (first claim = restoration source of truth).

### 2. Schedule ownership conflicts (ZeaSchedules.kt, ZeaTimedHide.kt)
- (2A) Timer expiry yields to schedule ownership (done in the previous pass;
  re-verified).
- (2B) `claimTarget` first-writer-wins helper: ownership is captured BEFORE the
  hide and only when no prior claim exists.
- (2C) Overlapping-schedule transfer now carries the ORIGINAL user state via
  `zeaScheduleMergedOwnership` (earliest `claimedAt` claim wins).

### 3. END-phase validation
- `zeaScheduleFirePlan` now validates an END broadcast against the computed
  window: an EARLY end (window still open) keeps protection and re-arms the
  true end; a STALE/VALID end releases ownership with prior-state restore.
  Recurring schedules transition cleanly into the next cycle (tested).
- END UNHIDE paths (fire + pause/delete reconcile) skip apps whose state was
  independently changed mid-window (e.g. user re-timed) — manual state is
  never destroyed.

### 4. Schedule resume
- Existing fire-plan skip logic verified: a delayed START inside yesterday's
  window hides for the NEXT cycle; validation comment documented.

### 5. Manual override semantics
- `ZeaAppHideService.unhideApp` success now records a manual override per
  owning schedule+package; active-window fire plans SKIP overridden packages
  for the rest of the cycle (manual action wins). Overrides are cleared when
  ownership ends (END / pause / delete / release reconcile).

### 6. Group/batch journal truth (ZeaGroups.kt, ZeaBulkSelection.kt)
- The three group batch ops share `runGroupBatch`; `ZeaGroupBatchResult` gained
  `journalClosed`. `BATCH_COMPLETED` is recorded ONLY when the durable journal
  actually closed — an open journal is logged as a recoverable RECOVERY event
  and surfaced in the UI ("batch stays recoverable"), never as success.
- `runBulkHide`/`runBulkUnhide` only close-recorded snapshots when the journal
  closed.

### 7. Consistent stale-reference cleanup
- `ZeaGroups.pruneStaleMembers` + `ZeaProfiles.pruneStaleMembership` drop
  ghost (uninstalled) members across ALL operation types; both are wired into
  the screen refresh paths. Ownership snapshots are never pruned (they are
  restoration records, not membership). Schedule target pruning verified.

### 8. Bulk undo
- `ZeaUndo` gained bulk snapshots (`recordBulk`/`performBulkUndo`/`canUndoBulk`)
  — one entry per batch member; per-app single-slot undo is suppressed during
  bulk operations so member N no longer overwrites member N-1.
- The bulk outcome dialogs (All Apps hide, Hidden list unhide) now offer an
  Undo action when a fresh bulk snapshot exists.

### 9. Undo exact-state audit (ZeaUndo.kt, ZeaAppHideService.kt)
- `ZeaUndoEntry` gained `appliedTimedEndEpochMillis`; `zeaUndoIsSafe` compares
  CURRENT state against the APPLIED state (mode + exact newly-applied timer
  deadline), not the previous one.
- (9A) Re-time 14:00→18:00: undo is valid only while the current deadline is
  18:00 and restores the original 14:00 (REARM_TIMER).
- (9B) Permanently hidden → timed: undo converts back to the PERMANENT hide
  (CONVERT_TO_PERMANENT).
- (9C) Later subsystem changes make older undo snapshots unsafe — reversal is
  refused and reported as skipped.

### 10. Search privacy (ZeaSearch.kt)
- Hidden/timed identity is REDACTED only while the session is unauthenticated
  against the Zyro lock (`zeaSearchMayRevealSensitive`) — authenticated
  sessions keep hidden apps searchable, and with the lock off there is no
  privacy boundary. No permanent omission.

### 11. App Details completion (ZeaAppDetailsScreen.kt)
- Added: app icon, version, "Hidden since", uninstall-protection state, last
  ZYRO action, Profile relationship, Schedule relationship (direct + via
  group), and Add/Remove from Group actions.

### 12. Filter combinations
- `filterAndSortApps` AND-combination verified (status axis x attribute axis x
  query), covered by new deterministic tests.

### 13. Tests
- 18 new deterministic tests in `ZeaPhase3BlockerLogicTest` covering the
  blockers above; one stale closure-pass expectation (END-while-open asserted
  UNHIDE — the actual bug) was corrected.
- Full suite: 128 tests, 0 failures, 0 errors.
- `:app:assembleDebug` and `:app:assembleRelease`: BUILD SUCCESSFUL.

## Remaining device-dependent items (unchanged)
Same as the main report: boot recovery, alarm delivery, device-owner
transactions, uninstall blocking, and permission flows require a physical
device/emulator. Phase 4 has NOT been started, per directive.
