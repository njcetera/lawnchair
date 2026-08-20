package app.lawnchair.areslauncher

import android.graphics.Rect
import android.util.Log
import android.view.View
import com.android.launcher3.Launcher
import com.android.launcher3.Utilities
import com.android.launcher3.folder.Folder
import com.android.launcher3.folder.FolderIcon
import com.android.launcher3.model.data.FolderInfo
import com.android.launcher3.model.data.ItemInfo

/**
 * The **folder half of the dwell** (§18): holding an icon over a folder opens it, mid-drag, so the
 * position *inside* the folder can be chosen before anything is committed.
 *
 * > *"Holding an app over a folder while in edit mode will open the folder to place the app in so
 * > the user can select the location to drop it off - potentially they could pull it back out of
 * > the folder before ever letting go and the app should close and the user can place it back in
 * > the home page, and repeat."*
 *
 * and, reported again after the first version shipped as a highlight-and-append:
 *
 * > *"when in edit mode and holding an app to move over to a folder, the animation is weird... I
 * > think it should open the folder so you can choose where in the folder it can go. and again, I
 * > need to be able to pull it out of the folder as well even if I don't let go."*
 *
 * ## The three properties that make this different from "spring-loaded open"
 *
 *  1. **Reversible.** The open/close is a *state that tracks the pointer*, not a one-way
 *     transition. Leaving the folder closes it again ([EXIT_CLOSE_MS]) and the dwell can reopen it,
 *     any number of times, within one uninterrupted drag. A one-way open would trap the user
 *     mid-drag, which is worse than not building it at all.
 *  2. **Nothing commits until release.** Opening writes nothing; moving the slot writes nothing;
 *     closing writes nothing. [commit] is reached only from the lift.
 *  3. **The position is chosen, not appended.** The empty slot follows the finger through stock's
 *     own `realTimeReorder`, and the release inserts at exactly that rank.
 *
 * ## One preview, both drag pipelines
 *
 * [AresFolderDrop] already feeds one dwell from two completely different sources — an in-grid
 * `ItemTouchHelper` reorder, and a `DragController` drag from the app list or another folder — and
 * §17's rule is that an interaction has one implementation. So this is driven the same way from
 * both, in the *list's* coordinate space, and stock's `Folder.onDragOver` / `onDragExit` / `onDrop`
 * are deliberately left off the path: [Folder.aresBeginPreviewDrag] unregisters the folder as a
 * `DropTarget` for the duration, precisely so that an app-list drag cannot end up in stock's
 * external-drop branch while an in-grid drag ends up in ours.
 *
 * That also keeps `FolderIcon.onDragEnter` unreachable, which is a hard constraint rather than a
 * preference: its first two lines cast to `CellLayoutLayoutParams` and `CellLayout`, and our folder
 * icons are RecyclerView rows (design/strategy-d-dead-paths.md).
 */
object AresFolderPreview {

    private const val TAG = "AresFolderPreview"

    /**
     * How long the drag may be outside the open folder before it closes again.
     *
     * Stock's own `Folder.ON_EXIT_CLOSE_DELAY`, deliberately: the user described "a second or so"
     * for the related drag-*out* case, but that was intent rather than a number, and starting from
     * the value the platform has shipped for years is the honest default. One knob, expected to be
     * tuned in the hand.
     */
    const val EXIT_CLOSE_MS = 400L

    /**
     * How long the drag must settle on a new slot before the icons shuffle to make room.
     *
     * Same constant the open-folder reorder already uses ([AresEditMotion.FOLDER_REORDER_DELAY_MS]),
     * because it is the same gesture on the same surface. Without a wait the arrangement churns on
     * every frame the finger crosses a cell boundary; with stock's 250ms it holds still and then
     * jumps.
     */
    private const val SLOT_SETTLE_MS = AresEditMotion.FOLDER_REORDER_DELAY_MS.toLong()

    private var launcher: Launcher? = null
    private var list: AresHomeListView? = null
    private var folder: Folder? = null
    private var folderIcon: FolderIcon? = null

    /** The rank the settle timer is counting towards, or -1 when nothing is pending. */
    private var pendingRank = -1

    /** True once the drag has been inside the open folder at least once. See [onDragPoint]. */
    private var entered = false

    /** Last reported "on the folder" answer, so the decision is logged on the transition only. */
    private var wasOn = true

    /** The tile the folder was opened from, in DragLayer coordinates. */
    private val opener = Rect()

    /**
     * DragLayer-space region that still counts as "on the folder" before it has been reached: the
     * folder's own rect unioned with [opener]. Rebuilt on every drag point — see [onDragPoint].
     */
    private val approach = Rect()

    private val settleSlot = Runnable {
        val f = folder
        if (f != null && pendingRank >= 0) f.aresMovePreviewSlot(pendingRank)
        pendingRank = -1
    }

    /** True while a folder is open as a drop preview. */
    @JvmStatic
    fun isOpen(): Boolean = folder != null

    /** The [FolderInfo] currently previewing, or null. */
    @JvmStatic
    fun openFolderInfo(): FolderInfo? = folder?.info

    /**
     * Opens the folder behind [icon] as a preview target.
     *
     * @return true when the folder actually opened, so the caller can fall back to the plain
     *   highlight ring if it did not. A folder can decline — one already open, an app-drawer
     *   folder, or a folder whose contents have gone — and a caller that assumed success would
     *   leave the drag with no target and no explanation.
     */
    @JvmStatic
    fun open(launcher: Launcher, list: AresHomeListView, icon: FolderIcon): Boolean {
        close()
        val f = icon.folder ?: return false
        if (f.isDestroyed) return false
        // BEFORE the folder opens, while the workspace is still at rest. See [captureMapping].
        this.launcher = launcher
        this.list = list
        if (!captureMapping(launcher, list)) {
            forget()
            return false
        }
        // CLAIMED BEFORE THE FOLDER IS ACTUALLY OPENED, and the order is load-bearing.
        //
        // `aresBeginPreviewDrag` adds the folder to the DragLayer, which lays the DragLayer out,
        // which makes `DragController.forceTouchMove()` re-deliver the current drag position --
        // synchronously, from inside this call. That re-entrant `Workspace.onDragOver` reaches
        // `AresFolderDrop.onDragPoint` again, and if this object still says "no folder open" it
        // takes the *grid* branch: measured on emulator-5554, it retargeted the dwell onto the tile
        // that happened to be under the finger, armed a drop ring on it while the folder was open
        // in front of it, and left `candidate` and `candidateInfo` describing different items.
        //
        // Claiming first makes the re-entrant call take the preview branch, where it finds a folder
        // with no bounds yet and holds -- which is exactly the right answer.
        this.folder = f
        this.folderIcon = icon
        pendingRank = -1
        entered = false
        wasOn = true
        if (!f.aresBeginPreviewDrag()) {
            Log.i(TAG, "folder ${f.info.id} declined to open for the drag")
            forget()
            return false
        }
        // THE SAME OPEN FOLDER THE MODE ALREADY HAS, not a second presentation of one.
        //
        // AresFolderEdit is what puts the frost box, the × and the ! on a folder's apps while edit
        // mode is on. A dwell opens the folder from inside that same mode, so it has to look the
        // same: reported as "the folder opens (yay) but the apps in the folder don't have the
        // blur". Attaching the existing session is the fix rather than drawing a frost of our own
        // -- the box is the visible cell boundary a corner-anchored badge is anchored to, and two
        // implementations of it would drift the moment either is restyled.
        //
        // It detaches itself when the folder leaves the window, which is exactly what closing the
        // preview does, so there is no unwind to get wrong here.
        if (list.isEditMode()) {
            AresFolderEdit.attach(launcher, icon)
        }

        // AFTER the folder is in the DragLayer, so the ghost is added over it. Its elevation makes
        // that independent of add order, but there is no reason to rely on both.
        AresDragGhost.show(launcher, list.draggedTile())
        Log.i(TAG, "opened folder ${f.info.id} mid-drag; slot at rank ${f.aresPreviewRank()}")
        return true
    }

    /**
     * Puts [opener] in step with the tile the folder was opened from, in DragLayer coordinates.
     *
     * Recomputed on every drag point rather than captured at open, because the tile's *layout*
     * position can still move under a rebind, and it costs two multiplications.
     *
     * The tile's corners are mapped from the **list's** space rather than the tile's own, so its
     * edit-mode scale is left out: the region wanted here is the cell the user is aiming at, not
     * the slightly smaller rectangle currently being drawn inside it.
     */
    private fun syncOpener() {
        opener.setEmpty()
        val tile = folderIcon?.parent as? View ?: return
        val topLeft = toDragLayerSpace(tile.left.toFloat(), tile.top.toFloat()) ?: return
        val left = topLeft[0].toInt()
        val top = topLeft[1].toInt()
        val bottomRight = toDragLayerSpace(tile.right.toFloat(), tile.bottom.toFloat()) ?: return
        opener.set(left, top, bottomRight[0].toInt(), bottomRight[1].toInt())
    }

    /**
     * Reports the drag position, in [AresHomeListView]'s own coordinates.
     *
     * ## "On the folder" is wider than the folder until the finger has been inside it once
     *
     * The folder does not open under the finger. `centerAboutIcon` clamps it to fit the screen, so
     * on a tile near the top it opens *below* the icon — measured on emulator-5554, the tile spans
     * y 195–427 and the folder that opened from it spans y 315–973, leaving the finger four pixels
     * above it. Testing the folder rect alone, the drag was outside from the instant it opened, the
     * close countdown started immediately, and the folder shut and reopened in a loop the user
     * could do nothing about.
     *
     * So until the drag has actually reached the folder, the region that counts is the **union of
     * the folder and the tile that opened it** — which is, by construction, the whole path from
     * where the finger already is to where it is going. Once it has been inside, only the folder
     * counts, because by then "outside" genuinely means leaving.
     *
     * @return true while the drag is on the open folder. False is the caller's cue to start
     *   counting down to a close — the countdown is [AresFolderDrop]'s, because that object owns
     *   every other timer in this interaction and two places arming timers on one gesture is how
     *   the state machine would drift.
     */
    @JvmStatic
    fun onDragPoint(x: Float, y: Float): Boolean {
        val f = folder ?: return false
        val point = toDragLayerSpace(x, y) ?: return false
        val px = point[0]
        val py = point[1]

        // Before any of the decisions below, and deliberately: the tile the finger is holding has
        // to keep up with it whatever the folder is or is not doing, including on the frames where
        // this method has nothing else to say.
        AresDragGhost.moveTo(px, py)

        // NOT YET LAID OUT. `animateOpen` adds the folder to the DragLayer and sets its layout
        // params, but its left/top/right/bottom stay at zero until the next layout pass -- measured
        // as `folder=0,0-0,0` on the frame the dwell armed, which made every point "outside", armed
        // the close countdown immediately, and put the folder in an open/close loop the user could
        // not break. There is nothing to aim at yet either, so hold everything until it has bounds.
        if (f.width <= 0 || f.height <= 0) {
            cancelSettle()
            return true
        }

        syncOpener()
        approach.set(f.left, f.top, f.right, f.bottom)
        if (!opener.isEmpty) approach.union(opener)

        val inFolder = px >= f.left && px < f.right && py >= f.top && py < f.bottom
        if (inFolder) entered = true
        val on = inFolder || (!entered && approach.contains(px.toInt(), py.toInt()))
        if (on != wasOn) {
            // On the transition only -- this runs on every frame of a drag. It earns its place
            // because every failure this interaction has had so far has been a *geometry* failure
            // that produced no error at all: a folder that closed while the finger had not moved,
            // and a slot that jumped to rank 0 the instant the folder opened. Neither is visible
            // anywhere without the numbers that decided it.
            wasOn = on
            Log.d(
                TAG,
                "drag ${if (on) "on" else "off"} folder ${f.info.id}: " +
                    "(${px.toInt()},${py.toInt()}) folder=${f.left},${f.top}-${f.right},${f.bottom} " +
                    "opener=$opener entered=$entered",
            )
        }
        if (!on) {
            // Leave the slot where it is. The user is on their way out, and shuffling the icons
            // behind them describes a drop that is no longer going to happen.
            cancelSettle()
            return false
        }
        if (!inFolder) {
            // On the way in, but not there yet. Nothing to aim at.
            cancelSettle()
            return true
        }

        val rank = f.aresRankNear(px - f.left, py - f.top)
        if (rank == f.aresPreviewRank()) {
            cancelSettle()
            return true
        }
        if (rank != pendingRank) {
            pendingRank = rank
            f.removeCallbacks(settleSlot)
            f.postDelayed(settleSlot, SLOT_SETTLE_MS)
        }
        return true
    }

    /**
     * Files [item] into the open folder at the previewed rank.
     *
     * `Folder.addFolderContent` is the whole write, and it is stock's: it inserts into the
     * `FolderInfo` at that rank, assigns the in-folder cell, persists with `addOrMoveItemInDatabase`
     * — a *move*, since the row already exists — re-ranks the rest and refreshes the icon preview.
     * Everything it touches is folder-internal, where the `CellLayout` is real.
     *
     * [Folder.aresPersistContentRanks] then force-writes the ranks. That is not belt-and-braces:
     * stock's batch skips any row whose *in-memory* rank already equals its index, and in-memory
     * ranks are not always what the database holds — measured on emulator-5554, a folder bound at
     * ranks 0,1 whose rows carried 5,6 accepted an item "at the end" as rank 2, which reads back
     * **first** after a reload. A chosen position that only holds until the next load is not a
     * chosen position.
     *
     * The folder is left **open** on purpose. §18 has a folder as a container you descend into:
     * having just placed an icon inside it, the user is *in* it, and tapping outside returns them
     * to the still-editing grid. It is also what stock does after a drop into a spring-loaded
     * folder.
     *
     * @return true when the item was consumed, so the caller must not also place it on the grid.
     */
    @JvmStatic
    fun commit(launcher: Launcher, item: ItemInfo): Boolean {
        val f = folder ?: return false
        val icon = folderIcon
        val grid = list
        val info = f.info
        if (!FolderInfo.willAcceptItemType(item.itemType)) {
            Log.i(TAG, "folder ${info.id} will not take item ${item.id}; drop declined")
            close()
            return false
        }
        cancelSettle()
        val rank = f.aresPreviewRank().coerceIn(0, info.getContents().size)

        // Before addFolderContent, which re-lays the folder out: the slot is about to hold a real
        // item, so the folder should measure by its occupied rows again from here or it keeps a
        // row of empty space it no longer needs.
        f.aresEndPreviewSizing()
        f.addFolderContent(item, rank, true)
        f.aresPersistContentRanks()
        Log.i(TAG, "dropped item ${item.id} into folder ${info.id} at rank $rank")

        // Posted for the reason spelled out on AresFolderDrop.addToFolder: in the in-grid case this
        // is reached from ItemTouchHelper's clearView, inside a recover animation's end callback,
        // and notifying adapter changes there mutates RecyclerView mid-frame. The model write above
        // is deliberately NOT posted -- a deferred write can be beaten to the row by the source
        // folder's own auto-collapse DELETE.
        grid?.post {
            grid.aresAdapter.removeItems { it.id == item.id }
            AresHomeReorder.persistOrder(launcher, grid.aresAdapter.snapshot())
        }

        // The mode continues inside the folder the user is now standing in (§18) -- wiggle and x
        // badges on its apps, exactly as if they had tapped it open.
        if (icon != null && grid?.isEditMode() == true) {
            AresFolderEdit.attach(launcher, icon)
        }

        // Forget the preview WITHOUT closing the folder: the drop is what ended the preview, and
        // the arrangement on screen is now the real one.
        forget()
        return true
    }

    /** Closes an uncommitted preview and forgets it. Safe to call at any time. */
    @JvmStatic
    fun close() {
        val f = folder ?: return
        cancelSettle()
        Log.i(TAG, "closing folder ${f.info.id}; nothing committed")
        f.aresEndPreviewDrag()
        forget()
    }

    private fun forget() {
        // Every exit runs through here -- commit, abandon, and a failed open -- so this is the one
        // place that can guarantee the ghost never outlives the preview. A ghost left behind is a
        // frozen icon over the launcher and a home row stuck invisible.
        AresDragGhost.hide()
        folder = null
        folderIcon = null
        list = null
        launcher = null
        pendingRank = -1
        entered = false
        wasOn = true
        approach.setEmpty()
        opener.setEmpty()
    }

    private fun cancelSettle() {
        folder?.removeCallbacks(settleSlot)
        pendingRank = -1
    }

    /** Scratch for [toDragLayerSpace]; used before the next call, on the main thread only. */
    private val point = FloatArray(2)

    /** The list → DragLayer transform, captured once per open. See [captureMapping]. */
    private var mapOffsetX = 0f
    private var mapOffsetY = 0f
    private var mapScaleX = 1f
    private var mapScaleY = 1f

    /**
     * Records the list → DragLayer transform, **once, before the folder opens**.
     *
     * ## Why it is frozen rather than read live
     *
     * The drag position this object is fed is the dragged tile's position *inside the list*, and
     * the list lives under the `Workspace` — which the folder-open animation **scales**. So reading
     * the transform live means the reported point moves while the finger does not: measured on
     * emulator-5554, a stationary drag reported DragLayer `(140,289)` before the folder opened and
     * `(150,311)` after it settled, having passed *through* the folder's top edge on the way. That
     * frame set `entered`, which retired the approach region, and the next frame — with the finger
     * still in exactly the same place — read as "left the folder" and started the close countdown.
     * The folder opened and shut in a loop with no user input at all.
     *
     * What is actually wanted is where the **finger** is, and the tile's list position is a
     * faithful proxy for that under the transform in force when the drag began. The list does not
     * scroll or reflow while a preview is open (the reflow is frozen, see
     * `AresFolderDrop.isFrozen`), so one capture holds for the whole preview.
     *
     * Two points are mapped rather than one because an offset alone cannot express the workspace
     * scale, and [SAMPLE_SPAN] is large so the division is not dominated by rounding.
     */
    private fun captureMapping(launcher: Launcher, list: AresHomeListView): Boolean {
        val dragLayer = launcher.dragLayer ?: return false
        val origin = floatArrayOf(0f, 0f)
        Utilities.getDescendantCoordRelativeToAncestor(list, dragLayer, origin, false)
        val far = floatArrayOf(SAMPLE_SPAN, SAMPLE_SPAN)
        Utilities.getDescendantCoordRelativeToAncestor(list, dragLayer, far, false)
        mapOffsetX = origin[0]
        mapOffsetY = origin[1]
        mapScaleX = (far[0] - origin[0]) / SAMPLE_SPAN
        mapScaleY = (far[1] - origin[1]) / SAMPLE_SPAN
        // A degenerate transform would map every point to one place, and every hit test after that
        // would be meaningless rather than merely wrong. Decline instead, and let the caller fall
        // back to the ring.
        if (mapScaleX <= 0f || mapScaleY <= 0f) {
            Log.e(TAG, "list transform is degenerate ($mapScaleX,$mapScaleY); not opening")
            return false
        }
        return true
    }

    /** Maps a point in the home list's coordinates up into the `DragLayer`'s. */
    private fun toDragLayerSpace(x: Float, y: Float): FloatArray? {
        if (list == null || launcher == null) return null
        point[0] = mapOffsetX + x * mapScaleX
        point[1] = mapOffsetY + y * mapScaleY
        return point
    }

    /** Far corner used by [captureMapping]; arbitrary, only its size matters. */
    private const val SAMPLE_SPAN = 1000f
}
