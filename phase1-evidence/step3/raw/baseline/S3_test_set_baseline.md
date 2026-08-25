# S3 Test Set — Pre-Test Baseline (captured 2026-08-24)

Build under test: versionCode=107 versionName=1.38-ptr-fix lastUpdateTime=2026-08-24 10:29:38 (Step-3 build)
Device: vivo 10FE7N04C40001Y

| # | Package | installed | DPM hidden | pre-test logical | pre-test timer |
|---|---|---|---|---|---|
| 1 | com.ninegag.android.app | true | false | VISIBLE | none |
| 2 | com.agoda.mobile.consumer | true | false | VISIBLE | none |
| 3 | com.airbnb.android | true | false | VISIBLE | none |
| 4 | com.miniclip.eightballpool | true | false | VISIBLE | none |
| 5 | com.miniclip.cricketleague | true | false | VISIBLE | none |
| 6 | com.adobe.scan.android | true | false | VISIBLE | none |
| 7 | com.booking | true | false | VISIBLE | none |
| 8 | com.expedia.bookings | true | false | VISIBLE | none |
| 9 | com.alibaba.aliexpresshd | true | false | VISIBLE | none |
| 10 | com.daraz.android | true | false | VISIBLE | none |

Note: vHidden=1 is the documented vivo vendor residual (Step-0 §2) — NOT enforced by launcher, not part of truth.

Selection rationale: all third-party USER apps (games/travel/shopping); none system-critical, launcher, dialer, SMS, IME, admin, installer, Zyro itself, or in a private session; no active timed records.
Expected Zyro counts at test start: All Apps = 204 · Hidden = 0 · Timed = 0.

RESTORE CONTRACT: after every scenario (and at end of testing) restore each package's exact pre-test state: hidden=false, records removed ⇒ counts back to 204/0/0.
