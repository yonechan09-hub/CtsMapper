package com.example.ctsmapper

import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Last 50 events, persisted in its own prefs file (NOT "cfg", so writes never re-trigger config listeners). */
object AppLog {
    private const val MAX = 50
    private val lock = Any()
    private val items = ArrayList<String>()
    private var loaded = false
    private var crashHooked = false
    private val fmt = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US)
    private val main = Handler(Looper.getMainLooper())

    /** Read this in Compose to get recomposed when a line is added. */
    var version by mutableIntStateOf(0)
        private set

    private fun prefs(c: Context) =
        c.applicationContext.getSharedPreferences("log", Context.MODE_PRIVATE)

    private fun ensureLoaded(c: Context) {
        if (loaded) return
        loaded = true
        prefs(c).getString("lines", "")?.split("\n")?.filter { it.isNotBlank() }?.let { items.addAll(it) }
        while (items.size > MAX) items.removeAt(0)
    }

    fun add(c: Context, kind: String, msg: String) = write(c, kind, msg, sync = false)

    private fun write(c: Context, kind: String, msg: String, sync: Boolean) {
        val clean = msg.replace('\n', ' ').take(300)
        synchronized(lock) {
            ensureLoaded(c)
            items.add("${fmt.format(Date())} ${kind.padEnd(4)} $clean")
            while (items.size > MAX) items.removeAt(0)
            val e = prefs(c).edit().putString("lines", items.joinToString("\n"))
            if (sync) e.commit() else e.apply()
        }
        if (!sync) main.post { version++ }
    }

    fun lines(c: Context): List<String> = synchronized(lock) { ensureLoaded(c); items.toList() }

    fun clear(c: Context) {
        synchronized(lock) { ensureLoaded(c); items.clear(); prefs(c).edit().putString("lines", "").apply() }
        main.post { version++ }
    }

    /** Records uncaught exceptions of the app process in the log before the process dies. */
    fun installCrashHandler(c: Context) {
        if (crashHooked) return
        crashHooked = true
        val app = c.applicationContext
        val old = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            runCatching {
                val top = e.stackTrace.take(4).joinToString(" | ") { "${it.fileName}:${it.lineNumber}" }
                write(app, "CRSH", "${e.javaClass.name}: ${e.message} @ $top", sync = true)
            }
            old?.uncaughtException(t, e)
        }
    }
}
