package app.lawnchair.areslauncher

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import android.os.SystemClock
import android.util.Log
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.view.animation.OvershootInterpolator
import android.widget.FrameLayout
import android.view.ViewTreeObserver
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.RecyclerView
import com.android.launcher3.AbstractFloatingView
import com.android.launcher3.BubbleTextView
import com.android.launcher3.Launcher
import com.android.launcher3.LauncherState
import com.android.launcher3.R
import com.android.launcher3.celllayout.CellLayoutLayoutParams
import com.android.launcher3.folder.Folder
import com.android.launcher3.folder.FolderIcon
import com.android.launcher3.model.data.FolderInfo
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
class AresHomeListView(context: Context, val launcher: Launcher) : RecyclerView(context) {

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

    /**
     * Material-You card behind an expanded WP folder's apps (assigned in [init]). Held so the close
     * choreography can tell it to fade the card OUT before the run is removed ([onWpFolderCollapsing]).
     */
    private lateinit var folderBounds: AresFolderBounds

    init {
        layoutManager = masonry
        adapter = aresAdapter
        // The masonry owns every move/insert/remove animation explicitly (animateNextRelayout, the
        // WP child unfurl, edit-mode reflow). The default RecyclerView ItemAnimator would ALSO
        // animate the same moves on its own ~250ms curve, double-animating each shifted tile -- which
        // made the tiles below an opening folder keep sliding past our own reflow and collide with
        // the revealed content (owner 2026-08-24). Drop it so the masonry is the sole animator.
        itemAnimator = null
        clipToPadding = false
        // §11c. Goes through super because our own setPadding() is a deliberate no-op (see below):
        // ShortcutAndWidgetContainer would otherwise clobber this on every measure pass.
        // AresMasonryLayoutManager already lays every cell out from paddingTop, and AresEditGrid
        // derives its dot origin from the same value, so both follow this without further change.
        // Top = §11c alignment + ergonomic reach padding; bottom = ergonomic scroll room. With
        // clipToPadding=false above, the bottom is real room to scroll the last rows clear of the nav
        // gesture area rather than dead space (owner). Both are shared with the app list via AresAllApps.
        super.setPadding(
            0,
            AresAllApps.homeListTopPaddingPx(context),
            0,
            AresAllApps.ergoBottomPaddingPx(context),
        )
        addItemDecoration(editDots)
        // Material-You card + title behind an expanded WP folder's opened apps (owner 2026-08-24).
        // Draws nothing unless a folder is expanded, so it is free to leave installed. It also owns
        // the vertical space an open folder needs (title band + gaps); hand those to the layout
        // manager so the reserved space and the card geometry are derived from the same numbers.
        folderBounds = AresFolderBounds(context, this)
        addItemDecoration(folderBounds)
        masonry.expandPadTopPx = folderBounds.expandedTopPadPx
        masonry.expandPadBottomPx = folderBounds.expandedBottomPadPx
        applyGridMetrics()
        itemTouchHelper.attachToRecyclerView(this)
        aresAdapter.editModeHost = { enterEditMode() }
        aresAdapter.gridColumns = { masonry.columns }
        // WP folders Phase 3 #3: keep an expanded folder's children packed as one contiguous block
        // with the folder, so the greedy packer's backfill can't strand a child in an upstream hole.
        masonry.reservedRunProvider = { aresAdapter.expandedRunRange() }
        aresAdapter.resizeHost = { info, dx, dy, phase -> onResizeDrag(info, dx, dy, phase) }
        aresAdapter.removeHost = { info -> removeFromHome(info) }
        aresAdapter.boundHost = { info, container -> onRowBound(info, container) }
        aresAdapter.menuHost = { info -> showItemMenu(info) }
        aresAdapter.wpExpandHost = { folderInfo, expanded -> onWpFolderExpanded(folderInfo, expanded) }
    }

    /**
     * Raises the context menu for [info] (§E4), anchored to the tile that is currently showing it.
     *
     * The view is looked up here rather than passed from the adapter because only the host knows
     * what an item is *currently drawn as*: holders are recycled, and for an icon the popup anchors
     * to the `BubbleTextView` itself, not to the holder container the badge lives on.
     *
     * **Edit mode stays on.** The first cut left it, on the reasoning in [AresHomeAdapter]'s
     * long-click handler — that a popup and edit mode are two floating states and each dismissal
     * gesture clears only one. In use that was wrong here, and the owner said so: *"it does take us
     * out of edit mode tho"*.
     *
     * The distinction is intent. That reasoning was about a single long-press raising both at once,
     * unasked, which left the user dismissing a menu they never opened before they could drag
     * anything. Tapping ! is a deliberate request for the menu *while arranging*, and dropping out
     * of the mode to serve it throws away the arrangement they were in the middle of. Dismissing
     * the popup now returns them to editing, which is where they were.
     */
    private fun showItemMenu(info: ItemInfo) {
        val container = childForItem(info)
        val itemView = (container as? ViewGroup)?.getChildAt(0)
        if (!AresInfoBadge.showMenu(itemView)) {
            Log.w(TAG, "no menu could be shown for ${info.targetComponent}")
        }
    }

    /**
     * Dissolve the folder [folderId] eagerly if a drag-out just dropped it below the 2-item minimum
     * (owner decision 2026-08-23). Called from AresFolderExitHandoff the instant the second-to-last
     * member joins the grid, so the folder never lingers as an interactive 1-item zombie. No-op if
     * the folder is not on screen, or is a 3+-item / open / already-destroyed folder (the guard
     * lives in Folder.aresDissolveIfBelowMinimum).
     */
    fun aresDissolveSourceFolder(folderId: Int) {
        if (folderId < 0) return
        for (i in 0 until childCount) {
            val item = (getChildAt(i) as? ViewGroup)?.getChildAt(0)
            if (item is FolderIcon && item.mInfo?.id == folderId) {
                item.folder?.aresDissolveIfBelowMinimum()
                return
            }
        }
    }

    /** The attached holder container currently bound to [info], or null if it is not on screen. */
    private fun childForItem(info: ItemInfo): View? {
        for (i in 0 until childCount) {
            val child = getChildAt(i) ?: continue
            val position = getChildAdapterPosition(child)
            if (position != NO_POSITION && aresAdapter.itemAt(position) === info) return child
        }
        return null
    }

    /**
     * H1 (state-seam, ledger row 40 / Bug B): reload-equivalent heal for a folder wedged
     * un-openable. `Folder.aresRecoverStuckOpen` covers only the *detached* wedge
     * (`getParent()==null`); when a tap is still declined the folder is wedged in a variant it does
     * not cover — attached-invisible (`mIsOpen` true, still parented into the DragLayer) or destroyed
     * (`mDestroyed` is write-once and can never be reset on the same view). Both are un-openable for
     * the rest of the session, and only a reload has been observed to heal them.
     *
     * This is that reload, scoped to one row: **discard the stale view** and **rebind the folder's
     * home row** to a fresh `FolderIcon`/`Folder`, which clears every facet at once
     * (`mIsOpen`/`mState`/`mDestroyed`/attachment) because it is a *new instance*, then open it.
     *
     * Called ONLY from `ItemClickHandler.onClickFolderIcon`'s declined branch, so it can never touch
     * a folder that opens normally — the normal path never reaches here.
     */
    fun aresRebindAndOpenFolder(stale: Folder) {
        val info: FolderInfo = stale.info ?: return
        // 0. Confirm this is actually one of OUR home rows BEFORE any destructive teardown.
        //    indexOf matches the home adapter only, so a folder that reaches the declined branch but
        //    is not a live home row -- an app-drawer folder, or a home folder whose model row was
        //    already dropped by a dissolve race (the isDestroyed decline) -- must be left untouched.
        //    Detaching-then-abandoning it (index<0 after teardown) would leave it strictly worse
        //    than the wedge. Adversarial-review finding, 2026-08-23.
        val index = aresAdapter.indexOf(info)
        if (index < 0) return
        // 1. Hard-discard the stale view (reload semantics — we are replacing it, not reconciling
        //    it, so this deliberately does NOT go through the racy close animation that wedged it):
        //    detach it from the DragLayer if it lingered there (the attached-invisible variant),
        //    drop its drop-target AND drag-listener registrations (no-ops if absent), and reset its
        //    open latches. The reset closes a sub-frame re-entrancy window: without it, a second
        //    declined tap arriving before the rebind lays out would find the now-detached stale with
        //    mIsOpen still true and aresRecoverStuckOpen would resurrect it alongside the fresh one.
        (stale.parent as? ViewGroup)?.removeView(stale)
        launcher.dragController.removeDropTarget(stale)
        launcher.dragController.removeDragListener(stale)
        stale.aresRecoverStuckOpen()
        // 2. Rebind the row -> fresh FolderIcon + Folder. Folder rows are recyclable TYPE_ICON, so
        //    notifyItemChanged re-inflates cleanly (onBindViewHolder does removeAllViews + inflate).
        aresAdapter.notifyItemChanged(index)
        // 3. Open the fresh folder once the rebind has laid out. A pre-draw listener fires after
        //    layout and before draw, so the fresh FolderIcon is guaranteed present (a bare post()
        //    can race the rebind traversal).
        viewTreeObserver.addOnPreDrawListener(object : ViewTreeObserver.OnPreDrawListener {
            override fun onPreDraw(): Boolean {
                viewTreeObserver.removeOnPreDrawListener(this)
                val container = childForItem(info) as? ViewGroup
                val fresh = (container?.getChildAt(0) as? FolderIcon)?.folder
                if (fresh != null && !fresh.isOpen && !fresh.isDestroyed) {
                    fresh.animateOpen()
                }
                return true
            }
        })
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
    internal fun removeFromHome(info: ItemInfo) {
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

    /** The widget currently being resized by a handle drag, and what it started as. */
    private var resizeItem: ItemInfo? = null
    private var resizeFrom: AresPacker.Span? = null
    private var resizeAllowed: List<AresPacker.Span> = emptyList()

    /**
     * Drives a widget resize from the handle's drag (§3).
     *
     * The grid repacks **live**, on every cell boundary the drag crosses, which is the owner's
     * explicit feel decision and makes resize consistent with drag-to-move. Only the release
     * writes: a per-frame `persistSize` would reallocate a cell and hit the database on every
     * boundary, and an abandoned drag would leave the last intermediate size persisted.
     *
     * Every refusal says something. A visible affordance that declines to act must not do so
     * silently -- an earlier defect here produced a chevron that did nothing at all, and the absence
     * of any log made "the touch never arrived" indistinguishable from "the resize was refused".
     */
    private fun onResizeDrag(info: ItemInfo, dx: Float, dy: Float, phase: AresWidgetResize.Phase) {
        when (phase) {
            AresWidgetResize.Phase.BEGIN -> {
                val allowed = AresWidgetResize.allowedSizes(launcher, info, masonry.columns)
                if (allowed.isEmpty()) {
                    Log.d(
                        TAG,
                        "resize declined: no allowed sizes for id=${info.id} at " +
                            "${info.spanX}x${info.spanY}, columns=${masonry.columns}",
                    )
                    return
                }
                resizeItem = info
                resizeFrom = AresPacker.Span(info.spanX.coerceAtLeast(1), info.spanY.coerceAtLeast(1))
                resizeAllowed = allowed
                // Same flag a reorder raises. The float animator and the pane swipe both stand down
                // for it, which is what a resize needs too: a tile that is changing size must not
                // also be drifting, and a horizontal drag on the handle must not open the app list.
                setReorderInProgress(true)
            }

            AresWidgetResize.Phase.MOVE -> {
                val from = resizeFrom ?: return
                if (resizeItem !== info) return
                applyLiveSpan(info, spanFromDrag(from, dx, dy))
            }

            AresWidgetResize.Phase.END -> {
                val from = resizeFrom ?: return
                finishResize(info, from, spanFromDrag(from, dx, dy), commit = true)
            }

            AresWidgetResize.Phase.CANCEL -> {
                val from = resizeFrom ?: return
                finishResize(info, from, from, commit = false)
            }
        }
    }

    private fun spanFromDrag(from: AresPacker.Span, dx: Float, dy: Float): AresPacker.Span =
        AresWidgetResize.spanForDrag(
            from,
            dx,
            dy,
            masonry.resolvedCellWidthPx(),
            masonry.resolvedCellHeightPx(),
            resizeAllowed,
        )

    /**
     * Shows [span] without writing it.
     *
     * The packer derives every position from the adapter's spans, so changing the item's own span
     * and asking for a re-pack is the whole of "show me this size" -- there is no separate preview
     * to build. The widget's host view is re-reported at the same time, or the provider keeps
     * drawing its RemoteViews against the old box inside a tile that has already changed shape.
     */
    private fun applyLiveSpan(info: ItemInfo, span: AresPacker.Span) {
        if (info.spanX == span.w && info.spanY == span.h) return
        info.spanX = span.w
        info.spanY = span.h
        val position = aresAdapter.indexOf(info)
        if (position >= 0) {
            (findViewHolderForAdapterPosition(position) as? AresHomeAdapter.ViewHolder)
                ?.let { aresAdapter.reportSizeAfterResize(info, it.container) }
        }
        masonry.animateNextLayout()
        masonry.invalidatePacking()
    }

    /**
     * Animate the next relayout instead of snapping it — for a structural change that lands AFTER
     * a drag has ended.
     *
     * Creating a folder, or merging an icon into one, runs from `list.post` off
     * `ItemTouchHelper`'s `clearView`; by then [setReorderInProgress] has cleared the masonry's
     * `reflowActive`, so the tiles that shift to close the vacated slots would snap to their new
     * positions with no motion — the owner's report, "all the remaining apps jump to their new
     * positions instead of doing a transition animation". This is the same one-shot
     * animate-don't-snap path [removeFromHome] and the widget resize already use; the caller
     * invokes it right after its adapter mutation, before the frame lays out.
     */
    fun animateNextRelayout(exemptItemId: Long = -1L) {
        masonry.repackExemptItemId = exemptItemId
        masonry.animateNextLayout()
        masonry.invalidatePacking()
    }

    /**
     * WP folders accordion polish (owner 2026-08-24). On expand: (1) fade the newly-opened apps in
     * -- the tiles below already slide via [animateNextRelayout], but the spliced children have no
     * previous bounds so they would pop; a short staggered alpha fade makes them appear rather than
     * snap; (2) nudge the list so the opened apps stay ON SCREEN when the folder sits low in a long
     * list, without ever pushing the folder header off the top. Both run posted, after the insert's
     * layout pass, when the child holders exist. Collapse needs neither (the slide covers it).
     */
    // --- Open-folder focus wash (owner 2026-08-25) --------------------------------------------
    // While a WP folder is open, every home tile that is NOT part of it (other icons AND widgets)
    // gets a dimmed colour wash, so the open folder stands out. The wash tints each tile's
    // ACTUALLY-DRAWN content via a hardware-layer ColorMatrix paint -- transparent pixels stay
    // transparent, so the wallpaper behind/around a tile is never tinted (owner: "not the
    // background or wallpaper, the icons and widgets themselves"). The matrix is a partial
    // desaturate plus a per-channel multiply toward [washColor] (MULTIPLY-style, so it only ever
    // dims), which is why it reads as a dim colour wash rather than a flat scrim.
    private val washColor by lazy { context.getColor(R.color.materialColorPrimary) }
    private var washStrength = 0f
    private var washPaint: Paint? = null
    private var washAnimator: ValueAnimator? = null

    // Tiles frozen (wiggle stopped, badges hidden) while washed in edit mode -- the differential
    // that reads as "only the folder is editable" (owner 2026-08-25). Tracked by view identity so
    // each is un-frozen exactly once on close.
    private val frozenTiles = HashSet<View>()

    /** Edit-mode only: stop a washed tile's wiggle and hide its ×/ⓘ/tint, so it reads as parked. */
    private fun freezeTile(view: View) {
        if (!isEditMode() || !frozenTiles.add(view)) return
        AresEditWiggle.stop(view, wiggles.remove(view))
        AresEditWiggle.reset(view)
        setEditChromeAlpha(view, 0f)
    }

    /**
     * While a folder is inline-expanded, only its own children may be picked up -- an OUTSIDE tile
     * must not start a drag (owner 2026-08-25: "I can still drag apps outside the folder toward the
     * folder"). That is the interaction half of the focus wash: the dim SAYS the rest is inert, this
     * MAKES it inert, so a press-and-hold on an outside app scrolls instead of dragging into a
     * cross-boundary move we do not support. No folder open -> normal rules.
     */
    private fun dragAllowedWhileFolderOpen(child: View?): Boolean {
        val fid = aresAdapter.expandedWpFolder()
        if (fid == -1) return true
        val pos = child?.let { getChildAdapterPosition(it) } ?: return false
        if (pos == NO_POSITION) return false
        // Only the open folder's children (container == its id) are draggable; the folder tile and
        // every other home tile are locked while it is open.
        return aresAdapter.itemAt(pos)?.container == fid
    }

    /** Restores a frozen tile's wiggle and edit chrome when the folder closes (or it recycles). */
    private fun unfreezeTile(view: View) {
        if (!frozenTiles.remove(view)) return
        if (isEditMode()) {
            val pos = getChildAdapterPosition(view)
            if (pos != NO_POSITION) {
                AresEditWiggle.start(view, pos) { editMode }?.let { wiggles[view] = it }
            }
            fadeInEditChrome(view)
        }
    }

    private fun buildWashPaint(strength: Float): Paint {
        val cm = ColorMatrix().apply { setSaturation(1f - WASH_SAT_DROP * strength) }
        val wr = Color.red(washColor) / 255f
        val wg = Color.green(washColor) / 255f
        val wb = Color.blue(washColor) / 255f
        // Per-channel multiply toward the wash colour (dim + tint); at strength 0 it is identity.
        cm.postConcat(
            ColorMatrix(
                floatArrayOf(
                    1f - (1f - wr * WASH_K) * strength, 0f, 0f, 0f, 0f,
                    0f, 1f - (1f - wg * WASH_K) * strength, 0f, 0f, 0f,
                    0f, 0f, 1f - (1f - wb * WASH_K) * strength, 0f, 0f,
                    0f, 0f, 0f, 1f, 0f,
                ),
            ),
        )
        return Paint().apply { colorFilter = ColorMatrixColorFilter(cm) }
    }

    /** Applies [strength] wash to one tile (0 clears it). Shared [washPaint] is reused per frame. */
    private fun applyTileWash(view: View, strength: Float) {
        if (strength <= 0.001f) {
            if (view.layerType == LAYER_TYPE_HARDWARE) view.setLayerType(LAYER_TYPE_NONE, null)
            return
        }
        val paint = washPaint ?: return
        if (view.layerType != LAYER_TYPE_HARDWARE) {
            view.setLayerType(LAYER_TYPE_HARDWARE, paint)
        } else {
            view.setLayerPaint(paint)
        }
    }

    /** Washes every attached tile at [strength], leaving the open folder's own run untinted. */
    private fun paintFolderWash(strength: Float) {
        washStrength = strength
        washPaint = if (strength > 0.001f) buildWashPaint(strength) else null
        val run = aresAdapter.expandedRunRange()
        for (i in 0 until childCount) {
            val child = getChildAt(i) ?: continue
            val pos = getChildAdapterPosition(child)
            val inFolder = run != null && pos != NO_POSITION && pos in run
            if (inFolder) {
                applyTileWash(child, 0f)
                if (child in frozenTiles) unfreezeTile(child)
            } else {
                applyTileWash(child, strength)
                if (strength > 0.001f) freezeTile(child) // no-op outside edit mode
            }
        }
    }

    private fun clearAllTileWash() {
        washStrength = 0f
        washPaint = null
        for (i in 0 until childCount) {
            val child = getChildAt(i) ?: continue
            if (child.layerType == LAYER_TYPE_HARDWARE) child.setLayerType(LAYER_TYPE_NONE, null)
        }
        for (child in frozenTiles.toList()) unfreezeTile(child)
    }

    /**
     * Immediately clears the inline-folder focus wash and freeze without animating. For a full model
     * rebind ([AresHomeAdapter.clear]), where the washed rows are about to be discarded and the
     * animated close path (`wpExpandHost(..., false)`) never runs for the vanishing folder. Without
     * this, `washStrength` stays at [WASH_MAX] and [onRowBound] re-applies the dim + freeze to every
     * tile of the fresh list -- leaving the whole home dimmed and (in edit mode) frozen with no
     * folder open and no self-heal short of opening and closing another folder. (Adversarial review
     * 2026-08-25, Finding 1.)
     */
    fun tearDownFolderWashImmediate() {
        washAnimator?.cancel()
        washAnimator = null
        clearAllTileWash()
    }

    /**
     * A recycled tile must not carry the focus wash into its next bind. [clearAllTileWash] walks only
     * attached children, so a washed tile scrolled off-screen (recycled) while a folder is open keeps
     * its hardware wash layer and would reattach dimmed if the wash is torn down while it is detached.
     * Drop the layer and its freeze bookkeeping here. (Adversarial review 2026-08-25, Finding 2.)
     */
    fun onTileRecycled(view: View) {
        if (view.layerType == LAYER_TYPE_HARDWARE) view.setLayerType(LAYER_TYPE_NONE, null)
        frozenTiles.remove(view)
    }

    /** Fades the focus wash in (folder opened) or out (closed). */
    private fun updateFolderWash(expanded: Boolean) {
        val target = if (expanded) WASH_MAX else 0f
        washAnimator?.cancel()
        if (!ValueAnimator.areAnimatorsEnabled()) {
            if (expanded) paintFolderWash(target) else clearAllTileWash()
            return
        }
        washAnimator = ValueAnimator.ofFloat(washStrength, target).apply {
            duration = WASH_MS
            addUpdateListener { paintFolderWash(it.animatedValue as Float) }
            addListener(
                object : android.animation.AnimatorListenerAdapter() {
                    override fun onAnimationEnd(animation: android.animation.Animator) {
                        washAnimator = null
                        // Only tear the layers down once actually settled closed, so a rapid
                        // reopen (which cancels this) does not strip a wash it is about to re-raise.
                        if (target <= 0f && washStrength <= 0.001f) clearAllTileWash()
                    }
                },
            )
            start()
        }
    }

    private fun onWpFolderExpanded(folderInfo: FolderInfo, expanded: Boolean) {
        updateFolderWash(expanded)
        if (!expanded) return
        val childIds = folderInfo.getContents().sortedBy { it.rank }.map { it.id }
        if (childIds.isEmpty()) return
        // Scale is skipped in edit mode, where the tile scale is owned by the edit-mode cue.
        val endScale = if (isEditMode()) EDIT_MODE_SCALE else 1f
        // Pre-hide the spliced children until the fall arms them one frame later. Without this the
        // children attach at their FINAL cells (onChildAttachedToWindow writes their resting scale)
        // and paint there for a frame before the posted fall moves them back onto their preview
        // slots -- the "tiny icons at the final positions" flash the owner saw on an edit-mode open
        // (2026-08-25). Only with animations on; the harness path snaps and has no flash to hide.
        if (ValueAnimator.areAnimatorsEnabled()) {
            wpFallPendingFolderId = folderInfo.id
            for (i in 0 until childCount) {
                val c = getChildAt(i) ?: continue
                val pos = getChildAdapterPosition(c)
                if (pos != NO_POSITION && aresAdapter.itemAt(pos)?.container == folderInfo.id) c.alpha = 0f
            }
        }
        post {
            // The signature open (owner 2026-08-24): each icon starts pixel-aligned ON its own preview
            // mini-icon (same position + size), then FALLS out of the folder -- drops through the
            // teardrop into the card, then spreads + enlarges to its cell. Staggered so they stream out
            // one after another. Delayed past the icon morph and the tiles-below reflow.
            childIds.forEachIndexed { i, id ->
                val v = findViewHolderForItemId(id.toLong())?.itemView ?: return@forEachIndexed
                val hasSlot = wpChildStart(folderInfo, i, tmpStart)
                playWpChildFall(
                    v, tmpStart[0], tmpStart[1], tmpStart[2],
                    forward = true,
                    delayMs = WP_CHILD_ENTER_DELAY_MS + i * WP_FALL_STAGGER_MS,
                    durationMs = WP_FALL_MS,
                    endScale = endScale,
                    tiltDeg = if (i % 2 == 0) WP_FALL_TILT_DEG else -WP_FALL_TILT_DEG,
                    startScale = tmpStart[3],
                    hasSlot = hasSlot,
                    seed = id,
                )
            }
            // Every tile is now seated exactly over its preview icon: snap the previews off in the SAME
            // frame -- a seamless preview->tile swap (owner 2026-08-24, "there's still like a
            // transition"). Done here, not in expandWpFolder, so there is never an empty teardrop.
            wpFolderIcon(folderInfo.id)?.setAresPreviewItemsHidden(true)
            nudgeExpandedIntoView(folderInfo, childIds)
            // Falls are armed for every attached child; stop pre-hiding late-attaching rows.
            wpFallPendingFolderId = -1
        }
    }

    /**
     * The folder whose spliced children are pre-hidden until their fall arms (see onWpFolderExpanded
     * and onChildAttachedToWindow), or -1. Kills the "tiny icons at the final cells" open flash.
     */
    private var wpFallPendingFolderId = -1

    /**
     * Entrance for a SINGLE child added to an already-open folder (dwell-add while expanded): the
     * same fall-out-of-the-teardrop motion, posted for just this one tile.
     */
    fun animateWpChildEnter(folderInfo: FolderInfo, id: Int) {
        post {
            val v = findViewHolderForItemId(id.toLong())?.itemView ?: return@post
            // A dwell-added child is the newest member, so it maps to the LAST preview slot (its index
            // among contents); resolve that slot exactly, falling back to the cluster centre.
            val index = folderInfo.getContents().sortedBy { it.rank }.indexOfFirst { it.id == id }
            val hasSlot = wpChildStart(folderInfo, index.coerceAtLeast(0), tmpStart)
            playWpChildFall(
                v, tmpStart[0], tmpStart[1], tmpStart[2],
                forward = true,
                delayMs = 0L,
                durationMs = WP_FALL_MS,
                endScale = if (isEditMode()) EDIT_MODE_SCALE else 1f,
                tiltDeg = WP_FALL_TILT_DEG,
                startScale = tmpStart[3],
                hasSlot = hasSlot,
                seed = id,
            )
            // Match the open: snap this member's preview off once its tile is seated over it.
            wpFolderIcon(folderInfo.id)?.setAresPreviewItemsHidden(true)
        }
    }

    private val tmpPreview = FloatArray(3)

    /** The FolderIcon of folder [id] if its tile is bound on screen. */
    private fun wpFolderIcon(id: Int): FolderIcon? =
        ((findViewHolderForItemId(id.toLong())?.itemView as? ViewGroup)?.getChildAt(0) as? FolderIcon)

    /**
     * Resolve child [index]'s fall START in this list's coordinate space, filling
     * [out] = {originX, originY, tipY, startScale}. When the child maps to one of the folder's real
     * preview slots (the first four), the origin is that slot's EXACT centre and startScale is its
     * exact drawn size / the full icon size -- so the app tile begins pixel-aligned on its own preview
     * mini-icon and the swap reads as the preview simply becoming the tile (owner 2026-08-24, "there's
     * still like a transition"). Beyond four children there is no preview slot: fall back to the
     * cluster centre at [WP_FALL_TIP_SCALE]. Returns true iff a real preview slot was used.
     */
    private fun wpChildStart(folderInfo: FolderInfo, index: Int, out: FloatArray): Boolean {
        val fv = findViewHolderForItemId(folderInfo.id.toLong())?.itemView
        if (fv == null) { out[0] = 0f; out[1] = 0f; out[2] = 0f; out[3] = WP_FALL_TIP_SCALE; return false }
        out[2] = fv.bottom.toFloat() // tipY (teardrop tip = folder tile bottom)
        val icon = (fv as? ViewGroup)?.getChildAt(0) as? FolderIcon
        if (icon != null && icon.getAresPreviewSlot(index, tmpPreview)) {
            out[0] = fv.left + icon.left + tmpPreview[0]
            out[1] = fv.top + icon.top + tmpPreview[1]
            val iconPx = launcher.deviceProfile.iconSizePx.toFloat()
            out[3] = if (iconPx > 0f) tmpPreview[2] / iconPx else WP_FALL_TIP_SCALE
            return true
        }
        // Fallback: cluster centre (or tile centre if the icon isn't bound), small tip scale.
        if (icon != null) {
            icon.getAresPreviewCenter(tmpPreview)
            out[0] = fv.left + icon.left + tmpPreview[0]
            out[1] = fv.top + icon.top + tmpPreview[1]
        } else {
            out[0] = fv.left + fv.width / 2f
            out[1] = out[2]
        }
        out[3] = WP_FALL_TIP_SCALE
        return false
    }

    private val tmpStart = FloatArray(4)
    private val tmpCardRect = RectF()

    /**
     * Set the alpha of a tile's EDIT-MODE CHROME -- the cell-outline background (the green tint) and
     * the ×, ⓘ and resize-handle badge views. During a WP folder open/close in edit mode the chrome
     * is hidden so ONLY the icons fly, then faded back once each icon lands (owner 2026-08-25).
     * A no-op off edit mode (no background, no badges).
     */
    private fun setEditChromeAlpha(container: View, a: Float) {
        container.background?.alpha = (a * 255f).toInt().coerceIn(0, 255)
        (container as? ViewGroup)?.let { g ->
            for (i in 0 until g.childCount) {
                val c = g.getChildAt(i)
                when (c.tag) {
                    AresRemoveBadge.BADGE_TAG, AresInfoBadge.BADGE_TAG, AresWidgetResize.CHEVRON_TAG ->
                        c.alpha = a
                }
            }
        }
    }

    /** Fade a tile's edit chrome back in after its open-fall lands. */
    private fun fadeInEditChrome(container: View) {
        ValueAnimator.ofFloat(0f, 1f).apply {
            duration = EDIT_CHROME_FADE_MS
            addUpdateListener { setEditChromeAlpha(container, it.animatedFraction) }
            start()
        }
    }

    /**
     * The signature WP folder motion: a child icon FALLS out of the folder icon. It appears small at
     * its PREVIEW slot on the folder's face ([originX]/[originY]), drops through the teardrop toward
     * the card (down to [tipY] + [WP_FALL_DROP_PX]) under gravity, then spreads + enlarges to its cell
     * on a bouncy settle. [forward]=false walks the same path backwards for the close (icon rises from
     * its cell, gathers under the teardrop, then rises back into the folder's preview and fades).
     * Driven by one ValueAnimator per child in "offset from the laid-out cell" space (translation 0 ==
     * the cell) so it lands exactly on the real layout with no drift. Honours the animator scale.
     */
    private fun playWpChildFall(
        v: View,
        originX: Float,
        originY: Float,
        tipY: Float,
        forward: Boolean,
        delayMs: Long,
        durationMs: Long,
        endScale: Float,
        tiltDeg: Float,
        startScale: Float,
        hasSlot: Boolean,
        seed: Int,
    ) {
        // Pivot on the tile's ICON centre, not its view centre: the origin is a preview-icon centre, so
        // aligning the icon (not the label-inclusive view box) is what makes the tile sit pixel-exact on
        // its preview slot. At rest (scale 1, translation 0) the pivot is irrelevant, so the cell landing
        // is unaffected.
        val btv = (v as? ViewGroup)?.getChildAt(0) as? BubbleTextView
        val iconPx = launcher.deviceProfile.iconSizePx.toFloat()
        val pivotXv = btv?.let { it.left + it.width / 2f } ?: (v.width / 2f)
        val pivotYv = btv?.let { it.top + it.paddingTop + iconPx / 2f } ?: (v.height / 2f)
        v.pivotX = pivotXv
        v.pivotY = pivotYv
        val cx = v.left + pivotXv
        val cy = v.top + pivotYv
        val startOffX = originX - cx
        val startOffY = originY - cy
        val dropOffX = originX - cx // drop straight down from the preview origin

        // Per-child variation (owner 2026-08-24: "less unified between all apps ... more natural,
        // fluid, bouncy"). Each icon gets its OWN drop depth, arc curvature, pace and settle bounce,
        // seeded DETERMINISTICALLY from its index so the fan looks organic instead of a rigid
        // formation -- and stable frame-to-frame (no per-frame jitter). None of it moves the landing:
        // the Bezier's end point is always the exact cell (0,0).
        val rDrop = wpRnd(seed, 12.9898f)
        val rBow = wpRnd(seed, 78.233f)
        val rLift = wpRnd(seed, 55.31f)
        val rDur = wpRnd(seed, 39.425f)
        val rOver = wpRnd(seed, 27.611f)
        val dropPx = WP_FALL_DROP_PX * (1f + (rDrop * 2f - 1f) * WP_FALL_DROP_JITTER)
        val dropOffY = (tipY + dropPx * resources.displayMetrics.density) - cy
        // Fan-arc Bezier control, varied per child: cxCtrl decides how long the icon stays under the
        // folder before cutting toward its column; cyCtrl bows the arc deeper for some. Baseline is
        // (dropOffX, small) = "rise then arc"; the jitter bends each icon's path a little differently.
        val cxCtrl = dropOffX * (1f - rBow * WP_FALL_BOW)
        val cyCtrl = dropOffY * (WP_FALL_BOW_LIFT_MIN + rLift * WP_FALL_BOW_LIFT_SPAN)
        // Settle with WEIGHT, varied per child: a higher-tension overshoot lands harder and springs
        // more -- so the icons don't all plop identically.
        val spread = android.view.animation.OvershootInterpolator(
            WP_FALL_OVERSHOOT_MIN + rOver * WP_FALL_OVERSHOOT_SPAN,
        )
        // Mid-fall (drop-point) size: a fraction of the way from this child's own start scale toward
        // full, so the growth is monotonic no matter how large the preview slot already is.
        val midScale = startScale + (1f - startScale) * WP_FALL_DROP_FRAC
        // A slightly different pace per child (open only -- close timing feeds the deferred removal, so
        // it must stay the value the adapter scheduled against).
        val durMs = if (forward) {
            (durationMs * (1f + (rDur * 2f - 1f) * WP_FALL_DUR_JITTER)).toLong()
        } else {
            durationMs
        }
        // Folder-background bounds: the flying icon is kept INSIDE the card (owner 2026-08-24, "should
        // NOT go beyond the folder background"). Captured once here into immutable locals so all the
        // children's concurrent animators clamp against a stable rect.
        val hasCard = folderBounds.cardContentRect(tmpCardRect)
        val cardL = tmpCardRect.left
        val cardR = tmpCardRect.right
        val cardB = tmpCardRect.bottom
        v.animate().cancel()

        // Hide the icon's LABEL while it is in motion, and fade it back in only once it lands (owner
        // 2026-08-24): a swarm of labels mid-flight reads as clutter. The tile is a container whose
        // first child is the BubbleTextView that owns the text.
        if (forward) {
            btv?.setTextVisibility(false) // derender the label the instant it lifts off
        } else {
            // Close: fade the label out as the icon lifts (it is about to leave its place). No
            // restore needed -- the row is removed and re-inflated fresh on the next bind.
            btv?.createTextAlphaAnimator(false)?.setDuration(WP_TEXT_FADE_MS)?.start()
        }

        // Pre-set the tile to its start frame so it is not briefly visible at its cell during the
        // stagger delay (open only; on close it legitimately starts at the cell). The tile is kept
        // HIDDEN (alpha 0) for the whole stagger delay and only appears when its fall actually
        // begins -- the animator's first update frame (which fires only AFTER startDelay) sets the
        // real alpha. Previously a slot-backed tile was pre-set to FULL alpha to sit over its preview
        // mini-icon as a "seamless swap", but the enter delay is timed past the folder card's morph,
        // so during it the card is still moving while the parked tile stays put -- it drifted off the
        // card and read as a tiny static icon appearing early, below the folder (owner 2026-08-25,
        // "small Instagram icon appears before the folder expands"). Hidden-until-flight removes that
        // stray frame; the folder's own preview covers the spot until the child streams out.
        if (forward) {
            v.translationX = startOffX
            v.translationY = startOffY
            v.scaleX = startScale * endScale; v.scaleY = startScale * endScale
            v.rotation = tiltDeg
            v.alpha = 0f
            // In edit mode, hide the ×/ⓘ/tint so only the icon flies; it fades back on landing.
            if (isEditMode()) setEditChromeAlpha(v, 0f)
        }

        ValueAnimator.ofFloat(0f, 1f).apply {
            duration = durMs
            startDelay = delayMs
            addUpdateListener { anim ->
                val t = if (forward) anim.animatedFraction else 1f - anim.animatedFraction
                var x: Float; var y: Float; val s: Float; val al: Float; val rot: Float
                if (t <= WP_FALL_SEG) {
                    val frac = (t / WP_FALL_SEG).coerceIn(0f, 1f)
                    val u = WP_FALL_FALL_INTERP.getInterpolation(frac)
                    // Smooth accelerating fall straight to the drop point -- NO bounce at the bottom
                    // (owner 2026-08-24: a bounce here read as awkward, esp. for folders with many
                    // items); it flows directly into the seg-2 curve, which keeps its springy settle.
                    x = lerpF(startOffX, dropOffX, u)
                    y = lerpF(startOffY, dropOffY, u)
                    s = lerpF(startScale, midScale, u)
                    // Slot-backed tiles stay fully opaque (they ARE the preview); slotless ones fade in.
                    al = if (hasSlot) 1f else (u * 2f).coerceIn(0f, 1f)
                    rot = tiltDeg // hold the tilt while it tumbles out
                } else {
                    // Bouncy, weighty settle: the per-child OvershootInterpolator pushes u past 1 then
                    // back, so position, scale AND the tilt overshoot their target and spring into place.
                    val u = spread.getInterpolation(
                        ((t - WP_FALL_SEG) / (1f - WP_FALL_SEG)).coerceIn(0f, 1f),
                    )
                    // CURVED fan-out (owner 2026-08-24, "dropped and curved into its final spot"): a
                    // quadratic Bezier from the drop point (dropOffX, dropOffY) to the cell (0, 0) with
                    // a per-child control (cxCtrl, cyCtrl). The icon rises up out of the drop then arcs
                    // sideways into its cell -- one fluid curve, each app's a little different. P2 is
                    // always the exact cell, so u=1 lands dead-on; u>1 (the overshoot) carries it past
                    // the cell along the arc then springs back = weight.
                    val omu = 1f - u
                    x = omu * omu * dropOffX + 2f * omu * u * cxCtrl
                    y = omu * omu * dropOffY + 2f * omu * u * cyCtrl
                    s = lerpF(midScale, 1f, u)
                    al = 1f
                    rot = lerpF(tiltDeg, 0f, u) // overshoots through 0 -> a little wobble, then level
                }
                if (hasCard) {
                    // Keep the flying icon INSIDE the folder background: clamp its CENTRE to the card's
                    // sides and floor so the arc, overshoot and deep drop can't spill past the edges.
                    // The top is left free -- the icon legitimately starts up on the folder face
                    // (teardrop). A cell centre is always inside the card, so the landing (x=y=0) is
                    // never clamped.
                    val rad = iconPx * s * endScale * 0.5f
                    var cX = cx + x
                    val loX = cardL + rad
                    val hiX = cardR - rad
                    if (hiX > loX) cX = cX.coerceIn(loX, hiX)
                    var cY = cy + y
                    val hiY = cardB - rad
                    if (cY > hiY) cY = hiY
                    x = cX - cx
                    y = cY - cy
                }
                v.translationX = x
                v.translationY = y
                v.scaleX = s * endScale; v.scaleY = s * endScale
                v.rotation = rot
                v.alpha = al
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    // Land clean at the cell (open). On close a slot-backed tile finishes FULLY OPAQUE
                    // exactly over its preview slot, so when the adapter restores the preview + removes
                    // the row there is no blink; a slotless one fades to invisible as before.
                    v.translationX = 0f
                    v.translationY = 0f
                    v.scaleX = endScale; v.scaleY = endScale
                    v.rotation = 0f
                    v.alpha = if (forward || hasSlot) 1f else 0f
                    // Rerender the label once the icon reaches its place (open only) -- but NOT in
                    // edit mode, where labels are deliberately hidden (AresEditLabel). The folder
                    // children are plain grid BubbleTextViews, so shouldTextBeVisible() is true for
                    // them; without this guard the landing fade-in forced their names back on over
                    // the edit-mode hide (owner 2026-08-25).
                    if (forward && !isEditMode()) {
                        btv?.createTextAlphaAnimator(true)?.setDuration(WP_TEXT_FADE_MS)?.start()
                    }
                    // Fade the ×/ⓘ/tint back now that the icon has landed (open, edit mode).
                    if (forward && isEditMode()) fadeInEditChrome(v)
                }
            })
            start()
        }
    }

    private fun lerpF(a: Float, b: Float, u: Float): Float = a + (b - a) * u

    /**
     * Deterministic pseudo-random in [0,1) from a child [seed] and a [salt] (the classic sin-hash).
     * Stable across frames -- the same child always draws the same value, so the per-child animation
     * variation is fixed for a given open, not jittering every frame.
     */
    private fun wpRnd(seed: Int, salt: Float): Float {
        val v = kotlin.math.sin((seed + 1).toFloat() * salt) * 43758.545f
        return v - kotlin.math.floor(v)
    }

    /**
     * The CLOSE of one child -- the clean mirror of [playWpChildFall], played in FORWARD time (owner
     * 2026-08-24: "apply the same principles ... they're a bit buggy and inconsistent"). The old close
     * time-REVERSED the open, which flipped the settle overshoot into an outward lurch before the icon
     * gathered in. Instead this runs its own two phases:
     *   phase 1 (0..[WP_CLOSE_SEG]): the icon leaves its cell (0,0) and curves IN to the drop point,
     *     along the same per-child Bezier the open used (control cxCtrl/cyCtrl), gathering speed;
     *   phase 2 ([WP_CLOSE_SEG]..1): it RISES straight up from the drop point into its preview slot on
     *     the folder face, shrinking to [startScale] and fading to 0 -- drawn back into the folder.
     * Same per-child variation seed as the open (so an app closes consistently with how it opened) and
     * the same card clamp. The tile ends invisible; the row is removed right after by finishCollapse.
     */
    private fun playWpChildClose(
        v: View,
        originX: Float,
        originY: Float,
        tipY: Float,
        delayMs: Long,
        durationMs: Long,
        endScale: Float,
        tiltDeg: Float,
        startScale: Float,
        hasSlot: Boolean,
        seed: Int,
    ) {
        val btv = (v as? ViewGroup)?.getChildAt(0) as? BubbleTextView
        val iconPx = launcher.deviceProfile.iconSizePx.toFloat()
        val pivotXv = btv?.let { it.left + it.width / 2f } ?: (v.width / 2f)
        val pivotYv = btv?.let { it.top + it.paddingTop + iconPx / 2f } ?: (v.height / 2f)
        v.pivotX = pivotXv
        v.pivotY = pivotYv
        val cx = v.left + pivotXv
        val cy = v.top + pivotYv
        val slotOffX = originX - cx // where it ends: the preview slot on the folder face
        val slotOffY = originY - cy
        // Per-child variation, SAME seeds as the open so the drop depth + arc match how it opened.
        val rDrop = wpRnd(seed, 12.9898f)
        val rBow = wpRnd(seed, 78.233f)
        val rLift = wpRnd(seed, 55.31f)
        val dropPx = WP_FALL_DROP_PX * (1f + (rDrop * 2f - 1f) * WP_FALL_DROP_JITTER)
        val dropOffX = slotOffX // drop point is straight below the slot (same x)
        val dropOffY = (tipY + dropPx * resources.displayMetrics.density) - cy
        val cxCtrl = dropOffX * (1f - rBow * WP_FALL_BOW)
        val cyCtrl = dropOffY * (WP_FALL_BOW_LIFT_MIN + rLift * WP_FALL_BOW_LIFT_SPAN)
        val midScale = startScale + (1f - startScale) * WP_FALL_DROP_FRAC
        val hasCard = folderBounds.cardContentRect(tmpCardRect)
        val cardL = tmpCardRect.left
        val cardR = tmpCardRect.right
        val cardB = tmpCardRect.bottom
        v.animate().cancel()
        // In edit mode, hide the ×/ⓘ/tint so only the icon rises back into the folder (the row is
        // removed at the end of the close, so there is nothing to fade back).
        if (isEditMode()) setEditChromeAlpha(v, 0f)
        // Fade the label out as the icon lifts (it is leaving its place; row re-inflated fresh on bind).
        btv?.createTextAlphaAnimator(false)?.setDuration(WP_TEXT_FADE_MS)?.start()

        ValueAnimator.ofFloat(0f, 1f).apply {
            duration = durationMs
            startDelay = delayMs
            addUpdateListener { anim ->
                val p = anim.animatedFraction
                var x: Float; var y: Float; val s: Float; val al: Float; val rot: Float
                if (p <= WP_CLOSE_SEG) {
                    // Curve IN: cell (0,0) -> drop point, quadratic Bezier via the per-child control.
                    val u = WP_CLOSE_IN_INTERP.getInterpolation((p / WP_CLOSE_SEG).coerceIn(0f, 1f))
                    val omu = 1f - u
                    x = 2f * omu * u * cxCtrl + u * u * dropOffX
                    y = 2f * omu * u * cyCtrl + u * u * dropOffY
                    s = lerpF(1f, midScale, u)
                    al = 1f
                    rot = lerpF(0f, tiltDeg, u) // tips over as it dives in
                } else {
                    // Rise INTO the folder: drop point -> preview slot, shrink + fade.
                    val u = WP_CLOSE_RISE_INTERP.getInterpolation(
                        ((p - WP_CLOSE_SEG) / (1f - WP_CLOSE_SEG)).coerceIn(0f, 1f),
                    )
                    x = lerpF(dropOffX, slotOffX, u)
                    y = lerpF(dropOffY, slotOffY, u)
                    s = lerpF(midScale, startScale, u)
                    // NO fade for slot-backed icons (owner 2026-08-24: "same as the open but in
                    // reverse -- not the fade"): the open never faded them, so the close doesn't
                    // either -- it rides to the slot at full alpha and the preview snaps in for it.
                    // A slotless overflow child (5th+, no preview) still fades out, mirroring its
                    // fade-IN on the open.
                    al = if (hasSlot) 1f else 1f - u
                    rot = lerpF(tiltDeg, 0f, u)
                }
                if (hasCard) {
                    // Keep the icon inside the folder background (sides + floor; top free, it rises out).
                    val rad = iconPx * s * endScale * 0.5f
                    var cX = cx + x
                    val loX = cardL + rad
                    val hiX = cardR - rad
                    if (hiX > loX) cX = cX.coerceIn(loX, hiX)
                    var cY = cy + y
                    val hiY = cardB - rad
                    if (cY > hiY) cY = hiY
                    x = cX - cx
                    y = cY - cy
                }
                v.translationX = x
                v.translationY = y
                v.scaleX = s * endScale; v.scaleY = s * endScale
                v.rotation = rot
                v.alpha = al
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    if (hasSlot) {
                        // Hide the instant it lands. It was HELD here tiny at full alpha to "read as
                        // the preview" until finishCollapse restored the real one -- but the cascade's
                        // staggered finishes mean an early-landing child waits out the rest of the run
                        // (up to (n-1)*stagger) holding tiny, and by then the folder card has already
                        // faded ~all the way out (beginExit runs over the whole `total`), so the held
                        // mini-icon reads as a stray tiny icon floating with no card behind it -- the
                        // close-side of the owner's "tiny icon appears" flash (2026-08-25), the mirror
                        // of the open pre-set. Going invisible removes it; the collapsed folder tile's
                        // own preview takes the slot back at finishCollapse a few frames later.
                        v.translationX = slotOffX
                        v.translationY = slotOffY
                        v.scaleX = startScale * endScale; v.scaleY = startScale * endScale
                        v.rotation = 0f
                        v.alpha = 0f
                    } else {
                        // Slotless overflow child: it faded out; leave it invisible (row about to go).
                        v.translationX = 0f
                        v.translationY = 0f
                        v.scaleX = endScale; v.scaleY = endScale
                        v.rotation = 0f
                        v.alpha = 0f
                    }
                }
            })
            start()
        }
    }

    /**
     * WP accordion CLOSE (owner 2026-08-24) -- the exact reverse of [onWpFolderExpanded]. The opened
     * apps furl back UP into the folder tile (slide toward its bottom edge + fade + shrink) and the
     * card fades OUT, and only THEN (deferred by the adapter) do the rows get removed, the tiles
     * reflow up, and the teardrop morph back to a circle. So the content leaves first and the surface
     * settles after -- the mirror of the open, where the surface leads and the content follows.
     *
     * Returns true when it started an exit animation (live child tiles are on screen); the adapter
     * then defers the structural collapse until it finishes. Returns 0 when
     * there is nothing to animate (folder scrolled off, empty) so the adapter collapses immediately.
     */
    fun onWpFolderCollapsing(folderInfo: FolderInfo): Int {
        val childIds = folderInfo.getContents().sortedBy { it.rank }.map { it.id }
        if (childIds.isEmpty()) return 0
        val endScale = if (isEditMode()) EDIT_MODE_SCALE else 1f
        val n = childIds.size
        var any = false
        childIds.forEachIndexed { i, id ->
            val v = findViewHolderForItemId(id.toLong())?.itemView ?: return@forEachIndexed
            any = true
            // Each child furls back to its OWN preview slot (mirror of the open), so it lands
            // pixel-exact where the restored preview icon will reappear. Reverse cascade: the FARTHEST
            // child leaves first, so the run zips back UP into the tile.
            val hasSlot = wpChildStart(folderInfo, i, tmpStart)
            playWpChildClose(
                v, tmpStart[0], tmpStart[1], tmpStart[2],
                delayMs = (n - 1 - i) * WP_FALL_CLOSE_STAGGER_MS,
                durationMs = WP_FALL_CLOSE_MS,
                endScale = endScale,
                tiltDeg = if (i % 2 == 0) WP_FALL_TILT_DEG else -WP_FALL_TILT_DEG,
                startScale = tmpStart[3],
                hasSlot = hasSlot,
                seed = id,
            )
        }
        if (!any) return 0
        // Total time for the whole reverse cascade to finish; the adapter removes the rows only then.
        val total = ((n - 1) * WP_FALL_CLOSE_STAGGER_MS + WP_FALL_CLOSE_MS).toInt()
        // Fade the card out over that same window so it is gone before the run is removed (no pop).
        folderBounds.beginExit(total.toFloat())
        return total
    }

    /**
     * Bring the expanded run into view when the folder is low in a long list, capped so the folder
     * header never leaves the top. Positive dy scrolls content up (reveals what is below).
     */
    private fun nudgeExpandedIntoView(folderInfo: FolderInfo, childIds: List<Int>) {
        val folderHolder = findViewHolderForItemId(folderInfo.id.toLong()) ?: return
        // Never scroll the folder header above the top: cap the nudge at how far the header can
        // travel before it reaches paddingTop.
        val maxNudge = (folderHolder.itemView.top - paddingTop).coerceAtLeast(0)
        val lastHolder = findViewHolderForItemId(childIds.last().toLong())
        val nudge = if (lastHolder != null) {
            val overflow = lastHolder.itemView.bottom - (height - paddingBottom)
            if (overflow <= 0) return // the whole opened run already fits on screen
            overflow.coerceAtMost(maxNudge)
        } else {
            // The run is longer than the viewport, so the LAST child isn't laid out and can't be
            // measured -- exactly the low-folder case MJ-3 targets, where the old early-return did
            // nothing (adversarial review 2026-08-24, finding 7). Fall back to pulling the folder
            // header to the top, which reveals as many children as fit below it.
            maxNudge
        }
        if (nudge > 0) smoothScrollBy(0, nudge)
    }

    /**
     * Ends a resize: writes [target], or puts the widget back to [from].
     *
     * `persistSize` is the only writer, and it reallocates a legal `cellX/cellY` for the new
     * footprint -- growing a span in place leaves the stored coordinate breaking the loader's
     * bounds rule, and the loader deletes what it rejects. A refused write restores the original
     * size rather than leaving the grid showing a footprint the database does not have.
     */
    private fun finishResize(
        info: ItemInfo,
        from: AresPacker.Span,
        target: AresPacker.Span,
        commit: Boolean,
    ) {
        resizeItem = null
        resizeFrom = null
        resizeAllowed = emptyList()
        setReorderInProgress(false)

        if (!commit || (target.w == from.w && target.h == from.h)) {
            applyLiveSpan(info, from)
            return
        }
        if (!AresWidgetResize.persistSize(launcher, info, target)) {
            Log.d(TAG, "resize refused for id=${info.id}; restoring ${from.w}x${from.h}")
            applyLiveSpan(info, from)
            return
        }
        applyLiveSpan(info, target)
        announceForAccessibility(
            context.getString(com.android.launcher3.R.string.widget_resized, target.w, target.h),
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
        // WP folders (design/wp-folder-design.md): the create-new-folder FAB lives only in edit
        // mode. It is a DragLayer overlay, so it does not disturb the grid's own layout.
        AresWpFab.attach(launcher)
        applyEditModeVisual()
        // Keep an open folder's tile showing its preview circle (not the frost-box-clipped pointer)
        // and its label hidden, after the walk above (§ AresHomeAdapter.refreshExpandedFolderTile).
        aresAdapter.refreshExpandedFolderTile()
        // Let the edge back GESTURE through. Stock suppresses it at NORMAL with nothing floating,
        // and edit mode is still NORMAL -- so without this the mode can only be left with the BACK
        // key. See LawnchairLauncher.aresWantsBackGesture.
        launcher.updateDisallowBack()
    }

    /** Leaves edit mode, cancelling any in-flight drag. Safe to call when not in edit mode. */
    fun exitEditMode(): Boolean {
        if (!editMode) return false
        editMode = false
        setReorderInProgress(false)
        // Drop any i-menu label suppression BEFORE the un-hide walk below. If a popup is still open
        // when the mode ends (HOME / a home gesture from another app runs exitEditMode before super
        // closes floating views), a still-set flag would make the walk's un-hide read
        // shouldTextBeVisible()==false and strand that tile's label blank. See
        // AresEditLabel.clearMenuSuppression.
        AresEditLabel.clearMenuSuppression()
        // An open folder's × badges belong to this mode too. They normally go with the folder --
        // it closes before edit mode can be left -- but HOME closes floating views and exits the
        // mode in one pass, so the two orders must both end clean.
        AresFolderEdit.detach()
        // Same reasoning for a folder that a dwell opened mid-drag: HOME and BACK can end the mode
        // while a drag is still live, and an abandoned preview must not commit anything, must not
        // leave a phantom slot in the folder, and must not leave the reflow frozen.
        AresFolderDrop.cancel()
        // Set before the visual walk, for the same reason as enterEditMode (§6).
        aresAdapter.setEditMode(false)
        // WP folders: the create-new-folder FAB belongs to the mode; drop it as the mode ends.
        AresWpFab.detach()
        // Cancel every animator up front rather than relying on the per-child walk: children that
        // scrolled out while editing are no longer attached, so the walk would never reach them and
        // their animators would outlive the mode.
        clearWiggles()
        applyEditModeVisual()
        // Restore the open folder's dropped pointer and re-hide its label -- the walk above un-hides
        // tile labels, which would otherwise strand the folder's label showing over its pointer.
        aresAdapter.refreshExpandedFolderTile()
        // Hand the edge back to the system again now that there is nothing here to dismiss.
        launcher.updateDisallowBack()
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
            child.animate().scaleX(tileScale(child)).scaleY(tileScale(child))
                .setDuration(EDIT_SCALE_MS).start()
            setItemClickable(child, !editMode)
            syncEditVisuals(child)
            syncWiggle(child)
        }
        animateGridDots()
    }

    /**
     * The tile currently in the user's hand, or null.
     *
     * Only the scale is kept here; the float suspension and the reflow exemption ride on
     * [setFloatSuspendedFor], which `ItemTouchHelper` drives from the same two callbacks.
     */
    private var pickedUp: View? = null

    /**
     * What size [child] should be resting at right now.
     *
     * One expression, consulted by every writer of the scale on this surface — the edit-mode walk,
     * the attach hook and the pick-up bump — so the three cannot disagree. The picked-up tile is a
     * **multiple** of the mode's rest scale rather than an absolute, so the same gesture reads
     * identically here (0.92 → 1.03) and inside an open folder (1.00 → 1.12), where the rest scale
     * is different. See [AresEditMotion.PICKUP_SCALE_FACTOR].
     */
    private fun tileScale(child: View): Float {
        val rest = if (editMode) EDIT_MODE_SCALE else 1f
        if (child !== pickedUp) return rest

        // The bump is a RATIO, which is size-blind, and on a big enough tile it lifts the tile
        // straight past the edge of the list. A 1x1 icon grows about 8px and looks right; a
        // full-width widget grows ~31px against a viewport with nothing spare, and the result is a
        // widget clipped along both sides while it is held. Reported as "holding a large widget, it
        // enlarges but it's too big so it's getting clipped".
        //
        // So keep 1.12 as the intent and clamp it to what actually fits. Anything at or below about
        // three columns still gets the full bump; only the tiles that would overflow are reduced,
        // and they are reduced to exactly the largest lift that stays inside the viewport rather
        // than being denied one.
        // Keep a little margin while lifted, rather than letting the bump run right out to the
        // physical edge: a full-width tile clamps to exactly the list width otherwise, which is
        // not clipped but reads as though it is about to be.
        val desired = rest * AresEditMotion.PICKUP_SCALE_FACTOR
        val margin = resources.getDimensionPixelSize(R.dimen.ares_home_widget_inset).toFloat()
        val availW = (width - paddingLeft - paddingRight).toFloat() - margin
        val availH = (height - paddingTop - paddingBottom).toFloat() - margin
        val fitW = if (child.width > 0 && availW > 0f) availW / child.width else desired
        val fitH = if (child.height > 0 && availH > 0f) availH / child.height else desired
        // Never below the resting scale: a tile taller than the viewport would otherwise be told to
        // shrink on pick-up, which reads as the opposite of being lifted.
        return minOf(desired, fitW, fitH).coerceAtLeast(rest)
    }

    /**
     * Enlarges [child] slightly to mark it as picked up, or restores the previous one when passed
     * null.
     *
     * > *"when selecting an item in edit mode, it slightly enlarges to really highlight that its
     * > been selected"*
     *
     * Called from [AresHomeReorder.Callback] at `onSelectedChanged(ACTION_STATE_DRAG)` and
     * `clearView` — the same pair that suspends and restores the float, because they are the exact
     * moments `ItemTouchHelper` takes and releases the tile.
     *
     * **No pivot is set, deliberately.** `View` treats an explicitly-set pivot as sticky and
     * nothing would put it back, which is how the resize chevron was killed once already (see the
     * ⛔ note in [AresMasonryLayoutManager]): a leftover top-left pivot drew every tile up to 8%
     * off its layout box while hit-testing stayed transform-blind. The default pivot is the view's
     * centre, which is what this wants anyway — the tile should swell in place, not toward a corner.
     *
     * **Restores to the rest scale, never to a constant.** [tileScale] reads the *current* mode, so
     * a drag that outlives edit mode (the mode exited mid-gesture) settles at 1.0 rather than
     * snapping back to 0.92, and nothing is left enlarged.
     */
    fun setPickedUp(child: View?) {
        val previous = pickedUp
        if (previous === child) return
        pickedUp = child
        previous?.let { animateTileScale(it) }
        child?.let { animateTileScale(it) }
        // Once per drag, and it says the absolute number rather than the factor: "0.92 times 1.12"
        // is not a thing anyone can check against what they are looking at, and a scale that is
        // silently not applied looks exactly like one that is too small to see.
        Log.d(TAG, "pickup: ${if (child != null) "held at" else "released to"} " +
            "${child?.let { tileScale(it) } ?: previous?.let { tileScale(it) }}")
    }

    private fun animateTileScale(child: View) {
        val scale = tileScale(child)
        if (!ValueAnimator.areAnimatorsEnabled()) {
            child.scaleX = scale
            child.scaleY = scale
            return
        }
        // ViewPropertyAnimator, like the edit-mode walk: a second animate() call on the same view
        // cancels the pending animation of the same property rather than running beside it, so the
        // two can never both be driving the scale.
        child.animate().scaleX(scale).scaleY(scale).setDuration(AresEditMotion.PICKUP_MS).start()
    }

    /**
     * Plays "a folder was just made here" on the tile bound to [info] (§C3).
     *
     * > *"when holding an app over another app to generate a folder, there is no folder creation
     * > animation to indicate folder creation"*
     *
     * Dropping into an **existing** folder already animates — `Folder.addFolderContent` refreshes
     * the icon's preview with `animate = true`, so the new app visibly drops into the stack.
     * Creation had nothing: the two tiles vanished and a folder appeared in one frame, which reads
     * as a glitch rather than as an outcome. The two are the same event to the user and should read
     * that way.
     *
     * ## Why a pop on the tile rather than stock's create animation
     *
     * `FolderIcon.performCreateAnimation` is the stock equivalent and it is unreachable here: it
     * wants the source and destination *views* plus a rect in the drag layer's space, and it is
     * driven from `CellLayout`'s drop path, which Strategy D does not use. Our creation is a model
     * write followed by an adapter insert, and the honest signal for that is the new tile arriving
     * with weight — scaled up from small with an overshoot, in the slot the user was aiming at.
     *
     * ## It waits for the BIND, it does not poll for it
     *
     * The insert only notifies the adapter; the holder is built later, so asking for it immediately
     * answers null. Two other hooks were tried on device first and both were wrong:
     *
     *  - A **posted retry** did not find the holder for **2.4 seconds** on emulator-5554, with the
     *    insert's own change animation in flight — by which time an "it just happened" cue is
     *    describing something that did not just happen.
     *  - **`onChildAttachedToWindow` never fired at all.** Creating a folder removes two rows and
     *    inserts one in the same pass, so RecyclerView rebinds a holder that is *already attached*
     *    rather than attaching a new one. Measured: the pop was armed and no attach ever came.
     *
     * `onBindViewHolder` is the moment that does happen, every time, for an inserted item. The pop
     * itself is posted from there rather than run inline, because `onChildAttachedToWindow` writes
     * the resting scale afterwards and would otherwise wipe the pop's starting size.
     * [CREATED_PENDING_MS] drops the arming if no bind arrives at all.
     */
    fun playFolderCreated(info: ItemInfo) {
        if (!ValueAnimator.areAnimatorsEnabled()) return
        val position = aresAdapter.indexOf(info)
        val child = if (position >= 0) findViewHolderForAdapterPosition(position)?.itemView else null
        if (child != null) {
            popCreated(child, info.id)
            return
        }
        pendingCreatedId = info.id
        removeCallbacks(clearPendingCreated)
        postDelayed(clearPendingCreated, CREATED_PENDING_MS)
    }

    /**
     * Eject (owner 2026-08-25): an app leaving a folder pops in at its new grid cell while the
     * folder run and the tiles below slide closed. The slide is [animateNextRelayout] (armed by the
     * caller, exempting this item); this supplies the arrival pop. Reuses the folder-created pop --
     * same overshoot scale-in, same arm-until-bound path for the common case where the new desktop
     * row has not laid out yet in the frame the extract mutates the adapter.
     */
    fun playFolderChildEjected(info: ItemInfo) = playFolderCreated(info)

    /** The folder whose arrival pop is waiting for its row to bind, or [ItemInfo.NO_ID]. */
    private var pendingCreatedId = ItemInfo.NO_ID

    private val clearPendingCreated = Runnable { pendingCreatedId = ItemInfo.NO_ID }

    /** Fires the armed arrival pop when its row binds. Wired to the adapter in `init`. */
    private fun onRowBound(info: ItemInfo, container: View) {
        // Keep a freshly-bound (e.g. scrolled-in) tile in step with the open-folder wash. Membership
        // is by id, not adapter position, because the view is not attached yet at bind time.
        if (washStrength > 0.001f) {
            val fid = aresAdapter.expandedWpFolder()
            val partOfFolder = fid != -1 && (info.id == fid || info.container == fid)
            applyTileWash(container, if (partOfFolder) 0f else washStrength)
            if (partOfFolder) unfreezeTile(container) else freezeTile(container)
        }
        if (pendingCreatedId == ItemInfo.NO_ID || info.id != pendingCreatedId) return
        pendingCreatedId = ItemInfo.NO_ID
        removeCallbacks(clearPendingCreated)
        val id = info.id
        container.post { popCreated(container, id) }
    }

    private fun popCreated(child: View, id: Int) {
        val rest = tileScale(child)
        child.scaleX = rest * FOLDER_CREATED_FROM
        child.scaleY = rest * FOLDER_CREATED_FROM
        child.alpha = 0f
        child.animate()
            .scaleX(rest).scaleY(rest).alpha(1f)
            .setInterpolator(OvershootInterpolator(FOLDER_CREATED_TENSION))
            .setDuration(FOLDER_CREATED_MS)
            .start()
        Log.d(TAG, "folder $id created; playing the arrival pop")
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
        AresEditWiggle.start(child, getChildAdapterPosition(child)) { editMode }
            ?.let { wiggles[child] = it }
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
    /**
     * The tile [ItemTouchHelper] is currently dragging, or null.
     *
     * Read by [AresFolderPreview] so it can stand a ghost in for the row while an open folder is
     * covering it. [floatSuspendedFor] is exactly that row and nothing else: it is set from
     * `onSelectedChanged(ACTION_STATE_DRAG)` and cleared in `clearView`, which are the two moments
     * `ItemTouchHelper` takes and releases the view.
     */
    fun draggedTile(): View? = floatSuspendedFor

    fun setFloatSuspendedFor(child: View?) {
        val previous = floatSuspendedFor
        if (previous === child) return
        floatSuspendedFor = child
        // The reflow stands down on the same tile for the same reason, and at exactly the same two
        // moments: `ItemTouchHelper` owns this view's translation until the drop settles.
        masonry.reflowExempt = child
        if (previous != null) syncWiggle(previous)
        if (child != null) syncWiggle(child)
    }

    /**
     * Tiles whose orbit float is suspended for the life of a repack animation
     * ([AresMasonryLayoutManager.animateFromPreviousBounds]), counted per tile.
     *
     * Distinct from [floatSuspendedFor], which is the single dragged tile and is also read as
     * [draggedTile] — a repack moves several tiles at once and must not disturb that slot. The
     * count lets overlapping repacks (rapid affordance taps, or a folder-create landing on a settle
     * still in flight) each balance their own suspend with their own resume: a tile suspended twice
     * resumes only after both repacks release it.
     */
    private val repackFloatSuspends = mutableMapOf<View, Int>()

    /**
     * Suspends the edit-mode orbit float on [child] while a repack animates its translation.
     *
     * The float ([AresEditWiggle]) writes `translationX/Y` every frame for the whole of edit mode,
     * so a repack's `ViewPropertyAnimator` on the same property is overwritten frame-by-frame and
     * the tile snaps to its new cell instead of sliding — the owner's "the apps jump to their new
     * positions with no animation" on the Pixel, and exactly the contention AresEditMotion's header
     * warned to fix here. This mirrors the drag's [setFloatSuspendedFor] but for the several tiles
     * one repack moves, and without claiming the single drag slot. Paired with [resumeFloatAfterRepack].
     */
    fun suspendFloatForRepack(child: View) {
        repackFloatSuspends[child] = (repackFloatSuspends[child] ?: 0) + 1
        AresEditWiggle.stop(child, wiggles.remove(child))
    }

    /**
     * Restores the orbit float on [child] when its repack ends — one resume per
     * [suspendFloatForRepack], counted, so a newer repack still holding the tile keeps it
     * suspended. A drag that took the tile meanwhile ([floatSuspendedFor]) keeps it; edit mode
     * ended leaves it at rest via [syncWiggle]'s own `editMode` guard.
     */
    fun resumeFloatAfterRepack(child: View) {
        val remaining = (repackFloatSuspends[child] ?: 0) - 1
        if (remaining > 0) {
            repackFloatSuspends[child] = remaining
            return
        }
        repackFloatSuspends.remove(child)
        if (child === floatSuspendedFor) return
        // The tile may have scrolled off during the repack's LAYOUT_ANIM_MS. syncWiggle on a
        // detached, recycled view starts an infinite orbit animator on it (position NO_POSITION),
        // which would then write translation to whatever item binds into it next.
        // onChildAttachedToWindow re-syncs it if the row comes back. (adversarial review 2026-08-22)
        if (getChildAdapterPosition(child) == NO_POSITION) return
        syncWiggle(child)
    }

    /** Stops every float and puts every tile back at rest, so nothing is left displaced. */
    private fun clearWiggles() {
        for ((child, animator) in wiggles) {
            AresEditWiggle.stop(child, animator)
        }
        wiggles.clear()
        repackFloatSuspends.clear()
        floatSuspendedFor = null
        masonry.reflowExempt = null
    }

    /**
     * Brings one attached row's edit-mode affordances in line with the current mode — the × badge,
     * the resize chevron and the cell outline, not the chevron alone.
     *
     * Adds and removes the views directly instead of rebinding, because widget holders are
     * non-recyclable: `notifyItemChanged` on one builds a *second* holder and leaves the first
     * attached, which leaked a widget host view per toggle.
     *
     * Note they ride on the holder container, which is also what the edit-mode scale animates — so
     * they transform with the item rather than sitting still over it.
     */
    private fun syncEditVisuals(child: View) {
        val container = child as? FrameLayout ?: return
        // Deliberately no early return on NO_POSITION. A row that has left the adapter -- removed,
        // or mid-animation on its way out -- is still an attached child carrying our × and chevron,
        // and skipping it is precisely how those were left behind after edit mode ended: the user
        // reported "the x won't leave and the resize button on one won't leave but neither are
        // actually editable", with the state dump confirming mState:Normal. getOrNull(-1) resolves
        // to a null item, which the adapter already reads as "this row carries nothing".
        aresAdapter.syncEditVisuals(container, getChildAdapterPosition(child))
    }

    override fun onChildAttachedToWindow(child: View) {
        super.onChildAttachedToWindow(child)
        // Rows bound while already editing (recycled in on scroll) must match the current mode --
        // including the pick-up bump, so a re-attached dragged tile does not shed it.
        val scale = tileScale(child)
        child.scaleX = scale
        child.scaleY = scale
        // A folder child attaching mid-open would paint at its final cell for the frame before the
        // posted fall repositions it onto its preview slot. Keep it hidden until the fall arms it.
        if (wpFallPendingFolderId != -1) {
            val pos = getChildAdapterPosition(child)
            if (pos != NO_POSITION && aresAdapter.itemAt(pos)?.container == wpFallPendingFolderId) {
                child.alpha = 0f
            }
        }
        setItemClickable(child, !editMode)
        syncWiggle(child)
        // Affordances too, and not only for rows that were just bound: widget holders are
        // `setIsRecyclable(false)`, so one that scrolls off and back on re-attaches *without*
        // onBindViewHolder running. Such a row kept whatever badge it had when it left, which
        // outlived the mode in both directions -- a stale × after exiting, and no × at all on a row
        // that scrolled in after editing began.
        syncEditVisuals(child)
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
        // Same guard, same reason: the label treatment moves the ICON inside the tile, and the tile
        // in the user's hand should not have its icon jump back up mid-drag if an auto-scroll ever
        // detaches it. Everything else gives its caption back here, so nothing recycles carrying a
        // half-applied one. onChildAttachedToWindow re-applies it if the row comes back.
        if (child !== floatSuspendedFor) AresEditLabel.reset(child)
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
     * Removes every item that matches, **including items inside home folders** (S1/R6).
     *
     * `Workspace.removeItemsByMatcher` has always had a `FolderIcon` branch that does this — it
     * just sits inside the walk over `getWorkspaceAndHotseatCellLayouts()`, and under Strategy D no
     * folder icon is ever a CellLayout child, so it has never run for one of ours. The Ares
     * carve-out beside it called straight through to [AresHomeAdapter.removeItems], which matches
     * **top-level rows only** and never looks at `FolderInfo.getContents()`.
     *
     * What that leaves behind, on every uninstall of an app that lives in a home folder — and
     * equally on `PackageUpdatedTask`, `SessionFailureTask`, `ShortcutsChangedTask` and
     * `UserLockStateChangedTask`, which all funnel here: the row is gone from the database and the
     * app is gone from the app list, but the folder still draws the icon in its preview and still
     * lists it when opened. Tapping it launches nothing. The below-two auto-collapse counts the
     * ghost, so a folder that is really down to one item never collapses.
     *
     * **It heals on a full reload**, which is exactly why it is easy to "fail to reproduce": any
     * attempt that starts with a restart re-reads the folder from the database and looks fine.
     *
     * The fix is to make stock's own branch reachable rather than to reimplement it:
     * [com.android.launcher3.folder.Folder.removeFolderContent] takes the item out of the
     * `FolderInfo`, notifies the model, drops the view, rearranges, refreshes the icon preview and
     * carries the below-two collapse. That is the identical call stock makes one branch away.
     *
     * A folder whose row is **recycled off-screen** has no inflated `FolderIcon` and therefore no
     * `Folder` to call, so its model is corrected directly. The preview is rebuilt from
     * `getContents()` when the row next binds, so nothing is stale on screen; what is given up in
     * that case is only the immediate collapse, which the next reload performs.
     */
    fun removeItems(matcher: java.util.function.Predicate<ItemInfo>): Boolean {
        // Top-level rows first, so the folder pass below reads settled adapter positions.
        var removed = aresAdapter.removeItems(matcher)

        // Collected before acting: removeFolderContent can collapse a folder, which removes its row
        // and adds the survivor, so mutating while walking positions would skip or double-visit.
        val work = mutableListOf<Pair<FolderInfo, Array<ItemInfo>>>()
        for (i in 0 until aresAdapter.itemCount) {
            val info = aresAdapter.itemAt(i) as? FolderInfo ?: continue
            val matches = info.getContents().filter { matcher.test(it) }
            if (matches.isNotEmpty()) work += info to matches.toTypedArray()
        }

        for ((info, matches) in work) {
            removed = true
            val position = aresAdapter.indexOf(info)
            val holder = if (position >= 0) findViewHolderForAdapterPosition(position) else null
            val folder = folderIconOf(holder?.itemView)?.folder
            if (folder != null) {
                // animate=false: this is a model-driven removal, not a gesture, and stock passes
                // false on the branch this mirrors.
                folder.removeFolderContent(false, *matches)
                Log.i(TAG, "removed ${matches.size} item(s) from folder ${info.id}")
            } else {
                info.getContents().removeAll(matches.toSet())
                launcher.modelWriter.notifyItemModified(info)
                Log.i(
                    TAG,
                    "folder ${info.id} is not attached; corrected its model only, " +
                        "${matches.size} item(s)",
                )
            }
        }
        return removed
    }

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
            // The §C4 drop slot is a hole. It must never be a dwell target -- otherwise hovering
            // over the gap the drag itself opened would offer to make a folder out of nothing --
            // and it must never be the tile a drop resolves onto.
            if (aresAdapter.isDropSlot(info)) continue
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
            // Pop the forming shape in with an overshoot so it reads as a folder taking shape;
            // only on the fade-IN -- an overshoot on the clear would dip the ring negative and
            // flash it back before it vanished.
            if (!clearing) interpolator = OvershootInterpolator(DROP_RING_FORM_TENSION)
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
     * True when [x],[y] — in [child]'s own coordinate space — fall in the small disc at the tile's
     * centre that always belongs to the drag, never to an affordance.
     *
     * ## The defect this closes
     *
     * The × badge and the resize chevron are each a 48dp touch target, inset 4dp into opposite
     * corners of the tile. On a **1×1** tile that is most of it: the two targets meet near the
     * middle and leave roughly 3dp of clearance at the exact centre. `editModeTouchListener`
     * correctly refuses to start a drag from a gesture that began on an affordance — so aiming at
     * the middle of a small icon, which is what anyone does to pick something up, frequently grabs
     * a control instead and the tile will not move. It is worse inside an open folder, where the
     * one badge's 48dp target on a ~83dp cell reaches the centre outright.
     *
     * ## Why a priority region rather than shrinking the targets
     *
     * The alternatives were to shrink the affordances' touch footprints on small tiles, or to inset
     * them further into the corners. Both trade away hit area at the corners, which is where the
     * user is aiming when they *do* want the control, and both would put the targets below the 44dp
     * minimum on exactly the tiles where they are hardest to hit. This takes the area back from the
     * middle instead — where neither glyph is drawn, so nothing visible is being overridden.
     *
     * Sized so it never covers a pixel of a **drawn** glyph on a home tile: the 28dp glyph inside
     * the 48dp target reaches about 10.6dp from a 1×1 tile's centre. Inside a folder the cell is
     * smaller and the × glyph's far corner does reach the centre, so there the disc clips that
     * corner — the glyph's own centre is still ~19dp away and fully tappable, and a middle-of-icon
     * touch meaning "move this" no longer removes the app.
     *
     * On a large widget the affordances are nowhere near the centre, so this changes nothing there.
     *
     * The radius lives in [AresEditMotion] with the other edit-mode feel constants, because the
     * same disc has to apply inside an open folder — the two surfaces are one mode.
     */
    private fun isInDragPriorityZone(child: View, x: Float, y: Float): Boolean =
        AresEditMotion.isInDragPriorityZone(child, x, y)

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
                        !isInDragPriorityZone(child, local[0], local[1]) &&
                            (
                                AresWidgetResize.isPointOnChevron(child, local[0], local[1]) ||
                                    AresRemoveBadge.isPointOnBadge(child, local[0], local[1]) ||
                                    AresInfoBadge.isPointOnBadge(child, local[0], local[1])
                                )
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
                        !pickUpForfeited && (enteredEditModeDuringGesture || heldLongEnough()) &&
                        dragAllowedWhileFolderOpen(downOnChild)
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
                    val downChild = downOnChild
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
                    // WP folders (owner 2026-08-24): while a folder is inline-expanded, a TAP OUTSIDE
                    // it closes it -- in edit mode AND normal mode (this listener sees the UP in both,
                    // and downChild was recorded at DOWN unconditionally). "Outside" is empty space or
                    // any tile that is NOT the open folder or one of its children; a tap ON the folder
                    // tile (which toggles) or on a child (launch, or inert in edit mode) keeps its
                    // normal behaviour. The dismiss consumes the tap, so the first tap outside just
                    // closes the folder rather than also launching an app or leaving edit mode. Gated
                    // on a stationary tap, so scrolling the grid with a folder open is unaffected.
                    val expandedFolderId = aresAdapter.expandedWpFolder()
                    if (tap && expandedFolderId != -1) {
                        // Tapping the folder's TITLE on the card renames it (owner 2026-08-24), rather
                        // than closing -- checked first, since the title band is "outside" any tile.
                        if (folderBounds.titleBandContains(e.x, e.y)) {
                            aresAdapter.promptRenameExpandedFolder()
                            return true
                        }
                        val pos = downChild?.let { getChildAdapterPosition(it) } ?: NO_POSITION
                        val info = if (pos != NO_POSITION) aresAdapter.itemAt(pos) else null
                        val onOpenFolderOrChild = info != null &&
                            (info.id == expandedFolderId || info.container == expandedFolderId)
                        if (!onOpenFolderOrChild) {
                            aresAdapter.collapseWpFolder()
                            return true
                        }
                    }
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
        // Live reflow (§4) is on for exactly the life of a drag: displaced tiles flow to their new
        // cells instead of appearing there. Outside a drag it must be off, or every scroll and
        // recycle would spring.
        masonry.reflowActive = inProgress
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
     * ## The assumption this used to rest on, and why it was wrong
     *
     * It said: *"Empty space only exists when the content doesn't fill the viewport -- in which case
     * there is nothing to scroll -- so declining it costs no scrolling behaviour."* That is false,
     * and the owner found the counter-example: *"swipping vertically on homepage doesnt work on a
     * blank space (no app icon, folder, or widget)"*.
     *
     * A **masonry** grid has empty space at any content height. A final row that does not divide by
     * the column count leaves a gap beside it; [AresPacker]'s first-fit leaves holes next to any
     * item wider than one cell; and scrolled to the bottom there is whatever the last row does not
     * fill. Declining all of it meant a page tall enough to scroll could only be scrolled by
     * starting the drag *on a tile* -- and the taller the page, the more of it was dead.
     *
     * So the decline is now conditional on there being nothing to scroll, which is what the comment
     * above always claimed it was. When the list **can** scroll it keeps the gesture, and raises the
     * workspace popup itself -- see [armEmptySpaceLongPress]. On a short page nothing changes at
     * all: the same decline, the same bubble up to Workspace, the same popup from the same code.
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

    /** Armed while a hold on scrollable empty space might still become the workspace popup. */
    private var emptySpaceLongPress: Runnable? = null

    /**
     * True once the workspace popup has claimed this gesture; everything left of it is swallowed.
     *
     * Stock's equivalent is `WorkspaceTouchListener`'s `STATE_COMPLETED`, which returns true for
     * every remaining event. This exists because the synthetic `ACTION_CANCEL` alone does **not**
     * end the gesture, which the commit that added the popup wrongly claimed it did: the cancel
     * resets `RecyclerView`'s scroll *state* to IDLE, but this view is still the parent's touch
     * target, so real MOVEs keep arriving — and since the timer only fires while the finger is
     * inside touch slop, the recorded down point is still valid, so further travel simply re-enters
     * a drag and scrolls the grid underneath the popup that just opened.
     */
    private var emptySpacePopupTook = false

    private var emptySpaceDownX = 0f
    private var emptySpaceDownY = 0f

    /** True when the content is taller than the viewport, whichever end it is currently at. */
    private fun canScrollTheGrid(): Boolean = canScrollVertically(-1) || canScrollVertically(1)

    /**
     * Arms the workspace popup for a hold that began on empty space the list is *keeping*.
     *
     * This is the other half of the [gestureStartedOnEmptySpace] change. Once the list stops
     * declining empty-space gestures on a scrollable page, `WorkspaceTouchListener` never sees them
     * -- it is an `OnTouchListener` on Workspace, and a listener only runs when no descendant
     * consumed the event -- so the wallpaper/widgets/settings popup would go with the fix, taking
     * launcher settings and the §7 widget picker with it. That trade was put to the owner, who
     * chose to keep both.
     *
     * Both is possible because the two gestures separate cleanly *after* the DOWN: a scroll crosses
     * touch slop, a long-press does not. So the list consumes the DOWN (it must, or it receives
     * nothing further and cannot scroll) and starts this timer; slop cancels it, and the timer
     * firing cancels the scroll.
     *
     * Deliberately a copy of `WorkspaceTouchListener.maybeShowMenu`'s *decisions* rather than a
     * call into it -- that method is driven by a GestureDetector fed from Workspace's own touch
     * stream, which by construction is not running here. What is copied is the part that matters
     * and would be wrong to reinvent: the same `canHandleLongPress` guard (no floating view already
     * open, launcher in NORMAL), the same haptic, and the same `Launcher.showDefaultOptions` entry
     * point, so the popup that appears is the stock one with the stock options.
     */
    private fun armEmptySpaceLongPress(e: MotionEvent) {
        cancelEmptySpaceLongPress()
        emptySpaceDownX = e.x
        emptySpaceDownY = e.y
        val armed = Runnable {
            emptySpaceLongPress = null
            fireEmptySpaceLongPress()
        }
        emptySpaceLongPress = armed
        postDelayed(armed, ViewConfiguration.getLongPressTimeout().toLong())
    }

    private fun cancelEmptySpaceLongPress() {
        emptySpaceLongPress?.let { removeCallbacks(it) }
        emptySpaceLongPress = null
    }

    private fun fireEmptySpaceLongPress() {
        // Stock's own precondition. Without it a hold behind an open folder or mid-transition pops
        // a second floating view on top of the first -- the "two floating states raised by one
        // gesture" this project already refused once, in the long-press that enters edit mode.
        if (AbstractFloatingView.getTopOpenView(launcher) != null ||
            !launcher.isInState(LauncherState.NORMAL)
        ) {
            return
        }

        // DragLayer space, not ours. `Launcher.getPopupTarget` measures the anchor against
        // `mDragLayer.getWidth()`, and this view is several parents down (ShortcutAndWidgetContainer
        // -> CellLayout -> Workspace) and carries the workspace's own scale and page translation.
        // Stock gets away with passing raw coordinates because its listener sits on Workspace, which
        // is effectively the DragLayer's own box; ours does not, so it has to be mapped.
        val point = floatArrayOf(emptySpaceDownX, emptySpaceDownY)
        launcher.dragLayer.getDescendantCoordRelativeToSelf(this, point)

        // Stock's edge-margin refusal, which the first cut dropped.
        // `WorkspaceTouchListener` declines the long press when the down point falls within
        // `edgeMarginPx` of the drag layer, because that band belongs to the gestures that START at
        // an edge -- here the §10 Pivot pan and the edge-back that §20 routes out of edit mode. A
        // hesitation at the bezel should begin a pan, not raise the wallpaper popup.
        val edge = launcher.deviceProfile.edgeMarginPx
        val dl = launcher.dragLayer
        if (point[0] < edge || point[1] < edge ||
            point[0] > dl.width - edge || point[1] > dl.height - edge
        ) {
            return
        }

        // The other half of taking the gesture, and it is not optional: stock pairs showing the
        // menu with `requestDisallowInterceptTouchEvent(true)` for a reason. Without it
        // `BaseDragLayer.onInterceptTouchEvent` keeps running on every MOVE, and
        // AresPaneSwipeController latched its decision at a DOWN taken before the popup existed --
        // so a sideways drag would pan the app-list pane with the popup still up. This file already
        // uses exactly this call, for exactly this reason, when entering edit mode.
        parent?.requestDisallowInterceptTouchEvent(true)

        performHapticFeedback(
            HapticFeedbackConstants.LONG_PRESS,
            HapticFeedbackConstants.FLAG_IGNORE_VIEW_SETTING,
        )
        launcher.showDefaultOptions(point[0], point[1])

        // Take the gesture back off RecyclerView, or the finger that is still down keeps scrolling
        // the grid underneath the popup it just opened. `super` deliberately: our own override
        // would read this as a real cancel and abandon a folder drop that is not happening.
        val now = SystemClock.uptimeMillis()
        val cancel = MotionEvent.obtain(now, now, MotionEvent.ACTION_CANCEL, 0f, 0f, 0)
        super.dispatchTouchEvent(cancel)
        cancel.recycle()

        // Re-assert, because the cancel above just cleared it. The dispatch lands in
        // editModeTouchListener.onInterceptTouchEvent's ACTION_CANCEL branch, whose whole job is to
        // let go of a gesture -- including `rv.parent?.requestDisallowInterceptTouchEvent(false)`.
        // So the claim made before showDefaultOptions survived exactly thirteen lines, and
        // BaseDragLayer's FLAG_DISALLOW_INTERCEPT was cleared with it (the call propagates up the
        // whole chain).
        //
        // The consequence is the precise failure the first claim exists to prevent, so it read as
        // fixed while still being broken: hold empty space until the popup opens, keep the finger
        // down, drag sideways, and BaseDragLayer.onInterceptTouchEvent resumes on the next MOVE.
        // AresPaneSwipeController latched mNoIntercept = false at a DOWN taken before the popup
        // existed and is not re-consulted mid-gesture, so the app-list pane pans in underneath the
        // open popup. emptySpacePopupTook does not help: it guards onTouchEvent, which runs after
        // the DragLayer has already claimed the gesture.
        //
        // Re-asserting rather than reordering, deliberately. Moving the claim below the cancel
        // would work too, but it would also move it below showDefaultOptions, leaving the
        // popup-raising window uncovered -- and that window is why stock pairs the two in the first
        // place. Two calls, both idempotent, each covering a different half of the gesture.
        parent?.requestDisallowInterceptTouchEvent(true)

        // ...and the cancel alone is NOT enough, which is the correction to this function's first
        // cut. It resets RecyclerView's scroll state, but this view is still the parent's touch
        // target and real MOVEs keep arriving; the recorded down point is still inside slop (that is
        // the only reason the timer fired), so further travel just starts a fresh drag under the
        // open popup. Swallowing the remainder is what actually ends the gesture, and it is what
        // stock's STATE_COMPLETED does.
        emptySpacePopupTook = true
    }

    /**
     * Keeps the empty-space long-press in step with the gesture.
     *
     * In `dispatchTouchEvent` rather than `onTouchEvent` because of how `ViewGroup` routes a touch
     * that no child claimed: with `mFirstTouchTarget` null, `onInterceptTouchEvent` is called on the
     * DOWN and **never again** for that gesture, so a MOVE-based cancel written there would never
     * run. `dispatchTouchEvent` sees every event unconditionally.
     */
    private fun trackEmptySpaceLongPress(ev: MotionEvent) {
        when (ev.actionMasked) {
            // A new gesture always starts clean, however the last one ended.
            MotionEvent.ACTION_DOWN -> emptySpacePopupTook = false

            MotionEvent.ACTION_MOVE -> {
                if (emptySpaceLongPress == null) return
                val slop = ViewConfiguration.get(context).scaledTouchSlop
                val dx = ev.x - emptySpaceDownX
                val dy = ev.y - emptySpaceDownY
                if (dx * dx + dy * dy > (slop * slop).toFloat()) cancelEmptySpaceLongPress()
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                cancelEmptySpaceLongPress()
                emptySpacePopupTook = false
            }
        }
    }

    /**
     * Drops a pending empty-space long-press when this view leaves the window.
     *
     * The timer is posted on the view, and nothing else takes it down: a fold, a configuration
     * change or any teardown between the finger landing and the timeout would otherwise fire it
     * against a `Launcher` that is no longer the live one.
     */
    override fun onDetachedFromWindow() {
        cancelEmptySpaceLongPress()
        emptySpacePopupTook = false
        super.onDetachedFromWindow()
    }

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
    /**
     * How the gesture that owned the CURRENT reorder drag ended. A LATCH, not a last-event
     * record: `clearView` reads this ~250ms after the release, and the user's next touch lands
     * inside that window in the ordinary rapid-rearrange rhythm — a plain last-event field was
     * measured rewriting a completed UP to "never ended", which discarded the armed folder commit
     * AND the persist (adversarial review, 2026-08-21). So: armed by
     * [AresHomeReorder.Callback.onSelectedChanged] via [beginDragGestureWatch] when a drag
     * starts, written ONCE by the first UP/CANCEL that arrives while a reorder is in progress,
     * ignored the rest of the time. Later gestures cannot rewrite the drag's own ending.
     *
     * See [GESTURE_END_NONE]/[GESTURE_END_UP]/[GESTURE_END_CANCEL] on the companion.
     */
    internal var dragGestureEnd = GESTURE_END_NONE
        private set

    /** Arms [dragGestureEnd] for a new drag. Called at drag start, before any end can arrive. */
    internal fun beginDragGestureWatch() {
        dragGestureEnd = GESTURE_END_NONE
    }

    // ------------------------------------------------------- folder-exit handoff (rows 31/32)

    private var lastHandoffX = 0f
    private var lastHandoffY = 0f

    /**
     * Feeds [AresFolderExitHandoff]'s relayed gesture into this view's ordinary dispatch, so the
     * in-grid pipeline — ItemTouchHelper, the gesture-end latch, the empty-space tracker — sees a
     * stream indistinguishable from a finger that started here. Deliberately through
     * [dispatchTouchEvent], NOT `super`: the popup's synthetic CANCEL bypasses via `super`
     * precisely so it cannot masquerade, and this relay is the opposite case — it must.
     */
    internal fun dispatchSyntheticHandoffEvent(
        action: Int,
        downTime: Long,
        eventTime: Long,
        x: Float,
        y: Float,
    ) {
        lastHandoffX = x
        lastHandoffY = y
        val ev = MotionEvent.obtain(downTime, eventTime, action, x, y, 0)
        dispatchTouchEvent(ev)
        ev.recycle()
    }

    /** Where the relay last placed the pointer, for ending a drag whose source has gone. */
    internal fun lastHandoffPoint(): FloatArray = floatArrayOf(lastHandoffX, lastHandoffY)

    /** Selects [holder] for the in-grid drag; the relayed MOVEs drive it from there. */
    internal fun startHandoffDrag(holder: androidx.recyclerview.widget.RecyclerView.ViewHolder) {
        itemTouchHelper.startDrag(holder)
    }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        val end = when (ev.actionMasked) {
            MotionEvent.ACTION_UP -> GESTURE_END_UP
            MotionEvent.ACTION_CANCEL -> GESTURE_END_CANCEL
            else -> GESTURE_END_NONE
        }
        if (end != GESTURE_END_NONE &&
            isReorderInProgress() &&
            dragGestureEnd == GESTURE_END_NONE
        ) {
            dragGestureEnd = end
        }
        // A CANCEL normally aborts the dwell -- EXCEPT while a live-create is arming. The live-create
        // arm() dispatches a synthetic UP to end the in-grid drag, but the REAL finger is still down;
        // on the fold, AresPaneSwipeController then claims that still-down finger and the framework
        // sends the grid a CANCEL. Unguarded, that CANCEL calls cancel() -> clear(), wiping liveArming
        // ~1 frame BEFORE the deferred clearView->commitDrop can build the folder, so live-create
        // never completes on the fold (owner report 2026-08-23; the N1 race in AresFolderDrop.clearTarget,
        // reliably triggered here). The synthetic UP has already latched dragGestureEnd=UP, so commitDrop
        // still runs on the real end; suppressing only this cancel() lets it see liveArming and create.
        if (ev.actionMasked == MotionEvent.ACTION_CANCEL && !AresFolderDrop.isLiveArming()) {
            AresFolderDrop.cancel()
        }
        trackEmptySpaceLongPress(ev)
        return super.dispatchTouchEvent(ev)
    }

    override fun onInterceptTouchEvent(e: MotionEvent): Boolean {
        if (e.actionMasked == MotionEvent.ACTION_DOWN) {
            val onEmptySpace = !editMode && findChildViewUnder(e.x, e.y) == null
            // Decline ONLY when there is genuinely nothing to scroll, which is what this guard
            // always claimed to be doing. See [gestureStartedOnEmptySpace].
            gestureStartedOnEmptySpace = onEmptySpace && !canScrollTheGrid()
            if (onEmptySpace && !gestureStartedOnEmptySpace) armEmptySpaceLongPress(e)
        }
        if (gestureStartedOnEmptySpace) return false
        return super.onInterceptTouchEvent(e)
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        // The popup owns the rest of this gesture. Consume rather than decline: returning false
        // would hand the remainder up to Workspace, which is no better than scrolling it here.
        if (emptySpacePopupTook) return true
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
        // The host's real window position is unknown during the first measure (it isn't laid out yet),
        // so re-measure once it settles to apply the exact edge-to-edge overscan. Extending this child
        // never moves the host, so this converges in one extra pass and then stays quiet.
        (parent as? View)?.getLocationInWindow(tmpLoc)
        if (tmpLoc[1] in 0 until Int.MAX_VALUE && tmpLoc[1] != measuredHostWindowTop) {
            requestLayout()
        }
        // Widget providers can only be told their box once the layout manager has assigned one.
        aresAdapter.reportPendingWidgetSizes()
        // And the label lift is measured from the cell, so it is stale for exactly the same reason:
        // a row bound or attached mid-mode has no height yet, and folding moves every cell's centre.
        reassertLabelLift()
    }

    /**
     * Re-measures the edit-mode label lift on every attached row.
     *
     * Called after layout and after each scroll — the two moments a row can acquire, or change, the
     * height the lift is computed from. Both run before the frame is drawn, so a row that took a
     * lift of zero at attach is corrected without ever being seen off-centre.
     *
     * Cheap by construction: it early-outs when not editing, and [AresEditLabel.reassert] writes
     * nothing unless the measured value has actually moved.
     */
    private fun reassertLabelLift() {
        if (!editMode) return
        for (i in 0 until childCount) {
            AresEditLabel.reassert(getChildAt(i))
        }
    }

    /**
     * Re-asserts the edit-mode label treatment on every attached row, from outside this view.
     *
     * The one caller is `AresFolderEdit`, when a folder closes. `Folder.closeComplete` runs
     * `mFolderIcon.mFolderName.setTextVisibility(true)` on the way out, which un-hides that one
     * tile's caption while the rest of the grid stays bare — and nothing in the normal funnel
     * covers it, because the row is neither detached nor rebound and the mode walk does not re-run.
     *
     * **It must be POSTED by the caller, not called inline.** `closeComplete` removes the folder
     * from the DragLayer *first* (which is what tells `AresFolderEdit` to stop) and sets the text
     * visible several lines later, so a synchronous re-assert would run before the write it is
     * meant to undo and be silently overwritten.
     *
     * Safe on the mode-ending path: [reassertLabelLift] early-outs when not editing, so a folder
     * closing *because* edit mode ended does nothing here and the ordinary restore stands.
     */
    fun reassertLabels() = reassertLabelLift()

    override fun onScrolled(dx: Int, dy: Int) {
        super.onScrolled(dx, dy)
        // A scroll attaches and lays out rows without a layout pass (AresMasonryLayoutManager.fill
        // is driven straight from scrollVerticallyBy), so onLayout above never sees them.
        reassertLabelLift()
    }

    // Current edge-to-edge overscan (the host's window offsets baked into bounds + padding). Tracked
    // so the padding is only rewritten when they actually change; see onMeasure.
    private var overscanTop = 0
    private var overscanBottom = 0
    // The host window-top used by the last measure. The host isn't laid out during the first measure,
    // so onLayout re-measures once it settles (it never moves afterwards) -- see onLayout.
    private var measuredHostWindowTop = -1
    private val tmpLoc = IntArray(2)

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

        // Keep the host ShortcutAndWidgetContainer from clipping our edge-to-edge overscan. Its
        // onAttachedToWindow re-enables clipChildren/clipToPadding whenever the allow-widget-overlap
        // pref is off (the default), which would clip the extended list back to the inset content
        // rect [top=statusInset, bottom=navInset] -- exactly what we're extending past so scrolled
        // rows can flow behind the transparent bars. Our cells never overflow their own bounds, so
        // unclipping the host only frees the overscan region; it changes nothing else on the page.
        host?.let {
            it.clipChildren = false
            it.clipToPadding = false
            it.clipToOutline = false
        }
        // The Workspace (a PagedView) draws with clipChildren=true, which clips each page (the
        // CellLayout) to the PAGE's own bounds -- and the page is inset to [statusInset .. windowH -
        // navInset]. That is a DRAW-TIME clip (it isn't a property on any view between here and the
        // page), so it re-clips our extended list at the status-bar line even though every view in
        // the chain is unclipped. Free the pager so the page can overflow to the window edges. This
        // fork flattens the desktop into a single page (Strategy D), so there is no adjacent page to
        // bleed into. The app list avoids this entirely by living in the DragLayer, above the pager.
        (host?.parent as? ViewGroup)?.clipChildren = false   // CellLayout (already false; belt-and-braces)
        ((host?.parent as? ViewGroup)?.parent as? ViewGroup)?.let { workspace ->
            workspace.clipChildren = false
            workspace.clipToPadding = false
        }

        // Start below the pinned smartspace rather than on top of it (it occupies grid row 0 of the
        // same container, so a full-bleed list would overlap row 1).
        val headerTop = pinnedHeaderBottom(host)

        // Edge-to-edge overscan (owner, 2026-08-22): let scrolled rows flow *behind* the transparent
        // status and nav bars instead of clipping at the workspace's inset content rect. The cell
        // hierarchy is unclipped (CellLayout is clipChildren=false; we unclip the host S&W above) and
        // the Workspace fills the whole window, so extending this list to span the full window renders
        // it edge-to-edge -- no ancestor change.
        //
        // We size the overscan from the host's REAL window position, not from the device insets: the
        // host sits `statusInset + workspaceTopPadding` below the window top, so an inset-only extend
        // stops short of the physical edge. getLocationInWindow is ground truth and reaches y=0 exactly.
        //
        // The SAME amounts are added to top/bottom padding below, so the resting first/last row, the
        // masonry maxScroll, and the edit-mode availH -- all computed as height - padTop - padBottom --
        // are unchanged; only the overscan region (empty scroll room behind the bars) is new. We do
        // NOT extend up when a pinned smartspace occupies the top region (headerTop > 0), or rows would
        // slide behind the at-a-glance rather than behind the status bar.
        host?.getLocationInWindow(tmpLoc)
        val hostWindowTop = tmpLoc[1].coerceAtLeast(0)
        measuredHostWindowTop = hostWindowTop
        val windowHeight = launcher.dragLayer.height.takeIf { it > 0 } ?: (hostWindowTop + hostHeight)
        val ovTop = if (headerTop > 0) 0 else hostWindowTop
        val ovBottom = (windowHeight - (hostWindowTop + hostHeight)).coerceAtLeast(0)
        if (ovTop != overscanTop || ovBottom != overscanBottom) {
            overscanTop = ovTop
            overscanBottom = ovBottom
            // Through super: our setPadding() is a deliberate no-op (see below). Runs only when the
            // overscan actually changes (first settle, or a fold), so the extra requestLayout is rare.
            super.setPadding(
                0,
                AresAllApps.homeListTopPaddingPx(context) + ovTop,
                0,
                AresAllApps.ergoBottomPaddingPx(context) + ovBottom,
            )
        }

        val top = headerTop - ovTop
        val height = (hostHeight - headerTop + ovTop + ovBottom).coerceAtLeast(0)

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

    companion object {
        /**
         * True if [container] hosts the edge-to-edge home list. The list draws its content beyond the
         * container's bounds -- behind the transparent system bars (see onMeasure's overscan). A
         * hardware layer is sized to the view's bounds and would clip that overflow, so the workspace
         * must not layer this container during transitions ([CellLayout.enableHardwareLayer]); doing so
         * momentarily re-clips home content at the status-bar line while sliding to/from the app list.
         * Strategy D uses a single page, so skipping its layer costs one live-rendered page.
         */
        @JvmStatic
        fun hostsEdgeToEdgeList(container: ViewGroup): Boolean {
            for (i in 0 until container.childCount) {
                if (container.getChildAt(i) is AresHomeListView) return true
            }
            return false
        }

        /**
         * How the most recent gesture on this list ended. [AresHomeReorder]'s `clearView` reads
         * this to tell a real drop from everything else, because `ItemTouchHelper` calls
         * `clearView` for all of them identically (S3):
         *
         *  - [GESTURE_END_UP] -- the user released. The only ending that may commit into a folder
         *    (spec B3: only a manual release adds an item) and the only one that reflects intent.
         *  - [GESTURE_END_CANCEL] -- the system took the gesture. Nothing was released, so nothing
         *    is committed; the adapter's moves already happened and are on screen, so the order IS
         *    persisted.
         *  - [GESTURE_END_NONE] -- no end event at all: the dragged holder was DETACHED mid-drag
         *    (a rebind, a model update retiring it). `clearView` fires from RecyclerView's
         *    child-detach path with the finger still down, and before this existed an armed dwell
         *    then filed the item into a folder with no release having happened. Nothing is
         *    committed and nothing is persisted.
         *
         * Maintained by [dispatchTouchEvent], which sees every event unconditionally. The
         * empty-space popup's synthetic CANCEL deliberately bypasses it by dispatching through
         * `super`, so it cannot masquerade as a real ending here either.
         *
         * The record is a per-drag LATCH ([dragGestureEnd]): the first ending that arrives while
         * a reorder is in progress wins, and later touches cannot rewrite it. The first version
         * was a last-event field reset on every DOWN, and its "known, accepted imprecision" was
         * neither known fully nor acceptable: a tap landing inside the ~250ms settle window --
         * the ordinary rapid-rearrange rhythm -- rewrote a completed UP to NONE, which discarded
         * an armed folder commit outright and skipped the persist (adversarial review,
         * 2026-08-21).
         */
        const val GESTURE_END_NONE = 0
        const val GESTURE_END_UP = 1
        const val GESTURE_END_CANCEL = 2

        const val TAG = "AresHomeGrid"

        /** Slight shrink signalling edit mode, mirroring the Windows Phone Start cue. */
        const val EDIT_MODE_SCALE = 0.92f

        // Open-folder focus wash. Feel is owner-tunable (owner 2026-08-25, "dimmed colour wash").
        const val WASH_MAX = 0.85f      // target strength at full open
        const val WASH_SAT_DROP = 0.35f // desaturation at full strength (0 = keep colour, 1 = grey)
        const val WASH_K = 0.55f        // brightness of the wash: lower dims more
        const val WASH_MS = 220L        // fade in/out duration

        /** Matches the edit-mode enter/exit scale animation. */
        const val EDIT_SCALE_MS = 120L
        const val EDIT_CHROME_FADE_MS = 150L // fade the ×/ⓘ/tint back after a folder icon lands (edit)

        // Start the child falls AFTER the icon morph (220ms) and the reflow (LAYOUT_ANIM_MS 200ms)
        // have both settled, so they fall into an already-cleared gap and never cross a moving tile
        // (owner 2026-08-24).
        const val WP_CHILD_ENTER_DELAY_MS = 240L

        // ---- "fall out of the teardrop" open/close motion (owner 2026-08-24) ----
        // Each icon appears small at the teardrop tip, falls a short way into the card under gravity
        // (segment 1), then spreads + enlarges to its cell on an emphasized-decelerate settle
        // (segment 2). WP_FALL_SEG splits the two segments of one child's timeline.
        const val WP_FALL_MS = 650L // one child, open (drop+spread; owner: slightly slower)
        const val WP_FALL_STAGGER_MS = 54L // gap between successive children streaming out
        // Close mirrors the open (owner 2026-08-24 "apply the same principles"): a clean FORWARD-time
        // animation (NOT a time-reverse of the open, which flipped the settle overshoot into an
        // outward lurch). One child: curve in from its cell to the drop point, then rise into the
        // folder while shrinking + fading. A bit quicker than the 650ms open, but the same family.
        const val WP_FALL_CLOSE_MS = 520L
        const val WP_FALL_CLOSE_STAGGER_MS = 34L
        const val WP_CLOSE_SEG = 0.56f // fraction of the close that is the curve-IN (rest = rise into folder)
        /** Phase 1 (leave the cell, gather toward the drop point) accelerates. */
        val WP_CLOSE_IN_INTERP: android.view.animation.Interpolator =
            android.view.animation.AccelerateInterpolator(1.1f)
        /** Phase 2 (rise into the folder + shrink + fade) accelerates -- the folder draws the icon in. */
        val WP_CLOSE_RISE_INTERP: android.view.animation.Interpolator =
            android.view.animation.AccelerateInterpolator(1.7f)
        const val WP_FALL_SEG = 0.44f // fraction of the timeline that is the fall (rest is the spread)
        const val WP_FALL_DROP_PX = 55f // dp the icon falls below the tip before spreading (owner tune)
        const val WP_FALL_TIP_SCALE = 0.3f // fallback start size (child past the 4 preview slots)
        // The mid-fall (drop-point) size is now a FRACTION of the way from the child's own start scale
        // toward full, so a tile that starts at its exact preview-slot size grows monotonically to 1.
        const val WP_FALL_DROP_FRAC = 0.45f
        const val WP_FALL_TILT_DEG = 12f // how far a falling icon tips over; alternates sign per index
        const val WP_TEXT_FADE_MS = 130L // label derender/rerender as an icon lifts off / lands
        /** Gravity feel for the fall segment (drives scale + alpha; y uses the bounce below). */
        val WP_FALL_FALL_INTERP: android.view.animation.Interpolator =
            android.view.animation.AccelerateInterpolator(1.5f)
        // ---- per-child variation (owner 2026-08-24, "less unified ... natural, fluid, bouncy") ----
        // Each child's drop depth, arc bow, pace and settle spring are jittered off its index (wpRnd),
        // so the fan looks organic. The settle OvershootInterpolator is built per child from the tension
        // range below (replaces the old single shared WP_FALL_SPREAD_INTERP).
        const val WP_FALL_DROP_JITTER = 0.55f // +/- fraction on each child's drop depth (owner: less uniform)
        const val WP_FALL_DUR_JITTER = 0.13f // +/- fraction on each child's OPEN duration (pace)
        const val WP_FALL_BOW = 0.45f // how far the arc control can pull inward (0 = straight rise)
        const val WP_FALL_BOW_LIFT_MIN = 0.06f // min downward bow of the arc control (x drop height)
        const val WP_FALL_BOW_LIFT_SPAN = 0.28f // added range of the downward bow
        const val WP_FALL_OVERSHOOT_MIN = 1.7f // settle spring tension, soft end (more weight)
        const val WP_FALL_OVERSHOOT_SPAN = 1.4f // added range -> up to ~3.1 (harder, bouncier plop)

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

        /**
         * The size a newly created folder's tile grows *from*, as a fraction of its resting scale.
         *
         * Small enough that the growth is unmistakable, not so small that the icon is a dot for
         * half the animation. Relative, not absolute, so it reads the same in edit mode (where
         * every tile rests at 0.92) as outside it.
         */
        const val FOLDER_CREATED_FROM = 0.55f

        /** Overshoot on the arrival pop. Enough to feel like a landing, short of a bounce. */
        const val FOLDER_CREATED_TENSION = 2.0f

        /**
         * Overshoot on the drop-ring's forming pop as the dwell arms. Matched to
         * [FOLDER_CREATED_TENSION] so the shape that grows under the finger and the tile that pops
         * in on release are the same gesture.
         */
        const val DROP_RING_FORM_TENSION = 2.0f

        /**
         * How long the arrival pop runs.
         *
         * Longer than the edit-mode scale (120ms), because this is announcing that something
         * *happened* rather than that a mode changed, and it has to be seen. Still well under the
         * quarter second where a launcher starts to feel slow.
         */
        const val FOLDER_CREATED_MS = 220L

        /**
         * How long the arrival pop stays armed waiting for its tile to attach.
         *
         * Generous against a slow bind, short enough that a folder created off-screen does not pop
         * when the user eventually scrolls to it and wonders what just changed.
         */
        const val CREATED_PENDING_MS = 1000L
    }
}
