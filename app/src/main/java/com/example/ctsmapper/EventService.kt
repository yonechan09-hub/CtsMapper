package com.example.ctsmapper

import android.content.ComponentName
import android.content.Context
import android.os.SystemClock
import java.io.File
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Locale

/**
 * Runs in a separate process with shell (adb) privileges, started by Shizuku as a DAEMON, so it keeps
 * working after the app process is swiped away / killed.
 *
 * 1. Optional: reads /dev/input/event* (EV_KEY / EV_SW only) for the Plus Key.
 * 2. Optional recovery watcher (event driven, reads the system event log stream, no polling): if the app's
 *    own process has died while the accessibility service is still enabled ("Not working"), toggle it off
 *    and back on so Android rebinds it.
 *
 * Must not touch app resources / Context (it runs outside the normal app environment).
 */
class EventService() : IUserService.Stub() {
    @Suppress("UNUSED_PARAMETER")
    constructor(context: Context) : this()

    @Volatile private var gen = 0
    @Volatile private var cb: IKeyCallback? = null
    private val readers = ArrayList<FileInputStream>()

    private val lock = Any()
    private var target: ComponentName? = null
    private var component = ""
    private var appPkg = ""
    private var recover = false
    private var lastRecover = 0L

    @Volatile private var watchGen = 0
    @Volatile private var logcat: Process? = null
    private var watching = false

    /** Closing the stream interrupts the blocked read(), so no reader thread / fd survives. */
    private fun closeReaders() {
        val l = synchronized(readers) { val c = ArrayList(readers); readers.clear(); c }
        l.forEach { runCatching { it.close() } }
    }

    override fun destroy() {
        gen++
        closeReaders()
        stopWatcher()
        System.exit(0)
    }

    override fun stop() { gen++; closeReaders() }

    // ------------------------------------------------------------------ evdev (Plus Key)
    override fun start(cb: IKeyCallback, evdev: Boolean) {
        this.cb = cb
        val g = ++gen
        closeReaders()
        if (!evdev) return
        File("/dev/input").listFiles { f -> f.name.startsWith("event") }?.forEach { f ->
            val name = runCatching { File("/sys/class/input/${f.name}/device/name").readText().trim() }
                .getOrDefault(f.name)
            Thread { readLoop(f, name, cb, g) }.apply { isDaemon = true }.start()
        }
    }

    private fun readLoop(f: File, name: String, cb: IKeyCallback, g: Int) {
        val size = if (android.os.Process.is64Bit()) 24 else 16 // struct input_event
        val off = size - 8                                       // type(2) code(2) value(4)
        val buf = ByteArray(size)
        val bb = ByteBuffer.wrap(buf).order(ByteOrder.nativeOrder())
        try {
            FileInputStream(f).use { ins ->
                synchronized(readers) {
                    if (g != gen) return
                    readers.add(ins)
                }
                while (g == gen) {
                    var n = 0
                    while (n < size) {
                        val r = ins.read(buf, n, size - n)
                        if (r < 0) return
                        n += r
                    }
                    val type = bb.getShort(off).toInt() and 0xFFFF
                    val code = bb.getShort(off + 2).toInt() and 0xFFFF
                    val value = bb.getInt(off + 4)
                    val isKey = type == 1 && code !in 320..335 // skip touch (BTN_TOUCH etc.)
                    val isSwitch = type == 5
                    if ((isKey || isSwitch) && (value == 0 || value == 1) && g == gen) {
                        cb.onEvent(f.path, name, type, code, value)
                    }
                }
            }
        } catch (_: Throwable) { /* node not readable, closed on screen off, or app gone */ }
    }

    // ------------------------------------------------------------------ config from the app
    override fun configure(component: String, recover: Boolean) {
        synchronized(lock) {
            this.component = component
            this.target = ComponentName.unflattenFromString(component)
            this.appPkg = target?.packageName ?: ""
            this.recover = recover
        }
        if (recover) ensureWatcher() else stopWatcher()
    }

    override fun grantSecure(pkg: String): Boolean =
        sh("pm", "grant", pkg, "android.permission.WRITE_SECURE_SETTINGS").first == 0

    /** Manual "Restore service" fallback: puts the component back right now, unconditionally. */
    override fun restoreService(component: String): Boolean {
        val t = ComponentName.unflattenFromString(component) ?: return false
        val list = readList()
        if (list.none { same(it, t) }) list.add(component)
        return sh("sh", "-c", listScript(list)).first == 0
    }

    // ------------------------------------------------------------------ dead-process recovery watcher
    private val resumedRe = Regex("""wm_set_resumed_activity.*?\[\d+,([\w.]+)/""")
    private val ignore = setOf(
        "com.android.systemui", "com.android.permissioncontroller",
        "com.google.android.permissioncontroller", "com.android.intentresolver"
    )

    private fun ensureWatcher() {
        synchronized(lock) {
            if (watching) return
            watching = true
            val g = ++watchGen
            Thread { watchLoop(g) }.apply { isDaemon = true }.start()
        }
    }

    private fun stopWatcher() {
        synchronized(lock) {
            watching = false
            watchGen++
            runCatching { logcat?.destroy() }
            logcat = null
        }
    }

    private fun watchLoop(g: Int) {
        var first = true
        while (g == watchGen) {
            try {
                val now = System.currentTimeMillis()
                val since = String.format(Locale.US, "%d.%03d", now / 1000, now % 1000)
                val p = ProcessBuilder(
                    "logcat", "-b", "events", "-v", "brief", "-T", since,
                    "wm_set_resumed_activity:I", "*:S"
                ).redirectErrorStream(true).start()
                logcat = p
                if (first) {
                    first = false
                    info("recovery watcher started")
                    recoverLocked()
                }
                p.inputStream.bufferedReader().forEachLine { line -> if (g == watchGen) handleLine(line) }
            } catch (_: Throwable) { }
            if (g != watchGen) return
            try { Thread.sleep(3000) } catch (_: Throwable) { }
        }
    }

    private fun handleLine(line: String) {
        val r = resumedRe.find(line) ?: return
        if (r.groupValues[1] in ignore) return
        recoverLocked()
    }

    // ------------------------------------------------------------------ recovery
    private fun same(s: String, t: ComponentName): Boolean {
        val a = ComponentName.unflattenFromString(s)
        return a != null && a == t
    }

    private fun readList(): MutableList<String> {
        val out = sh("settings", "get", "secure", "enabled_accessibility_services").second
        if (out.isBlank() || out == "null") return mutableListOf()
        return out.split(':').filter { it.isNotBlank() }.toMutableList()
    }

    private fun listScript(list: List<String>): String =
        if (list.isEmpty()) {
            "settings delete secure enabled_accessibility_services; settings put secure accessibility_enabled 0"
        } else {
            "settings put secure enabled_accessibility_services '${list.joinToString(":")}'; " +
                "settings put secure accessibility_enabled 1"
        }

    /** App process is gone but the service is still enabled/stuck: toggle it so the system rebinds it. */
    private fun recoverLocked() {
        val t: ComponentName
        synchronized(lock) {
            if (!recover || target == null) return
            t = target!!
        }
        val now = SystemClock.elapsedRealtime()
        if (now - lastRecover < 20_000) return
        if (sh("sh", "-c", "ps -A -o NAME | grep -x $appPkg").first == 0) return // process alive
        lastRecover = now
        val list = readList()
        if (list.any { same(it, t) }) {
            sh("sh", "-c", listScript(list.filterNot { same(it, t) }))
            try { Thread.sleep(400) } catch (_: Throwable) { }
        }
        val l2 = readList()
        if (l2.none { same(it, t) }) l2.add(component)
        sh("sh", "-c", listScript(l2))
        info("app process was dead: accessibility service re-enabled")
    }

    private fun info(msg: String) { runCatching { cb?.onInfo(msg) } }

    private fun sh(vararg cmd: String): Pair<Int, String> = try {
        val p = ProcessBuilder(*cmd).redirectErrorStream(true).start()
        val out = p.inputStream.bufferedReader().readText().trim()
        p.waitFor() to out
    } catch (e: Throwable) {
        -1 to ""
    }
}
