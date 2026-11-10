package com.saascom.caller.util

import android.Manifest
import android.annotation.SuppressLint
import android.app.role.RoleManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings

/** Checks and settings screens for the setup steps (phases/phase-1-android-mvp.md, 1.13). */
object Permissions {

    fun roleAvailable(context: Context): Boolean =
        context.getSystemService(RoleManager::class.java)?.isRoleAvailable(RoleManager.ROLE_CALL_SCREENING) == true

    fun roleHeld(context: Context): Boolean =
        context.getSystemService(RoleManager::class.java)?.isRoleHeld(RoleManager.ROLE_CALL_SCREENING) == true

    fun roleRequestIntent(context: Context): Intent =
        context.getSystemService(RoleManager::class.java).createRequestRoleIntent(RoleManager.ROLE_CALL_SCREENING)

    fun overlay(context: Context): Boolean = Settings.canDrawOverlays(context)

    fun overlayIntent(context: Context) =
        Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${context.packageName}"))

    fun granted(context: Context, permission: String): Boolean =
        context.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED

    fun phoneState(context: Context) = granted(context, Manifest.permission.READ_PHONE_STATE)

    fun contacts(context: Context) = granted(context, Manifest.permission.READ_CONTACTS)

    fun notifications(context: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU || granted(context, Manifest.permission.POST_NOTIFICATIONS)

    fun batteryUnrestricted(context: Context): Boolean =
        context.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(context.packageName)

    @SuppressLint("BatteryLife") // internal app, distributed outside the Play Store
    fun batteryIntent(context: Context) =
        Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${context.packageName}"))

    fun appDetailsIntent(context: Context) =
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}"))

    /** Brands that kill background apps unless "autostart" is on. */
    fun needsAutostart(): Boolean = Build.MANUFACTURER.lowercase() in AUTOSTART_BRANDS

    /** Brand autostart screens; try them in order, the app details page is the last resort. */
    fun autostartIntents(context: Context): List<Intent> =
        AUTOSTART_SCREENS.map { (pkg, cls) -> Intent().setComponent(ComponentName(pkg, cls)) } + appDetailsIntent(context)

    /** Steps 1–4 are required for the card to work. */
    fun isReady(context: Context): Boolean =
        roleHeld(context) && overlay(context) && phoneState(context) && contacts(context)

    private val AUTOSTART_BRANDS = setOf("xiaomi", "redmi", "poco", "oppo", "realme", "oneplus", "vivo", "huawei", "honor")

    private val AUTOSTART_SCREENS = listOf(
        "com.miui.securitycenter" to "com.miui.permcenter.autostart.AutoStartManagementActivity",
        "com.coloros.safecenter" to "com.coloros.safecenter.permission.startup.StartupAppListActivity",
        "com.coloros.safecenter" to "com.coloros.safecenter.startupapp.StartupAppListActivity",
        "com.oplus.safecenter" to "com.oplus.safecenter.permission.startup.StartupAppListActivity",
        "com.vivo.permissionmanager" to "com.vivo.permissionmanager.activity.BgStartUpManagerActivity",
        "com.iqoo.secure" to "com.iqoo.secure.ui.phoneoptimize.AddWhiteListActivity",
        "com.huawei.systemmanager" to "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity",
        "com.huawei.systemmanager" to "com.huawei.systemmanager.optimize.process.ProtectActivity",
    )
}
