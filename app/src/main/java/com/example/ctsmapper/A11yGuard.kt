package com.example.ctsmapper

import android.Manifest
import android.app.Activity
import android.app.AlarmManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.IBinder
import android.os.PowerManager
import android.os.SystemClock
import android.provider.Settings

/**
 * Keeps the accessibility service alive and makes it recoverable, and lets the user switch it off/on by hand.
 *
 * - With WRITE_SECURE_SETTINGS (granted once via Shizuku / adb) the app can switch its own service off and on in
 *   ENABLED_ACCESSIBILITY_SERVICES, which forces Android to rebind it when it shows "Not working", and is also
 *   what the manual toggle (button / Quick Settings tile) uses.
 * - No polling: recovery runs on app open, boot, package update, Shizuku death and one-shot alarms armed when the
 *   task is swiped away. Nothing in this app watches for a particular app coming to the foreground.
 */
object A11yGuard {
    const val ACTION_WAKE = "com.example.ctsmapper.WAKE"
    private const val KEY = Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES

    @Volatile private var expectUnbindUntil = 0L
    @Volatile private var lastRebind = 0L

    fun component(ctx: Context): String = ComponentName(ctx, TriggerService::class.java).flattenToString()

    fun canWrite(ctx: Context): Boolean =
        ctx.checkSelfPermission(Manifest.permission.WRITE_SECURE_SETTINGS) == PackageManager.PERMISSION_GRANTED

    fun expectUnbind(ms: Long = 8000L) {
        expectUnbindUntil = SystemClock.elapsedRealtime() + ms
    }

    // ------------------------------------------------------------------ settings list
    private fun list(ctx: Context): MutableList<String> {
        val s = Settings.Secure.getString(ctx.contentResolver, KEY) ?: ""
        return s.split(':').filter { it.isNotBlank() }.toMutableList()
    }

    private fun same(ctx: Context, s: String): Boolean {
        val a = ComponentName.unflattenFromString(s)
        return a != null && a == ComponentName(ctx, TriggerService::class.java)
    }

    fun contains(ctx: Context): Boolean = list(ctx).any { same(ctx, it) }

    private fun write(ctx: Context, l: List<String>): Boolean = try {
        val cr = ctx.contentResolver
        Settings.Secure.putString(cr, KEY, if (l.isEmpty()) null else l.joinToString(":"))
        Settings.Secure.putInt(cr, Settings.Secure.ACCESSIBILITY_ENABLED, if (l.isEmpty()) 0 else 1)
        true
    } catch (e: Throwable) {
        AppLog.add(ctx, "ERR", "settings write failed: ${e.javaClass.simpleName}: ${e.message}")
        false
    }

    private fun addOurs(c: Context): Boolean {
        val l = list(c)
        if (l.none { same(c, it) }) l.add(component(c))
        return write(c, l)
    }

    private fun removeOurs(c: Context): Boolean = write(c, list(c).filterNot { same(c, it) })

    /** Blocking (sleeps): call from a worker thread. Off -> on makes the system bind the service again. */
    private fun rebind(c: Context) {
        expectUnbind()
        if (contains(c)) {
            removeOurs(c)
            try { Thread.sleep(500) } catch (_: Throwable) { }
        }
        addOurs(c)
    }

    // ------------------------------------------------------------------ recovery
    /** Re-enable the service if it is enabled-but-dead or missing. Safe to call from anywhere, any time. */
    fun check(ctx: Context, why: String, settleMs: Long = 2500L, n: Int = 0, done: (() -> Unit)? = null) {
        val c = ctx.applicationContext
        Thread {
            try {
                if (settleMs > 0) Thread.sleep(settleMs)
                checkNow(c, why, n)
            } catch (_: Throwable) { }
            done?.invoke()
        }.start()
    }

    private fun checkNow(c: Context, why: String, n: Int) {
        if (!Prefs.keepAlive(c) || Prefs.userOff(c)) return
        if (TriggerService.instance != null) return

        if (!canWrite(c)) {
            AppLog.add(c, "SYS", "recover($why): service not connected and WRITE_SECURE_SETTINGS is not granted")
            return
        }
        val now = SystemClock.elapsedRealtime()
        if (now - lastRebind < 8000L) return
        lastRebind = now
        AppLog.add(c, "SYS", "recover($why): service not connected - re-enabling")
        rebind(c)
        if (n < 3) armWake(c, 6000L, n + 1)
    }

    fun onConnected(c: Context) {
        Prefs.setUserOff(c, false)
        startGuard(c)
    }

    /** The service was unbound while the process stayed alive: decide if the user did it. */
    fun onUnbound(ctx: Context) {
        val app = ctx.applicationContext
        Thread {
            try { Thread.sleep(1500) } catch (_: Throwable) { }
            val expected = SystemClock.elapsedRealtime() < expectUnbindUntil
            if (!expected && !contains(app) && TriggerService.instance == null) {
                Prefs.setUserOff(app, true)
                AppLog.add(app, "SYS", "service was switched off by the user - auto-recover paused until it is switched on again")
                RawInput.pushConfig(app)
            }
        }.start()
    }

    /** Put the service back right now, whatever state it is in (UI button). */
    fun restoreNow(c: Context): String {
        Prefs.setUserOff(c, false)
        RawInput.pushConfig(c, true)
        if (canWrite(c)) {
            Thread {
                rebind(c)
                AppLog.add(c, "SYS", "restore: service re-enabled")
            }.start()
            return "Re-enabling the accessibility service…"
        }
        if (RawInput.restoreViaShizuku(c)) return "Asked the Shizuku helper to restore the service."
        return "Grant WRITE_SECURE_SETTINGS (or start Shizuku) first, or switch the service on in Accessibility settings."
    }

    // ------------------------------------------------------------------ manual toggle (button / QS tile)
    /**
     * User-initiated only: switches the service off right now. Nothing in the app calls this automatically
     * or in response to another app opening – it only runs when the person taps the switch or the tile.
     */
    fun turnOffManually(c: Context): Boolean {
        if (!canWrite(c)) {
            AppLog.add(c, "SYS", "manual off failed: WRITE_SECURE_SETTINGS not granted")
            return false
        }
        Prefs.setUserOff(c, true)
        val ok = removeOurs(c)
        AppLog.add(c, if (ok) "SYS" else "ERR", if (ok) "manual: accessibility service switched off" else "manual off failed")
        return ok
    }

    /** User-initiated only: switches the service back on right now. */
    fun turnOnManually(c: Context): Boolean {
        Prefs.setUserOff(c, false)
        if (!canWrite(c)) {
            AppLog.add(c, "SYS", "manual on: WRITE_SECURE_SETTINGS not granted, asking Shizuku helper instead")
            return RawInput.restoreViaShizuku(c)
        }
        val ok = addOurs(c)
        AppLog.add(c, if (ok) "SYS" else "ERR", if (ok) "manual: accessibility service switched on" else "manual on failed")
        return ok
    }

    // ------------------------------------------------------------------ keep-alive plumbing
    fun startGuard(c: Context) {
        try {
            c.applicationContext.startService(Intent(c.applicationContext, GuardService::class.java))
        } catch (e: Exception) {
            AppLog.add(c, "SYS", "guard service not started: ${e.javaClass.simpleName}")
        }
    }

    /** One-shot, inexact alarm: survives the process being killed. No special permission needed. */
    fun armWake(c: Context, delayMs: Long, n: Int = 0) {
        val app = c.applicationContext
        val pi = PendingIntent.getBroadcast(
            app, 7,
            Intent(app, GuardReceiver::class.java).setAction(ACTION_WAKE).putExtra("n", n),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        runCatching {
            app.getSystemService(AlarmManager::class.java)
                .setAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, SystemClock.elapsedRealtime() + delayMs, pi)
        }
    }

    /** Asks once for the standard battery exemption (also available as a button in the app). */
    fun requestBatteryOnce(act: Activity) {
        val pm = act.getSystemService(PowerManager::class.java)
        if (pm.isIgnoringBatteryOptimizations(act.packageName) || Prefs.battAsked(act)) return
        Prefs.setBattAsked(act, true)
        requestBattery(act)
    }

    fun requestBattery(c: Context) {
        try {
            c.startActivity(
                Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${c.packageName}"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        } catch (e: Exception) {
            AppLog.add(c, "ERR", "battery exemption request failed: ${e.javaClass.simpleName}")
        }
    }
}

/** Started (not foreground) from the UI. Its only job: notice that the task was swiped away and arm a wake-up. */
class GuardService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_NOT_STICKY

    override fun onTaskRemoved(rootIntent: Intent?) {
        AppLog.add(this, "SYS", "task removed (swiped from Recents)")
        if (Prefs.keepAlive(this)) A11yGuard.armWake(this, 2500L, 0)
        super.onTaskRemoved(rootIntent)
    }
}

class GuardReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val why = when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED -> "boot"
            Intent.ACTION_MY_PACKAGE_REPLACED -> "app updated"
            A11yGuard.ACTION_WAKE -> "wake-up alarm"
            else -> return
        }
        val n = intent.getIntExtra("n", 0)
        val pending = goAsync()
        A11yGuard.check(context, why, if (why == "boot") 20_000L else 500L, n) { pending.finish() }
    }
}
