package com.aliven.cyclocomp.ui

import android.Manifest
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import com.aliven.cyclocomp.core.LocationLevel

/** Android-side checks and shortcuts for keeping GPS recording alive with the screen off. */
object LocationAccess {
    private const val PREFS = "cyclocomp_ride"

    fun level(context: Context): LocationLevel {
        if (!granted(context, Manifest.permission.ACCESS_FINE_LOCATION)) return LocationLevel.NONE
        // Before Android 10 there is no separate "all the time" permission.
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return LocationLevel.ALWAYS
        return if (granted(context, Manifest.permission.ACCESS_BACKGROUND_LOCATION)) LocationLevel.ALWAYS
        else LocationLevel.WHILE_IN_USE
    }

    /** True when Android will not pause the app to save battery. */
    fun batteryUnrestricted(context: Context): Boolean =
        context.getSystemService(PowerManager::class.java)?.isIgnoringBatteryOptimizations(context.packageName) ?: true

    /** On Android 11+ "Allow all the time" can only be chosen on the app's own settings page. */
    fun openAppSettings(context: Context) {
        context.startActivity(
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }

    /** Opens Android's one-tap dialog to exempt the app from battery optimisation. */
    fun requestBatteryExemption(context: Context) {
        context.startActivity(
            Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${context.packageName}"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }

    /** The "keep recording with the screen off" question is asked once, not at every start. */
    fun askedBefore(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean("asked_always", false)

    fun markAsked(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean("asked_always", true).apply()
    }
}
