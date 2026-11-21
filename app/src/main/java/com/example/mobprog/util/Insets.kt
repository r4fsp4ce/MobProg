package com.example.mobprog.util

import android.view.View
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat

/** Edge-to-edge padding helpers, so every screen uses the same insets logic. */
object Insets {

    fun applySystemBars(view: View) {
        ViewCompat.setOnApplyWindowInsetsListener(view) { target, windowInsets ->
            val bars = windowInsets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
            )
            target.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            windowInsets
        }
    }

    /** Keeps the bottom ribbon above the keyboard. */
    fun applySystemBarsAndIme(view: View) {
        ViewCompat.setOnApplyWindowInsetsListener(view) { target, windowInsets ->
            val bars = windowInsets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
            )
            val ime = windowInsets.getInsets(WindowInsetsCompat.Type.ime())
            target.setPadding(bars.left, bars.top, bars.right, maxOf(bars.bottom, ime.bottom))
            windowInsets
        }
    }
}
