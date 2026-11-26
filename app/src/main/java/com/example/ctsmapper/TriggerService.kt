package com.example.ctsmapper

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.Color
import android.graphics.PixelFormat
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.inputmethod.InputMethodManager
import kotlin.math.hypot

/**
 * Event-driven only: no polling, no wakelocks, no timers except short Handler callbacks while a key is held.
 * [applyConfig] switches off key filtering, window events and the overlay whenever nothing needs them.
 */
class TriggerService : AccessibilityService() {

    private val h = Handler(Looper.getMainLooper())

    private class KeyState {
        var pendingLong: Runnable? = null
        var longFired = false
        var doubleFired = false
        var lastUp = 0L
    }
    private val states = HashMap<Int, KeyState>()

    private var overlay: View? = null
    private var overlayKey: String? = null
    private var appliedKeys: Boolean? = null
    private val prefListener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ -> applyConfig() }

    override fun onServiceConnected() {
        instance = this
        AppLog.installCrashHandler(this)
        AppLog.add(this, "SYS", "accessibility service connected")
        A11yGuard.onConnected(applicationContext)
        Prefs.sp(this).registerOnSharedPreferenceChangeListener(prefListener)
        RawInput.install(this)
        appliedKeys = null
        applyConfig()
        Engine.sync(this)
        // Some ROMs reset the service info right after (re)connect: apply once more shortly after.
        h.postDelayed({ if (instance === this) { appliedKeys = null; applyConfig() } }, 1500)
    }

    override fun onUnbind(intent: Intent?): Boolean {
        Prefs.sp(this).unregisterOnSharedPreferenceChangeListener(prefListener)
        clearKeyState()
        removeOverlay()
        instance = null
        AppLog.add(this, "SYS", "accessibility service unbound")
        h.removeCallbacksAndMessages(null)
        A11yGuard.onUnbound(applicationContext)
        Notifs.sync(this)
        return super.onUnbind(intent)
    }

    override fun onInterrupt() {}

    // ---------------------------------------------------------------- config -> what is actually active
    fun applyConfig() {
        val master = Prefs.master(this)
        val live = master
        val needKeys = live && (RawInput.recording || Engine.keyTriggersConfigured(this))
        val needEdge = live && Prefs.getAction(this, Trigger.EDGE_LONG) != Action.NONE

        if (needKeys != appliedKeys) {
            val info = serviceInfo
            if (info == null) {
                h.postDelayed({ if (instance === this) applyConfig() }, 500)
            } else {
                val f = android.accessibilityservice.AccessibilityServiceInfo.FLAG_REQUEST_FILTER_KEY_EVENTS
                info.flags = if (needKeys) info.flags or f else info.flags and f.inv()
                info.eventTypes = 0
                serviceInfo = info
                appliedKeys = needKeys
                AppLog.add(this, "SYS", "engine: keyFilter=$needKeys")
            }
        }

        if (needEdge) {
            val key = "${Prefs.edgeHeightDp(this)}/${Prefs.edgeDebug(this)}"
            if (overlay == null || overlayKey != key) addOverlay(key)
        } else {
            removeOverlay()
        }
        Notifs.sync(this)
        RawInput.refresh(this) // keeps the Shizuku helper (hide list / recovery) in step with the settings
    }

    private fun clearKeyState() {
        states.values.forEach { s -> s.pendingLong?.let { h.removeCallbacks(it) } }
        states.clear()
        RawKey.reset()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}

    // ---------------------------------------------------------------- hardware keys
    private fun fire(t: Trigger, a: Action) {
        AppLog.add(this, "TRIG", t.name)
        Actions.run(this, a)
    }

    override fun onKeyEvent(e: KeyEvent): Boolean {
        if (!Engine.live(this)) return false // filter is normally already off in this case

        if (RawInput.recording) { RawInput.onA11yKey(e); return false }

        val sig = Prefs.rawSig(this)
        if (sig != null && sig == "a11y:${e.keyCode}") {
            if (e.action == KeyEvent.ACTION_DOWN) { if (e.repeatCount == 0) RawKey.feed(this, true) }
            else if (e.action == KeyEvent.ACTION_UP) RawKey.feed(this, false)
            return true
        }

        val (longT, dblT) = when (e.keyCode) {
            KeyEvent.KEYCODE_VOLUME_UP -> Trigger.VOL_UP_LONG to Trigger.VOL_UP_DOUBLE
            KeyEvent.KEYCODE_VOLUME_DOWN -> Trigger.VOL_DOWN_LONG to Trigger.VOL_DOWN_DOUBLE
            else -> return false
        }
        val longA = Prefs.getAction(this, longT)
        val dblA = Prefs.getAction(this, dblT)
        if (longA == Action.NONE && dblA == Action.NONE) return false

        val st = states.getOrPut(e.keyCode) { KeyState() }
        when (e.action) {
            KeyEvent.ACTION_DOWN -> if (e.repeatCount == 0) {
                st.longFired = false; st.doubleFired = false
                val now = SystemClock.uptimeMillis()
                if (dblA != Action.NONE && now - st.lastUp < DOUBLE_MS) {
                    st.doubleFired = true
                    fire(dblT, dblA)
                } else if (longA != Action.NONE) {
                    val r = Runnable { st.longFired = true; fire(longT, longA) }
                    st.pendingLong = r
                    h.postDelayed(r, ViewConfiguration.getLongPressTimeout().toLong())
                }
            }
            KeyEvent.ACTION_UP -> {
                st.pendingLong?.let { h.removeCallbacks(it) }
                st.pendingLong = null
                val handled = st.longFired || st.doubleFired
                if (!handled) {
                    getSystemService(AudioManager::class.java).adjustSuggestedStreamVolume(
                        if (e.keyCode == KeyEvent.KEYCODE_VOLUME_UP) AudioManager.ADJUST_RAISE
                        else AudioManager.ADJUST_LOWER,
                        AudioManager.USE_DEFAULT_STREAM_TYPE, AudioManager.FLAG_SHOW_UI
                    )
                }
                st.lastUp = if (handled) 0L else SystemClock.uptimeMillis()
                st.longFired = false; st.doubleFired = false
            }
        }
        return true
    }

    // ---------------------------------------------------------------- edge strip gesture
    private fun addOverlay(key: String) {
        removeOverlay()
        val dm = resources.displayMetrics
        val view = EdgeView(this, Prefs.edgeDebug(this)) {
            val a = Prefs.getAction(this, Trigger.EDGE_LONG)
            if (Engine.live(this) && a != Action.NONE) fire(Trigger.EDGE_LONG, a)
        }
        val lp = WindowManager.LayoutParams(
            dm.widthPixels / 2,
            (Prefs.edgeHeightDp(this) * dm.density).toInt(),
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL }

        try {
            getSystemService(WindowManager::class.java).addView(view, lp)
            overlay = view
            overlayKey = key
        } catch (e: Exception) {
            AppLog.add(this, "ERR", "edge overlay failed: ${e.javaClass.simpleName}: ${e.message}")
        }
    }

    private fun removeOverlay() {
        overlay?.let { v -> runCatching { getSystemService(WindowManager::class.java).removeView(v) } }
        overlay = null
        overlayKey = null
    }

    private class EdgeView(ctx: Context, debug: Boolean, val onLong: () -> Unit) : View(ctx) {
        private val handler = Handler(Looper.getMainLooper())
        private val slop = ViewConfiguration.get(ctx).scaledTouchSlop
        private var sx = 0f
        private var sy = 0f
        private val fire = Runnable {
            performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
            onLong()
        }

        init { if (debug) setBackgroundColor(Color.argb(90, 255, 0, 0)) }

        override fun onTouchEvent(e: MotionEvent): Boolean {
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> { sx = e.rawX; sy = e.rawY; handler.postDelayed(fire, LONG_MS) }
                MotionEvent.ACTION_MOVE ->
                    if (hypot(e.rawX - sx, e.rawY - sy) > slop) handler.removeCallbacks(fire)
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> handler.removeCallbacks(fire)
            }
            return true
        }
    }

    companion object {
        private const val DOUBLE_MS = 350L
        private const val LONG_MS = 400L

        @Volatile var instance: TriggerService? = null

        /** Re-evaluate what needs to be active (safe to call from anywhere, any time). */
        fun refresh() {
            val s = instance ?: return
            s.h.post { s.applyConfig() }
        }
    }
}
