package com.example.ctsmapper

import android.content.Context
import android.content.SharedPreferences

enum class Trigger(val label: String) {
    VOL_UP_LONG("Volume Up – long press"),
    VOL_DOWN_LONG("Volume Down – long press"),
    VOL_UP_DOUBLE("Volume Up – double press"),
    VOL_DOWN_DOUBLE("Volume Down – double press"),
    EDGE_LONG("Bottom edge strip – long press (custom gesture)"),
    RAW_PRESS("Recorded key – press"),
    RAW_LONG("Recorded key – long press");

    val isRaw get() = name.startsWith("RAW")
}

enum class Action(val label: String) {
    NONE("Nothing"),
    CTS("Circle to Search"),
    GEMINI_OVERLAY("Gemini overlay (\"Ask Gemini\" pill, voice-interaction call)"),
    GEMINI_ASSIST("Gemini via ASSIST intent (opens the Google app)"),
    GEMINI_APP("Gemini app (full screen)")
}

object Prefs {
    fun sp(c: Context): SharedPreferences =
        c.applicationContext.getSharedPreferences("cfg", Context.MODE_PRIVATE)

    fun getAction(c: Context, t: Trigger): Action =
        runCatching { Action.valueOf(sp(c).getString("act_${t.name}", Action.NONE.name)!!) }
            .getOrDefault(Action.NONE)

    fun setAction(c: Context, t: Trigger, a: Action) =
        sp(c).edit().putString("act_${t.name}", a.name).apply()

    fun edgeHeightDp(c: Context): Int = sp(c).getInt("edge_h", 24)
    fun setEdgeHeightDp(c: Context, v: Int) = sp(c).edit().putInt("edge_h", v).apply()
    fun edgeDebug(c: Context): Boolean = sp(c).getBoolean("edge_debug", false)
    fun setEdgeDebug(c: Context, v: Boolean) = sp(c).edit().putBoolean("edge_debug", v).apply()

    // ---- Gemini overlay: which argument set to send (1..3), for trial and error
    fun overlayVariant(c: Context): Int = sp(c).getInt("overlay_variant", 1)
    fun setOverlayVariant(c: Context, v: Int) = sp(c).edit().putInt("overlay_variant", v).apply()

    // ---- engine / battery
    fun master(c: Context): Boolean = sp(c).getBoolean("master", true)
    fun setMaster(c: Context, v: Boolean) = sp(c).edit().putBoolean("master", v).apply()
    fun statusNotif(c: Context): Boolean = sp(c).getBoolean("status_notif", false)
    fun setStatusNotif(c: Context, v: Boolean) = sp(c).edit().putBoolean("status_notif", v).apply()
    /** Re-enable / rebind the accessibility service when it is dead ("Not working"). */
    fun keepAlive(c: Context): Boolean = sp(c).getBoolean("keep_alive", true)
    fun setKeepAlive(c: Context, v: Boolean) = sp(c).edit().putBoolean("keep_alive", v).apply()
    /** Set when the user switched the service off themselves; auto-recover then stays quiet. */
    fun userOff(c: Context): Boolean = sp(c).getBoolean("user_off", false)
    fun setUserOff(c: Context, v: Boolean) { if (userOff(c) != v) sp(c).edit().putBoolean("user_off", v).apply() }
    fun battAsked(c: Context): Boolean = sp(c).getBoolean("batt_asked", false)
    fun setBattAsked(c: Context, v: Boolean) = sp(c).edit().putBoolean("batt_asked", v).apply()
    fun guideDone(c: Context, id: String): Boolean = sp(c).getBoolean("guide_$id", false)
    fun setGuideDone(c: Context, id: String, v: Boolean) = sp(c).edit().putBoolean("guide_$id", v).apply()

    // ---- recorded key: "evdev:<device name>|<type>|<code>"  or  "a11y:<keyCode>"
    fun rawSig(c: Context): String? = sp(c).getString("raw_sig", null)
    fun setRawSig(c: Context, v: String?) = sp(c).edit().putString("raw_sig", v).apply()
    fun rawEnabled(c: Context): Boolean = sp(c).getBoolean("raw_enabled", false)
    fun setRawEnabled(c: Context, v: Boolean) = sp(c).edit().putBoolean("raw_enabled", v).apply()
}
