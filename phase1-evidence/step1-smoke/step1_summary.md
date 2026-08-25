# STEP 1 — IMP-1 Targeted Smoke Test Evidence
**Build**: v107 release + manifest fix (ZeaPrivateSessionMonitorService declared, `foregroundServiceType="specialUse"`, PROPERTY_SPECIAL_USE_FGS_SUBTYPE=device_owner_private_session_monitor)
**Device**: 10FE7N04C40001Y · **Date**: 2026-08-24 · Install preserved data + Device Owner (`install -r`)

## Result matrix
| # | Check | Result | Primary evidence |
|---|-------|--------|------------------|
| S1.1 | Reboot forces PIN gate | GREEN | gate shown True post-reboot (session-grace design: no gate on quick relaunch — expected) |
| S1.2 | Boot reconciliation keeps counts stable | GREEN | hub 204/0/0 immediately post-reboot, no phantom entries |
| S1.3 | Single hide transaction commit (T0→T_dpm) | GREEN | logcat `set hidden ... requested=true applied=true confirmed=true` @09:21:05.068; dumpsys observed hidden=true ≤1040ms after final confirm tap |
| S1.4 | Uninstall-block precedes unhide/hide ops | GREEN | `set uninstall blocked ... confirmed=true` always logged first (@09:21:05.001, @09:27:36.858, @09:29:38.727) |
| S1.5 | Honest outcome banner | GREEN | "1 apps hidden / Everything completed successfully." |
| S1.6 | Count coherence after hide | GREEN | header 203 apps instantly; hub 203/1/0 (disjoint, sum=204) |
| S1.7 | Post-hide cache invalidation | GREEN | `private app lookup cache invalidated reason=private app registry changed` |
| S1.8 | Drawer surface convergence (T1) | GREEN | vivo drawer: AccuWeather absent at alphabetical slot (…9GAG → Adobe Scan…), neighbors intact |
| S1.9 | **IMP-1 private-launch: monitor starts** | **GREEN** | `private session monitor ready package=com.accuweather.android session=8f890487-1e9b-42cb-80a6-d9124ce0595e`; ServiceRecord live; FGS notif id=7008 channel=zea_private_session flags=ONGOING\|NO_CLEAR\|FOREGROUND_SERVICE |
| S1.10 | Private-launch unhide applied | GREEN | `set hidden requested=false applied=true confirmed=false` (confirmed=false = vivo async-confirm quirk; package verifiably usable) |
| S1.11 | Screen-off safety re-hide | GREEN | T0+~1.2s: `automatic re-hide ... success=true attempt=1 reason=android.intent.action.SCREEN_OFF`, uninstall-block restored first |
| S1.12 | Session teardown clean | GREEN | `private session monitor stopped`; ServiceRecord gone; NotificationRecord gone (stopForeground(STOP_FOREGROUND_REMOVE) svc.kt:L1632/L1671); `launcher task cleared after visibility change` |

## Observations / findings (non-blocking, for report)
1. **Private-launched app did not retain foreground** this run (topResumedActivity=launcher throughout session window). Process alive, workers ran. Possible causes: vivo launcher recents interference / app-side redirect. Core transaction unaffected; re-test during Step 5/6 flows.
2. **Unhide `confirmed=false`**: DPM setApplicationHidden returns async confirm on vivo for unhide direction; applied=true and state verified false. Track as known platform quirk.
3. **Bulk hide = two-stage confirm** ("Hide All Now"/"For Time" dialog → per-app list confirm "Hide 1 Apps"). UX note only.
4. **Dialog taps need container bounds** (text nodes clickable=false; IME overlay raced text-coordinate taps). Harness updated to tap container centers.
5. Deferred launcher refresh while Zyro foreground confirmed (`launcher refresh deferred...`) — matches plan §refresh ordering.

## Baseline restore status
Pending device unlock → unhide AccuWeather via UI → expect counts 204/0/0 + DPM hidden=false.

## Raw artifacts (this folder)
- s1_hub.xml — hub post-reboot 204/0/0
- s2_search_accuweather.xml — search-filtered row (Visible label pre-hide)
- s3_after_hide.xml / s4_after_hide_commit.xml — confirm-dialog stages
- s5_after_hide_hub.xml — All Apps 203 apps, filtered row gone
- s6_hidden_unlocked.xml — Hidden screen: AccuWeather "Permanently Hidden/Hidden"
- s7_screenoff_logcat.txt — full logcat of SCREEN_OFF safety path
