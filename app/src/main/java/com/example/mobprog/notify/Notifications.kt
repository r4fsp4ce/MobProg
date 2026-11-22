package com.example.mobprog.notify

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.RingtoneManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.example.mobprog.NoteEditorActivity
import com.example.mobprog.R

object Notifications {

    const val CHANNEL_DEADLINES = "deadlines"
    const val CHANNEL_DEADLINE_ALARM = "deadline_alarm"
    const val CHANNEL_SERVICE = "quick_note"

    private const val SERVICE_NOTIFICATION_ID = 1001
    private const val DEADLINE_NOTIFICATION_ID = 2000

    fun ensureChannels(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        val deadlines = NotificationChannel(
            CHANNEL_DEADLINES,
            context.getString(R.string.notification_channel_deadlines),
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = context.getString(R.string.notification_channel_deadlines_description)
        }
        // The at-deadline alert has to be heard: alarm stream, alarm ringtone,
        // vibration. The early reminders keep the ordinary notification sound.
        val alarmSound = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
            ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
        val deadlineAlarm = NotificationChannel(
            CHANNEL_DEADLINE_ALARM,
            context.getString(R.string.notification_channel_deadline_alarm),
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description =
                context.getString(R.string.notification_channel_deadline_alarm_description)
            setSound(
                alarmSound,
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ALARM)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build()
            )
            enableVibration(true)
            vibrationPattern = longArrayOf(0, 500, 400, 500)
        }
        val service = NotificationChannel(
            CHANNEL_SERVICE,
            context.getString(R.string.notification_channel_quick_note),
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = context.getString(R.string.notification_channel_quick_note_description)
            setShowBadge(false)
        }
        manager.createNotificationChannels(listOf(deadlines, deadlineAlarm, service))
    }

    fun canPost(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return true
        return ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.POST_NOTIFICATIONS
        ) == PackageManager.PERMISSION_GRANTED
    }

    fun postDeadline(
        context: Context,
        noteId: Long,
        noteTitle: String,
        entryText: String,
        offsetMinutes: Int
    ) {
        if (!canPost(context)) return
        ensureChannels(context)

        val title = noteTitle.ifBlank { context.getString(R.string.app_name) }
        val atDeadline = offsetMinutes <= 0
        val headline = if (atDeadline) {
            context.getString(R.string.notification_deadline_due)
        } else if (offsetMinutes < 60) {
            context.getString(R.string.countdown_minutes, offsetMinutes)
        } else {
            context.getString(R.string.countdown_hours, offsetMinutes / 60)
        }
        val body = entryText.ifBlank { context.getString(R.string.notification_body_fallback) }

        val builder = NotificationCompat.Builder(
            context,
            if (atDeadline) CHANNEL_DEADLINE_ALARM else CHANNEL_DEADLINES
        )
            .setSmallIcon(R.drawable.ic_logo)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText("$headline\n$body"))
            .setAutoCancel(true)
            .setContentIntent(openNote(context, noteId, deadLineRequestCode(noteId)))
        if (atDeadline) {
            builder.setCategory(NotificationCompat.CATEGORY_ALARM)
        }

        managerOf(context)?.notify(DEADLINE_NOTIFICATION_ID + (noteId and 0xFFFF).toInt(), builder.build())
    }

    fun serviceNotification(context: Context): android.app.Notification {
        ensureChannels(context)
        val open = PendingIntent.getActivity(
            context,
            0,
            Intent(context, com.example.mobprog.MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(context, CHANNEL_SERVICE)
            .setSmallIcon(R.drawable.ic_logo)
            .setContentTitle(context.getString(R.string.service_notification_title))
            .setContentText(context.getString(R.string.service_notification_text))
            .setContentIntent(open)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    fun serviceNotificationId(): Int = SERVICE_NOTIFICATION_ID

    private fun openNote(context: Context, noteId: Long, requestCode: Int): PendingIntent {
        val intent = Intent(context, NoteEditorActivity::class.java)
            .putExtra(NoteEditorActivity.EXTRA_NOTE_ID, noteId)
        return PendingIntent.getActivity(
            context,
            requestCode,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun deadLineRequestCode(noteId: Long): Int = 3000 + (noteId and 0x7FFF).toInt()

    private fun managerOf(context: Context): NotificationManager? =
        context.getSystemService(NotificationManager::class.java)
}
