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
     * Finds a free region for [spanX] x [spanY], writing the cell into [outCell].
     *
     * @return the screen id to place on, or [NO_SCREEN] if the grid is full.
     */
    @JvmStatic
    fun findFreeCell(launcher: Launcher, spanX: Int, spanY: Int, outCell: IntArray): Int {
        val idp = launcher.deviceProfile.inv
        val countX = idp.numColumns
        val countY = idp.numRows
        if (spanX > countX || spanY > countY) {
            Log.e(TAG, "item ${spanX}x$spanY does not fit the ${countX}x$countY grid")
            return NO_SCREEN
        }

        val items = launcher.workspace?.aresHomeItems.orEmpty()
        val byScreen = items.filter { it.container == Favorites.CONTAINER_DESKTOP }
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
     * @return false if there was nowhere to put it, in which case nothing was written.
     */
    @JvmStatic
    fun addToHomeList(launcher: Launcher, info: PendingAddItemInfo): Boolean {
        val cell = IntArray(2)
        val screenId = findFreeCell(launcher, info.spanX, info.spanY, cell)
        if (screenId == NO_SCREEN) return false

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
}
