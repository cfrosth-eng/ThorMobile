package com.example.thormobile

import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.os.Bundle
import android.os.SystemClock
import android.view.View
import android.view.WindowManager
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity

/**
 * WO-159: Instant Full-Screen Living Mjolnir Storm Screensaver Launcher (Portrait + Landscape Full Sensor).
 * Double-tap anywhere (or press Back) to exit — prevents accidental exit while rotating your phone.
 */
class ThorScreensaverActivity : AppCompatActivity() {

    private var stormView: ThorMjolnirScreensaverView? = null
    private var lastTapElapsedMs: Long = 0L
    private var lastHintElapsedMs: Long = 0L

    @Suppress("DEPRECATION")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_FULL_SENSOR
        window.addFlags(
            WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON or
                WindowManager.LayoutParams.FLAG_FULLSCREEN
        )
        val view = ThorMjolnirScreensaverView(this).apply {
            setOnClickListener {
                val now = SystemClock.elapsedRealtime()
                if (now - lastTapElapsedMs <= 480L) {
                    finish()
                } else {
                    lastTapElapsedMs = now
                    if (now - lastHintElapsedMs >= 4500L) {
                        lastHintElapsedMs = now
                        Toast.makeText(
                            this@ThorScreensaverActivity,
                            "Double-tap anywhere to exit Mjolnir Storm",
                            Toast.LENGTH_SHORT,
                        ).show()
                    }
                }
            }
        }
        stormView = view
        setContentView(view)
        applyImmersiveUi()
    }

    override fun onResume() {
        super.onResume()
        applyImmersiveUi()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        applyImmersiveUi()
        stormView?.requestLayout()
        stormView?.invalidate()
    }

    override fun onDestroy() {
        stormView?.releaseBitmaps()
        stormView = null
        super.onDestroy()
    }

    @Suppress("DEPRECATION")
    private fun applyImmersiveUi() {
        window.decorView.systemUiVisibility = (
            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or
                View.SYSTEM_UI_FLAG_FULLSCREEN or
                View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
                View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
                View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
            )
    }
}
