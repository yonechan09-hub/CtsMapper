package com.example.ctsmapper

import android.content.ComponentName
import android.content.Context
import android.os.IBinder
import android.os.RemoteException
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import android.system.StructPollfd
import java.io.File
import java.io.FileDescriptor
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Runs in a separate process with shell (adb) privileges, started by Shizuku as a DAEMON, so it keeps
 * working after the app process is swiped away / killed.
 *
 * 1. Optional: reads /dev/input/event* for the Plus Key: every readable node while recording (device == ""),
 *    otherwise only the recorded device (if it is not found, nothing is opened and the helper stays idle).
 *    One thread blocks in poll() on the nodes plus a stop pipe.
 * 2. Optional dead-process recovery, purely event driven (no watcher, no polling): when the app's callback binder
 *    dies, or when configure(recover = true) arrives, one debounced check runs in a single `sh -c`. If the app
 *    process is gone while the accessibility service is still enabled ("Not working"), the service is toggled
 *    off and back on so Android rebinds it.
 *
 * Must not touch app resources / Context (it runs outside the normal app environment).
 */
class EventService() : IUserService.Stub() {
    @Suppress("UNUSED_PARAMETER")
    constructor(context: Context) : this()

    private companion object {
        const val DEATH_DEBOUNCE_MS = 1500L
        const val CONFIG_DEBOUNCE_MS = 500L
    }

    @Volatile private var gen = 0
    @Volatile private var cb: IKeyCallback? = null
    private var session: Session? = null
    private var deathCb: IKeyCallback? = null

    private val deathRecipient = IBinder.DeathRecipient {
        // App process died: nothing is listening any more, so stop reading input right away.
        gen++
        stopSession()
        scheduleRecovery(DEATH_DEBOUNCE_MS)
    }

    init {
        // Nothing in this process may take the whole service down: report the exception instead.
        Thread.setDefaultUncaughtExceptionHandler { t, e -> info("ERR uncaught in ${t.name}: ${describeError(e)}") }
    }

    private class Dev(val fd: FileDescriptor, val path: String, val name: String)

    /** One start(): the opened devices plus a stop pipe. Closing the write end wakes the reader's poll(). */
    private class Session(val devs: List<Dev>, val stopRd: FileDescriptor, private val stopWr: FileDescriptor) {
        private val wrClosed = AtomicBoolean(false)
        fun stop() { if (wrClosed.compareAndSet(false, true)) closeFd(stopWr) }
    }

    private val lock = Any()
    private var target: ComponentName? = null
    private var component = ""
    private var appPkg = ""
    private var recover = false

    // One debounced recovery run: every trigger (binder death, configure) just re-arms this single timer.
    private val recoveryExec: ScheduledThreadPoolExecutor by lazy {
        ScheduledThreadPoolExecutor(1) { r -> Thread(r, "recovery").apply { isDaemon = true } }
            .apply { removeOnCancelPolicy = true }
    }
    private var recoveryTask: ScheduledFuture<*>? = null

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
        synchronized(lock) { recoveryTask?.cancel(false); recoveryTask = null }
        System.exit(0)
    }

    override fun stop() { gen++; stopSession() }

    // ------------------------------------------------------------------ evdev (Plus Key)
    @Synchronized
    override fun start(cb: IKeyCallback, evdev: Boolean, device: String) {
        try {
            this.cb = cb
            val g = ++gen
            stopSession()
            linkDeath(cb)
            if (!evdev || g != gen) return
            openAndRead(cb, g, device)
        } catch (t: Throwable) {
            // Never let anything escape into the binder thread: that would kill this process.
            info("ERR start(): ${describeError(t)}")
        }
    }

    /** device == "" (recording): open every readable event node. Otherwise only the node(s) with that name; if there are none, stay idle. */
    private fun openAndRead(cb: IKeyCallback, g: Int, device: String) {
        val nodes = (File("/dev/input").listFiles { f -> f.name.startsWith("event") } ?: emptyArray<File>())
            .sortedBy { it.name.removePrefix("event").toIntOrNull() ?: Int.MAX_VALUE }
        if (nodes.isEmpty()) { info("ERR no /dev/input/event* nodes visible"); return }
        val names = deviceNames()
        val labelled = nodes.map { f ->
            val n = names[f.name]
                ?: runCatching { File("/sys/class/input/${f.name}/device/name").readText().trim() }.getOrNull()?.takeIf { it.isNotBlank() }
                ?: f.name
            f to n
        }

        val opened = ArrayList<Dev>()
        val failed = ArrayList<String>()
        fun openAll(list: List<Pair<File, String>>) {
            for ((f, n) in list) {
                try {
                    val fd = Os.open(f.path, OsConstants.O_RDONLY or OsConstants.O_NONBLOCK or OsConstants.O_CLOEXEC, 0)
                    opened.add(Dev(fd, f.path, n))
                } catch (t: Throwable) {
                    failed.add("${f.name}: ${t.javaClass.simpleName} ${t.message}") // one bad node never stops the rest
                }
            }
        }
        val mode: String
        if (device.isEmpty()) {
            mode = "all"
            openAll(labelled)
        } else {
            mode = "recorded"
            openAll(labelled.filter { it.second == device || it.first.name == device })
            if (opened.isEmpty()) {
                info("recorded device '$device' not found or not readable: staying idle (record the key again)")
            }
        }
        if (failed.isNotEmpty()) infoChunks("open failed", failed.joinToString("; "))
        infoChunks("opened ${opened.size}/${nodes.size} ($mode)", opened.joinToString(", ") { "${File(it.path).name}=${it.name}" })
        if (opened.isEmpty() || g != gen) { opened.forEach { closeFd(it.fd) }; return }

        val pipe = try { Os.pipe() } catch (t: Throwable) {
            info("ERR pipe(): ${describeError(t)}")
            opened.forEach { closeFd(it.fd) }
            return
        }
        val s = Session(opened, pipe[0], pipe[1])
        session = s
        try {
            Thread { readLoop(s, g, cb) }.apply { isDaemon = true; name = "evdev-reader" }.start()
        } catch (t: Throwable) {
            info("ERR reader thread: ${describeError(t)}")
            session = null
            s.stop(); closeFd(s.stopRd)
            opened.forEach { closeFd(it.fd) }
        }
    }

    /** node -> device name, from /proc/bus/input/devices (best effort; callers fall back to sysfs / node name). */
    private fun deviceNames(): Map<String, String> {
        val m = HashMap<String, String>()
        runCatching {
            var name: String? = null
            File("/proc/bus/input/devices").forEachLine { l ->
                when {
                    l.startsWith("N: Name=") -> name = l.substringAfter("Name=").trim().removeSurrounding("\"")
                    l.startsWith("H: Handlers=") -> {
                        val n = name
                        if (n != null) Regex("event\\d+").findAll(l).forEach { m[it.value] = n }
                    }
                    l.isBlank() -> name = null
                    else -> Unit
                }
            }
        }
        return m
    }

    private fun infoChunks(head: String, body: String) {
        if (body.isEmpty()) { info(head); return }
        var i = 0
        var first = true
        while (i < body.length) {
            val part = body.substring(i, minOf(body.length, i + 200))
            info(if (first) "$head: $part" else "  ...$part")
            first = false
            i += 200
        }
    }

    /** One thread polls every opened node plus the stop pipe. A node that fails is dropped, the rest keep going. */
    private fun readLoop(s: Session, g: Int, cb: IKeyCallback) {
        val live = ArrayList(s.devs)
        val size = if (android.os.Process.is64Bit()) 24 else 16 // struct input_event
        val off = size - 8                                       // type(2) code(2) value(4)
        val buf = ByteArray(size * 32)
        val bb = ByteBuffer.wrap(buf).order(ByteOrder.nativeOrder())
        fun pollSet(): Array<StructPollfd> {
            val a = ArrayList<StructPollfd>(live.size + 1)
            a.add(pollfd(s.stopRd))
            live.forEach { a.add(pollfd(it.fd)) }
            return a.toTypedArray()
        }
        var pf = pollSet()
        try {
            while (g == gen && live.isNotEmpty()) {
                pf.forEach { it.revents = 0 }
                try {
                    Os.poll(pf, -1)
                } catch (e: ErrnoException) {
                    if (e.errno == OsConstants.EINTR) continue
                    info("ERR poll(): ${describeError(e)}")
                    return
                }
                if (pf[0].revents.toInt() != 0) return // stop pipe closed
                val dead = ArrayList<Dev>()
                for (i in live.indices) {
                    val d = live[i]
                    val rev = pf[i + 1].revents.toInt()
                    if (rev == 0) continue
                    if (rev and OsConstants.POLLIN != 0) {
                        val n = try {
                            Os.read(d.fd, buf, 0, buf.size)
                        } catch (e: ErrnoException) {
                            if (e.errno == OsConstants.EAGAIN || e.errno == OsConstants.EINTR) continue
                            info("ERR read ${File(d.path).name}: ${describeError(e)}")
                            dead.add(d)
                            continue
                        }
                        if (n <= 0) { dead.add(d); continue }
                        var p = 0
                        while (p + size <= n) {
                            val type = bb.getShort(p + off).toInt() and 0xFFFF
                            val code = bb.getShort(p + off + 2).toInt() and 0xFFFF
                            val value = bb.getInt(p + off + 4)
                            p += size
                            val isKey = type == 1 && code !in 320..335 // skip touch (BTN_TOUCH etc.)
                            val isSwitch = type == 5
                            if ((isKey || isSwitch) && (value == 0 || value == 1) && g == gen) {
                                try {
                                    cb.onEvent(d.path, d.name, type, code, value)
                                } catch (e: Throwable) {
                                    if (e is RemoteException) return // app is gone
                                    info("ERR onEvent: ${describeError(e)}")
                                }
                            }
                        }
                    } else if (rev and (OsConstants.POLLERR or OsConstants.POLLHUP or OsConstants.POLLNVAL) != 0) {
                        info("device closed: ${File(d.path).name} (revents=$rev)")
                        dead.add(d)
                    }
                }
                if (dead.isNotEmpty()) {
                    dead.forEach { closeFd(it.fd) }
                    live.removeAll(dead.toSet())
                    pf = pollSet()
                }
            }
        } catch (t: Throwable) {
            info("ERR reader: ${describeError(t)}")
        } finally {
            live.forEach { closeFd(it.fd) }
            s.stop()
            closeFd(s.stopRd)
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
        if (recover) scheduleRecovery(CONFIG_DEBOUNCE_MS)
        else synchronized(lock) { recoveryTask?.cancel(false); recoveryTask = null }
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

    /** (Re)arms the single debounced recovery run: bursts of triggers collapse into one check. */
    private fun scheduleRecovery(delayMs: Long) {
        synchronized(lock) {
            if (!recover || target == null) return
            try {
                recoveryTask?.cancel(false)
                recoveryTask = recoveryExec.schedule(Runnable { recoverLocked() }, delayMs, TimeUnit.MILLISECONDS)
            } catch (t: Throwable) {
                info("ERR scheduleRecovery: ${describeError(t)}")
            }
        }
    }

    private fun q(v: String) = "'" + v.replace("'", "'\\''") + "'"

    /**
     * App process is gone but the service is still enabled/stuck: toggle it so the system rebinds it.
     * Check and toggle run in ONE `sh -c` (no pidof / settings get processes from Kotlin).
     * Exit codes: 0 toggled, 10 app process alive or unknown (never toggle on a guess), 11 service not enabled.
     */
    private fun recoverLocked() {
        val t: ComponentName
        val pkg: String
        synchronized(lock) {
            if (!recover) return
            t = target ?: return
            pkg = appPkg
        }
        val flat = t.flattenToString()
        val short = t.flattenToShortString()
        val script = """
            P=${q(pkg)}; F=${q(flat)}; S=${q(short)}
            pidof "${'$'}P" >/dev/null 2>&1; [ ${'$'}? -eq 1 ] || exit 10
            cur=${'$'}(settings get secure enabled_accessibility_services)
            case ":${'$'}cur:" in *":${'$'}F:"*|*":${'$'}S:"*) ;; *) exit 11;; esac
            set -f; n=; IFS=:
            for e in ${'$'}cur; do [ "${'$'}e" = "${'$'}F" ] || [ "${'$'}e" = "${'$'}S" ] || n="${'$'}{n:+${'$'}n:}${'$'}e"; done
            unset IFS
            if [ -n "${'$'}n" ]; then settings put secure enabled_accessibility_services "${'$'}n"; else settings delete secure enabled_accessibility_services; fi
            sleep 0.4
            settings put secure enabled_accessibility_services "${'$'}cur"
        """.trimIndent()
        val r = sh("sh", "-c", script)
        if (r.first == 0) info("app process was dead: accessibility service re-enabled")
        else if (r.first != 10 && r.first != 11) info("ERR recovery script exit ${r.first}: ${r.second.take(120)}")
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

private fun pollfd(f: FileDescriptor): StructPollfd {
    val p = StructPollfd()
    p.fd = f
    p.events = OsConstants.POLLIN.toShort()
    return p
}
