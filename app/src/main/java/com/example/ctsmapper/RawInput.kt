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
import android.os.SystemClock
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
    private var lastEvdev: String? = null
    private var lastCfg = ""
    private var wantGrant = false
    @Volatile private var grantUntil = 0L
    private var screenRx: BroadcastReceiver? = null
    private var lastKeyLog = 0L

    // ---- reconnect with backoff (1s, 2s, 4s, 8s, 16s), at most MAX_RECONNECT attempts, only while Shizuku is running.
    // When Shizuku is not running there is no timer at all: the sticky binder-received listener restarts things.
    private const val MAX_RECONNECT = 5
    private const val BIND_TIMEOUT_MS = 10_000L
    private var wantUp = false
    private var reconnectAttempt = 0
    private var reconnectPending = false
    private var connectedAt = 0L
    private val reconnectRun = Runnable {
        reconnectPending = false
        val c = appCtx ?: return@Runnable
        if (wantUp && (Engine.shellWanted(c) || recording) && !running && !binding) start(c)
    }

    // ---- a bind that never answers is abandoned after BIND_TIMEOUT_MS
    private val bindTimeoutRun = Runnable {
        if (!binding) return@Runnable
        val c = appCtx ?: return@Runnable
        binding = false
        AppLog.add(c, "ERR", "shizuku: bind timed out after ${BIND_TIMEOUT_MS / 1000}s")
        status = "bind timed out"
        runCatching { Shizuku.unbindUserService(userArgs(c), conn, false) }
        svc = null; running = false
        scheduleReconnect()
    }

    // ---- stop is debounced so a transient "not wanted" flip never tears the connection down
    private var stopPending = false
    private val stopRun = Runnable {
        stopPending = false
        val c = appCtx ?: return@Runnable
        if (!(Engine.shellWanted(c) || recording) && SystemClock.elapsedRealtime() > grantUntil) {
            stop("not needed (raw=${Engine.rawWanted(c)} recover=${Engine.recoverWanted(c)} recording=$recording)")
        }
    }

    /**
     * What the helper should read: null = nothing, "" = every readable device (recording only), otherwise only the
     * recorded device's name. Real events are read only while the screen is on.
     */
    private fun evdevTarget(c: Context): String? = when {
        recording -> ""
        Engine.rawWanted(c) && c.getSystemService(PowerManager::class.java).isInteractive -> recordedDevice(c)
        else -> null
    }

    /** Device name out of the recorded "evdev:<name>|<type>|<code>" signature. */
    private fun recordedDevice(c: Context): String? {
        val sig = Prefs.rawSig(c) ?: return null
        if (!sig.startsWith("evdev:")) return null
        val body = sig.removePrefix("evdev:")
        val j = body.lastIndexOf('|')
        if (j <= 0) return null
        val i = body.lastIndexOf('|', j - 1)
        return if (i > 0) body.substring(0, i) else null
    }

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

    private val callback: IKeyCallback.Stub = object : IKeyCallback.Stub() {
        override fun onEvent(path: String, name: String, type: Int, code: Int, value: Int) {
            main.post { handle(name, type, code, value) }
        }

        override fun onInfo(msg: String) {
            main.post { appCtx?.let { AppLog.add(it, if (msg.startsWith("ERR")) "ERR" else "HELP", msg.removePrefix("ERR ")) } }
        }
    }

    private val conn: ServiceConnection = object : ServiceConnection {
        override fun onServiceConnected(n: ComponentName, b: IBinder) {
            binding = false
            main.removeCallbacks(bindTimeoutRun)
            val c = appCtx ?: return
            val s = IUserService.Stub.asInterface(b)
            svc = s
            lastEvdev = null; lastCfg = ""
            connectedAt = SystemClock.elapsedRealtime()
            try {
                val evdev = evdevTarget(c)
                s.start(callback, evdev != null, evdev ?: "")
                lastEvdev = evdev
                running = true
                st(if (evdev != null) "listening" else if (Engine.rawWanted(c)) "paused (screen off)" else "connected")
                pushConfig(c, true)
                if (wantGrant || !A11yGuard.canWrite(c)) { wantGrant = false; doGrant(s, c) }
            } catch (e: Throwable) {
                fail(c, "start failed", e)
            }
        }

        override fun onBindingDied(n: ComponentName) { bindingLost("binding died") }

        override fun onNullBinding(n: ComponentName) { bindingLost("null binding") }

        override fun onServiceDisconnected(n: ComponentName) {
            main.removeCallbacks(bindTimeoutRun)
            svc = null; binding = false; running = false
            lastEvdev = null; lastCfg = ""
            status = "service disconnected"
            appCtx?.let { AppLog.add(it, "ERR", "shizuku: service disconnected") }
            if (SystemClock.elapsedRealtime() - connectedAt > 10_000L) reconnectAttempt = 0
            scheduleReconnect()
        }
    }

    private fun bindingLost(what: String): Unit {
        main.removeCallbacks(bindTimeoutRun)
        val c = appCtx ?: return
        svc = null; binding = false; running = false
        lastEvdev = null; lastCfg = ""
        status = "shizuku: $what"
        AppLog.add(c, "ERR", "shizuku: $what")
        runCatching { Shizuku.unbindUserService(userArgs(c), conn, false) }
        scheduleReconnect()
    }

    /** Logs the exact exception, drops the connection and retries with backoff. */
    private fun fail(c: Context, what: String, e: Throwable) {
        main.removeCallbacks(bindTimeoutRun)
        AppLog.add(c, "ERR", "shizuku: $what: ${describeError(e)}")
        status = "$what: ${e.javaClass.simpleName}: ${e.message}"
        running = false
        runCatching { Shizuku.unbindUserService(userArgs(c), conn, false) }
        svc = null; binding = false
        lastEvdev = null; lastCfg = ""
        scheduleReconnect()
    }

    private fun scheduleReconnect() {
        val c = appCtx ?: return
        if (!wantUp || reconnectPending) return
        if (!Engine.shizukuRunning()) return // no timers while Shizuku is down: the sticky binder listener restarts us
        if (reconnectAttempt >= MAX_RECONNECT) {
            AppLog.add(c, "SYS", "shizuku: giving up after $MAX_RECONNECT reconnect attempts")
            return
        }
        val delay = 1000L shl reconnectAttempt
        reconnectAttempt++
        reconnectPending = true
        AppLog.add(c, "SYS", "shizuku: reconnecting in ${delay / 1000}s (attempt $reconnectAttempt)")
        main.postDelayed(reconnectRun, delay)
    }

    /** Call once (Activity / AccessibilityService). Re-connects automatically if enabled. */
    fun install(ctx: Context) {
        appCtx = ctx.applicationContext
        if (installed) return
        installed = true
        Shizuku.addBinderReceivedListenerSticky {
            val c = appCtx
            if (c != null && Engine.shellWanted(c)) main.post { reconnectAttempt = 0; start(c) }
        }
        Shizuku.addBinderDeadListener {
            main.post {
                running = false; binding = false
                main.removeCallbacks(reconnectRun); main.removeCallbacks(bindTimeoutRun); reconnectPending = false
                st("Shizuku stopped – start it again", true)
                // If it died while the service was hidden, put the service back.
                appCtx?.let { A11yGuard.check(it, "shizuku stopped", 1000) }
            }
        }
    }

    private fun userArgs(ctx: Context) =
        Shizuku.UserServiceArgs(ComponentName(ctx.packageName, EventService::class.java.name))
            .daemon(true).processNameSuffix("events").debuggable(false).version(BuildConfig.VERSION_CODE)

    /** Start / stop / re-push settings so the Shizuku process matches what is currently wanted. */
    fun refresh(ctx: Context) {
        val c = ctx.applicationContext
        appCtx = c
        syncScreenReceiver(c)
        if (Engine.shellWanted(c) || recording) {
            main.removeCallbacks(stopRun); stopPending = false
            if (running) pushAll(c) else start(c)
        } else if ((running || binding || svc != null) && SystemClock.elapsedRealtime() > grantUntil) {
            if (!stopPending) { stopPending = true; main.postDelayed(stopRun, 4000L) }
        }
    }

    private fun pushAll(c: Context) {
        val s = svc ?: return
        val evdev = evdevTarget(c)
        try {
            if (lastEvdev != evdev) {
                s.start(callback, evdev != null, evdev ?: "")
                lastEvdev = evdev
                st(if (evdev != null) "listening" else if (Engine.rawWanted(c)) "paused (screen off)" else "connected")
            }
            pushConfig(c, false)
        } catch (e: Throwable) {
            fail(c, "update failed", e)
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
        } catch (e: Throwable) {
            AppLog.add(c, "ERR", "shizuku: configure failed: ${describeError(e)}")
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
        wantUp = true
        if (running || binding) return
        if (!Shizuku.pingBinder()) { st("Shizuku is not running. Start it first.", true); return } // no retry timer: the sticky binder listener starts us when Shizuku appears
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
        main.removeCallbacks(bindTimeoutRun)
        main.postDelayed(bindTimeoutRun, BIND_TIMEOUT_MS)
        try {
            Shizuku.bindUserService(userArgs(ctx), conn)
        } catch (e: Throwable) {
            main.removeCallbacks(bindTimeoutRun)
            binding = false
            AppLog.add(ctx, "ERR", "shizuku: bind failed: ${describeError(e)}")
            status = "bind failed: ${e.message}"
            scheduleReconnect()
        }
    }

    fun stop(reason: String = "requested") {
        val c = appCtx
        val was = running || binding || svc != null
        wantUp = false
        reconnectAttempt = 0; reconnectPending = false
        main.removeCallbacks(reconnectRun); main.removeCallbacks(bindTimeoutRun)
        main.removeCallbacks(stopRun); stopPending = false
        runCatching { svc?.stop() }
        // remove = true also destroys the daemon (it restores the service first)
        if (c != null) runCatching { Shizuku.unbindUserService(userArgs(c), conn, true) }
        svc = null; running = false; binding = false
        lastEvdev = null; lastCfg = ""
        status = "Shizuku listener: off"
        if (was) appCtx?.let { AppLog.add(it, "SYS", "shizuku: listener stopped ($reason)") }
    }

    // ------------------------------------------------------------ WRITE_SECURE_SETTINGS via Shizuku
    /** Grants WRITE_SECURE_SETTINGS to this app once (like `adb shell pm grant`). */
    fun grantSecure(ctx: Context) {
        val c = ctx.applicationContext
        appCtx = c
        grantUntil = SystemClock.elapsedRealtime() + 15_000L // don't stop the helper mid-grant
        val s = svc
        if (s == null) { wantGrant = true; start(c); return }
        doGrant(s, c)
    }

    private fun doGrant(s: IUserService, c: Context) {
        Thread {
            val ok = try {
                s.grantSecure(c.packageName)
            } catch (e: Throwable) {
                AppLog.add(c, "ERR", "shizuku: grant failed: ${describeError(e)}")
                false
            }
            // The permission usually shows up at once; give it a moment before reporting.
            var effective = A11yGuard.canWrite(c)
            var tries = 0
            while (ok && !effective && tries < 10) {
                try { Thread.sleep(200) } catch (_: Throwable) { }
                effective = A11yGuard.canWrite(c)
                tries++
            }
            main.post {
                AppLog.add(
                    c, "SYS",
                    "WRITE_SECURE_SETTINGS grant via Shizuku: ${if (ok) "ok" else "failed"}" +
                        if (ok && !effective) " (not visible to the app yet)" else ""
                )
                grantUntil = 0L
                A11yGuard.grantChanged() // UI re-reads the granted state right now
                Engine.sync(c)
            }
        }.start()
    }

    // ------------------------------------------------------------ record
    fun beginRecord(ctx: Context) {
        appCtx = ctx.applicationContext
        log.clear(); captured = null; lastA11yKey = null; recording = true
        AppLog.add(appCtx!!, "SYS", "record: started (helper opens every readable input device)")
        TriggerService.refresh() // makes the service enable key filtering for the recording
        refresh(ctx)
    }

    fun endRecord() {
        recording = false
        appCtx?.let { AppLog.add(it, "SYS", "record: ended") }
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
        // Key lines only while recording, or for the recorded device; at most one per second.
        if (recording || name == recordedDevice(ctx)) {
            val now = SystemClock.elapsedRealtime()
            if (now - lastKeyLog >= 1000L) {
                lastKeyLog = now
                AppLog.add(ctx, "KEY", "${if (down) "down" else "up"} $name · ${label(type, code)}")
            }
        }
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
