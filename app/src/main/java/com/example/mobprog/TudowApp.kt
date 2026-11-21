
package com.example.mobprog

import android.app.Application
import com.example.mobprog.data.SQLiteTodoStore
import com.example.mobprog.data.StoreProvider
import com.example.mobprog.notify.Notifications

class TudowApp : Application() {

    override fun onCreate() {
        super.onCreate()

        Notifications.ensureChannels(this)

        StoreProvider.install(SQLiteTodoStore(this))
    }
}
