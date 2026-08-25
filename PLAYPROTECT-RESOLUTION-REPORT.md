# Zyro Play Protect Block — Resolution Report (v78)

## 1. Exact Root Cause
Consumer APK mein poora enterprise Device-Admin/DPC stack bundled tha (roadmap Phase 5 violation):
- `ZeaDeviceAdminReceiver` + `device_admin.xml` (device administrator profile)
- 3 exported provisioning activities (`GET_PROVISIONING_MODE`, `ADMIN_POLICY_COMPLIANCE`, `PROVISIONING_SUCCESSFUL`)
- Exported `ZeaDeviceOwnerKeepAliveService` (DEVICE_ADMIN_SERVICE)
- `PACKAGE_USAGE_STATS` + `FOREGROUND_SERVICE` + `FOREGROUND_SERVICE_SPECIAL_USE` permissions (sirf DO-only monitor use karta tha)
- 2 release instrumentations (test harness release APK mein)

Ye combination Play Protect ka "can request access to sensitive data / device control" malware-profile match karta hai.

## 2. Triggering Capabilities
DeviceAdmin declarations + provisioning intent-filters + exported admin service + usage-stats/FGS permissions.

## 3. Files Changed
- `app/src/main/AndroidManifest.xml` — 6 DPC components, 2 instrumentations, 3 permissions removed
- `app/src/main/java/com/raomuhammadnoman/zea/ZeaAppLock.kt` — usage-access onboarding step + checker function + dead UI branches removed (onboarding ab 5 pages)
- `app/src/main/res/xml/device_admin.xml` — DELETED
- `keystore.properties` — professional release keystore
- `app/build.gradle.kts` — v2+v3 signing enabled; versionCode 78

## 4. Permissions Removed
`PACKAGE_USAGE_STATS`, `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_SPECIAL_USE`

## 5. Permissions Retained + Justification
| Permission | Justification |
|---|---|
| READ_CONTACTS | Trusted Contacts feature (runtime-gated, skip-able) |
| RECEIVE_BOOT_COMPLETED | Safety receiver: lock-state re-arm after reboot |
| SCHEDULE_EXACT_ALARM | Time Hide expiry restore |
| POST_NOTIFICATIONS | Notifications feature |

## 6. Accessibility Status: RETAINED
- Config minimal: sirf `typeWindowStateChanged`, `canRetrieveWindowContent="false"`
- Kabhi screen content nahi padhta; Lock Mode ka bounce-home enforcement isi par hai (Phase 7 documented)
- `isAccessibilityTool=true` intentionally NOT set

## 7. Architecture Changes
Consumer build ab 100% DPC-free. Device Owner mode ab activate hi nahi ho sakta (admin receiver absent). Managed variant future ke liye separate rahega. Non-DO hide = ZeaLockMode registry + accessibility bounce (unchanged, verified working).

## 8. Final Merged Manifest Sensitive Permissions
Sirf: READ_CONTACTS, RECEIVE_BOOT_COMPLETED, SCHEDULE_EXACT_ALARM, POST_NOTIFICATIONS (+auto DYNAMIC_RECEIVER_NOT_EXPORTED). Zero admin/provisioning/FGS/usage residue.

## 9. Release APK Path
`C:\Users\User\Desktop\zea-v41-working\app\build\outputs\apk\release\app-release.apk`

## 10. APK SHA-256
```
3458657502919B13C1F9AAAA0C0D588E42D74171A6176653CC67A969C0061FE3
```
Size: 12.74 MB · Signing: v2+v3, CN=Zyro App Lock, O=Zyro

## 11. Fresh Install Test Result
Same-device fresh install E2E PASS: PIN create → confirm → Contacts skip → App Lock Engine skip → Notifications → gate relaunch "Zyro is locked" ✓. Onboarding 6→5 pages confirmed.

## 12. Play Protect Warning Present
Pending real sideload test (WhatsApp se friend/device par install karke dekhna hoga). Expected: hard block ki jagah "More details → Install anyway" flow ya no warning.

## 13. Play Store Testing
Not yet done — Internal/Closed testing track recommended next.

## 14. Remaining Limitations
1. AccessibilityService retained (Lock Mode enforcement requires it) — agar future me isko bhi hatana ho to Phase 4 ka launcher-based architecture banana hoga
2. Play Protect cloud reputation factor code-fix se control nahi hota — time/install-base se improve hota hai
3. Real WhatsApp-sideload verification pending (user physical test)

---
⚠️ **KEESTORE BACKUP (CRITICAL):** `Desktop\zea-signing-backup\zyro-release.keystore` · alias `zyro` · pass `ZyroRelease2026!K`

## 15. MobSF + apkanalyzer Scan (Post-Fix Verification)
- mobsfscan: 0 HIGH / 0 CRITICAL / 0 WARNING. 2 ERROR (task-hijacking CWE-1021) = FALSE POSITIVES (rules require targetSdk<28/29; ours=36, StrandHogg-immune by platform). INFO items (ssl-pinning/root-detection/safetynet) N/A for offline consumer app.
- apkanalyzer: debuggable=false, allowBackup=false, cleartext=false, minSdk=26, targetSdk=36, resources shrunk/obfuscated
- Play Protect-sensitive surface in final APK: NONE
- VERDICT: CLEAN - no rebuild required. v78 APK (SHA256 3458...FE3) approved for fresh-device installation test.
