# PHASE 3 — REQUIREMENTS LOCKED (Features + Experience)

> **Status:** LOCKED — No changes allowed without explicit user approval
> **Date:** 2026-08-25
> **Source:** ZYRO Professional App Improvement & Feature Roadmap (Phase 3+ section, per `PHASE2_REQUIREMENTS_LOCK.md` exclusion list)
> **Prerequisite:** Phase 1 closed at 90% (on-device tests pending) + Phase 2 code-side complete (90%, on-device tests pending)

---

## Phase 3 Scope

Phase 3 is **New Features + User Experience**. Phase 1 (Stability) and Phase 2 (Security + Recovery) are the foundation — Phase 3 adds app-management features, richer screens, and UX polish on top. Security hardening work in this phase is limited to extending existing Phase 2 engines; entirely new security mechanisms are Phase 4+.

---

## 3.1 — App Groups / Collections

### Requirement
User can create named groups of apps and perform bulk operations per group.

### Design (LOCKED)
```
Group (name, color/icon optional)
 → member packages (set)
 → operations: Hide All, Unhide All, Timed-Hide All,
    Manage (open group management)
```

### Rules (LOCKED)
1. Create, rename, and delete groups; deleting never hides/unhides members.
2. Members reference packages by ID — group data never duplicates registries.
3. Bulk hide/unhide runs through the same transactional engine (`ZeaAppHideService`) as single operations.
4. Empty group = normal; operations on empty group must no-op gracefully.
5. Groups visible from Apps Hub; membership shown on App Details (3.6).

### Acceptance Criteria (LOCKED)
- [ ] Create/rename/delete group works
- [ ] Add/remove apps from group
- [ ] Hide/Unhide All uses transactional verification per app
- [ ] Failed bulk members report per-app failures, others succeed
- [ ] Groups survive app restart

---

## 3.2 — Privacy Profiles / Modes

### Requirement
User can save named presets of protection configurations and switch quickly.

### Design (LOCKED)
```
Profile (name)
 → capture of current protection state:
    protected set, timed-hide set, auto-lock option,
    fingerprint/security toggles
 → Apply = verified transactional rollout to that state
```

### Rules (LOCKED)
1. Profile captures current state; applying a profile reconciles ALL differences transactionally.
2. Minimum two built-in suggestions allowed (e.g., "Work", "Private"); user-defined profiles supported.
3. Applying a profile never touches security-disable state; PIN/security stays active.
4. Profile application reports failures per action item; never partial silent success.
5. Profiles survive updates; schema versioning required.

### Acceptance Criteria (LOCKED)
- [ ] Save current state as named profile
- [ ] Apply profile with full transactional reconciliation
- [ ] Profile list + application UI reachable from Home/Apps Hub
- [ ] Failed apply reports what could not be applied
- [ ] Profiles survive restart and app update

---

## 3.3 — Scheduled / Recurring Hiding

### Requirement
User can schedule automatic hide/unhide windows on a recurring basis.

### Design (LOCKED)
```
Schedule (days-of-week, start-time, end-time)
 → target: group (3.1) or individual apps
 → action: hide at start, unhide at end
 → engine: AlarmManager (exact alarms) + Phase 2 health check
```

### Rules (LOCKED)
1. Recurring schedules use exact alarms; missed executions re-check on boot/catalog-load.
2. Schedules respect mode (Device Owner vs Standard) and always run through transactional engines.
3. Schedule list editable; individual schedule toggleable ON/OFF without deletion.
4. Overlapping schedules resolve deterministically (hide wins over unhide).
5. Schedules deleted cleanly when target group/app is removed.

### Acceptance Criteria (LOCKED)
- [ ] Create schedule for group or individual apps
- [ ] Schedule fires hide at start-time and unhide at end-time
- [ ] Toggling schedule off doesn’t unhide current members
- [ ] Missed executions recover on next boot/load
- [ ] Removing target app/group deletes its schedules

---

## 3.4 — Global Search

### Requirement
User can search apps, settings entries, and diagnostics from one field.

### Design (LOCKED)
```
Search query
 → apps (all / hidden / timed)
 → settings categories
 → recovery actions
 → history entries (if 3.9 done)
```

### Rules (LOCKED)
1. Search reachable from Home and Apps Hub.
2. Results grouped by category with the same item actions as their screens.
3. Searches never leak package names of protected apps outside the gated sections.
4. Search index built in-memory; no persistent index of protected apps.
5. Empty query shows category shortcuts, not raw list.

### Acceptance Criteria (LOCKED)
- [ ] Search returns apps, settings, recovery actions in one view
- [ ] Tapping a hidden/timed app result requires PIN gate first
- [ ] No protected app data leaks into non-gated views
- [ ] Works with empty query gracefully

---

## 3.5 — Professional Filters

### Requirement
User can filter app lists by advanced criteria beyond hidden/timed.

### Design (LOCKED)
```
Filters:
 → protection state (hidden, timed, visible, locked, not protected)
 → source (user / system)
 → recently managed (last 7 days via 3.8)
 → member of group (3.1)
```

### Rules (LOCKED)
1. Filters apply to All Apps, Hidden, Timed lists.
2. Filter state persists per screen; never global.
3. Combine filters (AND semantics) with a visible active-filter indicator.
4. Filters never modify underlying data.
5. Clear-all resets every active filter.

### Acceptance Criteria (LOCKED)
- [ ] Each listed filter dimension present
- [ ] Multiple filters combine correctly (AND)
- [ ] Active filter state visually indicated
- [ ] Clear-all resets cleanly

---

## 3.6 — App Details Screen

### Requirement
Per-app screen showing full state and all available actions.

### Design (LOCKED)
```
App Details
 → icon, label, package, version
 → current protection state + membership + schedules
 → actions: hide, unhide, timed-hide, add to group,
    add to schedule, favorite toggle, history slice
```

### Rules (LOCKED)
1. Opening details for a protected app still requires the section gate first.
2. All actions run through the standard transactional engines.
3. System-critical app shows read-only info; actions blocked per `alwaysRejectedPackages` rule.
4. Screen reachable from every app list row (context action or dedicated entry).
5. Membership (groups, schedules, profiles) shown with counts.

### Acceptance Criteria (LOCKED)
- [ ] Details screen shows all locked metadata
- [ ] Actions respect section gate before opening
- [ ] Actions execute via transactional pipeline
- [ ] System-critical apps show info only

---

## 3.7 — Favorites / Pinned Apps

### Requirement
User can pin specific apps for quick access.

### Design (LOCKED)
```
Favorites list (ordered, user-controlled)
 → shown on Home after Health card + in Apps Hub top
 → quick actions per favorite
```

### Rules (LOCKED)
1. Favorites are user-managed and reorderable.
2. Favorites visible even if the app is protected (number badge only, no state leak).
3. Favorites not duplicated when app is already protected/timed.
4. Favorites max 12 entries; overflow handled with scroll.
5. Favorites membership survives restart.

### Acceptance Criteria (LOCKED)
- [ ] Pin/remove apps from favorites in any list
- [ ] Home + Apps Hub show favorites row
- [ ] Protected favorites show no state detail
- [ ] Favorites persist across restart

---

## 3.8 — Recently Managed Apps

### Requirement
User can see apps most recently affected by hide/unhide/timed operations.

### Design (LOCKED)
```
Ring buffer (last 20 package names + operation + timestamp)
 → visible on Home / Apps Hub
 → ties into activity history (3.9) when done
```

### Rules (LOCKED)
1. Only the last 20 operations retained; FIFO eviction.
2. Entries store package + operation name + timestamp; no secret metadata.
3. Cleared when the owner runs "Clear History" (3.9) with a distinct pinned flag.
4. Ring is append-only in storage; full rewrite per update avoided.
5. Recent items never leak through global search (3.4).

### Acceptance Criteria (LOCKED)
- [ ] Operations populate recent list with correct ordering
- [ ] List caps at 20 entries correctly
- [ ] Recent list cleared on history clear
- [ ] Recent list is not searchable from global search

---

## 3.9 — Activity / Security History

### Requirement
User can review a chronological log of protection operations and security events.

### Design (LOCKED)
```
History entries:
 → hide / unhide / timed-hide / schedule fire
 → PIN gate events (lockout, successful entry)
 → recovery actions (2.7), system-check failures
 → profile/group changes (3.1/3.2)
```

### Rules (LOCKED)
1. History only records metadata (package ID, operation, timestamp, result) — never PIN values or underlying data.
2. History stored encrypted at rest using Android Keystore; cap 500 entries.
3. History UI PIN-gated like Hidden/Timed sections.
4. "Clear history" requires PIN; destructive.
5. Diagnostics screen can add relevant entries without exposing them elsewhere.

### Acceptance Criteria (LOCKED)
- [ ] Operations are logged with correct metadata
- [ ] History capped at 500 entries (FIFO)
- [ ] History encrypted at rest
- [ ] Accessing history requires PIN
- [ ] Clear history option available (PIN-confirm)

---

## 3.10 — Undo

### Requirement
User can undo the immediate previous hide/unhide/timed operation.

### Design (LOCKED)
```
UndoStack (single operation snapshot)
 → hide: snapshot → unhide back
 → unhide: snapshot → hide back
 → timed: snapshot → re-create record if still active
 → invalidates on next operation or time window
```

### Rules (LOCKED)
1. Undo only applies to the latest completed operation.
2. Snapshot stores prior state; replacement with newer state discards older.
3. Undo executes through the same transactional pipeline.
4. Time window for undo availability: 5 minutes.
5. Rollback failures report normally; never partial silent revert.

### Acceptance Criteria (LOCKED)
- [ ] Undo reverts the latest operation only
- [ ] Undo unavailable after 5 minutes
- [ ] Undo executes transactionally
- [ ] Failed undo restores nothing (no partial state)

---

## 3.11 — Improved Timed Hidden Apps Screen

### Requirement
Timed-hide management screen gets richer information and bulk actions.

### Design (LOCKED)
```
Timed screen improvements:
 → countdown labels (Remaining Xh Ym)
 → sort by end-time / label
 → group-by next-24h / later
 → bulk cancel action
 → empty state guidance (3.16)
```

### Rules (LOCKED)
1. Countdown labels update live (30-second tick max).
2. Sorting/grouping is user-selectable and persisted per-screen.
3. Bulk cancel asks confirmation (PIN-gated for timed section).
4. Live label updates avoid battery-intensive redraws.
5. Empty state explains what timed hiding does.

### Acceptance Criteria (LOCKED)
- [ ] Each record shows live countdown label
- [ ] Sort/group options selectable and persisted
- [ ] Bulk cancel confirms before executing
- [ ] Empty screen shows explanation

---

## 3.12 — Home Dashboard Redesign

### Requirement
Home becomes a real dashboard beyond the Phase 2 health card.

### Design (LOCKED)
```
Sections:
 → Favorites row (3.7)
 → Recently managed (3.8)
 → Groups overview (3.1)
 → Schedules overview (3.3)
 → Protection Health card (Phase 2)
 → Quick actions (resume remaining, abandoned batches)
```

### Rules (LOCKED)
1. Sections render only if data exists; no permanent empty placeholders.
2. Sections reorderable by the user (persisted order).
3. All section data is consistent (no double counting across cards).
4. Full dashboard remains scrollable; no horizontal-only bias.
5. PIN gates still apply for any protected data shown compactly.

### Acceptance Criteria (LOCKED)
- [ ] All listed sections render with data
- [ ] Section order user-configurable
- [ ] No section shows stale/duplicate counts
- [ ] Protected data stays behind gates

---

## 3.13 — Settings Center Redesign

### Requirement
Settings become a categorized center with clear information architecture.

### Design (LOCKED)
```
Categories:
 → Security & Privacy (PIN, auto-lock, fingerprint, history, recovery)
 → App Management (groups, schedules, profiles, favorites)
 → Display & Accessibility (dark mode, font, labels)
 → Diagnostics (system check, recovery, export)
```

### Rules (LOCKED)
1. All existing Phase 2 rows remain reachable after redesign.
2. Category headers track navigation; search still finds any row.
3. Redesign doesn’t remove any functional option.
4. Redesign must not leak protected data into wider-accessible categories.
5. Category order fixed; user customize optional.

### Acceptance Criteria (LOCKED)
- [ ] All current settings rows still reachable
- [ ] Search finds rows across categories
- [ ] No option was deleted by redesign
- [ ] Categories are visually distinct

---

## 3.14 — UX Polish Bundle

### Requirement
Finish-app polish tasks: Dark Mode, empty/loading states, micro-interactions, confirmation strategy, failure explanation polish.

### Design (LOCKED)
```
Dark Mode: manual toggle (Light/Dark/System) honored everywhere
Empty states: illustration + one-line guidance + action
Micro-interactions: small motion (150-300ms) on hide/unhide confirm
Confirmation strategy: one framework per destructive action
Failure explanation: every failure message has a plain-language cause + next step
```

### Rules (LOCKED)
1. Dark Mode option persisted; system default option honored alongside manual.
2. Empty states for all main list screens (All Apps, Hidden, Timed, Favorites, Groups, Schedules).
3. Micro-interactions non-blocking and cancellable (no animation-only gating).
4. Confirmation dialogs unified (same builder); destructive vs neutral copy.
5. Failure messages include a one-line cause and a "What to do" hint.

### Acceptance Criteria (LOCKED)
- [ ] Dark Mode toggle applies app-wide
- [ ] Every list has an appropriate empty state
- [ ] Confirm dialogs share one builder
- [ ] Failure messages show cause + next step
- [ ] Micro-interactions play on hide/unhide confirm

---

## 3.15 — Notifications Polish

### Requirement
Notifications are meaningful, grouped, and actionable.

### Design (LOCKED)
```
Notification channels:
 → Protection alerts (re-hide success/fail, lockout events)
 → Schedule events (3.3)
 → Batch completion (bulk operations)
 → Diagnostics findings (critical)
```

### Rules (LOCKED)
1. Notification channels described with plain names/titles.
2. Only critical alerts bypass user mute preferences with explanation.
3. Batch completion notifications summarize success/fail counts.
4. Schedule notifications include schedule name + affected group/apps.
5. All notifications open deep-link to the relevant screen.

### Acceptance Criteria (LOCKED)
- [ ] Channels defined with plain-language names
- [ ] Batch completion counts correct
- [ ] Schedule notifications carry correct name/target
- [ ] Tapping notification opens the right screen

---

## 3.16 — Onboarding Redesign + Help & Support + Privacy Center

### Requirement
Onboarding explains mode choice, permissions, and privacy posture clearly; Help & Support and Privacy Center close gaps.

### Design (LOCKED)
```
Onboarding:
 → Mode choice (Standard vs Device Owner) explained with pros/cons
 → Permission pages with why-needed text
 → Post-onboarding checklist until all permissions granted

Help & Support:
 → FAQ content in-app
 → Contact/links placeholder to real channel when available

Privacy Center:
 → Explanation of where data is kept (local-only)
 → Export-history, clear-history, recovery entries
```

### Rules (LOCKED)
1. Mode choice page explains the trade-offs before any PIN is set.
2. Permission pages map to their gate states.
3. Help & Support is self-contained once built (no external-only content).
4. Privacy Center never requests data from any server.
5. Onboarding completion state updates existing flags without breaking old devices.

### Acceptance Criteria (LOCKED)
- [ ] Mode choice explanation page present
- [ ] Each permission page has why-needed text
- [ ] Help content embedded in-app
- [ ] Privacy Center shows storage model honestly
- [ ] Existing onboarding flags untouched

---

## 3.17 — Accessibility + Branding Consistency

### Requirement
Accessibility semantics and branding cleanup throughout.

### Design (LOCKED)
```
Accessibility: contentDescription on all icons/buttons, focus order, touch targets ≥48dp
Branding: unified spacing/typography; "Zea"/"Zyro" consistent in copy; palette normalized
```

### Rules (LOCKED)
1. Every interactive icon has a meaningful content description.
2. Focus order on lock screens, dialogs, and lists is logical.
3. Branding replaces "Zyro" mentions with the agreed final label once decided (still placeholder).
4. Touch targets on all rows/cards ≥48dp or explicitly justified.
5. Palette normalized (no one-off hex colors).

### Acceptance Criteria (LOCKED)
- [ ] All interactive icons have descriptions
- [ ] Focus order verified on PIN/recovery screens
- [ ] Touch targets meet 48dp minimum
- [ ] Palette consolidated into `Colors.kt`/theme tokens

---

## 3.18 — Error Architecture Standardization

### Requirement
Unified error/surfacing architecture instead of ad-hoc banners/buttons.

### Design (LOCKED)
```
ZeaUiError:
 → severity (info, warning, error)
 → message
 → optional action (repair, retry, dismiss)
 → consumed via one shared component
```

### Rules (LOCKED)
1. One shared error model consumed by every screen.
2. No per-screen bespoke banner implementations.
3. Errors log minimally (no PIN/data) for diagnostics.
4. Retries reset error state; dismiss clears safely.
5. Error UI is never blocking (always dismissable).

### Acceptance Criteria (LOCKED)
- [ ] Shared error model exists and is used by all screens
- [ ] No bespoke error banners remain in codebase
- [ ] Errors are dismissable by design

---

## 3.19 — Local Encrypted Backup

### Requirement
User can back up protection state, groups, profiles, schedules, and history.

### Design (LOCKED)
```
Backup blob (JSON schema v1)
 → protected set, timed-hide records
 → groups, profiles, schedules
 → history (last N entries)
 → PIN-gated before restore
 → AES from Android Keystore, local file
```

### Rules (LOCKED)
1. Restore requires the current Zyro PIN before merge.
2. Restore merges rather than replaces; rollback on failure.
3. No backup contains PIN hash, verifier, or device owner credentials.
4. Backup file explicitly readable only via SAF/training flow.
5. Schema versioned; migration path documented.

### Acceptance Criteria (LOCKED)
- [ ] Export creates local encrypted backup
- [ ] Import requires PIN before merge
- [ ] Failed import leaves pre-import state
- [ ] Backup contains no security credentials

---

## 3.20 — Performance Optimization + QA Matrix

### Requirement
Performance budgets and an executable QA regression matrix.

### Design (LOCKED)
```
Performance budgets:
 → All Apps list: <300ms initial render on 200 apps
 → Hide/Unhide single: <1s round trip
 → Health evaluation: <500ms
 → QA matrix: checklist per change crossing this phase
```

### Rules (LOCKED)
1. Budgets measured on the owner’s vivo device (user-performed).
2. All Phase 3 features respect these budgets before closure.
3. QA matrix is a markdown checklist: input → action → expected → actual.
4. Matrix includes Phase 1 + Phase 2 critical flows + Phase 3 additions.
5. Perf regressions are treated as release blockers.

### Acceptance Criteria (LOCKED)
- [ ] Budgets met on device (user-measured)
- [ ] QA matrix exists and maintained
- [ ] Matrix includes Phase 1 & 2 critical flows
- [ ] Perf regressions block closure

---

## Phase 3 Exclusions (Explicitly Out of Scope)

These are **NOT** in Phase 3:

- Cloud backup / cross-device sync (Phase 4+)
- Widget support (Phase 4+)
- Remote administration APIs (Phase 4+)
- Multi-user/device management (Phase 4+)
- Branding final decision (name + icon) — placeholder remains

---

## Phase 3 Dependencies

| Dependency | Status | Notes |
|------------|--------|-------|
| Phase 1 code-side complete | ✅ (8227418) | Pending on-device validation |
| Phase 2 code-side complete | ✅ (8227418) | Pending on-device validation |
| Security reference points | Present | Recovery, PIN gates, health engines |
| Feature foundations | Present | Catalog, storage, hide service |

---

## Phase 3 Deliverables (LOCKED)

1. **Code:** All 20 items implemented and verified
2. **Build:** Debug + Release APKs compile successfully
3. **Tests:** Unit tests for pure logic (filtering, grouping, undo, history ring, schedule overlap, search scoring)
4. **Docs:** `PHASE3_IMPLEMENTATION_NOTES.md` (created during implementation)
5. **Closure:** `PHASE3_CLOSURE_REPORT.md` (after on-device tests)

---

## Sign-Off

**Locked by:** OpenHands AI Agent
**Date:** 2026-08-25
**User approval required:** YES — Please review and confirm these Phase 3 requirements are complete and correct.

**To unlock changes:** User must explicitly say "Phase 3 requirements change" or "add/remove Phase 3 item".

---

**End of Phase 3 Requirements Lock**
