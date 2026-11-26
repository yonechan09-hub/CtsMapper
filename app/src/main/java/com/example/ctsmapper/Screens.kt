package com.example.ctsmapper

import android.content.ClipData
import android.content.ClipboardManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.app.NotificationManager
import android.os.PowerManager
import android.provider.Settings
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

// ------------------------------------------------------------------ setup guide
// Component names are from memory of older ColorOS builds and may not exist on yours.
private val AUTOSTART_TARGETS = listOf(
    "com.coloros.safecenter" to "com.coloros.safecenter.permission.startup.StartupAppListActivity",
    "com.coloros.safecenter" to "com.coloros.safecenter.startupapp.StartupAppListActivity",
    "com.oplus.battery" to "com.oplus.startupapp.view.StartupAppListActivity",
    "com.oppo.safe" to "com.oppo.safe.permission.startup.StartupAppListActivity"
)

private fun appInfo(pkg: String) =
    Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$pkg"))

private fun openAutostart(ctx: Context) {
    for ((pkg, cls) in AUTOSTART_TARGETS) {
        try {
            ctx.startActivity(Intent().setComponent(ComponentName(pkg, cls)))
            AppLog.add(ctx, "SYS", "autostart screen opened: $pkg")
            return
        } catch (_: Exception) { /* try next */ }
    }
    AppLog.add(ctx, "ERR", "no known autostart screen on this ROM; opened app info instead")
    Toast.makeText(ctx, "No autostart screen found – use App info → Auto-launch.", Toast.LENGTH_LONG).show()
    openSettings(ctx, appInfo(ctx.packageName))
}

/** Done = verified (or ticked by you), Open = not done, Unverified = the app cannot read this setting. */
private enum class StepState { Done, Open, Unverified }

@Composable
private fun StepMark(state: StepState) {
    val size = 28.dp
    when (state) {
        StepState.Done -> Box(
            Modifier.size(size).clip(CircleShape).background(Ct.Teal),
            contentAlignment = Alignment.Center
        ) { CtText("\u2713", size = 16, weight = FontWeight.Bold, color = Ct.Bg) }

        StepState.Unverified -> Box(
            Modifier.size(size).clip(RoundedCornerShape(8.dp)).background(Ct.WarnDim)
                .border(2.dp, Ct.Warn, RoundedCornerShape(8.dp)),
            contentAlignment = Alignment.Center
        ) { CtText("?", size = 16, weight = FontWeight.Bold, color = Ct.Warn) }

        StepState.Open -> Box(Modifier.size(size).border(2.dp, Ct.Ink2, CircleShape))
    }
}

@Composable
private fun StepCard(title: String, state: StepState, body: String, content: @Composable ColumnScope.() -> Unit) {
    CtCard {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            StepMark(state)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                CardTitle(title)
                CtText(body, size = 14)
                content()
            }
        }
    }
}

/** A step the app can't verify: it takes your word. Tap again to undo. */
@Composable
private fun ManualCheck(done: Boolean, onToggle: () -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        CtText(
            if (done) "Marked by you" else "Can't be checked automatically",
            modifier = Modifier.weight(1f), size = 14,
            color = if (done) Ct.Teal else Ct.Warn
        )
        CtButton(if (done) "Undo" else "I did this", onToggle)
    }
}

@Composable
fun GuideScreen() {
    val ctx = LocalContext.current
    val tick = rememberResumeTick()
    val battOk = remember(tick) {
        ctx.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(ctx.packageName)
    }
    val a11y = rememberA11yEnabled()
    val notifOk = remember(tick) { ctx.getSystemService(NotificationManager::class.java).areNotificationsEnabled() }

    // Manual ticks live in Prefs; mTick just makes this screen re-read them after a tap.
    var mTick by remember { mutableIntStateOf(0) }
    val battManual = remember(mTick) { Prefs.guideDone(ctx, "battery") }
    val autoManual = remember(mTick) { Prefs.guideDone(ctx, "autostart") }
    val lockManual = remember(mTick) { Prefs.guideDone(ctx, "lock") }
    val googleManual = remember(mTick) { Prefs.guideDone(ctx, "google") }
    fun toggle(id: String, now: Boolean) { Prefs.setGuideDone(ctx, id, !now); mTick++ }

    val grantVer = A11yGuard.grantVersion
    val canWrite = remember(tick, grantVer) { A11yGuard.canWrite(ctx) }
    val doneCount = listOf(a11y, canWrite, battOk, autoManual, lockManual, googleManual, notifOk).count { it }
    val total = 7

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        ScreenTitle(
            "Setup guide",
            "The app uses no polling and no wakelocks. These settings only stop ColorOS from killing or freezing it."
        )

        // progress: static bar + count
        Box(Modifier.fillMaxWidth().height(8.dp).clip(CircleShape).background(Ct.Raised)) {
            Box(Modifier.fillMaxWidth(doneCount / total.toFloat()).fillMaxHeight().background(Ct.Teal))
        }
        CtText(
            "$doneCount of $total done",
            modifier = Modifier.fillMaxWidth(),
            size = 15, weight = FontWeight.SemiBold, color = Ct.Teal,
            align = androidx.compose.ui.text.style.TextAlign.End
        )

        StepCard(
            "Accessibility service", if (a11y) StepState.Done else StepState.Open,
            "Turn on \"CTS Mapper triggers\". If the switch is greyed out: App info → ⋮ menu → Allow restricted settings, then try again."
        ) {
            CtButton("Open accessibility settings", { openSettings(ctx, Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) })
            CtButton("Open App info", { openSettings(ctx, appInfo(ctx.packageName)) })
        }

        StepCard(
            "Switch the service from the app", if (canWrite) StepState.Done else StepState.Open,
            "Start Shizuku and tap \"Grant via Shizuku\" on the main screen (one time). " +
                "This lets the Quick Settings tile and auto-recover switch the accessibility service."
        ) {
            CtButton("Grant via Shizuku", { RawInput.grantSecure(ctx) }, enabled = !canWrite)
            CtText("Or: adb shell pm grant ${ctx.packageName} android.permission.WRITE_SECURE_SETTINGS", size = 14)
        }

        StepCard(
            "Battery: Unrestricted", if (battOk) StepState.Done else StepState.Open,
            "Tap \"Request exemption\" and allow. Then open App info → Battery (or Battery usage) and choose Unrestricted / " +
                "Allow background activity. The tick only reflects the standard Android exemption; ColorOS' own switch can't be read."
        ) {
            CtButton("Request exemption", {
                openSettings(ctx, Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${ctx.packageName}")))
            })
            CtButton("Open App info", { openSettings(ctx, appInfo(ctx.packageName)) })
            ManualCheck(battManual) { toggle("battery", battManual) }
        }

        StepCard(
            "Autostart / Auto-launch", if (autoManual) StepState.Done else StepState.Unverified,
            "Turn on Auto-launch (自启动) for this app. On newer ColorOS it is inside App info (Battery or Permissions). " +
                "The button tries the known older screens and falls back to App info."
        ) {
            CtButton("Open autostart screen", { openAutostart(ctx) })
            ManualCheck(autoManual) { toggle("autostart", autoManual) }
        }

        StepCard(
            "Lock in recents", if (lockManual) StepState.Done else StepState.Unverified,
            "Open the recents screen, then long-press this app's card (or tap its ⋮ / lock icon) and choose Lock. " +
                "A locked card is skipped by \"clear all\" and by background clean-up. Do the same for Google and Gemini."
        ) {
            ManualCheck(lockManual) { toggle("lock", lockManual) }
        }

        StepCard(
            "Google / Gemini apps", if (googleManual) StepState.Done else StepState.Unverified,
            "Circle to Search runs inside the Google app, so it needs the same battery, autostart and recents-lock settings."
        ) {
            CtButton("Google app – App info", { openSettings(ctx, appInfo(Actions.GOOGLE)) })
            CtButton("Gemini app – App info", { openSettings(ctx, appInfo(Actions.GEMINI)) })
            ManualCheck(googleManual) { toggle("google", googleManual) }
        }

        StepCard(
            "Notifications (optional)", if (notifOk) StepState.Done else StepState.Open,
            "Only needed for the optional status notification. It is off by default."
        ) {
            CtButton("Notification settings", {
                openSettings(ctx, Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, ctx.packageName))
            })
        }

        CtCard {
            CtText(
                "ColorOS steps use your word, not a guess: steps with a \"?\" are things the app cannot verify.",
                size = 14
            )
        }
    }
}

// ------------------------------------------------------------------ log screen
private data class LogTag(val label: String, val fg: Color, val bg: Color)

private class LogEntry(val time: String, val tag: LogTag, val msg: String)

/** Lines look like "MM-dd HH:mm:ss.SSS KIND msg" (see AppLog); anything else is shown as plain neutral text. */
private fun parseLog(line: String): LogEntry {
    if (line.length < 24) return LogEntry("", LogTag("", Ct.Ink2, Ct.Raised), line)
    val time = line.substring(6, 14)
    val kind = line.substring(19, 23).trim()
    val msg = line.substring(24)
    val tag = when {
        kind == "TRIG" -> LogTag("Trigger", Ct.Teal, Ct.TealDim)
        kind == "ACT" -> LogTag("Action", Ct.Peri, Ct.PeriDim)
        kind == "ERR" -> LogTag("Error", Ct.Danger, Ct.DangerDim)
        kind == "CRSH" -> LogTag("Crash", Ct.Danger, Ct.DangerDim)
        kind == "SYS" -> LogTag("System", Ct.Ink2, Ct.Raised)
        kind == "KEY" -> LogTag("Key", Ct.Teal, Ct.TealDim)
        kind == "HELP" -> LogTag("Helper", Ct.Peri, Ct.PeriDim)
        else -> LogTag(kind, Ct.Ink2, Ct.Raised)
    }
    return LogEntry(time, tag, msg)
}

@Composable
fun LogScreen() {
    val ctx = LocalContext.current
    val ver = AppLog.version // read so the list refreshes when a line is added
    val lines = remember(ver) { AppLog.lines(ctx) }
    val entries = remember(lines) { lines.asReversed().map { parseLog(it) } }

    Column(
        Modifier.fillMaxSize().padding(horizontal = 24.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                ScreenTitle("Log", "Last ${lines.size} of 150 events, newest first")
            }
            Spacer(Modifier.width(12.dp))
            CtButton("Copy for support", {
                val cm = ctx.getSystemService(ClipboardManager::class.java)
                cm.setPrimaryClip(ClipData.newPlainText("CTS Mapper log", Diag.report(ctx)))
                Toast.makeText(ctx, "Copied log + diagnostics", Toast.LENGTH_SHORT).show()
            })
        }

        if (entries.isEmpty()) {
            CtCard { CtText("No events yet.") }
        } else {
            LazyColumn(
                Modifier.weight(1f).fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Ct.Card),
                contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp)
            ) {
                itemsIndexed(entries) { i, e ->
                    Column(Modifier.fillMaxWidth()) {
                        if (i > 0) CtDivider()
                        Column(Modifier.padding(vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                CtText(e.time, size = 14, color = Ct.Ink2, family = CtMono)
                                if (e.tag.label.isNotEmpty()) {
                                    Box(Modifier.clip(CircleShape).background(e.tag.bg).padding(horizontal = 10.dp, vertical = 3.dp)) {
                                        CtText(e.tag.label, size = 13, weight = FontWeight.SemiBold, color = e.tag.fg)
                                    }
                                }
                            }
                            CtText(e.msg, size = 15, color = Ct.Ink)
                        }
                    }
                }
            }
        }

        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            CtText("Copy adds a device/config header.", modifier = Modifier.weight(1f), size = 14)
            CtButton("Clear", { AppLog.clear(ctx) })
        }
    }
}
