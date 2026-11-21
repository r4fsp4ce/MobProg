package com.example.mobprog.service

import android.animation.ValueAnimator
import android.app.Service
import android.content.Intent
import android.graphics.PixelFormat
import android.graphics.Point
import android.graphics.Rect
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import android.view.ContextThemeWrapper
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowInsets
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import com.example.mobprog.R
import com.example.mobprog.notify.Notifications
import com.example.mobprog.ui.DateTimePicker
import com.example.mobprog.util.AppVisibility
import com.example.mobprog.util.ThemePrefs
import kotlin.math.abs

/**
 * Foreground service that keeps a draggable chat head above other apps and opens
 * the quick-note panel on tap. Needs "Display over other apps".
 *
 * The head is hidden while one of the app's activities is in the foreground
 * (see [AppVisibility]); the service keeps running so it reappears immediately
 * when the app goes to the background.
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
        instance = this
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
        head.visibility = if (AppVisibility.isForeground) View.GONE else View.VISIBLE
    }

    private fun applyBubbleVisibility(visible: Boolean) {
        val head = chatHead ?: return
        if (visible) {
            head.visibility = View.VISIBLE
        } else {
            head.visibility = View.GONE
            closePanel()
            removeDeadlineOverlay()
        }
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
            .also { ThemePrefs.applyTo(it) }
        val quickNote = QuickNotePanel(
            service = this,
            onClose = { closePanel() },
            showDeadlinePicker = { initial, allDay, onPicked ->
                showDeadlinePicker(themed, initial, allDay, onPicked)
            }
        )
        val bounds = screenBounds()
        val width = minOf(dp(360), bounds.width() - dp(24)).coerceAtLeast(dp(240))
        val params = WindowManager.LayoutParams(
            width,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = ((bounds.width() - width) / 2).coerceAtLeast(0)
            y = dp(120)
            softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE or
                WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE
        }
        panel = quickNote
        panelParams = params
        windowManager.addView(quickNote.view, params)
        quickNote.dragHandle.setOnTouchListener(PanelDragListener(quickNote.view))
        quickNote.focusInput()
    }

    private fun closePanel() {
        removeDeadlineOverlay()
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
            isClickable = true
            addView(
                picker.view,
                FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.WRAP_CONTENT,
                    Gravity.CENTER
                )
            )
        }
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
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

    private fun screenBounds(): Rect =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            windowManager.currentWindowMetrics.bounds
        } else {
            val size = Point()
            @Suppress("DEPRECATION")
            windowManager.defaultDisplay.getRealSize(size)
            Rect(0, 0, size.x, size.y)
        }

    private fun systemBarInsets(): Rect =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val insets = windowManager.currentWindowMetrics.windowInsets
                .getInsetsIgnoringVisibility(WindowInsets.Type.systemBars())
            Rect(insets.left, insets.top, insets.right, insets.bottom)
        } else {
            Rect(
                0,
                systemDimension("status_bar_height"),
                0,
                systemDimension("navigation_bar_height")
            )
        }

    private fun systemDimension(name: String): Int {
        val id = resources.getIdentifier(name, "dimen", "android")
        return if (id > 0) resources.getDimensionPixelSize(id) else 0
    }

    /** The head follows the finger, then springs to the nearest side edge. */
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
                    if (moved) {
                        snapToEdge(view, params)
                    } else {
                        onTap()
                    }
                    return true
                }
                MotionEvent.ACTION_CANCEL -> return true
            }
            return false
        }
    }

    private fun snapToEdge(view: View, params: WindowManager.LayoutParams) {
        val bounds = screenBounds()
        val bars = systemBarInsets()
        val targetX = if (params.x + view.width / 2 < bounds.width() / 2) {
            bounds.left
        } else {
            bounds.right - view.width
        }
        params.y = params.y.coerceIn(
            bounds.top + bars.top,
            maxOf(bounds.top + bars.top, bounds.bottom - bars.bottom - view.height)
        )
        ValueAnimator.ofInt(params.x, targetX).apply {
            duration = 220L
            addUpdateListener {
                params.x = it.animatedValue as Int
                runCatching { windowManager.updateViewLayout(view, params) }
            }
            start()
        }
    }

    /** Drags the panel window by its header. */
    private inner class PanelDragListener(private val panelView: View) : View.OnTouchListener {

        private var startX = 0
        private var startY = 0
        private var downX = 0f
        private var downY = 0f

        override fun onTouch(view: View, event: MotionEvent): Boolean {
            val params = panelParams ?: return false
            val bounds = screenBounds()
            val bars = systemBarInsets()
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    startX = params.x
                    startY = params.y
                    downX = event.rawX
                    downY = event.rawY
                    return true
                }
                MotionEvent.ACTION_MOVE -> {
                    val width = if (panelView.width > 0) panelView.width else params.width
                    val height = if (panelView.height > 0) panelView.height else 0
                    val minY = bounds.top + bars.top
                    val maxY = maxOf(minY, bounds.bottom - bars.bottom - height)
                    params.x = (startX + (event.rawX - downX).toInt())
                        .coerceIn(bounds.left, maxOf(bounds.left, bounds.right - width))
                    params.y = (startY + (event.rawY - downY).toInt()).coerceIn(minY, maxY)
                    runCatching { windowManager.updateViewLayout(panelView, params) }
                    return true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> return true
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
        if (instance === this) instance = null
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    companion object {
        private var instance: ChatHeadService? = null

        /** Called from the Application lifecycle callbacks (same process). */
        fun setBubbleVisible(visible: Boolean) {
            instance?.applyBubbleVisibility(visible)
        }
    }
}
