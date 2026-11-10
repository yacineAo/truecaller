# Phase 0: CRM API

**Where:** `/home/yacine/Desktop/work/soft_client_suivi` · **Time:** ~3 days · **Needs:** nothing

**Goal:** add a small, secure API under `/api/mobile` that the Android app can call. All endpoints are specified in [`api-contract.md`](api-contract.md).

**Status (2026-11-09):** tasks 0.1–0.12 done on branch `feature/mobile-api` (not committed yet); 0.13 (deploy) to do. `composer test`: 165 passed.
Changes compared with the plan below:
- `phpunit/phpunit` was added to `require-dev`. It existed only in `vendor/`, and `composer require laravel/sanctum` removed it.
- `Lead::saving` now normalizes `secondary_phone`, so numbers typed by hand in the CRM keep matching caller ID.
- `bootstrap/app.php` also uses `redirectGuestsTo()`. API guests got a 500 ("Route [login] not defined") instead of a 401.
- `open_requests` on the card follows `UserNote::visibleTo`, so agents don't see notes the CRM hides from them.
- `/directory` sends Arabic as UTF-8 (`JSON_UNESCAPED_UNICODE`): 401 KB → 318 KB, about 52 KB gzipped, on real data.
- Extra regression test `tests/Feature/LogCallActionTest.php` for the dashboard form after the `CallLogger` move.

**Done when:**
- [x] All 8 endpoints work and match `api-contract.md`
- [x] `composer test` passes, including the new `tests/Feature/Mobile/*`
- [x] `curl …/api/mobile/lookup?phone=+213550123456` returns the same lead as `?phone=0550123456`
- [x] An admin can see and disconnect phones in «الأجهزة المرتبطة»

---

## 0.1 Branch and baseline

- [x] `git checkout -b feature/mobile-api`
- [x] `composer test` passes **before** changing anything, so a later failure means our change caused it.

## 0.2 Install Sanctum (login tokens)

The CRM has no API login today.

- [x] `composer require laravel/sanctum`
- [x] `php artisan vendor:publish --provider="Laravel\Sanctum\SanctumServiceProvider"` (publishes `config/sanctum.php` and the `personal_access_tokens` migration)
  - Don't use `php artisan install:api`: `routes/api.php` is already set up in `bootstrap/app.php`, and we don't want it touched.
- [x] `php artisan migrate`
- [x] `app/Models/User.php`: add `use Laravel\Sanctum\HasApiTokens;` and `use HasApiTokens, HasFactory, Notifiable;`
- [x] `config/sanctum.php`: set `'expiration' => null`. Tokens stay valid until logout, disable, or an admin disconnects the phone.

> Model hooks (`Lead::saving` sets `updated_by`, `LeadStatusLog.user_id`, `UserNoteResource::prepareCreateData`) read `auth()->id()`. With `auth:sanctum`, Laravel's `Authenticate` middleware calls `Auth::shouldUse('sanctum')`, so `auth()->id()` is the token's user. Task 0.10 has tests that prove it.

## 0.3 Always answer `/api/*` with JSON

- [x] `bootstrap/app.php`, in `withExceptions`:

```php
->withExceptions(function (Exceptions $exceptions): void {
    // API routes always get JSON errors (401/403/404/422), never a redirect to the login page.
    $exceptions->shouldRenderJsonWhen(fn (Request $request) => $request->is('api/*'));
})
```

(add `use Illuminate\Http\Request;`)

## 0.4 Phone search on both columns

Today `Lead::findByPhone()` only checks `whatsapp_phone`. Caller ID must also match `secondary_phone`.

- [x] Migration `add_index_to_leads_secondary_phone`: `$table->index('secondary_phone');`
- [x] `Lead::findByAnyPhone()`:

```php
/** The lead owning this number in whatsapp_phone or secondary_phone (any format), or null. */
public static function findByAnyPhone(?string $phone): ?self
{
    $normalized = \App\Support\Phone::normalize($phone);
    if ($normalized === null) {
        return null;
    }
    $variants = \App\Support\Phone::variants($normalized);

    // whatsapp_phone wins: it is the lead's main number.
    return static::query()->whereIn('whatsapp_phone', $variants)->first()
        ?? static::query()->whereIn('secondary_phone', $variants)->first();
}
```

- [x] **Landlines** (`+213 21 23 45 67` = `+213` + 8 digits): today `normalize()` keeps them as `+21321234567`, so they never match a stored `021234567`. In `App\Support\Phone`:
  - `normalize()`: after the mobile rule, add `if (preg_match('/^\+213(\d{8})$/', $phone, $m)) { $phone = '0'.$m[1]; }`. Require the `+` (or `00`, which is already turned into `+`) so a bare 11-digit number isn't misread.
  - `variants()`: for `0` + 8 digits, also return `+213…` and `213…`.
  - Add the landline rows from the contract table to `tests/Unit/PhoneTest.php`.

## 0.5 One-time cleanup of old phone values

`secondary_phone` was typed by hand for years, so its formats are mixed.

- [x] Command `php artisan leads:normalize-phones {--dry-run}` in `routes/console.php` (or `app/Console/Commands`):
  - For each lead with a `secondary_phone`: `Phone::normalize()`. If the value changes, show `id | old → new` and save it (unless `--dry-run`). If the result is `null`, only report it.
  - For `whatsapp_phone`: **report only** values that aren't normalized. It's a unique column, so changing it could collide with another lead. Fix those by hand.
  - Use `Lead::withoutEvents()` so it doesn't change `updated_by` or send notifications.
- [x] Run `--dry-run` on a copy of production first, check the list, then run it for real.

## 0.6 Move the "log call" logic into `App\Support\CallLogger`

The «تسجيل مكالمة» form is defined once in `app/Filament/Actions/LeadActions.php::callHistory()`, but its save logic sits inside the Filament `->action()` closure, so the API can't reuse it. Move it out:

- [x] Create `app/Support/CallLogger.php`:

```php
<?php

namespace App\Support;

use App\Models\CallLog;
use App\Models\Lead;
use App\Models\User;
use Carbon\Carbon;
use Illuminate\Support\Facades\DB;

/**
 * Logs a call for a lead. Shared by the dashboard's "log call" form and the mobile app.
 *
 * $data: status, notes, and optionally new_status_id, reminder_date, client_id, customer_name,
 * company_name, business_type, inventory_software, ios.
 * Keys missing from $data are left unchanged; reminder_date = null clears the reminder.
 */
class CallLogger
{
    /** Lead fields the call form may update. */
    public const LEAD_FIELDS = ['client_id', 'customer_name', 'company_name', 'business_type', 'inventory_software', 'ios'];

    public static function log(Lead $lead, User $user, array $data): CallLog
    {
        return DB::transaction(function () use ($lead, $user, $data): CallLog {
            $call = $lead->callLogs()->create([
                'user_id' => $user->id,
                'notes' => $data['notes'] ?? '',
                'status' => $data['status'] ?? 'available',
            ]);

            $updates = array_intersect_key($data, array_flip(self::LEAD_FIELDS));

            if (! empty($data['new_status_id']) && (int) $data['new_status_id'] !== (int) $lead->status_id) {
                $updates['status_id'] = $data['new_status_id'];
            }

            if (array_key_exists('reminder_date', $data)) {
                // The database is in UTC: convert any sent offset before saving.
                $new = filled($data['reminder_date']) ? Carbon::parse($data['reminder_date'])->utc() : null;
                if ($new?->format('Y-m-d H:i') !== $lead->reminder_date?->format('Y-m-d H:i')) {
                    $updates['reminder_date'] = $new;
                }
            }

            if ($updates) {
                $lead->update($updates);
            }

            return $call;
        });
    }

    /** Same computation as the form's "quick reminder" buttons. */
    public static function reminderInBusinessDays(int $days): Carbon
    {
        return BusinessDays::addBusinessDays($days)->setHour(9)->setMinute(0);
    }
}
```

- [x] In `LeadActions::callHistory()`, replace the body of `->action(...)` with:

```php
->action(function (Lead $record, array $data): void {
    // The form always sends every lead field; empty ones are saved as empty, as before.
    $data += array_fill_keys(CallLogger::LEAD_FIELDS, null);
    $data['ios'] ??= false;
    $data['reminder_date'] = ! empty($data['reminder_date']) ? $data['reminder_date'] : null;
    CallLogger::log($record, auth()->user(), $data);

    Notification::make()->title('تم تسجيل المكالمة بنجاح')->success()->send();
});
```

- [x] In the `reminder_preset` `afterStateUpdated` closures (call form and `scheduleReminder`), use `CallLogger::reminderInBusinessDays((int) $state)` so there is one source for this computation.
- [x] Run the existing tests that use the form (`TodayRemindersPageTest`, `LeadDetailsPageTest`, `LeadStatusChangeTest`). They must still pass unchanged.

> Note: the quick-reminder buttons compute 09:00 in **UTC** (10:00 in Algiers). `CallLogger` keeps exactly the same behavior so the CRM and the app agree. Fixing the hour is a separate change, with its own tests.

## 0.7 Config for the app

- [x] `config/mobile.php`:

```php
<?php

return [
    // Latest mobile app release (APK hosted on this server).
    'app' => [
        'version_code' => (int) env('MOBILE_APP_VERSION_CODE', 1),
        'version_name' => env('MOBILE_APP_VERSION_NAME', '1.0.0'),
        'min_version_code' => (int) env('MOBILE_APP_MIN_VERSION_CODE', 1),
        'apk_url' => env('MOBILE_APP_APK_URL'),
        'sha256' => env('MOBILE_APP_APK_SHA256'),
    ],
    // Lead source name for leads created from an incoming call (shown in the UI).
    'incoming_call_source' => 'مكالمة واردة',
];
```

- [x] Add the `MOBILE_APP_*` keys to `.env.example`.

## 0.8 Routes, limits, and blocking disabled users

- [x] `app/Http/Middleware/EnsureUserIsActive.php`: if `! $request->user()->is_active`, delete the current token and return **401** `{"message": "الحساب معطّل"}`.
- [x] `User::booted()`: when `is_active` changes to `false`, run `$user->tokens()->delete()`.
- [x] Rate limiters in `AppServiceProvider::boot()`:

```php
RateLimiter::for('mobile-login', fn (Request $r) => Limit::perMinute(5)->by(strtolower((string) $r->input('email')).'|'.$r->ip()));
RateLimiter::for('mobile', fn (Request $r) => Limit::perMinute(120)->by($r->user()?->id ?: $r->ip()));
```

- [x] `routes/api.php` (keep the two existing routes):

```php
use App\Http\Controllers\Api\Mobile;
use App\Http\Middleware\EnsureUserIsActive;

Route::prefix('mobile')->name('api.mobile.')->group(function () {
    Route::post('login', [Mobile\AuthController::class, 'login'])
        ->middleware('throttle:mobile-login')->name('login');

    Route::middleware(['auth:sanctum', EnsureUserIsActive::class, 'throttle:mobile'])->group(function () {
        Route::post('logout', [Mobile\AuthController::class, 'logout'])->name('logout');
        Route::get('directory', Mobile\DirectoryController::class)->name('directory');
        Route::get('lookup', Mobile\LookupController::class)->name('lookup');
        Route::post('leads', [Mobile\LeadController::class, 'store'])->name('leads.store');
        Route::post('leads/{lead}/calls', [Mobile\CallController::class, 'store'])->name('leads.calls.store');
        Route::post('leads/{lead}/requests', [Mobile\RequestController::class, 'store'])->name('leads.requests.store');
        Route::get('app-version', Mobile\AppVersionController::class)->name('app-version');
    });
});
```

## 0.9 Controllers (`app/Http/Controllers/Api/Mobile/`)

Follow the request and response shapes in `api-contract.md` exactly.

- [x] **`AuthController`**
  - `login`: validate `email`, `password`, `device_name` (max 100). Check with `Hash::check`. If `! is_active`, return 403. Delete existing tokens with the same `device_name`, then `createToken($device_name)`. Return `token` + `user {id, name, role}`.
  - `logout`: `$request->user()->currentAccessToken()->delete()`, then 204.
- [x] **`CallerCardResource`** (`app/Http/Resources/CallerCardResource.php`): builds the `lead` object. It takes a `full` flag: when `false`, return only `id, name, company, status, assigned_to`. Eager-load everything in one go:

```php
$lead->load([
    'leadStatus', 'assignedAgent:id,name', 'wilaya:id,name', 'tags:id,name',
    'latestCallLog.user:id,name', 'latestSubscription.payments',
    'userNotes' => fn ($q) => $q->whereNotIn('status', UserNote::DONE_STATUSES)->latest()->limit(3),
]);
```

  - `open_requests_count`: a separate `count()` with the same filter.
  - `subscription.paid`: sum the loaded `payments`. Don't use the `total_paid` accessor, which runs its own query.
  - `crm_url`: `LeadResource::getUrl('view', ['record' => $lead])`. `whatsapp_url`: `Phone::whatsappUrl($lead->whatsapp_phone)`.
  - `follow_up_if_unreachable`: from `FollowUpStrategy::nextStepFor($lead, 'no_answer')`, plus `archive_status_id = LeadStatus::where('slug', FollowUpStrategy::ARCHIVE_STATUS_SLUG)->value('id')` when the action is `archive`.
- [x] **`LookupController`**: `Phone::normalize($request->query('phone'))`. If `null`, return 422. Then `Lead::findByAnyPhone()`. Not found: `{found:false, phone}`. Found: `full_access = $request->user()->can('view', $lead)`.
- [x] **`DirectoryController`**
  - One query: `Lead::select(['id','whatsapp_phone','secondary_phone','customer_name','company_name','status_id','assigned_user_id'])->with(['leadStatus:id,name,stage','assignedAgent:id,name'])`.
  - Build one entry per normalized phone. If a phone repeats, keep the one from `whatsapp_phone`.
  - `ETag` = `sha1(Lead::max('updated_at').'|'.Lead::count().'|'.LeadStatus::max('updated_at').'|'.User::max('updated_at'))`. If it matches `If-None-Match`, return 304.
  - Add `statuses` (ordered by `sort_order`).
- [x] **`CallController::store`**: `Gate::authorize('update', $lead)`. Validate per the contract (both reminder fields together → 422). Turn `reminder_in_business_days` into `reminder_date` with `CallLogger::reminderInBusinessDays()`. Map `result` → `status`. Then `CallLogger::log($lead, $request->user(), $data)` and return 201 with `call_id` + the full card.
- [x] **`LeadController::store`**: normalize the phone (422 if invalid). If `Lead::findByAnyPhone()` finds a lead, return 409 with `lead_id`. Otherwise, in a transaction:
  - `Lead::create([...'whatsapp_phone' => $normalized, 'source_id' => LeadSource::firstOrCreate(['name' => config('mobile.incoming_call_source')])->id])`
  - `CallLogger::log($lead, $user, ['status' => 'available', 'notes' => $notes])`, which auto-assigns the lead to the agent.
  - Return 201 `{lead_id}`.
  - Note: the existing `Lead::created` hook sends «🆕 زبون جديد» to all users, because the new lead has no status. That's the normal CRM behavior. Keep it.
- [x] **`RequestController::store`**: `Gate::authorize('update', $lead)`, validate, then:

```php
$note = $lead->userNotes()->create(UserNoteResource::prepareCreateData([
    'title' => $data['title'], 'content' => $data['content'],
    'category' => 'client_request', 'priority' => $data['priority'],
]));
```

- [x] **`AppVersionController`**: return `config('mobile.app')`.

## 0.10 Tests (`tests/Feature/Mobile/`)

Use `Laravel\Sanctum\Sanctum::actingAs($user)`, `RefreshDatabase`, and `$this->seed(LeadStatusSeeder::class)`, like the existing feature tests.

- [x] `AuthTest`
  - Login works and returns a token.
  - Wrong password → 422.
  - Disabled user → 403.
  - The same `device_name` replaces the old token.
  - Logout deletes the token: the next request → 401.
  - Disabling a user deletes their tokens.
  - An unauthenticated request → 401 JSON, not a redirect.
- [x] `LookupTest`
  - The same lead is found by `0555…`, `+213555…`, `213555…`, `00213555…`, and through `secondary_phone`.
  - An unknown number → `found:false`.
  - `abc` → 422.
  - An agent sees another agent's lead with `full_access:false` and no `last_call` / `subscription` keys.
  - With `SystemSetting` `leads_shared` on → `full_access:true`.
  - An admin always gets `full_access:true`.
  - `open_requests` excludes resolved/closed notes.
  - `subscription.remaining` is right after two payments.
- [x] `DirectoryTest`
  - One entry per phone.
  - A duplicate phone keeps the `whatsapp_phone` lead.
  - Sending back the `ETag` → 304.
  - Updating a lead changes the ETag.
- [x] `CallTest`
  - A call is created with `user_id` = the token's user.
  - `last_contacted_at` is updated.
  - An unassigned lead becomes assigned to the caller.
  - A status change writes a `LeadStatusLog` with `user_id` = the caller (this proves `auth()` works under Sanctum).
  - `reminder_in_business_days: 3` gives the same date as the CRM button.
  - `reminder_date: null` clears the reminder. An omitted `reminder_date` keeps it.
  - Both reminder fields → 422.
  - Another agent's lead → 403.
- [x] `CreateLeadTest`
  - 201 creates the lead (source «مكالمة واردة») + 1 call + assigns it.
  - An existing phone in any format → 409 with `lead_id`.
- [x] `RequestTest`
  - Creates a `UserNote` (`client_request`, `status` `new`, `user_id` = caller).
  - Another agent's lead → 403.
- [x] `tests/Unit/PhoneTest.php`: landline rows.

## 0.11 Admin page «الأجهزة المرتبطة»

- [x] `app/Filament/Pages/ConnectedDevices.php`
  - A Filament page with a table (`HasTable`) over `Laravel\Sanctum\PersonalAccessToken::query()->with('tokenable')`.
  - Admins only: `canAccess(): auth()->user()?->isAdmin()`. Navigation group «الإعدادات» (or next to `Settings`).
  - Columns: المستخدم (`tokenable.name`), الجهاز (`name`), آخر استخدام (`last_used_at`, `since()`), تاريخ الربط (`created_at`).
  - Row action «قطع الاتصال» (with confirmation) → `$record->delete()`.
- [x] Test: an admin sees the page, an agent gets 403.

## 0.12 Try it by hand

```bash
php artisan serve --host=0.0.0.0 --port=8000

TOKEN=$(curl -s -X POST http://localhost:8000/api/mobile/login \
  -H 'Accept: application/json' -H 'Content-Type: application/json' \
  -d '{"email":"agent@test.dz","password":"password","device_name":"curl"}' | jq -r .token)

curl -s "http://localhost:8000/api/mobile/lookup?phone=%2B213555000001" \
  -H 'Accept: application/json' -H "Authorization: Bearer $TOKEN" | jq
curl -s -D - -o /dev/null http://localhost:8000/api/mobile/directory \
  -H 'Accept: application/json' -H "Authorization: Bearer $TOKEN"
```

(`%2B` is `+` in a URL. The app must URL-encode the phone too.)

## 0.13 Deploy

Production: **https://suivi-client.valleycom-s2.com** (HTTPS certificate valid; on 2026-11-09 `/api/mobile/app-version` still answers 404, so the mobile API is not deployed yet).


- [ ] Merge, then on the server: `composer install --no-dev -o`, `php artisan migrate --force`, `php artisan config:cache`.
- [ ] The site must be **HTTPS**. The app refuses plain HTTP in release builds.
- [ ] Run `php artisan leads:normalize-phones --dry-run`, review, then run it without `--dry-run`.
- [ ] Create the folder `public/downloads/` for the APK (used from Phase 1). In the production `.env`: `MOBILE_APP_APK_URL=https://suivi-client.valleycom-s2.com/downloads/saascom-caller-1.0.0.apk`.
- [ ] Check: `curl -s -H 'Accept: application/json' https://suivi-client.valleycom-s2.com/api/mobile/app-version` answers **401** (route exists, token required) instead of 404.
- [x] Update the CRM's `CLAUDE.md`: the mobile API section, `CallLogger`, and `findByAnyPhone`.
