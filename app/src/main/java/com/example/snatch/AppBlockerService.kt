
package com.example.snatch

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView

class AppBlockerService : AccessibilityService() {

    private val handler = Handler(Looper.getMainLooper())
    private var foregroundPackage: String? = null
    private var overlay: View? = null
    private var overlayPackage: String? = null

    private val checker = object : Runnable {
        override fun run() {
            checkCurrentApp()
            handler.postDelayed(this, CHECK_INTERVAL_MS)
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        handler.post(checker)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val pkg = event?.packageName?.toString() ?: return

        // Events caused by our own overlay must not clear the target app.
        if (pkg == packageName) return

        foregroundPackage = pkg
        handler.removeCallbacks(checker)
        handler.post(checker)
    }

    private fun checkCurrentApp() {
        val pkg = foregroundPackage

        if (pkg.isNullOrBlank() || pkg == packageName) {
            hideOverlay()
            return
        }

        val limitMinutes = LimitStore.getLimitMinutes(this, pkg)
        if (limitMinutes == null) {
            hideOverlay()
            return
        }

        val usedMillis = readTodayUsage(this)
            .firstOrNull { it.packageName == pkg }
            ?.foregroundMillis ?: 0L

        if (usedMillis >= limitMinutes * 60_000L) {
            showOverlay(pkg, limitMinutes)
        } else {
            hideOverlay()
        }
    }

    private fun showOverlay(pkg: String, limitMinutes: Int) {
        if (overlay != null && overlayPackage == pkg) return

        hideOverlay()

        val appLabel = try {
            packageManager.getApplicationLabel(
                packageManager.getApplicationInfo(pkg, 0)
            ).toString()
        } catch (_: Exception) {
            pkg
        }

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(40, 48, 40, 48)
            setBackgroundColor(Color.rgb(20, 20, 24))
            isClickable = true
            isFocusable = true
        }

        val title = TextView(this).apply {
            text = "Time is up."
            textSize = 30f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
        }

        val detail = TextView(this).apply {
            text = "$appLabel has reached its ${limitMinutes}-minute daily limit."
            textSize = 18f
            setTextColor(Color.LTGRAY)
            gravity = Gravity.CENTER
            setPadding(0, 24, 0, 32)
        }

        val homeButton = Button(this).apply {
            text = "Return to home screen"
            setOnClickListener {
                hideOverlay()
                performGlobalAction(GLOBAL_ACTION_HOME)
            }
        }

        root.addView(title)
        root.addView(detail)
        root.addView(homeButton)

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
        }

        try {
            val windowManager =
                getSystemService(Context.WINDOW_SERVICE) as WindowManager
            windowManager.addView(root, params)
            overlay = root
            overlayPackage = pkg
        } catch (_: Exception) {
            overlay = null
            overlayPackage = null
        }
    }

    private fun hideOverlay() {
        val old = overlay ?: return
        try {
            val manager =
                getSystemService(Context.WINDOW_SERVICE) as WindowManager
            manager.removeView(old)
        } catch (_: Exception) {
            // The system may already have removed the accessibility window.
        } finally {
            overlay = null
            overlayPackage = null
        }
    }

    override fun onInterrupt() {
        hideOverlay()
    }

    override fun onDestroy() {
        handler.removeCallbacks(checker)
        hideOverlay()
        super.onDestroy()
    }

    companion object {
        private const val CHECK_INTERVAL_MS = 2_000L
    }
}
