package app.lawnchair.areslauncher

import android.util.Log
import com.android.launcher3.Launcher
import com.android.launcher3.LauncherSettings.Favorites
import com.android.launcher3.PendingAddItemInfo
import com.android.launcher3.WorkspaceLayoutManager
import com.android.launcher3.model.data.ItemInfo
import com.android.launcher3.util.GridOccupancy

/**
 * Placement for items added from the widget picker (§7).
 *
 * ## Why stock's allocator cannot be used
 *
 * Stock tap-to-add runs `LauncherAccessibilityDelegate.addToWorkspace`, which allocates a cell via
 * [findSpaceOnWorkspace][com.android.launcher3.accessibility.LauncherAccessibilityDelegate] →
 * `CellLayout.findCellForSpan(...)`. That reads the **CellLayout view's** occupancy map, which is
 * populated as item views are added as its children.
 *
 * Under Strategy D no item view is ever added to a `CellLayout`: `Workspace.addInScreen` redirects
 * every `CONTAINER_DESKTOP` item into [AresHomeListView] instead. So every `CellLayout` is
 * permanently empty of items, `findCellForSpan` always reports the first cell free, and **every**
 * widget added this way would be written to the same cell.
 *
 * That is not a cosmetic problem. `LoaderCursor.checkItemPlacement` occupancy-checks
 * `CONTAINER_DESKTOP` on load and *deletes* items whose region is already taken — the failure mode
 * §4 hit, which discarded the entire home screen on the next reboot (`already occupied` /
 * `Item position overlap`). See the ⛔ banner in design/component-verification-3.md §2.
 *
 * ## What this does instead
 *
 * Allocates from the **model** — the items actually in the home list — rather than from view
 * occupancy, replicating `LoaderCursor.checkItemPlacement`'s rules so that anything we write is
 * guaranteed to survive the next load:
 *
 *  - bounds are `cellX + spanX <= numColumns` and `cellY + spanY <= numRows`;
 *  - a region must be vacant against every other item on that screen;
 *  - row 0 of [FIRST_SCREEN_ID][WorkspaceLayoutManager.FIRST_SCREEN_ID] is treated as reserved for
 *    the search container.
 *
 * The search-container reservation is applied **unconditionally**, whereas the loader applies it
 * only when the smartspace preference is enabled. Reserving when the loader would not is harmless
 * (a handful of cells go unused); *failing* to reserve when the loader does would hand back a cell
 * the loader then rejects, deleting the item. Conservative in the safe direction, and it avoids
 * depending on a preference read here.
 *
 * Position is pure bookkeeping for us — rendering order comes from `rank` alone, and
 * `Workspace.addInScreen` ignores cellX/cellY entirely — but it has to be *valid* bookkeeping or
 * the loader throws the item away.
 */
object AresWidgetAdd {

    private const val TAG = "AresWidgetAdd"

    /** No free region anywhere; caller should abandon the add. */
    const val NO_SCREEN = -1

    /** [addToHomeList] index meaning "put it at the end", which is what a tap-to-add wants. */
    const val APPEND = Int.MAX_VALUE

    /**
     * Where a widget currently being added should land, or [APPEND].
     *
     * State, reluctantly, because the widget add is the one add path that is **not synchronous**:
     * `Launcher.addPendingItem` may allocate an id, bind the provider and run a configure activity
     * before `completeAddAppWidget` finally creates the `LauncherAppWidgetInfo`. The drop point is
     * known at the start of that and the item does not exist until the end, so something has to
     * carry it across. Cleared the moment it is consumed, and overwritten by any later add, so the
     * worst a leak can do is put one widget in the wrong place.
     */
    private var pendingIndex = APPEND

    /**
     * True when the home list owns desktop items, i.e. this launcher is the Ares home surface.
     *
     * Mirrors [AresAllApps.isAresAppListPane]'s role for the app-list stack: the widget sheet is
     * also shown from the Taskbar (`TaskbarOverlayContext`), which is not a [Launcher] and must
     * keep stock behaviour.
     */
    @JvmStatic
    fun isAresHome(launcher: Launcher?): Boolean = launcher?.workspace?.hasAresHomeList() == true

    /**
     * Next `rank` for an appended item: one past the highest currently in the list.
     *
     * Not `size`, because ranks are not guaranteed contiguous — §4 persists the visual order and a
     * removal leaves a gap. Ordering only needs to be monotonic, so max+1 is both correct and
     * stable against gaps.
     */
    @JvmStatic
    fun nextRank(launcher: Launcher): Int {
        val items = launcher.workspace?.aresHomeItems ?: return 0
        return (items.maxOfOrNull { it.rank } ?: -1) + 1
    }

    /**
     * Allocates a legal home-grid position for an item about to be added, cell **and** rank.
     *
     * The single entry point for every stock "put this on the home screen" path — the accessibility
     * `ADD_TO_WORKSPACE` and `MOVE_TO_WORKSPACE` actions, the deep-shortcut menu's add, and Save
     * app pair from Overview. All four reach it through
     * `LauncherAccessibilityDelegate.findSpaceOnWorkspace`.
     *
     * Those four were writing cells straight out of `CellLayout.findCellForSpan`, which reads the
     * **CellLayout view's** occupancy. Under Strategy D no desktop item view is ever a CellLayout
     * child, so that map is permanently empty, every call returns the same first free cell, and
     * `LoaderCursor.checkItemPlacement` then deletes every arrival after the first with
     * `Item position overlap` — on a *later* boot, hours after the action, which is why it hides.
     * Save app pair reaches it by a plain touch: Overview, pick two apps, Save.
     *
     * Returns [NO_SCREEN] when the grid is full, which every caller already treats as "abandon".
     */
    @JvmStatic
    fun findSpaceForAdd(launcher: Launcher, info: ItemInfo, outCell: IntArray): Int =
        findFreeCell(launcher, info.spanX, info.spanY, outCell)

    /**
     * Gives [info] the rank of an item appended to the end of the home grid.
     *
     * A no-op off the Ares home surface, so a call site can be unconditional.
     *
     * Called on the object that is actually **written**, never on the caller's input: the add paths
     * build a fresh `WorkspaceItemInfo` (or clone one) inside their state-transition callback, and a
     * rank set on the source `AppInfo` would also be mutating a row of the live all-apps model.
     *
     * Ordering under masonry is `rank` alone (§4: no stored x/y), so an item written at the default
     * `0` ties with whatever is already first and lands at the *top* of the grid instead of the end
     * — settled only by database id. Not data loss, and the next drag renumbers it densely, but it
     * is the wrong answer to "add to home screen".
     */
    @JvmStatic
    fun applyAppendRank(launcher: Launcher?, info: ItemInfo) {
        if (!isAresHome(launcher) || launcher == null) return
        info.rank = nextRank(launcher)
    }

    /**
     * Finds a free region for [spanX] x [spanY], writing the cell into [outCell].
     *
     * [excludeId] omits one item from the occupancy map. A **resize** re-places an item that is
     * already in the list, so without this it would collide with its own current footprint and be
     * told the grid is full. Defaulted, so the add path — where nothing is being replaced — is
     * unaffected.
     *
     * @return the screen id to place on, or [NO_SCREEN] if the grid is full.
     */
    @JvmStatic
    fun findFreeCell(
        launcher: Launcher,
        spanX: Int,
        spanY: Int,
        outCell: IntArray,
        excludeId: Int = ItemInfo.NO_ID,
    ): Int {
        val idp = launcher.deviceProfile.inv
        val countX = idp.numColumns
        val countY = idp.numRows
        if (spanX > countX || spanY > countY) {
            Log.e(TAG, "item ${spanX}x$spanY does not fit the ${countX}x$countY grid")
            return NO_SCREEN
        }

        val items = launcher.workspace?.aresHomeItems.orEmpty()
        // The `excludeId != NO_ID` guard matters: an item that is mid-add still carries NO_ID, so a
        // bare `id != excludeId` would quietly drop it from the occupancy map and hand out a cell
        // that is about to be taken.
        val byScreen = items
            .filter {
                it.container == Favorites.CONTAINER_DESKTOP &&
                    (excludeId == ItemInfo.NO_ID || it.id != excludeId)
            }
            .groupBy { it.screenId }

        // Existing screens first, lowest id first, so additions stay compact. FIRST_SCREEN_ID is
        // always considered even when it currently holds nothing.
        val candidates = (byScreen.keys + WorkspaceLayoutManager.FIRST_SCREEN_ID)
            .filter {
                it != WorkspaceLayoutManager.EXTRA_EMPTY_SCREEN_ID &&
                    it != WorkspaceLayoutManager.EXTRA_EMPTY_SCREEN_SECOND_ID
            }
            .distinct()
            .sorted()

        for (screenId in candidates) {
            val occupancy = occupancyFor(byScreen[screenId].orEmpty(), screenId, idp)
            if (firstVacantRegion(occupancy, countX, countY, spanX, spanY, outCell)) {
                Log.i(
                    TAG,
                    "placing ${spanX}x$spanY at screen=$screenId " +
                        "cell=(${outCell[0]},${outCell[1]})",
                )
                return screenId
            }
        }

        // Every existing screen is full. Take the next id up; it has no items by construction, so
        // only the search-container reservation can apply and a fresh grid always has room.
        val newScreenId = (candidates.maxOrNull() ?: WorkspaceLayoutManager.FIRST_SCREEN_ID) + 1
        val occupancy = occupancyFor(emptyList(), newScreenId, idp)
        if (firstVacantRegion(occupancy, countX, countY, spanX, spanY, outCell)) {
            Log.i(TAG, "existing screens full; placing on new screen=$newScreenId")
            return newScreenId
        }

        Log.e(TAG, "no free ${spanX}x$spanY region anywhere")
        return NO_SCREEN
    }

    private fun occupancyFor(
        items: List<ItemInfo>,
        screenId: Int,
        idp: com.android.launcher3.InvariantDeviceProfile,
    ): GridOccupancy {
        // Sized (+1) to match LoaderCursor's own occupancy grid; bounds are enforced separately in
        // firstVacantRegion so an item can never straddle the padding column/row.
        val occupancy = GridOccupancy(idp.numColumns + 1, idp.numRows + 1)
        if (screenId == WorkspaceLayoutManager.FIRST_SCREEN_ID) {
            occupancy.markCells(0, 0, idp.numSearchContainerColumns, 1, true)
        }
        items.forEach { occupancy.markCells(it, true) }
        return occupancy
    }

    /** Scans row-major for the first vacant region that also satisfies the loader's bounds check. */
    private fun firstVacantRegion(
        occupancy: GridOccupancy,
        countX: Int,
        countY: Int,
        spanX: Int,
        spanY: Int,
        outCell: IntArray,
    ): Boolean {
        for (y in 0..(countY - spanY)) {
            for (x in 0..(countX - spanX)) {
                if (occupancy.isRegionVacant(x, y, spanX, spanY)) {
                    outCell[0] = x
                    outCell[1] = y
                    return true
                }
            }
        }
        return false
    }

    /**
     * Adds a picker selection to the home list.
     *
     * Reuses stock's whole bind/configure/complete pipeline via [Launcher.addPendingItem] — that
     * method takes an explicit container/screen/cell and is not itself grid-coupled; only the
     * *caller* that computes the cell (`Workspace.onDropExternal`, and the accessibility delegate
     * above) is. Supplying our own cell is therefore the entire adaptation needed.
     *
     * [index] is the grid position a **drag** released on; [APPEND] for the sheet's tap-to-add,
     * which has no drop point to speak of. It is remembered rather than applied here because the
     * item being placed does not exist yet — see [pendingIndex] — and is consumed by [placeNewItem]
     * once `completeAddAppWidget` has built the real row.
     *
     * @return false if there was nowhere to put it, in which case nothing was written.
     */
    @JvmStatic
    @JvmOverloads
    fun addToHomeList(launcher: Launcher, info: PendingAddItemInfo, index: Int = APPEND): Boolean {
        val cell = IntArray(2)
        val screenId = findFreeCell(launcher, info.spanX, info.spanY, cell)
        if (screenId == NO_SCREEN) return false

        pendingIndex = index
        launcher.addPendingItem(
            info,
            Favorites.CONTAINER_DESKTOP,
            screenId,
            cell,
            info.spanX,
            info.spanY,
        )
        return true
    }

    /**
     * Moves a just-added widget to the position its drag was released on, if there was one.
     *
     * Called from `Launcher.completeAddAppWidget` after `addInScreen`, which is the first moment the
     * row exists in the adapter. The move-then-renumber shape is deliberate: `addInScreen` places by
     * `rank`, and a rank that ties with an existing one is settled on database `id` — arbitrary for
     * a row created seconds ago — so asking for the index directly is the only deterministic way to
     * express it. [AresHomeReorder.persistOrder] then makes it durable, exactly as it does for a
     * drag-reorder and for [AresHomeDrop]'s icon drops: one persistence path for all of them, which
     * is §17's rule.
     *
     * A no-op for a tap-to-add, which leaves [pendingIndex] at [APPEND] and has already landed at
     * the end.
     */
    @JvmStatic
    fun placeNewItem(launcher: Launcher, info: ItemInfo) {
        val index = pendingIndex
        pendingIndex = APPEND
        if (index == APPEND) return
        val list = launcher.workspace?.aresHomeList ?: return
        val from = list.aresAdapter.indexOf(info)
        if (from < 0) {
            Log.w(TAG, "widget ${info.id} is not in the grid; leaving it where it landed")
            return
        }
        val to = index.coerceIn(0, list.aresAdapter.itemCount - 1)
        list.aresAdapter.moveItem(from, to)
        AresHomeReorder.persistOrder(launcher, list.aresAdapter.snapshot())
        Log.i(TAG, "dropped widget ${info.id} moved from index $from to $to")
    }
}
