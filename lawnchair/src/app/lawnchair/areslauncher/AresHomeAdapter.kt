package app.lawnchair.areslauncher

import android.appwidget.AppWidgetHostView
import android.os.Bundle
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.View
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
import com.android.launcher3.model.data.FolderInfo
import com.android.launcher3.model.data.ItemInfo
import com.android.launcher3.model.data.LauncherAppWidgetInfo
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
     * Invoked on item long-press to put the surface into edit mode (§4).
     *
     * A callback rather than a direct reference so the adapter stays unaware of its host view.
     */
    var editModeHost: (() -> Unit)? = null

    /**
     * Invoked when a widget's resize chevron is tapped, with the item to advance (§6).
     *
     * The host owns the resize because it has to repack, persist and re-report the widget's box --
     * none of which is the adapter's business. Same reasoning as [editModeHost].
     */
    var resizeHost: ((ItemInfo) -> Unit)? = null

    /**
     * Invoked when an item's remove (×) badge is tapped, with the item to take off the home screen.
     *
     * Host-owned for the same reason as [resizeHost]: removal has to write the model, release a
     * widget's host id and let the grid repack. Note this removes the item from the **home
     * screen only** — it never uninstalls the app, which stays in the app list.
     */
    var removeHost: ((ItemInfo) -> Unit)? = null

    /**
     * Whether the surface is currently in edit mode.
     *
     * Only consulted to decide whether a widget row shows its resize chevron. The host keeps this
     * in step via [setEditMode]; the adapter deliberately does not observe edit state itself.
     */
    private var editMode = false

    /**
     * Records edit mode so newly-bound rows get the right affordance.
     *
     * Deliberately does **not** notify: widget holders are `setIsRecyclable(false)`, so a rebind
     * cannot reuse the existing holder — RecyclerView builds a second one and leaves the first
     * attached. Toggling edit mode that way leaked a widget host view per widget per toggle
     * (observed: four host views for two widgets, one still drawn at its pre-resize size).
     *
     * The host adds and removes chevrons on already-attached rows itself via [syncChevron], which
     * it does while walking children for the edit-mode scale anyway.
     */
    fun setEditMode(enabled: Boolean) {
        editMode = enabled
    }

    /**
     * Adds or removes an attached row's resize chevron to match the current mode.
     *
     * Operates on the live view rather than rebinding, for the reason in [setEditMode].
     */
    fun syncChevron(container: FrameLayout, position: Int) {
        syncChevronFor(container, items.getOrNull(position))
    }

    /**
     * The single place a row's edit-mode affordances are brought in line with the current mode.
     *
     * Every entry point funnels through here — the host's edit-mode walk, its child-attach hook and
     * [onBindViewHolder] — so the "already has one" case is handled once. That matters because
     * widget holders are `setIsRecyclable(false)`: a holder can be bound again, or re-attached
     * without a bind at all, while still carrying the chevron from last time, and a blind `addView`
     * would stack a second one on top of it.
     *
     * A **null [info] means the row is entitled to nothing** and is how the host clears a row that
     * has left the adapter. Those rows are still attached children, and skipping them is what left
     * an × and a chevron on screen after edit mode had ended.
     *
     * A widget whose provider declares no resizable axis, or only one possible footprint, gets no
     * chevron: an affordance that visibly does nothing is worse than none. The × has no such
     * condition — anything on the home screen can be taken off it.
     *
     * ## Why the affordances are siblings of the item view, not children of it
     *
     * They go into the holder's **container**, above the item view, not inside it. Two reasons, and
     * the second is what makes the wiggle work:
     *
     *  - An `AppWidgetHostView` hosts the provider's RemoteViews. Adding our own child to it would
     *    be **removed the next time the provider pushes an update**, so the chevron would silently
     *    vanish on any widget that refreshes itself.
     *  - They still move with the item regardless, because **every edit-mode visual is applied to
     *    the container**, not to the item view — see `AresHomeListView.applyEditModeVisual`, which
     *    scales and rotates `getChildAt(i)` (the container) and so carries these along with it.
     *
     * So: keep animating the container. Do not move these into the item view to make them wiggle.
     */
    private fun syncChevronFor(container: FrameLayout, info: ItemInfo?) {
        val existing = container.findViewWithTag<View>(AresWidgetResize.CHEVRON_TAG)
        val target = info?.takeIf { editMode && isWidget(it) && isResizable(it) }
        if (target != null && existing == null) {
            container.addView(
                AresWidgetResize.createChevron(container, spokenNameOf(container, target)) {
                    resizeHost?.invoke(target)
                },
            )
        } else if (target == null && existing != null) {
            container.removeView(existing)
        }
        syncRemoveBadgeFor(container, info)
        syncCellOutlineFor(container, info)
    }

    /**
     * Outlines a widget's allocated cells while editing (§21).
     *
     * The user's diagnosis was exact: the × and the chevron are positioned on the **holder
     * container**, which is the item's allocated cell footprint, but a widget's own rendered
     * content often does not fill that footprint — providers keep their aspect ratio, or simply
     * draw smaller. The badges then sit on an invisible boundary and read as floating beside the
     * widget rather than attached to it. Drawing that boundary fixes the appearance and, more
     * usefully, shows how much grid the widget actually occupies, which is what you need to know
     * while cycling its size.
     *
     * **Widgets only.** An app icon roughly fills its 1×1 cell already, so outlining every tile
     * would add a box per icon for no information — noise competing with §14's dots rather than
     * complementing them.
     *
     * The outline goes in the container's *foreground* so it draws above the widget's own content
     * and, being a property of the container, wiggles and scales with the tile exactly as the two
     * badges do. It is a drawable, so it takes no touches.
     */
    private fun syncCellOutlineFor(container: FrameLayout, info: ItemInfo?) {
        val wanted = info != null && editMode && isWidget(info)
        val has = container.foreground != null
        if (wanted == has) return
        container.foreground = if (wanted) AresEditGrid.cellOutline(container.context) else null
    }

    /**
     * The single place a remove badge is added or removed.
     *
     * Applies to apps, shortcuts, app pairs and widgets — anything that is a *leaf* on the home
     * screen can be taken off it. Otherwise it mirrors the chevron exactly, including the reason it
     * funnels through one function: widget holders are `setIsRecyclable(false)`, so a holder can be
     * bound again while still carrying last time's badge, and a blind `addView` would stack a
     * second one on top.
     *
     * ## Folders are the one exception, deliberately (§18)
     *
     * A folder is a **container you descend into**, not an item you delete, so it carries no ×.
     * Tapping it in edit mode opens it; the apps inside carry their own × and emptying it is how
     * the folder itself goes away — stock deletes a folder once its contents drop below two, so a
     * separate "delete the folder" affordance would both duplicate that and be ambiguous against
     * "open it".
     *
     * The test is `FolderInfo`, not `CollectionInfo`: an **app pair** is also a `CollectionInfo`
     * but is not something you open and edit, so it keeps its × like any other leaf.
     */
    private fun syncRemoveBadgeFor(container: FrameLayout, info: ItemInfo?) {
        val existing = container.findViewWithTag<View>(AresRemoveBadge.BADGE_TAG)
        val target = info?.takeIf { editMode && it !is FolderInfo }
        if (target != null && existing == null) {
            container.addView(
                AresRemoveBadge.createBadge(container, spokenNameOf(container, target)) {
                    removeHost?.invoke(target)
                },
            )
        } else if (target == null && existing != null) {
            container.removeView(existing)
        }
    }

    /**
     * The name an affordance on this row should speak, or null when the row has no usable one.
     *
     * `ItemInfo.title` covers icons, shortcuts and folders. It is routinely **null for a widget** —
     * the model has no title for one — so the item view's own content description is the fallback:
     * `LauncherAppWidgetHostView` sets it from the provider's label, which is what a user would
     * call the thing ("Analog", "Chrome bookmarks"). The item view is child 0 by construction,
     * added by [onBindViewHolder] before this runs.
     */
    private fun spokenNameOf(container: FrameLayout, info: ItemInfo): CharSequence? =
        info.title?.takeIf { it.isNotBlank() }
            ?: container.getChildAt(0)?.contentDescription?.takeIf { it.isNotBlank() }

    private fun isResizable(info: ItemInfo): Boolean {
        val columns = gridColumns?.invoke() ?: return false
        return AresWidgetResize.allowedSizes(launcher, info, columns).isNotEmpty()
    }

    /**
     * Re-registers a widget for a size report after its footprint changed.
     *
     * A resize keeps the existing host view (see [AresMasonryLayoutManager.invalidatePacking]), so
     * nothing rebinds and nothing would otherwise tell the provider its box changed — it would keep
     * rendering RemoteViews measured for the old one.
     */
    fun reportSizeAfterResize(info: ItemInfo, container: FrameLayout) {
        val hostView = container.getChildAt(0) as? AppWidgetHostView ?: return
        pendingWidgetSizeReports[hostView] = info
    }

    private fun isWidget(info: ItemInfo): Boolean = when (info.itemType) {
        Favorites.ITEM_TYPE_APPWIDGET, Favorites.ITEM_TYPE_CUSTOM_APPWIDGET -> true
        else -> false
    }

    /** Position of [info] in the current order, or -1. Used by the host to repack after a resize. */
    fun indexOf(info: ItemInfo): Int = items.indexOfFirst { it.id == info.id }

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
     * bind batches**, and within each batch `BaseLauncherBinder` sorts *spatially* by
     * `(screenId, cellY, cellX)`. Under masonry those cell coordinates are stale bookkeeping that
     * nothing renders from, so delivery order carries no meaning at all — appending would render
     * every widget after every icon regardless of where the user put them. Observed directly: a
     * widget saved at rank 2 came back rendered last after a reboot, while the icons around it were
     * correctly ordered.
     *
     * ## Ties break on `id`, deliberately — never on arrival order
     *
     * Equal ranks are not hypothetical. They arise on a fresh profile (every row still `rank = 0`),
     * and they arise in normal use: a folder collapsing hands its survivor a rank, and the widget
     * add path appends at `max + 1`, so any path that mis-numbers produces a pair. Left as a
     * *stable* insert, a tie resolves by model delivery order — i.e. by those stale `cellX`/`cellY`
     * values, in two batches. That is reproducible only by accident: it changes when an unrelated
     * write touches a cell, and it puts every widget after every icon within a tie group.
     *
     * Comparing `(rank, id)` instead gives a **total order** over the list. `id` is the model's
     * primary key — unique, stable, and present before the row is ever bound — so a set of colliding
     * ranks comes back in the same visual order after every reload, which is the property that
     * matters. It is not a *meaningful* order, and it is not meant to be; the packer is a pure
     * function of the sequence, so an arbitrary-but-fixed sequence still renders identically every
     * time. `AresHomeReorder.persistOrder` renumbers the whole grid densely on any drag, so a
     * collision heals as soon as the user rearranges anything.
     */
    fun addItem(info: ItemInfo) {
        if (dropDuplicateWidgetRow(info)) return

        var index = items.size
        for (i in items.indices) {
            if (sortsAfter(items[i], info)) {
                index = i
                break
            }
        }
        items.add(index, info)
        notifyItemInserted(index)
    }

    /** True when [existing] belongs strictly after [incoming] in the grid's total order. */
    private fun sortsAfter(existing: ItemInfo, incoming: ItemInfo): Boolean =
        existing.rank > incoming.rank ||
            (existing.rank == incoming.rank && existing.id > incoming.id)

    /**
     * Collapses two rows that describe the **same widget instance** into one (§7).
     *
     * An `appWidgetId` identifies one live widget held by the launcher's widget host. Two database
     * rows carrying the same one is always a bug — never a user with two clocks, who would have two
     * separate ids — and it is the wreckage the pre-fix add flow left behind: `completeAddAppWidget`
     * could not find the pending host view (see `Workspace.getWidgetForAppWidgetId`), so on the way
     * back from a configure activity it wrote a second row instead of replacing the first. The user
     * saw two clocks, one of them stuck as a `PendingAppWidgetHostView` that never finished setting
     * up.
     *
     * `Workspace.getWidgetForAppWidgetId` stops that happening again, but only for a row that is on
     * screen at the time. This is the row-level backstop for the rest, and it is also what heals a
     * profile that already has the duplicate — the point of it is that nobody has to wipe data.
     *
     * The row kept is whichever finished restoring; a placeholder loses to a real widget. The loser
     * goes through `deleteItemFromDatabase` and **never** `deleteWidgetInfo`: the two rows share one
     * `appWidgetId`, and releasing it from the host would take the surviving widget down with it.
     *
     * @return true when [info] is the row to discard, so the caller must not insert it.
     */
    private fun dropDuplicateWidgetRow(info: ItemInfo): Boolean {
        if (info !is LauncherAppWidgetInfo) return false
        val index = items.indexOfFirst {
            it is LauncherAppWidgetInfo && it.appWidgetId == info.appWidgetId
        }
        if (index < 0) return false

        val existing = items[index] as LauncherAppWidgetInfo
        // Same row arriving twice (a double bind), not two rows: nothing to delete, just don't
        // list it again. removeItems() exists to keep this rare, but it is cheap to be sure.
        if (existing === info || existing.id == info.id) return true

        if (isPlaceholder(existing) && !isPlaceholder(info)) {
            launcher.modelWriter.deleteItemFromDatabase(existing, DUPLICATE_WIDGET_REASON)
            items.removeAt(index)
            notifyItemRemoved(index)
            return false
        }
        launcher.modelWriter.deleteItemFromDatabase(info, DUPLICATE_WIDGET_REASON)
        return true
    }

    /** A widget row still waiting on binding, restore or a configure activity to finish. */
    private fun isPlaceholder(info: LauncherAppWidgetInfo): Boolean =
        info.restoreStatus != LauncherAppWidgetInfo.RESTORE_COMPLETED

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

    /**
     * The item at [index], or null when [index] is out of range.
     *
     * Exists so hit-testing can go from a child view to its model item without reading a tag off
     * the view tree. The tag is set by [com.android.launcher3.util.ItemInflater] on the *item* view
     * rather than the holder container, and for a widget that view is an `AppWidgetHostView` whose
     * children the provider replaces at will — asking the adapter is both shorter and immune to
     * that. `RecyclerView.NO_POSITION` is a normal answer here (a detached or animating holder), so
     * it is handled rather than guarded against at every call site.
     */
    fun itemAt(index: Int): ItemInfo? = items.getOrNull(index)

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
            applyGridStyle(itemView)
        }

        // Long-press enters edit mode for EVERY item type. This was previously gated on
        // `itemView is BubbleTextView`, and FolderIcon is not one -- so a folder could never
        // *enter* edit mode by long-pressing it (folders dragged fine once it was active by other
        // means). The user has a Google folder on their home screen, so it was immediately hit.
        itemView.setOnLongClickListener {
            // One gesture, one state. A long-press on a tile that is *not* already editing enters
            // edit mode and nothing else; the context popup comes up only on a long-press made
            // from inside the mode.
            //
            // Showing both at once was incoherent in use, and measurably so. Edit mode and the
            // popup are two floating states raised by a single gesture, and each dismissal gesture
            // clears exactly one of them -- so tapping empty space (or pressing BACK) once closed
            // the popup and left the grid editing, and it took a second identical gesture to
            // actually leave. It also blocked the thing edit mode is *for*: with the popup up, the
            // first touch anywhere outside it is spent closing it, so the immediate drag §9C asks
            // for could not start until the user had dismissed a menu they never asked for.
            //
            // Nothing is lost. The popup is still the only route to App info, Uninstall and an
            // app's shortcuts, and it is still a long-press away -- one made deliberately, from a
            // surface that is already in the mode. Verified reachable that way on device before
            // this change was written.
            //
            // Deliberately not ItemLongClickListener.INSTANCE_WORKSPACE: that listener's job is
            // starting a CellLayout grid-drag, which the packed grid doesn't use.
            // startLongPressAction() -> PopupContainerWithArrow.showForIcon() takes a
            // BubbleTextView, so only icons can show the popup at all; a folder never does.
            //
            // enterEditMode() carries the mid-gesture guard -- edit mode is entered *during* a
            // gesture, so that gesture's own UP would otherwise read as an empty-space tap and
            // exit immediately -- and is a no-op once the mode is on.
            if (editMode && itemView is BubbleTextView) {
                itemView.startLongPressAction()
            }
            editModeHost?.invoke()
            true
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

        // Outside the widget branch on purpose. The × applies to every item type -- anything on the
        // home screen can be taken off it -- and while this was gated on AppWidgetHostView, an icon
        // or folder bound during edit mode (scrolled in, or delivered by a late bind) came up with
        // no badge at all and could not be removed without leaving and re-entering the mode.
        // syncChevronFor decides what this particular item is entitled to.
        syncChevronFor(holder.container, info)
    }

    /**
     * Supplies the grid's column count, for clamping the sizes a widget may be offered.
     *
     * A callback rather than a stored int because folding changes the device profile, and an
     * offered size wider than the grid would make the cycle appear to skip a step.
     */
    var gridColumns: (() -> Int)? = null

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
        const val DUPLICATE_WIDGET_REASON =
            "AresLauncher: second database row for an appWidgetId already on the home grid"
    }
}
