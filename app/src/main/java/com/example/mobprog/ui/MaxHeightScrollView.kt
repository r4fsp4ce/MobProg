package com.example.mobprog.ui

import android.content.Context
import android.util.AttributeSet
import androidx.core.widget.NestedScrollView

/**
 * NestedScrollView with a cap, used by the quick-note panel so a long note
 * scrolls instead of growing the overlay past the screen.
 */
class MaxHeightScrollView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : NestedScrollView(context, attrs, defStyleAttr) {

    var maxHeight: Int = 0
        set(value) {
            field = value
            requestLayout()
        }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        if (maxHeight > 0) {
            val capped = MeasureSpec.makeMeasureSpec(maxHeight, MeasureSpec.AT_MOST)
            super.onMeasure(widthMeasureSpec, capped)
        } else {
            super.onMeasure(widthMeasureSpec, heightMeasureSpec)
        }
    }
}
