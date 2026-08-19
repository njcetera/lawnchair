package app.lawnchair.areslauncher

import android.util.Log
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.RecyclerView
import com.android.launcher3.AbstractFloatingView
import com.android.launcher3.Launcher
import com.android.launcher3.LauncherSettings.Favorites
import com.android.launcher3.WorkspaceLayoutManager
import com.android.launcher3.model.data.ItemInfo

/**
 * AresLauncher §4 — drag-to-reorder for the vertical home list.
 *
 * Deliberately an [ItemTouchHelper] rather than any of `CellLayout`'s drag machinery: reordering a
 * flat list needs none of `findNearestArea()`/`mOccupied`/`performReorder()`'s 2D occupancy math,
 * which is the core scope reduction Strategy D was chosen for
 * (design/vertical-home-strategies.md §4).
 */
object AresHomeReorder {

    private const val TAG = "AresHomeReorder"

    /**
     * Writes the list's current visual order to the model as `rank`.
     *
     * ## Why `rank`, and why the item's grid position is deliberately left alone
     *
     * `rank` is a first-class flat ordinal already in the schema, independent of grid coordinates,
     * and already the authoritative order for folders, app pairs and the Hotseat. See
     * design/component-verification-3.md §2.
     *
     * That doc recommended `moveItemInDatabase(item, container, screenId, 0, 0)`, reasoning that
     * cellX/cellY are inert for a list. **That is true for folder contents and false for
     * `CONTAINER_DESKTOP`, and following it deleted the entire home screen on the next reboot.**
     * Folder children live outside the grid, but desktop items are occupancy-checked by the loader:
     * writing every row to cell (0,0) made them collide, and `LoaderCursor` discarded each one:
     *
     * ```
     * cell(0,0) span(1,1) rank=2 ... into cell (0-0:0,0,1,1) already occupied
     * Item position overlap
     * ```
     *
     * So the position is left exactly as the model already had it — each item keeps the distinct,
     * in-bounds cell it was placed at, which the loader already accepts — and only `rank` changes.
     * [com.android.launcher3.model.ModelWriter.updateItemInDatabase] is the right call for that: it
     * writes the item's *current* fields via `ItemInfo.onAddToDatabase`, which includes RANK, so
     * mutating `rank` alone and re-writing leaves container/screen/cellX/cellY untouched.
     *
     * Our rendering never reads cellX/cellY/screenId — `Workspace.addInScreen` redirects every
     * `CONTAINER_DESKTOP` item into this list regardless of them — so they remain pure bookkeeping
     * that exists only to keep the loader happy.
     *
     * ## Why writes are logged
     *
     * A discarded model write is **silent** — no exception, just a debug log — so data loss looks
     * exactly like success. A `ModelTask` captures the load generation when it is created and drops
     * itself if a reload intervened; a writer used before the first load completes carries the
     * sentinel `mLoadId = -1` and *every* write through it is doomed. That was the Phase 8 widget
     * failure. Reorder is user-initiated long after the first load, so it is not in that window, but
     * the logging is here so that if it ever does happen it is visible rather than mysterious. See
     * design/model-persistence.md.
     */
    fun persistOrder(launcher: Launcher, items: List<ItemInfo>) {
        // Fetched at write time rather than cached, so a writer is never held across a reload.
        val writer = launcher.modelWriter
        var written = 0

        items.forEachIndexed { index, info ->
            if (info.rank == index) return@forEachIndexed
            if (info.container != Favorites.CONTAINER_DESKTOP) {
                // Only desktop rows belong to this list's ordering; anything else here would be a
                // bug elsewhere, and rewriting it would corrupt it.
                Log.w(TAG, "skipping non-desktop item id=${info.id} container=${info.container}")
                return@forEachIndexed
            }
            if (info.screenId == WorkspaceLayoutManager.EXTRA_EMPTY_SCREEN_ID ||
                info.screenId == WorkspaceLayoutManager.EXTRA_EMPTY_SCREEN_SECOND_ID
            ) {
                // ItemInfo.onAddToDatabase throws on these, which would take down the write pass.
                Log.e(TAG, "skipping item id=${info.id} on sentinel screen ${info.screenId}")
                return@forEachIndexed
            }

            // Only rank changes. Position is deliberately untouched -- see the doc above.
            info.rank = index
            writer.updateItemInDatabase(info)
            written++
        }

        Log.i(TAG, "persistOrder: ${items.size} rows, $written rank writes enqueued")
    }

    /**
     * [ItemTouchHelper.Callback] for the home list.
     *
     * Vertical drag only, no swipe-to-dismiss — removing an item is the long-press menu's job, and
     * a swipe gesture here would collide with [AresPaneSwipeController], which claims horizontal
     * drags anywhere on the home screen.
     */
    class Callback(
        private val launcher: Launcher,
        private val list: AresHomeListView,
    ) : ItemTouchHelper.Callback() {

        override fun isLongPressDragEnabled(): Boolean = true

        override fun isItemViewSwipeEnabled(): Boolean = false

        override fun getMovementFlags(
            recyclerView: RecyclerView,
            viewHolder: RecyclerView.ViewHolder,
        ): Int = makeMovementFlags(ItemTouchHelper.UP or ItemTouchHelper.DOWN, 0)

        override fun onMove(
            recyclerView: RecyclerView,
            viewHolder: RecyclerView.ViewHolder,
            target: RecyclerView.ViewHolder,
        ): Boolean = list.aresAdapter.moveItem(
            viewHolder.bindingAdapterPosition,
            target.bindingAdapterPosition,
        )

        override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) = Unit

        override fun onSelectedChanged(viewHolder: RecyclerView.ViewHolder?, actionState: Int) {
            super.onSelectedChanged(viewHolder, actionState)
            if (actionState != ItemTouchHelper.ACTION_STATE_DRAG) return

            list.setReorderInProgress(true)

            // A long-press on a row also fires BubbleTextView.startLongPressAction(), which opens
            // PopupContainerWithArrow -- that behaviour is required and deliberately kept. Once the
            // finger actually moves and this becomes a reorder, the popup is stale, so close it.
            // This mirrors stock Launcher3, where long-press shows the popup and dragging from it
            // moves the icon.
            AbstractFloatingView.closeAllOpenViews(launcher)
        }

        override fun clearView(recyclerView: RecyclerView, viewHolder: RecyclerView.ViewHolder) {
            super.clearView(recyclerView, viewHolder)
            list.setReorderInProgress(false)
            persistOrder(launcher, list.aresAdapter.snapshot())
        }
    }
}
