package app.lawnchair.areslauncher

import android.util.Log
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

        val added = when (dragged) {
            // Picker selections (widgets, and legacy ACTION_CREATE_SHORTCUT items) still have to go
            // through Launcher.addPendingItem so binding and the configure activity happen -- the
            // item is not ready to persist yet. That is exactly what addToHomeList wraps.
            is PendingAddItemInfo -> AresWidgetAdd.addToHomeList(launcher, dragged)
            else -> addDraggedItem(launcher, dragged)
        }
        if (!added) {
            Log.e(TAG, "no room on the home grid for $dragged")
            launcher.workspace.onNoCellFound(launcher.workspace, dragged, d.logInstanceId)
        }

        // Stock disposes of the drag view as a side effect of animating it into the dropped item's
        // final position. There is no such view here, so nothing would ever remove it and the
        // dragged icon would be left frozen over the launcher. Clearing the defer flag is the same
        // hand-back Workspace.onDrop uses when it declines to animate (see its `foundCell` else).
        d.deferDragViewCleanupPostAnimation = false
        launcher.stateManager.goToState(
            LauncherState.NORMAL,
            LauncherAnimUtils.SPRING_LOADED_EXIT_DELAY.toLong(),
        )
        return true
    }

    /**
     * Persists an item dragged in from the app list or out of a folder, and shows it.
     *
     * The inflate is not wasted work even though [com.android.launcher3.Workspace.addInScreen]
     * discards the view: `ItemInflater` is what **converts** the drag payload into the model item.
     * An All Apps drag carries an `AppInfo`, and `makeWorkspaceItem()` — reached only through the
     * inflater's own type dispatch — is the correct way to turn one into the `WorkspaceItemInfo`
     * that gets written. Stock does the same thing and then reads the view's tag back, which is why
     * this reads the tag rather than the value it passed in.
     */
    private fun addDraggedItem(launcher: Launcher, dragged: ItemInfo): Boolean {
        val view = launcher.itemInflater.inflateItem(
            dragged,
            launcher.workspace.aresHomeList,
            Favorites.CONTAINER_DESKTOP,
        ) ?: return false
        val info = view.tag as? ItemInfo ?: return false

        // A legal cell before anything is written: position is pure bookkeeping for us -- order
        // comes from `rank` alone -- but LoaderCursor.checkItemPlacement validates it on every load
        // and deletes what it rejects. excludeId matters for a drag out of a folder, where the item
        // already carries a database id.
        val cell = IntArray(2)
        val screenId = AresWidgetAdd.findFreeCell(launcher, info.spanX, info.spanY, cell, info.id)
        if (screenId == AresWidgetAdd.NO_SCREEN) return false

        // Appended, not inserted: a drop is an add, and the grid packs in rank order. Set before
        // the write so the row is correct on its first pass.
        info.rank = AresWidgetAdd.nextRank(launcher)

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
        launcher.workspace.addInScreen(
            view,
            Favorites.CONTAINER_DESKTOP,
            screenId,
            cell[0],
            cell[1],
            info.spanX,
            info.spanY,
        )
        return true
    }
}
