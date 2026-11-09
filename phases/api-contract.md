# API contract: CRM ↔ Android app

The CRM implements this in Phase 0, and the app uses it from Phase 1 on. If something here changes, update this file first, then both sides.

## Basics

- **Base URL:** `{CRM_URL}/api/mobile`. Debug from the emulator: `http://10.0.2.2:8000/api/mobile`.
- **Headers the app always sends:**
  - `Accept: application/json`
  - `Authorization: Bearer {token}` (all routes except `/login`)
  - `X-App-Version: {versionCode}`, e.g. `3`
- **Dates:** UTC ISO-8601 with `Z`, e.g. `2026-11-15T08:00:00Z`. The app converts to `Africa/Algiers` for display.
- **Money:** numbers in DA, e.g. `10000` (no formatting in the API).
- **Errors:** Laravel's normal JSON `{ "message": "…", "errors": { "field": ["…"] } }`. Messages are in Arabic.

| HTTP | Meaning | What the app does |
|---|---|---|
| 401 | Token missing, wrong, deleted, or user disabled | Delete the session and show the login screen |
| 403 | Not allowed for this lead | Show `message` |
| 404 | Lead not found | Show «العميل غير موجود» |
| 409 | Already exists (new lead) | Open the existing lead (`lead_id` in body) |
| 422 | Validation error | Show `message` / field errors |
| 429 | Too many requests | Retry later, quietly |

**Rate limits:** `/login` allows 5 per minute per email + IP. Everything else allows 120 per minute per user.

## Shared values

**Call result** (`CallLog::STATUSES`, colors from `CallLog::STATUS_COLORS`)

| value | label | color |
|---|---|---|
| `available` | متوفر | `#10b981` |
| `no_answer` | لا يجيب | `#f59e0b` |
| `busy` | مشغول | `#3b82f6` |
| `closed` | مغلق | `#ef4444` |

**Lead stage** (`LeadStatus::STAGES`; no status = `new`). The colors below are suggestions for the app.

| value | label | app color |
|---|---|---|
| `new` | جديد | `#f59e0b` |
| `follow_up` | قيد المتابعة | `#6b7280` |
| `interested` | مهتم | `#3b82f6` |
| `in_process` | قيد الإجراء | `#6366f1` |
| `won` | مشترك | `#10b981` |
| `closed` | مغلق | `#ef4444` |

**Request priority** (`UserNote::PRIORITIES`): `normal` عادية · `high` مهمة · `urgent` عاجلة

## Phone normalization: shared test table

`App\Support\Phone::normalize()` (PHP) and `PhoneNormalizer.normalize()` (Kotlin) must both pass this table.

| input | output |
|---|---|
| `0555123456` | `0555123456` |
| `+213555123456` | `0555123456` |
| `213555123456` | `0555123456` |
| `00213555123456` | `0555123456` |
| `+213 555-12.34(56)` | `0555123456` |
| `p:+213555123456` | `0555123456` |
| `0033612345678` | `+33612345678` |
| `+33612345678` | `+33612345678` |
| `+21321234567` (landline) | `021234567` ← added in Phase 0, task 0.4 |
| `021234567` (landline) | `021234567` |
| `abc` | `null` |
| `12345` | `null` |
| `` (empty / hidden number) | `null` |

---

## 1. `POST /login`

```json
{ "email": "karim@saascom.dz", "password": "••••••", "device_name": "Samsung A54 · كريم" }
```

**200**

```json
{ "token": "7|Xb3kP…", "user": { "id": 3, "name": "كريم", "role": "agent" } }
```

- **422** `{"message": "بيانات الدخول غير صحيحة"}`
- **403** `{"message": "الحساب معطّل"}` when `is_active = false`
- One token per phone: logging in again with the same `device_name` deletes the old token.

## 2. `POST /logout`

**204**, no body. Deletes the current token.

## 3. `GET /directory`

The light list used for the instant match while the phone rings. The app may send `If-None-Match: "<etag>"`, and the CRM answers **304** if nothing changed.

**200** (header `ETag: "…"`)

```json
{
  "generated_at": "2026-11-09T18:00:00Z",
  "statuses": [
    { "id": 1, "name": "مشترك", "stage": "won" },
    { "id": 2, "name": "مهتم", "stage": "interested" }
  ],
  "entries": [
    { "lead_id": 1234, "phone": "0550123456", "name": "محمد بن علي", "company": "صيدلية النور",
      "stage": "interested", "status": "مهتم", "owner": "كريم" },
    { "lead_id": 1234, "phone": "0661987654", "name": "محمد بن علي", "company": "صيدلية النور",
      "stage": "interested", "status": "مهتم", "owner": "كريم" }
  ]
}
```

- **One entry per phone**, already normalized: a lead with a `secondary_phone` gives two entries. If two leads share a phone, the one where it's the `whatsapp_phone` wins.
- Contains **all** leads (only these minimal fields), so the agent always knows the caller is a client and who owns it.
- `statuses` is ordered by `sort_order`, for the "change status" dropdown.
- `name` can be `null`. The app then shows `company`, then the phone.

## 4. `GET /lookup?phone={any format}`

**200, found, full access**

```json
{
  "found": true,
  "full_access": true,
  "lead": {
    "id": 1234,
    "name": "محمد بن علي",
    "company": "صيدلية النور",
    "phone": "0550123456",
    "secondary_phone": "0661987654",
    "wilaya": "وهران",
    "business_type": "صيدلية",
    "status": { "id": 2, "label": "مهتم", "stage": "interested" },
    "assigned_to": { "id": 3, "name": "كريم" },
    "last_call": { "at": "2026-11-06T10:15:00Z", "result": "available", "by": "سارة",
                   "note": "طلب عرض سعر لنسخة متعددة الأسعار" },
    "next_reminder": "2026-11-15T08:00:00Z",
    "subscription": {
      "type": "subscription", "type_label": "اشتراك",
      "required": 30000, "paid": 20000, "remaining": 10000,
      "payment_status": "partial", "payment_status_label": "دفع جزئي",
      "should_pay_on": "2026-11-20"
    },
    "open_requests_count": 1,
    "open_requests": [
      { "id": 9, "title": "مشكل في الطباعة", "priority": "urgent", "status": "in_progress",
        "created_at": "2026-11-08T09:00:00Z" }
    ],
    "tags": ["VIP"],
    "follow_up_if_unreachable": { "action": "remind", "date": "2026-11-12T09:00:00Z", "archive_status_id": null },
    "crm_url": "https://crm.example.dz/admin/leads/1234",
    "whatsapp_url": "https://wa.me/213550123456"
  }
}
```

- `wilaya`: the `wilaya.name` relation, otherwise `wilaya_text`.
- `last_call`, `next_reminder`, `subscription`: `null` when empty. `subscription` is `latestSubscription`.
- `open_requests`: the latest 3 `userNotes` whose status is not in `UserNote::DONE_STATUSES`.
- `follow_up_if_unreachable`: `FollowUpStrategy::nextStepFor($lead, 'no_answer')`. The app uses it to pre-fill the reminder when the agent picks `no_answer`/`busy`/`closed`, the same way the CRM form does. When the action is `archive`, `date` is `null` and `archive_status_id` is the id of the `archived` status (or `null` if that status doesn't exist).

**200, found, limited** (the lead belongs to another agent; `LeadPolicy::view` is false)

```json
{
  "found": true,
  "full_access": false,
  "lead": { "id": 1234, "name": "محمد بن علي", "company": "صيدلية النور",
            "status": { "id": 2, "label": "مهتم", "stage": "interested" },
            "assigned_to": { "id": 5, "name": "أمين" } }
}
```

**200, not found:** `{ "found": false, "phone": "0661987654" }`

**422:** the phone can't be normalized.

## 5. `POST /leads/{id}/calls`

Logs a call. This is the same action as «تسجيل مكالمة» in the CRM.

```json
{
  "result": "available",
  "notes": "سيرسل الوثائق يوم الأحد",
  "new_status_id": 2,
  "reminder_in_business_days": 3
}
```

| field | rule |
|---|---|
| `result` | required, one of the call results |
| `notes` | required, max 2000 |
| `new_status_id` | optional, must exist; omit to keep the status |
| `reminder_date` | optional ISO date. **Omit** to keep the current reminder, send **`null`** to clear it |
| `reminder_in_business_days` | optional int 1–60, instead of `reminder_date`. The server computes the date exactly like the CRM's quick-reminder buttons (skips Fridays and holidays) |

Send `reminder_date` **or** `reminder_in_business_days`, never both (422).

**201**

```json
{ "call_id": 5521, "lead": { "...": "same object as /lookup, full access" } }
```

**403** if `LeadPolicy::update` is false.

## 6. `POST /leads`

Creates a lead from an unknown number, after a call.

```json
{ "phone": "+213661987654", "name": "سمير", "company": "مكتبة الأمل", "notes": "يريد تجربة البرنامج" }
```

- `phone` required (any format). `name` and `company` optional (max 255). `notes` required (max 2000).
- The CRM creates the lead (source «مكالمة واردة»), then logs a call with result `available` and these notes. That call auto-assigns the lead to the agent through the existing `CallLog` hook.

**201** `{ "lead_id": 2600 }`
**409** `{ "message": "الرقم موجود مسبقاً", "lead_id": 1234 }`

## 7. `POST /leads/{id}/requests`

Opens an SAV request: a `UserNote` with category `client_request`.

```json
{ "title": "مشكل في الطباعة", "content": "الفاتورة لا تُطبع منذ التحديث", "priority": "urgent" }
```

- `title` required, max 180. `content` required, max 10000. `priority` required, one of the priorities.

**201** `{ "request_id": 31 }`
**403** if `LeadPolicy::update` is false.

## 8. `GET /app-version`

```json
{
  "version_code": 3,
  "version_name": "1.0.2",
  "min_version_code": 1,
  "apk_url": "https://crm.example.dz/downloads/saascom-caller-1.0.2.apk",
  "sha256": "9f2c…"
}
```

If the app's `versionCode < min_version_code`, the update is required. Otherwise the app offers it once a day.
