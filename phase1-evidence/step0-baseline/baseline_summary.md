# ZYRO Phase-1 — Step 0 Baseline Evidence Summary (Step 0A + 0C)

> Captured: 2026-08-24 ~08:40–09:05 PKT · Device: vivo V2352 (`10FE7N04C40001Y`) · Build under test: v107 `1.38-ptr-fix` (release, NOT debuggable → run-as unavailable; registry files not directly pullable)
> All raw artifacts: `phase1-evidence/step0-baseline/raw/` · Ground-truth lists: this folder

## 1. Identity & Platform State

| Artifact | Value | Source | Color |
|---|---|---|---|
| versionCode / versionName | **107 / 1.38-ptr-fix** (targetSdk 36, minSdk 26) | dumpsys package | GREEN |
| Installed | firstInstall 2026-08-23 09:39, lastUpdate 2026-08-24 06:14 | dumpsys package | GREEN |
| Build flags | release (`pkgFlags=[HAS_CODE ALLOW_CLEAR_USER_DATA]`, not debuggable) | dumpsys package | GREEN |
| Package id | com.raomuhammadnoman.zea · uid 10338 | dumpsys package | GREEN |
| Device Owner | ACTIVE — admin `com.raomuhammadnoman.zea/.ZeaDeviceAdminReceiver`, provisioningState 3, testOnlyAdmin=false | dumpsys device_policy | GREEN |
| Launcher identity | com.android.launcher3 / com.bbk.launcher2.Launcher (vivo FunTouch) | resolve-activity HOME | GREEN |
| Third-party packages on device | 171 · full list incl. system captured | pm list packages ×2 | GREEN |

## 2. Ground Truth vs Zyro UI (the big one)

**Verified TRUE state at capture time: NOTHING is hidden.**

| Surface | Observation | Verdict |
|---|---|---|
| DPM enforcement per package (8 sampled from old hidden set) | `installed=true hidden=false` (AOSP flag FALSE) | Not hidden |
| Drawer SURFACE evidence (uiautomator of actual drawer, first page alphabetical) | **8 Ball Pool, 9GAG, AccuWeather, Adobe Scan, Agoda, Airbnb … all PRESENT** | Not hidden |
| Zyro App Hub counts | All Apps **204** · Hidden **0** · Timed **0** | Consistent |
| Zyro Hidden screen | "0 apps / No apps are hidden." | Consistent |
| Zyro Timed screen | "0 apps" (after PIN) | Consistent |
| Zyro All Apps rows | every sampled row labeled "Visible" | Consistent |

⚠️ **STALE DMS POLICY RECORDS**: `dumpsys device_policy` "Local Policies" contains **200 × `applicationHidden` PackagePolicyKeys — all with `BooleanPolicyValue mValue= false`** (see `dpm_ground_truth_hidden_packages.txt`). Interpretation: these 200 apps were DO-hidden in earlier sessions and later UNHIDDEN; DMS retains per-package policy records with value false. **They do NOT represent current hiding.** Lesson recorded: policy-key presence alone is NOT ground truth — per-package enforcement flag + drawer surface are required (vHidden=1 vendor residual also present and NOT enforced by launcher).

Consequence: registry↔platform↔drawer are currently CONSISTENT (clean starting point). The 200-stale-records quirk is documented as device-behavior evidence for REG design (never treat policy-key listing as truth).

## 3. Timed Engine State

| Item | Evidence | Color |
|---|---|---|
| Alarm history | 10 × `TIMED_HIDE_EXPIRED` RTC_WAKEUP entries, ALL `Reason=alarm_cancelled` (replaced/cancelled historically) | GREEN (history) |
| Active pending zea alarms | LazyAlarmStore format exposes no tag-level pending list; none found in Top Alarms; consistent with 0 timed entries | NOT VERifiable from this dump format alone → NOT VERIFIED (will be proven live during Step-2 timed tests) |
| Exact-alarm op mode | `App ids requesting SCHEDULE_EXACT_ALARM: {… u0a338? not listed}`; `Last OP_SCHEDULE_EXACT_ALARM` has no explicit zea allow → op "default" | NOTED (risk for Step 2 atomic-commit alarm verification; test on-device) |

## 4. Security / Mode State

| Item | Evidence | Color |
|---|---|---|
| PIN gate active | "Zyro is locked" keypad; PIN unlock worked twice (dumps a19/a20/a22) | GREEN |
| Section gates active | Hidden & Timed screens re-prompt PIN ("…is locked") even after app unlock | GREEN |
| Standard Mode engine | Accessibility DISABLED (`enabled_accessibility_services=null`, accessibility_enabled=0) → Standard lock engine INACTIVE on this device | GREEN (state) — STD behavior itself NOT VERIFIED until Step 6 |
| Fingerprint option | "Use fingerprint" visible on gate | NOT VERIFIED (no fingerprint enrolled?) |

## 5. Manifest / Code Baseline (source-side)

| Item | Status |
|---|---|
| `ZeaPrivateSessionMonitorService` declared? | ❌ **NOT DECLARED** → IMP-1 target confirmed (private-launch transaction cannot legally start its monitor service; prior-session logcat showed runtime throw) |
| Declared services/receivers | ZeaDeviceOwnerKeepAliveService ✓ · ZeaDeviceAdminReceiver ✓ · ZeaDeviceOwnerSafetyReceiver ✓ (BOOT/MY_PACKAGE_REPLACED/TIMED_HIDE_EXPIRED) · ZeaStealthLockService ✓ (accessibility) · provisioning trio ✓ |
| Permissions | READ_CONTACTS · RECEIVE_BOOT_COMPLETED · SCHEDULE_EXACT_ALARM · POST_NOTIFICATIONS · PACKAGE_USAGE_STATS · FOREGROUND_SERVICE(+SPECIAL_USE) · KILL_BACKGROUND_PROCESSES |
| allowBackup | false (+fullBackupContent false) |
| Temporary debug markers | `ZeaPTR` Log.i at 5 call-sites: hub×1, allapps×2, hiddenlist×2 (gesture received / refresh skipped) — inventory complete |
| UI strings location | Hardcoded Compose literals (strings.xml effectively empty, 7 lines) → STD-1 copy audit must sweep .kt sources |
| Registry storage | SharedPreferences-based (`getSharedPreferences`) in ZeaStorage.kt; direct inspection blocked by release build — UI+DPM probes are the evidence channel |

## 6. Findings Logged During Capture (honest REDs/quirks)

1. 🔴 **Private-launch UI path unreachable right now** (hidden list empty ⇒ nothing to tap). Repro deferred until fresh hide exists (Step 2/8). Prior v107 throw-evidence remains the RED anchor.
2. 🟡 **Hub overflow-menu "Settings" item unresponsive** in v107 (taps at correct coords did nothing; sibling items work). Re-test during Step 5/6; if real, fix belongs to hub nav wiring (in-scope as count/state surface plumbing only if trivially adjacent — otherwise document).
3. 🟡 **Stale DMS applicationHidden(false) ×200** — device quirk documented above.
4. ⚪ **Batch interruption**: no durable journal exists (expected pre-IMP-15); outcome-dialog-only behavior assumed from audit — NOT VERIFIED live yet.
5. ⚪ **PTR per-screen baselines**: mechanism present (markers exist) but gesture runs not yet executed in Step-0 window → scheduled into Step 0B/4 runs. Recorded NOT VERIFIED now.

## 7. Step 0A artifact index

raw/a01 dumpsys package · a02 device_policy (483KB) · a04 packages (all/-3) · a06 home resolver · a16 logcat baseline (3744 lines) · a17 dumpsys gm/8ball excerpts · a18 dumpsys alarm full (372KB) · a19–a24 UI dumps (gate/home/menu/hub/hidden/timed/all-apps) · a25–a26 settings attempt dumps · a27 drawer dumps ×3
derived/dpm_ground_truth_hidden_packages.txt (200 stale keys) · dpm_hidden_and_installed.txt · dpm_hidden_but_uninstalled.txt (empty) · dpm_active_zea_alarms.txt
tools/zyro-helpers.ps1 (test-only)

## 8. Step 0C — Honest Baseline Colors (pre-harness)

```
GREEN (evidenced):  DO role active · PIN gate + section gates · catalog integrity (204) · counts coherent-with-platform TODAY · boot receiver wired · manifest otherwise sound · timed alarms historically functional
RED   (known-broken): private-launch transaction path (missing service declaration) — UI-unreachable today but defect persists in code
NOT VERIFIED:       PTR gesture matrix per screen · private-launch smoke (blocked) · batch interruption live · timed expiry live · exact-alarm grant behavior · Standard Mode end-to-end (engine inactive) · drawer convergence timing · scale behavior
KNOWN PHASE-2 REDS (non-blocking): no PIN cooldown · reversible PIN copy present · dev key/routes present ("Developer Controls" menu item VISIBLE in release build — captured) · release/debug separation pending
```

*No unverified path is claimed GREEN. Harness (Step 0B) will convert NOT-VERIFIED items into measured colors.*
