package com.example.ctsmapper

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.IBinder
import org.lsposed.hiddenapibypass.HiddenApiBypass
import java.lang.reflect.InvocationTargetException

/**
 * Circle to Search trigger, "VIS" route (the only no-root route in MiCTS/VISTrigger).
 *
 * It asks the system VoiceInteractionManagerService to show the session of the *current default
 * assistant* (must be the Google app) with the Omni/CTS entry-point extras. That is why Google must
 * be the default assistant – and why this is NOT the same as launching Gemini.
 *
 * NOTE: reconstructed from the public descriptions of MiCTS / OppoCTS (flags=7, omni.entry_point=1,
 * attribution tag of a CTS-capable caller). I could not read their source. If it fails on your
 * ROM, compare the Bundle/flags/tag with MiCTS' VIS trigger code. The fallback below simply
 * launches MiCTS / VISTrigger if you have one installed (launching them triggers CTS).
 */
object CtsTrigger {
    private const val FLAGS = 7
    private const val ATTRIBUTION_TAG = "hyperOS_home"
    private val FALLBACK_APPS = listOf(
        "com.parallelc.vistrigger", "com.parallelc.micts",
        "dev.parallel.vistrigger", "dev.parallel.micts"
    )

    /** @return null on success, otherwise a human-readable error. */
    fun trigger(ctx: Context): String? {
        val vis = runCatching { viaVis(Bundle().apply { putInt("omni.entry_point", 1) }, FLAGS) }
        if (vis.getOrNull() == true) {
            AppLog.add(ctx, "ACT", "CTS: VIS call accepted")
            return null
        }

        val cause = vis.exceptionOrNull()?.let {
            val c = (it as? InvocationTargetException)?.cause ?: it
            "${c.javaClass.simpleName}: ${c.message}"
        } ?: "VIS call returned false"

        AppLog.add(ctx, "ERR", "CTS VIS failed: $cause")
        for (pkg in FALLBACK_APPS) {
            val i = ctx.packageManager.getLaunchIntentForPackage(pkg) ?: continue
            return try {
                AppLog.add(ctx, "ACT", "CTS: launching fallback app $pkg")
                ctx.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); null
            } catch (e: Exception) { "Fallback $pkg failed: ${e.message}" }
        }
        return "CTS failed ($cause). Set Google as default assistant."
    }

    // Session-show flags (VoiceInteractionSession): SHOW_WITH_ASSIST = 1, SHOW_WITH_SCREENSHOT = 2
    /**
     * Gemini "Ask Gemini" overlay: the same system call as CTS, but WITHOUT the omni (CTS) entry point,
     * so the Google app shows its normal assistant session. The exact arguments are my best guess
     * (invocation_type values come from SystemUI's AssistManager: 1 gesture, 5 home long-press,
     * 6 power-button long-press). Three variants are provided; try them in order and check the log.
     */
    fun overlay(ctx: Context): String? {
        val variant = Prefs.overlayVariant(ctx)
        val (type, flags) = when (variant) {
            2 -> 1 to 3
            3 -> 5 to 1
            else -> 6 to 3
        }
        val r = runCatching { viaVis(Bundle().apply { putInt("invocation_type", type) }, flags) }
        if (r.getOrNull() == true) {
            AppLog.add(ctx, "ACT", "overlay variant $variant (type=$type flags=$flags): VIS call accepted")
            return null
        }
        val cause = r.exceptionOrNull()?.let {
            val c = (it as? InvocationTargetException)?.cause ?: it
            "${c.javaClass.simpleName}: ${c.message}"
        } ?: "VIS call returned false"
        return "Gemini overlay variant $variant failed ($cause). Try another variant."
    }

    private fun viaVis(extras: Bundle, flags: Int): Boolean {
        HiddenApiBypass.addHiddenApiExemptions("L")

        val sm = Class.forName("android.os.ServiceManager")
        val binder = sm.getMethod("getService", String::class.java)
            .invoke(null, "voiceinteraction") as? IBinder
            ?: error("voiceinteraction service not found")

        val iface = Class.forName("com.android.internal.app.IVoiceInteractionManagerService")
        val stub = Class.forName("com.android.internal.app.IVoiceInteractionManagerService\$Stub")
        val svc = stub.getMethod("asInterface", IBinder::class.java).invoke(null, binder)
            ?: error("asInterface returned null")

        // Signature differs between Android versions (3 or 4 params) -> map by type.
        val m = iface.methods.firstOrNull { it.name == "showSessionFromSession" }
            ?: error("showSessionFromSession not present on this Android version")

        var intUsed = false
        val args = m.parameterTypes.map { t ->
            when (t) {
                IBinder::class.java -> null
                Bundle::class.java -> extras
                Int::class.javaPrimitiveType -> if (!intUsed) { intUsed = true; flags } else 0
                String::class.java -> ATTRIBUTION_TAG
                else -> null
            }
        }.toTypedArray()

        return (m.invoke(svc, *args) as? Boolean) ?: true
    }
}
