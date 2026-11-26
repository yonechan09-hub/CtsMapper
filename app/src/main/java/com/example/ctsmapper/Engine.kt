package com.example.ctsmapper

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings
import rikka.shizuku.Shizuku

/** Central "should anything be running?" logic. Nothing here polls or holds a wakelock. */
object Engine {
    fun live(ctx: Context): Boolean = Prefs.master(ctx)

    fun a11yEnabled(ctx: Context): Boolean {
        val cn = ComponentName(ctx, TriggerService::class.java)
        val s = Settings.Secure.getString(ctx.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: ""
        return s.split(':').any { it.equals(cn.flattenToString(), true) || it.equals(cn.flattenToShortString(), true) }
    }

    fun rawActionsSet(ctx: Context): Boolean =
        Prefs.getAction(ctx, Trigger.RAW_PRESS) != Action.NONE ||
            Prefs.getAction(ctx, Trigger.RAW_LONG) != Action.NONE

    /** Triggers that need the accessibility key filter. */
    fun keyTriggersConfigured(ctx: Context): Boolean {
        val volume = listOf(
            Trigger.VOL_UP_LONG, Trigger.VOL_DOWN_LONG, Trigger.VOL_UP_DOUBLE, Trigger.VOL_DOWN_DOUBLE
        ).any { Prefs.getAction(ctx, it) != Action.NONE }
        val a11yRaw = Prefs.rawSig(ctx)?.startsWith("a11y:") == true && rawActionsSet(ctx)
        return volume || a11yRaw
    }

    fun anyTriggerConfigured(ctx: Context): Boolean {
        val evdev = Prefs.rawEnabled(ctx) && Prefs.rawSig(ctx)?.startsWith("evdev:") == true && rawActionsSet(ctx)
        return keyTriggersConfigured(ctx) ||
            Prefs.getAction(ctx, Trigger.EDGE_LONG) != Action.NONE || evdev
    }

    /**
     * Plus Key (evdev) listener runs only if: master on, listener enabled, a press/long-press action is assigned
     * and the recorded key is a raw input key. RawInput additionally runs it only while the screen is on.
     */
    fun rawWanted(ctx: Context): Boolean =
        Prefs.master(ctx) && Prefs.rawEnabled(ctx) && rawActionsSet(ctx) &&
            Prefs.rawSig(ctx)?.startsWith("evdev:") == true

    /** Dead-process recovery is wanted only while keep-alive is on and the accessibility service is enabled. */
    fun recoverWanted(ctx: Context): Boolean = Prefs.keepAlive(ctx) && !Prefs.userOff(ctx) && a11yEnabled(ctx)

    fun shizukuRunning(): Boolean = runCatching { Shizuku.pingBinder() }.getOrDefault(false)

    /**
     * The Shizuku helper is kept alive only while a Plus Key mapping is enabled, or Auto-recover is on AND Shizuku is
     * already running. Otherwise nothing is started and no reconnect timer exists (the sticky binder listener in
     * RawInput starts it when Shizuku shows up).
     */
    fun shellWanted(ctx: Context): Boolean = rawWanted(ctx) || (recoverWanted(ctx) && shizukuRunning())

    /** Call after changing a toggle: starts/stops the optional pieces and refreshes the service. */
    fun sync(ctx: Context) {
        val app = ctx.applicationContext
        RawInput.refresh(app)
        Notifs.sync(app)
        TriggerService.refresh()
    }
}

object Notifs {
    private const val CH_STATUS = "status"
    const val ID_STATUS = 1

    private fun ensureChannels(ctx: Context) {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        for ((id, name) in listOf(CH_STATUS to "Status")) {
            val ch = NotificationChannel(id, name, NotificationManager.IMPORTANCE_MIN)
            ch.setShowBadge(false)
            ch.setSound(null, null)
            nm.createNotificationChannel(ch)
        }
    }

    private fun build(ctx: Context, channel: String, text: String): Notification {
        val open = PendingIntent.getActivity(
            ctx, 0, Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return Notification.Builder(ctx, channel)
            .setSmallIcon(R.drawable.ic_stat_mark)
            .setContentTitle("CTS Mapper")
            .setContentText(text)
            .setOngoing(true)
            .setShowWhen(false)
            .setContentIntent(open)
            .build()
    }

    private fun statusText(ctx: Context): String = when {
        !Prefs.master(ctx) -> "Master switch is off"
        TriggerService.instance == null -> "Accessibility service is off"
        !Engine.anyTriggerConfigured(ctx) -> "Idle – no trigger configured"
        else -> "Active"
    }

    fun sync(ctx: Context) {
        val app = ctx.applicationContext
        val nm = app.getSystemService(NotificationManager::class.java)
        if (Prefs.statusNotif(app) && nm.areNotificationsEnabled()) {
            ensureChannels(app)
            nm.notify(ID_STATUS, build(app, CH_STATUS, statusText(app)))
        } else {
            nm.cancel(ID_STATUS)
        }
    }

}

/** Plain-text report that is prepended when you press "Copy" on the log screen. */
object Diag {
    private fun installed(ctx: Context, pkg: String) =
        runCatching { ctx.packageManager.getPackageInfo(pkg, 0); true }.getOrDefault(false)

    fun report(ctx: Context): String {
        val sb = StringBuilder()
        val ver = runCatching { ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName }.getOrNull()
        sb.appendLine("=== CTS Mapper diagnostics (app $ver) ===")
        sb.appendLine("device: ${Build.MANUFACTURER} ${Build.MODEL}, Android ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT})")
        sb.appendLine("build: ${Build.DISPLAY}")
        sb.appendLine("accessibility enabled: ${Engine.a11yEnabled(ctx)}, service connected: ${TriggerService.instance != null}")
        sb.appendLine("WRITE_SECURE_SETTINGS: ${A11yGuard.canWrite(ctx)}, keep-alive: ${Prefs.keepAlive(ctx)}, userOff: ${Prefs.userOff(ctx)}")
        sb.appendLine("master: ${Prefs.master(ctx)}")
        sb.appendLine("default assistant: ${Settings.Secure.getString(ctx.contentResolver, "assistant")}")
        sb.appendLine("voice_interaction_service: ${Settings.Secure.getString(ctx.contentResolver, "voice_interaction_service")}")
        sb.appendLine("google app: ${installed(ctx, Actions.GOOGLE)}, gemini app: ${installed(ctx, Actions.GEMINI)}")
        val pm = ctx.getSystemService(android.os.PowerManager::class.java)
        sb.appendLine("battery optimisation ignored: ${pm.isIgnoringBatteryOptimizations(ctx.packageName)}")
        sb.appendLine("shizuku listener: enabled=${Prefs.rawEnabled(ctx)} status='${RawInput.status}' sig=${Prefs.rawSig(ctx)}")
        Trigger.values().forEach { sb.appendLine("  ${it.name} -> ${Prefs.getAction(ctx, it).name}") }
        sb.appendLine("=== last events (oldest first) ===")
        AppLog.lines(ctx).forEach { sb.appendLine(it) }
        return sb.toString()
    }
}
