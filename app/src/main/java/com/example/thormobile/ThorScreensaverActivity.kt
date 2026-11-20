package com.example.thormobile

import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.os.Bundle
import android.view.View
import android.view.WindowManager
import androidx.appcompat.app.AppCompatActivity

/**
 * WO-159: Instant Full-Screen Living Mjolnir Storm Screensaver Launcher (Portrait + Landscape Full Sensor).
 * Tap anywhere to exit cleanly.
 */
class ThorScreensaverActivity : AppCompatActivity() {

    private var stormView: ThorMjolnirScreensaverView? = null

    @Suppress("DEPRECATION")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_FULL_SENSOR
        window.addFlags(
            WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON or
                WindowManager.LayoutParams.FLAG_FULLSCREEN
        )
        val view = ThorMjolnirScreensaverView(this).apply {
            setOnClickListener { finish() }
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
