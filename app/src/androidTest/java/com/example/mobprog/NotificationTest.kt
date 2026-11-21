
package com.example.mobprog

import android.Manifest
import android.app.AlarmManager
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import com.example.mobprog.alarm.AlarmScheduler
import com.example.mobprog.model.Entry
import com.example.mobprog.model.EntryType
import com.example.mobprog.model.Priority
import com.example.mobprog.notify.Notifications
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class NotificationTest {

    @get:Rule
    val permissionRule: GrantPermissionRule =
        GrantPermissionRule.grant(
            Manifest.permission.POST_NOTIFICATIONS
        )

    private val context: Context
        get() = InstrumentationRegistry
            .getInstrumentation()
            .targetContext

    private val manager: NotificationManager
        get() = context.getSystemService(
            Context.NOTIFICATION_SERVICE
        ) as NotificationManager

    private val testNoteId = 99999L
    private val testEntryId = 987655L
    private val testTitle = "Backend Notification Test"

    @Before
    fun setup() {
        Notifications.ensureChannels(context)
    }

    @After
    fun cleanup() {
        AlarmScheduler.cancelEntry(context, testEntryId)

        manager.activeNotifications.forEach {
            if (
                it.notification.extras.getString(
                    "android.title"
                ) == testTitle
            ) {
                manager.cancel(it.id)
            }
        }
    }

    private fun hasTestNotification(): Boolean =
        manager.activeNotifications.any {
            it.notification.extras.getString(
                "android.title"
            ) == testTitle
        }

    // TEST 1: DEADLINE NOTIFICATION CHANNEL

    @Test
    fun deadlineNotificationChannelExists() {
        val channel = manager.getNotificationChannel(
            Notifications.CHANNEL_DEADLINES
        )

        assertNotNull(channel)
        assertEquals(
            NotificationManager.IMPORTANCE_HIGH,
            channel.importance
        )
    }

    // TEST 2: QUICK NOTE NOTIFICATION CHANNEL

    @Test
    fun quickNoteNotificationChannelExists() {
        val channel = manager.getNotificationChannel(
            Notifications.CHANNEL_SERVICE
        )

        assertNotNull(channel)
        assertEquals(
            NotificationManager.IMPORTANCE_LOW,
            channel.importance
        )
    }

    // TEST 3: NOTIFICATION PERMISSION

    @Test
    fun notificationPermissionGranted() {
        assertTrue(
            "POST_NOTIFICATIONS permission must be granted",
            Notifications.canPost(context)
        )
    }

    // TEST 4: DIRECT NOTIFICATION POSTING

    @Test
    fun deadlineNotificationPostsSuccessfully() {
        assertTrue(
            "Notification permission must be granted",
            Notifications.canPost(context)
        )

        Notifications.postDeadline(
            context = context,
            noteId = testNoteId,
            noteTitle = testTitle,
            entryText = "Testing deadline reminders",
            offsetMinutes = 30
        )

        var found = false

        repeat(10) {
            if (hasTestNotification()) {
                found = true
            }

            if (!found) {
                Thread.sleep(200)
            }
        }

        assertTrue(
            "Direct deadline notification was not posted",
            found
        )
    }

    // TEST 5: ALARM SCHEDULING SMOKE TEST

    @Test
    fun fallbackAlarmCanBeRegistered() {
        val deadline =
            System.currentTimeMillis() + 31 * 60_000L

        val entry = createTestEntry(deadline)

        AlarmScheduler.syncEntry(
            context,
            testNoteId,
            entry,
            testTitle
        )

        // This only verifies no scheduling exception.
        // It does not prove actual delivery.
    }

    // TEST 6: ACTUAL SCHEDULED NOTIFICATION DELIVERY

    @Test
    fun actualScheduledNotificationArrives() {
        assertTrue(
            "Notification permission must be granted",
            Notifications.canPost(context)
        )

        val alarmManager = context.getSystemService(
            Context.ALARM_SERVICE
        ) as AlarmManager

        val exactAllowed =
            Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
                    alarmManager.canScheduleExactAlarms()

        // The 30-minute offset should trigger
        // approximately 20 seconds from now.
        val deadline =
            System.currentTimeMillis() +
                    30 * 60_000L +
                    20_000L

        val entry = createTestEntry(deadline)

        // Remove any old notification with this title.
        manager.activeNotifications.forEach {
            if (
                it.notification.extras.getString(
                    "android.title"
                ) == testTitle
            ) {
                manager.cancel(it.id)
            }
        }

        AlarmScheduler.syncEntry(
            context,
            testNoteId,
            entry,
            testTitle
        )

        android.util.Log.i(
            "NotificationTest",
            "Scheduled alarm; exact access: $exactAllowed"
        )

        var delivered = false

        // Wait up to 3 minutes for actual delivery.
        val timeout =
            System.currentTimeMillis() + 180_000L

        while (System.currentTimeMillis() < timeout) {
            if (hasTestNotification()) {
                delivered = true
                break
            }

            Thread.sleep(1000)
        }

        assertTrue(
            "No scheduled notification received within 3 minutes. " +
                    "Android may delay inexact alarms.",
            delivered
        )
    }

    private fun createTestEntry(
        deadline: Long
    ): Entry =
        Entry(
            id = testEntryId,
            blockId = 1L,
            position = 0,
            type = EntryType.CHECKBOX,
            text = "Scheduled notification test",
            checked = false,
            priority = Priority.HIGHEST,
            deadlineAt = deadline,
            allDay = false
        )
}
