package com.example.mobprog.util

import android.os.Handler
import android.os.Looper
import android.util.Log
import java.util.concurrent.Executors

/**
 * One worker for store calls, one main-thread poster for the results.
 * Every screen goes through this, so a disk-backed store never touches the UI thread.
 */
object Threads {

    private const val TAG = "Tudow"

    private val worker = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "tudow-store")
    }
    private val main = Handler(Looper.getMainLooper())

    fun io(block: () -> Unit) {
        worker.execute(block)
    }

    fun <T> load(work: () -> T, then: (T) -> Unit) {
        worker.execute {
            try {
                val value = work()
                main.post { then(value) }
            } catch (error: Exception) {
                Log.e(TAG, "Store call failed", error)
            }
        }
    }

    fun post(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) block() else main.post(block)
    }
}
