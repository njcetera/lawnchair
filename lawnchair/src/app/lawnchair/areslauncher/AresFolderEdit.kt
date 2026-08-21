package app.lawnchair.areslauncher

import android.animation.ValueAnimator
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.widget.FrameLayout
import com.android.launcher3.Launcher
import com.android.launcher3.R
import com.android.launcher3.Reorderable
import com.android.launcher3.ShortcutAndWidgetContainer
import com.android.launcher3.celllayout.CellLayoutLayoutParams
import com.android.launcher3.folder.Folder
import com.android.launcher3.folder.FolderIcon
import com.android.launcher3.model.data.ItemInfo
import com.android.launcher3.util.MultiTranslateDelegate.INDEX_REORDER_PREVIEW_OFFSET

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

    private const val TAG = "AresFolderEdit"

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

    /**
     * How long [folder] should wait over a new target position before rearranging, in ms.
     *
     * Called from `Folder.onDragOver` in place of the bare `REORDER_DELAY`. Returns the Ares value
     * **only for the one folder currently being edited on our home surface**, and hands back
     * [stockDelay] for everything else — an app-drawer folder, a taskbar folder, any folder outside
     * edit mode. The scope is the point: this changes the feel of the interaction the user asked
     * about and nothing else.
     *
     * ## Why this is the knob, and not a missing animation
     *
     * Establish before changing: `FolderPagedView.realTimeReorder` **already animates**, 230ms per
     * icon on a 30ms stagger via `CellLayout.animateChildToPosition`. Stacking a second animator on
     * that is exactly how the home grid ended up with four writers on one property.
     *
     * What makes it read as a jump is `Folder.onDragOver`, which cancels and re-arms this alarm on
     * *every* change of target rank. While the finger is moving the alarm is perpetually restarted
     * and the arrangement never moves at all; hold still for a quarter of a second and it all
     * shuffles at once. Shortening the wait is what turns that into flow.
     *
     * (The second half of the folder fix is not here: the edit-mode float used to write
     * `translationX` directly, which erases a `MultiTranslateDelegate` channel — including the very
     * one `animateChildToPosition` animates. See [AresEditMotion].)
     */
    @JvmStatic
    fun reorderDelayMs(folder: Folder, stockDelay: Int): Int =
        if (session?.folder === folder) AresEditMotion.FOLDER_REORDER_DELAY_MS else stockDelay

    /**
     * True when [x],[y] — in [icon]'s own coordinate space — fall on that icon's × badge.
     *
     * The badge lives in a sibling cell laid out over the icon with identical bounds, and the two
     * wiggle in phase, so the float cancels out and only **one** term separates their coordinate
     * spaces: the lift.
     *
     * That term is the price of §26. The lift is written to the icon and deliberately not to the
     * cell — the badge marks the cell and must stay on it while the icon slides to the middle —
     * which is exactly what makes a point in one space stop being a point in the other. The icon's
     * box has moved down by the lift relative to the cell's, so `cellY = iconY + lift`. Left
     * unmapped, the × keeps a 48dp target that no longer sits under the drawn glyph: with a ~36px
     * lift, the top third of the × is dead and an equal strip below it removes the app.
     *
     * (The home grid needs a mapping for a different reason: there the badge rides on a holder
     * container that edit mode scales. See `AresHomeListView.toChildLocal`.)
     *
     * Used by [AresFolderDrag.DragStarter] to leave a gesture that begins on the badge alone — it
     * is a tap on a control, not a drag handle.
     */
    fun isPointOnBadgeFor(folder: Folder, icon: View, x: Float, y: Float): Boolean {
        val cell = session?.takeIf { it.folder === folder }?.cellFor(icon) ?: return false
        return AresRemoveBadge.isPointOnBadge(cell, x, y + AresEditLabel.liftOf(icon))
    }

    private class Session(
        private val launcher: Launcher,
        val folder: Folder,
    ) : ViewTreeObserver.OnPreDrawListener, View.OnAttachStateChangeListener {

        /** Badge cell per icon view, so a cell is never added twice for the same icon. */
        private val cells = mutableMapOf<View, View>()

        /** Running floats, keyed by the view they move — icons and badge cells alike. */
        private val wiggles = mutableMapOf<View, ValueAnimator>()

        /**
         * Turns a plain touch-and-drag on any of this folder's icons into a folder drag.
         *
         * One instance for the session rather than one per icon: it holds only per-gesture state,
         * and a folder can only be dragged from one finger at a time.
         */
        private val dragStarter = AresFolderDrag.DragStarter(folder)

        /** The badge cell shadowing [icon], for hit-testing. */
        fun cellFor(icon: View): View? = cells[icon]

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
            // Icons are pooled and reused by FolderPagedView's view cache, so a listener left
            // behind would keep turning drags into folder drags in a folder that is not editing.
            for (icon in cells.keys) {
                icon.setOnTouchListener(null)
                // Cached views: a tap must launch again the moment the mode ends. This is the
                // same listener FolderPagedView.createNewView installs.
                icon.setOnClickListener(launcher.itemOnClickListener)
                // The label comes back for the same reason the click listener does, and it is the
                // more visible of the two failures: this runs both when edit mode ends with the
                // folder open AND when the folder simply closes (onViewDetachedFromWindow ->
                // detach -> stop), so it is the only restore point covering a folder that is shut
                // while still editing.
                AresEditLabel.resetItem(icon)
            }
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
                // LIFTED FOR A DRAG, not gone.
                //
                // Stock pulls the icon out of the container and into a DragView the moment a drag
                // starts, so its parent stops being the container while the folder still lists it
                // as content. Falling through to the sweep below then treats it as having left the
                // folder, which is destructive three ways at once: it drops the badge cell (the
                // reported symptom -- the app being held shows no editing chrome), it stops the
                // wiggle, and it RESTORES the launch click listener, so a tap on that app inside an
                // editing folder opens it again. That last one is the very defect the
                // `setOnClickListener(null)` further down exists to prevent, undone by a drag.
                //
                // Keeping it in `live` preserves all three across the drag. The cell is only
                // hidden, because a frosted box left hanging at the vacated slot reads as a second
                // item rather than as an absence.
                //
                // Measured on the owner's device mid-drag: a two-app folder whose container held
                // one icon and one EditCell, with a DragView carrying the other.
                val held = icon.parent as? ShortcutAndWidgetContainer
                if (held == null) {
                    live.add(icon)
                    cells[icon]?.let {
                        live.add(it)
                        it.visibility = View.INVISIBLE
                    }
                    // Keep the tree drawing while anything is lifted, or this pass may be the LAST
                    // one and the icon's return is never noticed.
                    //
                    // sync() is a pre-draw listener: it runs when the folder draws, and nothing
                    // else. Releasing an icon without having moved it puts it back with no reorder,
                    // no layout and therefore no draw -- so sync never runs again and the cell is
                    // never rebuilt. Nudge it first and the reorder forces a layout, sync runs, and
                    // the chrome appears. That is exactly the reported behaviour: "if I let go
                    // without miving it, it will NOT have frost, but if I move it a tad and then
                    // let go, it will have frost".
                    //
                    // One scheduled frame per lifted icon, and it stops as soon as nothing is
                    // lifted, so this cannot spin: a drag is already producing frames of its own.
                    folder.postInvalidateOnAnimation()
                    return@forEachIndexed
                }
                val parent = held
                val iconLp = icon.layoutParams as? CellLayoutLayoutParams ?: return@forEachIndexed
                val info = icon.tag as? ItemInfo ?: return@forEachIndexed
                live.add(icon)

                var cell = cells[icon]
                if (cell == null || cell.parent !== parent) {
                    (cell?.parent as? ViewGroup)?.removeView(cell)
                    cell = createCell(parent, info, iconLp.width)
                    cells[icon] = cell
                    parent.addView(cell, newCellParams())
                }
                live.add(cell)
                // Back in the container after a drag, if it was ever lifted.
                if (cell.visibility != View.VISIBLE) cell.visibility = View.VISIBLE

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

                // Re-sized every pass, not once at creation.
                //
                // createCell runs from the first sync, which can happen BEFORE the folder's icons
                // have been laid out — `iconLp.width` is then 0, the badge size computes to
                // ((0 - 2*margin) / 2).coerceAtLeast(1) = **1**, and since createCell only re-runs
                // when a cell's parent changes it never recovered. Measured: opening a folder by
                // tapping it while the grid was already editing, first time after a cold start,
                // gave four 1x1px badges in 202x231 cells — the × was there but unhittable. Closing
                // and reopening produced the correct 91px, which is exactly why every earlier check
                // missed it: they used the other open path.
                sizeBadges(cell, iconLp.width)

                // Re-asserted every pass for the same reason the badges are: arrangeChildren wipes
                // and re-adds the icons, and a recycled BubbleTextView comes back without it.
                // setOnTouchListener is idempotent for a listener already installed.
                icon.setOnTouchListener(dragStarter)

                // A tap inside an open folder must not launch anything either (§4/§18: "tapping an
                // item in edit mode does NOT launch it"). The grid has done this since the mode
                // shipped; the folder never did, so a tap anywhere on an icon that the × badge did
                // not happen to cover launched the app while the surface was supposedly inert.
                // Measured on emulator-5554: a tap at an icon's exact centre in an editing folder
                // opened Camera.
                //
                // **Clearing the listener, not `isClickable`.** The obvious `isClickable = false`
                // is what the grid uses and it does NOT work here -- also measured, same tap, same
                // launch. `View.onTouchEvent` computes `clickable` as CLICKABLE **or**
                // LONG_CLICKABLE, and these icons are long-clickable (that is how the popup is
                // raised from inside the mode), so the ACTION_UP branch still reaches
                // `performClickInternal()`. On the grid the flag is belt-and-braces behind
                // `editModeTouchListener` swallowing the terminal UP; a folder has no such
                // listener, so the listener itself has to go.
                //
                // `setOnLongClickListener` is untouched, so the popup still works. Restored in
                // [stop] and in the "gone" sweep below from `ActivityContext.getItemOnClickListener`
                // -- the exact listener `FolderPagedView.createNewView` installs -- because these
                // icons come from a view cache and a stuck null would leave an app unlaunchable
                // long after the mode ended.
                icon.setOnClickListener(null)

                // Icon and badge share a rect and a phase, so the × stays pinned to its corner
                // instead of drifting across a rotating icon.
                startWiggle(icon, index)
                startWiggle(cell, index)

                // No app names in here either, same as the grid (§26).
                //
                // Idempotent, so calling it every pre-draw is the re-measure: the first sync can
                // run before the folder has laid its icons out, and a lift measured from a height
                // of 0 is 0. The grid needs a separate reassert hook for this; here the funnel
                // already runs every frame.
                //
                // What makes this safe next to the badge is a channel separation, and it is the
                // whole reason an earlier attempt talked itself out of doing this at all:
                // everything AresEditMotion writes goes to INDEX_REORDER_BOUNCE_OFFSET, while the
                // badge below copies INDEX_REORDER_PREVIEW_OFFSET -- stock's channel, and stock's
                // only. So the badge follows the rearrangement slide and is blind to the lift,
                // which is exactly the split §21 wants: the frost box marks the CELL and stays
                // there while the icon moves to the middle of it.
                AresEditLabel.setItem(icon, true)

                // ...and share the slide, during a rearrangement. `animateChildToPosition` moves
                // the icon with a translation while leaving its layout params at the old cell, and
                // the badge's bounds are copied from those layout params -- so without this the ×
                // would sit at the old cell for the whole 230ms and then jump to the new one. Not
                // an animator: the value is copied from the one stock is already running.
                val slide = (icon as? Reorderable)?.translateDelegate
                if (slide != null) {
                    AresEditMotion.setFollow(
                        cell,
                        slide.getTranslationX(INDEX_REORDER_PREVIEW_OFFSET).value,
                        slide.getTranslationY(INDEX_REORDER_PREVIEW_OFFSET).value,
                    )
                }
            }

            // Anything that has left the folder: stop its animator and drop its badge. A wiggle
            // left running on a recycled BubbleTextView would keep rotating it somewhere else.
            val gone = wiggles.keys.filterNot { it in live } + cells.keys.filterNot { it in live }
            for (view in gone.distinct()) {
                // Before the wiggle, which clears the translation but knows nothing about the text
                // alpha. `gone` holds badge cells as well as icons; a cell falls straight through
                // this with nothing written. An app left at alpha 0 in a pooled view would come
                // back somewhere else with no name and no path to getting one.
                AresEditLabel.resetItem(view)
                wiggles.remove(view)?.let { AresEditWiggle.stop(view, it) }
                cells.remove(view)?.let { cell ->
                    (cell.parent as? ViewGroup)?.removeView(cell)
                    // The icon this cell shadowed has left the folder; take its drag starter and
                    // its suppressed click with it, for the same reason the wiggle is stopped --
                    // the view is pooled and will be bound to some other app.
                    view.setOnTouchListener(null)
                    view.setOnClickListener(launcher.itemOnClickListener)
                }
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
        /**
         * Brings both badges to the size [cellWidthPx] affords, if it is known yet.
         *
         * Half the cell each, less the margins, so the two abut at the centre and cannot overlap
         * for any cell width. Declines to act on a width of 0 rather than computing a size from
         * it — a badge that is briefly absent is recoverable, a badge sized 1px looks present and
         * is not usable, and nothing came back to correct it.
         */
        private fun sizeBadges(cell: View, cellWidthPx: Int) {
            if (cellWidthPx <= 0 || cell !is ViewGroup) return
            val margin = cell.resources.getDimensionPixelSize(R.dimen.ares_widget_resize_margin)
            val touch = ((cellWidthPx - 2 * margin) / 2).coerceAtLeast(1)
            for (tag in arrayOf(AresRemoveBadge.BADGE_TAG, AresInfoBadge.BADGE_TAG)) {
                val badge = cell.findViewWithTag<View>(tag) ?: continue
                val lp = badge.layoutParams as? FrameLayout.LayoutParams ?: continue
                if (lp.width != touch || lp.height != touch) {
                    lp.width = touch
                    lp.height = touch
                    badge.layoutParams = lp
                }
            }
        }

        private fun createCell(parent: ViewGroup, info: ItemInfo, cellWidthPx: Int): View {
            val cell = EditCell(parent.context)
            cell.tag = CELL_TAG

            // The frost, same drawable the home grid uses (§23/§25).
            //
            // It was originally home-only, on the owner's instruction that it "makes sense for it
            // to just be on the home screen edit and not inside folders when editing". They
            // reversed that after the badges moved into their corners, and the reason is structural
            // rather than cosmetic: on the grid the frost box IS the visible cell boundary, so a
            // corner-anchored badge reads as anchored because there is a rectangle for it to be in
            // the corner of. Without the box the same badge is a circle hovering near an icon's
            // edge -- "they'll just look like it's floating on apps in folders without the blur
            // background [to] ground them to the app".
            //
            // Watch the layering rather than assuming this is free: an open folder already draws
            // its own translucent panel, so this is frost over frost. If it reads muddy the answer
            // is a lower alpha here, not removing it -- the box is load-bearing for the badges now.
            //
            // Inset so neighbouring frost boxes do not touch, WITHOUT reporting padding.
            //
            // Folder cells are laid out edge to edge and the frost is the cell's background, so it
            // fills that rect exactly; during a reorder the cells follow their icons past one
            // another and the boxes visibly collide.
            //
            // A plain InsetDrawable is the obvious fix and it is a trap. `View.setBackground`
            // applies a background's `getPadding` through `internalSetPadding`, which does NOT
            // route through the overridable `setPadding` that [EditCell] no-ops for exactly this
            // reason — so the inset became real cell padding, the badges' own margins stacked on
            // it, and two 91px badges stopped fitting across a 202px cell (measured: 20x91px of
            // overlap, the ! stealing that strip from the ×).
            //
            // Declining to report padding keeps the visual inset and leaves the cell's own box
            // alone, so the badges still size and place against the full cell.
            val frostInset =
                cell.resources.getDimensionPixelSize(R.dimen.ares_home_widget_inset) / 2
            cell.background = object : android.graphics.drawable.InsetDrawable(
                AresEditGrid.cellOutline(cell.context),
                frostInset,
                frostInset,
                frostInset,
                frostInset,
            ) {
                override fun getPadding(padding: android.graphics.Rect): Boolean {
                    padding.set(0, 0, 0, 0)
                    return false
                }
            }

            // Both badges take a REDUCED target here. A folder cell is ~83dp (202x240px measured):
            // two 48dp targets side by side need 234px of a 202px width and would overlap by about
            // 52px, leaving which control a tap reached down to draw order. Halving the width less
            // the margins is what actually fits. This is below the 44dp guideline and that is a
            // real cost, mitigated by EditCell.dispatchTouchEvent already handing the icon's centre
            // back to the icon -- so the miss case is "the drag starts" rather than "the wrong
            // destructive control fires".
            // Sized from the CELL's own width, which is the icon's layout width.
            //
            // The first cut derived it as `parent.width / columnsOf(parent)`, and `columnsOf` cast
            // the parent to CellLayout to read `countX` -- but the parent here is a
            // ShortcutAndWidgetContainer, so the cast ALWAYS failed and it fell back to 4. A
            // two-column folder was therefore measured as four: 404/4 - 20, halved, gave badges of
            // **40px** where the cell affords 91. Measured on the owner's device: two 40x40
            // ImageViews at (10,10) and (152,10) in a 202-wide cell, which reads as a pair of ticks
            // rather than controls. A fallback that silently produces a plausible-looking wrong
            // number is worse than one that throws.
            //
            // The cell width is handed in rather than derived, so there is nothing left to get
            // wrong: it is the same `iconLp.width` the cell's own bounds are copied from.
            val margin = cell.resources.getDimensionPixelSize(R.dimen.ares_widget_resize_margin)
            val touch = ((cellWidthPx - 2 * margin) / 2).coerceAtLeast(1)

            // Named for the same reason as on the grid: a folder of six apps would otherwise offer
            // six controls that all announce themselves as "Remove".
            cell.addView(
                AresRemoveBadge.createBadge(cell, info.title, touch) { removeFromFolder(info) },
            )
            if (AresInfoBadge.hasMenu(info)) {
                cell.addView(
                    AresInfoBadge.createBadge(cell, info.title, touch) { showMenuFor(info) },
                )
            }
            return cell
        }

        /**
         * Raises the context menu for an app **inside** the folder (§25).
         *
         * Anchors to the icon the folder is drawing, not to our overlay cell: the popup positions
         * itself against a `BubbleTextView`, and the overlay is a transparent box sitting on top of
         * one. Edit mode is left alone, exactly as on the grid — the menu is asked for *while*
         * arranging, so dropping the mode would discard the arrangement in progress.
         */
        private fun showMenuFor(info: ItemInfo) {
            val icon = cells.entries.firstOrNull { (view, _) ->
                (view as? com.android.launcher3.BubbleTextView)?.tag === info
            }?.key
            if (!AresInfoBadge.showMenu(icon)) {
                android.util.Log.w(TAG, "no menu could be shown for ${info.targetComponent}")
            }
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

        /**
         * Declines a touch that starts at the exact centre of the icon, so it reaches the icon
         * instead of the ×.
         *
         * A folder cell is ~83dp and the badge is a 48dp target inset 4dp into its top-start
         * corner, so **the badge's target covers the icon's centre**. Aiming at the middle of an
         * icon to drag it therefore lands on Remove, and a tap with no movement removes the app
         * from the home screen — the most destructive form of the dead-spot defect the home grid
         * has in a milder version.
         *
         * Returning false rather than consuming is what makes this work:
         * `ViewGroup.dispatchTouchEvent` only records a touch target on ACTION_DOWN and walks
         * children front-to-back until one accepts, so declining here hands the whole gesture to
         * the icon underneath — which is exactly where a drag needs it. No later event is routed
         * back to this cell, so there is nothing to latch.
         *
         * Radius and rationale: [AresEditMotion.DRAG_PRIORITY_RADIUS_DP].
         */
        override fun dispatchTouchEvent(ev: android.view.MotionEvent): Boolean {
            if (ev.actionMasked == android.view.MotionEvent.ACTION_DOWN &&
                AresEditMotion.isInDragPriorityZone(this, ev.x, ev.y)
            ) {
                return false
            }
            return super.dispatchTouchEvent(ev)
        }
    }
}
