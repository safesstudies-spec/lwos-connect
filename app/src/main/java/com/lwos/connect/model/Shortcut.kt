package com.lwos.connect.model

import kotlinx.serialization.Serializable

/**
 * One button in the Shortcuts grid (see ShortcutsActivity) — a label
 * the user picks, and the shell command that runs on the target PC
 * when it's tapped. Deliberately just a free-form command string, not
 * a fixed menu of built-in actions, since "customizable, like a Stream
 * Deck" was the whole point: whatever you could type in a terminal on
 * the PC, a shortcut can run there.
 *
 * [slotIndex] is the grid position (0-based) so slots stay in the same
 * place across edits rather than reordering based on creation order.
 */
@Serializable
data class Shortcut(
    val id: String,
    val slotIndex: Int,
    val label: String,
    val command: String
)
