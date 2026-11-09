# Phase 3: Extras

**Where:** both · **Time:** pick items one by one · **Needs:** Phase 2 in daily use for a couple of weeks

Do these only after the team has used the app for real, and pick the ones they actually ask for. Each item is independent.

| # | Item | Size | Where |
|---|---|---|---|
| 3.1 | Card for outgoing calls | S | App |
| 3.2 | Work SIM only (dual SIM) | S | App |
| 3.3 | Missed client calls in the CRM | M | Both |
| 3.4 | Incoming calls in CRM reports | M | CRM (after 3.3) |
| 3.5 | Push notification when an SAV request changes | M | Both |
| 3.6 | Mark calls logged from the app | S | Both |
| 3.7 | Card position, size, dark mode | S | App |

S ≈ 1 day, M ≈ 2–4 days.

---

## 3.1 Card for outgoing calls

When an agent dials a client from the phone's dialer, show the same card.

- [ ] In `CallerScreeningService.onScreenCall`, also handle `Call.Details.DIRECTION_OUTGOING`. Respond with the same "allow" response, then call `CallerCardController.onOutgoingCall(...)`.
- [ ] Settings toggle «إظهار البطاقة عند الاتصال بعميل» (default on).
- [ ] After-call: the default result follows the call state. `OFFHOOK` held for more than 5 s → متوفر. Otherwise, ask the agent (لا يجيب / مشغول / مغلق), since Android can't tell whether the other side answered.
- [ ] Check on real phones that the service is called for outgoing calls (with `READ_CONTACTS` granted).

## 3.2 Work SIM only (dual SIM)

For agents who have their personal SIM in the same phone.

- [ ] Settings: «شريحة العمل» lists the active SIMs (`SubscriptionManager.getActiveSubscriptionInfoList()`, needs `READ_PHONE_STATE`), plus «الكل» (default).
- [ ] In `onScreenCall`, compare `details.accountHandle` with the chosen SIM's `PhoneAccountHandle` (via `TelecomManager.getCallCapablePhoneAccounts()`). If it doesn't match, do nothing: no card, no CRM request.
- [ ] Test with a call on each SIM.

## 3.3 Missed client calls in the CRM

So the team (not only the agent's phone) knows a client called and nobody answered.

- [ ] **CRM**
  - Migration `incoming_calls(id, lead_id null, phone, user_id, answered bool, duration_seconds, called_at, created_at)`
  - Model `IncomingCall`
  - Endpoint `POST /api/mobile/call-events` `{phone, lead_id?, answered, duration_seconds, called_at}`, added to `api-contract.md` first
- [ ] **App**: after each call (2.1), send the event (through the `PendingAction` queue, so offline works).
- [ ] **CRM UI**
  - Home widget «مكالمات فائتة من العملاء»: today's missed calls with no answered call or `CallLog` for that lead afterwards. Each row has a «تسجيل مكالمة» action.
  - Follow the CRM rules: numbers come from `App\Support\Metrics\WorkMetrics`, and each count links to the list it shows.
- [ ] Tests: `tests/Feature/Mobile/CallEventsTest`, plus a metrics test on the "missed and not called back" definition.

## 3.4 Incoming calls in CRM reports

Needs 3.3.

- [ ] `TeamMetrics`: incoming calls per agent (answered / missed / average duration) for the period, using `AlgerianDate::sqlLocal()` for days.
- [ ] Widget in `Pages\Reports\TeamPerformance`, with the `HasReportFilters` filters.
- [ ] Hand-computed test scenarios in `tests/Feature/Metrics`, including the midnight Algiers/UTC edge, like the existing ones.

## 3.5 Push notification when an SAV request changes

- [ ] Create a Firebase project. Add `google-services.json` to the app, and the Firebase Messaging dependency.
- [ ] **App**: get the FCM token after login and send it with `POST /api/mobile/push-token` (new endpoint; contract first). On a push, show a notification that opens `LeadScreen`.
- [ ] **CRM**
  - Column `fcm_token` on `personal_access_tokens`, or a `device_push_tokens` table
  - Send through the FCM HTTP v1 API from a queued job
  - Trigger on `UserNoteUpdate` created, when the note's author is a mobile user
- [ ] The CRM needs a queue worker for this, or send synchronously with a short timeout.

## 3.6 Mark calls logged from the app

- [ ] Migration: `call_logs.source` enum `web|mobile`, default `web`.
- [ ] `CallLogger::log()` takes an optional `source`. `CallController` passes `mobile`.
- [ ] `call-history.blade.php`: small 📱 icon when `source = mobile`.

## 3.7 Card position, size, dark mode

- [ ] Drag the card up or down, and remember the position in `SettingsStore`.
- [ ] «بطاقة مختصرة» setting: name + status only.
- [ ] The card follows the phone's dark mode.
