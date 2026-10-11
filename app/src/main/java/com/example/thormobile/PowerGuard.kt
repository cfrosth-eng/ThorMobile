package com.example.thormobile

import android.annotation.SuppressLint
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings
import android.util.Log

/**
 * Keeps the always-listening service alive with the screen off:
 *   1. a PARTIAL_WAKE_LOCK so the CPU keeps running while the phone is pocketed (held only while listening), and
 *   2. the battery-optimization exemption (Doze / App Standby), requested from the user.
 *
 * Samsung One UI adds its own "put unused apps to sleep" layer on top of stock Android that no API can switch off;
 * [openSamsungBatterySettings] opens the closest screen, but the user must still set Thor to "Never sleeping".
 */
class PowerGuard(context: Context) {

    private val appContext = context.applicationContext
    private val powerManager = appContext.getSystemService(Context.POWER_SERVICE) as PowerManager
    private var wakeLock: PowerManager.WakeLock? = null

    /** Idempotent. Deliberately has no timeout: the service releases it when listening stops or it is destroyed. */
    @SuppressLint("WakelockTimeout")
    fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Thor:WakeWord").apply {
            setReferenceCounted(false)
            acquire()
        }
        Log.d(TAG, "Partial wake lock acquired.")
    }

    fun releaseWakeLock() {
        val lock = wakeLock ?: return
        if (lock.isHeld) {
            lock.release()
            Log.d(TAG, "Partial wake lock released.")
        }
        wakeLock = null
    }

    val isWakeLockHeld: Boolean get() = wakeLock?.isHeld == true

    fun isIgnoringBatteryOptimizations(): Boolean =
        powerManager.isIgnoringBatteryOptimizations(appContext.packageName)

    /** System dialog: "Let Thor always run in the background?" (needs REQUEST_IGNORE_BATTERY_OPTIMIZATIONS). */
    @SuppressLint("BatteryLife")
    fun requestIgnoreBatteryOptimizationsIntent(): Intent =
        Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${appContext.packageName}"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    /**
     * Best-effort jump to Samsung's battery screen (Device care). Activity names differ between One UI versions, so
     * several are tried, then the stock "battery optimization" list. Returns true if something opened.
     */
    fun openSamsungBatterySettings(context: Context): Boolean {
        val candidates = listOf(
            Intent().setClassName("com.samsung.android.lool", "com.samsung.android.sm.battery.ui.BatteryActivity"),
            Intent().setClassName("com.samsung.android.lool", "com.samsung.android.sm.ui.battery.BatteryActivity"),
            Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS),
        )
        for (intent in candidates) {
            try {
                context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                return true
            } catch (e: ActivityNotFoundException) {
                Log.d(TAG, "Settings screen not available: ${intent.component ?: intent.action}")
            } catch (e: SecurityException) {
                Log.d(TAG, "Settings screen not permitted: ${intent.component ?: intent.action}")
            }
        }
        return false
    }

    companion object {
        private const val TAG = "ThorPower"
    }
}
