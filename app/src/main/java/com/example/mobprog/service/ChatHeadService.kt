package com.example.mobprog.service

import android.app.Service
import android.content.Intent
import android.graphics.PixelFormat
import android.os.IBinder
import android.provider.Settings
import android.view.ContextThemeWrapper
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import com.example.mobprog.R
import com.example.mobprog.notify.Notifications
import com.example.mobprog.ui.DateTimePicker
import kotlin.math.abs

/**
 * Foreground service that keeps a draggable chat head above other apps and opens
 * the quick-note panel on tap. Needs "Display over other apps".
 */
class ChatHeadService : Service() {

    private lateinit var windowManager: WindowManager

    private var chatHead: View? = null
    private var headParams: WindowManager.LayoutParams? = null
    private var panel: QuickNotePanel? = null
    private var panelParams: WindowManager.LayoutParams? = null
    private var deadlineOverlay: View? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        startForeground(
            Notifications.serviceNotificationId(),
            Notifications.serviceNotification(this)
        )
        if (!Settings.canDrawOverlays(this)) {
            stopSelf()
            return
        }
        windowManager = getSystemService(WindowManager::class.java)
        addChatHead()
    }

    private fun addChatHead() {
        val size = dp(56)
        val head = ImageView(this).apply {
            setImageResource(R.drawable.bg_chat_head)
            contentDescription = getString(R.string.cd_quick_note)
            setOnTouchListener(DragListener(onTap = { togglePanel() }))
        }
        val params = WindowManager.LayoutParams(
            size,
            size,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = dp(8)
            y = dp(180)
        }
        chatHead = head
        headParams = params
        windowManager.addView(head, params)
    }

    private fun togglePanel() {
        if (panel != null) {
            closePanel()
        } else {
            openPanel()
        }
    }

    private fun openPanel() {
        val themed = ContextThemeWrapper(this, R.style.Theme_MobProg)
        val quickNote = QuickNotePanel(
            service = this,
            onClose = { closePanel() },
            showDeadlinePicker = { initial, allDay, onPicked ->
                showDeadlinePicker(themed, initial, allDay, onPicked)
            }
        )
        val params = WindowManager.LayoutParams(
            dp(300),
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            y = dp(120)
            softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE or
                WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE
        }
        panel = quickNote
        panelParams = params
        windowManager.addView(quickNote.view, params)
        quickNote.view.requestFocus()
    }

    private fun closePanel() {
        val current = panel ?: return
        panel = null
        panelParams = null
        runCatching { windowManager.removeView(current.view) }
    }

    /** The deadline picker needs a window token, so it becomes an overlay window of its own. */
    private fun showDeadlinePicker(
        themed: ContextThemeWrapper,
        initial: Long?,
        allDay: Boolean,
        onPicked: (Long, Boolean) -> Unit
    ) {
        removeDeadlineOverlay()
        val picker = DateTimePicker(themed, initial, allDay)
        val container = FrameLayout(themed).apply {
            val pad = dp(24)
            setPadding(pad, pad, pad, pad)
            addView(picker.view)
        }
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_DIM_BEHIND,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.CENTER
            dimAmount = 0.4f
            softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
        }

        picker.onPicked = { millis, isAllDay ->
            removeDeadlineOverlay()
            onPicked(millis, isAllDay)
        }
        picker.onCancel = { removeDeadlineOverlay() }

        deadlineOverlay = container
        windowManager.addView(container, params)
    }

    private fun removeDeadlineOverlay() {
        val current = deadlineOverlay ?: return
        deadlineOverlay = null
        runCatching { windowManager.removeView(current) }
    }

    private inner class DragListener(private val onTap: () -> Unit) : View.OnTouchListener {

        private var startX = 0
        private var startY = 0
        private var downX = 0f
        private var downY = 0f
        private var moved = false

        override fun onTouch(view: View, event: MotionEvent): Boolean {
            val params = headParams ?: return false
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    startX = params.x
                    startY = params.y
                    downX = event.rawX
                    downY = event.rawY
                    moved = false
                    return true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (event.rawX - downX).toInt()
                    val dy = (event.rawY - downY).toInt()
                    if (abs(dx) > dp(4) || abs(dy) > dp(4)) moved = true
                    params.x = startX + dx
                    params.y = startY + dy
                    runCatching { windowManager.updateViewLayout(view, params) }
                    return true
                }
                MotionEvent.ACTION_UP -> {
                    if (!moved) onTap()
                    return true
                }
                MotionEvent.ACTION_CANCEL -> return true
            }
            return false
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        closePanel()
        removeDeadlineOverlay()
        chatHead?.let { head -> runCatching { windowManager.removeView(head) } }
        chatHead = null
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}
