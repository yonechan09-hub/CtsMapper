package com.example.ctsmapper

import android.service.quicksettings.Tile
import android.service.quicksettings.TileService

/**
 * Quick Settings tile for a one-tap, user-initiated toggle of the accessibility service.
 * Purely manual: it only acts when the person taps the tile, never on its own and never because
 * of another app opening or closing.
 */
class A11yTile : TileService() {
    override fun onStartListening() {
        super.onStartListening()
        refresh()
    }

    override fun onClick() {
        super.onClick()
        val ctx = applicationContext
        val on = Engine.a11yEnabled(ctx)
        if (on) A11yGuard.turnOffManually(ctx) else A11yGuard.turnOnManually(ctx)
        refresh()
    }

    private fun refresh() {
        val t = qsTile ?: return
        val on = Engine.a11yEnabled(applicationContext)
        t.state = if (on) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        t.label = "CTS Mapper"
        t.subtitle = if (on) "On" else "Off"
        t.updateTile()
    }
}
