package com.example.mobprog.ui

import android.widget.TextView
import io.noties.markwon.Markwon

/** Thin wrapper so the Markdown library stays in one place. */
object MarkdownUtils {

    @Volatile
    private var markwon: Markwon? = null

    fun render(target: TextView, markdown: String) {
        if (markdown.isBlank()) {
            target.text = ""
            return
        }
        val instance = markwon ?: Markwon.builder(target.context).build().also { markwon = it }
        instance.setMarkdown(target, markdown)
    }
}
