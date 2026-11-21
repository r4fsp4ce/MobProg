package com.example.mobprog.util

import com.example.mobprog.model.EntryType
import com.example.mobprog.model.NoteDraft

/**
 * Turns the draft into the Markdown that is mirrored to disk: an H1 title, block
 * headings as written, and one line per entry using its list marker.
 */
object NoteMarkdown {

    fun from(draft: NoteDraft): String = buildString {
        val title = draft.title.trim()
        if (title.isNotEmpty()) {
            append("# ").append(title).append("\n\n")
        }

        draft.blocks.forEach { block ->
            val heading = block.heading.trim()
            if (heading.isNotEmpty()) {
                append(heading).append("\n\n")
            }

            var number = 1
            var written = 0
            block.entries.forEach { entry ->
                val text = entry.text.trim()
                if (text.isEmpty()) return@forEach
                when (entry.type) {
                    EntryType.CHECKBOX -> append(if (entry.checked) "- [x] " else "- [ ] ")
                    EntryType.BULLET -> append("- ")
                    EntryType.NUMBERED -> append(number++).append(". ")
                    EntryType.TEXT -> Unit
                }
                append(text).append("\n")
                written++
            }
            if (written > 0) append("\n")
        }
    }.trimEnd() + "\n"
}
