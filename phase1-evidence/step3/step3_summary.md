# STEP 3 Runtime Verification — Evidence Summary (IMP-15 Durable Batch Journal)

Date: 2026-08-24 · Device: vivo 10FE7N04C40001Y · Build: com.raomuhammadnoman.zea v107 (1.38-ptr-fix)
Test set (all pre-test hidden=false): ninegag, agoda, airbnb, eightballpool, cricketleague, adobe.scan, booking, expedia, aliexpresshd, daraz
Baseline: All Apps = 204 / Hidden = 0 / Timed = 0 (`raw/baseline/S3_test_set_baseline.md`)

## Interruption tooling findings (vivo quirks, documented)
| Method | Result |
|---|---|
| `am force-stop` | Silently fails on DO-owned app (PID persists, even foreground/thawed) |
| `am kill` | Works only on idle-backgrounded (frozen) process; useless mid-batch foreground |
| `am crash` | Ineffective |
| HOME backgrounding | vivo cgroup freezer stalls process ~1–2s after HOME (process alive, frozen mid-batch) |
| `adb reboot` | Kernel-guaranteed death; ADOPTED as authoritative interruption method |
| Timed HOME→freeze→reboot | Used for tail-position control (stall ≈ HOME_time + ~2s) |

Measured batch rate: ~0.775 s/item. Journal lines (tag `ZeaDeviceOwner`):
`batch journal started id=<uuid> op=<hide|unhide> targets=N` / `batch journal closed id=<uuid> outcome=<completed|abandoned> processed=k/N`

## Scenario results

### Normal path (pre-interruption sanity) — GREEN
- Hide 10: id=`19fa68a4-2da1-40bf-a653-e931d2065d93` completed 10/10 (12:02:34)
- Unhide 10: id=`27c35a9d-5f8b-4360-ab5d-f0208ce05909` completed 10/10 (12:15:48)

### S3-A — Interrupt mid-batch (freeze @5 processed), Resume — GREEN
- Journal id=`7e208869-4b6b-4d44-ac06-7b97cd1363df` op=hide targets=10 started 12:27:12.348, never closed.
- Truth at freeze: 5 HIDDEN / 5 visible (`raw/first_interrupt/s3a_dpm_truth_after_freeze.txt`).
- After reboot: "Interrupted batch found" dialog on launch (`s3a_dialog_after_reboot.xml`).
- Review Details: Completed(5)=exactly the 5 DPM-hidden; Remaining(5)=the rest (`review_details/s3a_review_details.xml`). UI classification == platform truth.
- Resume: old record archived `outcome=abandoned processed=5/10`; new journal id=`02270b38-d6a9-450e-9d66-add9dac1f058` targets=5, completed 5/5 (`resume_remaining/s3a_resume_logcat.txt`). No re-processing of completed items.
- Post: all 10 hidden; counts 194/10/0 (`resume_remaining/s3a_final_dpm.txt`, `s3a_hub_counts.xml`).

### S3-B — Interrupt near-first (@1 processed, unhide direction), Abandon — GREEN
- Journal id=`87258d16-f29a-4eb5-84f7-3ae1236dff88` op=unhide targets=10 started 12:37:44.968, killed by reboot at T+~2s.
- Truth: 1 visible (eightballpool) / 9 still hidden (`review_details/s3b_review_details.xml`, `s3b_dpm_truth.txt`).
- Review Details: Completed(1)=eightballpool; Remaining(9)=all others (+ "…and 3 more" truncation). Exact match.
- ABANDON Record: journal closed `outcome=abandoned processed=1/10`; NO rollback — processed unhide kept (eightballpool visible), untouched items stayed hidden (`raw/abandon/s3b_after_abandon.xml`, `s3b_abandon_logcat.txt`).
- Gate release: immediately afterward a NEW clean batch ran normally (unhide 9: id=`2cfed77e-7a90-4d31-81c8-eecdf94ebcc4` targets=9 completed 9/9) (`new_batch_gate/s3b_gate_released_result.xml`).

### Negative control — completed batch produces NO dialog — GREEN
- Overshoot run (batch fully completed 10/10 before death): relaunch showed NO interrupted-batch dialog (`lastminus1_interrupt/s3c_overshoot_launch.xml`).

### S3-C — Interrupt near-last (timed freeze @ item 9 mid-write), Resume — RECOVERY GREEN / DEFECT RED
- Journal id=`6ef38b48-eca5-4ecd-b64d-1bc776f18d10` op=hide targets=10 started 13:29:30.855; HOME pressed at T+5010ms; process froze during item 9 (daraz) — its DPM write landed, journal mark did not.
- Truth after death: 9 HIDDEN / expedia visible (`lastminus1_interrupt/s3c_dpm_truth.txt`).
- Dialog appeared post-reboot. Review Details THREE-WAY: Completed(8) / **NoOp(1)=daraz** / Remaining(1)=expedia (`review_details/s3c_review_details.xml`) — matches platform truth exactly.
- Resume: old record `abandoned processed=8/10`; new journal id=`daa00727-315d-4230-bb2d-26a76b90927a` targets=**1** (only expedia; NoOp not re-run), completed 1/1; DPM then 10/10 hidden (`resume_remaining/s3c_resume_logcat.txt`).

## 🔴 RED FINDING — NoOp item leaves orphaned repo record (defect)
After S3-C recovery completed successfully at the platform level, Zyro's own bookkeeping diverged from reality:
- Hub counts: **194 / 9 / 0** while DPM truth = 10 hidden (expected 194/10/0) (`resume_remaining/s3c_hub_counts.xml`, re-verified after re-entry).
- Hidden Apps list: 9 rows; **com.daraz.android absent** (`resume_remaining/s3c_hidden_list.xml`).
- All Apps search "daraz": **ABSENT** (`resume_remaining/s3c_daraz_search_allapps.xml`).
- Persists across app process restart (`resume_remaining/s3c_hub_after_restart.xml`).
- User impact: daraz is functionally hidden system-wide (launcher respects DPM) but invisible to BOTH management lists → cannot be viewed/unhidden through the app.
- Restore attempts: `adb shell pm unhide` denied (SecurityException MANAGE_USERS); Zyro command bar has no unhide verb ("unhide …" parses to open_app) (`restore/s3c_daraz_cmdbar_attempt*.xml`).
Root cause hypothesis: reconciliation classifies "already in target state" as NoOp and correctly skips re-applying, but never writes the item into the internal hidden-repo, so UI/state store and PackageManager disagree permanently.
Minimal fix suggestion (NOT applied per authorization): when reconciling, treat NoOp as verified-complete and sync the repo (insert into hidden set / mark hidden in bookkeeping) before closing the journal.

## Final device state vs baseline
- Counts: **203 / 0 / 0** (baseline 204/0/0; delta = daraz residue, see RED finding) (`restore/s3c_final_counts.xml`).
- DPM: 9 test apps visible again; **daraz left HIDDEN (residue, unrecoverable without code fix or device-owner shell)** (`restore/final_dpm_state.txt`).

## Evidence index
raw/baseline/S3_test_set_baseline.md · raw/first_interrupt/{s3a_*,s3b_*} · raw/middle_interrupt/ · raw/lastminus1_interrupt/{s3c_confirm_dialog,s3c_pid_after_freeze,s3c_freeze_logcat,s3c_overshoot_launch,s3c_launch,s3c_dialog,s3c_dpm_truth}.txt/xml · raw/review_details/{s3a,s3b,s3c}_review_details.xml · raw/resume_remaining/{s3a,s3c}_resume_logcat.txt,+final dumps · raw/abandon/s3b_* · raw/new_batch_gate/*gate_released*+restores · raw/reboot_or_restart/ · raw/restore/{final_dpm_state.txt,s3c_final_counts.xml,s3c_daraz_cmdbar_attempt*.xml}
