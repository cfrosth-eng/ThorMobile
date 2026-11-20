package com.example.thormobile

import android.content.res.Configuration
import android.service.dreams.DreamService
import android.view.View

/**
 * WO-159: Native Android System Screensaver (DreamService) — Thor Mjolnir Storm.
 * Selectable under Android Settings -> Display -> Screen saver -> Thor Mjolnir Storm.
 * Automatically switches Wide/Tall Ultra-HD masters when rotating Portrait <-> Landscape.
 */
class ThorMjolnirDreamService : DreamService() {

    private var stormView: ThorMjolnirScreensaverView? = null

    @Suppress("DEPRECATION")
    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        isInteractive = false
        isFullscreen = true
        isScreenBright = true
        val view = ThorMjolnirScreensaverView(this).apply {
            systemUiVisibility = (
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or
                    View.SYSTEM_UI_FLAG_FULLSCREEN or
                    View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                    View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
                    View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
                    View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                )
        }
        stormView = view
        setContentView(view)
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        stormView?.requestLayout()
        stormView?.invalidate()
    }

    override fun onDetachedFromWindow() {
        stormView?.releaseBitmaps()
        stormView = null
        super.onDetachedFromWindow()
    }
}
