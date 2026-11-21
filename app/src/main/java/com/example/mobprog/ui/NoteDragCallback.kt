package com.example.mobprog.ui

import android.view.View
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.RecyclerView
import com.example.mobprog.R
import com.google.android.material.card.MaterialCardView

/**
 * Long-press a card to drag it onto another card. Nothing is reordered: the card
 * under the finger is outlined while hovering, and on release [onDrop] gets the two
 * positions so the screen can ask "Append X to Y?".
 */
class NoteDragCallback(
    private val isEnabled: () -> Boolean,
    private val onDrop: (from: Int, to: Int) -> Unit
) : ItemTouchHelper.SimpleCallback(
    ItemTouchHelper.UP or ItemTouchHelper.DOWN or ItemTouchHelper.START or ItemTouchHelper.END,
    0
) {

    private var hovered = RecyclerView.NO_POSITION
    private var highlighted = RecyclerView.NO_POSITION

    override fun isLongPressDragEnabled(): Boolean = isEnabled()

    override fun onMove(
        recyclerView: RecyclerView,
        viewHolder: RecyclerView.ViewHolder,
        target: RecyclerView.ViewHolder
    ): Boolean {
        val position = target.bindingAdapterPosition
        if (position == RecyclerView.NO_POSITION) return false
        highlight(recyclerView, position)
        hovered = position
        return false
    }

    override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) = Unit

    override fun onSelectedChanged(viewHolder: RecyclerView.ViewHolder?, actionState: Int) {
        super.onSelectedChanged(viewHolder, actionState)
        if (actionState == ItemTouchHelper.ACTION_STATE_DRAG && viewHolder != null) {
            (viewHolder.itemView as? MaterialCardView)?.apply {
                cardElevation = dp(this, 8)
                alpha = 0.9f
            }
        }
    }

    override fun clearView(recyclerView: RecyclerView, viewHolder: RecyclerView.ViewHolder) {
        super.clearView(recyclerView, viewHolder)
        (viewHolder.itemView as? MaterialCardView)?.apply {
            cardElevation = dp(this, 1)
            alpha = 1f
        }
        clearHighlight(recyclerView)
        val from = viewHolder.bindingAdapterPosition
        val to = hovered
        hovered = RecyclerView.NO_POSITION
        if (from != RecyclerView.NO_POSITION && to != RecyclerView.NO_POSITION && from != to) {
            onDrop(from, to)
        }
    }

    private fun highlight(recyclerView: RecyclerView, position: Int) {
        if (highlighted == position) return
        clearHighlight(recyclerView)
        val card = recyclerView.findViewHolderForAdapterPosition(position)?.itemView as? MaterialCardView
            ?: return
        card.strokeWidth = dp(card, 2).toInt()
        card.strokeColor = ContextCompat.getColor(card.context, R.color.color_primary)
        highlighted = position
    }

    private fun clearHighlight(recyclerView: RecyclerView) {
        if (highlighted == RecyclerView.NO_POSITION) return
        val card = recyclerView.findViewHolderForAdapterPosition(highlighted)?.itemView as? MaterialCardView
        card?.strokeWidth = 0
        highlighted = RecyclerView.NO_POSITION
    }

    private fun dp(view: View, value: Int): Float =
        value * view.resources.displayMetrics.density
}
