package app.lawnchair.areslauncher

import com.android.launcher3.LauncherSettings.Favorites
import com.android.launcher3.model.data.ItemInfo

/**
 * WP folders Phase 2 (design/wp-phase2-spike.md) — the PURE drag-membership classifier.
 *
 * All the data-loss risk in drag-membership lives in *classification* (a mis-classified drag writes
 * the wrong `container`, and a desktop landing with a stale cell is deleted by the loader — row-34).
 * The reviewers (MJ-1) rejected classifying by drop-index-vs-range because the live reflow moves the
 * range under the finger. This classifies by **dwell-target identity** instead, which the reflow
 * cannot invalidate, and it is a pure function with no side effects — so it is exhaustively testable
 * off a real gesture (via the `ares-wp-resolve-drag` channel) rather than depending on flaky
 * synthetic drags.
 *
 * Scope is deliberately NARROW: only the three membership cases Phase 1 could not express. Every
 * other drag (desktop reorder, overlay create/add, dwell-add onto a *collapsed* WP tile) already has
 * a verified path through `AresFolderDrop.kindOf`/`arm`, so this returns [Action.None] for them and
 * the caller falls back to that pipeline.
 */
object AresWpMembership {

    /** The resolved action for a membership drag. [None] = not a new WP case; use the old pipeline. */
    sealed interface Action {
        /** Not a WP-membership drag; the existing dwell/reorder pipeline owns it. */
        object None : Action

        /** A child of [folderId] dragged out of it onto the grid: extract to DESKTOP + legal cell. */
        data class Extract(val folderId: Int) : Action

        /** A child of [folderId] dragged among its siblings: reorder, folder-local rank only. */
        data class ReorderInFolder(val folderId: Int) : Action

        /** A desktop app dragged into [folderId]'s expanded children: add to the folder. */
        data class AddToFolder(val folderId: Int) : Action
    }

    /**
     * Resolve a membership drag from the dragged item, the item currently under the drag centre
     * ([target], null = empty grid area), and the inline-expanded WP folder id ([expandedFolderId],
     * -1 = none). Pure; no model or view side effects.
     *
     * The whole truth table:
     * - dragged is a CHILD of the open folder → [Action.ReorderInFolder] if the target is a sibling
     *   child OR the folder's own header tile (nudging a child up to the header keeps it in the
     *   folder, finding 5), else [Action.Extract] (it left the folder's own tiles). A spliced child
     *   is NEVER a folder-create target, which is what closes the finding-1 stray-overlay hazard.
     * - dragged is a DESKTOP app and the target is a child of the open folder → [Action.AddToFolder].
     * - everything else → [Action.None].
     */
    @JvmStatic
    fun resolve(dragged: ItemInfo, target: ItemInfo?, expandedFolderId: Int): Action {
        if (target != null && target.id == dragged.id) return Action.None
        if (expandedFolderId == -1) return Action.None

        // Case A: the dragged item is a child of the currently-expanded WP folder.
        if (dragged.container == expandedFolderId) {
            return when {
                // Over a sibling child -> reorder within the folder.
                target != null && target.container == expandedFolderId ->
                    Action.ReorderInFolder(expandedFolderId)
                // Over the folder's OWN tile (its header) -> keep it in the folder, do NOT extract.
                // The header's container is DESKTOP, so without this it would read as "out onto
                // another tile" and yank the child out -- surprising when a user nudges a child up
                // toward the header to move it to the top (adversarial review 2026-08-24, finding 5).
                target != null && target.id == expandedFolderId ->
                    Action.ReorderInFolder(expandedFolderId)
                // Out onto the desktop, another tile, or empty space -> take it out of the folder.
                else -> Action.Extract(expandedFolderId)
            }
        }

        // Case B: a desktop app dragged onto one of the folder's expanded children -> add it.
        if (dragged.container == Favorites.CONTAINER_DESKTOP &&
            target != null && target.container == expandedFolderId
        ) {
            return Action.AddToFolder(expandedFolderId)
        }

        // Everything else is not a NEW WP-membership action; the existing pipeline handles it.
        return Action.None
    }
}
