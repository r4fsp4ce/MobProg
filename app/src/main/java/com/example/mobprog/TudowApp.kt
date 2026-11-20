package com.example.mobprog

import android.app.Application
import com.example.mobprog.data.StoreProvider
import com.example.mobprog.notify.Notifications

class TudowApp : Application() {

    override fun onCreate() {
        super.onCreate()
        Notifications.ensureChannels(this)
        // The SQLite layer plugs in here once it exists:
        // StoreProvider.install(SQLiteTodoStore(this))
        StoreProvider.get()
    }
}
