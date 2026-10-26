package dev.spelfrog.getsynced.trigger

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import dev.spelfrog.getsynced.sync.SyncTrigger

/** Local automation hook (Tasker, MacroDroid, adb): `am broadcast -a dev.spelfrog.getsynced.SYNC -p dev.spelfrog.getsynced`. */
class SyncIntentReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == ACTION_SYNC) {
            SyncTrigger.enqueue(context, "intent")
        }
    }

    companion object {
        const val ACTION_SYNC = "dev.spelfrog.getsynced.SYNC"
    }
}
