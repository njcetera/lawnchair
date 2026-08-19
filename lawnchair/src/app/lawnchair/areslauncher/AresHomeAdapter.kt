package app.lawnchair.areslauncher

import android.appwidget.AppWidgetHostView
import android.os.Bundle
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.android.launcher3.BubbleTextView
import com.android.launcher3.Launcher
import com.android.launcher3.LauncherSettings.Favorites
import com.android.launcher3.R
import com.android.launcher3.folder.FolderIcon
import com.android.launcher3.model.data.ItemInfo
import java.util.function.Predicate

/**
 * Adapter for AresLauncher's vertical home list (Strategy D: see
 * design/vertical-home-strategies.md and design/architecture-reassessment.md).
 *
 * Data-backed: rows hold [ItemInfo] and inflate their view on bind via
 * [Launcher.getItemInflater], the same API ModelCallbacks.bindItems uses. That keeps Launcher3's
 * whole view-construction pipeline -- icon loading, icon-pack theming, badges, click and focus
 * listeners -- without reimplementing any of it.
 *
 * An earlier revision stored already-inflated Views instead. That retained every item view for the
 * life of the list (only the containers recycled) and detached LauncherAppWidgetHostViews mid
 * scroll, which is hostile to widget sizing and RemoteViews state. See
 * design/architecture-reassessment.md §4.
 */
class AresHomeAdapter(private val launcher: Launcher) :
    RecyclerView.Adapter<AresHomeAdapter.ViewHolder>() {

    private val items = mutableListOf<ItemInfo>()

    /**
     * Width of the list we are attached to, for reporting real row size to widget providers.
     * Zero until attached; [reportRowSizeToProvider] falls back to the device profile then.
     */
    private var recyclerViewWidth: Int = 0

    override fun onAttachedToRecyclerView(recyclerView: RecyclerView) {
        super.onAttachedToRecyclerView(recyclerView)
        recyclerViewWidth = recyclerView.width
        // The list is typically unmeasured at attach time, so pick the width up on first layout.
        recyclerView.addOnLayoutChangeListener { v, _, _, _, _, _, _, _, _ ->
            recyclerViewWidth = v.width
        }
    }

    init {
        // ItemInfo.id is the model's stable primary key, so holders survive rebinds correctly.
        setHasStableIds(true)
    }

    /**
     * Inserts an item at its `rank` position rather than appending.
     *
     * The model does not hand items over in rank order: icons and widgets arrive in **separate
     * bind batches**, so appending renders every widget after every icon regardless of where the
     * user put it. Observed directly — a widget saved at rank 2 came back rendered last after a
     * reboot, while the icons around it were correctly ordered.
     *
     * Insertion is stable for equal ranks, which matters on a fresh profile where every row is
     * still `rank = 0`: those keep model delivery order until the user reorders something.
     */
    fun addItem(info: ItemInfo) {
        var index = items.size
        for (i in items.indices) {
            if (items[i].rank > info.rank) {
                index = i
                break
            }
        }
        items.add(index, info)
        notifyItemInserted(index)
    }

    fun clear() {
        val size = items.size
        if (size == 0) return
        items.clear()
        notifyItemRangeRemoved(0, size)
    }

    /**
     * Removes every row whose [ItemInfo] matches, mirroring what
     * [com.android.launcher3.Workspace.removeItemsByMatcher] does for CellLayout-hosted items.
     *
     * Strategy D means our rows are not CellLayout children, so that method's walk over
     * `getShortcutsAndWidgets()` never sees them. Without this, *nothing* is ever removed from the
     * list once bound. Two consequences were observed:
     *
     *  - **Duplicate rows.** `ModelCallbacks.bindItemsUpdated` updates an item by
     *    remove-then-rebind: it calls `removeItemsByMatcher(...)` and then `bindItems(...)`, which
     *    lands back in `addInScreen`. With the removal half missing, the rebind appended a second
     *    copy. Observed on a cold boot: Gmail (id 14) delivered once by the initial
     *    `bindCompleteModelAsync` pass, then again ~13s later via `bindUpdatedWorkspaceItems`,
     *    giving 7 rendered rows against 6 database rows.
     *  - **Stale rows after uninstall/removal**, which flow through the same matcher.
     *
     * Iterates in reverse so indices stay valid while removing, matching the stock method.
     */
    fun removeItems(matcher: Predicate<ItemInfo>): Boolean {
        var removed = false
        for (i in items.indices.reversed()) {
            if (matcher.test(items[i])) {
                items.removeAt(i)
                notifyItemRemoved(i)
                removed = true
            }
        }
        return removed
    }

    /**
     * Reorders a row during a drag. Visual only -- [snapshot] plus
     * [AresHomeReorder.persistOrder] handle the model write once the drag settles, so a drag that
     * crosses several rows produces one write pass rather than one per intermediate step.
     */
    fun moveItem(from: Int, to: Int): Boolean {
        if (from == to) return false
        if (from !in items.indices || to !in items.indices) return false
        items.add(to, items.removeAt(from))
        notifyItemMoved(from, to)
        return true
    }

    /** Current visual order, for persisting `rank`. */
    fun snapshot(): List<ItemInfo> = items.toList()

    override fun getItemCount(): Int = items.size

    override fun getItemId(position: Int): Long = items[position].id.toLong()

    override fun getItemViewType(position: Int): Int = when (items[position].itemType) {
        Favorites.ITEM_TYPE_APPWIDGET, Favorites.ITEM_TYPE_CUSTOM_APPWIDGET -> TYPE_WIDGET
        else -> TYPE_ICON
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val container = FrameLayout(parent.context)
        container.layoutParams = RecyclerView.LayoutParams(
            RecyclerView.LayoutParams.MATCH_PARENT,
            RecyclerView.LayoutParams.WRAP_CONTENT,
        )
        val holder = ViewHolder(container)
        if (viewType == TYPE_WIDGET) {
            // Widget host views must not be detached and re-attached while scrolling -- doing so
            // disrupts AppWidgetHostView sizing and RemoteViews state. Opting the holder out of
            // recycling is the standard escape hatch.
            holder.setIsRecyclable(false)
        }
        return holder
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val info = items[position]
        holder.container.removeAllViews()

        // inflateItem() uses attachToRoot=false, so the view is ours to add. It returns null when
        // the model decides an item should be dropped (e.g. a widget pending deletion).
        val itemView = launcher.itemInflater.inflateItem(info, holder.container) ?: return

        if (itemView is BubbleTextView) {
            // Deliberately not ItemLongClickListener.INSTANCE_WORKSPACE: that listener's job is
            // starting a CellLayout grid-drag, which doesn't apply to list rows.
            // BubbleTextView.startLongPressAction() is the generic, CellLayout-free mechanism that
            // shows the popup menu -- see component-verification-1.md/-3.md.
            itemView.setOnLongClickListener {
                itemView.startLongPressAction()
                true
            }
            applyRowStyle(itemView)
        }
        itemView.isHapticFeedbackEnabled = false

        if (itemView is FolderIcon) {
            holder.container.addView(
                buildFolderRow(itemView, holder.container),
                FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    holder.container.resources.getDimensionPixelSize(R.dimen.ares_app_row_height),
                ),
            )
            return
        }

        val rowHeight = if (itemView is BubbleTextView) {
            holder.container.resources.getDimensionPixelSize(R.dimen.ares_app_row_height)
        } else {
            widgetRowHeight(info)
        }
        holder.container.addView(
            itemView,
            FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, rowHeight),
        )

        if (itemView is AppWidgetHostView) {
            reportRowSizeToProvider(itemView, rowHeight)
        }
    }

    /**
     * Tells the provider the size of the row it actually occupies.
     *
     * Without any size report the host view lays out correctly but its RemoteViews content
     * collapses to zero (observed: content measured `520,232-520,232` inside a correctly-sized
     * 1040x464 host), because the provider was never handed size options and so never supplied a
     * layout for these bounds.
     *
     * The size is reported in **real dp**, not grid spans. `WidgetSizes.updateWidgetSizeRanges` --
     * which stock uses, and which this originally called -- derives dimensions from `spanX`/`spanY`
     * against the grid. That is right for a grid, and wrong here: our rows are always the full list
     * width regardless of `spanX`. A 2x1 widget was told it had two cells (~520px) while sitting in
     * a 1040px row, so it rendered its content as a small pill floating in the middle of the row
     * (observed with the Chrome Dino widget). Reporting the true row box makes the provider lay out
     * for the space it has been given.
     *
     * Width is the list's own width; during the very first bind the list may not be measured yet,
     * in which case the launcher's available width is a good stand-in, since the list is full-bleed.
     */
    private fun reportRowSizeToProvider(hostView: AppWidgetHostView, rowHeight: Int) {
        val density = launcher.resources.displayMetrics.density
        val widthPx = when {
            recyclerViewWidth > 0 -> recyclerViewWidth
            else -> launcher.deviceProfile.deviceProperties.availableWidthPx
        }
        val widthDp = (widthPx / density).toInt()
        val heightDp = (rowHeight / density).toInt()
        if (widthDp <= 0 || heightDp <= 0) return

        // A fresh Bundle, never Bundle.EMPTY: updateAppWidgetSize writes the computed size keys
        // into the bundle it is handed, and Bundle.EMPTY is immutable -- passing it throws
        // UnsupportedOperationException("ArrayMap is immutable") from inside the framework.
        // Exact box: the row does not resize with content, so min and max are the same.
        hostView.updateAppWidgetSize(Bundle(), widthDp, heightDp, widthDp, heightDp)
    }

    /**
     * Explicit pixel height for a widget row.
     *
     * Widgets must be given a concrete height. An [android.appwidget.AppWidgetHostView] has no
     * intrinsic content height of its own -- its children come from RemoteViews applied
     * asynchronously -- so `WRAP_CONTENT` measures to **zero** and the widget renders as an
     * invisible full-width strip. (Observed directly: the host view sat in the tree at
     * `0,0-1040,0`.) Stock Launcher3 never hits this because `CellLayout` always hands widgets
     * exact pixel bounds derived from their grid span.
     *
     * Policy for v1: reuse the height the grid would have produced, `spanY * cellHeightPx`. That
     * keeps widgets at the proportions their providers were designed against, and keeps us
     * consistent with how the same widget renders in any other launcher, without inventing a
     * bespoke sizing rule. §6 (resize) will replace this with a persisted per-widget height; this
     * is deliberately the simplest thing that is correct until then.
     *
     * Floored at one app-row height so a malformed or zero-span item can never collapse to an
     * invisible row again.
     */
    private fun widgetRowHeight(info: ItemInfo): Int {
        val res = launcher.resources
        val floor = res.getDimensionPixelSize(R.dimen.ares_app_row_height)
        val spanY = info.spanY.coerceAtLeast(1)
        return (spanY * launcher.deviceProfile.cellHeightPx).coerceAtLeast(floor)
    }

    /**
     * Wraps a [FolderIcon] into a row matching [applyRowStyle]'s app rows: preview on the left,
     * label dominant and adjacent, both vertically centred.
     *
     * A folder can't reuse [applyRowStyle] because [FolderIcon] is a `FrameLayout` that *draws* its
     * preview in `onDraw` rather than carrying it as a compound drawable, and its own label is a
     * `match_parent` child offset downwards by `iconSizePx + iconDrawablePaddingPx`. So the stock
     * arrangement is inherently icon-above-label.
     *
     * This uses only public API -- no vendored edits. The preview's position is controlled
     * indirectly, via the two inputs `PreviewItemManager` feeds to `PreviewBackground.setup()`:
     *  - X: `basePreviewOffsetX = (measuredWidth - previewSize) / 2`, so constraining the
     *    FolderIcon to a narrow leading box centres the preview inside that box instead of across
     *    the whole row. Box width is chosen so the preview's left edge lands on the same leading
     *    inset an app row's icon uses.
     *  - Y: `basePreviewOffsetY = paddingTop + folderIconOffsetYPx`, so paddingTop centres it.
     *
     * The built-in label is hidden via the public `setTextVisible(false)` and replaced with a
     * sibling `TextView`, which is what lets the label sit *beside* the preview rather than under
     * it. Colour and text are taken from the real label so themed/dark handling stays consistent.
     *
     * Because the FolderIcon now occupies only the leading box, the row forwards clicks to it.
     */
    private fun buildFolderRow(folderIcon: FolderIcon, parent: ViewGroup): ViewGroup {
        val res = parent.resources
        val grid = launcher.deviceProfile
        val rowHeight = res.getDimensionPixelSize(R.dimen.ares_app_row_height)
        val padH = res.getDimensionPixelSize(R.dimen.ares_app_row_padding_horizontal)
        val previewSize = grid.folderIconSizePx
        val stockLabel = folderIcon.folderName

        folderIcon.setTextVisible(false)
        // Vertically centre the drawn preview inside the row. setup() adds folderIconOffsetYPx on
        // top of paddingTop, so subtract it back out.
        folderIcon.setPadding(
            0,
            ((rowHeight - previewSize) / 2 - grid.folderIconOffsetYPx).coerceAtLeast(0),
            0,
            0,
        )

        val label = TextView(parent.context).apply {
            text = stockLabel.text
            setTextColor(stockLabel.textColors)
            setTextSize(TypedValue.COMPLEX_UNIT_PX, res.getDimension(R.dimen.ares_app_row_text_size))
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            gravity = Gravity.START or Gravity.CENTER_VERTICAL
        }

        return LinearLayout(parent.context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(folderIcon, LinearLayout.LayoutParams(padH * 2 + previewSize, rowHeight))
            addView(
                label,
                LinearLayout.LayoutParams(0, rowHeight, 1f).apply { marginEnd = padH },
            )
            setOnClickListener { folderIcon.performClick() }
            setOnLongClickListener { folderIcon.performLongClick() }
        }
    }

    /**
     * Applies the Niagara-style row appearance to a home-list icon: icon on the left, label
     * dominant and adjacent, both vertically centred in a generous row.
     *
     * Done programmatically rather than in XML because workspace items are inflated by
     * [com.android.launcher3.util.ItemInflater], which hardcodes `R.layout.app_icon` with no
     * override hook. `app_icon.xml` is shared with folders, app pairs and other surfaces, so it is
     * deliberately not edited in place -- see design/gesture-transition-reassessment.md §4.
     *
     * Note both attributes below are corrections of the stock vertical-grid styling:
     *  - `centerVertically` must be OFF: its onMeasure() path sums icon + padding + text height,
     *    which only holds when the icon sits ABOVE the text. In horizontal mode that over-estimates
     *    content height and produces a large bogus top padding.
     *  - gravity must be overridden: `BaseIcon.Workspace` inherits `center_horizontal`, which would
     *    centre the label in the leftover width instead of placing it next to the icon.
     *
     * Known gap: the icon keeps the workspace icon size, because `BubbleTextView.mIconSize` is
     * `private final` and only settable via the `iconSizeOverride` XML attribute. The app-list pane
     * gets the smaller Niagara icon via `ares_all_apps_icon.xml`; matching it here would require an
     * override hook in the shared ItemInflater.
     */
    private fun applyRowStyle(icon: BubbleTextView) {
        val res = icon.resources
        icon.setLayoutHorizontal(true)
        icon.setCenterVertically(false)
        icon.gravity = Gravity.START or Gravity.CENTER_VERTICAL
        icon.compoundDrawablePadding =
            res.getDimensionPixelSize(R.dimen.ares_app_row_drawable_padding)
        val padH = res.getDimensionPixelSize(R.dimen.ares_app_row_padding_horizontal)
        icon.setPaddingRelative(padH, 0, padH, 0)
        icon.setTextSize(
            TypedValue.COMPLEX_UNIT_PX,
            res.getDimension(R.dimen.ares_app_row_text_size),
        )
        icon.maxLines = 1
        icon.ellipsize = TextUtils.TruncateAt.END
    }

    override fun onViewRecycled(holder: ViewHolder) {
        holder.container.removeAllViews()
    }

    class ViewHolder(val container: FrameLayout) : RecyclerView.ViewHolder(container)

    private companion object {
        const val TYPE_ICON = 0
        const val TYPE_WIDGET = 1
    }
}
