package com.example.ctsmapper

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.database.ContentObserver
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AppLog.installCrashHandler(this)
        RawInput.install(this)
        Engine.sync(this)
        A11yGuard.startGuard(this)
        A11yGuard.check(this, "app opened", 3000L)
        A11yGuard.requestBatteryOnce(this)
        setContent { CtTheme { Root() } }
    }

    override fun onStop() {
        AppLog.flush()
        super.onStop()
    }
}

@Composable
fun Root() {
    var screen by rememberSaveable { mutableStateOf("main") }
    BackHandler(enabled = screen != "main") { screen = "main" }
    val topLevel = screen == "main" || screen == "log" || screen == "guide"
    Column(Modifier.fillMaxSize().background(Ct.Bg)) {
        Box(Modifier.weight(1f).fillMaxWidth()) {
            when (screen) {
                "record" -> RecordScreen { screen = "main" }
                "guide" -> GuideScreen()
                "log" -> LogScreen()
                else -> MainScreen { screen = it }
            }
        }
        if (topLevel) CtBottomNav(screen) { screen = it }
    }
}

// ------------------------------------------------------------------ helpers
@Composable
fun rememberResumeTick(): Int {
    val act = LocalContext.current as ComponentActivity
    var tick by remember { mutableIntStateOf(0) }
    DisposableEffect(Unit) {
        val o = LifecycleEventObserver { _, e -> if (e == Lifecycle.Event.ON_RESUME) tick++ }
        act.lifecycle.addObserver(o)
        onDispose { act.lifecycle.removeObserver(o) }
    }
    return tick
}

/** Accessibility-service-enabled state that updates live: ContentObserver on the setting + refresh on resume. */
@Composable
fun rememberA11yEnabled(): Boolean {
    val ctx = LocalContext.current
    val tick = rememberResumeTick()
    var on by remember { mutableStateOf(Engine.a11yEnabled(ctx)) }
    LaunchedEffect(tick) { on = Engine.a11yEnabled(ctx) }
    DisposableEffect(Unit) {
        val obs = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) { on = Engine.a11yEnabled(ctx) }
        }
        val cr = ctx.contentResolver
        cr.registerContentObserver(Settings.Secure.getUriFor(Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES), false, obs)
        cr.registerContentObserver(Settings.Secure.getUriFor(Settings.Secure.ACCESSIBILITY_ENABLED), false, obs)
        on = Engine.a11yEnabled(ctx)
        onDispose { cr.unregisterContentObserver(obs) }
    }
    return on
}

fun openSettings(ctx: Context, i: Intent) {
    try {
        ctx.startActivity(i)
    } catch (e: Exception) {
        AppLog.add(ctx, "ERR", "open settings failed: ${e.javaClass.simpleName}")
        Toast.makeText(ctx, "Can't open that screen on this ROM – open it manually.", Toast.LENGTH_LONG).show()
    }
}

/** Leave our app first, otherwise CTS would capture this screen. */
private fun test(act: Activity, a: Action) {
    if (a == Action.NONE) return
    act.moveTaskToBack(true)
    Handler(Looper.getMainLooper()).postDelayed({ Actions.run(act.applicationContext, a) }, 1200)
}

private val PAGE_PADDING = 24.dp

// ------------------------------------------------------------------ main
@Composable
fun MainScreen(go: (String) -> Unit) {
    val ctx = LocalContext.current
    val act = ctx as ComponentActivity
    val tick = rememberResumeTick()
    val a11y = rememberA11yEnabled()
    val assistant = remember(tick) { Settings.Secure.getString(ctx.contentResolver, "assistant") ?: "(none)" }
    val googleIsAssistant = assistant.startsWith(Actions.GOOGLE)

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState())
            .padding(horizontal = PAGE_PADDING, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Header()

        MasterRow(a11y)
        NotifCard()

        SectionLabel("Service")
        CtCard {
            SwitchRow(
                title = if (a11y) "Accessibility service is on" else "Accessibility service is off",
                checked = a11y,
                tone = if (a11y) StatusTone.Ok else StatusTone.Bad,
                onChange = { if (it) A11yGuard.turnOnManually(ctx) else A11yGuard.turnOffManually(ctx) }
            )
            CtText(
                "Use this switch (or the Quick Settings tile) to turn the service off yourself before opening " +
                    "an app that won't run with one enabled, and back on when you're done."
            )
            if (googleIsAssistant) StatusLine(StatusTone.Ok, "Default assistant: Google")
            else StatusLine(StatusTone.Warn, "Default assistant: $assistant\nCircle to Search needs Google here.")
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                CtButton("Accessibility", { openSettings(ctx, Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }, Modifier.weight(1f))
                CtButton("Assistant", { openSettings(ctx, Intent(Settings.ACTION_VOICE_INPUT_SETTINGS)) }, Modifier.weight(1f))
            }
        }

        ReliabilityCard()

        SectionLabel("Shortcuts")
        Trigger.values().filter { !it.isRaw }.forEach { t ->
            CtCard { TriggerRow(t, act) }
        }

        OverlayVariantCard()
        EdgeCard()

        SectionLabel("Recorded key")
        RawCard(act, go)
    }
}

@Composable
private fun Header() {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Image(
            painter = painterResource(R.drawable.ct_mark),
            contentDescription = null,
            modifier = Modifier.size(width = 44.dp, height = 48.dp)
        )
        Spacer(Modifier.width(12.dp))
        ScreenTitle("CtsMapper", "Map any key to Circle to Search")
    }
}

/** Status pill + master switch on one row. The pill is static text derived from state that already exists. */
@Composable
fun MasterRow(a11y: Boolean) {
    val ctx = LocalContext.current
    var master by remember { mutableStateOf(Prefs.master(ctx)) }
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        when {
            !master -> StatusPill("Off", Ct.Ink2, Ct.Raised)
            !a11y -> StatusPill("Service off", Ct.Warn, Ct.WarnDim)
            else -> StatusPill("Active", Ct.Teal, Ct.TealDim)
        }
        Spacer(Modifier.weight(1f))
        CtText("Master", size = 17, weight = FontWeight.Medium, color = Ct.Ink2)
        Spacer(Modifier.width(12.dp))
        CtSwitch(master) {
            master = it
            Prefs.setMaster(ctx, it)
            AppLog.add(ctx, "SYS", "master switch ${if (it) "ON" else "OFF"}")
            Engine.sync(ctx)
        }
    }
}

@Composable
fun NotifCard() {
    val ctx = LocalContext.current
    var notif by remember { mutableStateOf(Prefs.statusNotif(ctx)) }
    val permLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        Notifs.sync(ctx)
    }
    CtCard {
        SwitchRow(title = "Persistent status notification", checked = notif, onChange = {
            notif = it
            Prefs.setStatusNotif(ctx, it)
            val needPerm = Build.VERSION.SDK_INT >= 33 &&
                ctx.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
            if (it && needPerm) permLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            else Notifs.sync(ctx)
        })
    }
}

private fun glyph(t: Trigger): String = when (t) {
    Trigger.VOL_UP_LONG, Trigger.VOL_UP_DOUBLE -> "+"
    Trigger.VOL_DOWN_LONG, Trigger.VOL_DOWN_DOUBLE -> "\u2212"
    Trigger.EDGE_LONG -> "~"
    Trigger.RAW_PRESS, Trigger.RAW_LONG -> "R"
}

@Composable
fun TriggerRow(t: Trigger, act: Activity) {
    val ctx = LocalContext.current
    var cur by remember { mutableStateOf(Prefs.getAction(ctx, t)) }
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        IconChip(glyph(t))
        Column(Modifier.weight(1f)) {
            CardTitle(t.label)
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ActionPicker(cur, Modifier.weight(1f)) { cur = it; Prefs.setAction(ctx, t, it); Engine.sync(ctx) }
                CtButton("Test", { test(act, cur) }, enabled = cur != Action.NONE)
            }
        }
    }
}

@Composable
fun OverlayVariantCard() {
    val ctx = LocalContext.current
    var v by remember { mutableIntStateOf(Prefs.overlayVariant(ctx)) }
    CtCard {
        CardTitle("Gemini overlay variant")
        CtText(
            "If the \"Ask Gemini\" pill doesn't appear, press Test on a trigger set to the overlay action, " +
                "then switch variant and test again. Tell me which one works (or paste the log).",
            size = 14
        )
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            for (n in 1..3) {
                val pick = { v = n; Prefs.setOverlayVariant(ctx, n) }
                CtButton(
                    "$n", pick, Modifier.weight(1f),
                    kind = if (v == n) BtnKind.Primary else BtnKind.Secondary
                )
            }
        }
    }
}

@Composable
fun EdgeCard() {
    val ctx = LocalContext.current
    var h by remember { mutableFloatStateOf(Prefs.edgeHeightDp(ctx).toFloat()) }
    var dbg by remember { mutableStateOf(Prefs.edgeDebug(ctx)) }
    CtCard {
        CtText("Bottom-edge strip: ${h.toInt()} dp (centre half of the screen)", size = 17, weight = FontWeight.Medium, color = Ct.Ink)
        Slider(
            value = h, onValueChange = { h = it },
            onValueChangeFinished = { Prefs.setEdgeHeightDp(ctx, h.toInt()) },
            valueRange = 8f..64f,
            colors = SliderDefaults.colors(
                thumbColor = Ct.Teal,
                activeTrackColor = Ct.Teal,
                inactiveTrackColor = Ct.Hairline
            )
        )
        SwitchRow("Show strip in red (debug)", dbg, { dbg = it; Prefs.setEdgeDebug(ctx, it) })
    }
}

@Composable
fun ReliabilityCard() {
    val ctx = LocalContext.current
    val tick = rememberResumeTick()
    val grantVer = A11yGuard.grantVersion
    val canWrite = remember(tick, grantVer) { A11yGuard.canWrite(ctx) }
    val batt = remember(tick) { ctx.getSystemService(android.os.PowerManager::class.java).isIgnoringBatteryOptimizations(ctx.packageName) }
    var keep by remember { mutableStateOf(Prefs.keepAlive(ctx)) }
    CtCard {
        CardTitle("Keep-alive / recovery")
        StatusLine(
            if (canWrite) StatusTone.Ok else StatusTone.Bad,
            if (canWrite) "WRITE_SECURE_SETTINGS granted" else "WRITE_SECURE_SETTINGS not granted"
        )
        StatusLine(
            if (batt) StatusTone.Ok else StatusTone.Bad,
            if (batt) "Battery exemption granted" else "Battery exemption not granted"
        )
        SwitchRow("Auto-recover the accessibility service (\"Not working\")", keep, {
            keep = it
            Prefs.setKeepAlive(ctx, it)
            Engine.sync(ctx)
        })
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            CtButton("Grant via Shizuku", { RawInput.grantSecure(ctx) }, Modifier.weight(1f), enabled = !canWrite)
            CtButton("Battery exemption", { A11yGuard.requestBattery(ctx) }, Modifier.weight(1f), enabled = !batt)
        }
        CtButton("Restore accessibility service now", {
            Toast.makeText(ctx, A11yGuard.restoreNow(ctx), Toast.LENGTH_LONG).show()
        }, Modifier.fillMaxWidth())
        if (!canWrite) {
            CtText(
                "Or once from a PC: adb shell pm grant ${ctx.packageName} android.permission.WRITE_SECURE_SETTINGS",
                size = 14
            )
        }
    }
}

@Composable
fun RawCard(act: Activity, go: (String) -> Unit) {
    val ctx = LocalContext.current
    var enabled by remember { mutableStateOf(Prefs.rawEnabled(ctx)) }
    CtCard {
        CardTitle("Recorded key (Plus Key / side button)")
        CtText("Recorded: ${Prefs.rawSig(ctx) ?: "none – press Record"}", size = 14)
        CtText(RawInput.status, size = 14)
        SwitchRow("Shizuku listener (raw input)", enabled, {
            enabled = it
            Prefs.setRawEnabled(ctx, it)
            Engine.sync(ctx)
        })
        CtButton("Record trigger", { go("record") }, Modifier.fillMaxWidth(), kind = BtnKind.Primary)
        CtDivider()
        TriggerRow(Trigger.RAW_PRESS, act)
        CtDivider()
        TriggerRow(Trigger.RAW_LONG, act)
        CtText(
            "This only observes the key. ColorOS' own Plus Key action still fires unless you set it to nothing in system settings.",
            size = 14
        )
    }
}

@Composable
fun ActionPicker(current: Action, modifier: Modifier = Modifier, onPick: (Action) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box(modifier) {
        Row(
            Modifier.clip(RoundedCornerShape(10.dp)).clickable { open = true }.padding(vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            CtText(
                current.label,
                modifier = Modifier.weight(1f, fill = false),
                size = 15, weight = FontWeight.Medium,
                color = if (current != Action.NONE) Ct.Teal else Ct.Ink2
            )
            CtText(" \u25BE", size = 15, color = Ct.Ink2)
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            Action.values().forEach {
                DropdownMenuItem(
                    text = { CtText(it.label, size = 15, color = Ct.Ink) },
                    onClick = { onPick(it); open = false }
                )
            }
        }
    }
}

// ------------------------------------------------------------------ record trigger
@Composable
fun RecordScreen(back: () -> Unit) {
    val ctx = LocalContext.current
    LaunchedEffect(Unit) { RawInput.beginRecord(ctx) }
    DisposableEffect(Unit) {
        onDispose {
            RawInput.endRecord()
        }
    }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState())
            .padding(horizontal = PAGE_PADDING, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        ScreenTitle("Record trigger", "Press the physical button (Plus Key) now. Volume and power are ignored.")
        CtText(RawInput.status, size = 14)

        val cap = RawInput.captured
        CtCard {
            if (cap != null) StatusLine(StatusTone.Ok, "Captured: $cap")
            else StatusLine(StatusTone.Warn, "Waiting for a key…")
        }

        if (RawInput.lastA11yKey != null) {
            CtButton(
                "Use the accessibility key instead (no Shizuku needed)",
                { RawInput.useA11yKey(ctx) },
                Modifier.fillMaxWidth()
            )
        }

        SectionLabel("Live events")
        CtCard {
            if (RawInput.log.isEmpty()) {
                CtText(
                    "Nothing yet. If this stays empty after pressing: Shizuku isn't running / permitted, " +
                        "and the system doesn't hand this key to accessibility services either.",
                    size = 14
                )
            }
            RawInput.log.takeLast(25).forEach { CtText(it, size = 13, family = CtMono) }
        }

        CtButton("Done", back, Modifier.fillMaxWidth(), kind = BtnKind.Primary, big = true)
    }
}
