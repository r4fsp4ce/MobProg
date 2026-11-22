package com.example.mobprog.util

import com.example.mobprog.model.BlockDraft
import com.example.mobprog.model.EntryDraft
import com.example.mobprog.model.EntryType
import com.example.mobprog.model.NoteDraft

/**
 * The Markdown projection of a draft and its parser: an H1 title, block headings
 * as H2, and one line per entry using its list marker. A blank line starts a new
 * block.
 *
 *     # Title
 *
 *     ## Block heading
 *     - [ ] a checkbox
 *     - a bullet
 *     1. a numbered item
 *     a plain text item
 *
 * Markdown has no place for priorities and deadlines, so [parse] carries them
 * over from the draft that was on screen using a line diff.
 */
object NoteMarkdown {

    private val TITLE = Regex("""^#\s+(.*)$""")
    private val HEADING = Regex("""^##\s+(.*)$""")
    private val CHECKBOX = Regex("""^[-*+]\s+\[([ xX])]\s*(.*)$""")
    private val BULLET = Regex("""^[-*+]\s+(.*)$""")
    private val NUMBERED = Regex("""^\d+[.)]\s+(.*)$""")

    /** Past this many entries the diff table is skipped in favour of position matching. */
    private const val MAX_DIFF_ENTRIES = 400

    fun from(draft: NoteDraft): String = buildString {
        val title = draft.title.trim()
        if (title.isNotEmpty()) {
            append("# ").append(title).append("\n\n")
        }

        draft.blocks.forEach { block ->
            val heading = block.heading.trim()
            if (heading.isNotEmpty()) {
                append("## ").append(heading).append("\n")
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
            // The blank line closes the paragraph, and keeps heading-only blocks apart.
            if (written > 0 || heading.isNotEmpty()) append("\n")
        }
    }.trimEnd() + "\n"

    /**
     * Turns [source] back into a draft. [base] supplies the identity and note
     * attributes Markdown cannot carry: every parsed entry whose text matches an
     * entry of [base] keeps that entry's id, priority and deadline.
     */
    fun parse(source: String, base: NoteDraft = NoteDraft()): NoteDraft {
        val lines = source.replace("\r\n", "\n").replace('\r', '\n').split('\n')
        val blocks = mutableListOf<BlockDraft>()
        var block: BlockDraft? = null
        var title = ""
        var firstContentLine = true

        for (raw in lines) {
            val text = raw.trim()
            if (text.isEmpty()) {
                block = null
                continue
            }

            val heading = TITLE.matchEntire(text)?.groupValues?.get(1)?.trim()
            if (firstContentLine && heading != null) {
                title = heading
                firstContentLine = false
                continue
            }
            firstContentLine = false

            val target = block ?: BlockDraft().also {
                blocks.add(it)
                block = it
            }
            val checkbox = CHECKBOX.matchEntire(text)
            val bullet = BULLET.matchEntire(text)
            val numbered = NUMBERED.matchEntire(text)
            val headingLine = HEADING.matchEntire(text)
            when {
                checkbox != null -> target.entries.add(
                    EntryDraft(
                        type = EntryType.CHECKBOX,
                        text = checkbox.groupValues[2].trim(),
                        checked = checkbox.groupValues[1].isNotBlank()
                    )
                )

                bullet != null -> target.entries.add(
                    EntryDraft(type = EntryType.BULLET, text = bullet.groupValues[1].trim())
                )

                numbered != null -> target.entries.add(
                    EntryDraft(type = EntryType.NUMBERED, text = numbered.groupValues[1].trim())
                )

                headingLine != null && target.heading.isEmpty() ->
                    target.heading = headingLine.groupValues[1].trim()

                else -> target.entries.add(EntryDraft(type = EntryType.TEXT, text = text))
            }
        }

        val parsed = NoteDraft(
            id = base.id,
            folderId = base.folderId,
            title = title,
            color = base.color,
            pinned = base.pinned,
            blocks = blocks.ifEmpty { mutableListOf(BlockDraft()) }
        )
        carryMetadata(base, parsed)
        return parsed
    }

    /** Matches entries by text (longest common subsequence) and copies their metadata. */
    private fun carryMetadata(base: NoteDraft, target: NoteDraft) {
        val old = base.blocks.flatMap { it.entries }
        val new = target.blocks.flatMap { it.entries }

        // Blocks are rewritten on save, but keeping their ids makes an unedited
        // round-trip compare equal to the original draft.
        target.blocks.forEachIndexed { index, block ->
            val source = base.blocks.getOrNull(index) ?: return@forEachIndexed
            block.id = source.id
            block.originNoteId = source.originNoteId
        }

        if (old.isEmpty() || new.isEmpty()) return

        if (old.size > MAX_DIFF_ENTRIES || new.size > MAX_DIFF_ENTRIES) {
            for (index in 0 until minOf(old.size, new.size)) {
                copyMetadata(old[index], new[index])
            }
            return
        }

        val table = Array(old.size + 1) { IntArray(new.size + 1) }
        for (i in old.indices.reversed()) {
            for (j in new.indices.reversed()) {
                table[i][j] = if (sameText(old[i], new[j])) {
                    table[i + 1][j + 1] + 1
                } else {
                    maxOf(table[i + 1][j], table[i][j + 1])
                }
            }
        }

        var i = 0
        var j = 0
        while (i < old.size && j < new.size) {
            when {
                sameText(old[i], new[j]) -> {
                    copyMetadata(old[i], new[j])
                    i++
                    j++
                }

                table[i + 1][j] >= table[i][j + 1] -> i++
                else -> j++
            }
        }
    }

    private fun sameText(a: EntryDraft, b: EntryDraft): Boolean = a.text.trim() == b.text.trim()

    private fun copyMetadata(from: EntryDraft, to: EntryDraft) {
        to.id = from.id
        to.priority = from.priority
        to.deadlineAt = from.deadlineAt
        to.allDay = from.allDay
    }
}
