# SAASCOM Caller: implementation phases

An **Android app** that shows the client's CRM file on top of the call screen when a client calls someone from the sales or SAV team. After the call, the agent logs it into the CRM with one tap.

Overview and mockups: [`../project-plan.html`](../project-plan.html)

## Two codebases

| Part | Folder | Tech |
|---|---|---|
| CRM (already exists) | `/home/yacine/Desktop/work/soft_client_suivi` | Laravel 12 + Filament 3.3, PHP 8.2+ |
| Android app (new) | `/home/yacine/Desktop/work/truecaller` | Kotlin + Jetpack Compose, Android 10+ |

The two talk only through the HTTP API described in [`api-contract.md`](api-contract.md). **Change that file first** whenever the API changes.

## Order

| # | File | Where | Time | Needs | Done when |
|---|---|---|---|---|---|
| 0 | [`phase-0-crm-api.md`](phase-0-crm-api.md) | CRM | ~3 days | nothing | `curl …/api/mobile/lookup?phone=…` returns the right card for every phone format, and all tests pass |
| 1 | [`phase-1-android-mvp.md`](phase-1-android-mvp.md) | App | ~2 weeks | Phase 0 | A call from a client shows the card in under 1 s, with the app closed |
| 2 | [`phase-2-after-call.md`](phase-2-after-call.md) | App (+ small CRM bits) | ~1 week | Phase 1 | A call logged from the phone appears in the lead's call history in the CRM |
| 3 | [`phase-3-extras.md`](phase-3-extras.md) | Both | later | Phase 2 | Pick items one by one |

Times are rough estimates for one developer.

## Rules for every phase

1. **Never block or delay a call.** The app only shows information.
2. **One phone format.** The CRM stores Algerian numbers as `0XXXXXXXXX` and foreign ones as `+XXXXXXXX` (`App\Support\Phone::normalize()`). The app's `PhoneNormalizer.kt` must give **exactly** the same results. The shared test table is in `api-contract.md`.
3. **Dates.** The API sends and receives UTC ISO-8601 (`2026-11-15T08:00:00Z`). The app displays them in `Africa/Algiers`.
4. **Arabic UI, right-to-left.** Reuse the CRM's labels (`CallLog::STATUSES`, `LeadStatus::STAGES`, `UserNote::PRIORITIES`) so both sides use the same words.
5. **Same visibility as the CRM.** Use `LeadPolicy` / `Lead::visibleTo()`. Never invent new access rules in the API.
6. **CRM work happens on a branch** (`feature/mobile-api`). Tests run with `composer test` against the `_test` database (see the CRM's `CLAUDE.md`).
7. **Small steps.** Each numbered task in a phase file can be finished and tested on its own. Tick the boxes as you go.

## Decisions (defaults used until you change them)

| Question | Default in these files |
|---|---|
| Company phones or personal phones? | Company phones. "Ask the CRM about unknown numbers" is **on**. |
| Another agent's client calls: full details? | No. Name, status and owner only. Admins see everything. |
| Where do SAV requests go? | Existing notes: `UserNote`, category `client_request` («طلب عميل»). |
| How is the app installed? | APK hosted on the CRM server + in-app update check. No Play Store. |

## Using these files with Claude Code

Open a session in the right folder and point to the phase file, for example:

```
cd /home/yacine/Desktop/work/soft_client_suivi
claude "Implement /home/yacine/Desktop/work/truecaller/phases/phase-0-crm-api.md, task by task, with tests"
```
