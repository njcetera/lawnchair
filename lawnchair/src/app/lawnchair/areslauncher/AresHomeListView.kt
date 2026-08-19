package app.lawnchair.areslauncher

import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.content.Context
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
import com.android.launcher3.model.data.ItemInfo

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

    init {
        layoutManager = masonry
        adapter = aresAdapter
        clipToPadding = false
        applyGridMetrics()
        itemTouchHelper.attachToRecyclerView(this)
        aresAdapter.editModeHost = { enterEditMode() }
        aresAdapter.gridColumns = { masonry.columns }
        aresAdapter.resizeHost = { info -> cycleWidgetSize(info) }
        aresAdapter.removeHost = { info -> removeFromHome(info) }
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
        val allowed = AresWidgetResize.allowedSizes(launcher, info, masonry.columns)
        if (allowed.isEmpty()) return

        val current = AresPacker.Span(info.spanX.coerceAtLeast(1), info.spanY.coerceAtLeast(1))
        val next = AresWidgetResize.nextSize(current, allowed)
        if (next.w == current.w && next.h == current.h) return

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
     * Running wiggle animators, keyed by the holder container they rotate.
     *
     * Held so they can be cancelled individually as rows recycle and wholesale on exit. An
     * animator left running on a recycled view would rotate whatever item was bound into it next,
     * including outside edit mode.
     */
    private val wiggles = mutableMapOf<View, ObjectAnimator>()

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
     * Called from the item long-press handler, which *also* shows `PopupContainerWithArrow`. Both
     * deliberately happen: the popup is the only route to remove an item or reach its shortcuts, so
     * suppressing it would lose that with no replacement (per-item affordances are a later
     * increment). Dismissing the popup leaves the surface in edit mode, ready to drag.
     */
    fun enterEditMode() {
        if (editMode) return
        editMode = true
        enteredEditModeDuringGesture = true
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
        for (i in 0 until childCount) {
            val child = getChildAt(i)
            child.animate().scaleX(scale).scaleY(scale).setDuration(120).start()
            setItemClickable(child, !editMode)
            syncChevron(child)
            syncWiggle(child)
        }
    }

    /**
     * Starts or stops one row's edit-mode wiggle.
     *
     * ## Why a wiggle at all
     *
     * The scale-down alone proved too subtle to notice, so there was no way to tell whether the
     * surface was editable — which made taps that no longer launched look broken rather than
     * intentional. The oscillation is the iOS/Windows Phone cue, and crucially **its absence is the
     * signal that you are back to normal**, so exiting has visible feedback too.
     *
     * ## Why the phase is offset per item
     *
     * Every tile rotating in lockstep reads as one animated sheet rather than a set of loose
     * objects. Seeding each animator's play time from its adapter position breaks that up. The
     * offset is derived from *position*, not a random value, so a row landing back on screen after
     * a scroll resumes at the phase it would have had — otherwise recycling would visibly re-sync
     * tiles as they scrolled into view.
     *
     * ## Rotation, on the container
     *
     * Applied to the holder container rather than the item view, matching the scale and the two
     * affordances — so the × and the chevron wiggle *with* the tile instead of sitting still over a
     * moving icon. (For a widget the item view is an `AppWidgetHostView`, whose children the
     * provider replaces on every update; the container is the only stable place to attach to.)
     *
     * Honours the system animator scale: with animations off, items simply hold the edit-mode
     * scale, which still distinguishes the mode without motion.
     */
    private fun syncWiggle(child: View) {
        val running = wiggles.remove(child)
        running?.cancel()

        if (!editMode) {
            child.rotation = 0f
            return
        }
        if (!ValueAnimator.areAnimatorsEnabled()) {
            child.rotation = 0f
            return
        }

        val position = getChildAdapterPosition(child)
        val animator = ObjectAnimator.ofFloat(child, View.ROTATION, -WIGGLE_DEGREES, WIGGLE_DEGREES)
        animator.duration = WIGGLE_PERIOD_MS
        animator.repeatMode = ObjectAnimator.REVERSE
        animator.repeatCount = ObjectAnimator.INFINITE
        animator.start()
        // Seed the phase *after* start(): currentPlayTime only takes effect on a running animator.
        if (position != NO_POSITION) {
            animator.currentPlayTime = (position * WIGGLE_PHASE_STEP_MS) % WIGGLE_PERIOD_MS
        }
        wiggles[child] = animator
    }

    /** Stops every wiggle and clears the rotation, so nothing is left tilted. */
    private fun clearWiggles() {
        for ((child, animator) in wiggles) {
            animator.cancel()
            child.rotation = 0f
        }
        wiggles.clear()
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
        val position = getChildAdapterPosition(child)
        if (position == NO_POSITION) return
        aresAdapter.syncChevron(container, position)
    }

    override fun onChildAttachedToWindow(child: View) {
        super.onChildAttachedToWindow(child)
        // Rows bound while already editing (recycled in on scroll) must match the current mode.
        val scale = if (editMode) EDIT_MODE_SCALE else 1f
        child.scaleX = scale
        child.scaleY = scale
        setItemClickable(child, !editMode)
        syncWiggle(child)
    }

    override fun onChildDetachedFromWindow(child: View) {
        super.onChildDetachedFromWindow(child)
        // Stop the animator with the view it belongs to. Left running, it would keep rotating a
        // recycled view after a different item had been bound into it.
        wiggles.remove(child)?.cancel()
        child.rotation = 0f
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
     */
    private fun setItemClickable(child: View, clickable: Boolean) {
        val item = (child as? ViewGroup)?.getChildAt(0) ?: return
        item.isClickable = clickable
    }

    /**
     * Routes touches while editing.
     *
     * - **Drag**: past the touch slop on an item, hands the gesture to [ItemTouchHelper] via
     *   `startDrag`. Edit mode replaces its built-in long-press trigger, which is the Android
     *   one-shot model rather than a persistent mode.
     * - **Tap on an item**: consumed at `ACTION_UP` so the child's click never fires — WP behaviour,
     *   where you leave edit mode before launching anything.
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
        private var downX = 0f
        private var downY = 0f
        private var movedPastSlop = false
        private var dragStarted = false

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
                        val localX = e.x - child.left
                        val localY = e.y - child.top
                        AresWidgetResize.isPointOnChevron(child, localX, localY) ||
                            AresRemoveBadge.isPointOnBadge(child, localX, localY)
                    } ?: false
                    downX = e.x
                    downY = e.y
                    movedPastSlop = false
                    dragStarted = false
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
                    if (!movedPastSlop && exceededSlop(e)) movedPastSlop = true
                    // A gesture that began on the chevron is a resize tap, not a drag handle --
                    // otherwise the smallest wobble while tapping would pick the widget up.
                    if (editMode && !dragStarted && movedPastSlop && !downOnChevron) {
                        val holder = downOnChild?.let { getChildViewHolder(it) }
                        if (holder != null) {
                            dragStarted = true
                            itemTouchHelper.startDrag(holder)
                        }
                    }
                }
                MotionEvent.ACTION_UP -> {
                    // A stationary touch. The gesture that *entered* edit mode is excluded, or the
                    // mode would end the instant the long-press finger lifted.
                    val tap = !movedPastSlop && !dragStarted && !enteredEditModeDuringGesture
                    val onItem = downOnChild != null
                    val onChevron = downOnChevron
                    downOnChild = null
                    downOnChevron = false
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
    }

    // Declared after the listener because Kotlin runs initialisers in declaration order, and the
    // listener has to exist before it can be attached.
    init {
        addOnItemTouchListener(editModeTouchListener)
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
        /** Slight shrink signalling edit mode, mirroring the Windows Phone Start cue. */
        const val EDIT_MODE_SCALE = 0.92f

        /**
         * Half-amplitude of the edit-mode wiggle.
         *
         * Small on purpose: enough to read as alive across a screen of tiles, not so much that
         * labels become hard to read or a 4-wide widget's corners sweep into its neighbours.
         */
        const val WIGGLE_DEGREES = 1.4f

        /** One half-cycle. The full period is twice this, since the animator reverses. */
        const val WIGGLE_PERIOD_MS = 180L

        /**
         * Phase offset applied per adapter position.
         *
         * Deliberately not a divisor of [WIGGLE_PERIOD_MS]: a value that divides evenly makes
         * every Nth tile share a phase, which reintroduces the visible banding this is meant to
         * avoid.
         */
        const val WIGGLE_PHASE_STEP_MS = 47L
    }
}
