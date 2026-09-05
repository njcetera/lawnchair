package app.lawnchair.areslauncher

import com.android.launcher3.DropTarget
import com.android.launcher3.Launcher
import com.android.launcher3.allapps.ActivityAllAppsContainerView
import com.android.launcher3.PendingAddItemInfo

/**
 * Whether a drag should bring stock Launcher3's workspace drag *presentation* with it.
 *
 * ## What "the chrome" is, and why it does not fit
 *
 * Stock presents a drag as: `SPRING_LOADED` (which scales the workspace down so its CellLayout
 * pages read as cards you are making room in), an extra empty page appended on the right, and the
 * `DropTargetBar` — the Cancel / Uninstall pills across the top.
 *
 * Every part of that describes a paged CellLayout grid. Strategy D does not have one: the home
 * screen is a single vertical tile list, there are no pages to make room in and nothing to append
 * a page to, so the zoom-out is showing the user a model the launcher does not use. The owner put
 * it plainly on 2026-09-04, seeing it on the unfolded app-list drag: *"the windows that pop up
 * during the drag ... it's from the old grid system and not the tile system."*
 *
 * ## This predicate is a UNION of specific cases, not a general rule
 *
 * [AresFolderDrag.isFolderDrag] carries a warning worth repeating: an earlier version of its
 * comment justified suppression with a claim broad enough to cover every drag in the launcher,
 * while the predicate only gated folder-sourced ones. Reading a general claim off a narrow gate is
 * how someone concludes something false about the paths it never touched.
 *
 * So the cases are listed, each with its own reason, and adding one is a deliberate act:
 *
 *  - **Folder-sourced** ([AresFolderDrag.isFolderDrag]) — the folder is open on top of the
 *    workspace and the bar's hit rect reaches into it, so a live Remove target sits behind an open
 *    folder. That was a real data-loss defect.
 *  - **App-list-sourced** ([isAppListDrag]) — owner decision 2026-09-04. Nothing is lost by
 *    dropping the bar: removal from the home screen is the tile's own × badge, and uninstall is
 *    already in the long-press popup as `LawnchairShortcut.UNINSTALL`, so the gesture was a second
 *    route to a function that has a first one.
 *  - **Picker-sourced** ([isPickerDrag]) — owner decision 2026-09-04 (evening), widening the one
 *    above once asked: *"Yes, widen it."* The payload is a `PendingAddItemInfo`, which is how the
 *    widget tray, the picker's recommendations row and another app's pin-widget request all arrive
 *    (`AresHomeDrop.handleExternalDrop` already treats them as one case and routes all three through
 *    `Launcher.addPendingItem`). The bar's only target for these is Cancel, and cancelling is still
 *    a release anywhere the list does not accept — or the added widget's own × badge.
 *
 * With that, every `DragController` drag that lands on the Ares home surface is covered, and the
 * one that never reaches `Workspace.onDragStart` at all — the home grid's own reorder, an
 * `ItemTouchHelper` drag — never showed the chrome to begin with. Consistency, not exception.
 */
object AresDragChrome {

    private const val TAG = "AresDragChrome"

    /**
     * True when [dragSource] is one of this launcher's all-apps containers.
     *
     * Both postures are covered on purpose even though the owner reported the unfolded one. The
     * home surface being dragged ONTO is the same vertical tile list either way, so the chrome is
     * equally wrong folded; gating on posture would make the drag look different for no reason the
     * user could name.
     *
     * A CORRECTION (adversarial review 2026-09-05, F5). An earlier version of this doc claimed the
     * class match was needed because "`launcher.appsView` is the FOLDED sheet even while the
     * unfolded pane is what was touched, so an identity test would have missed the reported case".
     * That is false: `ItemLongClickListener.onAllAppsItemLongClick` passes `launcher.getAppsView()`
     * as the drag source in BOTH postures (`beginDragShared(v, launcher.getAppsView(), ...)`), so an
     * identity test would have matched too. The class match stays because it says what is meant —
     * "an all-apps container, whichever one" — and would keep holding if a pane-sourced drag ever
     * named the pane as its source.
     *
     * Measured (2026-09-04, emulator-5554, one-build A/B on `debug.ares.drag_chrome`): UNFOLDED,
     * control `state=SpringLoaded`, fix `state=Normal` — the reported case. FOLDED, the same
     * `input motionevent` recipe never left PRE-DRAG on EITHER arm (`starts=0`, popup still up,
     * no log line from this class), so the folded arm is NOT yet measured and the owner's finger is
     * what settles it. Recorded rather than assumed.
     */
    private fun isAppListDrag(dragSource: Any?): Boolean =
        dragSource is ActivityAllAppsContainerView<*>

    /**
     * True when the payload is an unplaced picker selection. The picker's own `DragSource` is an
     * anonymous lambda (`ItemLongClickListener.onWidgetItemLongClick`), so there is no source class
     * to match; the payload is the honest discriminator.
     */
    private fun isPickerDrag(dragObject: DropTarget.DragObject): Boolean =
        dragObject.dragInfo is PendingAddItemInfo

    /**
     * Control switch for the app-list and picker arms, so a one-build A/B can compare the owner's
     * reported behaviour against the fix from identical bytes. `debug.ares.drag_chrome=0` restores
     * the pre-fix presentation. Read once: an arm that can change under a running process measures
     * a mixture of both.
     *
     * Deliberately does NOT gate the folder arm. That one is a shipped fix for a data-loss defect
     * (a live Remove target behind an open folder); putting it behind a switch would make the
     * control arm reintroduce a real bug rather than just an unwanted animation.
     */
    private val DEBUG_ARM =
        !"0".equals(android.os.SystemProperties.get("debug.ares.drag_chrome", "1"))

    /** True when stock's SPRING_LOADED / empty-page / drop-target-bar presentation must not run. */
    @JvmStatic
    fun suppressesStockChrome(launcher: Launcher?, dragObject: DropTarget.DragObject?): Boolean {
        if (launcher == null || dragObject == null || !AresWidgetAdd.isAresHome(launcher)) return false
        if (AresFolderDrag.isFolderDrag(launcher, dragObject.dragSource)) return true
        val arm = when {
            isAppListDrag(dragObject.dragSource) -> "app-list"
            isPickerDrag(dragObject) -> "widget-picker"
            else -> return false
        }
        if (!DEBUG_ARM) {
            // Log the DECLINE, not just the fire. This guard's whole effect is the ABSENCE of
            // chrome, so a version that had quietly stopped engaging would be indistinguishable in
            // a log from a run where no qualifying drag ever happened.
            android.util.Log.i(TAG, "DECLINED (debug.ares.drag_chrome=0) $arm drag")
            return false
        }
        android.util.Log.i(TAG, "suppressing stock chrome for $arm drag")
        return true
    }
}
