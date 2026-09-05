package app.lawnchair.areslauncher

import com.android.launcher3.DropTarget
import com.android.launcher3.Launcher
import com.android.launcher3.dragndrop.DragController
import com.android.launcher3.dragndrop.DragOptions
import com.android.launcher3.model.data.ItemInfo

/**
 * Makes the home grid open a space for an item that is still being held (§C4).
 *
 * ## The gap this closes
 *
 * The grid has exactly one reflow, and it is driven by `ItemTouchHelper`:
 * [AresHomeReorder.Callback.onMove] calls `AresHomeAdapter.moveItem`, RecyclerView animates, and a
 * tile visibly slides aside as the finger approaches it. That pipeline only exists for a drag that
 * *starts* on the grid.
 *
 * A drag out of an open folder is the other pipeline — a `DragController` drag, with the item in a
 * `DragView` and nothing in the adapter to move. Nobody was asking the grid to make room, so it did
 * not. Measured on emulator-5554 before this file existed, dragging an app out of a folder onto the
 * grid:
 *
 * ```
 * edit  : FolderIcon@20,195 | DoubleShadowBubbleTextView@280,195 | DoubleShadowBubbleTextView@540,195
 * mid   : FolderIcon@20,195 | DoubleShadowBubbleTextView@280,195 | DoubleShadowBubbleTextView@540,195
 * after : FolderIcon@20,195 | DoubleShadowBubbleTextView@280,195 | DoubleShadowBubbleTextView@540,195 | DoubleShadowBubbleTextView@800,195
 * ```
 *
 * Byte-identical while held; the fourth tile appears only on release. The owner's report exactly:
 * *"the home screen does not readjust around the app until release."*
 *
 * ## Why an adapter entry rather than a drawn preview
 *
 * The grid's position model is an **ordered sequence** — `rank` plus a footprint, no stored x/y
 * (§4) — and the packer is a pure function of it. So there is no way to express "leave a space
 * here" other than by putting something in the sequence. Drawing a ghost rectangle would be a
 * second, parallel layout model that agrees with the packer only by luck, which is the failure mode
 * this project has hit repeatedly.
 *
 * So the space is a real adapter entry that renders nothing — [AresHomeAdapter.showDropSlot]. The
 * reflow that follows is then *literally* the in-grid one: same `notifyItemMoved`, same RecyclerView
 * animation, same packer. Nothing here re-implements it.
 *
 * ## Scope, stated rather than implied
 *
 * Only drags carrying an item that **already has a database row** get a slot. That covers the case
 * §C4 is about — an app leaving an open folder — and deliberately excludes:
 *
 *  - the **widget picker**, which drags a `PendingAddItemInfo` with no row, no spans that survive
 *    the add, and a configure activity that may cancel the whole thing;
 *  - the **app list**, whose payload is an `AppInfo` with `id == NO_ID`. Two of those in flight
 *    would collide under the adapter's `setHasStableIds(true)`, and the conversion to a
 *    `WorkspaceItemInfo` does not happen until the drop.
 *
 * Widening it is possible and is not free; it is not what was reported and is not attempted here.
 */
object AresHomeDropPreview : DragController.DragListener {

    private var list: AresHomeListView? = null

    /** Held only to deregister in [onDragEnd]; never used to reach anything else. */
    private var host: Launcher? = null

    /**
     * Updates the gap for a `DragController` drag now over the grid.
     *
     * Called from `Workspace.onDragOver`, which is the only per-move hook such a drag offers —
     * the same one [AresFolderDrop.onExternalDragOver] uses, and for the same reason.
     */
    @JvmStatic
    fun onExternalDragOver(launcher: Launcher, d: DropTarget.DragObject) {
        val item = d.dragInfo ?: return
        // See "Scope" above.
        if (item.id == ItemInfo.NO_ID) return
        if (!AresWidgetAdd.isAresHome(launcher)) return
        val grid = launcher.workspace?.aresHomeList ?: return

        // A dwell that is aiming at a tile freezes the reflow so the target can be held still
        // (see AresFolderDrop.isFrozen). The gap is part of that reflow: sliding it about under a
        // finger that is trying to hold steady over a folder is exactly what the freeze exists to
        // stop. Deliberately leaves an already-open gap where it is rather than closing it -- the
        // dwell may be abandoned, and a hole that blinks out and back is worse than one that waits.
        if (AresFolderDrop.isFrozen()) return

        val local = AresFolderDrop.toListSpace(launcher, grid, d.x.toFloat(), d.y.toFloat())

        var index = grid.dropIndexAt(local[0], local[1])

        // Never displace the tile the finger is INSIDE when a dwell over it could arm (row 32).
        // dropIndexAt's case 1 takes the hovered tile's own index — right for a RELEASE, wrong for
        // the live mover: opening the gap AT the aim shoves the aim target aside (measured: the
        // icon slid 263px out from under a still finger), so the CREATE dwell structurally never
        // completed — the freeze only engages once a dwell ARMS, and the dwell needs the icon to
        // still be there. Parking the slot one index PAST the hovered tile keeps every tile before
        // it — the aim included — exactly where it is, which is the external-pipeline mirror of
        // the in-grid rule that displacement "must not fire at the point they are aiming for".
        val aim = grid.dropCandidateUnder(local[0], local[1], item.id)
        if (aim != null) {
            val position = grid.getChildAdapterPosition(aim)
            val info = if (position != androidx.recyclerview.widget.RecyclerView.NO_POSITION) {
                grid.aresAdapter.itemAt(position)
            } else {
                null
            }
            if (info != null && AresFolderDrop.couldAcceptDwell(info, item)) {
                index = position + 1
            }
        }

        if (list !== grid) {
            clear()
            list = grid
            host = launcher
            // Idempotent by contract: DragController holds an ArrayList and this is only reached
            // when `list` was null or a different grid, both of which run through clear() first.
            launcher.dragController.addDragListener(this)
            grid.aresAdapter.showDropSlot(index)
            return
        }
        grid.aresAdapter.moveDropSlot(index)
    }

    /**
     * Closes the gap and reports the index it held, or -1 when there was none.
     *
     * Called first thing in [AresHomeDrop.handleExternalDrop] so the slot can never be present
     * while anything persists, and so the item lands **where the gap was** — which is what the user
     * has been watching for the whole drag, and is a stricter answer than re-deriving a drop index
     * from the release point.
     */
    @JvmStatic
    fun take(): Int {
        val at = list?.aresAdapter?.clearDropSlot() ?: -1
        clear()
        return at
    }

    /** Closes the gap without reading it. Safe at any time, and idempotent. */
    @JvmStatic
    fun clear() {
        list?.aresAdapter?.clearDropSlot()
        list = null
        host?.dragController?.removeDragListener(this)
        host = null
    }

    override fun onDragStart(dragObject: DropTarget.DragObject?, options: DragOptions?) = Unit

    /**
     * The backstop, and the reason this is a `DragListener` at all.
     *
     * A drop on the grid is handled by [AresHomeDrop], but most drags end some other way: cancelled
     * by a call or the shade, released over the drop-target bar, dropped back into the folder it
     * came from, or committed into a folder by a dwell. `DragController` calls this for every one
     * of them, so there is no outcome that can leave a hole in the grid.
     */
    override fun onDragEnd() = clear()
}
