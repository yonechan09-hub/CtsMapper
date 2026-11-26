package com.example.ctsmapper

import android.app.Activity
import android.os.Bundle
import android.os.Handler
import android.os.Looper

/** Invisible launcher for actions: ctsmapper://run/CTS | GEMINI_OVERLAY | GEMINI_ASSIST | GEMINI_APP (or extra "action"). */
class RunActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val name = intent.getStringExtra("action") ?: intent.data?.lastPathSegment
        val a = runCatching { Action.valueOf(name!!) }.getOrNull()
        val app = applicationContext
        AppLog.add(app, "TRIG", "RunActivity action=$name")
        // finish first so CTS captures what was underneath, then fire.
        Handler(Looper.getMainLooper()).postDelayed({
            if (a != null && a != Action.NONE) Actions.run(app, a)
        }, 200)
        finish()
    }
}
