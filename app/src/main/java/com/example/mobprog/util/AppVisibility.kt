package com.example.mobprog.util

/**
 * Tracks whether any activity of this app is started. The chat head lives in the
 * same process, so it can consult this directly instead of asking the system.
 */
object AppVisibility {

    var startedActivities: Int = 0
        private set

    val isForeground: Boolean get() = startedActivities > 0

    fun onActivityStarted() {
        startedActivities++
    }

    fun onActivityStopped() {
        startedActivities = (startedActivities - 1).coerceAtLeast(0)
    }
}
