package app.lawnchair.areslauncher

import android.util.Log
import android.view.View
import com.android.launcher3.DropTarget
import com.android.launcher3.Launcher
import com.android.launcher3.Utilities
import com.android.launcher3.folder.Folder
import com.android.launcher3.folder.FolderIcon
import com.android.launcher3.model.data.FolderInfo
import com.android.launcher3.model.data.ItemInfo
import kotlin.math.hypot

/**
 * **Dwell to drop in** — holding an icon still over a folder makes that folder a drop target (§4,
 * §17, §18).
 *
 * ## Why a dwell, and not a plain hover
 *
 * §4 specifies **live reflow**: the grid physically repacks around the finger continuously, which
 * is the signature Windows Phone feel. That is fundamentally at odds with "hover over a folder to
 * drop into it" — the folder is being *pushed aside* by the very drag approaching it, so there is
 * no stable hover target by construction.
 *
 * Put to the user as a product call, their answer was a dwell:
 *
 * > *"hold still over a folder for roughly half a second, the reflow freezes, the folder highlights
 * > as a target, and releasing drops the icon inside."*
 *
 * So the reflow is unchanged everywhere else, and a folder drop becomes a deliberate act rather
 * than something that happens by accident mid-drag. [DWELL_MS] is the one knob.
 *
 * ## One mechanism, two sources, two resolutions
 *
 * §17's rule is that an operation with several entry points must be *one implementation*, because
 * parallel ones drift. Two drag pipelines reach this object, and they could not be less alike:
 *
 *  - **A drag inside the home grid** is an [androidx.recyclerview.widget.ItemTouchHelper] reorder
 *    and never enters `DragController` at all. It feeds this from
 *    [AresHomeReorder.Callback.onChildDraw].
 *  - **A drag from the app list, the widget picker or out of an open folder** is a real
 *    `DragController` drag. It feeds this from `Workspace.onDragOver`.
 *
 * Everything after the position update — target hit-testing, the dwell timer, the highlight, and
 * the write — is shared, so a folder behaves identically regardless of where the icon came from.
 * That was an explicit requirement, not an optimisation.
 *
 * ## Hard constraint: stock's folder drop path is a live crash here
 *
 * `FolderIcon.onDragEnter` opens with
 *
 * ```java
 * CellLayoutLayoutParams lp = (CellLayoutLayoutParams) getLayoutParams();
 * CellLayout cl = (CellLayout) getParent().getParent();
 * ```
 *
 * Our folder icons are RecyclerView rows, so both casts throw. Routing drags there would convert
 * today's silent no-op into a crash (design/strategy-d-dead-paths.md). The highlight is therefore
 * ours ([AresHomeListView.setFolderDropTarget]) and the write goes through
 * [Folder.addFolderContent], which touches only the folder's *internal* `CellLayout` — that one is
 * real under Strategy D and stock's folder machinery is intact behind it.
 *
 * `Launcher.removeItem` is likewise unusable: its `Workspace.getViewByItemId` walks `CellLayout`
 * children and can never find our rows. The adapter is asked directly instead, the same route
 * [AresFolderEdit.Session.removeFromFolder] already had to take.
 */
object AresFolderDrop {

    private const val TAG = "AresFolderDrop"

    /**
     * How long the drag must hold still over a folder before it becomes the drop target.
     *
     * The user described "roughly half a second". Stock's comparable delays bracket it — the
     * spring-load open is `ON_OPEN_DELAY = 800`, the folder's own drag-exit close is 400 — and
     * 500ms sits between a flick past a folder and a wait that feels like the launcher has hung.
     * One constant, expected to be tuned in the hand.
     */
    const val DWELL_MS = 500L

    /**
     * How far the drag may drift while dwelling before the timer restarts.
     *
     * Not zero: a real finger jitters by a pixel or two even when the user believes it is still,
     * and a strict test would make the dwell unreachable on hardware while passing under synthetic
     * input — the worst possible split between the device and the harness.
     */
    private const val DWELL_SLOP_PX = 18f

    /** The grid the current drag is over, or null when nothing is being tracked. */
    private var grid: AresHomeListView? = null

    /** Item being dragged. Held so hit-testing can exclude it and the drop can resolve it. */
    private var dragged: ItemInfo? = null

    /** Holder container currently under the drag point, whether or not the dwell has elapsed. */
    private var candidate: View? = null

    /** [candidate]'s model item. */
    private var candidateInfo: ItemInfo? = null

    /** Where the drag was when the dwell timer was last armed. */
    private var anchorX = 0f
    private var anchorY = 0f

    /** True once the dwell has elapsed: the reflow is frozen and the target is highlighted. */
    private var armed = false

    private val dwellElapsed = Runnable { arm() }

    /**
     * Reports where the drag currently is, in [list]'s own coordinate space.
     *
     * Idempotent and cheap: it is called on every frame of an in-grid drag and every move event of
     * an external one. It only restarts the timer when the answer actually changes, which is what
     * makes "held still" mean what it says.
     */
    @JvmStatic
    fun onDragPoint(list: AresHomeListView, item: ItemInfo?, x: Float, y: Float) {
        if (item == null) {
            clear()
            return
        }
        if (grid !== list || dragged !== item) {
            clear()
            grid = list
            dragged = item
        }

        val view = list.dropCandidateUnder(x, y, item.id)
        val info = view?.let { list.aresAdapter.itemAt(list.getChildAdapterPosition(it)) }
        if (view == null || info == null || kindOf(info, item) == Kind.NONE) {
            clearTarget()
            return
        }

        if (info !== candidateInfo) {
            // Logged on change only -- this runs on every frame of a drag, so per-frame logging is
            // not free. It earns its place because the failure it exposes is otherwise invisible: a
            // drop point mapped into the wrong coordinate space does not throw, it just never finds
            // a tile, and the whole feature then looks like it "does not work" with nothing
            // anywhere saying why. That is exactly how this was built wrong twice.
            Log.d(TAG, "dwell target: (${x.toInt()},${y.toInt()}) -> item ${info.id}")
            // A different tile: start counting again from here.
            clearTarget()
            candidate = view
            candidateInfo = info
            restart(list, x, y)
            return
        }
        // Same tile. Only a real move restarts the count -- see DWELL_SLOP_PX.
        if (!armed && hypot(x - anchorX, y - anchorY) > DWELL_SLOP_PX) {
            restart(list, x, y)
        }
    }

    /**
     * True while the drag is over a tile it could drop **into**, whether or not the dwell has
     * elapsed yet.
     *
     * This, not [isArmed], is what suspends the reflow. The dwell has to be *reachable* before it
     * can be satisfied: under live reflow the folder is swapped aside on the very move that brings
     * the drag onto it, so a freeze that waited for the dwell would be waiting on something that
     * can never happen. Entering the tile suspends the swap; leaving it resumes, and the grid
     * catches up in a single step.
     */
    @JvmStatic
    fun isFrozen(): Boolean = candidateInfo != null

    /** True once the dwell has elapsed: the target is highlighted and a release drops into it. */
    @JvmStatic
    fun isArmed(): Boolean = armed

    /** Forgets everything about the current drag. Safe to call at any time. */
    @JvmStatic
    fun cancel() = clear()

    private fun restart(list: AresHomeListView, x: Float, y: Float) {
        anchorX = x
        anchorY = y
        list.removeCallbacks(dwellElapsed)
        list.postDelayed(dwellElapsed, DWELL_MS)
    }

    private fun arm() {
        val list = grid ?: return
        val view = candidate ?: return
        armed = true
        list.setFolderDropTarget(view)
        Log.i(TAG, "dwell elapsed on ${candidateInfo?.id}; reflow frozen, target armed")
    }

    private fun clearTarget() {
        grid?.removeCallbacks(dwellElapsed)
        if (armed) grid?.setFolderDropTarget(null)
        armed = false
        candidate = null
        candidateInfo = null
    }

    private fun clear() {
        clearTarget()
        grid = null
        dragged = null
    }

    /** What dwelling on [target] with [source] in hand would do. */
    private enum class Kind { NONE, ADD }

    /**
     * Whether [target] can take [source], and how.
     *
     * `Folder.willAccept` is stock's own predicate for "may live inside a folder" — apps, deep
     * shortcuts and app pairs, never widgets — so the question is asked with the same rule stock
     * uses rather than a second one that could disagree with it. It is also what keeps the freeze
     * out of the way of ordinary reordering: dragging a widget, or a folder, past a folder is
     * never eligible, so the reflow is never suspended for it.
     *
     * Dwelling one *icon* on another to create a new folder is the third dead behaviour the
     * Strategy D audit found, and it is deliberately not here yet — it needs a new `FolderInfo`, an
     * inflated `FolderIcon` and both items re-parented, which is a materially bigger write than
     * moving one item into a container that already exists. It joins this table when it lands, so
     * that both resolutions share one dwell rather than growing a second one.
     */
    private fun kindOf(target: ItemInfo, source: ItemInfo): Kind {
        if (target.id == source.id) return Kind.NONE
        if (!Folder.willAccept(source)) return Kind.NONE
        if (target !is FolderInfo) return Kind.NONE
        return if (FolderInfo.willAcceptItemType(source.itemType)) Kind.ADD else Kind.NONE
    }

    /**
     * Completes a drop that the dwell armed, moving [item] into the folder it locked onto.
     *
     * ## Why this resolves against the armed target rather than re-hit-testing the drop point
     *
     * There is no usable drop point at either call site. In the grid, this is reached from
     * `ItemTouchHelper`'s `clearView`, which fires at the **end of the settle animation** — the
     * dragged view's translation has already been animated back to zero by then, so its position
     * describes where the tile landed, not where the finger let go. Externally,
     * `DragController.drop` calls `onDragExit` on the target *before* `onDrop`, so the arm cannot
     * be torn down on exit either.
     *
     * The armed target is the sound answer regardless: arming requires the drag to have held still
     * over one tile, and any move onto a different tile or off the grid disarms it in
     * [onDragPoint]. What is worth re-checking is that the tile is still *there* — a rebind or a
     * model update can retire a holder mid-drag — and that is what the position lookup below does.
     *
     * @return true when the item was consumed into a folder, so the caller must not also place it
     *   on the grid.
     */
    @JvmStatic
    fun commitDrop(launcher: Launcher, item: ItemInfo): Boolean {
        if (!armed) return false
        val list = grid
        val view = candidate
        val info = candidateInfo
        if (list == null || view == null || info == null || view.parent !== list) {
            Log.i(TAG, "armed target went away before the drop; declined")
            clear()
            return false
        }
        if (list.aresAdapter.itemAt(list.getChildAdapterPosition(view)) !== info) {
            Log.i(TAG, "armed target ${info.id} no longer binds that holder; declined")
            clear()
            return false
        }

        val done = when (kindOf(info, item)) {
            Kind.ADD -> addToFolder(launcher, list, view, info as FolderInfo, item)
            Kind.NONE -> false
        }
        clear()
        return done
    }

    /**
     * Files [item] into the existing folder [folderInfo].
     *
     * [Folder.addFolderContent] is the whole write: it inserts into the `FolderInfo`, has
     * `FolderGridOrganizer` assign a legal rank and in-folder cell, persists with
     * `addOrMoveItemInDatabase` — a *move*, since the item already owns a row — re-ranks the rest
     * through `updateItemLocationsInDatabaseBatch`, and refreshes the icon's preview. Everything it
     * touches is folder-internal, where the `CellLayout` is real.
     *
     * The desktop half is ours: drop the row from the adapter and renumber what is left, so the
     * grid packs closed over the gap exactly as it does for any other removal.
     *
     * Posted rather than run inline. In the in-grid case this is reached from
     * [AresHomeReorder.Callback.clearView], which `ItemTouchHelper` calls from inside a recover
     * animation's end callback — notifying a removal there risks mutating the adapter while
     * RecyclerView is mid-frame, and the cost of waiting a frame is invisible.
     */
    private fun addToFolder(
        launcher: Launcher,
        list: AresHomeListView,
        targetView: View,
        folderInfo: FolderInfo,
        item: ItemInfo,
    ): Boolean {
        val folder = folderOf(targetView) ?: run {
            Log.e(TAG, "folder icon ${folderInfo.id} has no Folder view; drop declined")
            return false
        }
        list.post {
            folder.addFolderContent(item, folderInfo.getContents().size, true)
            list.aresAdapter.removeItems { it.id == item.id }
            AresHomeReorder.persistOrder(launcher, list.aresAdapter.snapshot())
            Log.i(TAG, "moved item ${item.id} into folder ${folderInfo.id}")
        }
        return true
    }

    /** The [Folder] behind a holder container hosting a [FolderIcon], or null. */
    private fun folderOf(container: View): Folder? =
        ((container as? android.view.ViewGroup)?.getChildAt(0) as? FolderIcon)?.folder

    /**
     * Feeds a `DragController` drag (app list, widget picker, or an app leaving an open folder)
     * into the same dwell the in-grid reorder uses.
     *
     * ## It resolves against the TOUCH POINT, not `DragObject.getVisualCenter()`
     *
     * Stock resolves every drop against the visual centre, so that was the obvious choice. It is
     * wrong on this launcher, and the difference is not small — measured on the emulator during an
     * app-list drag, with the finger at DragLayer `(234,508)`:
     *
     * ```
     * ext dl=(147,280) list@dl=Rect(133,426 - 947,2093) scale=0.78 -> local=(18,-186)
     * ```
     *
     * `getVisualCenter` answered **228px above and 87px left of the finger** — off the top of the
     * grid entirely, so every hit-test returned nothing and the dwell could never arm. The drag
     * view is drawn *at* the finger (confirmed from a screenshot taken mid-drag), so the visual
     * centre is not describing what is on screen: `DragPreviewProvider` derives the registration
     * point and drag region from a stock icon-above-label cell, and our app-list rows are Niagara
     * rows — icon left, label right — so the geometry it computes does not match the view.
     *
     * **This applies to any drop-point resolution on this surface**, not only the dwell.
     *
     * ## And `DragObject.x`/`y` is in the DROP TARGET's space, not the DragLayer's
     *
     * The second half of the same bug, and it looks identical from the outside.
     * `DragController.findDropTarget` runs the touch point through
     * `mapCoordInSelfToDescendant(target.getDropView(), ...)` before storing it, so by the time
     * `Workspace.onDragOver` sees it, it is already in **Workspace** coordinates — the workspace
     * scale and page scroll are taken out of it. Mapping it a second time from the DragLayer
     * produced `(20,-184)`, within two pixels of the visual-centre answer, which is exactly the
     * kind of near-miss that reads as "the mapping is roughly right, something else is wrong".
     *
     * `Workspace` is the only drop target that calls this, so the space is known. The mapping still
     * goes through `Utilities.mapCoordInSelfToDescendant` rather than arithmetic on `getLeft()`: it
     * is the same primitive stock's own `mapPointFromDropLayout` uses, and a hand-rolled version
     * that is subtly wrong does not fail loudly — it simply picks the wrong tile, or none.
     */
    @JvmStatic
    fun onExternalDragOver(launcher: Launcher, d: DropTarget.DragObject) {
        val list = launcher.workspace?.aresHomeList ?: return
        val local = toListSpace(launcher, list, d.x.toFloat(), d.y.toFloat())
        onDragPoint(list, d.dragInfo, local[0], local[1])
    }

    /**
     * Maps a point in **Workspace** coordinates — which is what `DropTarget.DragObject.x`/`y`
     * carries once the Workspace is the drop target — into [list]'s own coordinates.
     */
    @JvmStatic
    fun toListSpace(launcher: Launcher, list: AresHomeListView, x: Float, y: Float): FloatArray {
        val coord = floatArrayOf(x, y)
        val workspace = launcher.workspace ?: return coord
        Utilities.mapCoordInSelfToDescendant(list, workspace, coord)
        return coord
    }
}
