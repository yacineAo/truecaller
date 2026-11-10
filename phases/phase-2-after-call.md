# Phase 2: After the call

**Where:** mostly `/home/yacine/Desktop/work/truecaller`, using CRM endpoints from Phase 0 · **Time:** ~1 week · **Needs:** Phase 1

**Goal:** after a call, the agent logs it into the CRM in a few taps, creates a lead from an unknown number, or opens an SAV request. Plus a search screen, a recent client calls list, and in-app updates.

**Changes compared with the plan below (decided while building):**
- **Explicit intents instead of a `saascom://` deep link:** other apps cannot open the after-call screens (`AppIntents`).
- **Plain SQLite again (`LocalDb`, `local.db`)** for the call history and the outbox. It's separate from the client-list cache, because this data must survive list refreshes.
- **The «الأخيرة» tab** shows the calls, the outbox counter and refused writes. **The «الإعدادات» tab** took over the Phase 1 home content (ready status, list sync, test card).
- **Reminder chips send `reminder_in_business_days`** (the CRM computes the date, skipping Fridays and holidays). «تاريخ آخر…» opens a date picker then a time picker on the Algiers clock. A suggested follow-up date in the past is never pre-filled.
- **Missed calls:** the app shows the «اتصل بك …» notification for any known number, including another agent's client. Logging stays with the owner: no log prompt for another agent's client.
- **Version number from Gradle properties:** `-Psaascom.versionCode=2 -Psaascom.versionName=1.0.1`, used to test the in-app update.
- **In-app update downloads with OkHttp, not `DownloadManager`:** the APK goes to `cacheDir/updates` (shared through the `FileProvider`), streamed through a SHA-256 digest with a progress bar. It needs no external storage and no broadcast receiver. The sha256 check and the installer intent are as planned.
- **A required update cannot be dodged:** the once-a-day pause of `GET /app-version` is skipped while the installed `versionCode` is below the last `min_version_code` the CRM announced (`shouldCheckForUpdate`). The CRM does not enforce the minimum itself; the contract leaves that to the app.
- **403 reads «هذا العميل ليس من عملائك»:** the CRM answers a policy denial with Laravel's English default text, so the app replaces it (`ApiException.Http.userMessage`). The CRM's own Arabic messages (e.g. «الحساب معطّل») are shown as sent.
- **A refused queued call is «غير مسجّلة» again:** a call counts as logged when it is queued, and the outbox undoes that when the CRM refuses it (4xx), so the agent can log it again.
- **A lead sent from the outbox triggers a directory sync,** so its number is known on the next call.
- **A suggested follow-up date in the past** pre-fills nothing, shows «الموعد المقترح فات» and outlines the quick-reminder chips.
- **Pull-to-refresh on the client page** replaced the «تحديث» button.
- **A call row follows its lead:** after an unknown number is added (online, on a 409, or later from the outbox) the history row points to the new lead instead of staying «غير موجود في CRM».
- **Known limit, the install step:** on the API 37 emulator Android's installer refused the downloaded APK with `INSTALL_FAILED_VERIFICATION_FAILURE: Install not allowed` (system verification of sideloaded apps; `adb install` is exempt). Everything before it works, and the in-place update itself was proven with `adb install -r`. Test the real install on a phone before relying on it.
- **Known limit:** if the CRM saves a write but the answer is lost, the app queues it and sends it again, which makes a duplicate call log. Fixing it needs an idempotency key in the API contract.

**Done when:**
- [x] A call logged from the phone appears in the lead's «سجل المكالمات» in the CRM, with the agent's name
- [x] Choosing «لا يجيب» pre-fills the next reminder exactly like the CRM form
- [x] An unknown caller can be added as a new lead and is assigned to the agent
- [x] An SAV request created from the phone appears on the CRM's NotesBoard (checked as a `client_request` note in the database; the NotesBoard page itself was not opened)
- [x] Saving without internet works: it is sent automatically later
- [x] A missed call from a client gives a «إعادة الاتصال» notification
- [x] The app offers a new version when the CRM has one

---

## 2.1 Remember what happened during the call

- [x] Extend `CurrentCall` (from Phase 1) to `CurrentCall(phone, leadId?, name?, ringingAt, answeredAt?, endedAt?)`.
- [x] `CallStateWatcher`: `OFFHOOK` sets `answeredAt`, `IDLE` sets `endedAt`. `answered = answeredAt != null`. `durationSeconds = endedAt - answeredAt`.
- [x] Save each call into a local Room table `CallHistory(id, phone, leadId?, name?, at, answered, durationSeconds, logged = false)`. Only the calls the app reacted to (clients, or unknown numbers if the setting is on). **It never reads the phone's call log.**

## 2.2 What happens when the call ends

| Situation | What the app does |
|---|---|
| **Answered**, known client | The overlay turns into a small bar: «سجّل المكالمة مع محمد بن علي» [تسجيل] [لاحقاً]. «تسجيل» opens `AfterCallScreen`. The bar closes after 15 s and leaves a notification. |
| **Answered**, unknown number | Same bar: «إضافة كعميل جديد؟» [إضافة] [تجاهل] |
| **Missed**, known client | Notification «اتصل بك محمد بن علي» with actions [إعادة الاتصال] (`ACTION_DIAL`) and [فتح البطاقة] |
| **Missed**, unknown | Nothing |
| Answered call shorter than 5 s | Treated as missed (wrong number / dropped) |

- [x] Notification channel `after_call` (high importance). Tapping it opens the deep link `saascom://after-call?callId={CallHistory.id}`. Add the `<intent-filter>` on `MainActivity` and handle it in Navigation Compose.
- [x] The app opens the screen only from a **user tap** (on the bar or the notification). Android limits opening activities from the background, and a tap is always allowed.

## 2.3 `AfterCallScreen`: log a call for a known client

This mirrors the CRM form «تسجيل مكالمة» (`LeadActions::callHistory()`), simplified for a phone:

- [x] Header: name, company, status chip, call duration («4:12»).
- [x] **Result**: 4 chips from `CallLog::STATUSES`. Default **متوفر** for an answered call.
- [x] **Notes** (required, max 2000):
  - Picking لا يجيب / مشغول / مغلق fills the note with that word, like the CRM.
  - Picking an unreachable result also pre-fills the reminder from `follow_up_if_unreachable` (from the last `/lookup`). If the action is `archive`, it pre-selects the archive status and clears the reminder.
  - If that date is in the past (old leads: it's counted from the day the lead arrived), don't pre-fill it. Just highlight the quick-reminder chips.
- [x] **Status**: dropdown from the `statuses` list saved during sync. Default = the current status.
- [x] **Reminder**:
  - Quick chips «بعد 3 / 5 / 7 / 14 / 30 يوم» → send `reminder_in_business_days` (the server skips Fridays and holidays).
  - Or a date/time picker in Algiers time, converted to UTC → `reminder_date`.
  - «حذف الموعد» → `reminder_date: null`.
  - Not touched → don't send any reminder field.
- [x] «حفظ في CRM» → `POST /leads/{id}/calls`. On 201: mark `CallHistory.logged = true`, show «تم تسجيل المكالمة», close. On 403: «هذا العميل ليس من عملائك».
- [x] «+ طلب عميل» button → `RequestScreen` (2.5).
- [x] If `full_access` was false: only show «هذا العميل لدى أمين» and a button to call or WhatsApp the owner. No log form, since the CRM would answer 403 anyway.

## 2.4 New lead from an unknown number

- [x] `NewLeadScreen`: phone (pre-filled, read-only), name, company, notes (required).
- [x] «إضافة» → `POST /leads`.
  - 201 → «تمت إضافة العميل», then refresh the directory in the background.
  - 409 → «الرقم موجود مسبقاً», then open the existing lead (`lead_id`).

## 2.5 SAV request

- [x] `RequestScreen`: title (max 180), details (max 10000), priority chips عادية / مهمة / عاجلة (default عادية).
- [x] «إرسال» → `POST /leads/{id}/requests` → «تم إرسال الطلب». It appears in the CRM (NotesBoard, category «طلب عميل»).

## 2.6 Saving without internet (offline queue)

- [x] Room table `PendingAction(id, type [CALL|NEW_LEAD|REQUEST], leadId?, payloadJson, createdAt, attempts, lastError?)`.
- [x] Every save tries the API first. On a network error (no 4xx response), store a `PendingAction`, tell the user «سيتم الإرسال عند توفر الإنترنت», and schedule `SendPendingWorker` (one-time, network required, exponential backoff).
- [x] 4xx answers are **not** retried. Keep them with `lastError`, and show them on Home as «لم يُرسل» so the user can fix or delete them.
- [x] Home shows a small counter «2 بانتظار الإرسال».

## 2.7 Search and client card screens

- [x] `SearchScreen`: one search field.
  - Digits → normalize, then search the phone (`LIKE %digits%` in `DirectoryDao`).
  - Text → search name and company (`LIKE %text%`).
  - Results show name, company, status chip.
- [x] `LeadScreen` (tap a result): calls `/lookup` and shows the full `CallerCard` + buttons:
  - اتصال (`ACTION_DIAL`), واتساب, فتح في CRM
  - تسجيل مكالمة (opens `AfterCallScreen` without call info, for calls made from another phone)
  - طلب عميل
- [x] Pull-to-refresh on `LeadScreen`.

## 2.8 Recent client calls

- [x] `RecentCallsScreen` becomes the first tab, with `CallHistory` newest first:
  - Name or number, time («اليوم 10:42»), answered / missed icon
  - An orange badge **«غير مسجّلة»** when `logged = false` and answered. Tap opens `AfterCallScreen`.
- [x] Keep 30 days of history, cleaned by `SyncWorker`.
- [x] Bottom tabs: الأخيرة · بحث · الإعدادات.

## 2.9 In-app update

- [x] At startup (at most once per day): `GET /app-version`.
  - `version_code > BuildConfig.VERSION_CODE` → dialog «نسخة جديدة 1.0.3» [تحديث] [لاحقاً].
  - `BuildConfig.VERSION_CODE < min_version_code` → blocking dialog, with no «لاحقاً».
- [x] [تحديث]:
  - Download with `DownloadManager` into the app's external files dir
  - Check the **sha256**
  - Open the installer through a `FileProvider` URI (`ACTION_VIEW`, `application/vnd.android.package-archive`, `FLAG_GRANT_READ_URI_PERMISSION`)
- [x] Manifest: `REQUEST_INSTALL_PACKAGES` + the `FileProvider` declaration. The first time, Android asks the user to allow installs from this app.

## 2.10 CRM side (small)

- [x] Nothing new is required: the endpoints exist since Phase 0.
- [ ] Check in the CRM that a call from the app looks exactly like one made from the dashboard:
  - The agent's name in «سجل المكالمات» (checked in `call_logs`)
  - `last_contacted_at` updated (checked)
  - The lead counts in the «مكالمات الفريق اليوم» widgets and in TeamPerformance
- [ ] Optional: in the CRM call history view (`resources/views/filament/components/call-history.blade.php`), show a small 📱 icon for calls logged from the app. That needs a `source` column on `call_logs` (`web` | `mobile`), set by `CallController`. Decide whether it's worth it.

## 2.11 Tests

- [x] Unit:
  - The after-call decision table (2.2) for every situation
  - Reminder pre-fill from `follow_up_if_unreachable`
  - `PendingAction` retry rules (network error → retry, 4xx → no retry)
  - The `reminder_date` "omit vs null" serialization (configure `explicitNulls` so `null` is really sent when wanted)
- [ ] Instrumented / manual (run 2026-11-10, see below):
  - [x] Answer a client call and log it → visible in the CRM within seconds
  - [x] Missed client call → notification → «إعادة الاتصال» opens the dialer with the number
  - [x] Unknown number → add → the lead exists in the CRM, assigned to the agent; adding it again → opens the existing one
  - [x] No internet (Wi-Fi and data off) → log → «بانتظار الإرسال» → network back → sent
  - [ ] Publish version +1 on the CRM → the app offers the update → installs and keeps the login (offer, blocking dialog, download, sha256 and the installer prompt passed; the system install was refused on the emulator, see «Known limit, the install step»)

**Run of 2026-11-10** (emulator Pixel_10_Pro, API 37; local CRM on `127.0.0.1:8010`, branch `feature/mobile-api`, throwaway database `soft_client_caller_e2e`; the dev database was not touched). Calls were simulated with `adb emu gsm call|accept|cancel`; the app was driven with `uiautomator`. Also checked on the device:
- The «لا يجيب» case for an old lead (suggested date in the past): nothing pre-filled, hint shown, quick chips outlined.
- Another agent's client logged offline: the CRM answers 403, the write is not retried, the app shows «هذا العميل ليس من عملائك» and the call is «غير مسجّلة» again.
- 3 business days from Tuesday 10 Nov gave Saturday 14 Nov (Friday skipped).
- A required update (`min_version_code` above the installed one): no «لاحقاً», BACK does not close it, and it returns after killing and reopening the app.
- Pull-to-refresh on the client page sends a new `/lookup`.
