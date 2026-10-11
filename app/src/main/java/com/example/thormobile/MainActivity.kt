package com.example.thormobile

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

/**
 * Setup screen for the always-listening Thor service. On every launch it walks through what the service needs, one
 * step at a time, and starts the service once the mandatory pieces are in place:
 *   1. overlay permission      (floating button)
 *   2. microphone permission   (RECORD_AUDIO; also required to START a microphone foreground service)
 *   3. notification permission (Android 13+; the foreground-service notification)
 *   4. battery-optimization exemption (so Doze does not freeze the wake-word listener with the screen off)
 * Samsung adds a separate "put unused apps to sleep" layer that no API can turn off; a one-time hint points the user
 * at the Samsung battery screen.
 */
class MainActivity : AppCompatActivity() {

    private val powerGuard by lazy { PowerGuard(this) }
    private var askedBatteryExemption = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        continueSetup()
    }

    override fun onResume() {
        super.onResume()
        // Returning from a system settings screen: carry on with the next step.
        continueSetup()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_PERMISSIONS) {
            if (!granted(Manifest.permission.RECORD_AUDIO)) {
                Toast.makeText(this, "Thor needs the microphone to hear you.", Toast.LENGTH_LONG).show()
            } else {
                continueSetup()
            }
        }
    }

    private fun granted(permission: String) =
        ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED

    private fun continueSetup() {
        if (!Settings.canDrawOverlays(this)) {
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
            return
        }

        val missing = mutableListOf<String>()
        if (!granted(Manifest.permission.RECORD_AUDIO)) missing += Manifest.permission.RECORD_AUDIO
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !granted(Manifest.permission.POST_NOTIFICATIONS)) {
            missing += Manifest.permission.POST_NOTIFICATIONS
        }
        if (missing.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, missing.toTypedArray(), REQ_PERMISSIONS)
            return
        }

        // Start (or re-start) the service from the foreground: Android 12+ forbids starting a mic service from the
        // background, and the mic permission is now granted.
        ContextCompat.startForegroundService(this, Intent(this, FloatingThorService::class.java))

        if (!powerGuard.isIgnoringBatteryOptimizations() && !askedBatteryExemption) {
            askedBatteryExemption = true
            try {
                startActivity(powerGuard.requestIgnoreBatteryOptimizationsIntent())
            } catch (e: Exception) {
                Log.w(TAG, "Battery-optimization dialog unavailable; opening the settings list instead.", e)
                powerGuard.openSamsungBatterySettings(this)
            }
            return
        }

        if (!powerGuard.isIgnoringBatteryOptimizations()) {
            Toast.makeText(
                this,
                "Allow Thor to run unrestricted in the background, or it may stop listening with the screen off.",
                Toast.LENGTH_LONG
            ).show()
            return
        }

        val prefs = getSharedPreferences(PREFS, MODE_PRIVATE)
        if (isSamsung() && !prefs.getBoolean(KEY_SAMSUNG_HINT_SHOWN, false)) {
            prefs.edit().putBoolean(KEY_SAMSUNG_HINT_SHOWN, true).apply()
            Toast.makeText(
                this,
                "Samsung: Settings > Battery > Background usage limits > Never auto sleeping apps > add Thor.",
                Toast.LENGTH_LONG
            ).show()
            powerGuard.openSamsungBatterySettings(this)
            return
        }

        finish()   // everything is in place; the floating button and wake word take over
    }

    private fun isSamsung() = Build.MANUFACTURER.equals("samsung", ignoreCase = true)

    companion object {
        private const val TAG = "ThorSetup"
        private const val REQ_PERMISSIONS = 42
        private const val PREFS = "thor_setup"
        private const val KEY_SAMSUNG_HINT_SHOWN = "samsung_hint_shown"
    }
}