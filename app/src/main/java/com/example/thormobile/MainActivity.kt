package com.example.thormobile

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Outline
import android.graphics.Shader
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewOutlineProvider
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import java.util.concurrent.Executors

/**
 * WO-218: NORSE THOR SOVEREIGN COCKPIT
 * - Chiseled gold serif Odin inscription ("Whosoever holds this hammer...") at top.
 * - Forged gold-beveled Mjolnir-on-Pedestal hero emblem directly below the inscription.
 * - Clean 2-column straight-down grid of 10 classic round floating Mjolnir orbs (minimal labels + live pills).
 * - Clean forged-iron Norse buttons for external platforms & cloud links (duplicate Google AI Studio removed).
 */
class MainActivity : AppCompatActivity() {

    private val CLOUD_URL = "https://api.thegodofthunder.com"
    private val SOVEREIGN_TOKEN = "9d943a8005992357c7d6ca29278e803b80521c4d0d703e1ad74fb141ac050d19"
    private val uiIo = Executors.newSingleThreadExecutor()

    private data class OrbTileRefs(
        val ringFrame: FrameLayout,
        val statusPill: TextView,
        val card: LinearLayout,
    )

    private var voiceOrbRefs: OrbTileRefs? = null
    private var displayShareOrbRefs: OrbTileRefs? = null
    private var torchOrbRefs: OrbTileRefs? = null
    private var rearCamOrbRefs: OrbTileRefs? = null
    private var ravenOrbRefs: OrbTileRefs? = null
    private var ravenCenterBadgeTv: TextView? = null

    private val toggleUiHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private val toggleUiTicker = object : Runnable {
        override fun run() {
            updateVoiceToggleButtonAppearance()
            toggleUiHandler.postDelayed(this, 450L)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        try { actionBar?.hide() } catch (_: Throwable) {}
        @Suppress("DEPRECATION")
        window.statusBarColor = Color.parseColor("#04060D")
        @Suppress("DEPRECATION")
        window.navigationBarColor = Color.parseColor("#04060D")

        requestNeededPermissions()
        ThorFlashlightAndLensService.ensureTorchListener(this)
        ObdLinkAutoWatchdog.startAutonomousWatchdog(CLOUD_URL)
        VikingPopupOverlayManager.start(this, CLOUD_URL)
        RavenHeraldService.ensureRunning(this)

        val outerFrame = FrameLayout(this).apply {
            setBackgroundColor(Color.parseColor("#04060D"))
        }
        try {
            assets.open("norse_art/hall_backdrop.png").use { inp ->
                val bmp = BitmapFactory.decodeStream(inp)
                if (bmp != null) {
                    outerFrame.addView(ImageView(this).apply {
                        setImageBitmap(bmp)
                        scaleType = ImageView.ScaleType.CENTER_CROP
                        alpha = 0.16f
                        layoutParams = FrameLayout.LayoutParams(
                            FrameLayout.LayoutParams.MATCH_PARENT,
                            FrameLayout.LayoutParams.MATCH_PARENT,
                        )
                    })
                }
            }
        } catch (_: Exception) {}

        val scroll = ScrollView(this).apply {
            isFillViewport = true
            isVerticalScrollBarEnabled = false
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT,
            )
        }

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(16), dp(22), dp(16), dp(36))
        }

        // ====================================================================
        // 1. ODIN'S INSCRIPTION (CHISELED NORSE GOLD HEADER)
        // ====================================================================
        root.addView(buildRunicDivider("ᚦ  ·  ᛟ  ·  ᚱ"))

        val oathTv = TextView(this).apply {
            text = "“WHOSOEVER HOLDS THIS HAMMER,\nIF HE BE WORTHY,\nSHALL POSSESS THE POWER OF THOR”"
            typeface = Typeface.create(Typeface.SERIF, Typeface.BOLD_ITALIC)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15.5f)
            letterSpacing = 0.08f
            setLineSpacing(dp(4).toFloat(), 1.0f)
            gravity = Gravity.CENTER
            setTextColor(Color.parseColor("#FDE68A"))
            setShadowLayer(10f, 0f, 3f, Color.parseColor("#CC000000"))
            setPadding(dp(8), dp(8), dp(8), dp(10))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            )
            addOnLayoutChangeListener { v, _, _, _, _, _, _, _, _ ->
                val tv = v as TextView
                if (tv.height > 0) {
                    tv.paint.shader = LinearGradient(
                        0f, 0f, 0f, tv.height.toFloat(),
                        intArrayOf(
                            Color.parseColor("#FFFBEB"),
                            Color.parseColor("#FDE047"),
                            Color.parseColor("#CA8A04"),
                        ),
                        floatArrayOf(0f, 0.52f, 1f),
                        Shader.TileMode.CLAMP,
                    )
                    tv.invalidate()
                }
            }
        }
        root.addView(oathTv)

        // ====================================================================
        // 2. MJOLNIR ON PEDESTAL HERO EMBLEM DIRECTLY UNDERNEATH INSCRIPTION
        // ====================================================================
        root.addView(buildMjolnirPedestalHero())

        // ====================================================================
        // 3. 2-COLUMN STRAIGHT-DOWN CLASSIC ROUND FLOATING ORB GRID (10 APPS)
        // ====================================================================
        root.addView(buildNorseSectionDivider("ᛟ   COMMAND ORBS   ᛟ"))

        val orbGrid = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            )
        }

        // Row 1: Floating Thor + Raven Mail
        val (voiceCard, voiceRefs) = buildClassicOrbTile(
            label = "Floating Thor",
            iconRes = R.drawable.orb_classic_gold,
            accentHex = "#EAB308",
            initialStatus = "OFF",
        ) { toggleFloatingThorOverlay() }
        voiceOrbRefs = voiceRefs

        val (ravenCard, ravenRefs) = buildClassicOrbTile(
            label = "Raven Mail",
            iconRes = R.drawable.orb_classic_crimson,
            accentHex = "#F43F5E",
            initialStatus = "INBOX",
        ) { startActivity(Intent(this, RavenCenterActivity::class.java)) }
        ravenOrbRefs = ravenRefs
        ravenCenterBadgeTv = ravenRefs.statusPill
        orbGrid.addView(buildTwoColRow(voiceCard, ravenCard))

        // Row 2: Display Share + Rear Camera
        val (dsCard, dsRefs) = buildClassicOrbTile(
            label = "Display Share",
            iconRes = R.drawable.orb_classic_cyan,
            accentHex = "#38BDF8",
            initialStatus = "OFF",
        ) {
            if (ThorDisplayShareService.isDisplayShareActive) {
                ThorDisplayShareService.stopSharing(this@MainActivity)
                Toast.makeText(this@MainActivity, "Display Share OFF", Toast.LENGTH_SHORT).show()
            } else {
                DisplayShareConsentActivity.launchConsent(this@MainActivity)
            }
            toggleUiHandler.postDelayed({ updateVoiceToggleButtonAppearance() }, 250L)
        }
        displayShareOrbRefs = dsRefs

        val (camCard, camRefs) = buildClassicOrbTile(
            label = "Rear Camera",
            iconRes = R.drawable.orb_classic_emerald,
            accentHex = "#10B981",
            initialStatus = "OFF",
        ) {
            if (ThorFlashlightAndLensService.isRearCameraActive) {
                ThorFlashlightAndLensService.stopRearCamera(this@MainActivity)
                Toast.makeText(this@MainActivity, "Rear Camera OFF", Toast.LENGTH_SHORT).show()
            } else {
                ThorFlashlightAndLensService.startRearCamera(
                    this@MainActivity,
                    ThorFlashlightAndLensService.isTorchOn,
                )
            }
            toggleUiHandler.postDelayed({ updateVoiceToggleButtonAppearance() }, 250L)
        }
        rearCamOrbRefs = camRefs
        orbGrid.addView(buildTwoColRow(dsCard, camCard))

        // Row 3: Flashlight + Screensaver
        val (torchCard, torchRefs) = buildClassicOrbTile(
            label = "Flashlight",
            iconRes = R.drawable.orb_classic_amber,
            accentHex = "#F59E0B",
            initialStatus = "OFF",
        ) {
            val on = ThorFlashlightAndLensService.toggleTorch(this@MainActivity)
            Toast.makeText(this@MainActivity, if (on) "Flashlight ON" else "Flashlight OFF", Toast.LENGTH_SHORT).show()
            updateVoiceToggleButtonAppearance()
        }
        torchOrbRefs = torchRefs

        val (ssCard, _) = buildClassicOrbTile(
            label = "Screensaver",
            iconRes = R.drawable.orb_classic_silver,
            accentHex = "#E2E8F0",
            initialStatus = null,
        ) {
            startActivity(Intent(this@MainActivity, ThorScreensaverActivity::class.java))
        }
        orbGrid.addView(buildTwoColRow(torchCard, ssCard))

        // Row 4: 3D Sanctum + Thor Chat
        val (sanctumCard, _) = buildClassicOrbTile(
            label = "3D Sanctum",
            iconRes = R.drawable.orb_classic_indigo,
            accentHex = "#818CF8",
            initialStatus = null,
        ) {
            openInBrowser("$CLOUD_URL/notes?key=$SOVEREIGN_TOKEN&v=${System.currentTimeMillis()}")
        }

        val (chatCard, _) = buildClassicOrbTile(
            label = "Thor Chat",
            iconRes = R.drawable.orb_classic_sapphire,
            accentHex = "#60A5FA",
            initialStatus = null,
        ) {
            openInBrowser("$CLOUD_URL/chat?key=$SOVEREIGN_TOKEN&v=${System.currentTimeMillis()}")
        }
        orbGrid.addView(buildTwoColRow(sanctumCard, chatCard))

        // Row 5: Runic Altar + Saver Setup
        val (altarCard, _) = buildClassicOrbTile(
            label = "Runic Altar",
            iconRes = R.drawable.orb_classic_purple,
            accentHex = "#C084FC",
            initialStatus = null,
        ) {
            openInBrowser("$CLOUD_URL/observatory?key=$SOVEREIGN_TOKEN&v=${System.currentTimeMillis()}")
        }

        val (setupCard, _) = buildClassicOrbTile(
            label = "Saver Setup",
            iconRes = R.drawable.orb_classic_magenta,
            accentHex = "#F472B6",
            initialStatus = null,
        ) {
            try {
                startActivity(Intent(Settings.ACTION_DREAM_SETTINGS))
            } catch (_: Exception) {
                startActivity(Intent(Settings.ACTION_DISPLAY_SETTINGS))
            }
        }
        orbGrid.addView(buildTwoColRow(altarCard, setupCard))

        root.addView(orbGrid)

        // ====================================================================
        // 4. FORGED NORSE LINK BUTTONS (CLEAN LABELS, DUPLICATE REMOVED)
        // ====================================================================
        root.addView(buildNorseSectionDivider("ᛟ   REALMS & CONSOLES   ᛟ"))

        val links = listOf(
            Triple("Gemini", "#60A5FA", "https://gemini.google.com/app"),
            Triple("ChatGPT", "#34D399", "https://chatgpt.com"),
            Triple("Claude", "#FB923C", "https://claude.ai"),
            Triple("Google AI Studio", "#FBBF24", "https://aistudio.google.com/app/apikey"),
            Triple("Supabase", "#22C55E", "https://supabase.com/dashboard"),
            Triple("Netcup Server CP", "#10B981", "https://www.servercontrolpanel.de"),
            Triple("Cloudflare DNS", "#F97316", "https://dash.cloudflare.com"),
            Triple("Tailscale Mesh", "#38BDF8", "https://login.tailscale.com/admin/machines"),
            Triple("Anthropic API", "#F97316", "https://console.anthropic.com"),
            Triple("OpenAI API", "#10B981", "https://platform.openai.com"),
        )

        var idx = 0
        while (idx < links.size) {
            val left = links[idx]
            val right = links[idx + 1]
            root.addView(
                buildTwoColRow(
                    buildNorseLinkButton(left.first, left.second) { openInBrowser(left.third) },
                    buildNorseLinkButton(right.first, right.second) { openInBrowser(right.third) },
                    bottomMarginDp = 9,
                )
            )
            idx += 2
        }

        val ghBtn = buildNorseLinkButton("GitHub  ·  Thor Repo", "#CBD5E1") {
            openInBrowser("https://github.com/cfrosth-eng/Thor")
        }.apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply {
                topMargin = dp(2)
                bottomMargin = dp(8)
            }
        }
        root.addView(ghBtn)

        scroll.addView(root)
        outerFrame.addView(scroll)
        setContentView(outerFrame)

        updateVoiceToggleButtonAppearance()
        refreshRavenFolderCountsBadge()
        routeIncomingAndroidShareIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        routeIncomingAndroidShareIntent(intent)
    }

    private fun routeIncomingAndroidShareIntent(src: Intent?) {
        if (src?.action != Intent.ACTION_SEND) return
        val mime = src.type ?: ""
        val sharedText = src.getStringExtra(Intent.EXTRA_TEXT) ?: ""
        val streamUri: Uri? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            src.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
        } else {
            @Suppress("DEPRECATION")
            src.getParcelableExtra(Intent.EXTRA_STREAM)
        }
        if (sharedText.isNotEmpty() || streamUri != null) {
            val rcIntent = Intent(this, RavenCenterActivity::class.java).apply {
                putExtra(RavenCenterActivity.EXTRA_OPEN_COMPOSE, true)
                putExtra(RavenCenterActivity.EXTRA_SHARED_TEXT, sharedText)
                putExtra(RavenCenterActivity.EXTRA_SHARED_URI, streamUri?.toString() ?: "")
                putExtra(RavenCenterActivity.EXTRA_SHARED_MIME, mime)
            }
            src.action = ""
            startActivity(rcIntent)
        }
    }

    private fun updateRavenCountBadgeText(rawLabel: String) {
        val m = Regex("Received:\\s*(\\d+).*?Sent:\\s*(\\d+)").find(rawLabel)
        val compact = if (m != null) "${m.groupValues[1]} IN · ${m.groupValues[2]} OUT" else "INBOX"
        ravenOrbRefs?.let { refs ->
            val hasUnread = LivingPedestalDirector.hasUnreadRaven
            styleStatusPill(
                refs.statusPill,
                if (hasUnread) "NEW MAIL" else compact,
                if (hasUnread) "#3B0A16" else "#182235",
                if (hasUnread) "#F43F5E" else "#64748B",
                if (hasUnread) "#FDA4AF" else "#CBD5E1",
            )
        }
    }

    private fun refreshRavenFolderCountsBadge() {
        uiIo.execute {
            try {
                val dao = ThorDatabase.getDatabase(applicationContext).ravenMessageDao()
                val recv = dao.getCountByFolder("RECEIVED")
                val unread = dao.getUnreadReceivedCount()
                val sent = dao.getCountByFolder("SENT")
                val label = if (unread > 0) {
                    "📥 Received: $recv ($unread NEW)  •  📤 Sent: $sent"
                } else {
                    "📥 Received: $recv  •  📤 Sent: $sent  •  ＋ New Message"
                }
                runOnUiThread { updateRavenCountBadgeText(label) }
            } catch (_: Exception) {}
        }
    }


    override fun onResume() {
        super.onResume()
        updateVoiceToggleButtonAppearance()
        refreshRavenFolderCountsBadge()
        toggleUiHandler.removeCallbacks(toggleUiTicker)
        toggleUiHandler.post(toggleUiTicker)
    }

    override fun onPause() {
        super.onPause()
        toggleUiHandler.removeCallbacks(toggleUiTicker)
    }

    private fun buildRunicDivider(runes: String): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { bottomMargin = dp(4) }

            val lineL = View(this@MainActivity).apply {
                setBackgroundColor(Color.parseColor("#66CA8A04"))
                layoutParams = LinearLayout.LayoutParams(0, dp(1), 1f).apply { marginEnd = dp(10) }
            }
            val tv = TextView(this@MainActivity).apply {
                text = runes
                setTextColor(Color.parseColor("#D4A234"))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                typeface = Typeface.SERIF
            }
            val lineR = View(this@MainActivity).apply {
                setBackgroundColor(Color.parseColor("#66CA8A04"))
                layoutParams = LinearLayout.LayoutParams(0, dp(1), 1f).apply { marginStart = dp(10) }
            }
            addView(lineL); addView(tv); addView(lineR)
        }
    }

    private fun buildNorseSectionDivider(label: String): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply {
                topMargin = dp(16)
                bottomMargin = dp(12)
            }

            val lineL = View(this@MainActivity).apply {
                setBackgroundColor(Color.parseColor("#55D4A234"))
                layoutParams = LinearLayout.LayoutParams(0, dp(1), 1f).apply { marginEnd = dp(10) }
            }
            val tv = TextView(this@MainActivity).apply {
                text = label
                setTextColor(Color.parseColor("#EAB308"))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f)
                typeface = Typeface.create(Typeface.SERIF, Typeface.BOLD)
                letterSpacing = 0.14f
            }
            val lineR = View(this@MainActivity).apply {
                setBackgroundColor(Color.parseColor("#55D4A234"))
                layoutParams = LinearLayout.LayoutParams(0, dp(1), 1f).apply { marginStart = dp(10) }
            }
            addView(lineL); addView(tv); addView(lineR)
        }
    }

    private fun buildMjolnirPedestalHero(): FrameLayout {
        val heroW = dp(192)
        val heroH = dp(238)
        val outer = FrameLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(heroW, heroH).apply {
                topMargin = dp(4)
                bottomMargin = dp(6)
            }
            setPadding(dp(3), dp(3), dp(3), dp(3))
            background = GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM,
                intArrayOf(
                    Color.parseColor("#FEF08A"),
                    Color.parseColor("#CA8A04"),
                    Color.parseColor("#5A3907"),
                    Color.parseColor("#EAB308"),
                ),
            ).apply {
                cornerRadius = dp(22).toFloat()
            }
            elevation = dp(10).toFloat()
        }

        val inner = FrameLayout(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT,
            )
            clipToOutline = true
            outlineProvider = object : ViewOutlineProvider() {
                override fun getOutline(view: View, outline: Outline) {
                    outline.setRoundRect(0, 0, view.width, view.height, dp(19).toFloat())
                }
            }
            setBackgroundColor(Color.parseColor("#060A14"))
        }

        inner.addView(ImageView(this).apply {
            setImageResource(R.drawable.mjolnir_pedestal_base_hd)
            scaleType = ImageView.ScaleType.CENTER_CROP
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT,
            )
        })

        inner.addView(View(this).apply {
            background = GradientDrawable().apply {
                setColor(Color.TRANSPARENT)
                setStroke(dp(1), Color.parseColor("#88FEF08A"))
                cornerRadius = dp(19).toFloat()
            }
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT,
            )
        })
        outer.addView(inner)

        outer.setOnClickListener {
            startActivity(Intent(this@MainActivity, ThorScreensaverActivity::class.java))
        }
        return outer
    }

    private fun buildClassicOrbTile(
        label: String,
        iconRes: Int,
        accentHex: String,
        initialStatus: String?,
        onClick: () -> Unit,
    ): Pair<LinearLayout, OrbTileRefs> {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(8), dp(12), dp(8), dp(10))
            background = makeNorsePanelDrawable("#CC0A1120", "#3D" + accentHex.removePrefix("#"), 18)
            elevation = dp(4).toFloat()
            isClickable = true
            isFocusable = true
        }

        val orbSize = dp(82)
        val ringFrame = FrameLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(orbSize, orbSize)
            setPadding(dp(3), dp(3), dp(3), dp(3))
            background = makeOrbRingDrawable(accentHex, strokeDp = 2)
        }

        ringFrame.addView(ImageView(this).apply {
            setImageResource(iconRes)
            scaleType = ImageView.ScaleType.FIT_CENTER
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT,
            )
            clipToOutline = true
            outlineProvider = object : ViewOutlineProvider() {
                override fun getOutline(view: View, outline: Outline) {
                    outline.setOval(0, 0, view.width, view.height)
                }
            }
        })
        card.addView(ringFrame)

        card.addView(TextView(this).apply {
            text = label
            setTextColor(Color.parseColor("#F8FAFC"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13.5f)
            typeface = Typeface.create(Typeface.SERIF, Typeface.BOLD)
            letterSpacing = 0.04f
            gravity = Gravity.CENTER
            setPadding(0, dp(8), 0, dp(2))
            maxLines = 1
        })

        val statusTv = TextView(this).apply {
            text = initialStatus ?: ""
            visibility = if (initialStatus != null) View.VISIBLE else View.GONE
            setTextColor(Color.parseColor("#CBD5E1"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 10.5f)
            typeface = Typeface.DEFAULT_BOLD
            letterSpacing = 0.08f
            gravity = Gravity.CENTER
            setPadding(dp(10), dp(2), dp(10), dp(2))
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#182235"))
                cornerRadius = dp(10).toFloat()
                setStroke(dp(1), Color.parseColor("#475569"))
            }
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(3) }
        }
        card.addView(statusTv)
        card.setOnClickListener { onClick() }

        return card to OrbTileRefs(ringFrame, statusTv, card)
    }

    private fun buildNorseLinkButton(label: String, accentHex: String, onClick: () -> Unit): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), dp(12), dp(12), dp(12))
            background = GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM,
                intArrayOf(
                    Color.parseColor("#E6111A2E"),
                    Color.parseColor("#E6090F1D"),
                ),
            ).apply {
                cornerRadius = dp(12).toFloat()
                setStroke(dp(1), Color.parseColor("#66CA8A04"))
            }
            elevation = dp(3).toFloat()
            isClickable = true
            isFocusable = true

            addView(View(this@MainActivity).apply {
                background = GradientDrawable().apply {
                    setColor(Color.parseColor(accentHex))
                    cornerRadius = dp(3).toFloat()
                }
                layoutParams = LinearLayout.LayoutParams(dp(4), dp(18)).apply { marginEnd = dp(10) }
            })
            addView(TextView(this@MainActivity).apply {
                text = label
                setTextColor(Color.parseColor("#F1F5F9"))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                typeface = Typeface.create(Typeface.SERIF, Typeface.BOLD)
                letterSpacing = 0.03f
                maxLines = 1
            })
            setOnClickListener { onClick() }
        }
    }

    private fun buildTwoColRow(leftView: View, rightView: View, bottomMarginDp: Int = 11): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { bottomMargin = dp(bottomMarginDp) }

            leftView.layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f).apply {
                marginEnd = dp(6)
            }
            rightView.layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f).apply {
                marginStart = dp(6)
            }
            addView(leftView)
            addView(rightView)
        }
    }

    private fun makeNorsePanelDrawable(bgHex: String, borderHex: String, radiusDp: Int): GradientDrawable {
        return GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dp(radiusDp).toFloat()
            setColor(Color.parseColor(bgHex))
            setStroke(dp(1), Color.parseColor(borderHex))
        }
    }

    private fun makeOrbRingDrawable(strokeHex: String, strokeDp: Int): GradientDrawable {
        return GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(Color.parseColor("#060A14"))
            setStroke(dp(strokeDp), Color.parseColor(strokeHex))
        }
    }

    private fun styleStatusPill(tv: TextView, textVal: String, bgHex: String, strokeHex: String, textHex: String) {
        tv.visibility = View.VISIBLE
        tv.text = textVal
        tv.setTextColor(Color.parseColor(textHex))
        tv.background = GradientDrawable().apply {
            setColor(Color.parseColor(bgHex))
            cornerRadius = dp(10).toFloat()
            setStroke(dp(1), Color.parseColor(strokeHex))
        }
    }

    private fun updateVoiceToggleButtonAppearance() {
        voiceOrbRefs?.let { refs ->
            val on = FloatingThorService.isServiceRunning
            refs.ringFrame.background = makeOrbRingDrawable(if (on) "#22C55E" else "#B45309", if (on) 3 else 2)
            refs.card.background = makeNorsePanelDrawable(if (on) "#CC082315" else "#CC0A1120", if (on) "#6622C55E" else "#44EAB308", 18)
            styleStatusPill(
                refs.statusPill,
                if (on) "ON" else "OFF",
                if (on) "#0A2F1C" else "#2A1215",
                if (on) "#22C55E" else "#EF4444",
                if (on) "#86EFAC" else "#FCA5A5",
            )
        }

        displayShareOrbRefs?.let { refs ->
            val on = ThorDisplayShareService.isDisplayShareActive
            refs.ringFrame.background = makeOrbRingDrawable(if (on) "#34D399" else "#38BDF8", if (on) 3 else 2)
            refs.card.background = makeNorsePanelDrawable(if (on) "#CC06281E" else "#CC0A1120", if (on) "#6634D399" else "#3D38BDF8", 18)
            styleStatusPill(
                refs.statusPill,
                if (on) "LIVE" else "OFF",
                if (on) "#064E3B" else "#0F172A",
                if (on) "#34D399" else "#38BDF8",
                if (on) "#A7F3D0" else "#93C5FD",
            )
        }

        torchOrbRefs?.let { refs ->
            val on = ThorFlashlightAndLensService.isTorchOn
            refs.ringFrame.background = makeOrbRingDrawable(if (on) "#FACC15" else "#B45309", if (on) 3 else 2)
            refs.card.background = makeNorsePanelDrawable(if (on) "#CC2B1904" else "#CC0A1120", if (on) "#88FACC15" else "#3DF59E0B", 18)
            styleStatusPill(
                refs.statusPill,
                if (on) "ON" else "OFF",
                if (on) "#422006" else "#172033",
                if (on) "#FACC15" else "#64748B",
                if (on) "#FEF08A" else "#CBD5E1",
            )
        }

        rearCamOrbRefs?.let { refs ->
            val on = ThorFlashlightAndLensService.isRearCameraActive
            refs.ringFrame.background = makeOrbRingDrawable(if (on) "#10B981" else "#059669", if (on) 3 else 2)
            refs.card.background = makeNorsePanelDrawable(if (on) "#CC06281E" else "#CC0A1120", if (on) "#6610B981" else "#3D10B981", 18)
            styleStatusPill(
                refs.statusPill,
                if (on) "LIVE" else "OFF",
                if (on) "#064E3B" else "#0F172A",
                if (on) "#10B981" else "#64748B",
                if (on) "#6EE7B7" else "#CBD5E1",
            )
        }
    }

    private fun openInBrowser(url: String) {
        try {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
                setPackage("com.android.chrome")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            startActivity(intent)
        } catch (_: Exception) {
            try {
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
            } catch (e: Exception) {
                Toast.makeText(this, "Could not open link: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun toggleFloatingThorOverlay() {
        if (FloatingThorService.isServiceRunning) {
            stopFloatingThorOverlay()
        } else {
            launchFloatingThorOverlay()
        }
        toggleUiHandler.postDelayed({ updateVoiceToggleButtonAppearance() }, 180L)
        toggleUiHandler.postDelayed({ updateVoiceToggleButtonAppearance() }, 500L)
    }

    private fun launchFloatingThorOverlay() {
        if (!Settings.canDrawOverlays(this)) {
            Toast.makeText(this, "Please enable 'Appear on top' for ThorMobile", Toast.LENGTH_LONG).show()
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
            return
        }
        try {
            getSharedPreferences("thor_overlay_prefs", MODE_PRIVATE).edit().putBoolean("user_stopped", false).apply()
            FloatingThorService.userRequestedStop = false
            ContextCompat.startForegroundService(this, Intent(this, FloatingThorService::class.java))
            Toast.makeText(this, "Floating Thor ON", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Toast.makeText(this, "Overlay error: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun stopFloatingThorOverlay() {
        try {
            // WO-196 Safety Lock: guarantee Display Share & Rear Camera shut down with Floating Thor
            if (ThorDisplayShareService.isDisplayShareActive) {
                ThorDisplayShareService.stopSharing(this)
            }
            if (ThorFlashlightAndLensService.isRearCameraActive) {
                ThorFlashlightAndLensService.stopRearCamera(this)
            }
            getSharedPreferences("thor_overlay_prefs", MODE_PRIVATE).edit().putBoolean("user_stopped", true).apply()
            FloatingThorService.userRequestedStop = true
            val stopIntent = Intent(this, FloatingThorService::class.java).apply {
                action = FloatingThorService.ACTION_STOP
            }
            try {
                if (FloatingThorService.isServiceRunning) {
                    startService(stopIntent)
                }
            } catch (_: Exception) {}
            stopService(Intent(this, FloatingThorService::class.java))
            val nm = getSystemService(android.app.NotificationManager::class.java)
            nm?.cancel(1)
            toggleUiHandler.postDelayed({ updateVoiceToggleButtonAppearance() }, 250L)
            Toast.makeText(this, "Floating Thor OFF", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Toast.makeText(this, "Stop note: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun requestNeededPermissions() {
        val perms = mutableListOf(
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.CAMERA,
            Manifest.permission.READ_SMS,
            Manifest.permission.SEND_SMS,
            Manifest.permission.READ_CONTACTS,
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            perms.add(Manifest.permission.BLUETOOTH_CONNECT)
            perms.add(Manifest.permission.BLUETOOTH_SCAN)
        }
        val missing = perms.filter { ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, missing.toTypedArray(), 101)
        }
    }

    private fun dp(v: Int): Int =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics).toInt()
}
