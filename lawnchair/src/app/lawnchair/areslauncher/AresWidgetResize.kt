package app.lawnchair.areslauncher

import android.appwidget.AppWidgetProviderInfo
import android.graphics.Rect
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import com.android.launcher3.Launcher
import com.android.launcher3.R
import com.android.launcher3.model.data.ItemInfo
import com.android.launcher3.model.data.LauncherAppWidgetInfo
import com.android.launcher3.widget.WidgetManagerHelper

/**
 * Widget resize for the masonry home grid: **edge-dragging** (§3).
 *
 * In edit mode a resizable widget shows a handle in its bottom-end corner. Dragging it changes the
 * footprint continuously, quantised to whole cells, and the grid **repacks live** as each boundary
 * is crossed — consistent with drag-to-move, and the owner's explicit feel decision. Release
 * commits; abandoning restores the original footprint.
 *
 * ## This replaced tap-to-cycle, and the old rationale is retained only as history
 *
 * The shipped affordance was a chevron that advanced through the provider's allowed footprints,
 * Windows Phone style. This file's header used to assert that cycling "is not a simplification of a
 * richer design — it is the whole design", and a panel review found that sentence being cited as
 * authority for keeping it. The owner reversed the model:
 *
 * > *"instead of the widget resize being tap with auto resize like windows phone, I think I'd
 * > prefer to manually resize the widget with a drag function. I think android widgets are just too
 * > inconsistent for the automated resizing approach."*
 *
 * [allowedSizes] survives the change with a different job: it enumerated the cycle, and now supplies
 * the **clamps** a drag is bounded by.
 *
 * ## What does NOT change: stock's resize frame is still unusable here
 *
 * `AppWidgetResizeFrame` was runtime-refuted for our hosting and that refutation still binds. It
 * casts unconditionally to `CellLayoutLayoutParams` in `setupForWidget` and throws *before*
 * `dl.addView(frame)`, so the frame is never added and nothing renders — under Strategy D our
 * widgets are RecyclerView rows, not `CellLayout` children. Do not attempt to call it. Its
 * `createAreaForResize` would also have nothing to do here: it validates a footprint against grid
 * occupancy, and packing cannot fail for want of space — items simply reflow.
 *
 * Everything a later pass is likely to want to adjust — the clamps, the quantisation, and how the
 * handle looks and sits — is in this one file.
 */
object AresWidgetResize {

    /** Tag on the chevron view, so the host can hit-test it without a resource id. */
    const val CHEVRON_TAG = "ares_widget_resize_chevron"

    /**
     * The footprints this widget may take, in ascending order, or empty if it cannot be resized.
     *
     * Cell spans are read from [com.android.launcher3.widget.LauncherAppWidgetProviderInfo], which
     * has **already converted the provider's dp declarations into grid cells** (`minSpanX`,
     * `maxSpanX`, …) against the current device profile. Doing that conversion here would duplicate
     * logic that has to agree with the rest of Launcher3 and would drift from it.
     *
     * `resizeMode` gates each axis independently, so a vertically-resizable-only widget keeps its
     * declared width at every step. A widget declaring neither axis returns empty and gets no
     * chevron at all — an affordance that does nothing is worse than no affordance.
     *
     * Sizes are clamped to the grid's [columns]; a widget cannot be offered a width the device
     * cannot show. The packer clamps too, but offering an unreachable size would make the cycle
     * appear to skip.
     */
    fun allowedSizes(launcher: Launcher, info: ItemInfo, columns: Int): List<AresPacker.Span> {
        val widgetInfo = info as? LauncherAppWidgetInfo ?: return emptyList()
        val provider = runCatching {
            WidgetManagerHelper(launcher)
                .getLauncherAppWidgetInfo(widgetInfo.appWidgetId, widgetInfo.targetComponent)
        }.getOrNull() ?: return emptyList()

        val mode = provider.resizeMode
        val horizontal = (mode and AppWidgetProviderInfo.RESIZE_HORIZONTAL) != 0
        val vertical = (mode and AppWidgetProviderInfo.RESIZE_VERTICAL) != 0
        if (!horizontal && !vertical) return emptyList()

        val maxColumns = columns.coerceAtLeast(1)

        // Fall back to the widget's current span when the provider left a bound unset (0), which is
        // common for widgets that only declare one axis as resizable.
        val minW = if (horizontal) provider.minSpanX.coerceAtLeast(1) else widgetInfo.spanX
        val maxW = if (horizontal) provider.maxSpanX.let { if (it > 0) it else maxColumns } else widgetInfo.spanX
        val minH = if (vertical) provider.minSpanY.coerceAtLeast(1) else widgetInfo.spanY
        val maxH = if (vertical) provider.maxSpanY.let { if (it > 0) it else MAX_ROWS } else widgetInfo.spanY

        val wRange = minW.coerceAtLeast(1).coerceAtMost(maxColumns)..maxW.coerceAtLeast(1).coerceAtMost(maxColumns)
        val hRange = minH.coerceAtLeast(1)..maxH.coerceAtLeast(1).coerceAtMost(MAX_ROWS)
        if (wRange.isEmpty() || hRange.isEmpty()) return emptyList()

        val sizes = ArrayList<AresPacker.Span>()
        for (h in hRange) {
            for (w in wRange) {
                sizes.add(AresPacker.Span(w, h))
            }
        }

        // Cycle order: ascending by area, then by width, so each tap is a visible step up in size
        // and the sequence is the same every time. A single allowed size means nothing to cycle
        // through, so treat it as not resizable.
        sizes.sortWith(compareBy({ it.w * it.h }, { it.w }))
        return if (sizes.size <= 1) emptyList() else sizes
    }

    /**
     * The footprint after this tap: the next larger allowed size, wrapping to the smallest.
     *
     * Wrapping is what makes a single affordance sufficient — growing past the maximum returns to
     * the minimum, so every size stays reachable without a second control or a long-press variant.
     * A current size outside the allowed set (a provider whose declarations changed under a
     * persisted widget) starts the cycle from the smallest rather than getting stuck.
     */
    fun nextSize(current: AresPacker.Span, allowed: List<AresPacker.Span>): AresPacker.Span {
        if (allowed.isEmpty()) return current
        val index = allowed.indexOfFirst { it.w == current.w && it.h == current.h }
        return if (index < 0) allowed.first() else allowed[(index + 1) % allowed.size]
    }

    /** Where a resize drag is in its lifecycle. */
    enum class Phase { BEGIN, MOVE, END, CANCEL }

    /**
     * Builds the resize **drag handle** for a widget cell (§3).
     *
     * The view is deliberately dumb: it reports the drag's displacement from its own DOWN point and
     * nothing else. Quantising to cells, clamping to the provider's allowed spans and repacking are
     * the host's business, because only the host has the grid metrics and the adapter — and keeping
     * the arithmetic there rather than in a view is what lets it be reasoned about in one place.
     *
     * Displacement is accumulated in **raw screen coordinates** rather than from `event.getX()`.
     * The handle is a child of the holder container, edit mode scales that container, and the live
     * repack moves it mid-gesture — so a local coordinate is measured against a frame that is
     * itself moving, and the drag would fight its own feedback. `getRawX/Y` is the only frame that
     * stays still while the thing being dragged changes size underneath it.
     *
     * Returns true from every event after DOWN. A handle that let go of the gesture would hand the
     * rest of the drag to the tile underneath, which starts a reorder.
     */
    fun createHandle(
        container: FrameLayout,
        label: CharSequence?,
        onDrag: (dxPx: Float, dyPx: Float, phase: Phase) -> Unit,
    ): View {
        val res = container.resources
        val touch = res.getDimensionPixelSize(R.dimen.ares_widget_resize_touch_size)
        val margin = res.getDimensionPixelSize(R.dimen.ares_widget_resize_margin)

        return ImageView(container.context).apply {
            tag = CHEVRON_TAG
            // A corner HOOK, not a chevron on a filled circle. The old treatment read as a button,
            // which is now the wrong promise -- this is dragged, not tapped. "right now it looks
            // like a buttom. Maybe a corner hook would be better? ... like a curved L traciong the
            // boarder of the corner of the square".
            //
            // FIT_END pins the drawing to the view's bottom-end corner, so the L traces the tile's
            // corner rather than floating in the middle of its own touch target. The 48dp target is
            // untouched -- only what is painted moved, exactly as the x and ! badges do it.
            setImageResource(R.drawable.ares_widget_resize_hook)
            scaleType = ImageView.ScaleType.FIT_END
            isClickable = true
            isFocusable = true
            contentDescription = if (label.isNullOrBlank()) {
                res.getString(R.string.action_resize)
            } else {
                res.getString(R.string.ares_resize_item, label)
            }
            AresA11y.describeAsButton(this)

            var downX = 0f
            var downY = 0f
            setOnTouchListener { v, ev ->
                when (ev.actionMasked) {
                    android.view.MotionEvent.ACTION_DOWN -> {
                        downX = ev.rawX
                        downY = ev.rawY
                        // The RecyclerView and the folder/drag controllers above it would otherwise
                        // claim this gesture the moment it looks like a scroll or a reorder.
                        v.parent?.requestDisallowInterceptTouchEvent(true)
                        onDrag(0f, 0f, Phase.BEGIN)
                        true
                    }
                    android.view.MotionEvent.ACTION_MOVE -> {
                        onDrag(ev.rawX - downX, ev.rawY - downY, Phase.MOVE)
                        true
                    }
                    android.view.MotionEvent.ACTION_UP -> {
                        v.parent?.requestDisallowInterceptTouchEvent(false)
                        onDrag(ev.rawX - downX, ev.rawY - downY, Phase.END)
                        true
                    }
                    android.view.MotionEvent.ACTION_CANCEL -> {
                        v.parent?.requestDisallowInterceptTouchEvent(false)
                        onDrag(ev.rawX - downX, ev.rawY - downY, Phase.CANCEL)
                        true
                    }
                    else -> false
                }
            }
            layoutParams = FrameLayout.LayoutParams(touch, touch).apply {
                gravity = Gravity.BOTTOM or Gravity.END
                setMargins(margin, margin, margin, margin)
            }
        }
    }

    /**
     * The footprint a drag of [dxPx],[dyPx] from [from] lands on, quantised and clamped.
     *
     * Pure arithmetic, separated from the view so it can be reasoned about (and, unlike the gesture,
     * checked without a device). Rounding rather than truncating means a boundary is crossed when
     * the handle passes the **halfway** point of a cell, which is what makes the grid follow the
     * finger instead of trailing a whole cell behind it.
     *
     * The clamps come from [allowedSizes], so an axis the provider declared non-resizable cannot
     * move: its min and max are both the widget's current span.
     */
    fun spanForDrag(
        from: AresPacker.Span,
        dxPx: Float,
        dyPx: Float,
        cellW: Int,
        cellH: Int,
        allowed: List<AresPacker.Span>,
    ): AresPacker.Span {
        if (allowed.isEmpty() || cellW <= 0 || cellH <= 0) return from
        val minW = allowed.minOf { it.w }
        val maxW = allowed.maxOf { it.w }
        val minH = allowed.minOf { it.h }
        val maxH = allowed.maxOf { it.h }
        val w = (from.w + Math.round(dxPx / cellW)).coerceIn(minW, maxW)
        val h = (from.h + Math.round(dyPx / cellH)).coerceIn(minH, maxH)
        return AresPacker.Span(w, h)
    }

    /**
     * Builds the chevron overlay for a widget cell.
     *
     * Sits in the bottom-end corner, inside the widget's own bounds — the grid is packed with no
     * gaps, so an affordance hanging outside the cell would overlap a neighbour.
     *
     * The glyph is [R.dimen.ares_widget_resize_chevron_size] but the view is
     * [R.dimen.ares_widget_resize_touch_size], padded out to a comfortable target. A 28dp tap
     * target would fail the 44dp minimum; growing the glyph instead would cover the widget it is
     * meant to sit on.
     */
    fun createChevron(container: FrameLayout, label: CharSequence?, onTap: () -> Unit): View {
        val res = container.resources
        val touch = res.getDimensionPixelSize(R.dimen.ares_widget_resize_touch_size)
        val glyph = res.getDimensionPixelSize(R.dimen.ares_widget_resize_chevron_size)
        val margin = res.getDimensionPixelSize(R.dimen.ares_widget_resize_margin)
        val inset = ((touch - glyph) / 2).coerceAtLeast(0)

        return ImageView(container.context).apply {
            tag = CHEVRON_TAG
            setImageResource(R.drawable.ares_widget_resize_chevron)
            setPadding(inset, inset, inset, inset)
            // Its own background so it reads against whatever the widget draws underneath.
            setBackgroundResource(R.drawable.ares_widget_resize_background)
            isClickable = true
            isFocusable = true
            // Named, not bare: "Resize" alone gives a screen-reader user no way to tell which of
            // several widgets a control belongs to. See AresRemoveBadge.createBadge.
            contentDescription = if (label.isNullOrBlank()) {
                res.getString(R.string.action_resize)
            } else {
                res.getString(R.string.ares_resize_item, label)
            }
            AresA11y.describeAsButton(this)
            setOnClickListener { onTap() }
            layoutParams = FrameLayout.LayoutParams(touch, touch).apply {
                gravity = Gravity.BOTTOM or Gravity.END
                setMargins(margin, margin, margin, margin)
            }
        }
    }

    /**
     * True when [x],[y] fall on the chevron.
     *
     * The host's edit-mode touch listener consumes taps on items so tiles stay inert, which would
     * otherwise swallow the chevron's click before it fired. It asks this first and declines to
     * consume when the answer is yes.
     *
     * **[x],[y] must be in [container]'s own coordinate space, mapped through the container's
     * transform** — `AresHomeListView.toChildLocal` is the only correct way to produce them.
     * Subtracting `container.left` alone is not: edit mode scales the container, so an
     * untransformed point disagrees with the framework's own dispatch, and this function then
     * answers a question about a chevron that is not where the finger went. See that function for
     * what that cost.
     */
    fun isPointOnChevron(container: View, x: Float, y: Float): Boolean {
        val chevron = container.findViewWithTag<View>(CHEVRON_TAG) ?: return false
        if (chevron.visibility != View.VISIBLE) return false
        val bounds = Rect()
        chevron.getHitRect(bounds)
        return bounds.contains(x.toInt(), y.toInt())
    }

    /**
     * Applies and persists a widget's new footprint, or reports that it cannot be placed.
     *
     * ## Why this re-places the item instead of only writing the spans
     *
     * Our grid derives position from `rank` alone and never reads cellX/cellY — but the **loader
     * still validates them**, and deletes anything that fails. Growing a span in place leaves the
     * stored coordinate untouched and so silently breaks the loader's bounds rule
     * (`cellX + spanX <= numColumns`). Verified the hard way: resizing a widget at `cell(2,1)` to
     * `span(4,1)` produced, on the next cold start,
     *
     * ```
     * E LoaderCursor: ... into cell (0-0:2,1) out of screen bounds ( 4x6)
     * E LoaderCursor: Item position overlap
     * D DatabaseHelper: Deleting widget not found in db: appWidgetId=25
     * ```
     *
     * — the widget was gone after a reboot. Note Lawnchair's `allowWidgetOverlap` preference does
     * *not* cover this: it only softens the overlap branch, not the bounds branch.
     *
     * So a resize has to reallocate a legal cell for the new footprint, exactly as an add does —
     * [AresWidgetAdd.findFreeCell] already replicates the loader's rules, so it is reused rather
     * than re-derived, with `excludeId` keeping the item from colliding with its own old footprint.
     * The coordinates written are still pure bookkeeping; they simply have to be *valid*
     * bookkeeping. See the ⛔ banner in design/component-verification-3.md §2.
     *
     * ## Atomicity
     *
     * Nothing is mutated unless a cell is found, so a footprint that cannot be placed leaves the
     * item exactly as it was and the caller can simply skip it — no revert path, and the view and
     * the database can never disagree.
     *
     * `updateItemInDatabase` writes the item's *current* fields through `ItemInfo.onAddToDatabase`
     * → `writeToValues`, which covers SCREEN/CELLX/CELLY as well as SPANX/SPANY, so mutating the
     * info and re-writing is sufficient. **Never `moveItemInDatabase(..., 0, 0)`**: that collides
     * every row at cell (0,0) and the loader discards the entire desktop on the next boot.
     *
     * The writer is fetched here rather than cached: one obtained before the first load completes
     * carries the sentinel `mLoadId = -1`, and every write through it is **silently discarded** —
     * no exception, just a debug log, so data loss looks exactly like success. A resize is
     * user-initiated long after the first load so it is not in that window, but the write is logged
     * anyway so that if it ever is, it is visible rather than mysterious. See
     * design/model-persistence.md.
     *
     * @return false if nothing was changed, either because the item is ineligible or because the
     *   new footprint does not fit anywhere in the stored coordinate space.
     */
    fun persistSize(launcher: Launcher, info: ItemInfo, span: AresPacker.Span): Boolean {
        if (info.container != com.android.launcher3.LauncherSettings.Favorites.CONTAINER_DESKTOP) {
            android.util.Log.w(
                TAG,
                "skipping resize write for non-desktop item id=${info.id} container=${info.container}",
            )
            return false
        }
        // ItemInfo.onAddToDatabase throws on the extra-empty-screen sentinels, which would take the
        // write pass down with it.
        val screen = info.screenId
        if (screen == com.android.launcher3.WorkspaceLayoutManager.EXTRA_EMPTY_SCREEN_ID ||
            screen == com.android.launcher3.WorkspaceLayoutManager.EXTRA_EMPTY_SCREEN_SECOND_ID
        ) {
            android.util.Log.e(TAG, "skipping resize write for item id=${info.id} on sentinel screen $screen")
            return false
        }

        val cell = IntArray(2)
        val targetScreen = AresWidgetAdd.findFreeCell(launcher, span.w, span.h, cell, info.id)
        if (targetScreen == AresWidgetAdd.NO_SCREEN) {
            android.util.Log.w(
                TAG,
                "no legal cell for ${span.w}x${span.h}; leaving id=${info.id} at " +
                    "${info.spanX}x${info.spanY}",
            )
            return false
        }

        info.spanX = span.w
        info.spanY = span.h
        info.screenId = targetScreen
        info.cellX = cell[0]
        info.cellY = cell[1]

        launcher.modelWriter.updateItemInDatabase(info)
        android.util.Log.i(
            TAG,
            "persistSize: id=${info.id} -> ${span.w}x${span.h} " +
                "at screen=$targetScreen cell=(${cell[0]},${cell[1]})",
        )
        return true
    }

    private const val TAG = "AresWidgetResize"

    /**
     * Upper bound on rows offered for a widget with no declared maximum height.
     *
     * The grid scrolls, so there is no natural ceiling the way a fixed page provides one. Without a
     * cap, a provider declaring an unbounded height would offer a cycle long enough to feel broken.
     */
    private const val MAX_ROWS = 4
}
