# Zea — Session Handoff (Aug 2026)

Fresh chat mein ye file share karo — saara context isi mein hai.

## Project
- Path: `C:\Users\User\Desktop\zea-v41-working`
- Package: `com.raomuhammadnoman.zea` (personal assistant app with Device Owner app-hiding)
- Current: **v54** (`versionCode=54`, `versionName="1.2-no-app-loss"`)
- Build: `gradlew.bat assembleRelease` with `JAVA_HOME = C:\Program Files\Android\Android Studio\jbr`
- APK: `app\build\outputs\apk\release\app-release.apk`

## v54 — no-app-loss guarantee (Aug 2026)
- BUG FOUND: timed-hide expiry ne record PEHLE delete kiya, unhide BAAD mein — process death/race par apps ZOMBIE ban gayin (hidden=true + no record = launcher aur Zea dono se invisible). 4 victims recover ho gayin: Facebook (com.facebook.katana), Gmail (com.google.android.gm), EasyShare (com.vivo.easyshare), Feedback (com.vivo.feedback)
- FIX in `ZeaAppHideService.unhideApp`: ab policy-release PEHLE confirm hoti hai (`releasePolicyState` helper, try/catch), record sirf success ke baad delete
- FIX: target==null path ab recovery-unhide try karta hai (blind clearTimedHide nahi)
- NEW: `sweepOrphanedHiddenApps` — hidden-but-unmanaged koi bhi package mila toh release; throttle 5 min; called from `ZeaTimedHide.restoreExpiredHides` (jo catalog load/boot/alarm par chalta hai) + catalog cache invalidate on heal
- Device state: All Apps = 52 apps, sab visible (user ka time-hide expire ho chuka tha; wo dobara hide karega khud)

## Device / Automation
- vivo phone, serial `10FE7N04C40001Y`, adb at `$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe`
- Device lock PIN: `72382121` (Zea global lock + Hidden Apps gate dono isse unlock hote hain — "Use password" button @ (187.5,890) → type PIN → keyevent 66)
- UI helper: `$env:TEMP\opencode\tap.ps1` — usage: `powershell -NoProfile -ExecutionPolicy Bypass -File "$env:TEMP\opencode\tap.ps1" -Match "text" [-Desc]`
- Navigation: More options (content-desc) → Apps → "View all supported apps" (All Apps list) ya "Apps currently hidden" (Hub card)
- Panel (Device Owner controls): Zea main screen scroll-down → per-app sections with buttons
- **CRITICAL panel rule**: "Unhide and Remove from Zea" button hamesha APP LABEL ke neeche wale Y par target karo (label-relative), warna galat section hit hota hai
- Nav stuck ho jaye to: `adb shell am start --activity-clear-task -n com.raomuhammadnoman.zea/.MainActivity` (backstack wipe)
- vivo recents slides uiautomator dump mein NAHI dikhte — task check via `dumpsys activity recents | grep 'Recent #'`

## Install dance (jab naya APK install karna ho)
Install Lock RECORD-COUNT based hai: records > 0 → Zea ka update block.
1. Zea unlock → panel → har record par "Unhide and Remove from Zea" → verify "Protection Install Lock: Inactive"
2. `adb install -r <apk>`
3. Re-hide apps: All Apps → app row LONG-PRESS (swipe x y x y 900) → sheet "Hide App" → confirm dialog "Hide App" (last match)
4. Verify: `dumpsys package <pkg> | grep hidden=` → hidden=true

## v49–v53 ka architecture summary
- **Private launch flow**: hidden app unhide hoti hai → launch (sirf `FLAG_ACTIVITY_NEW_TASK`) → `ZeaPrivateSessionMonitorService` foreground service monitor karta hai
- **Session boundaries** (v53 se): Screen-off • Shutdown • 6-hour max • Naya private launch purani ko SUPERSEDE karta hai (reject nahi). Foreground chhodna ab session end NAHI karta
- **Gate** (`ZeaLockedAppsGate`, ZeaAppsNavigation.kt): FLAG_SECURE + auth; `unlocked` plain `remember` (saveable NAHI — process-death bypass fix)
- Auth plan: PIN-only device → DEVICE_CREDENTIAL system prompt auto-fire
- Fail-closed reconciliation active-session ke dauran defer hota hai; manual visibility window panel unhides ke liye
- Key files: `ZeaDeviceOwnerController.kt` (~2590 lines), `ZeaPrivateSessionMonitorService.kt`, `ZeaAppsNavigation.kt`, `ZeaAppLock.kt`, `ZeaHiddenListScreens.kt`, `ZeaAppCatalog.kt` (fingerprint cache)

## Vivo quirks (jo khud discover kiye)
- `am force-stop` kabhi-kabhi silently fail (PID same rehta hai) — process death test ke liye Developer Options "Don't keep activities" ON karo (`settings put global always_finish_activities 1`, baad mein 0)
- `pm unhide` SecurityException deta hai — sirf Zea (device owner) hi unhide kar sakta hai
- Record remove karte waqt race: unhide→PACKAGE_ADDED→reconciler re-hide kar sakta tha (rare); verify hidden=false after removal

## Current device state
- v53 installed; Albums (com.vivo.gallery) hidden=true WITH record (user ne manage kiya)
- Wallet/Weather/YT Music visible, records user ne khud hataye
- Sab persistence tests PASS: slide home/YouTube ke baad survive karti hai, screen-off par session end + auto re-hide confirmed

## Pending / ideas
- Recents thumbnail privacy: hidden app ki slide ka preview content dikhata hai (FLAG_SECURE sirf Zea gate par hai; target app ka thumbnail OS-level nahi blank hota) — user ne trade-off accept kiya tha
- Agar session notification hide karni ho ya boundaries tweak karni hon to monitor service constants (file bottom) dekho
