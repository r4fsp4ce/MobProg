
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
            if (
                manager.activeNotifications.any {
                    it.notification.extras.getString(
                        "android.title"
                    ) == testTitle
                }
            ) {
                found = true
            }

            if (!found) {
                Thread.sleep(200)
            }
        }

        assertTrue(
            "Deadline notification should be posted",
            found
        )
    }

    // TEST 5: ALARM SCHEDULING SMOKE TEST

    @Test
    fun fallbackAlarmCanBeRegistered() {
        val alarmManager = context.getSystemService(
            Context.ALARM_SERVICE
        ) as AlarmManager

        val exactAllowed =
            Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
                    alarmManager.canScheduleExactAlarms()

        val deadline =
            System.currentTimeMillis() + 31 * 60_000L

        val entry = Entry(
            id = testEntryId,
            blockId = 1L,
            position = 0,
            type = EntryType.CHECKBOX,
            text = "Fallback alarm test",
            checked = false,
            priority = Priority.HIGHEST,
            deadlineAt = deadline,
            allDay = false
        )

        AlarmScheduler.syncEntry(
            context,
            testNoteId,
            entry,
            testTitle
        )

        android.util.Log.i(
            "NotificationTest",
            "Scheduling completed; exact alarm access: $exactAllowed"
        )

        // Smoke test only: reaching here confirms that
        // scheduling did not throw an exception.
        // It does not prove registration or delivery.
    }
}
