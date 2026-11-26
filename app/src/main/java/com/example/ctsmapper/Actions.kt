package com.example.ctsmapper

import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.widget.Toast

object Actions {
    const val GOOGLE = "com.google.android.googlequicksearchbox"
    const val GEMINI = "com.google.android.apps.bard"
    private val main = Handler(Looper.getMainLooper())

    fun run(ctx: Context, a: Action) {
        val app = ctx.applicationContext
        if (a == Action.NONE) return
        AppLog.add(app, "ACT", "run ${a.name}")
        val err = when (a) {
            Action.NONE -> null
            Action.CTS -> CtsTrigger.trigger(app)
            Action.GEMINI_OVERLAY -> CtsTrigger.overlay(app)
            Action.GEMINI_ASSIST -> geminiAssist(app)
            Action.GEMINI_APP -> launchPackage(app, GEMINI)
        }
        if (err != null) {
            AppLog.add(app, "ERR", "${a.name}: $err")
            main.post { Toast.makeText(app, err, Toast.LENGTH_LONG).show() }
        } else {
            AppLog.add(app, "ACT", "${a.name} dispatched (no error from the system call)")
        }
    }

    /** Explicit package => resolves inside Google app, never the system default (Breeno). */
    private fun geminiAssist(ctx: Context): String? {
        val i = Intent(Intent.ACTION_ASSIST).setPackage(GOOGLE).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return try {
            ctx.startActivity(i); null
        } catch (e: Exception) {
            AppLog.add(ctx, "ERR", "ASSIST intent failed (${e.javaClass.simpleName}); trying Gemini app")
            launchPackage(ctx, GEMINI)
        }
    }

    private fun launchPackage(ctx: Context, pkg: String): String? {
        val i = ctx.packageManager.getLaunchIntentForPackage(pkg)
            ?: return "$pkg is not installed / not visible"
        return try {
            ctx.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); null
        } catch (e: Exception) {
            "Launch $pkg failed: ${e.message}"
        }
    }
}
