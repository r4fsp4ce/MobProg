package com.example.mobprog.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.PopupWindow
import androidx.core.content.ContextCompat
import com.example.mobprog.R
import com.example.mobprog.model.NoteColor

/**
 * The colour dropdown: a rounded square with a comic-style tail.
 *
 * When a [panel] view is supplied, the bubble's body is placed flush against the
 * bottom edge of the quick-note card, so the dropdown reads as an extension of
 * the panel rather than a floating card. The tail points up at the colour button
 * and is drawn over the panel's bottom strip. If there is no room below the card,
 * it falls back to hanging from the anchor button.
 */
class ColorSwatchPopup(
    private val context: Context,
    private val selected: NoteColor,
    private val onPick: (NoteColor) -> Unit
) {

    private val popup = PopupWindow()

    fun show(anchor: View, panel: View? = null) {
        val metrics = context.resources.displayMetrics
        val margin = dp(8)
        val pad = dp(10)
        val tailHeight = dp(10)

        val contentWidth = COLUMNS * dp(SWATCH) + (COLUMNS - 1) * dp(GAP)
        val popupWidth = contentWidth + pad * 2
        val rows = NoteColor.entries.chunked(COLUMNS).size
        val contentHeight = rows * dp(SWATCH) + (rows - 1) * dp(GAP)
        val bodyHeight = contentHeight + pad * 2
        val totalHeight = bodyHeight + tailHeight

        val anchorLocation = IntArray(2)
        anchor.getLocationOnScreen(anchorLocation)
        val anchorLeft = anchorLocation[0]
        val anchorCenter = anchorLeft + anchor.width / 2
        val anchorBottomOnScreen = anchorLocation[1] + anchor.height

        val popupX = (anchorCenter - popupWidth / 2).coerceIn(
            margin,
            maxOf(margin, metrics.widthPixels - popupWidth - margin)
        )

        val panelBottom = panel?.let {
            val location = IntArray(2)
            it.getLocationOnScreen(location)
            location[1] + it.height
        }
        val attached = panelBottom != null &&
            panelBottom - dp(1) + bodyHeight <= metrics.heightPixels - margin

        val tailAtTop: Boolean
        val rawTop: Int
        if (attached && panelBottom != null) {
            // Body top sits on the panel's bottom edge; the tail lies inside the panel.
            tailAtTop = true
            rawTop = panelBottom - dp(1) - tailHeight
        } else {
            val anchorTop = anchorLocation[1]
            val anchorBottom = anchorTop + anchor.height
            val fitsBelow = anchorBottom - dp(1) + totalHeight <= metrics.heightPixels - margin
            val fitsAbove = anchorTop - totalHeight + dp(1) >= margin
            tailAtTop = fitsBelow || !fitsAbove
            rawTop = if (tailAtTop) anchorBottom - dp(1) else anchorTop - totalHeight + dp(1)
        }

        val popupTop = rawTop.coerceIn(
            margin,
            maxOf(margin, metrics.heightPixels - totalHeight - margin)
        )

        val tailEdge = dp(CORNER + TAIL_WIDTH / 2)
        val tailX = (anchorCenter - popupX).toFloat().coerceIn(
            tailEdge.toFloat(),
            (popupWidth - tailEdge).toFloat()
        )

        val bubble = FrameLayout(context).apply {
            val topPad = if (tailAtTop) tailHeight + pad else pad
            val bottomPad = if (tailAtTop) pad else tailHeight + pad
            setPadding(pad, topPad, pad, bottomPad)
            addView(
                buildGrid(),
                FrameLayout.LayoutParams(contentWidth, ViewGroup.LayoutParams.WRAP_CONTENT)
            )
            background = BubbleDrawable(
                fill = ContextCompat.getColor(context, R.color.color_surface),
                stroke = ContextCompat.getColor(context, R.color.color_outline),
                outlineWidth = dp(1).toFloat(),
                corner = dp(CORNER).toFloat(),
                tailWidth = dp(TAIL_WIDTH).toFloat(),
                tailHeight = tailHeight.toFloat(),
                tailX = tailX,
                tailAtTop = tailAtTop
            )
        }

        popup.contentView = bubble
        popup.width = popupWidth
        popup.height = ViewGroup.LayoutParams.WRAP_CONTENT
        popup.isFocusable = true
        popup.isOutsideTouchable = true
        popup.elevation = dp(10).toFloat()
        popup.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        // Anchor-relative: screen coordinates were being offset by the overlay window.
        popup.showAsDropDown(anchor, popupX - anchorLeft, popupTop - anchorBottomOnScreen)
    }

    private fun buildGrid(): LinearLayout {
        val column = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
        }
        NoteColor.entries.chunked(COLUMNS).forEach { colors ->
            val row = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
            }
            colors.forEachIndexed { index, color ->
                row.addView(swatch(color, index == colors.lastIndex))
            }
            column.addView(row)
        }
        return column
    }

    private fun swatch(color: NoteColor, lastInRow: Boolean): View {
        val size = dp(SWATCH)
        return View(context).apply {
            layoutParams = LinearLayout.LayoutParams(size, size).apply {
                if (!lastInRow) marginEnd = dp(GAP)
            }
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(ContextCompat.getColor(context, color.colorRes))
                setStroke(
                    dp(if (color == selected) 2 else 1),
                    ContextCompat.getColor(
                        context,
                        if (color == selected) R.color.color_primary else R.color.color_outline
                    )
                )
            }
            isClickable = true
            isFocusable = true
            contentDescription = context.getString(color.labelRes)
            setOnClickListener {
                onPick(color)
                popup.dismiss()
            }
        }
    }

    private fun dp(value: Int): Int =
        (value * context.resources.displayMetrics.density).toInt()

    /**
     * Rounded square with a triangular tail. Tail and body are unioned so the
     * outline follows the whole silhouette without a seam.
     */
    private class BubbleDrawable(
        private val fill: Int,
        private val stroke: Int,
        private val outlineWidth: Float,
        private val corner: Float,
        private val tailWidth: Float,
        private val tailHeight: Float,
        private val tailX: Float,
        private val tailAtTop: Boolean
    ) : Drawable() {

        private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            color = fill
        }
        private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            color = stroke
            strokeWidth = outlineWidth
            strokeJoin = Paint.Join.ROUND
            strokeCap = Paint.Cap.ROUND
        }
        private val path = Path()

        override fun onBoundsChange(bounds: Rect) {
            super.onBoundsChange(bounds)
            val inset = outlineWidth / 2f
            val left = bounds.left + inset
            val top = bounds.top + inset
            val right = bounds.right - inset
            val bottom = bounds.bottom - inset

            val bodyTop = if (tailAtTop) top + tailHeight else top
            val bodyBottom = if (tailAtTop) bottom else bottom - tailHeight

            val body = Path().apply {
                addRoundRect(
                    RectF(left, bodyTop, right, bodyBottom),
                    corner,
                    corner,
                    Path.Direction.CW
                )
            }
            val tail = Path().apply {
                if (tailAtTop) {
                    moveTo(tailX - tailWidth / 2f, bodyTop)
                    lineTo(tailX, top)
                    lineTo(tailX + tailWidth / 2f, bodyTop)
                } else {
                    moveTo(tailX - tailWidth / 2f, bodyBottom)
                    lineTo(tailX, bottom)
                    lineTo(tailX + tailWidth / 2f, bodyBottom)
                }
                close()
            }
            path.op(body, tail, Path.Op.UNION)
        }

        override fun draw(canvas: Canvas) {
            canvas.drawPath(path, fillPaint)
            canvas.drawPath(path, strokePaint)
        }

        override fun setAlpha(alpha: Int) {
            fillPaint.alpha = alpha
            strokePaint.alpha = alpha
            invalidateSelf()
        }

        override fun setColorFilter(colorFilter: ColorFilter?) {
            fillPaint.colorFilter = colorFilter
            strokePaint.colorFilter = colorFilter
            invalidateSelf()
        }

        override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
    }

    private companion object {
        const val COLUMNS = 6
        const val SWATCH = 28
        const val GAP = 10
        const val CORNER = 16
        const val TAIL_WIDTH = 20
    }
}
