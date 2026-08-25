# PHASE 2 — REQUIREMENTS LOCKED (Security)

> **Status:** LOCKED — No changes allowed without explicit user approval  
> **Date:** 2026-08-25  
> **Source:** ZYRO Professional App Improvement & Feature Roadmap (lines 310–515)  
> **Prerequisite:** Phase 1 closed (currently 90% closed, on-device tests pending)

---

## Phase 2 Scope

Phase 2 is **Security + Recovery**. It hardens authentication, removes security debt, adds health visibility, and provides safe recovery paths. No new app-management features (Groups, Profiles, etc.) are in Phase 2 — those are Phase 3.

---

## 2.1 — PIN Brute-Force Protection (P1)

### Requirement
Implement lockout policy that makes unlimited PIN guessing impractical while remaining user-friendly.

### Escalation Tiers (LOCKED)

| Failed Attempts | Cooldown |
|-----------------|----------|
| 5 | 30 seconds |
| 10 | 2 minutes |
| 15 | 5 minutes |

### Behavior Rules (LOCKED)

1. Count consecutive failed attempts.
2. On reaching 5 failed attempts → enforce 30-second cooldown.
3. On reaching 10 total failed attempts → enforce 2-minute cooldown.
4. On reaching 15 total failed attempts → enforce 5-minute cooldown.
5. Successful PIN entry resets failed-attempt counter to 0.
6. Cooldown is persisted so closing/reopening the app does not bypass it.
7. During cooldown, PIN entry UI is disabled or shows remaining time.
8. After cooldown expires, user may try again.

### Persistence Requirements (LOCKED)

Store in encrypted/secure local storage:
- `failedAttemptCount`
- `cooldownUntilEpochMillis`
- `lastSuccessfulUnlockEpochMillis` (optional but recommended)

**Do not** expose sensitive details in logs or UI.

### Acceptance Criteria (LOCKED)

- [ ] 5 wrong PINs → 30s cooldown enforced
- [ ] 10 wrong PINs → 2min cooldown enforced
- [ ] 15 wrong PINs → 5min cooldown enforced
- [ ] Correct PIN resets counter
- [ ] Cooldown survives app restart
- [ ] No bypass via process kill or reinstall (state persists)
- [ ] UI clearly communicates cooldown state

---

## 2.2 — Remove Reversible PIN Storage (P1)

### Requirement
The app must never store the user's actual PIN in a reversible or recoverable form.

### Design (LOCKED)

```
User PIN
→ Random Salt (per user)
→ Strong KDF (PBKDF2 / Argon2 / scrypt)
→ Stored Verifier Only (hash + salt)
```

### Rules (LOCKED)

1. PIN is never stored in plaintext.
2. PIN is never stored encrypted with a reversible key.
3. Only the KDF output (verifier) + salt is persisted.
4. PIN verification is done by recomputing KDF and comparing verifier.
5. If PIN recovery is needed, use PIN reset workflow, Device Owner/admin recovery, or secure re-authentication — never display the original PIN.

### Acceptance Criteria (LOCKED)

- [ ] No plaintext PIN found in storage, logs, or memory dumps
- [ ] Salt is random per user and stored alongside verifier
- [ ] KDF parameters meet current security standards
- [ ] PIN change requires old PIN verification before update
- [ ] No code path can reconstruct the original PIN

---

## 2.3 — Remove Hardcoded Developer Access From Production (P1)

### Requirement
Developer/admin backdoors must not exist in release builds.

### Design (LOCKED)

Use Android build variants / compile-time flags:

```text
Debug build:
    Developer Controls available (sourceSet debug)

Release build:
    Developer Controls excluded (sourceSet release)
```

### Rules (LOCKED)

1. No hardcoded secret keys, backdoor PINs, or hidden admin menus in release APK.
2. Use `BuildConfig.DEBUG` or custom `buildConfigField` to gate developer features.
3. Move any developer-only code into `src/debug/` or wrap with `if (BuildConfig.DEBUG)`.
4. Release build must compile successfully with zero developer controls present.
5. Any existing hardcoded developer access must be removed or gated.

### Acceptance Criteria (LOCKED)

- [ ] Release APK does not contain developer menu/backdoor code
- [ ] Debug APK retains developer controls for testing
- [ ] `BuildConfig` flags correctly gate the feature
- [ ] No hardcoded secrets embedded in release resources or code

---

## 2.4 — Auto-Lock Settings (P1)

### Requirement
User can configure when the app re-locks itself.

### Options (LOCKED — exactly these)

- Immediately
- After 30 seconds
- After 1 minute
- After 5 minutes
- When screen turns off
- When Zyro leaves foreground

### Rules (LOCKED)

1. Setting is user-configurable from Settings.
2. Each option clearly explains its usability impact.
3. Auto-lock applies to the main app gate and any sensitive screens.
4. Lock state is enforced on next app launch or when timer expires.
5. Background timer does not drain battery excessively.

### Acceptance Criteria (LOCKED)

- [ ] User can select each auto-lock option from Settings
- [ ] Selected option is persisted and applied
- [ ] "Immediately" locks as soon as app loses focus or user navigates away
- [ ] "After X" locks after specified inactivity
- [ ] "When screen turns off" locks on ACTION_SCREEN_OFF
- [ ] "When Zyro leaves foreground" locks when app goes to background
- [ ] Setting survives app restart

---

## 2.5 — Protection Health Dashboard (P1 — Flagship Feature)

### Requirement
Home screen must show a compact, actionable health card so user instantly knows if protection is working.

### Health Signals (LOCKED — all 10 required)

1. Device Owner active
2. Accessibility/App Lock service
3. Usage Access
4. Notification permission
5. Exact alarm capability
6. Registry integrity
7. Pending re-hide status
8. Private session monitor availability
9. Install lock state
10. Launcher state sync

### UI Requirements (LOCKED)

**Healthy state:**
```
Protection Status

✓ Device Owner
✓ App Lock Engine
✓ Usage Access
✓ Notifications
✓ Timed Hide Engine
✓ Launcher Sync

150 Apps Protected
4 Timed
Protection Active
```

**Unhealthy state:**
```
Action Required

Usage Access is disabled.
Private app monitoring may not work.

[ Fix Now ]
```

### Rules (LOCKED)

1. Dashboard appears on Home screen.
2. Each signal shows green check or red warning.
3. If any signal fails, card switches to "Action Required" state.
4. "Fix Now" button deep-links to the relevant Android Settings screen.
5. Counts (protected apps, timed apps) are accurate and live.
6. Dashboard updates automatically when app resumes or refreshes.

### Acceptance Criteria (LOCKED)

- [ ] All 10 health signals evaluated correctly
- [ ] Healthy state shows all green checks and accurate counts
- [ ] Unhealthy state shows specific failure and "Fix Now" action
- [ ] "Fix Now" opens correct system settings page
- [ ] Dashboard refreshes on app resume
- [ ] No false positives or false negatives

---

## 2.6 — System Check / Diagnostics (P1)

### Requirement
Settings → Diagnostics → Run System Check provides a structured self-test.

### Checks (LOCKED — all 13 required)

1. Registry readable
2. Registry schema valid
3. Device Owner accessible
4. Hidden states queryable
5. Timed records valid
6. Exact alarms available
7. Pending re-hide queue empty
8. App Lock service active
9. Usage Access active
10. Monitor service available
11. Launcher resolvable
12. Cache consistency
13. Protection install lock consistency

### Result Format (LOCKED)

```
System Check

11 checks passed
1 issue found

Private Session Monitor
Service unavailable

[ Repair ]
```

### Rules (LOCKED)

1. Each check returns pass/fail with a human-readable explanation.
2. Failed checks offer a "Repair" action where safe.
3. Repair actions are not destructive without confirmation.
4. Results are shown in a dedicated diagnostics screen.
5. System Check can be re-run at any time.

### Acceptance Criteria (LOCKED)

- [ ] All 13 checks implemented
- [ ] Each check produces accurate pass/fail
- [ ] Failure shows specific reason
- [ ] "Repair" attempts safe recovery for applicable failures
- [ ] Results persist across screen rotation
- [ ] Re-run works correctly

---

## 2.7 — Emergency Recovery / Safe Mode (P1)

### Requirement
User can recover from state mismatches without ADB or reinstalling.

### Recovery Actions (LOCKED — all 9 required)

1. Unhide All Apps
2. Cancel All Timers
3. Reconcile Hidden State
4. Restore Launcher Visibility
5. Repair Registry
6. Clear Pending Re-hide Queue safely
7. Resume Protection
8. Pause Protection
9. Re-run System Check

### Security Rule (LOCKED)

Require Zyro PIN before any destructive recovery operation.

### Rules (LOCKED)

1. Emergency Recovery is accessible from Settings or a dedicated Safe Mode entry.
2. PIN verification required before executing any recovery action.
3. Each action shows a confirmation dialog explaining what will happen.
4. Actions are executed with the same transactional verification as normal operations.
5. After recovery, System Check is automatically re-run.
6. User sees a summary of what was done.

### Acceptance Criteria (LOCKED)

- [ ] All 9 recovery actions implemented
- [ ] PIN gate enforced before destructive operations
- [ ] Each action confirms with user before execution
- [ ] Recovery does not leave app in worse state
- [ ] System Check runs automatically after recovery
- [ ] Summary report shown to user

---

## Phase 2 Exclusions (Explicitly Out of Scope)

These are **NOT** in Phase 2 (they belong to Phase 3+):

- App Groups / Collections
- Privacy Profiles / Modes
- Scheduled / Recurring Hiding
- Improved Time Hidden Apps screen
- Global Search
- Professional Filters
- Activity / Security History
- Undo
- App Details Screen
- Favorites / Pinned Apps
- Recently Managed Apps
- Home Dashboard Redesign (beyond Health card)
- Settings Center Redesign
- Dark Mode
- Empty/Loading States polish
- Micro-interactions
- Confirmation strategy polish
- Failure explanation polish
- Notifications polish
- Onboarding redesign
- Help & Support
- Privacy Center
- Accessibility improvements
- Branding consistency cleanup
- Error architecture standardization
- Local encrypted backup
- Performance optimization
- QA Matrix / Regression suite expansion

---

## Phase 2 Dependencies

| Dependency | Status | Notes |
|------------|--------|-------|
| Phase 1 closed | 90% (pending on-device tests) | Must reach 100% before Phase 2 release |
| Existing PIN/auth system | Present | `ZeaAppLock.kt`, `ZeaLockedAppsGate` |
| Device Owner stack | Present | `ZeaDeviceOwnerController` |
| Private session monitor | Present | `ZeaPrivateSessionMonitorService` |
| Registry/storage layer | Present | `ZeaStorage.kt`, `ZeaAppCatalog` |
| Build system | Working | Gradle 9.3.1, Android SDK 36 |

---

## Phase 2 Deliverables (LOCKED)

1. **Code:** All 7 items implemented and verified
2. **Build:** Debug + Release APKs compile successfully
3. **Tests:** Unit tests for new logic (brute-force, KDF, auto-lock, health checks)
4. **Docs:** `PHASE2_IMPLEMENTATION_NOTES.md` (created during implementation)
5. **Closure:** `PHASE2_CLOSURE_REPORT.md` (after on-device tests)

---

## Sign-Off

**Locked by:** OpenHands AI Agent  
**Date:** 2026-08-25  
**User approval required:** YES — Please review and confirm these Phase 2 requirements are complete and correct.

**To unlock changes:** User must explicitly say "Phase 2 requirements change" or "add/remove Phase 2 item".

---

**End of Phase 2 Requirements Lock**
