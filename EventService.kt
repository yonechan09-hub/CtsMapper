package com.example.ctsmapper

import android.content.ComponentName
import android.content.Context
import android.os.IBinder
import android.os.SystemClock
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import android.system.StructPollfd
import java.io.File
import java.io.FileDescriptor
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * Runs in a separate process with shell (adb) privileges, started by Shizuku as a DAEMON, so it keeps
 * working after the app process is swiped away / killed.
 *
 * 1. Optional: reads only the recorded /dev/input/event* device (or only EV_KEY devices while recording) for the
 *    Plus Key. Never touchscreen or sensor nodes. Readers block in poll() on the device and a stop pipe.
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
    private var session: Session? = null
    private var deathCb: IKeyCallback? = null

    private val deathRecipient = IBinder.DeathRecipient {
        // App process died: nothing is listening any more, so stop reading input right away.
        gen++
        stopSession()
    }

    /** One start(): a stop pipe shared by its reader threads. Closing the write end wakes every poll(). */
    private class Session(val stopRd: FileDescriptor, private val stopWr: FileDescriptor, count: Int) {
        private val users = AtomicInteger(count)
        private val wrClosed = AtomicBoolean(false)
        fun stop() { if (wrClosed.compareAndSet(false, true)) closeFd(stopWr) }
        fun release() { if (users.decrementAndGet() == 0) { stop(); closeFd(stopRd) } }
    }

    private val lock = Any()
    private var target: ComponentName? = null
    private var component = ""
    private var appPkg = ""
    private var recover = false
    private var lastCheck = 0L

    @Volatile private var watchGen = 0
    @Volatile private var logcat: Process? = null
    private var watching = false

    @Synchronized
    private fun stopSession() {
        session?.stop()
        session = null
    }

    private fun linkDeath(cb: IKeyCallback) {
        if (deathCb?.asBinder() === cb.asBinder()) return
        runCatching { deathCb?.asBinder()?.unlinkToDeath(deathRecipient, 0) }
        deathCb = null
        try {
            cb.asBinder().linkToDeath(deathRecipient, 0)
            deathCb = cb
        } catch (_: Throwable) { gen++; stopSession() } // already dead
    }

    override fun destroy() {
        gen++
        stopSession()
        runCatching { deathCb?.asBinder()?.unlinkToDeath(deathRecipient, 0) }
        stopWatcher()
        System.exit(0)
    }

    override fun stop() { gen++; stopSession() }

    // ------------------------------------------------------------------ evdev (Plus Key)
    @Synchronized
    override fun start(cb: IKeyCallback, evdev: Boolean, device: String) {
        this.cb = cb
        val g = ++gen
        stopSession()
        linkDeath(cb)
        if (!evdev || g != gen) return

        val opened = ArrayList<Triple<FileDescriptor, String, String>>() // fd, path, name
        File("/dev/input").listFiles { f -> f.name.startsWith("event") }?.sortedBy { it.name }?.forEach { f ->
            val name = runCatching { File("/sys/class/input/${f.name}/device/name").readText().trim() }
                .getOrDefault(f.name)
            val ok = if (device.isEmpty()) isKeyDevice(f.name, name) else name == device
            if (!ok) return@forEach
            try {
                opened.add(Triple(Os.open(f.path, OsConstants.O_RDONLY or OsConstants.O_NONBLOCK or OsConstants.O_CLOEXEC, 0), f.path, name))
            } catch (_: Throwable) { /* node not readable */ }
        }
        if (opened.isEmpty()) return
        val pipe = try { Os.pipe() } catch (_: Throwable) { opened.forEach { closeFd(it.first) }; return }
        val s = Session(pipe[0], pipe[1], opened.size)
        session = s
        opened.forEach { (fd, path, name) ->
            Thread { readLoop(fd, path, name, cb, g, s) }.apply { isDaemon = true }.start()
        }
    }

    /** Only keyboard-like nodes: has EV_KEY and neither EV_ABS (touchscreen, many sensors) nor EV_REL. */
    private fun isKeyDevice(node: String, name: String): Boolean {
        val ev = runCatching {
            File("/sys/class/input/$node/device/capabilities/ev").readText().trim().split(' ').last().toLong(16)
        }.getOrNull() ?: return false
        val key = ev and (1L shl 1) != 0L
        val rel = ev and (1L shl 2) != 0L
        val abs = ev and (1L shl 3) != 0L
        return key && !rel && !abs && !name.contains("touch", true) && !name.contains("sensor", true)
    }

    private fun readLoop(fd: FileDescriptor, path: String, name: String, cb: IKeyCallback, g: Int, s: Session) {
        val size = if (android.os.Process.is64Bit()) 24 else 16 // struct input_event
        val off = size - 8                                       // type(2) code(2) value(4)
        val buf = ByteArray(size * 16)
        val bb = ByteBuffer.wrap(buf).order(ByteOrder.nativeOrder())
        val fds = arrayOf(
            StructPollfd().apply { this.fd = fd; events = OsConstants.POLLIN.toShort() },
            StructPollfd().apply { this.fd = s.stopRd; events = OsConstants.POLLIN.toShort() }
        )
        try {
            while (g == gen) {
                fds[0].revents = 0; fds[1].revents = 0
                try {
                    Os.poll(fds, -1)
                } catch (e: ErrnoException) {
                    if (e.errno == OsConstants.EINTR) continue
                    return
                }
                if (fds[1].revents.toInt() != 0) return // stop pipe closed / written
                val bad = OsConstants.POLLERR or OsConstants.POLLHUP or OsConstants.POLLNVAL
                if (fds[0].revents.toInt() and bad != 0) return
                if (fds[0].revents.toInt() and OsConstants.POLLIN == 0) continue
                val n = try {
                    Os.read(fd, buf, 0, buf.size)
                } catch (e: ErrnoException) {
                    if (e.errno == OsConstants.EAGAIN || e.errno == OsConstants.EINTR) continue
                    return
                }
                if (n <= 0) return
                var p = 0
                while (p + size <= n) {
                    val type = bb.getShort(p + off).toInt() and 0xFFFF
                    val code = bb.getShort(p + off + 2).toInt() and 0xFFFF
                    val value = bb.getInt(p + off + 4)
                    p += size
                    val isKey = type == 1 && code !in 320..335 // skip touch (BTN_TOUCH etc.)
                    val isSwitch = type == 5
                    if ((isKey || isSwitch) && (value == 0 || value == 1) && g == gen) {
                        cb.onEvent(path, name, type, code, value)
                    }
                }
            }
        } catch (_: Throwable) { /* app gone or callback dead */ }
        finally {
            closeFd(fd)
            s.release()
        }
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
        if (now - lastCheck < 20_000) return // throttle: at most one process check per 20 s
        lastCheck = now
        if (sh("pidof", appPkg).first == 0) return // process alive
        val list = readList()
        if (list.none { same(it, t) }) return // service not enabled: nothing to recover
        sh("sh", "-c", listScript(list.filterNot { same(it, t) }))
        try { Thread.sleep(400) } catch (_: Throwable) { }
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

private fun closeFd(fd: FileDescriptor) { try { Os.close(fd) } catch (_: Throwable) { } }
