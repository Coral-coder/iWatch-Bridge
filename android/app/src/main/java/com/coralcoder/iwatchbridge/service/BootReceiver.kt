package com.coralcoder.iwatchbridge.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Reconnects to the paired watches after a reboot, as an iPhone does. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) BridgeService.startIfNeeded(context)
    }
}
