package app.lawnchair.areslauncher

import android.animation.ObjectAnimator
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.widget.FrameLayout
import com.android.launcher3.Launcher
import com.android.launcher3.ShortcutAndWidgetContainer
import com.android.launcher3.celllayout.CellLayoutLayoutParams
import com.android.launcher3.folder.Folder
import com.android.launcher3.folder.FolderIcon
import com.android.launcher3.model.data.ItemInfo

/**
 * Editing **inside** an open folder, while the home grid is in edit mode (§18).
 *
 * A folder is a container you descend into rather than an item you delete, so it carries no × of
 * its own. Instead you open it — tapping a folder is the one tap edit mode does not make inert —
 * and remove its apps from in here. Emptying it is how the folder itself goes away, because stock
 * already collapses a folder once its contents drop below two.
 *
 * ## Where the badges live, and why
 *
 * Each × rides in a transparent, icon-sized cell added straight into the folder page's
 * [ShortcutAndWidgetContainer], with `isLockedToGrid = false` so its bounds are taken verbatim from
 * the icon it shadows. That is the same escape hatch the home grid uses to host itself inside a
 * CellLayout, and it is what makes this cheap: the badge is a sibling of the icon in the icon's own
 * coordinate space, so it inherits the folder's open/close scale, its page scroll and its clipping
 * for free. A DragLayer overlay was the alternative and would have had to re-derive all three every
 * frame.
 *
 * Two consequences of that choice, both deliberate:
 *
 *  - The cells are parked at **cell (-1, -1)**. Every stock read of folder contents goes through a
 *    *cell-coordinate* lookup — `FolderPagedView.iterateOverItems` walks `page.getChildAt(i, j)`
 *    over the real grid, and `getViewInCurrentPage` maps a rank to a cell — so a child at a
 *    negative cell is invisible to all of them. In particular `iterateOverItems` casts the tag to
 *    `ItemInfo`, which our badges do not carry; being unreachable by cell is what keeps that cast
 *    safe. `GridOccupancy.markCells` also returns early on a negative cell, so removal bookkeeping
 *    cannot walk off the array.
 *  - They **do** inflate `container.getChildCount()`. Only `getViewInCurrentPage` reads that, to
 *    turn "last child" into a rank; an inflated count makes it resolve a cell that is empty and
 *    return null. The one caller is `LauncherDelegate.replaceFolderWithFinalItem`, where null
 *    merely skips the destroy *animation* and runs the same completion immediately — and in the
 *    real sequence the badges are already gone, because `rearrangeChildren` wipes every page before
 *    the collapse is reached.
 *
 * ## Why sync runs on every pre-draw
 *
 * `FolderPagedView.arrangeChildren` calls `page.removeAllViews()` and re-adds only the icons, so a
 * single removal takes the badges with it. Rather than trying to enumerate every path that
 * rearranges a folder, the session re-asserts itself before each frame and adds only what is
 * missing. Folders hold at most a page or two of icons, so the walk is trivial, and it converges in
 * one extra pass.
 */
object AresFolderEdit {

    private const val CELL_TAG = "ares_folder_edit_cell"

    private const val REMOVE_REASON = "removed from folder by user in home edit mode"

    /** The one folder being edited, or null. Only one folder can be open at a time. */
    private var session: Session? = null

    /**
     * Starts editing the folder behind [folderIcon], which is about to open.
     *
     * Takes the icon rather than asking `Folder.getOpen(launcher)`, because at the moment the tap
     * is recognised **no folder is open yet**. `View.onTouchEvent` posts its `PerformClick` rather
     * than calling it inline, so anything the grid posts from the same gesture is queued *ahead* of
     * the click that opens the folder — measured directly: an attach posted from `ACTION_UP`
     * logged `folder=null`. The `Folder` view, by contrast, is built alongside its icon and exists
     * long before it is opened, so it can be taken from the icon and waited on.
     */
    @JvmStatic
    fun attach(launcher: Launcher, folderIcon: FolderIcon) {
        val folder = folderIcon.folder ?: return
        if (folder.isDestroyed) return
        if (session?.folder === folder) return
        detach()
        session = Session(launcher, folder).also { it.start() }
    }

    /** Ends the current session, if any. Safe to call at any time. */
    @JvmStatic
    fun detach() {
        session?.stop()
        session = null
    }

    private class Session(
        private val launcher: Launcher,
        val folder: Folder,
    ) : ViewTreeObserver.OnPreDrawListener, View.OnAttachStateChangeListener {

        /** Badge cell per icon view, so a cell is never added twice for the same icon. */
        private val cells = mutableMapOf<View, View>()

        /** Running wiggles, keyed by the view they rotate — icons and badge cells alike. */
        private val wiggles = mutableMapOf<View, ObjectAnimator>()

        /**
         * The folder is usually still closed at this point, so the pre-draw listener cannot be
         * registered yet: an unattached view's `ViewTreeObserver` is a floating one that is
         * discarded and replaced when the view joins a window, taking the listener with it. Wait
         * for the attach instead, which is exactly when the folder opens.
         */
        fun start() {
            folder.addOnAttachStateChangeListener(this)
            if (folder.isAttachedToWindow) onViewAttachedToWindow(folder)
        }

        fun stop() {
            folder.removeOnAttachStateChangeListener(this)
            folder.viewTreeObserver.takeIf { it.isAlive }?.removeOnPreDrawListener(this)
            for ((view, animator) in wiggles) {
                AresEditWiggle.stop(view, animator)
            }
            wiggles.clear()
            for (cell in cells.values) {
                (cell.parent as? ViewGroup)?.removeView(cell)
            }
            cells.clear()
        }

        override fun onPreDraw(): Boolean {
            sync()
            return true
        }

        /** The folder is removed from the DragLayer when it closes, which is our cue to stop. */
        override fun onViewDetachedFromWindow(v: View) = detach()

        /** The folder is added to the DragLayer when it opens, which is our cue to start drawing. */
        override fun onViewAttachedToWindow(v: View) {
            folder.viewTreeObserver.addOnPreDrawListener(this)
            sync()
        }

        /**
         * Brings the badges and wiggles in line with whatever the folder currently holds.
         *
         * Idempotent by construction: it adds only what is missing and only writes layout params
         * that actually differ, so running it every frame costs a walk and nothing else.
         */
        private fun sync() {
            val icons = folder.iconsInReadingOrder
            val live = mutableSetOf<View>()

            icons.forEachIndexed { index, icon ->
                val parent = icon.parent as? ShortcutAndWidgetContainer ?: return@forEachIndexed
                val iconLp = icon.layoutParams as? CellLayoutLayoutParams ?: return@forEachIndexed
                val info = icon.tag as? ItemInfo ?: return@forEachIndexed
                live.add(icon)

                var cell = cells[icon]
                if (cell == null || cell.parent !== parent) {
                    (cell?.parent as? ViewGroup)?.removeView(cell)
                    cell = createCell(parent, info)
                    cells[icon] = cell
                    parent.addView(cell, newCellParams())
                }
                live.add(cell)

                val lp = cell.layoutParams as CellLayoutLayoutParams
                if (lp.x != iconLp.x || lp.y != iconLp.y ||
                    lp.width != iconLp.width || lp.height != iconLp.height
                ) {
                    lp.x = iconLp.x
                    lp.y = iconLp.y
                    lp.width = iconLp.width
                    lp.height = iconLp.height
                    cell.requestLayout()
                }

                // Icon and badge share a rect and a phase, so the × stays pinned to its corner
                // instead of drifting across a rotating icon.
                startWiggle(icon, index)
                startWiggle(cell, index)
            }

            // Anything that has left the folder: stop its animator and drop its badge. A wiggle
            // left running on a recycled BubbleTextView would keep rotating it somewhere else.
            val gone = wiggles.keys.filterNot { it in live } + cells.keys.filterNot { it in live }
            for (view in gone.distinct()) {
                wiggles.remove(view)?.let { AresEditWiggle.stop(view, it) }
                cells.remove(view)?.let { cell -> (cell.parent as? ViewGroup)?.removeView(cell) }
            }
        }

        private fun startWiggle(view: View, index: Int) {
            if (wiggles.containsKey(view)) return
            AresEditWiggle.start(view, index)?.let { wiggles[view] = it }
        }

        /**
         * A transparent box the size of the icon, carrying the × in its top-start corner.
         *
         * The badge itself is [AresRemoveBadge]'s, unchanged, so the affordance is identical to
         * the grid's — same glyph, same touch target, same corner.
         */
        private fun createCell(parent: ViewGroup, info: ItemInfo): View {
            val cell = EditCell(parent.context)
            cell.tag = CELL_TAG
            // Named for the same reason as on the grid: a folder of six apps would otherwise offer
            // six controls that all announce themselves as "Remove".
            cell.addView(AresRemoveBadge.createBadge(cell, info.title) { removeFromFolder(info) })
            return cell
        }

        private fun newCellParams() = CellLayoutLayoutParams(-1, -1, 1, 1).apply {
            // Bounds are copied from the icon rather than derived from a cell, so the grid must
            // not recompute them; that is exactly what isLockedToGrid=false suppresses.
            isLockedToGrid = false
            canReorder = false
        }

        /**
         * Takes [info] out of the folder — off the home screen, never off the device.
         *
         * This is `Launcher.removeItem`'s folder branch, done with the open [Folder] in hand
         * instead of looked up. That matters: stock finds the folder with
         * `Workspace.getViewByItemId(itemInfo.container)`, which walks **CellLayout children**, and
         * under Strategy D our folder icon is a RecyclerView row — so the lookup returns null and
         * stock falls through to its plain "delete a workspace item" branch. The row would go from
         * the database with `FolderInfo.getContents()` never updated: a stale folder preview, and
         * the below-two auto-collapse would never fire. Nothing crashes, which is what makes it
         * worth writing down.
         *
         * `removeFolderContent` is what carries the collapse — it closes the folder at one item,
         * and `closeComplete` then calls `replaceFolderWithFinalItem`, which puts the survivor back
         * on the home grid through our own `addInScreen` redirect.
         *
         * The writer is fetched here rather than cached, for the reason in [AresRemoveBadge].
         */
        private fun removeFromFolder(info: ItemInfo) {
            folder.removeFolderContent(true, info)
            launcher.modelWriter.deleteItemFromDatabase(info, REMOVE_REASON)
        }
    }

    /**
     * The badge's host box.
     *
     * [ShortcutAndWidgetContainer.measureChild] calls `setPadding` on every non-widget child on
     * every measure pass, to centre an icon inside its grid cell. This box is positioned from
     * hand-set bounds instead, so that padding would push the × away from the corner it is meant to
     * sit in. Ignoring it here keeps the fix in our own file rather than in vendored code — the
     * same treatment [AresHomeListView] applies for the same reason.
     */
    private class EditCell(context: android.content.Context) : FrameLayout(context) {
        override fun setPadding(left: Int, top: Int, right: Int, bottom: Int) {
            // Intentionally empty.
        }
    }
}
