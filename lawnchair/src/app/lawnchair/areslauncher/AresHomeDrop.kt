package app.lawnchair.areslauncher

import android.graphics.Rect
import android.util.Log
import android.view.View
import com.android.launcher3.DropTarget
import com.android.launcher3.Launcher
import com.android.launcher3.LauncherAnimUtils
import com.android.launcher3.LauncherSettings.Favorites
import com.android.launcher3.LauncherState
import com.android.launcher3.PendingAddItemInfo
import com.android.launcher3.model.data.ItemInfo

/**
 * Drops that arrive from *outside* the home grid — the app list, the widget picker, a folder (§15).
 *
 * ## Why the stock path cannot be used
 *
 * `Workspace.onDropExternal` is grid-native from top to bottom. Its All Apps branch inflates the
 * item, asks `CellLayout.performReorder` for a cell, adds the view to the CellLayout and then
 * animates the drag view onto it:
 *
 * ```
 * addInScreen(view, container, screenId, ...);
 * cellLayout.onDropChild(view);
 * cellLayout.getShortcutsAndWidgets().measureChild(view);
 * mLauncher.getDragLayer().animateViewIntoPosition(d.dragView, view, this);
 * ```
 *
 * Under Strategy D `addInScreen` **discards that view** and hands the [ItemInfo] to
 * [AresHomeAdapter], which inflates its own on bind. So the local `view` is never attached to
 * anything, and `animateViewIntoPosition` dereferences its parent:
 *
 * ```
 * java.lang.NullPointerException: Attempt to invoke virtual method
 *   'void ShortcutAndWidgetContainer.measureChild(View)' on a null object reference
 *     at DragLayer.animateViewIntoPosition(DragLayer.java:255)
 *     at Workspace.onDropExternal(Workspace.java:3429)
 * ```
 *
 * — dragging any app out of the app list onto the home screen crashed the launcher outright. It is
 * a `NullPointerException` rather than a `ClassCastException`, which is what proves the parent is
 * *absent* rather than of the wrong type.
 *
 * The widget branch of the same method is broken more quietly and more dangerously. It takes its
 * cell from `cellLayout.performReorder(...)`, and every `CellLayout` is permanently empty of items
 * under Strategy D, so every dragged-in widget is written to the same cell. `checkItemPlacement`
 * then deletes the overlap on the next load — the failure that once discarded the whole home
 * screen. See the ⛔ banner in design/component-verification-3.md §2.
 *
 * ## What this does instead
 *
 * Both cases become the add path the picker's tap-to-add already uses: allocate a legal cell from
 * the **model** via [AresWidgetAdd], persist, and let the adapter render it. Nothing is animated
 * into position because there is no destination view to animate to — the drag view is disposed of
 * by handing the cleanup back to `DragController` instead.
 */
object AresHomeDrop {

    private const val TAG = "AresHomeDrop"

    /**
     * Handles a drop that originated outside the home grid.
     *
     * Three sources reach here, and they divide into two kinds of write:
     *
     *  - **The app list (§15) and the widget picker's drag-to-place** arrive with no database row
     *    of their own, so `addOrMoveItemInDatabase` writes a new one. An *add*.
     *  - **A drag out of an open folder** arrives carrying an existing row whose `container` is the
     *    folder, so the same call is a *move* — which is now exactly what is wanted.
     *
     * ## The folder case was refused until this commit, and why it is safe now
     *
     * `529276c113` refused it at `Workspace.acceptDrop`, because the drag could be armed by a
     * long-press **with no movement at all** (`ItemLongClickListener.beginDrag` routed any
     * folder-contained item to `Folder.startDrag`), and releasing still resolved a drop here. The
     * app left the folder without the user asking. Measured on the user's Pixel: `favorites` held
     * id 11 ("Drive") as a `container = -100` row while the folder's contents ran 7, 8, 9, 10, 12,
     * 13, 14 — persisted, not a view-level orphan.
     *
     * The user then overrode the scope decision: *"we absolutely need to be able to drag apps back
     * out of folders. Thats a very common action"*.
     *
     * The defect and the feature are the same code path with different intent, so the distinction
     * is drawn **where the drag starts** rather than where it lands. `Folder.onLongClick` no longer
     * begins a drag at all (it enters edit mode, see [AresFolderDrag]), and the only touch path
     * that does — `AresFolderDrag.DragStarter` — requires the finger to pass the touch slop first.
     * A press with no movement can no longer reach a drop, so the accidental case is gone by
     * construction rather than by veto. A release *inside* the folder's own bounds never arrives
     * here either: the open `Folder` is itself the drop target for that point, and handles it as an
     * in-folder reorder.
     *
     * ## What completes the move, and what stock already does
     *
     * Only the desktop half is written here. The folder half is already done by the time this runs:
     * `Folder.onDragStart` removes the item from the `FolderInfo` the moment the drag begins, and
     * `Folder.onDropCompleted` — which `DragController` calls immediately after this returns —
     * re-ranks the survivors and performs the **below-two auto-collapse** through
     * `replaceFolderWithFinalItem`. Both were verified running under Strategy D rather than assumed.
     *
     * @param isHotseatTarget true when the drop landed on the hotseat, which is still a real
     *   `CellLayout` and keeps stock behaviour entirely.
     * @return true when the drop was consumed here; false to fall through to
     *   `Workspace.onDropExternal`.
     */
    @JvmStatic
    fun handleExternalDrop(
        launcher: Launcher,
        isHotseatTarget: Boolean,
        d: DropTarget.DragObject,
    ): Boolean {
        if (isHotseatTarget) return false
        if (!AresWidgetAdd.isAresHome(launcher)) return false
        val dragged = d.dragInfo ?: return false

        // FIRST, before anything reads or writes the grid (§C4). The live gap
        // ([AresHomeDropPreview]) is a view-level entry with no database row, so it must be gone
        // before `commitDrop` or `addDraggedItem` renumbers anything -- and its index is a better
        // answer than re-deriving one from the release point, because it is the position the user
        // has been watching open up for the whole drag.
        val slotIndex = AresHomeDropPreview.take()

        // Converted up front because both destinations need the same object: an All Apps drag
        // carries an AppInfo, and only ItemInflater's type dispatch turns one into the
        // WorkspaceItemInfo the model -- and a FolderInfo's contents -- can actually hold.
        val converted = if (dragged is PendingAddItemInfo) null else toModelItem(launcher, dragged)
        val convertedInfo = converted?.tag as? ItemInfo

        // A dwell that armed over a folder takes precedence over the grid (§17: the folder behaves
        // the same whichever surface the icon came from). A picker selection can never get here:
        // Folder.willAccept refuses widgets, so the dwell never arms with one in hand.
        if (convertedInfo != null && AresFolderDrop.commitDrop(launcher, convertedInfo)) {
            finishDrop(launcher, d)
            return true
        }

        val added = when {
            // Picker selections (widgets, and legacy ACTION_CREATE_SHORTCUT items) still have to go
            // through Launcher.addPendingItem so binding and the configure activity happen -- the
            // item is not ready to persist yet. That is exactly what addToHomeList wraps.
            dragged is PendingAddItemInfo ->
                AresWidgetAdd.addToHomeList(
                    launcher,
                    dragged,
                    if (slotIndex >= 0) slotIndex else dropIndex(launcher, d),
                )
            converted != null ->
                addDraggedItem(launcher, converted, if (slotIndex >= 0) slotIndex else dropIndex(launcher, d))
            else -> false
        }
        if (!added) {
            Log.e(TAG, "no room on the home grid for $dragged")
            launcher.workspace.onNoCellFound(launcher.workspace, dragged, d.logInstanceId)
        }

        finishDrop(launcher, d)
        return true
    }

    /**
     * True when an external drag was RELEASED over the app-list pane sitting BESIDE the grid, so
     * `Workspace.acceptDrop` should refuse it and the drag view flies back to where it came from
     * (`ActivityAllAppsContainerView.onDropCompleted`).
     *
     * Measured on emulator-5554, 2026-09-04, unfolded: Gmail dragged out of the app list and
     * released back over the list at (1450,1300) was ADDED to the home grid (31 → 32 rows, log
     * *"drop at list (1429,1300) -> index 2"*). The Workspace is the drop target for every point
     * the DragLayer does not claim otherwise, and the home list's own bounds run under the pane, so
     * "over the pane" and "over the grid" are the same point to `dropIndexAt`. Owner: the icon
     * should "either [go] to [a] spot on the home page or pull back to its location in the app list."
     *
     * Posture is decided by GEOMETRY, not by any `DisplayController` flag (CLAUDE.md: nothing there
     * is posture-independent): folded, the drawer is a sheet OVER the grid and every release goes
     * through it, so a pane that covers (nearly) all of the list is never "not the grid". Only a
     * pane that overlaps less than 90% of the list's area can refuse a release. Logged on both
     * branches so a guard that has quietly stopped engaging is visible in a log.
     */
    @JvmStatic
    fun refusesExternalDrop(
        launcher: Launcher,
        isHotseatTarget: Boolean,
        d: DropTarget.DragObject,
    ): Boolean {
        if (isHotseatTarget || !AresWidgetAdd.isAresHome(launcher)) return false
        // The WORKSPACE-hosted pane, not launcher.appsView: that is the folded sheet, which is
        // hidden unfolded and answered isShown=false on the first measurement of this guard.
        val pane: View = launcher.workspace?.getAresAppListPaneOrNull() ?: run {
            Log.i(TAG, "no app-list pane inflated; not refusing")
            return false
        }
        if (!pane.isShown) {
            Log.i(TAG, "app-list pane not shown (folded); not refusing")
            return false
        }
        val list = launcher.workspace?.aresHomeList ?: return false
        val dragLayer = launcher.dragLayer
        val paneRect = Rect()
        dragLayer.getDescendantRectRelativeToSelf(pane, paneRect)
        val listRect = Rect()
        dragLayer.getDescendantRectRelativeToSelf(list, listRect)
        val overlap = Rect()
        val listArea = listRect.width().toLong() * listRect.height()
        val overlapArea = if (overlap.setIntersect(paneRect, listRect)) {
            overlap.width().toLong() * overlap.height()
        } else {
            0L
        }
        if (listArea <= 0 || overlapArea * 10 >= listArea * 9) {
            Log.i(TAG, "pane covers the list (pane=$paneRect list=$listRect); not refusing")
            return false
        }
        // Fresh array every call: getDescendantCoordRelativeToSelf maps IN PLACE and never zeroes.
        val p = floatArrayOf(d.x.toFloat(), d.y.toFloat())
        dragLayer.getDescendantCoordRelativeToSelf(launcher.workspace, p)
        val over = paneRect.contains(p[0].toInt(), p[1].toInt())
        Log.i(
            TAG,
            "release at dragLayer (${p[0].toInt()},${p[1].toInt()}) pane=$paneRect -> " +
                if (over) "over the app-list pane: REFUSED, drag view returns" else "on the grid",
        )
        return over
    }

    /**
     * The grid position the drop landed on, as an adapter index.
     *
     * ## Why this exists, and why it was left out the first time
     *
     * `8d1b546a4c` stated the gap plainly rather than hiding it: an item dropped on the grid was
     * **appended**, not placed where it was released, which misses the user's own description of
     * the flow — *"so the user can then place it on the home page in the location of their desire"*.
     * The reason given was that mapping a drop point through a scaled `Workspace` and a page scroll
     * *"fails silently when wrong, which is the worst way for this project to be wrong"*.
     *
     * That was the right call at the time and it was vindicated twice over: building
     * [AresFolderDrop] hit exactly that failure, in two independent ways, and neither threw. The
     * two facts it cost are what make this safe to write now, and both are measured rather than
     * reasoned:
     *
     *  - **Not `getVisualCenter()`.** On this launcher it answers ~228px above the finger, because
     *    `DragPreviewProvider` computes its registration point for a stock icon-above-label cell
     *    and our app-list rows are Niagara rows.
     *  - **`DragObject.x`/`y` is already in the drop target's space**, not the DragLayer's —
     *    `DragController.findDropTarget` maps it before storing it. `Workspace` is the target
     *    whenever this runs, so the remaining hop is Workspace → list, through the same
     *    `Utilities.mapCoordInSelfToDescendant` stock's own `mapPointFromDropLayout` uses.
     *
     * Both are documented at length on [AresFolderDrop.onExternalDragOver], with the measurements.
     */
    private fun dropIndex(launcher: Launcher, d: DropTarget.DragObject): Int {
        val list = launcher.workspace?.aresHomeList ?: return Int.MAX_VALUE
        val local = AresFolderDrop.toListSpace(launcher, list, d.x.toFloat(), d.y.toFloat())
        val index = list.dropIndexAt(local[0], local[1])
        Log.i(TAG, "drop at list (${local[0].toInt()},${local[1].toInt()}) -> index $index")
        return index
    }

    /**
     * Disposes of the drag view and leaves the drag state.
     *
     * Stock disposes of the drag view as a side effect of animating it into the dropped item's
     * final position. There is no such view here, so nothing would ever remove it and the dragged
     * icon would be left frozen over the launcher. Clearing the defer flag is the same hand-back
     * `Workspace.onDrop` uses when it declines to animate (see its `foundCell` else).
     */
    private fun finishDrop(launcher: Launcher, d: DropTarget.DragObject) {
        d.deferDragViewCleanupPostAnimation = false
        launcher.stateManager.goToState(
            LauncherState.NORMAL,
            LauncherAnimUtils.SPRING_LOADED_EXIT_DELAY.toLong(),
        )
    }

    /**
     * Turns a drag payload into the model item that can be persisted, or null if it cannot be.
     *
     * The inflate is not wasted work even though [com.android.launcher3.Workspace.addInScreen]
     * discards the view: `ItemInflater` is what **converts** the payload. An All Apps drag carries
     * an `AppInfo`, and `makeWorkspaceItem()` — reached only through the inflater's own type
     * dispatch — is the correct way to turn one into a `WorkspaceItemInfo`. Stock does the same
     * thing and then reads the view's tag back, which is why this reads the tag rather than the
     * value it passed in. An item dragged out of a folder is already a `WorkspaceItemInfo`, so the
     * inflater hands back the very same object and any write relocates the row it already has.
     */
    private fun toModelItem(launcher: Launcher, dragged: ItemInfo): View? =
        launcher.itemInflater.inflateItem(
            dragged,
            launcher.workspace.aresHomeList,
            Favorites.CONTAINER_DESKTOP,
        )

    /**
     * Persists an item dragged in from the app list or out of a folder at [index], and shows it.
     *
     * ## The insert, and why it does not go through `Workspace.addInScreen`
     *
     * That method's `CONTAINER_DESKTOP` branch is exactly `getAresAdapter().addItem(info)` — it
     * discards the view and hands the model item to the adapter, which places it by `rank`. Rank is
     * the right authority for an item arriving from the *model*, and the wrong one here: a drop
     * expresses an index, and ranks on this grid are dense after any drag, so an incoming item at
     * rank *k* ties with the item already there and [AresHomeAdapter.sortsAfter] settles it on
     * database `id`. For a freshly created row that is arbitrarily large, so the item would land
     * *after* the tile it was dropped on rather than at it, and not reproducibly.
     *
     * So the adapter is asked directly for a positioned insert and then the whole grid is
     * renumbered. The renumber is what makes the position durable — `rank` is the *entire* stored
     * position model under masonry — and it is the same call a drag-reorder already ends with, so
     * a drop and a drag persist through one code path rather than two.
     */
    private fun addDraggedItem(launcher: Launcher, view: View, index: Int): Boolean {
        val info = view.tag as? ItemInfo ?: return false
        // Created rather than fetched: on an empty home screen no desktop item has bound yet, so
        // the list does not exist. That is exactly the case this drop is about to end.
        val list = launcher.workspace?.orCreateAresHomeListForDrop ?: return false

        // A legal cell before anything is written: position is pure bookkeeping for us -- order
        // comes from `rank` alone -- but LoaderCursor.checkItemPlacement validates it on every load
        // and deletes what it rejects. excludeId matters for a drag out of a folder, where the item
        // already carries a database id.
        val cell = IntArray(2)
        val screenId = AresWidgetAdd.findFreeCell(launcher, info.spanX, info.spanY, cell, info.id)
        if (screenId == AresWidgetAdd.NO_SCREEN) return false

        val at = index.coerceIn(0, list.aresAdapter.itemCount)
        // Set before the write so the row is right on its first pass rather than needing a
        // follow-up. persistOrder below fixes up everything this displaces.
        info.rank = at

        // Fetched at the point of use, never cached: a writer obtained before the first load
        // carries the sentinel mLoadId = -1 and every write through it is silently discarded.
        // See design/model-persistence.md.
        launcher.modelWriter.addOrMoveItemInDatabase(
            info,
            Favorites.CONTAINER_DESKTOP,
            screenId,
            cell[0],
            cell[1],
        )
        list.aresAdapter.addItemAt(info, at)
        AresHomeReorder.persistOrder(launcher, list.aresAdapter.snapshot())
        return true
    }
}
