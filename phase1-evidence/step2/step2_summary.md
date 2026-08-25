# STEP 2 — IMP-2 Transactional Gap Fixes — Evidence
**Build**: v107 release (manifest IMP-1 fix retained) + ZeaAppHideService.kt edits
**Date**: 2026-08-24 · Install `-r` preserved data+DO

## Code changes (ZeaAppHideService.kt)
| Gap | Fix |
|-----|-----|
| (a) past-date timed-hide stranded app hidden w/o record | Pre-validation BEFORE `hideApp()` rejects `endEpochMillis <= now` (no platform mutation); post-hide race guard rolls back via `unhideApp()` + `Log.w` if timer expired during commit window |
| (b) silent double-save in unhideApp registry removal | Explicit single retry with `Log.w("registry save retry package=... operation=unhide_record_removal")`; honest failure outcome unchanged on second failure |
| (c) no cache invalidation after successful hide | `invalidateCatalogCache()` added to hideApp() success path AND hideAppForTime() success path (covers timed-record mutation) |

## Runtime verification (on-device)
| # | Check | Result | Evidence |
|---|-------|--------|----------|
| S2.1 | Timed hide commit path end-to-end | GREEN | T0=10:07:24.561 Confirm → `set hidden applied=true confirmed=true` @10:07:24.925; uninstall-block precedes; outcome "1 apps hidden / Everything completed successfully." |
| S2.2 | Alarm expiry auto-restore | GREEN | alarm @10:09:19.542 (+~115s incl. vivo batching): `set hidden requested=false applied=true`; "timed hide alarm … visible again"; safety reconciliation reason=`TIMED_HIDE_EXPIRED`, 0/0 hidden |
| S2.3 | **Pre-check caught real past-date case** | GREEN | First run (30s duration): UI automation consumed the window; NEW code rejected BEFORE mutation → DPM stayed hidden=false, honest failure dialog, NO orphan. Old code would have committed hide then failed without rollback (the original quirk). Logcat silence pre-dated added rejection log; rejection log added post-run. |
| S2.4 | Counts coherence through full cycle | GREEN | during timed: hub 203/0/1-equivalent state verified via All Apps header 203; after expiry: All Apps 204 + row Visible; hub **204/0/0** |
| S2.5 | Drawer convergence after expiry | GREEN | AccuWeather back in drawer (Search-apps dump), 9GAG sanity present |
| S2.6 | Catalog self-heal on stale screen | OBSERVED | Screen loaded pre-expiry kept "203 apps" until reload — live-refresh gap is IMP-3/Step-4 scope (documented, not a regression) |

## Honest limitations
- (b) retry path: storage-fault injection impossible on release build (no debug tooling; §14 forbids debug doubles here) → VERIFIED by code review + build + normal-path execution. Runtime fault-injection deferred to Phase-2 IMP-8 dev/build separation.
- Race-guard rollback branch (a-post): sub-second window not reproducible externally; same verification class as S2-limitation.
- Rejection diagnostic log (`timed hide rejected ...`) added AFTER first run; S2.3 evidence is behavioral (DPM false + honest dialog), log line will appear in future rejections.

## Artifacts
- s10_timed_dialog.xml — bulk timed sheet (30s attempt)
- s11_timed_cycle_logcat.txt — full commit→expiry cycle
- s12_final_hub.xml — 204/0/0 post-cycle
- s13_final_drawer.xml — drawer restored

## Status
STEP 2 COMPLETE → proceed STEP 3 (IMP-15 durable batch journal)
