package com.example.mobprog.alarm

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.example.mobprog.data.StoreProvider
import com.example.mobprog.notify.Notifications
import com.example.mobprog.util.Threads

/** Alarms do not survive a reboot, so everything with a future deadline is re-armed. */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        if (action != Intent.ACTION_BOOT_COMPLETED && action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        val appContext = context.applicationContext
        Threads.io {
            Notifications.ensureChannels(appContext)
            AlarmScheduler.rescheduleAll(appContext, StoreProvider.get())
        }
    }
}
