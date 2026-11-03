package com.lwos.connect.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.lwos.connect.R
import com.lwos.connect.model.Shortcut

/**
 * A fixed-size grid of slots (count comes from the `max_shortcuts`
 * resource, bigger on tablets — see integers.xml) rather than a list
 * that grows with however many shortcuts exist. An empty slot shows
 * "+" and opens the add dialog; a configured one shows its label, taps
 * fire it, and long-press opens the edit/remove dialog — same gesture
 * vocabulary a real Stream Deck's software uses.
 */
class ShortcutGridAdapter(
    private var slotCount: Int,
    private val onFire: (Shortcut) -> Unit,
    private val onConfigure: (slotIndex: Int, existing: Shortcut?) -> Unit
) : RecyclerView.Adapter<ShortcutGridAdapter.ViewHolder>() {

    private val shortcutsBySlot = mutableMapOf<Int, Shortcut>()

    fun submitShortcuts(shortcuts: List<Shortcut>, newSlotCount: Int) {
        slotCount = newSlotCount
        shortcutsBySlot.clear()
        for (s in shortcuts) {
            if (s.slotIndex in 0 until slotCount) shortcutsBySlot[s.slotIndex] = s
        }
        notifyDataSetChanged()
    }

    inner class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val label: TextView = view.findViewById(R.id.slotLabel)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_shortcut_slot, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val shortcut = shortcutsBySlot[position]
        holder.label.text = shortcut?.label ?: "+"
        holder.label.alpha = if (shortcut == null) 0.4f else 1f

        holder.itemView.setOnClickListener {
            if (shortcut != null) onFire(shortcut) else onConfigure(position, null)
        }
        holder.itemView.setOnLongClickListener {
            onConfigure(position, shortcut)
            true
        }
    }

    override fun getItemCount(): Int = slotCount
}
