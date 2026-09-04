package app.lawnchair.areslauncher

import android.view.View
import com.android.launcher3.AbstractFloatingView
import com.android.launcher3.Launcher
import com.android.launcher3.R
import com.android.launcher3.model.data.ItemInfo
import com.android.launcher3.model.data.WorkspaceItemFactory
import com.android.launcher3.popup.SystemShortcut

/**
 * "Add to home screen" in the long-press popup of an app-list item.
 *
 * Owner 2026-09-04: *"add an option to the menu to add app to home page so we don't have to totally
 * rely on dragging."* Dragging still works; this is the second route, and it is the one that works
 * one-handed, with an item far down a long list, or when the target row is off screen.
 *
 * ## Why it delegates instead of writing the row itself
 *
 * Adding an app to this home surface is deceptively involved: the item has to be converted through
 * [WorkspaceItemFactory], given a legal `cellX/cellY` (the loader occupancy-checks those and DELETES
 * what it rejects, even though the renderer positions by `rank` alone), given an append `rank` (which
 * is the entire stored ordering under masonry -- without it a new item lands at the TOP), written
 * through a `modelWriter` fetched at the point of use, and then bound.
 *
 * `LauncherAccessibilityDelegate.addToWorkspace` already does all of that, including this fork's own
 * `AresWidgetAdd.applyAppendRank` fix, because the accessibility "Add to home screen" action has
 * always existed and has always had to solve the same problem. Reusing it means one writer rather
 * than a third parallel implementation to keep in step -- this project has already been bitten by
 * duplicated add paths drifting apart.
 *
 * ## When it is offered
 *
 * Only for an item that is not already placed: `id == NO_ID`. A home tile or a folder child carries
 * a database row, and offering "add to home screen" on something already on the home screen would be
 * a menu entry that either does nothing or silently duplicates it. That test also keeps the entry off
 * the Taskbar and secondary-display popups without naming them, since it is a property of the ITEM.
 */
object AresAddToHome {

    @JvmField
    val SHORTCUT = SystemShortcut.Factory<Launcher> { launcher, itemInfo, originalView ->
        if (isOfferable(itemInfo)) AddToHome(launcher, itemInfo, originalView) else null
    }

    /** Not already on the workspace, and convertible into something the workspace can store. */
    private fun isOfferable(info: ItemInfo?): Boolean =
        info != null && info.id == ItemInfo.NO_ID && info is WorkspaceItemFactory

    class AddToHome(
        launcher: Launcher,
        itemInfo: ItemInfo,
        originalView: View?,
    ) : SystemShortcut<Launcher>(
        R.drawable.ic_home_screen,
        R.string.action_add_to_workspace,
        launcher,
        itemInfo,
        originalView,
    ) {
        override fun onClick(view: View) {
            // Close the popup FIRST. addToWorkspace runs findSpaceOnWorkspace, and an open floating
            // view can occupy the cells it is searching -- the accessibility path closes open views
            // for exactly this reason before it starts looking.
            AbstractFloatingView.closeAllOpenViews(mTarget)
            mTarget.accessibilityDelegate?.addToWorkspace(
                mItemInfo,
                false, /* accessibility -- false: this is a touch, so no a11y announcement */
                null, /* finishCallback */
            )
        }
    }
}
