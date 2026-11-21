package com.example.mobprog.ui

import android.content.Context
import android.util.AttributeSet
import android.view.ViewGroup

/**
 * Lays chips/pills out left to right and wraps them onto a new line when they no
 * longer fit. A long deadline therefore moves downwards instead of running into
 * the fixed icons on the right of a note card.
 */
class PillFlowLayout @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : ViewGroup(context, attrs, defStyleAttr) {

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val widthSize = MeasureSpec.getSize(widthMeasureSpec)
        val available = if (MeasureSpec.getMode(widthMeasureSpec) == MeasureSpec.UNSPECIFIED) {
            Int.MAX_VALUE
        } else {
            widthSize - paddingLeft - paddingRight
        }

        var lineWidth = 0
        var lineHeight = 0
        var totalHeight = 0
        var widestLine = 0

        for (index in 0 until childCount) {
            val child = getChildAt(index)
            if (child.visibility == GONE) continue
            val lp = child.layoutParams as MarginLayoutParams
            measureChildWithMargins(child, widthMeasureSpec, 0, heightMeasureSpec, 0)
            val width = child.measuredWidth + lp.leftMargin + lp.rightMargin
            if (lineWidth > 0 && lineWidth + width > available) {
                widestLine = maxOf(widestLine, lineWidth)
                totalHeight += lineHeight
                lineWidth = 0
                lineHeight = 0
            }
            lineWidth += width
            lineHeight = maxOf(lineHeight, child.measuredHeight + lp.topMargin + lp.bottomMargin)
        }

        widestLine = maxOf(widestLine, lineWidth)
        totalHeight += lineHeight

        setMeasuredDimension(
            resolveSize(paddingLeft + paddingRight + widestLine, widthMeasureSpec),
            resolveSize(paddingTop + paddingBottom + totalHeight, heightMeasureSpec)
        )
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        val available = right - left - paddingLeft - paddingRight
        var x = paddingLeft
        var y = paddingTop
        var lineHeight = 0

        for (index in 0 until childCount) {
            val child = getChildAt(index)
            if (child.visibility == GONE) continue
            val lp = child.layoutParams as MarginLayoutParams
            val width = child.measuredWidth + lp.leftMargin + lp.rightMargin
            if (x > paddingLeft && x + width - paddingLeft > available) {
                x = paddingLeft
                y += lineHeight
                lineHeight = 0
            }
            val childLeft = x + lp.leftMargin
            val childTop = y + lp.topMargin
            child.layout(
                childLeft,
                childTop,
                childLeft + child.measuredWidth,
                childTop + child.measuredHeight
            )
            x += width
            lineHeight = maxOf(lineHeight, child.measuredHeight + lp.topMargin + lp.bottomMargin)
        }
    }

    override fun generateLayoutParams(attrs: AttributeSet?): LayoutParams =
        MarginLayoutParams(context, attrs)

    override fun generateDefaultLayoutParams(): LayoutParams =
        MarginLayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT)

    override fun generateLayoutParams(params: LayoutParams): LayoutParams =
        MarginLayoutParams(params)

    override fun checkLayoutParams(params: LayoutParams): Boolean =
        params is MarginLayoutParams
}
