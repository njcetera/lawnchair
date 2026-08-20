package app.lawnchair.areslauncher

import android.view.View
import com.android.launcher3.Launcher
import com.android.launcher3.folder.Folder

/**
 * What a long-press on an app **inside an open folder** does (§18).
 *
 * ## The gesture stock gives that folder, and why it is wrong here
 *
 * `FolderPagedView.createNewView` hands every icon `setOnLongClickListener(mFolder)`, so a
 * long-press lands in `Folder.onLongClick` and goes straight to `Folder.startDrag`. Two things then
 * happen at once, and both are wrong for this launcher:
 *
 *  - `Workspace.beginDragShared` calls `btv.startLongPressAction()`, which raises that app's
 *    `PopupContainerWithArrow`. The user asked for the opposite: *"holding an app down to go into
 *    edit mode instead pull up the menu rather than going into edit mode"*.
 *  - The drag is **armed by the press alone**, before any movement. `Folder.onDragStart` then pulls
 *    the item out of the folder immediately (`mContent.removeItem` plus a suppressed
 *    `removeFolderContent`), which is the *"cause the icon to derender just for a moment"* half of
 *    the same report — and, before `529276c113` refused the drop, it is how an app silently left a
 *    folder for good with no drag ever performed.
 *
 * The popup is not merely in the way, it is what makes the gesture unavailable: it consumes the
 * long-press, so nothing else can be bound to it.
 *
 * ## The rule, and why it matches the home grid exactly
 *
 * An open folder should behave like the home grid, so the split is the same one
 * [AresHomeAdapter]'s long-click listener already makes:
 *
 * | gesture | not editing | editing |
 * |---|---|---|
 * | long-press an icon | enter edit mode | show that app's popup |
 * | touch and drag an icon | (nothing — stock's press-then-drag is gone) | move it |
 *
 * One gesture raises one thing. Nothing is lost: App info, Uninstall and an app's shortcuts are
 * still a long-press away, made deliberately from a surface that is already in the mode.
 */
object AresFolderDrag {

    /**
     * Handles a long-press on the icon [v] inside the open [folder].
     *
     * @return true when this consumed the gesture, so `Folder.onLongClick` must not fall through to
     *   `Folder.startDrag`.
     */
    @JvmStatic
    fun onFolderItemLongClick(launcher: Launcher, folder: Folder, v: View): Boolean {
        if (!AresWidgetAdd.isAresHome(launcher)) return false
        if (folder.isDestroyed) return false
        return true
    }
}
