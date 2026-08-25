# PHASE 1 — MOBILE TEST CHECKLIST (10%)

> **Purpose:** Execute these on-device tests to close Phase 1 100%.  
> **Device:** vivo phone (`10FE7N04C40001Y`)  
> **App version:** v108 (`1.39-phase1-stability`)  
> **Tester:** User  
> **Prerequisites:** Device Owner active, PIN set, ADB available at `$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe`

---

## Test 1 — Hidden App Open → Re-Hide (P0)

**Steps:**
1. Hide 3 apps (e.g., Instagram, WhatsApp, Chrome).
2. From Zea Hidden Apps, tap each app to open it.
3. Use the app for ~10 seconds.
4. Press Home / switch away.
5. Wait for Zea to auto-re-hide (or screen-off if configured).
6. Check Android launcher drawer: app must be absent.
7. Check Zea Hidden Apps count: must be correct.
8. Repeat for all 3 apps.

**Expected:** App opens, session monitored, re-hidden automatically, no stale visible icon.

**Result:** [ ] PASS  [ ] FAIL  — Notes: __________

---

## Test 2 — Transactional Hide (P0)

**Steps:**
1. Pick 5 visible apps.
2. Hide each one individually.
3. After each hide, verify:
   - Zea says "hidden and protected from uninstall"
   - Hidden Apps count increases
   - App disappears from launcher drawer
   - `adb shell dumpsys package <pkg> | grep hidden=` shows `hidden=true`
   - `adb shell dumpsys package <pkg> | grep blocked=` shows uninstall block if checked

**Expected:** Success only reported after all layers converge.

**Result:** [ ] PASS  [ ] FAIL  — Notes: __________

---

## Test 3 — Transactional Unhide (P0)

**Steps:**
1. Unhide 5 previously hidden apps.
2. After each unhide, verify:
   - Zea says app is visible
   - Hidden Apps count decreases
   - App reappears in launcher drawer
   - `adb shell dumpsys package <pkg> | grep hidden=` shows `hidden=false`

**Expected:** Success only after visible state verified.

**Result:** [ ] PASS  [ ] FAIL  — Notes: __________

---

## Test 4 — Pull-to-Refresh Standardization (P0/P1)

**Steps:**
1. Go to each screen and pull down from the top:
   - Home
   - App Hub
   - All Apps
   - Hidden Apps
   - Time Hidden Apps
2. Observe refresh indicator.
3. Verify no duplicate refresh jobs (pull twice quickly).
4. Verify no stuck loading state.
5. Verify search/selection state is preserved where practical.

**Expected:** Same smooth behavior on all screens.

**Result:** [ ] PASS  [ ] FAIL  — Notes: __________

---

## Test 5 — Count Consistency (P1)

**Steps:**
1. Note current counts (All Apps / Hidden / Timed).
2. Hide 10 apps in batch.
3. Verify Hidden count increases by 10.
4. Unhide 10 apps in batch.
5. Verify Hidden count decreases by 10.
6. Set timed hide for 2 apps (1 minute).
7. Wait for expiry.
8. Verify counts return to original.
9. Pull-to-refresh and verify counts unchanged.

**Expected:** Counts always match actual state.

**Result:** [ ] PASS  [ ] FAIL  — Notes: __________

---

## Test 6 — Freeze / Stuck Behavior (P1)

**Steps:**
1. Hide 50 apps in batch.
2. Observe progress dialog (`Hiding x of y`).
3. Verify UI remains responsive (can scroll, press back).
4. Repeat with 100 apps if device supports.
5. Try to hide while another operation is running — should be blocked or queued safely.

**Expected:** No UI freeze, controlled progress shown.

**Result:** [ ] PASS  [ ] FAIL  — Notes: __________

---

## Test 7 — Timed-Hide Rollback (P1)

**Steps:**
1. Set timed hide for an app with 1-minute duration.
2. Wait for expiry.
3. Verify app becomes visible automatically.
4. Verify no orphan record remains.
5. Repeat with 5-minute duration while Zea is closed (force-stop after hiding).

**Expected:** App unhidden at expiry, no zombie state.

**Result:** [ ] PASS  [ ] FAIL  — Notes: __________

---

## Test 8 — Reboot Persistence (P1)

**Steps:**
1. Hide 5 apps.
2. Reboot device.
3. Open Zea.
4. Verify Hidden Apps count = 5.
5. Verify launcher drawer still hides them.
6. Verify `adb shell dumpsys package <pkg> | grep hidden=` still `hidden=true`.

**Expected:** State survives reboot.

**Result:** [ ] PASS  [ ] FAIL  — Notes: __________

---

## Test 9 — Process Death Recovery (P1)

**Steps:**
1. Hide an app.
2. Force-stop Zea: `adb shell am force-stop com.raomuhammadnoman.zea`
3. Reopen Zea.
4. Verify hidden app still hidden in launcher and Zea count correct.

**Expected:** No state corruption.

**Result:** [ ] PASS  [ ] FAIL  — Notes: __________

---

## Test 10 — Permission Revocation Handling (P1)

**Steps:**
1. Revoke Usage Access permission from Android Settings.
2. Open Zea.
3. Verify health/status indicator shows warning.
4. Re-grant permission.
5. Verify warning clears.

**Expected:** Clear warning, no silent failure.

**Result:** [ ] PASS  [ ] FAIL  — Notes: __________

---

## Test 11 — System-Critical App Protection (P1)

**Steps:**
1. Try to hide Settings, Phone, or current launcher.
2. Verify Zea refuses with clear explanation.

**Expected:** Refusal with reason (critical system app).

**Result:** [ ] PASS  [ ] FAIL  — Notes: __________

---

## Test 12 — Large Dataset Performance (P1)

**Steps:**
1. If device has 200+ apps, load All Apps list.
2. Scroll through list.
3. Search for an app.
4. Select multiple apps.
5. Perform batch hide/unhide.
6. Measure if UI stays smooth.

**Expected:** No jank, no crash, no ANR.

**Result:** [ ] PASS  [ ] FAIL  — Notes: __________

---

## Final Sign-Off

| Test | Result | Date | Tester Initials |
|------|--------|------|-----------------|
| Test 1 | | | |
| Test 2 | | | |
| Test 3 | | | |
| Test 4 | | | |
| Test 5 | | | |
| Test 6 | | | |
| Test 7 | | | |
| Test 8 | | | |
| Test 9 | | | |
| Test 10 | | | |
| Test 11 | | | |
| Test 12 | | | |

**Overall Result:** [ ] ALL PASS — Phase 1 100% CLOSED  [ ] SOME FAIL — See notes

**Notes / Issues found:**

___________________________________________

___________________________________________

___________________________________________

---

**After completing this checklist, share results with the agent. If all tests pass, Phase 1 is 100% officially closed and Phase 2 may begin.**
