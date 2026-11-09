# Phase 2: After the call

**Where:** mostly `/home/yacine/Desktop/work/truecaller`, using CRM endpoints from Phase 0 · **Time:** ~1 week · **Needs:** Phase 1

**Goal:** after a call, the agent logs it into the CRM in a few taps, creates a lead from an unknown number, or opens an SAV request. Plus a search screen, a recent client calls list, and in-app updates.

**Done when:**
- [ ] A call logged from the phone appears in the lead's «سجل المكالمات» in the CRM, with the agent's name
- [ ] Choosing «لا يجيب» pre-fills the next reminder exactly like the CRM form
- [ ] An unknown caller can be added as a new lead and is assigned to the agent
- [ ] An SAV request created from the phone appears on the CRM's NotesBoard
- [ ] Saving without internet works: it is sent automatically later
- [ ] A missed call from a client gives a «إعادة الاتصال» notification
- [ ] The app offers a new version when the CRM has one

---

## 2.1 Remember what happened during the call

- [ ] Extend `CurrentCall` (from Phase 1) to `CurrentCall(phone, leadId?, name?, ringingAt, answeredAt?, endedAt?)`.
- [ ] `CallStateWatcher`: `OFFHOOK` sets `answeredAt`, `IDLE` sets `endedAt`. `answered = answeredAt != null`. `durationSeconds = endedAt - answeredAt`.
- [ ] Save each call into a local Room table `CallHistory(id, phone, leadId?, name?, at, answered, durationSeconds, logged = false)`. Only the calls the app reacted to (clients, or unknown numbers if the setting is on). **It never reads the phone's call log.**

## 2.2 What happens when the call ends

| Situation | What the app does |
|---|---|
| **Answered**, known client | The overlay turns into a small bar: «سجّل المكالمة مع محمد بن علي» [تسجيل] [لاحقاً]. «تسجيل» opens `AfterCallScreen`. The bar closes after 15 s and leaves a notification. |
| **Answered**, unknown number | Same bar: «إضافة كعميل جديد؟» [إضافة] [تجاهل] |
| **Missed**, known client | Notification «اتصل بك محمد بن علي» with actions [إعادة الاتصال] (`ACTION_DIAL`) and [فتح البطاقة] |
| **Missed**, unknown | Nothing |
| Answered call shorter than 5 s | Treated as missed (wrong number / dropped) |

- [ ] Notification channel `after_call` (high importance). Tapping it opens the deep link `saascom://after-call?callId={CallHistory.id}`. Add the `<intent-filter>` on `MainActivity` and handle it in Navigation Compose.
- [ ] The app opens the screen only from a **user tap** (on the bar or the notification). Android limits opening activities from the background, and a tap is always allowed.

## 2.3 `AfterCallScreen`: log a call for a known client

This mirrors the CRM form «تسجيل مكالمة» (`LeadActions::callHistory()`), simplified for a phone:

- [ ] Header: name, company, status chip, call duration («4:12»).
- [ ] **Result**: 4 chips from `CallLog::STATUSES`. Default **متوفر** for an answered call.
- [ ] **Notes** (required, max 2000):
  - Picking لا يجيب / مشغول / مغلق fills the note with that word, like the CRM.
  - Picking an unreachable result also pre-fills the reminder from `follow_up_if_unreachable` (from the last `/lookup`). If the action is `archive`, it pre-selects the archive status and clears the reminder.
- [ ] **Status**: dropdown from the `statuses` list saved during sync. Default = the current status.
- [ ] **Reminder**:
  - Quick chips «بعد 3 / 5 / 7 / 14 / 30 يوم» → send `reminder_in_business_days` (the server skips Fridays and holidays).
  - Or a date/time picker in Algiers time, converted to UTC → `reminder_date`.
  - «حذف الموعد» → `reminder_date: null`.
  - Not touched → don't send any reminder field.
- [ ] «حفظ في CRM» → `POST /leads/{id}/calls`. On 201: mark `CallHistory.logged = true`, show «تم تسجيل المكالمة», close. On 403: «هذا العميل ليس من عملائك».
- [ ] «+ طلب عميل» button → `RequestScreen` (2.5).
- [ ] If `full_access` was false: only show «هذا العميل لدى أمين» and a button to call or WhatsApp the owner. No log form, since the CRM would answer 403 anyway.

## 2.4 New lead from an unknown number

- [ ] `NewLeadScreen`: phone (pre-filled, read-only), name, company, notes (required).
- [ ] «إضافة» → `POST /leads`.
  - 201 → «تمت إضافة العميل», then refresh the directory in the background.
  - 409 → «الرقم موجود مسبقاً», then open the existing lead (`lead_id`).

## 2.5 SAV request

- [ ] `RequestScreen`: title (max 180), details (max 10000), priority chips عادية / مهمة / عاجلة (default عادية).
- [ ] «إرسال» → `POST /leads/{id}/requests` → «تم إرسال الطلب». It appears in the CRM (NotesBoard, category «طلب عميل»).

## 2.6 Saving without internet (offline queue)

- [ ] Room table `PendingAction(id, type [CALL|NEW_LEAD|REQUEST], leadId?, payloadJson, createdAt, attempts, lastError?)`.
- [ ] Every save tries the API first. On a network error (no 4xx response), store a `PendingAction`, tell the user «سيتم الإرسال عند توفر الإنترنت», and schedule `SendPendingWorker` (one-time, network required, exponential backoff).
- [ ] 4xx answers are **not** retried. Keep them with `lastError`, and show them on Home as «لم يُرسل» so the user can fix or delete them.
- [ ] Home shows a small counter «2 بانتظار الإرسال».

## 2.7 Search and client card screens

- [ ] `SearchScreen`: one search field.
  - Digits → normalize, then search the phone (`LIKE %digits%` in `DirectoryDao`).
  - Text → search name and company (`LIKE %text%`).
  - Results show name, company, status chip.
- [ ] `LeadScreen` (tap a result): calls `/lookup` and shows the full `CallerCard` + buttons:
  - اتصال (`ACTION_DIAL`), واتساب, فتح في CRM
  - تسجيل مكالمة (opens `AfterCallScreen` without call info, for calls made from another phone)
  - طلب عميل
- [ ] Pull-to-refresh on `LeadScreen`.

## 2.8 Recent client calls

- [ ] `RecentCallsScreen` becomes the first tab, with `CallHistory` newest first:
  - Name or number, time («اليوم 10:42»), answered / missed icon
  - An orange badge **«غير مسجّلة»** when `logged = false` and answered. Tap opens `AfterCallScreen`.
- [ ] Keep 30 days of history, cleaned by `SyncWorker`.
- [ ] Bottom tabs: الأخيرة · بحث · الإعدادات.

## 2.9 In-app update

- [ ] At startup (at most once per day): `GET /app-version`.
  - `version_code > BuildConfig.VERSION_CODE` → dialog «نسخة جديدة 1.0.3» [تحديث] [لاحقاً].
  - `BuildConfig.VERSION_CODE < min_version_code` → blocking dialog, with no «لاحقاً».
- [ ] [تحديث]:
  - Download with `DownloadManager` into the app's external files dir
  - Check the **sha256**
  - Open the installer through a `FileProvider` URI (`ACTION_VIEW`, `application/vnd.android.package-archive`, `FLAG_GRANT_READ_URI_PERMISSION`)
- [ ] Manifest: `REQUEST_INSTALL_PACKAGES` + the `FileProvider` declaration. The first time, Android asks the user to allow installs from this app.

## 2.10 CRM side (small)

- [ ] Nothing new is required: the endpoints exist since Phase 0.
- [ ] Check in the CRM that a call from the app looks exactly like one made from the dashboard:
  - The agent's name in «سجل المكالمات»
  - `last_contacted_at` updated
  - The lead counts in the «مكالمات الفريق اليوم» widgets and in TeamPerformance
- [ ] Optional: in the CRM call history view (`resources/views/filament/components/call-history.blade.php`), show a small 📱 icon for calls logged from the app. That needs a `source` column on `call_logs` (`web` | `mobile`), set by `CallController`. Decide whether it's worth it.

## 2.11 Tests

- [ ] Unit:
  - The after-call decision table (2.2) for every situation
  - Reminder pre-fill from `follow_up_if_unreachable`
  - `PendingAction` retry rules (network error → retry, 4xx → no retry)
  - The `reminder_date` "omit vs null" serialization (configure `explicitNulls` so `null` is really sent when wanted)
- [ ] Instrumented / manual:
  - [ ] Answer a client call and log it → visible in the CRM within seconds
  - [ ] Missed client call → notification → «إعادة الاتصال» opens the dialer with the number
  - [ ] Unknown number → add → the lead exists in the CRM, assigned to the agent; adding it again → opens the existing one
  - [ ] Airplane mode → log → «بانتظار الإرسال» → network back → sent
  - [ ] Publish version +1 on the CRM → the app offers the update → installs and keeps the login
