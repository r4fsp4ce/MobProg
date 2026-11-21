package com.example.mobprog

import android.app.Activity
import android.app.Application
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import com.example.mobprog.data.StoreProvider
import com.example.mobprog.notify.Notifications
import com.example.mobprog.service.ChatHeadService
import com.example.mobprog.util.AppVisibility
import com.example.mobprog.util.ThemePrefs

class TudowApp : Application() {

    private val handler = Handler(Looper.getMainLooper())

    private val showBubble = Runnable {
        if (!AppVisibility.isForeground) ChatHeadService.setBubbleVisible(true)
    }

    override fun onCreate() {
        super.onCreate()
        ThemePrefs.apply(this)
        Notifications.ensureChannels(this)
        // The SQLite layer plugs in here once it exists:
        // StoreProvider.install(SQLiteTodoStore(this))
        StoreProvider.get()

        // The bubble is for other apps: hide it while any of ours is on screen.
        registerActivityLifecycleCallbacks(object : ActivityLifecycleCallbacks {
            override fun onActivityStarted(activity: Activity) {
                AppVisibility.onActivityStarted()
                handler.removeCallbacks(showBubble)
                ChatHeadService.setBubbleVisible(false)
            }

            override fun onActivityStopped(activity: Activity) {
                AppVisibility.onActivityStopped()
                if (!AppVisibility.isForeground) {
                    handler.postDelayed(showBubble, BUBBLE_REAPPEAR_DELAY_MS)
                }
            }

            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
            override fun onActivityResumed(activity: Activity) = Unit
            override fun onActivityPaused(activity: Activity) = Unit
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
            override fun onActivityDestroyed(activity: Activity) = Unit
        })
    }

    private companion object {
        const val BUBBLE_REAPPEAR_DELAY_MS = 350L
    }
}
