package com.example.mobprog.util

import android.view.View
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat

/** Edge-to-edge padding helpers, so every screen uses the same insets logic. */
object Insets {

    fun applySystemBars(view: View) {
        val start = view.paddingLeft
        val top = view.paddingTop
        val end = view.paddingRight
        val bottom = view.paddingBottom
        ViewCompat.setOnApplyWindowInsetsListener(view) { target, windowInsets ->
            val bars = windowInsets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
            )
            target.setPadding(
                start + bars.left,
                top + bars.top,
                end + bars.right,
                bottom + bars.bottom
            )
            windowInsets
        }
    }

    /** Keeps the bottom ribbon above the keyboard. */
    fun applySystemBarsAndIme(view: View) {
        val start = view.paddingLeft
        val top = view.paddingTop
        val end = view.paddingRight
        val bottom = view.paddingBottom
        ViewCompat.setOnApplyWindowInsetsListener(view) { target, windowInsets ->
            val bars = windowInsets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
            )
            val ime = windowInsets.getInsets(WindowInsetsCompat.Type.ime())
            target.setPadding(
                start + bars.left,
                top + bars.top,
                end + bars.right,
                bottom + maxOf(bars.bottom, ime.bottom)
            )
            windowInsets
        }
    }

    /**
     * Adds the status bar and display cutout insets to a view's own padding.
     * Use on a bar that must keep its background edge-to-edge.
     */
    fun applyTopInset(view: View) {
        val start = view.paddingLeft
        val top = view.paddingTop
        val end = view.paddingRight
        val bottom = view.paddingBottom
        ViewCompat.setOnApplyWindowInsetsListener(view) { target, windowInsets ->
            val bars = windowInsets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
            )
            target.setPadding(start + bars.left, top + bars.top, end + bars.right, bottom)
            windowInsets
        }
    }

    /**
     * Adds the navigation bar inset to a view's own padding, so a bottom bar
     * keeps its background behind the gesture area.
     */
    fun applyBottomInset(view: View) {
        val start = view.paddingLeft
        val top = view.paddingTop
        val end = view.paddingRight
        val bottom = view.paddingBottom
        ViewCompat.setOnApplyWindowInsetsListener(view) { target, windowInsets ->
            val bars = windowInsets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
            )
            target.setPadding(start + bars.left, top, end + bars.right, bottom + bars.bottom)
            windowInsets
        }
    }
}
