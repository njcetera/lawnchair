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

    /**
     * Footprint of an item in grid cells, for [AresMasonryLayoutManager].
     *
     * Icons, shortcuts and folders are always 1x1. Widgets carry the spans the model persisted --
     * the same values stock would have used to place them in a CellLayout -- so a widget occupies
     * the proportions its provider was designed against.
     */
    fun spanOf(position: Int): AresPacker.Span {
        val info = items.getOrNull(position) ?: return AresPacker.Span(1, 1)
        return when (info.itemType) {
            Favorites.ITEM_TYPE_APPWIDGET, Favorites.ITEM_TYPE_CUSTOM_APPWIDGET ->
                AresPacker.Span(info.spanX.coerceAtLeast(1), info.spanY.coerceAtLeast(1))
            else -> AresPacker.Span(1, 1)
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val container = FrameLayout(parent.context)
        // The layout manager measures each holder to its exact cell footprint, so the container
        // fills whatever it is given rather than sizing itself.
        container.layoutParams = RecyclerView.LayoutParams(
            RecyclerView.LayoutParams.MATCH_PARENT,
            RecyclerView.LayoutParams.MATCH_PARENT,
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
            applyGridStyle(itemView)
        }
        itemView.isHapticFeedbackEnabled = false

        // Every item fills its cell; the layout manager has already sized the holder to the item's
        // footprint. Folders keep their stock arrangement (preview above label) like any other
        // icon -- the bespoke icon-left folder row belonged to the one-per-line list.
        holder.container.addView(
            itemView,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT,
            ),
        )

        if (itemView is AppWidgetHostView) {
            pendingWidgetSizeReports[itemView] = info
        }
    }

    /**
     * Widgets whose provider still needs to be told its box, keyed by host view.
     *
     * The size can only be reported once the view has real bounds, and under a grid those come from
     * the layout manager rather than from a height this adapter chose. [reportPendingWidgetSizes]
     * drains this after layout.
     */
    private val pendingWidgetSizeReports = mutableMapOf<AppWidgetHostView, ItemInfo>()

    /**
     * Reports each newly-bound widget's real on-screen box to its provider.
     *
     * Called after layout, because the box is the cell footprint the layout manager assigned, which
     * is not known at bind time. Without a size report the host view lays out correctly but its
     * RemoteViews content collapses to zero -- the provider was never handed size options, so it
     * never supplied a layout for these bounds.
     */
    fun reportPendingWidgetSizes() {
        if (pendingWidgetSizeReports.isEmpty()) return
        val iterator = pendingWidgetSizeReports.entries.iterator()
        while (iterator.hasNext()) {
            val (hostView, _) = iterator.next()
            if (hostView.width <= 0 || hostView.height <= 0) continue
            reportBoxSizeToProvider(hostView)
            iterator.remove()
        }
    }

    /**
     * Tells the provider the size of the box it actually occupies.
     *
     * Without any size report the host view lays out correctly but its RemoteViews content
     * collapses to zero (observed: content measured `520,232-520,232` inside a correctly-sized
     * 1040x464 host), because the provider was never handed size options and so never supplied a
     * layout for these bounds.
     *
     * The size is read from the host view's **measured bounds** rather than recomputed from spans.
     * Under masonry the layout manager owns placement, so its assigned box is the authority; deriving
     * a size independently risks the two disagreeing. An earlier row-based revision had exactly that
     * bug in the other direction -- it used `WidgetSizes.updateWidgetSizeRanges`, whose span-derived
     * width said "two cells" while the row was full-bleed, so a 2x1 widget rendered as a small pill
     * floating mid-row.
     */
    private fun reportBoxSizeToProvider(hostView: AppWidgetHostView) {
        val density = launcher.resources.displayMetrics.density
        val widthDp = (hostView.width / density).toInt()
        val heightDp = (hostView.height / density).toInt()
        if (widthDp <= 0 || heightDp <= 0) return

        // A fresh Bundle, never Bundle.EMPTY: updateAppWidgetSize writes the computed size keys
        // into the bundle it is handed, and Bundle.EMPTY is immutable -- passing it throws
        // UnsupportedOperationException("ArrayMap is immutable") from inside the framework.
        // Exact box: the row does not resize with content, so min and max are the same.
        hostView.updateAppWidgetSize(Bundle(), widthDp, heightDp, widthDp, heightDp)
    }

    /**
     * Home-grid icon styling: **stock arrangement, label under the icon**.
     *
     * The grid deliberately keeps `BubbleTextView`'s default vertical layout -- icon above, label
     * beneath, centred -- because that is what the spec asks for ("the text for icons should be
     * under the icon like usual", requirements-alignment.md §4).
     *
     * The icon-left/label-right treatment this used to apply belonged to the one-per-line list and
     * now lives **only on the app-list pane**, via `ares_all_apps_icon.xml`. Do not reintroduce
     * `setLayoutHorizontal(true)` here: in a square grid cell it would push the label into the
     * leftover width and clip it.
     *
     * Nothing is overridden, so items inherit the workspace icon size and text appearance the rest
     * of Launcher3 uses. That is the point -- a home grid should look like a home grid.
     */
    private fun applyGridStyle(icon: BubbleTextView) {
        icon.setLayoutHorizontal(false)
        icon.setCenterVertically(false)
        icon.gravity = Gravity.CENTER_HORIZONTAL
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
