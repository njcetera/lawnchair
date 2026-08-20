package app.lawnchair.areslauncher

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Matrix
import android.os.SystemClock
import android.util.Log
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.RecyclerView
import com.android.launcher3.Launcher
import com.android.launcher3.R
import com.android.launcher3.celllayout.CellLayoutLayoutParams
import com.android.launcher3.folder.FolderIcon
import com.android.launcher3.model.data.ItemInfo
import com.android.launcher3.model.data.LauncherAppWidgetInfo
import com.android.launcher3.widget.LauncherAppWidgetHostView

/**
 * Continuously-scrolling **masonry grid** of home-screen items, replacing CellLayout's paged grid
 * inside Workspace's single page.
 *
 * Placement follows Windows Phone Start semantics: items pack from the top with no holes, removal
 * compacts everything after it upward, and insertion pushes subsequent items down. All of that
 * falls out of one rule -- [AresPacker]'s greedy first-fit over `rank` order -- with
 * [AresMasonryLayoutManager] turning the resulting cells into pixels. Position is *derived*, never
 * stored: the model persists `rank` plus each item's `(spanX, spanY)` and nothing else. See
 * design/requirements-alignment.md §4 and design/scrolling-grid-home.md.
 *
 * This replaced an earlier one-item-per-row list. The data wiring survived that change unchanged --
 * an ordered list already had the no-holes property by construction; only the rendering differs.
 *
 * Hosted as a child of the active page's [com.android.launcher3.ShortcutAndWidgetContainer]
 * (see Workspace.getOrCreateAresHomeList). That container is what
 * WorkspaceStateTransitionAnimation applies VIEW_ALPHA to, and it lives under Workspace, which
 * receives WORKSPACE_SCALE_PROPERTY -- so hosting here means the list inherits workspace alpha,
 * scale and page translation for free, and PagedView keeps first claim on horizontal drags.
 *
 * An earlier revision attached this to the DragLayer instead. That made it a *sibling* of
 * Workspace, which inherited none of the above and, being the topmost DragLayer child, swallowed
 * every touch before PagedView saw it -- killing the Discover feed swipe. See
 * design/architecture-reassessment.md §0.
 */
class AresHomeListView(context: Context, private val launcher: Launcher) : RecyclerView(context) {

    val aresAdapter = AresHomeAdapter(launcher)

    private val masonry = AresMasonryLayoutManager { position -> aresAdapter.spanOf(position) }

    private val itemTouchHelper = ItemTouchHelper(AresHomeReorder.Callback(launcher, this))

    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop

    /**
     * The edit-mode corner dots (§14), drawn beneath the items and scrolling with them.
     *
     * Added once and left in place; it draws nothing until [editDots].progress rises above zero,
     * so there is no cost outside edit mode and no decoration to add and remove.
     */
    private val editDots = AresEditGrid.Dots(context, masonry)

    init {
        layoutManager = masonry
        adapter = aresAdapter
        clipToPadding = false
        addItemDecoration(editDots)
        applyGridMetrics()
        itemTouchHelper.attachToRecyclerView(this)
        aresAdapter.editModeHost = { enterEditMode() }
        aresAdapter.gridColumns = { masonry.columns }
        aresAdapter.resizeHost = { info -> cycleWidgetSize(info) }
        aresAdapter.removeHost = { info -> removeFromHome(info) }
    }

    /**
     * The attached host view for [appWidgetId], for `Workspace.getWidgetForAppWidgetId` (§7).
     *
     * Launcher uses that lookup to find the `PendingAppWidgetHostView` it put up while a configure
     * activity was running, so it can replace it in place when the activity returns. Stock searches
     * `CellLayout` children and finds nothing of ours, which cost an extra database row per widget
     * added through a configure activity — see the call site for the full failure.
     *
     * Only *attached* rows can be returned: the adapter is data-backed and a scrolled-off row has no
     * host view to hand back. That is not a hole in the fix, because everything downstream
     * (`reInflate`, `updateWidgetSizeRanges`) needs an attached view anyway; the row-level safety net
     * for the off-screen case is [AresHomeAdapter.addItem]'s duplicate collapse.
     */
    fun findWidgetForAppWidgetId(appWidgetId: Int): LauncherAppWidgetHostView? {
        for (i in 0 until childCount) {
            val container = getChildAt(i) as? ViewGroup ?: continue
            val hostView = container.getChildAt(0) as? LauncherAppWidgetHostView ?: continue
            val info = hostView.tag as? LauncherAppWidgetInfo ?: continue
            if (info.appWidgetId == appWidgetId) return hostView
        }
        return null
    }

    /**
     * Advances a widget to its next allowed footprint (§6).
     *
     * The whole operation is: change the spans, repack, persist. Position is derived from order and
     * footprint, never stored, so there is no coordinate to recompute and no occupancy to validate
     * -- a resize cannot fail for want of space, items simply reflow around the new size. That is
     * why stock's `createAreaForResize` has no counterpart here.
     *
     * The repack is triggered by [AresMasonryLayoutManager.invalidatePacking], which drops the
     * cached packing so the next layout pass re-runs [AresPacker] over the new footprints.
     *
     * Deliberately **not** `notifyItemChanged`: widget holders are `setIsRecyclable(false)`, so a
     * rebind cannot reuse the existing holder — RecyclerView builds a second one and leaves the
     * first attached. Observed directly as **four host views for two widgets**, one still drawn at
     * its pre-resize size. Invalidating the packing keeps the existing host view and simply hands
     * it a new box, which is both correct and far cheaper than re-creating a widget.
     *
     * Because nothing rebinds, the widget has to be re-registered for a size report explicitly, or
     * the provider keeps rendering RemoteViews measured for its old box.
     *
     * The model is committed **before** anything visual happens. [AresWidgetResize.persistSize]
     * mutates the item only if the new footprint can be placed legally, so a failure leaves the
     * item untouched and this simply returns — the view and the database cannot drift apart, and
     * there is no revert path to get wrong.
     */
    /**
     * Takes [info] off the home screen and lets the grid compact around the gap.
     *
     * Removes from the adapter first so the change is on screen immediately, then writes the model.
     * The order matters for feel, not correctness: waiting on the write would leave the tile under
     * the finger for a beat after a tap that clearly meant "go away".
     *
     * **Removes from the home screen only — never uninstalls.** The app stays installed and stays
     * in the app list; uninstall lives in the long-press popup. See [AresRemoveBadge].
     *
     * No explicit repack call: positions are derived from the resulting order, so compaction is
     * simply what the next layout pass computes. This is the first interaction that exercises that
     * property against real removals rather than the packer's own tests.
     */
    private fun removeFromHome(info: ItemInfo) {
        val id = info.id
        val removed = aresAdapter.removeItems { it.id == id }
        if (!removed) return

        AresRemoveBadge.removeFromHome(launcher, info)
        masonry.animateNextLayout()
        masonry.invalidatePacking()

        announceForAccessibility(
            context.getString(com.android.launcher3.R.string.item_removed),
        )
    }

    private fun cycleWidgetSize(info: ItemInfo) {
        // Every path out of this function says something. A visible affordance that declines to act
        // must not do so silently: the last defect here produced a chevron that did nothing at all,
        // and the absence of any log is what made "the click never arrived" indistinguishable from
        // "the resize was refused". persistSize logs both of its own refusals for the same reason.
        val allowed = AresWidgetResize.allowedSizes(launcher, info, masonry.columns)
        if (allowed.isEmpty()) {
            Log.d(
                TAG,
                "resize declined: no allowed sizes for id=${info.id} at " +
                    "${info.spanX}x${info.spanY}, columns=${masonry.columns}",
            )
            return
        }

        val current = AresPacker.Span(info.spanX.coerceAtLeast(1), info.spanY.coerceAtLeast(1))
        val next = AresWidgetResize.nextSize(current, allowed)
        if (next.w == current.w && next.h == current.h) {
            Log.d(TAG, "resize declined: id=${info.id} already the only allowed ${current.w}x${current.h}")
            return
        }

        if (!AresWidgetResize.persistSize(launcher, info, next)) return

        val position = aresAdapter.indexOf(info)
        if (position >= 0) {
            (findViewHolderForAdapterPosition(position) as? AresHomeAdapter.ViewHolder)
                ?.let { aresAdapter.reportSizeAfterResize(info, it.container) }
        }
        // Animate the repack: the widget grows into the space, and the items it displaces slide
        // rather than teleport. Without this the whole grid re-lays out in a single frame, which
        // reads as a glitch rather than as a size change.
        masonry.animateNextLayout()
        masonry.invalidatePacking()

        announceForAccessibility(
            context.getString(com.android.launcher3.R.string.widget_resized, next.w, next.h),
        )
    }

    // ---------------------------------------------------------------- edit mode

    /**
     * Windows Phone Start-screen edit mode: a **persistent** state the whole surface enters, rather
     * than Android's one-shot long-press-then-drag.
     *
     * Entered by long-pressing an item ([AresHomeAdapter] calls [enterEditMode]); left via the back
     * button ([com.android.launcher3.Launcher.onStateBack]) or a tap on empty space. While it is
     * active every item can be dragged with a plain touch-and-move, so several can be rearranged in
     * one session — which is the whole point of it being a mode. See requirements-alignment.md §4.
     */
    private var editMode = false

    /**
     * True when the in-flight gesture is the long-press that entered edit mode.
     *
     * That gesture's UP must not be read as a tap, or the mode would end the instant it began.
     */
    private var enteredEditModeDuringGesture = false

    /**
     * Running edit-mode float animators, keyed by the holder container they move.
     *
     * Held so they can be cancelled individually as rows recycle and wholesale on exit. An
     * animator left running on a recycled view would keep moving whatever item was bound into it
     * next, including outside edit mode.
     */
    private val wiggles = mutableMapOf<View, ValueAnimator>()

    /**
     * The tile whose float is suspended because [ItemTouchHelper] is dragging it.
     *
     * `ItemTouchHelper` writes `translationX/Y` on the dragged view every frame to keep it under
     * the finger, and [AresEditWiggle] writes the same two properties — so the float has to stand
     * down for the duration of the drag rather than trade the property with it. It is also the
     * behaviour that reads correctly: a lifted tile should be still, not still drifting.
     */
    private var floatSuspendedFor: View? = null

    /**
     * True while the grid is in edit mode.
     *
     * Also read by [AresPaneSwipeController] via `Workspace.isAresEditMode()`, so a horizontal drag
     * while editing reorders the grid instead of pulling the app-list pane in.
     */
    fun isEditMode(): Boolean = editMode

    /**
     * Enters edit mode.
     *
     * Called from the item long-press handler, which raises **only** this — the context popup is
     * kept for a long-press made from *inside* the mode. One gesture, one state, so one dismissal
     * gesture is enough to undo it. See the long-click listener in [AresHomeAdapter] for why the
     * two used to come up together and why they no longer do.
     */
    fun enterEditMode() {
        if (editMode) return
        editMode = true
        enteredEditModeDuringGesture = true
        // Claim the rest of THIS gesture for the grid, so the long-press can continue straight into
        // a drag without lifting -- which is the natural motion, and did not work.
        //
        // Two ancestors were taking it, both because they decide at ACTION_DOWN, when edit mode was
        // still off. Measured on the emulator by holding an icon until the badges appeared and then
        // dragging without lifting:
        //
        //  - leftward, the app-list pane opened instead (mState Normal -> AllApps) and the tile did
        //    not move. AresPaneSwipeController runs from BaseDragLayer.findControllerToHandleTouch
        //    and latches its answer in mNoIntercept at DOWN, so re-checking isAresEditMode() there
        //    can never see a mode entered later in the same gesture.
        //  - rightward, nothing happened at all: Workspace is a PagedView and intercepts horizontal
        //    drags to change pages, so the grid was starved of the moves that would have started
        //    the reorder.
        //
        // requestDisallowInterceptTouchEvent covers both at once, because the flag propagates the
        // whole way up and ViewGroup.dispatchTouchEvent skips onInterceptTouchEvent entirely while
        // it is set -- and BaseDragLayer's controller dispatch *is* an onInterceptTouchEvent. It is
        // cleared automatically at the next ACTION_DOWN, and explicitly on this gesture's UP/CANCEL
        // by the touch listener below, which already issues the same call for gestures that begin
        // inside the mode.
        parent?.requestDisallowInterceptTouchEvent(true)
        // Set BEFORE applyEditModeVisual: that walk calls back into the adapter to add chevrons,
        // and would otherwise read the previous mode and add none (§6).
        aresAdapter.setEditMode(true)
        applyEditModeVisual()
    }

    /** Leaves edit mode, cancelling any in-flight drag. Safe to call when not in edit mode. */
    fun exitEditMode(): Boolean {
        if (!editMode) return false
        editMode = false
        setReorderInProgress(false)
        // An open folder's × badges belong to this mode too. They normally go with the folder --
        // it closes before edit mode can be left -- but HOME closes floating views and exits the
        // mode in one pass, so the two orders must both end clean.
        AresFolderEdit.detach()
        // Set before the visual walk, for the same reason as enterEditMode (§6).
        aresAdapter.setEditMode(false)
        // Cancel every animator up front rather than relying on the per-child walk: children that
        // scrolled out while editing are no longer attached, so the walk would never reach them and
        // their animators would outlive the mode.
        clearWiggles()
        applyEditModeVisual()
        return true
    }

    /**
     * Scales items down slightly while editing.
     *
     * Some signal is required or the mode is invisible and the user cannot tell why taps stopped
     * launching things. A uniform scale on the holder is the Windows Phone cue, costs nothing, and
     * leaves the item's own bounds free for the resize chevron a later increment will add.
     */
    private fun applyEditModeVisual() {
        val scale = if (editMode) EDIT_MODE_SCALE else 1f
        // The layout manager composes its repack animation on top of this, and must not have to
        // guess it from a child that may be mid-animation.
        masonry.restScale = scale
        for (i in 0 until childCount) {
            val child = getChildAt(i)
            child.animate().scaleX(scale).scaleY(scale).setDuration(120).start()
            setItemClickable(child, !editMode)
            syncChevron(child)
            syncWiggle(child)
        }
        animateGridDots()
    }

    /** Running fade for the grid dots, cancelled before a new one so the two cannot fight. */
    private var dotsAnimator: ValueAnimator? = null

    /**
     * Fades the grid dots in and out with the mode (§14).
     *
     * Animated rather than switched because they appear alongside the wiggle, and a set of dots
     * snapping on at the same instant reads as a glitch next to it. The list is invalidated on each
     * frame because an `ItemDecoration`'s output is not otherwise re-drawn when nothing has scrolled.
     */
    private fun animateGridDots() {
        dotsAnimator?.cancel()
        val target = if (editMode) 1f else 0f
        if (!ValueAnimator.areAnimatorsEnabled()) {
            editDots.progress = target
            invalidate()
            return
        }
        dotsAnimator = ValueAnimator.ofFloat(editDots.progress, target).apply {
            duration = DOTS_FADE_MS
            addUpdateListener {
                editDots.progress = it.animatedValue as Float
                invalidate()
            }
            start()
        }
    }

    /**
     * Starts or stops one row's edit-mode float.
     *
     * ## Why any motion at all
     *
     * The scale-down alone proved too subtle to notice, so there was no way to tell whether the
     * surface was editable — which made taps that no longer launched look broken rather than
     * intentional. The motion is the iOS/Windows Phone cue, and crucially **its absence is the
     * signal that you are back to normal**, so exiting has visible feedback too.
     *
     * The amplitude, period and per-item phase offset live in [AresEditWiggle], shared with the
     * apps inside an open folder — the two surfaces are the same mode and have to look like it.
     *
     * ## On the container
     *
     * Applied to the holder container rather than the item view, matching the scale and the two
     * affordances — so the × and the chevron move *with* the tile instead of sitting still over a
     * drifting icon. (For a widget the item view is an `AppWidgetHostView`, whose children the
     * provider replaces on every update; the container is the only stable place to attach to.)
     *
     * Honours the system animator scale: with animations off, items simply hold the edit-mode
     * scale, which still distinguishes the mode without motion.
     */
    private fun syncWiggle(child: View) {
        AresEditWiggle.stop(child, wiggles.remove(child))
        if (!editMode || child === floatSuspendedFor) return
        AresEditWiggle.start(child, getChildAdapterPosition(child))?.let { wiggles[child] = it }
    }

    /**
     * Suspends the float on the tile [child] while it is being dragged, or resumes it when passed
     * null.
     *
     * Called from [AresHomeReorder.Callback] at the two points where `ItemTouchHelper` takes and
     * releases ownership of the view's translation — `onSelectedChanged(ACTION_STATE_DRAG)` and
     * `clearView`. `clearView`'s superclass call has already reset the translation to zero by the
     * time this restarts the float, so the tile resumes from rest rather than from wherever the
     * drop settled.
     */
    fun setFloatSuspendedFor(child: View?) {
        val previous = floatSuspendedFor
        if (previous === child) return
        floatSuspendedFor = child
        if (previous != null) syncWiggle(previous)
        if (child != null) syncWiggle(child)
    }

    /** Stops every float and puts every tile back at rest, so nothing is left displaced. */
    private fun clearWiggles() {
        for ((child, animator) in wiggles) {
            AresEditWiggle.stop(child, animator)
        }
        wiggles.clear()
        floatSuspendedFor = null
    }

    /**
     * Brings one attached row's resize chevron in line with the current mode.
     *
     * Adds and removes the view directly instead of rebinding, because widget holders are
     * non-recyclable: `notifyItemChanged` on one builds a *second* holder and leaves the first
     * attached, which leaked a widget host view per toggle.
     *
     * Note the chevron rides on the holder container, which is also what the edit-mode scale
     * animates — so it transforms with the item rather than sitting still over it.
     */
    private fun syncChevron(child: View) {
        val container = child as? FrameLayout ?: return
        // Deliberately no early return on NO_POSITION. A row that has left the adapter -- removed,
        // or mid-animation on its way out -- is still an attached child carrying our × and chevron,
        // and skipping it is precisely how those were left behind after edit mode ended: the user
        // reported "the x won't leave and the resize button on one won't leave but neither are
        // actually editable", with the state dump confirming mState:Normal. getOrNull(-1) resolves
        // to a null item, which the adapter already reads as "this row carries nothing".
        aresAdapter.syncChevron(container, getChildAdapterPosition(child))
    }

    override fun onChildAttachedToWindow(child: View) {
        super.onChildAttachedToWindow(child)
        // Rows bound while already editing (recycled in on scroll) must match the current mode.
        val scale = if (editMode) EDIT_MODE_SCALE else 1f
        child.scaleX = scale
        child.scaleY = scale
        setItemClickable(child, !editMode)
        syncWiggle(child)
        // Affordances too, and not only for rows that were just bound: widget holders are
        // `setIsRecyclable(false)`, so one that scrolls off and back on re-attaches *without*
        // onBindViewHolder running. Such a row kept whatever badge it had when it left, which
        // outlived the mode in both directions -- a stale × after exiting, and no × at all on a row
        // that scrolled in after editing began.
        syncChevron(child)
    }

    override fun onChildDetachedFromWindow(child: View) {
        super.onChildDetachedFromWindow(child)
        // Stop the animator with the view it belongs to. Left running, it would keep moving a
        // recycled view after a different item had been bound into it.
        wiggles.remove(child)?.cancel()
        // Clear the float's displacement -- but never on the tile ItemTouchHelper is dragging.
        // That view carries the drag's own translation and no float, so zeroing it here would
        // snap it off the finger.
        if (child !== floatSuspendedFor) AresEditWiggle.reset(child)
    }

    /**
     * Enables or disables the tile's click handling.
     *
     * Suppressing the launch by consuming the touch stream was tried first and does not work:
     * the click still fired even with the terminal `ACTION_UP` consumed by an
     * `OnItemTouchListener` (verified on device — the branch was taken and Gmail launched anyway).
     * Clearing `isClickable` on the item view itself is unambiguous and cannot be defeated by
     * touch-routing subtleties.
     *
     * The click listener installed by `ItemInflater` is left in place, so nothing needs restoring
     * beyond the flag.
     *
     * **Folders stay clickable in edit mode** (§18). The "tap is inert while editing" rule exists
     * so that a tap never *launches* anything; opening a folder launches nothing, it descends into
     * a container while the mode stays active. `onClickFolderIcon` only calls `animateOpen()`, so
     * nothing here reaches a launch path.
     */
    private fun setItemClickable(child: View, clickable: Boolean) {
        val item = (child as? ViewGroup)?.getChildAt(0) ?: return
        item.isClickable = clickable || item is FolderIcon
    }

    /** The folder icon [child] (a holder container) hosts, or null when it hosts anything else. */
    private fun folderIconOf(child: View?): FolderIcon? =
        (child as? ViewGroup)?.getChildAt(0) as? FolderIcon

    /**
     * The tile at [x],[y] that a drag could be dropped **into**, ignoring the one being dragged.
     *
     * Not `findChildViewUnder`, for two reasons that both matter here:
     *
     *  - **The dragged tile is in the way.** `ItemTouchHelper` translates it to follow the finger,
     *    so it is always the topmost view under the drag point and the stock lookup would answer
     *    with the item being dragged, every time.
     *  - **Translation must be ignored, not honoured.** `findChildViewUnder` adds each child's
     *    `translationX/Y`, which in edit mode is [AresEditWiggle]'s float — a few pixels of
     *    continuous motion. Hit-testing against a moving rectangle makes the dwell's "held still"
     *    test depend on where in the wiggle cycle the frame landed. Layout bounds are what the user
     *    is aiming at.
     *
     * @param excludeId the dragged item's id, or [ItemInfo.NO_ID] to consider every tile.
     */
    fun dropCandidateUnder(x: Float, y: Float, excludeId: Int): View? {
        for (i in childCount - 1 downTo 0) {
            val child = getChildAt(i) ?: continue
            if (x < child.left || x >= child.right || y < child.top || y >= child.bottom) continue
            val info = aresAdapter.itemAt(getChildAdapterPosition(child))
            if (info == null || (excludeId != ItemInfo.NO_ID && info.id == excludeId)) continue
            return child
        }
        return null
    }

    /**
     * The adapter index an item released at [x],[y] — in this list's own coordinates — should take.
     *
     * The grid's whole position model is the ordered sequence (§4: `rank` plus a footprint, no
     * stored x/y), so "where did they drop it" has exactly one answer shape: an **insertion index**.
     * Everything after it shifts down and the packer re-derives the pixels.
     *
     * Three cases, in the order they are asked:
     *
     *  1. **On a tile** — take that tile's index, pushing it and everything after it down. This is
     *     the Windows Phone rule the packing model already follows: *"reorg allows items to be
     *     added to the end or throughout the existing grid but to make room it would push things
     *     down"*.
     *  2. **In the empty part of a row** — insert after the last tile whose right edge is left of
     *     the point, so dropping into the gap at the end of a half-filled row puts the item there
     *     rather than at the end of the grid. Under masonry that gap is common: a tall widget
     *     leaves one beside it on every row it spans.
     *  3. **Off the ends** — above everything is index 0, below everything is an append.
     *
     * Deliberately **not** "nearest tile by distance". Nearest is unstable exactly where precision
     * matters — at a boundary it flips between two answers for a one-pixel move — and it gives no
     * way to express "before the first item", which case 3 needs.
     */
    fun dropIndexAt(x: Float, y: Float): Int {
        dropCandidateUnder(x, y, ItemInfo.NO_ID)?.let { direct ->
            val position = getChildAdapterPosition(direct)
            if (position != NO_POSITION) return position
        }

        var afterIndex = NO_POSITION
        var afterRight = Int.MIN_VALUE
        var rowFirstIndex = NO_POSITION
        var rowFirstLeft = Int.MAX_VALUE
        var anyAbove = false
        var anyBelow = false

        for (i in 0 until childCount) {
            val child = getChildAt(i) ?: continue
            val position = getChildAdapterPosition(child)
            if (position == NO_POSITION) continue
            if (child.bottom <= y) anyAbove = true
            if (child.top > y) anyBelow = true
            // The row band is by vertical overlap, not by row index: a 2-cell-tall widget belongs
            // to every band it crosses, which is what makes case 2 work beside one.
            if (y < child.top || y >= child.bottom) continue
            if (child.right <= x && child.right > afterRight) {
                afterRight = child.right
                afterIndex = position
            }
            if (child.left < rowFirstLeft) {
                rowFirstLeft = child.left
                rowFirstIndex = position
            }
        }

        if (afterIndex != NO_POSITION) return afterIndex + 1
        if (rowFirstIndex != NO_POSITION) return rowFirstIndex
        if (anyBelow && !anyAbove) return 0
        return aresAdapter.itemCount
    }

    /**
     * The ring marking the tile a dwelling drag would drop into ([AresFolderDrop]).
     *
     * Added once and left in place, like [editDots]: it draws nothing while its progress is zero,
     * so there is no decoration to attach and detach around a drag.
     */
    private val dropRing = AresEditGrid.DropRing(context)

    private var dropRingAnimator: ValueAnimator? = null

    /**
     * Marks [child] as the armed drop target, or clears the mark when passed null.
     *
     * Faded rather than switched, for the same reason the grid dots are: it appears mid-drag beside
     * a wiggling grid, and a hard edge snapping on there reads as a rendering glitch rather than as
     * feedback. The list is invalidated per frame because an `ItemDecoration` is not otherwise
     * re-drawn when nothing scrolls — and during an external drag nothing else is animating at all.
     */
    fun setFolderDropTarget(child: View?) {
        if (child != null) dropRing.target = child
        dropRingAnimator?.cancel()
        val target = if (child != null) 1f else 0f
        if (!ValueAnimator.areAnimatorsEnabled()) {
            dropRing.progress = target
            if (target == 0f) dropRing.target = null
            invalidate()
            return
        }
        val clearing = child == null
        dropRingAnimator = ValueAnimator.ofFloat(dropRing.progress, target).apply {
            duration = DROP_RING_FADE_MS
            // The reference is dropped from the update listener rather than an end listener:
            // cancelling an animator still runs its end listener, so a fade-out cancelled by a new
            // fade-in would clear the target that had just been set.
            //
            // `clearing` is not redundant with the progress check, and leaving it out cost a whole
            // verification pass: ValueAnimator delivers its FIRST update with the start value, so a
            // fade-IN from 0 reported progress 0 and immediately nulled the target it had just been
            // given. Every later frame then had a progress to draw at and nothing to draw around,
            // and the ring never appeared once -- with no error, and the drop itself working
            // perfectly, so the only symptom was an affordance that was simply absent.
            addUpdateListener {
                dropRing.progress = it.animatedValue as Float
                if (clearing && dropRing.progress <= 0f) dropRing.target = null
                invalidate()
            }
            start()
        }
    }

    /** Scratch for [toChildLocal]; the caller uses the result before the next call, on one thread. */
    private val localPoint = FloatArray(2)
    private val inverseChildMatrix = Matrix()

    /**
     * Maps a point in this list's coordinates into [child]'s own, **honouring the child's
     * transform**.
     *
     * ## Why this cannot be `x - child.left`
     *
     * Edit mode holds a scale (and a wiggle rotation) on every holder container, so a tile is drawn
     * somewhere other than its layout box. `ViewGroup.dispatchTouchEvent` accounts for that — it
     * subtracts the child's origin and then maps through the child's **inverse matrix** before
     * asking which grandchild was hit. This listener has to reach the same answer, because the two
     * decisions are halves of one behaviour: it declines to swallow the terminal UP precisely when
     * the framework is about to deliver the tap to an affordance.
     *
     * When they disagree, both outcomes are wrong and neither is visible in a log:
     *
     *  - Tap where the affordance is **drawn** and this said "not on it", so the UP was swallowed as
     *    a tile tap and the chevron's click was cancelled. That is the "resize gets stuck" report —
     *    every tap after the first was inert, with no trace anywhere.
     *  - Tap where it is **laid out** and this said "on it", so the UP was let through — but the
     *    framework routed the touch to the widget instead, whose RemoteViews **launched its app**,
     *    breaking the rule that a tap in edit mode never launches anything.
     *
     * Measured on the emulator against build `ca1b933`, both reproduced on the same widget.
     *
     * The transform that caused it is gone (see the ⛔ note in [AresMasonryLayoutManager]), but the
     * edit-mode scale remains and any future one would revive the bug, so the mapping is done
     * properly rather than assumed away. `scrollX`/`scrollY` are included for the same reason: they
     * are 0 today because the layout manager offsets children itself, and this stays correct if that
     * ever changes.
     */
    private fun toChildLocal(child: View, x: Float, y: Float): FloatArray {
        localPoint[0] = x + scrollX - child.left
        localPoint[1] = y + scrollY - child.top
        val matrix = child.matrix
        // A degenerate (non-invertible) matrix means the tile is scaled to nothing and cannot be
        // hit anyway; leaving the untransformed point is then as good an answer as exists.
        if (!matrix.isIdentity && matrix.invert(inverseChildMatrix)) {
            inverseChildMatrix.mapPoints(localPoint)
        }
        return localPoint
    }

    /**
     * Routes touches while editing.
     *
     * - **Drag**: past the touch slop on an item, hands the gesture to [ItemTouchHelper] via
     *   `startDrag`. Edit mode replaces its built-in long-press trigger, which is the Android
     *   one-shot model rather than a persistent mode. A **fresh** touch must additionally have been
     *   held for [PICKUP_HOLD_MS] first (§G5); the long-press that *entered* the mode is exempt.
     * - **Drag on an item before that hold has elapsed**: declined, so the grid scrolls. The
     *   gesture forfeits the pick-up for its whole life, not just until the hold time passes.
     * - **Tap on an item**: consumed at `ACTION_UP` so the child's click never fires — WP behaviour,
     *   where you leave edit mode before launching anything.
     * - **Tap on a folder**: *not* consumed, so the folder opens (§18). Folders are the documented
     *   exception to the inert-tap rule: opening one is navigation *within* edit mode, not a
     *   launch, and it is the only way to reach the apps inside so they can be removed.
     * - **Tap on empty space**: exits edit mode.
     * - **Drag on empty space**: left alone, so the grid still scrolls.
     *
     * Note it never intercepts at `ACTION_DOWN`. `RecyclerView` routes the *whole* remaining gesture
     * to the first listener that intercepts, so stealing the DOWN starved `ItemTouchHelper` of the
     * move stream and the drag could never track the finger. Consuming only the terminal UP
     * suppresses the click (the child receives `ACTION_CANCEL`) while leaving the drag path intact.
     */
    private val editModeTouchListener = object : OnItemTouchListener {
        private var downOnChild: View? = null
        private var downOnChevron = false
        private var downOnFolder: FolderIcon? = null
        private var downX = 0f
        private var downY = 0f
        private var movedPastSlop = false
        private var dragStarted = false

        /**
         * True once this gesture has given up its right to pick an item up.
         *
         * Latched, and that is the point. A gesture that moves *before* the hold has elapsed is a
         * scroll, and it must stay one for its whole life — without the latch it would become a
         * scroll for the first 200ms and then grab a tile out from under the finger mid-fling,
         * because by the second batch of moves the hold test reads true.
         */
        private var pickUpForfeited = false

        /**
         * When this gesture's ACTION_DOWN was observed, on the uptime clock.
         *
         * Deliberately **not** `MotionEvent.getDownTime()`. See [heldLongEnough].
         */
        private var downAt = 0L

        override fun onInterceptTouchEvent(rv: RecyclerView, e: MotionEvent): Boolean {
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    // Recorded unconditionally, *not* only while editing: edit mode is entered by a
                    // long-press, i.e. part-way through a gesture whose DOWN arrived while it was
                    // still off. Gating this on editMode left the fields unset for that gesture, so
                    // its UP looked like a tap on empty space and instantly exited the mode again.
                    downOnChild = findChildViewUnder(e.x, e.y)
                    // The resize chevron is a real clickable child, so this listener must not
                    // swallow its terminal UP the way it does for the rest of a tile. Recorded at
                    // DOWN because by UP the coordinates may have drifted within the slop.
                    // Both affordances get identical treatment: a gesture starting on either is a
                    // tap on a control, never a drag handle and never a tile tap to swallow.
                    downOnChevron = downOnChild?.let { child ->
                        val local = toChildLocal(child, e.x, e.y)
                        AresWidgetResize.isPointOnChevron(child, local[0], local[1]) ||
                            AresRemoveBadge.isPointOnBadge(child, local[0], local[1])
                    } ?: false
                    // Recorded at DOWN like the chevron, and for the same reason: by UP the child
                    // lookup may no longer be reliable. A folder tap must reach the FolderIcon's
                    // own click listener rather than being swallowed with the rest of the tiles.
                    downOnFolder = folderIconOf(downOnChild)
                    downX = e.x
                    downY = e.y
                    movedPastSlop = false
                    dragStarted = false
                    pickUpForfeited = false
                    downAt = SystemClock.uptimeMillis()
                    enteredEditModeDuringGesture = false
                    // Claim the gesture for the grid from the outset, while editing and on an item.
                    //
                    // Starting a reorder is otherwise circular: the drag only begins once the touch
                    // passes the slop threshold, but an ancestor claims the gesture before that
                    // happens. Traced on device, a sideways drag delivered exactly one ACTION_MOVE
                    // and then ACTION_CANCEL -- Workspace is a PagedView and intercepts horizontal
                    // drags to change pages -- so `startDrag` was never reached and the item never
                    // moved. `setReorderInProgress` issues the same call but only *after* a drag is
                    // running, which is too late to be what starts one.
                    //
                    // Scoped to items so empty-space gestures still bubble to Workspace for the
                    // wallpaper/widgets popup, and released on UP/CANCEL below.
                    if (editMode && downOnChild != null) {
                        rv.parent?.requestDisallowInterceptTouchEvent(true)
                    }
                    return false
                }
                MotionEvent.ACTION_MOVE -> {
                    // TWO GESTURES, TWO RULES, and the difference is which touch this is (§G5).
                    //
                    // The long-press that ENTERS edit mode may continue straight into a drag with
                    // no second hold -- 5a4944a054, which the user confirmed is right: "when we
                    // hold down an app to go into edit mode, the correct behavior which youre
                    // already doing is to allow the app to immediately be able to move."
                    //
                    // A FRESH touch, made while the mode is already on, must be HELD first: "you
                    // currently allow items to be moved by immediately touching them which causes
                    // issues if we end up need to scroll on the homepage... we should force the
                    // user to hold the app, widget, or folder for a short moment before its
                    // selected and can move." The grid is one continuous tall scroller (§4), so an
                    // immediate grab on every touch leaves no gesture to scroll with.
                    //
                    // The scroll itself needs nothing built. This listener never intercepts a MOVE,
                    // so RecyclerView's own scrolling was already wired and was simply being
                    // pre-empted by startDrag at the touch slop. Declining to start the drag is the
                    // whole change.
                    if (!movedPastSlop && exceededSlop(e)) {
                        movedPastSlop = true
                        // Moved before the hold elapsed, so this gesture is a scroll -- for good.
                        if (!enteredEditModeDuringGesture && !heldLongEnough()) {
                            pickUpForfeited = true
                        }
                    }
                    // A gesture that began on the chevron is a resize tap, not a drag handle --
                    // otherwise the smallest wobble while tapping would pick the widget up.
                    if (editMode && !dragStarted && movedPastSlop && !downOnChevron &&
                        !pickUpForfeited && (enteredEditModeDuringGesture || heldLongEnough())
                    ) {
                        // getChildViewHolder THROWS IllegalArgumentException for a view that is no
                        // longer a direct child, and downOnChild was captured at ACTION_DOWN -- a
                        // rebind or a fling can retire it under the finger. Ask the safe question
                        // first. (The panel flagged exactly this shape in the reverted G5.)
                        val holder = downOnChild
                            ?.takeIf { getChildAdapterPosition(it) != NO_POSITION }
                            ?.let { getChildViewHolder(it) }
                        if (holder != null) {
                            dragStarted = true
                            itemTouchHelper.startDrag(holder)
                            // THE FIX FOR THE EDIT-MODE WEDGE. Measured, not reasoned: with the
                            // first MOVE 300ms after the DOWN, PopupContainerWithArrow was attached
                            // to the DragLayer and open at the moment BACK was pressed, so BACK went
                            // to Launcher.getOnBackAnimationCallback()'s #3 branch (top open
                            // floating view) and closed the popup instead of reaching onStateBack --
                            // which is why the press "did nothing" while dragging=false and
                            // floating=null when sampled afterwards. A/B on the same build:
                            // 0ms pre-hold -> no popup, edit mode exits; 300ms -> popup open, edit
                            // mode STUCK; 900ms -> popup opened *before* the drag, so
                            // onSelectedChanged's closeAllOpenViews caught it and edit mode exits.
                            //
                            // The gap is the 300ms case only: the child's long-press callback was
                            // posted at ACTION_DOWN and is not removed until RecyclerView actually
                            // intercepts, which is one motion event after startDrag. If the timer
                            // (~400ms) expires inside that gap the popup opens *after*
                            // closeAllOpenViews has already run, and nothing else ever closes it.
                            //
                            // cancelPendingInputEvents, not cancelLongPress: the long-click listener
                            // lives on the item view *inside* the holder container, and only
                            // ViewGroup.dispatchCancelPendingInputEvents walks down to it.
                            downOnChild?.cancelPendingInputEvents()
                        }
                    }
                }
                MotionEvent.ACTION_UP -> {
                    // A stationary touch. The gesture that *entered* edit mode is excluded, or the
                    // mode would end the instant the long-press finger lifted.
                    val tap = !movedPastSlop && !dragStarted && !enteredEditModeDuringGesture
                    val onItem = downOnChild != null
                    val onChevron = downOnChevron
                    val onFolder = downOnFolder
                    downOnChild = null
                    downOnChevron = false
                    downOnFolder = null
                    // Release the claim taken at DOWN. Left set, the *next* gesture would start with
                    // ancestors still suppressed -- so a horizontal swipe meant for the app-list
                    // pane would be silently eaten by a grid that is no longer editing.
                    rv.parent?.requestDisallowInterceptTouchEvent(false)
                    // Let the chevron's own OnClickListener fire: consuming here would deliver
                    // ACTION_CANCEL to it and the resize would never happen.
                    if (onChevron) return false
                    if (editMode && tap) {
                        // On empty space: leave the mode. On an item: swallow it, so the tile stays
                        // inert. Either way the child gets ACTION_CANCEL and does not click.
                        if (!onItem) exitEditMode()
                        // A folder is the exception -- let its click through so it opens, and arm
                        // the in-folder × affordances for the folder that click is about to open.
                        if (onFolder != null) {
                            AresFolderEdit.attach(launcher, onFolder)
                            return false
                        }
                        return true
                    }
                }
                MotionEvent.ACTION_CANCEL -> {
                    downOnChild = null
                    dragStarted = false
                    rv.parent?.requestDisallowInterceptTouchEvent(false)
                }
            }
            return false
        }

        // Only reached for the terminal event this listener consumed above; nothing to do with it.
        override fun onTouchEvent(rv: RecyclerView, e: MotionEvent) = Unit

        override fun onRequestDisallowInterceptTouchEvent(disallow: Boolean) = Unit

        private fun exceededSlop(e: MotionEvent): Boolean =
            kotlin.math.hypot(e.x - downX, e.y - downY) > touchSlop

        /**
         * Whether this gesture has been down long enough to be allowed to pick an item up (§G5).
         *
         * Two things this deliberately is not:
         *
         * **Not a posted timer.** A timer has to be armed at DOWN, cancelled on movement and on
         * every terminal action, and it fires against a `downOnChild` that may have been recycled
         * in the meantime — which is exactly the `getChildViewHolder` `IllegalArgumentException`
         * the panel found in the reverted implementation. Asking the question when a MOVE arrives
         * needs no callback and leaves no window in which the view can go stale.
         *
         * **Not `MotionEvent.getDownTime()`**, which looks like the obvious source and is wrong
         * here. Measured on emulator-5554: every synthetic MOVE reports
         * `downTime == eventTime`, i.e. `eventTime - downTime == 0`, because each
         * `adb shell input motionevent` runs as its own process and stamps a fresh down time.
         * Reading the hold off the event therefore forfeits the pick-up on *every* injected drag,
         * which is not just a harness problem — it would have made this feature, the reorder
         * journeys and the stress soak all unverifiable, and the failure looks exactly like the
         * feature being broken. Our own [SystemClock.uptimeMillis] stamp at ACTION_DOWN reads
         * correctly for both real and injected input (129ms for an immediate move, 367ms for a
         * 300ms pre-hold).
         */
        private fun heldLongEnough(): Boolean =
            (SystemClock.uptimeMillis() - downAt) >= PICKUP_HOLD_MS
    }

    // Declared after the listener because Kotlin runs initialisers in declaration order, and the
    // listener has to exist before it can be attached. The drop ring is here for the same reason.
    init {
        addOnItemTouchListener(editModeTouchListener)
        addItemDecoration(dropRing)
    }

    /**
     * Sets the grid's column count and cell height from the device profile.
     *
     * Columns follow the launcher's own grid so the home screen matches the density the user
     * already has configured, rather than inventing a second notion of "how many columns". Cell
     * height likewise reuses the profile's cell height, which keeps icons and widgets at the
     * proportions their providers and icon packs were designed against.
     */
    private fun applyGridMetrics() {
        val dp = launcher.deviceProfile
        masonry.columns = dp.inv.numColumns.coerceAtLeast(1)
        masonry.cellHeightPx = dp.cellHeightPx.coerceAtLeast(1)
    }

    /**
     * True while a row is being dragged to a new position (§4).
     *
     * [AresPaneSwipeController] claims horizontal drags *anywhere* on the home screen, and
     * `TouchController`s run in `BaseDragLayer.findControllerToHandleTouch` before any child view
     * sees the event -- so without this a sideways wobble during a reorder would yank the app-list
     * pane in mid-drag. `requestDisallowInterceptTouchEvent` (which ItemTouchHelper already issues)
     * only suppresses *ancestor* `onInterceptTouchEvent`, so it is not sufficient on its own here;
     * the controller is asked directly instead. See design/implementation-scope.md §9.
     */
    private var reorderInProgress = false

    fun setReorderInProgress(inProgress: Boolean) {
        reorderInProgress = inProgress
        // Belt and braces alongside the controller check: also stop ancestors intercepting.
        parent?.requestDisallowInterceptTouchEvent(inProgress)
    }

    fun isReorderInProgress(): Boolean = reorderInProgress

    /**
     * True when the current gesture began on empty space rather than on a row.
     *
     * Long-pressing empty home space is how Launcher3 opens the wallpaper/widgets/settings popup
     * (WorkspaceTouchListener, an OnTouchListener on Workspace). A View's OnTouchListener only runs
     * if no descendant consumed the event, and this list spans the whole page below the smartspace
     * -- so once it started consuming touches, that popup became unreachable, taking launcher
     * settings and the §7 widget picker with it.
     *
     * Same family as the original DragLayer-overlay defect (our view eating touches Workspace
     * needs), but the list is hosted correctly now, so this is about routing *within* the page
     * rather than re-hosting: decline the gesture when it isn't on a row, and let it bubble up to
     * Workspace. Touches on rows are unaffected, so row taps and row long-press still work.
     *
     * Empty space only exists when the content doesn't fill the viewport -- in which case there is
     * nothing to scroll -- so declining it costs no scrolling behaviour.
     *
     * ## Why edit mode is excluded
     *
     * While editing, the grid must *keep* empty-space gestures: tapping empty space is how the mode
     * is left, and that is recognised at `ACTION_UP`. Declining the `ACTION_DOWN` drops this view
     * out of the dispatch chain for the rest of the gesture, so the `UP` is delivered to Workspace
     * instead and [editModeTouchListener] never sees it -- tap-to-exit could not fire at all, and
     * only the Back button worked (verified on device: BACK cleared the affordances, an empty-space
     * tap did not). The wallpaper/widgets popup this guard exists to protect is not wanted mid-edit
     * anyway, and outside edit mode the behaviour is unchanged.
     */
    private var gestureStartedOnEmptySpace = false

    /**
     * Observes every event this view receives, and abandons a pending folder drop on a CANCEL.
     *
     * ## Why here rather than in an OnItemTouchListener
     *
     * The user's rule for the dwell is explicit: *"a user needs to manually release before an item
     * is actually added to a folder."* An `ACTION_CANCEL` — the notification shade pulled down
     * mid-drag, an incoming call, any window taking focus — is not a release, but `ItemTouchHelper`
     * routes it through `select(null, ACTION_STATE_IDLE)` and then `clearView` exactly as it routes
     * a normal lift. Nothing downstream can tell the two apart, so a cancelled drag would file the
     * icon into the folder and persist it.
     *
     * [editModeTouchListener] cannot see the CANCEL reliably: `ItemTouchHelper` registers its own
     * `OnItemTouchListener` first (it is attached in the first `init` block, ours in the second),
     * and once it intercepts, `RecyclerView` routes the remainder of the gesture to it alone.
     * `dispatchTouchEvent` is above all of that and always runs. It consumes nothing — the return
     * value is `super`'s, untouched.
     *
     * Verified by taking the window away mid-dwell with a real activity launch rather than a
     * synthetic UP: the dwell armed, `clearView` took the non-committing branch, and the database
     * was byte-identical before and after. (An earlier attempt using `cmd statusbar
     * expand-notifications` did **not** reproduce a CANCEL against injected input, and reading that
     * run as a failed fix would have been wrong.)
     */
    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        if (ev.actionMasked == MotionEvent.ACTION_CANCEL) AresFolderDrop.cancel()
        return super.dispatchTouchEvent(ev)
    }

    override fun onInterceptTouchEvent(e: MotionEvent): Boolean {
        if (e.actionMasked == MotionEvent.ACTION_DOWN) {
            gestureStartedOnEmptySpace = !editMode && findChildViewUnder(e.x, e.y) == null
        }
        if (gestureStartedOnEmptySpace) return false
        return super.onInterceptTouchEvent(e)
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (gestureStartedOnEmptySpace) return false
        val handled = super.onTouchEvent(e)
        // A grid whose content is shorter than the viewport has nothing to scroll, and a view that
        // returns false for ACTION_DOWN receives no further events in that gesture. Claiming the
        // DOWN while editing keeps this view in the chain so the terminal UP -- the tap that leaves
        // edit mode -- actually arrives.
        return handled || (editMode && e.actionMasked == MotionEvent.ACTION_DOWN)
    }

    /**
     * No-op. ShortcutAndWidgetContainer.measureChild() unconditionally calls setPadding() on every
     * non-widget, non-QSB child on every measure pass, to centre an icon inside its grid cell.
     * That math is meaningless for a full-bleed list and would otherwise clobber our own padding
     * on each layout. Ignoring it here keeps the fix in our own file rather than in vendored
     * Launcher3 code. See design/architecture-reassessment.md §1(a).
     */
    override fun setPadding(left: Int, top: Int, right: Int, bottom: Int) {
        // Intentionally empty.
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        super.onLayout(changed, l, t, r, b)
        // Widget providers can only be told their box once the layout manager has assigned one.
        aresAdapter.reportPendingWidgetSizes()
    }

    override fun onMeasure(widthSpec: Int, heightSpec: Int) {
        // Re-read the grid metrics each measure: folding changes the device profile, and the
        // column count and cell height must follow it or the packing is computed against stale
        // geometry.
        applyGridMetrics()

        // ShortcutAndWidgetContainer.onMeasure() calls setMeasuredDimension() *before* measuring
        // its children, so the parent's dimensions are already valid here. Size to them, and
        // sync the CellLayoutLayoutParams so layoutChild() -- which positions us from lp.x/y/
        // width/height rather than from our measured size -- places us full-bleed.
        val host = parent as? ViewGroup
        val width = host?.measuredWidth?.takeIf { it > 0 } ?: MeasureSpec.getSize(widthSpec)
        val hostHeight = host?.measuredHeight?.takeIf { it > 0 } ?: MeasureSpec.getSize(heightSpec)

        // Start below the pinned smartspace rather than on top of it (it occupies grid row 0 of the
        // same container, so a full-bleed list would overlap row 1).
        val top = pinnedHeaderBottom(host)
        val height = (hostHeight - top).coerceAtLeast(0)

        // Mutating the existing lp's fields rather than calling setLayoutParams() avoids
        // triggering a nested requestLayout() from inside a measure pass.
        (layoutParams as? CellLayoutLayoutParams)?.let { lp ->
            lp.isLockedToGrid = false
            lp.x = 0
            lp.y = top
            lp.width = width
            lp.height = height
        }

        super.onMeasure(
            MeasureSpec.makeMeasureSpec(width, MeasureSpec.EXACTLY),
            MeasureSpec.makeMeasureSpec(height, MeasureSpec.EXACTLY),
        )
    }

    /**
     * Bottom edge of the first-page pinned item (the smartspace / at-a-glance header), or 0 when it
     * isn't present -- it's behind a preference, so a user with smartspace disabled gets the full
     * height.
     *
     * Workspace.bindAndInitFirstWorkspaceScreen() adds that item to this same container via
     * addViewToCellLayout() at grid cell (0,0) spanning the full width, tagged with
     * R.id.search_container_workspace. It stays grid-locked, so its CellLayoutLayoutParams carry
     * resolved pixel bounds. It's added at child index 0 and therefore measured before this view,
     * so those bounds are already populated by the time we read them; measuredHeight is used as a
     * fallback in case that ordering ever changes.
     *
     * Deliberately reads the smartspace's geometry instead of moving or resizing it -- it is shared
     * with Launcher3's own first-page handling, so offsetting our own list is the smaller change.
     */
    private fun pinnedHeaderBottom(host: ViewGroup?): Int {
        if (host == null) return 0
        for (i in 0 until host.childCount) {
            val child = host.getChildAt(i)
            if (child === this || child.id != R.id.search_container_workspace) continue
            if (child.visibility == GONE) return 0
            val lp = child.layoutParams as? CellLayoutLayoutParams
            val bottom = if (lp != null && lp.height > 0) lp.y + lp.height else child.measuredHeight
            return bottom.coerceAtLeast(0)
        }
        return 0
    }

    private companion object {
        const val TAG = "AresHomeGrid"

        /** Slight shrink signalling edit mode, mirroring the Windows Phone Start cue. */
        const val EDIT_MODE_SCALE = 0.92f

        /** Matches the edit-mode scale animation, so the whole mode arrives as one gesture. */
        const val DOTS_FADE_MS = 120L

        /** Same as the dots: fast enough to feel like a response, slow enough not to snap. */
        const val DROP_RING_FADE_MS = 120L

        /**
         * How long a **fresh** touch in edit mode must be held before it may pick an item up (§G5).
         *
         * Distinctly shorter than the system long-press (~400–500ms) that enters the mode and that
         * raises the context popup from inside it, or the gestures would be indistinguishable in
         * the hand. Long enough that a flick meant to scroll never grabs anything. One constant,
         * expected to be tuned on the user's device.
         */
        const val PICKUP_HOLD_MS = 200L
    }
}
