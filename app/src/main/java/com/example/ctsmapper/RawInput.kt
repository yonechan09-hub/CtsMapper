package com.example.ctsmapper

import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Intent
import android.content.IntentFilter
import android.os.PowerManager
import android.content.Context
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.view.KeyEvent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import rikka.shizuku.Shizuku

object RawInput {
    private const val REQ = 4242
    private val main = Handler(Looper.getMainLooper())
    private var appCtx: Context? = null
    private var svc: IUserService? = null
    private var binding = false
    private var installed = false
    private var lastEvdev: Boolean? = null
    private var lastCfg = ""
    private var wantGrant = false
    @Volatile private var grantUntil = 0L
    private var screenRx: BroadcastReceiver? = null

    /** evdev is read only while the screen is on (or while recording a key). */
    private fun evdevNow(c: Context): Boolean =
        recording || (Engine.rawWanted(c) && c.getSystemService(PowerManager::class.java).isInteractive)

    /** SCREEN_ON / SCREEN_OFF are push broadcasts: registered only while a Plus Key mapping is enabled. */
    private fun syncScreenReceiver(c: Context) {
        val want = Engine.rawWanted(c)
        if (want && screenRx == null) {
            val rx = object : BroadcastReceiver() {
                override fun onReceive(context: Context, intent: Intent) { main.post { appCtx?.let { refresh(it) } } }
            }
            val f = IntentFilter(Intent.ACTION_SCREEN_ON).apply { addAction(Intent.ACTION_SCREEN_OFF) }
            c.registerReceiver(rx, f)
            screenRx = rx
        } else if (!want && screenRx != null) {
            runCatching { c.unregisterReceiver(screenRx) }
            screenRx = null
        }
    }

    var status by mutableStateOf("Shizuku listener: off")
    var running by mutableStateOf(false)
    var recording by mutableStateOf(false)
    var captured by mutableStateOf<String?>(null)
    var lastA11yKey by mutableStateOf<Int?>(null)
    val log = mutableStateListOf<String>()

    private fun st(s: String, err: Boolean = false) {
        if (s == status) return
        status = s
        appCtx?.let { AppLog.add(it, if (err) "ERR" else "SYS", "shizuku: $s") }
    }

    private val callback = object : IKeyCallback.Stub() {
        override fun onEvent(path: String, name: String, type: Int, code: Int, value: Int) {
            main.post { handle(name, type, code, value) }
        }

        override fun onInfo(msg: String) {
            main.post { appCtx?.let { AppLog.add(it, "SYS", "watcher: $msg") } }
        }
    }

    private val conn = object : ServiceConnection {
        override fun onServiceConnected(n: ComponentName, b: IBinder) {
            binding = false
            val c = appCtx ?: return
            val s = IUserService.Stub.asInterface(b)
            svc = s
            lastEvdev = null; lastCfg = ""
            try {
                val evdev = evdevNow(c)
                s.start(callback, evdev)
                lastEvdev = evdev
                running = true
                st(if (evdev) "listening" else if (Engine.rawWanted(c)) "paused (screen off)" else "connected")
                pushConfig(c, true)
                if (wantGrant || !A11yGuard.canWrite(c)) { wantGrant = false; doGrant(s, c) }
            } catch (e: Exception) {
                st("start failed: ${e.message}", true)
            }
        }

        override fun onServiceDisconnected(n: ComponentName) {
            svc = null; binding = false; running = false
            lastEvdev = null; lastCfg = ""
            st("service disconnected", true)
        }
    }

    /** Call once (Activity / AccessibilityService). Re-connects automatically if enabled. */
    fun install(ctx: Context) {
        appCtx = ctx.applicationContext
        if (installed) return
        installed = true
        Shizuku.addBinderReceivedListenerSticky {
            val c = appCtx
            if (c != null && Engine.shellWanted(c)) main.post { start(c) }
        }
        Shizuku.addBinderDeadListener {
            main.post {
                running = false; binding = false
                st("Shizuku stopped – start it again", true)
                // If it died while the service was hidden, put the service back.
                appCtx?.let { A11yGuard.check(it, "shizuku stopped", 1000) }
            }
        }
    }

    private fun userArgs(ctx: Context) =
        Shizuku.UserServiceArgs(ComponentName(ctx.packageName, EventService::class.java.name))
            .daemon(true).processNameSuffix("events").debuggable(false).version(2)

    /** Start / stop / re-push settings so the Shizuku process matches what is currently wanted. */
    fun refresh(ctx: Context) {
        val c = ctx.applicationContext
        appCtx = c
        syncScreenReceiver(c)
        if (Engine.shellWanted(c) || recording) {
            if (running) pushAll(c) else start(c)
        } else if ((running || binding || svc != null) && android.os.SystemClock.elapsedRealtime() > grantUntil) {
            stop()
        }
    }

    private fun pushAll(c: Context) {
        val s = svc ?: return
        val evdev = evdevNow(c)
        try {
            if (lastEvdev != evdev) {
                s.start(callback, evdev)
                lastEvdev = evdev
                st(if (evdev) "listening" else if (Engine.rawWanted(c)) "paused (screen off)" else "connected")
            }
            pushConfig(c, false)
        } catch (e: Exception) {
            st("update failed: ${e.message}", true)
        }
    }

    /** Tell the shell-side process the service's component and whether dead-process recovery is wanted. */
    fun pushConfig(ctx: Context, force: Boolean = false) {
        val s = svc ?: return
        val c = ctx.applicationContext
        val rec = Engine.recoverWanted(c)
        val sig = "$rec"
        if (!force && sig == lastCfg) return
        try {
            s.configure(A11yGuard.component(c), rec)
            lastCfg = sig
        } catch (e: Exception) {
            st("configure failed: ${e.message}", true)
        }
    }

    /** Ask the shell process to put the service back right now (manual "Restore service" fallback). */
    fun restoreViaShizuku(ctx: Context): Boolean {
        val s = svc ?: return false
        return try { s.restoreService(A11yGuard.component(ctx)) } catch (e: Exception) { false }
    }

    fun start(ctx: Context) {
        appCtx = ctx.applicationContext
        if (running || binding) return
        if (!Shizuku.pingBinder()) { st("Shizuku is not running. Start it first.", true); return }
        if (Shizuku.isPreV11()) { st("Shizuku is too old – update it.", true); return }
        if (Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED) {
            st("waiting for Shizuku permission…")
            Shizuku.addRequestPermissionResultListener(permListener)
            Shizuku.requestPermission(REQ)
            return
        }
        bind()
    }

    private val permListener: Shizuku.OnRequestPermissionResultListener =
        Shizuku.OnRequestPermissionResultListener { code, result ->
            Shizuku.removeRequestPermissionResultListener(permListener)
            main.post {
                if (code == REQ && result == PackageManager.PERMISSION_GRANTED) {
                    bind()
                } else {
                    st("Shizuku permission denied", true)
                }
            }
        }

    private fun bind() {
        val ctx = appCtx ?: return
        if (binding || running) return
        binding = true
        st("connecting…")
        try {
            Shizuku.bindUserService(userArgs(ctx), conn)
        } catch (e: Exception) {
            binding = false
            st("bind failed: ${e.message}", true)
        }
    }

    fun stop() {
        val c = appCtx
        val was = running || binding || svc != null
        runCatching { svc?.stop() }
        // remove = true also destroys the daemon (it restores the service first)
        if (c != null) runCatching { Shizuku.unbindUserService(userArgs(c), conn, true) }
        svc = null; running = false; binding = false
        lastEvdev = null; lastCfg = ""
        status = "Shizuku listener: off"
        if (was) appCtx?.let { AppLog.add(it, "SYS", "shizuku: listener stopped") }
    }

    // ------------------------------------------------------------ WRITE_SECURE_SETTINGS via Shizuku
    /** Grants WRITE_SECURE_SETTINGS to this app once (like `adb shell pm grant`). */
    fun grantSecure(ctx: Context) {
        val c = ctx.applicationContext
        appCtx = c
        grantUntil = android.os.SystemClock.elapsedRealtime() + 15_000L // don't stop the helper mid-grant
        val s = svc
        if (s == null) { wantGrant = true; start(c); return }
        doGrant(s, c)
    }

    private fun doGrant(s: IUserService, c: Context) {
        Thread {
            val ok = runCatching { s.grantSecure(c.packageName) }.getOrDefault(false)
            main.post {
                AppLog.add(c, "SYS", "WRITE_SECURE_SETTINGS grant via Shizuku: ${if (ok) "ok" else "failed"}")
                grantUntil = 0L
                refresh(c)
            }
        }.start()
    }

    // ------------------------------------------------------------ record
    fun beginRecord(ctx: Context) {
        appCtx = ctx.applicationContext
        log.clear(); captured = null; lastA11yKey = null; recording = true
        TriggerService.refresh() // makes the service enable key filtering for the recording
        refresh(ctx)
    }

    fun endRecord() {
        recording = false
        TriggerService.refresh()
        appCtx?.let { refresh(it) }
    }

    fun onA11yKey(e: KeyEvent) {
        if (e.action != KeyEvent.ACTION_DOWN || e.repeatCount != 0) return
        addLog("A11y: ${KeyEvent.keyCodeToString(e.keyCode)} (scan ${e.scanCode})")
        if (e.keyCode != KeyEvent.KEYCODE_VOLUME_UP && e.keyCode != KeyEvent.KEYCODE_VOLUME_DOWN) {
            lastA11yKey = e.keyCode
        }
    }

    fun useA11yKey(ctx: Context) {
        val k = lastA11yKey ?: return
        Prefs.setRawSig(ctx, "a11y:$k")
        captured = "a11y:$k"
        AppLog.add(ctx, "SYS", "recorded a11y key $k")
    }

    private fun addLog(s: String) { log.add(s); while (log.size > 40) log.removeAt(0) }

    fun label(type: Int, code: Int): String = when {
        type == 1 && code == 114 -> "KEY_VOLUMEDOWN (114)"
        type == 1 && code == 115 -> "KEY_VOLUMEUP (115)"
        type == 1 && code == 116 -> "KEY_POWER (116)"
        type == 1 && code in 704..735 -> "BTN_TRIGGER_HAPPY${code - 703} ($code)"
        else -> "type=$type code=$code"
    }

    private fun handle(name: String, type: Int, code: Int, value: Int) {
        val ctx = appCtx ?: return
        val down = value == 1
        if (recording) {
            addLog("${if (down) "↓" else "↑"} $name · ${label(type, code)}")
            val ignore = type == 1 && (code == 114 || code == 115 || code == 116)
            if (down && !ignore && captured == null) {
                val sig = "evdev:$name|$type|$code"
                Prefs.setRawSig(ctx, sig)
                captured = sig
                AppLog.add(ctx, "SYS", "recorded $sig")
            }
            return
        }
        if (Prefs.rawSig(ctx) == "evdev:$name|$type|$code") RawKey.feed(ctx, down)
    }
}

/** Turns down/up of the recorded key into "press" and "long press". Shared by evdev and a11y sources. */
object RawKey {
    private const val LONG_MS = 600L
    private val h = Handler(Looper.getMainLooper())
    private var pending: Runnable? = null
    private var longFired = false

    fun reset() {
        pending?.let { h.removeCallbacks(it) }
        pending = null; longFired = false
    }

    fun feed(ctx: Context, down: Boolean) {
        val app = ctx.applicationContext
        if (!Engine.live(app)) { reset(); return }
        val pressA = Prefs.getAction(app, Trigger.RAW_PRESS)
        val longA = Prefs.getAction(app, Trigger.RAW_LONG)
        if (down) {
            reset()
            if (longA != Action.NONE) {
                val r = Runnable {
                    longFired = true; pending = null
                    AppLog.add(app, "TRIG", "RAW_LONG")
                    Actions.run(app, longA)
                }
                pending = r
                h.postDelayed(r, LONG_MS)
            } else if (pressA != Action.NONE) {
                AppLog.add(app, "TRIG", "RAW_PRESS")
                Actions.run(app, pressA)
            }
        } else {
            val wasShort = pending != null && !longFired
            reset()
            if (wasShort && pressA != Action.NONE && longA != Action.NONE) {
                AppLog.add(app, "TRIG", "RAW_PRESS")
                Actions.run(app, pressA)
            }
        }
    }
}
