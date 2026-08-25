# Phase 2 Closure Report — Zyro Security Hardening

**Date:** 2026-08-25
**Scope:** All 7 items from `PHASE2_REQUIREMENTS_LOCK.md`
**Status:** ~90% complete — all code implemented, built, and unit-tested. Remaining ~10% = on-device verification by the owner.

---

## Build & Test Evidence

| Check | Result |
|---|---|
| `:app:assembleDebug` | ✅ BUILD SUCCESSFUL |
| `:app:assembleRelease` | ✅ BUILD SUCCESSFUL |
| Phase 1 invariants (`ZeaPhase1InvariantsTest`) | ✅ 13/13 passing, 0 failures |
| Phase 2 policy tests (`ZeaPhase2LogicTest`) | ✅ 18/18 passing, 0 failures |
| Developer key in **release** APK dex scan | ✅ 0 matches — physically absent |
| Developer key in **debug** APK dex scan | ✅ present (dev-only, as intended) |

---

## Item 2.1 — PIN Brute-Force Protection ✅

**New file:** `ZeaPinLockout.kt`

- Persistent failed-attempt counter + cooldown deadline (survives force-stop/reboot — closing the app cannot bypass a cooldown)
- Escalating tiers: 5 failures → 30s, 10 → 2min, 15 → 5min (highest reached tier wins)
- Successful unlock (PIN **or** fingerprint) resets the counter
- Live countdown message + disabled submit button while locked out
- Wired into **all** PIN verification gates:
  - Global launch lock (`ZeaAppLock.kt` ENTER_PIN)
  - Hidden/Timed section gates (`ZeaAppsNavigation.kt`)
  - Settings PIN verify (`ZeaSettingsScreen.kt` CURRENT_PIN)
  - Emergency Recovery gate (new, `ZeaDiagnosticsScreen.kt`)
- `ZeaPinEntryScreen` gained a `submitEnabled` parameter (default `true` — backward compatible)

## Item 2.2 — Reversible PIN Storage Removed ✅

**File:** `ZeaStorage.kt`, `ZeaModels.kt`

- `encryptUserPin` / `decryptUserPin` / `revealSavedUserPin` / `canRevealSavedUserPin` **deleted**
- `EncryptedPinValue` model **deleted**
- `saveAdminPin` now removes `USER_PIN_ENCRYPTED`/`USER_PIN_IV` and deletes the AndroidKeyStore alias `zea_user_pin_key_v1`
- New migration `purgeReversiblePinStorage()` runs automatically after every successful PIN verification — leftover encrypted copies from older builds are wiped the first time the owner unlocks

## Item 2.3 — Hardcoded Developer Access Removed from Production ✅

**Approach:** source-set separation (stronger than a runtime flag).

- `ZeaDeveloperControls.kt` (key, keypad gate, controls screen) moved to `app/src/debug/` — debug builds keep full developer tooling
- New no-op stub at `app/src/release/` — release APKs contain **no developer key, no unlock comparison, no developer UI at all** (verified by dex string scan)
- Home menu entry "Developer Controls" is compiled out in release (`zeaDeveloperControlsEnabled` is a constant `false` in the release stub)

## Item 2.4 — Auto-Lock Settings ✅

**New file:** `ZeaAutoLock.kt` + settings UI in `ZeaSettingsScreen.kt`

- Six options, exactly as locked: Immediately, After 30s, After 1min, After 5min, When screen turns off, When Zyro leaves foreground
- Each option ships with a plain-language usability description in the picker dialog
- Policy engine: `ProcessLifecycleOwner` observer + dynamic `ACTION_SCREEN_OFF` receiver; timed options compare background elapsed time
- Relock clears the task-scoped session and bumps a Compose-observed lock epoch (`key(ZeaAutoLock.lockEpoch)` in `MainActivity.setContent`), recreating the gate at the PIN screen
- Never locks when no PIN exists or security is disabled; per-section gates keep their existing ON_STOP behavior

## Item 2.5 — Protection Health Dashboard ✅

**New file:** `ZeaProtectionHealth.kt` + Home card (`ZeaProtectionHealthCard`)

- 10 mode-aware health signals: Device Owner, App Lock engine, Usage Access, Notifications, Exact alarms, Registry integrity, Pending re-hide queue, Session monitor, Install lock, Launcher sync
- Live counts (protected / timed hidden / paused state)
- Healthy / issue headline, first issue highlighted, **Fix Now** button opens the matching system settings page (or full diagnostics when no direct fix exists)
- Dashboard entry points: Home card tap, Home menu → Diagnostics, Settings → Diagnostics & Recovery

## Item 2.6 — System Check / Diagnostics ✅

**New file:** `ZeaSystemCheck.kt` + UI in `ZeaDiagnosticsScreen.kt`

- 13 structured checks: registry readable, registry schema, Device Owner accessible, hidden states queryable, timed records valid, exact alarms, pending re-hide queue, App Lock service, Usage Access, monitor service, launcher resolvable, cache consistency, install-lock consistency
- Each failed check carries a plain-language explanation + repair:
  - Settings-opening repairs (Usage Access, Accessibility, Notifications, Exact Alarms)
  - Automatic repairs: registry rewrite through the standard sanitizer, re-hide queue reconciliation, catalog cache rebuild, install-lock reconciliation
- Auto re-runs after every repair to confirm the fix

## Item 2.7 — Emergency Recovery / Safe Mode ✅

**New file:** `ZeaEmergencyRecovery.kt` + UI in `ZeaDiagnosticsScreen.kt`

- 9 actions, exactly as locked: Unhide All Apps, Cancel All Timers, Reconcile Hidden State, Restore Launcher Visibility, Repair Registry, Clear Pending Re-hide Queue, Resume Protection, Pause Protection, Re-run System Check
- **PIN-gated** (with brute-force lockout from 2.1) — recovery can unhide apps, so it never opens without authentication
- Destructive actions require an explicit confirmation dialog
- Every action runs through the same transactional engines as normal flows (no safety bypass); results surface as user-readable messages and trigger an automatic System Check re-run

---

## Remaining ~10% — On-Device Tests (owner)

1. Enter 5 wrong PINs → confirm 30s lockout + countdown + disabled button; force-stop app → cooldown must persist
2. Save a PIN on the new build → confirm old encrypted PIN copy is wiped (no functional change visible; verify no regressions in unlock/change-PIN)
3. Release build: confirm no "Developer Controls" in Home menu; debug build: confirm it still works
4. Auto-Lock: try each option (Immediately / 30s / screen-off / leave-foreground) and confirm the PIN gate appears accordingly
5. Home health card: revoke Usage Access → card should show issue + Fix Now opens Usage Access settings
6. Diagnostics → Run System Check → confirm checks pass on a healthy device
7. Emergency Recovery: PIN gate works; Unhide All restores visibility; Resume Protection re-hides

## Known Notes

- Release signing still uses the debug keystore fallback (`keystore.properties` → `/tmp/debug.keystore`). Restore the real keystore before production distribution.
- Push to GitHub still requires the owner (current token is read-only, HTTP 403).
