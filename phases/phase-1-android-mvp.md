# Phase 1: Android MVP (the card while it rings)

**Where:** `/home/yacine/Desktop/work/truecaller` · **Time:** ~2 weeks · **Needs:** Phase 0 (running locally is enough to start)

**Goal:** when a client calls, a card with the CRM info appears over the call screen in under 1 second, even if the app is closed. It disappears when the call ends.

**Done when:**
- [ ] Login with a CRM account works, and the client list syncs
- [ ] A call from a client number shows the card (name + status in < 1 s, full details shortly after)
- [ ] It works with the app closed, the screen locked, and the number saved in contacts
- [ ] An unknown number shows «رقم غير موجود في CRM»
- [ ] The card disappears when the call ends
- [ ] Tested on at least 1 Samsung + 1 Xiaomi/Redmi + 1 Oppo/Realme
- [ ] Release APK signed and uploaded to the CRM server

---

## 1.1 Tools

- [ ] Android Studio (latest stable), with its bundled JDK
- [ ] A test phone with Android 10+ and USB debugging on. You'll need the emulator too.
- [ ] CRM running locally: `php artisan serve --host=0.0.0.0 --port=8000`. From the emulator it is `http://10.0.2.2:8000`. From a real phone on the same Wi-Fi it is `http://<PC-IP>:8000`.

## 1.2 Create the project

- [ ] Android Studio → New Project → **Empty Activity** (Compose)
  - Name `SAASCOM Caller`, package `com.saascom.caller`
  - Min SDK **29** (Android 10, needed for the call-screening role)
  - Build config: Kotlin DSL
- [ ] `git init` in `truecaller/`, with the default Android `.gitignore`. Also ignore `*.jks` and `keystore.properties`.
- [ ] Dependencies (latest stable versions, in `gradle/libs.versions.toml`):
  - Compose BOM, Material 3, Navigation Compose, Lifecycle (runtime-compose, viewmodel-compose)
  - Retrofit + `converter-kotlinx-serialization`, OkHttp + logging-interceptor (debug only), kotlinx-serialization-json
  - Room (runtime, ktx, compiler via **KSP**)
  - WorkManager (`work-runtime-ktx`)
  - DataStore Preferences
  - Coroutines (android)
  - Tests: JUnit, kotlinx-coroutines-test, Room testing, MockWebServer
- [ ] `buildConfigField` for the CRM URL:

```kotlin
android {
    buildFeatures { buildConfig = true; compose = true }
    buildTypes {
        debug   { buildConfigField("String", "CRM_URL", "\"http://10.0.2.2:8000\"") }
        release { buildConfigField("String", "CRM_URL", "\"https://YOUR-CRM-DOMAIN\"") }
    }
}
```

- [ ] Allow plain HTTP **only in debug**: `src/debug/res/xml/network_security_config.xml` with `cleartextTrafficPermitted="true"` for `10.0.2.2` and your PC's IP, referenced from `src/debug/AndroidManifest.xml`. Release builds stay HTTPS-only.

## 1.3 Folder structure

```
app/src/main/java/com/saascom/caller/
├─ App.kt                        ← Application: creates the singletons below (simple manual DI)
├─ MainActivity.kt               ← Compose host + navigation
├─ ui/
│  ├─ theme/                     ← colors, font, forced RTL
│  ├─ LoginScreen.kt
│  ├─ OnboardingScreen.kt        ← permissions step by step
│  ├─ HomeScreen.kt              ← "ready" status, last sync, "test card" button
│  ├─ SettingsScreen.kt
│  └─ components/CallerCard.kt   ← one Composable used by the overlay AND the screens
├─ call/
│  ├─ CallerScreeningService.kt  ← Android gives us the number here
│  ├─ CallerCardController.kt    ← local match → overlay → API → update
│  ├─ OverlayCard.kt             ← draws CallerCard over the call screen
│  └─ CallStateWatcher.kt        ← knows when the call ends
├─ data/
│  ├─ Api.kt                     ← Retrofit interface + DTOs (api-contract.md)
│  ├─ ApiClient.kt               ← OkHttp: headers, timeouts, 401 handling
│  ├─ db/AppDatabase.kt, DirectoryDao.kt, DirectoryEntry.kt
│  ├─ DirectoryRepository.kt     ← sync + find(phone)
│  ├─ SyncWorker.kt
│  ├─ Session.kt                 ← encrypted token
│  └─ SettingsStore.kt           ← user settings (DataStore)
└─ util/PhoneNormalizer.kt
```

## 1.4 Arabic and right-to-left

- [ ] All texts in `res/values/strings.xml`, in Arabic (default language). Reuse the CRM's words: «متوفر», «لا يجيب», «مهتم», «طلب عميل»…
- [ ] Force RTL even when the phone is in French or English: in the theme root, wrap the content in `CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) { … }`. Do the same in the overlay.
- [ ] Phone numbers and amounts are left-to-right inside Arabic text. Wrap them with `⁨…⁩` (bidi isolate) or show them in their own `Text`.
- [ ] Optional font: IBM Plex Sans Arabic (in `res/font/`).

## 1.5 `PhoneNormalizer.kt` (start here: it's small and testable)

- [ ] A Kotlin port of `App\Support\Phone::normalize()`, **including the landline rule from Phase 0**:

```kotlin
object PhoneNormalizer {
    private val separators = Regex("""[\s\-.()]""")
    private val dzMobile = Regex("""^\+?213(\d{9})$""")
    private val dzLandline = Regex("""^\+213(\d{8})$""")
    private val valid = Regex("""^\+?\d{6,15}$""")

    fun normalize(raw: String?): String? {
        var p = raw?.trim()?.removePrefix("p:")?.removePrefix("P:") ?: return null
        p = p.replace(separators, "")
        if (p.startsWith("00")) p = "+" + p.substring(2)
        dzMobile.matchEntire(p)?.let { p = "0" + it.groupValues[1] }
        dzLandline.matchEntire(p)?.let { p = "0" + it.groupValues[1] }
        return if (valid.matches(p)) p else null
    }
}
```

- [ ] `PhoneNormalizerTest`: **every row** of the table in `api-contract.md`.

## 1.6 Session (encrypted token)

- [ ] `Session.kt`: keep `token`, `userId`, `userName`, `role` in DataStore. Encrypt the token with an AES-GCM key from the **Android Keystore** (`KeyGenParameterSpec`, alias `saascom_session`). Store `iv + ciphertext` as Base64.
- [ ] `isLoggedIn`, `save(...)`, `clear()`. `clear()` also deletes the Room database contents.
- [ ] Synchronous read from memory: the screening service needs the token instantly. Load it once into an in-memory `@Volatile` field at `App.onCreate()`.

## 1.7 API client

- [ ] `Api.kt`: Retrofit interface with the 8 endpoints and `@Serializable` DTOs that match `api-contract.md` (use `@SerialName` for snake_case). Phase 1 uses `login`, `logout`, `directory`, `lookup`, and `appVersion`.
- [ ] `ApiClient.kt` (OkHttp):
  - Interceptor adds `Accept: application/json`, `Authorization: Bearer …`, `X-App-Version: ${BuildConfig.VERSION_CODE}`.
  - On **401**: `Session.clear()` and emit a "logged out" event. The UI goes to Login.
  - Timeouts: connect 2 s, read 3 s. The card must not wait longer.
- [ ] `ignoreUnknownKeys = true` in the Json config, so the CRM can add fields without breaking old app versions.

## 1.8 Local client list (Room) + sync

- [ ] Entity `DirectoryEntry(phone PK, leadId, name?, company?, stage, statusLabel, owner?)`. Statuses (`id, name, stage`) go into a second small table, or as JSON in DataStore.
- [ ] `DirectoryDao`: `find(phone): DirectoryEntry?`, `replaceAll(list)` in one `@Transaction` (delete all + insert all), `search(query)` for Phase 2.
- [ ] `DirectoryRepository.sync()`: `GET /directory` with `If-None-Match`. On 304, nothing to do. On 200, `replaceAll` + save the ETag + `lastSyncAt`.
- [ ] `SyncWorker` (CoroutineWorker): `PeriodicWorkRequest` every **2 h**, network required, `ExistingPeriodicWorkPolicy.KEEP`. Also run a one-time sync right after login and when the user taps «تحديث».

## 1.9 Getting the number: `CallerScreeningService`

- [ ] Manifest:

```xml
<uses-permission android:name="android.permission.INTERNET" />
<uses-permission android:name="android.permission.SYSTEM_ALERT_WINDOW" />
<uses-permission android:name="android.permission.READ_PHONE_STATE" />
<uses-permission android:name="android.permission.READ_CONTACTS" />
<uses-permission android:name="android.permission.POST_NOTIFICATIONS" />
<uses-permission android:name="android.permission.REQUEST_IGNORE_BATTERY_OPTIMIZATIONS" />

<service
    android:name=".call.CallerScreeningService"
    android:permission="android.permission.BIND_SCREENING_SERVICE"
    android:exported="true">
    <intent-filter>
        <action android:name="android.telecom.CallScreeningService" />
    </intent-filter>
</service>
```

- [ ] Service:

```kotlin
class CallerScreeningService : CallScreeningService() {
    override fun onScreenCall(details: Call.Details) {
        // 1) Never block: answer "allow" immediately.
        respondToCall(details, CallResponse.Builder().build())

        // 2) Incoming calls only (Phase 3 can add outgoing).
        if (details.callDirection != Call.Details.DIRECTION_INCOMING) return

        val phone = PhoneNormalizer.normalize(details.handle?.schemeSpecificPart) ?: return  // hidden number
        CallerCardController.onIncomingCall(applicationContext, phone)
    }
}
```

> Android only sends calls from **numbers saved in contacts** to this service if the app has `READ_CONTACTS`. That's why the onboarding asks for it.
> Only **one** app on the phone can hold the "Caller ID & spam" role. If Truecaller holds it, ours replaces it.

## 1.10 `CallerCardController`: local first, then the CRM

```kotlin
object CallerCardController {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    @Volatile var current: CurrentCall? = null   // used by Phase 2 (after-call)

    fun onIncomingCall(ctx: Context, phone: String) {
        if (!Session.isLoggedIn) return
        scope.launch {
            val local = withContext(Dispatchers.IO) { Directory.find(phone) }       // ~5 ms
            val askCrm = local != null || Settings.lookupUnknownNumbers           // privacy setting
            if (local == null && !askCrm) return@launch

            current = CurrentCall(phone, local?.leadId)
            OverlayCard.show(ctx, CardState.fromLocal(phone, local))             // name + status now
            CallStateWatcher.start(ctx) { onCallEnded(ctx) }

            if (askCrm) {
                val result = runCatching { withContext(Dispatchers.IO) { Api.lookup(phone) } }
                OverlayCard.update(
                    result.fold(
                        onSuccess = { CardState.fromLookup(it) },
                        onFailure = { CardState.offline(phone, local) }          // «غير متصل»
                    )
                )
            }
        }
    }

    private fun onCallEnded(ctx: Context) {
        OverlayCard.hide(ctx)
        CallStateWatcher.stop(ctx)
        // Phase 2: after-call prompt here
    }
}
```

- [ ] `CardState` has 5 shapes: **Loading** (local info only), **Full**, **Limited** (`full_access:false`, shows «عميل لدى أمين»), **Unknown** («رقم غير موجود في CRM»), **Offline** (local info + «غير متصل»).

## 1.11 `OverlayCard`: draw over the call screen

- [ ] `WindowManager.addView` with:

```kotlin
WindowManager.LayoutParams(
    MATCH_PARENT, WRAP_CONTENT,
    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
    FLAG_NOT_FOCUSABLE or FLAG_LAYOUT_IN_SCREEN,
    PixelFormat.TRANSLUCENT
).apply { gravity = Gravity.TOP; y = dp(120) }
```

- [ ] Check on each test phone that the card also shows over the **lock-screen** call screen. Some brands behave differently. If it doesn't show there, the notification fallback (below) must carry the name + status.

- [ ] The content is a `ComposeView` showing `CallerCard(state)`. **Gotcha:** a `ComposeView` outside an Activity crashes unless you give it owners. Create a small `OverlayLifecycleOwner` (implements `LifecycleOwner` + `SavedStateRegistryOwner`), move it to `RESUMED`, then call `view.setViewTreeLifecycleOwner(owner)` and `view.setViewTreeSavedStateRegistryOwner(owner)`.
- [ ] Keep the state in a `MutableStateFlow<CardState>` so `update()` just changes the value.
- [ ] Buttons on the card:
  - ✕ (close)
  - «فتح في CRM» (`crm_url` in the browser)
  - «واتساب» (`whatsapp_url`)
- [ ] Safety:
  - Check `Settings.canDrawOverlays(ctx)` before `addView`. If it's missing, show a **high-priority notification** with the name + status instead.
  - Auto-hide after 3 minutes if the end of the call was somehow missed.
- [ ] Show card dates as «منذ 3 أيام» in the `Africa/Algiers` timezone.

## 1.12 `CallStateWatcher`: know when the call ends

- [ ] Android 12+ (API 31): `TelephonyManager.registerTelephonyCallback(executor, object : TelephonyCallback(), TelephonyCallback.CallStateListener { … })`.
- [ ] Android 10–11: `PhoneStateListener` with `LISTEN_CALL_STATE` (deprecated but needed there).
- [ ] Record `RINGING → OFFHOOK` (answered) and `→ IDLE` (ended). Phase 2 uses `answered` and `durationSeconds`.
- [ ] Needs `READ_PHONE_STATE`.
- [ ] If the system kills the app during the call (test this on Xiaomi), run a short foreground service while the card is visible. Only add it if testing shows it's needed.

## 1.13 Onboarding: permissions step by step

`OnboardingScreen` shows one row per step with ✓ / «تفعيل». It re-checks everything in `onResume`. The app is "ready" only when steps 1–4 are ✓.

| # | Step (Arabic label) | How to check | How to ask |
|---|---|---|---|
| 1 | تطبيق تعريف المتصل | `RoleManager.isRoleHeld(ROLE_CALL_SCREENING)` | `startActivityForResult(roleManager.createRequestRoleIntent(ROLE_CALL_SCREENING))` |
| 2 | الظهور فوق التطبيقات | `Settings.canDrawOverlays()` | `Intent(ACTION_MANAGE_OVERLAY_PERMISSION, "package:$pkg".toUri())` |
| 3 | حالة الهاتف | `READ_PHONE_STATE` granted | runtime permission |
| 4 | جهات الاتصال | `READ_CONTACTS` granted | runtime permission (explain: «لإظهار البطاقة حتى للأرقام المحفوظة») |
| 5 | الإشعارات (Android 13+) | `POST_NOTIFICATIONS` granted | runtime permission |
| 6 | البطارية بلا قيود | `PowerManager.isIgnoringBatteryOptimizations()` | `ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` |
| 7 | التشغيل التلقائي (Xiaomi/Oppo/…) | can't be checked; the user taps «تم» | open the brand's autostart screen, with fallback to app details |

- [ ] Step 7 is shown only when `Build.MANUFACTURER` is xiaomi/redmi/poco/oppo/realme/vivo/huawei/honor. On Xiaomi, also tell the user to enable «عرض النوافذ المنبثقة أثناء العمل في الخلفية» (App info → Other permissions).
- [ ] Brand autostart intents (try each, catch `ActivityNotFoundException`, and fall back to `ACTION_APPLICATION_DETAILS_SETTINGS`):
  - Xiaomi: `com.miui.securitycenter/com.miui.permcenter.autostart.AutoStartManagementActivity`
  - Oppo/Realme: `com.coloros.safecenter/.permission.startup.StartupAppListActivity`
  - Vivo: `com.vivo.permissionmanager/.activity.BgStartUpManagerActivity`
  - Huawei: `com.huawei.systemmanager/.startupmgr.ui.StartupNormalAppListActivity`

## 1.14 Screens in this phase

- [ ] **Login:** email, password, «دخول». `device_name` = `"${Build.MANUFACTURER} ${Build.MODEL} · ${userName}"`. After login: sync → Onboarding (if not ready) → Home.
- [ ] **Home:**
  - Status «جاهز ✓» or «ينقص إذن» (opens Onboarding)
  - Number of clients in the list + last sync time
  - «تحديث القائمة» button
  - «تجربة البطاقة» button: shows the overlay with a sample client, so you can test without a call
- [ ] **Settings:**
  - Toggle «سؤال CRM عن الأرقام غير المسجلة» (default **on**)
  - «فحص الأذونات»
  - App version
  - «تسجيل الخروج» (`/logout` + `Session.clear()`)

## 1.15 Testing

- [ ] Unit tests:
  - `PhoneNormalizer` (contract table)
  - `DirectoryDao` (Room in-memory)
  - `CallerCardController` with a fake API (found / limited / unknown / timeout → offline)
  - `ApiClient` with MockWebServer (401 → logout, `ignoreUnknownKeys`)
- [ ] Emulator: Extended controls (⋯) → **Phone** → type a number from the CRM → **Call device**. Grant the role and overlay first.
- [ ] Real phones, for each one tick:
  - [ ] App closed (swiped away)
  - [ ] Screen locked
  - [ ] Number saved in contacts
  - [ ] No internet (card shows «غير متصل» with local info)
  - [ ] Hidden number (nothing shown, no crash)
  - [ ] Dual SIM: call on SIM 2
  - [ ] After a reboot
- [ ] Device matrix: Samsung (One UI), Xiaomi/Redmi (HyperOS/MIUI), Oppo or Realme (ColorOS). Use at least one Android 10–11 and one Android 14+.

## 1.16 Release build and distribution

- [ ] Create the signing key **once**: Build → Generate Signed App Bundle/APK → new keystore. **Back it up in two safe places.** If it's lost, phones can't install updates. Keep the passwords in `keystore.properties` (git-ignored).
- [ ] `versionCode` +1 for every release.
- [ ] `./gradlew assembleRelease` gives `app/build/outputs/apk/release/app-release.apk`.
- [ ] Copy it to the CRM as `public/downloads/saascom-caller-<version>.apk`. Run `sha256sum` on it and set the `MOBILE_APP_*` values in the CRM `.env`, then `php artisan config:cache`.
- [ ] Install on agents' phones: open the link → allow «تثبيت تطبيقات غير معروفة» for the browser → install → login → onboarding.
