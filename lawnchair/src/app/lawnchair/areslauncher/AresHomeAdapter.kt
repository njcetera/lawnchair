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
     * Invoked as a widget's resize handle is dragged (§3), with the item and the drag's
     * displacement in raw screen pixels.
     *
     * The host owns the resize because it has to quantise against the grid's cell metrics, repack
     * live, persist and re-report the widget's box -- none of which is the adapter's business. Same
     * reasoning as [editModeHost].
     */
    var resizeHost: ((ItemInfo, Float, Float, AresWidgetResize.Phase) -> Unit)? = null

    /**
     * Invoked when an item's remove (×) badge is tapped, with the item to take off the home screen.
     *
     * Host-owned for the same reason as [resizeHost]: removal has to write the model, release a
     * widget's host id and let the grid repack. Note this removes the item from the **home
     * screen only** — it never uninstalls the app, which stays in the app list.
     */
    var removeHost: ((ItemInfo) -> Unit)? = null

    /**
     * Invoked when an item's ! badge is tapped, with the item whose menu to raise (§E4).
     *
     * Host-owned for the same reason as [removeHost]: raising the popup needs the item's *view* to
     * anchor to, and the host is what holds the attached children. The adapter knows which item was
     * tapped; only the host can find what it is currently drawn as.
     */
    var menuHost: ((ItemInfo) -> Unit)? = null

    /**
     * Whether the surface is currently in edit mode.
     *
     * Consulted by **every** edit-mode visual this adapter owns — the resize chevron, the × remove
     * badge, the ! menu badge, the §21 cell outline and the hidden label ([AresEditLabel]) — all of
     * which funnel through [syncEditVisualsFor]. The host keeps this in step via [setEditMode]; the
     * adapter deliberately does not observe edit state itself.
     *
     * It used to say "only consulted to decide whether a widget row shows its resize chevron",
     * which was true when written and false the moment the × was added to every item. A comment of
     * exactly that shape already shipped a bug here (`1c4a4f33bc`), so: **if you add another one,
     * this list and the funnel's name are part of the change.** They already have been twice — the
     * funnel was called `syncAffordances` until the frost box and the label treatment, neither of
     * which is a tap target, made that name a third stale comment waiting to mislead someone.
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
     * The host adds and removes affordances on already-attached rows itself via [syncEditVisuals],
     * which it does while walking children for the edit-mode scale anyway.
     */
    fun setEditMode(enabled: Boolean) {
        editMode = enabled
    }

    /**
     * Brings an attached row's edit-mode visuals -- × badge, resize chevron, cell outline and the
     * hidden label -- in line with the current mode.
     *
     * Operates on the live view rather than rebinding, for the reason in [setEditMode].
     */
    fun syncEditVisuals(container: FrameLayout, position: Int) {
        // The drop slot (§C4) is a hole, not an item: no ×, no !, no outline, no chevron. Filtered
        // here rather than at each caller because BOTH of them -- onChildAttachedToWindow and
        // applyEditModeVisual -- run while a drag is over the grid, and syncEditVisualsFor already
        // reads a null item as "this row carries nothing".
        syncEditVisualsFor(container, items.getOrNull(position)?.takeIf { it !== dropSlot })
    }

    /**
     * The single place a row's edit-mode visuals are brought in line with the current mode.
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
     *
     * ## The label treatment is the one thing here that writes the ITEM view
     *
     * [AresEditLabel] fades the caption and slides the icon down to the cell's centre, and it must
     * touch the item view rather than the container for exactly the reason above, read backwards:
     * the badges and the frost describe the **cell**, so they have to stay where the cell is while
     * the icon moves inside it. See that file — it also records why an open folder is excluded.
     */
    private fun syncEditVisualsFor(container: FrameLayout, info: ItemInfo?) {
        val existing = container.findViewWithTag<View>(AresWidgetResize.CHEVRON_TAG)
        val target = info?.takeIf { editMode && isWidget(it) && isResizable(it) }
        if (target != null && existing == null) {
            container.addView(
                AresWidgetResize.createHandle(container, spokenNameOf(container, target)) { dx, dy, phase ->
                    resizeHost?.invoke(target, dx, dy, phase)
                },
            )
        } else if (target == null && existing != null) {
            container.removeView(existing)
        }
        syncRemoveBadgeFor(container, info)
        syncInfoBadgeFor(container, info)
        syncCellOutlineFor(container, info)
        // A null info means the row has left the adapter, and it gets its label back for the same
        // reason it loses its ×: it is no longer editable, whatever the mode says.
        AresEditLabel.set(container, editMode && info != null)
    }

    /**
     * The single place the ! context-menu badge is added or removed.
     *
     * Mirrors [syncRemoveBadgeFor] exactly, including why it funnels through one function: widget
     * holders are `setIsRecyclable(false)`, so a holder can be bound again while still carrying last
     * time's badge and a blind `addView` would stack a second one on it.
     *
     * Which items get one is [AresInfoBadge.hasMenu]'s decision rather than a condition written out
     * here, so the badge and its hit-test cannot drift apart about it. Folders are excluded there:
     * no popup exists for a folder, and an affordance with nothing behind it is worse than none.
     */
    private fun syncInfoBadgeFor(container: FrameLayout, info: ItemInfo?) {
        val existing = container.findViewWithTag<View>(AresInfoBadge.BADGE_TAG)
        val target = info?.takeIf { editMode && AresInfoBadge.hasMenu(it) }
        if (target != null && existing == null) {
            container.addView(
                AresInfoBadge.createBadge(container, spokenNameOf(container, target)) {
                    menuHost?.invoke(target)
                },
            )
        } else if (target == null && existing != null) {
            container.removeView(existing)
        }
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
     * **Every editable tile, not just widgets** — apps, folders, app pairs and widgets alike:
     *
     * > *"I also like this so much, I think we should expand it to app icons and folders when I
     * > edit mode? makes sense for it to just be on the home screen edit and not inside folders
     * > when editing."*
     *
     * That supersedes the widgets-only rule this function shipped with, whose reasoning was that an
     * icon roughly fills its own 1×1 cell so a box round it adds no information. True of a *box*;
     * the frost is not one. It now says "this tile is editable", which is information every tile
     * has, and it is what makes the whole grid read as one surface in edit mode instead of the
     * widgets looking singled out.
     *
     * **Home grid only, by construction.** The user drew that line themselves, and it needs no
     * condition here: this adapter serves [AresHomeListView] and nothing else, and an open folder's
     * edit mode is `AresFolderEdit`, which never calls this.
     *
     * The frost goes in the container's *foreground* so it draws above the item's own content and,
     * being a property of the container, wiggles and scales with the tile exactly as the badges do
     * — including the pick-up swell, which is applied to the same view. It is a drawable, so it
     * takes no touches and cannot affect the affordance hit-testing or the centre drag disc.
     */
    private fun syncCellOutlineFor(container: FrameLayout, info: ItemInfo?) {
        val wanted = info != null && editMode
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

    /**
     * The list we are attached to, held only so [releaseForRemoval] can reach a holder by position.
     * Null between detach and the next attach.
     */
    private var host: RecyclerView? = null

    override fun onAttachedToRecyclerView(recyclerView: RecyclerView) {
        super.onAttachedToRecyclerView(recyclerView)
        host = recyclerView
        recyclerViewWidth = recyclerView.width
        // The list is typically unmeasured at attach time, so pick the width up on first layout.
        recyclerView.addOnLayoutChangeListener { v, _, _, _, _, _, _, _, _ ->
            recyclerViewWidth = v.width
        }
    }

    override fun onDetachedFromRecyclerView(recyclerView: RecyclerView) {
        super.onDetachedFromRecyclerView(recyclerView)
        host = null
    }

    /**
     * Balances the recyclable counter for the holder at [position] **before** its removal notify.
     * This is the fix for W1 — a deleted widget that stays on screen forever.
     *
     * `setIsRecyclable` is a **counter**, not a flag (`RecyclerView.java:12500-12514`), and
     * `FLAG_NOT_RECYCLABLE` only clears when it reaches zero. [onCreateViewHolder] calls
     * `setIsRecyclable(false)` **permanently** on every widget holder, so the count sits at 1 for
     * the holder's whole life. That is an unbalanced decrement, and RecyclerView's removal path
     * assumes a balanced one:
     *
     * ```
     * animateDisappearance (RecyclerView.java:4976)
     *   addAnimatingView(holder)          -> attachViewToParent(view, -1, lp, hidden = true)
     *   holder.setIsRecyclable(false)     -> count 1 -> 2
     * ...fade runs, DefaultItemAnimator.animateRemoveImpl leaves alpha back at 1...
     * ItemAnimatorRestoreListener.onAnimationFinished (RecyclerView.java:13827)
     *   item.setIsRecyclable(true)        -> count 2 -> 1, so the FLAG STAYS SET
     *   if (!item.shouldBeKeptAsChild())  -> shouldBeKeptAsChild() is (mFlags & FLAG_NOT_RECYCLABLE) != 0
     *       removeAnimatingView(...)      -> NEVER REACHED
     * ```
     *
     * So the view is re-attached hidden and never detached. `LayoutManager.getChildCount()`
     * subtracts hidden views, so [AresMasonryLayoutManager] cannot see it, cannot lay it out and
     * cannot recycle it — it keeps the bounds it had at deletion until the activity is recreated.
     *
     * That single mechanism accounts for every symptom recorded against W1: 17 attached children
     * against 16 database rows (`dumpsys` walks the real children, hidden ones included); cleared
     * only by a reinstall; and unfolded-width tiles surviving a fold, because nothing ever lays it
     * out again. It also explains why only **widgets** ghost — an icon holder's count runs
     * 0 -> 1 -> 0, the flag clears, and cleanup happens normally.
     *
     * One earlier sentence here claimed the ghost "sinks to index 0" and draws *behind* everything.
     * **Measured wrong by the §27 verifier**: the ghost sits as the LAST direct child (index 30 of
     * 30 in its run) and draws ON TOP of the live widget that repacks into its cell — the
     * screenshot shows the deleted widget rendered over the live one, × badge and all. The owner's
     * "in the background" reading most plausibly came from the ghost's translucency (verifier's
     * inference, flagged as such). The mechanism above is unaffected; that explanatory sentence
     * was.
     *
     * Restoring the count here rather than dropping the item animator keeps §C4's drop-slot
     * animation and every reorder animation, which are the same `notifyItemMoved` pipeline. The
     * holder is leaving the adapter anyway, so it has no further use for the opt-out.
     */
    private fun releaseForRemoval(position: Int) {
        val holder = host?.findViewHolderForAdapterPosition(position) ?: return
        if (holder.itemViewType != TYPE_WIDGET) return
        // isRecyclable is false exactly when the flag is set. One matching call clears our one
        // permanent decrement; the animation's own pair then balances to zero on its own.
        if (!holder.isRecyclable) holder.setIsRecyclable(true)
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

    /**
     * Inserts [info] at an explicit [index] rather than wherever its `rank` sorts.
     *
     * [addItem] is the funnel for items arriving from the *model* — a bind, a restore, an update —
     * where `rank` is the authority and the visual order must follow it. A **drop** is the other
     * direction: the index is what the user just expressed with their finger, and `rank` is the
     * consequence. Sorting by rank here cannot express it, and would not even be deterministic: the
     * grid's ranks are dense after any drag, so an incoming item at rank *k* ties with the item
     * already at *k*, and [sortsAfter] then breaks the tie on database `id` — which for a freshly
     * created row is arbitrarily larger than everything else.
     *
     * The caller renumbers afterwards through [AresHomeReorder.persistOrder], which is what makes
     * the index durable.
     */
    fun addItemAt(info: ItemInfo, index: Int) {
        if (dropDuplicateWidgetRow(info)) return
        val at = index.coerceIn(0, items.size)
        items.add(at, info)
        notifyItemInserted(at)
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
        // An UNALLOCATED id is not an identity. Every restore placeholder carries
        // `appWidgetId = NO_ID`, so two seeded-but-not-yet-bound widgets "share" -1 without
        // sharing anything -- and when the bind race resolves slowly enough for both to arrive
        // here still unallocated, matching on it deleted a real row from the database. Measured
        // as the intermittent "fewer than 2 widgets survived" seed failure: the same two rows
        // bind fine on the next relaunch. The heal below exists for rows that share a REAL id.
        if (!info.isWidgetIdAllocated) return false
        val index = items.indexOfFirst {
            it is LauncherAppWidgetInfo && it.isWidgetIdAllocated &&
                it.appWidgetId == info.appWidgetId
        }
        if (index < 0) return false

        val existing = items[index] as LauncherAppWidgetInfo
        // Same row arriving twice (a double bind), not two rows: nothing to delete, just don't
        // list it again. removeItems() exists to keep this rare, but it is cheap to be sure.
        if (existing === info || existing.id == info.id) return true

        if (isPlaceholder(existing) && !isPlaceholder(info)) {
            launcher.modelWriter.deleteItemFromDatabase(existing, DUPLICATE_WIDGET_REASON)
            releaseForRemoval(index)
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
        for (i in 0 until size) releaseForRemoval(i)
        items.clear()
        // The §C4 gap is a view-level entry with no row behind it, and `items.clear()` does not
        // reach the field that points at it. Leaving it set orphans the slot: every later guard
        // then misfires by identity -- `moveDropSlot` finds `from < 0` and returns for the rest of
        // the drag, `AresHomeDropPreview.onExternalDragOver` still sees `list === grid` so it never
        // calls `showDropSlot` again, and `take()` returns -1 so the drop falls back to a
        // re-derived release point instead of the gap the user watched. The trigger is a rebind
        // landing mid-drag -- `Workspace.removeAllWorkspaceScreens` calls this from
        // `ModelCallbacks.startBinding`, which a HOME press or a finishing package install can
        // raise while an app is being dragged out of a folder. Nothing throws and the next drag is
        // fine, which is the intermittent-with-no-visible-cause shape this project keeps hitting.
        dropSlot = null
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
                releaseForRemoval(i)
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

    /**
     * Current visual order, for persisting `rank`.
     *
     * The §C4 drop slot is filtered out **here**, at the single point every persist path goes
     * through, rather than at each of the six call sites. It is a view-level hole with no database
     * row behind it; letting one reach [AresHomeReorder.persistOrder] would renumber the whole grid
     * around a thing that does not exist and leave a gap in the ranks when it vanished. Six callers
     * and a mid-drag lifetime is exactly the shape that gets missed at one of them.
     */
    fun snapshot(): List<ItemInfo> = items.filter { it !== dropSlot }

    // ------------------------------------------------------------------ §C4 drop slot ---
    //
    // A drag that arrives from OUTSIDE the grid -- out of an open folder, chiefly -- is a
    // `DragController` drag, not the `ItemTouchHelper` one that drives the masonry reflow, so
    // nothing was asking the grid to make room and it only readjusted on release. Measured on
    // emulator-5554 before this existed: mid-drag the tile signature was byte-identical to at
    // rest, and a fourth tile appeared only after the finger came up.
    //
    // The grid's position model is an ordered sequence, so "make room" has exactly one
    // representation: an extra entry. This is that entry -- an item that renders nothing, so the
    // packer lays out a gap the size of a cell and the tiles after it slide down, animated by
    // RecyclerView exactly as an in-grid reorder is. [AresHomeDropPreview] drives it.

    /** The in-flight gap, or null. Identity, never id: it has no row and no meaningful id. */
    private var dropSlot: ItemInfo? = null

    /** True when [info] is the gap rather than a real item. */
    fun isDropSlot(info: ItemInfo?): Boolean = info != null && info === dropSlot

    /**
     * Opens a gap at [index] and returns it.
     *
     * Not the dragged item itself, which is the obvious choice and wrong twice over: it would
     * render a second copy of the icon already under the finger, and stable ids would then have two
     * holders claiming one id at the moment the real insert lands.
     */
    fun showDropSlot(index: Int): ItemInfo {
        clearDropSlot()
        val slot = ItemInfo().apply {
            // Unique by construction: database ids are positive and NO_ID is -1, so nothing else
            // can collide with this under setHasStableIds(true).
            id = DROP_SLOT_ID
            itemType = Favorites.ITEM_TYPE_APPLICATION
            spanX = 1
            spanY = 1
        }
        dropSlot = slot
        val at = index.coerceIn(0, items.size)
        items.add(at, slot)
        notifyItemInserted(at)
        return slot
    }

    /** Slides the gap to [index]. The same `notifyItemMoved` an in-grid reorder uses. */
    fun moveDropSlot(index: Int) {
        val slot = dropSlot ?: return
        val from = items.indexOfFirst { it === slot }
        if (from < 0) return
        moveItem(from, index.coerceIn(0, items.size - 1))
    }

    /**
     * Closes the gap, returning the index it occupied, or -1 if there was none.
     *
     * The index is the return value because it is the answer to "where does this land": it is what
     * the user has been looking at for the whole drag.
     */
    fun clearDropSlot(): Int {
        val slot = dropSlot ?: return -1
        dropSlot = null
        val at = items.indexOfFirst { it === slot }
        if (at < 0) return -1
        items.removeAt(at)
        notifyItemRemoved(at)
        return at
    }

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

        // The §C4 drop slot renders NOTHING. It exists so the packer allocates a cell and the
        // tiles after it move aside while the item is still held; an empty container is the whole
        // implementation. Returning here also skips the long-press listener, the affordance sync
        // and the arrival animation below, none of which have anything to act on.
        if (info === dropSlot) return

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
            // ...and never while a reorder is already in flight. Belt and braces behind
            // AresHomeListView's cancelPendingInputEvents at drag start: a popup raised on top of a
            // live drag is stale by construction, nothing closes it once the drag has begun, and it
            // then eats the first BACK (Launcher.getOnBackAnimationCallback()'s #3 branch runs
            // before the #5 state handler that leaves edit mode). That was the intermittent
            // "edit mode refuses to exit" wedge.
            val reordering = launcher.workspace?.aresHomeList?.isReorderInProgress() == true
            if (editMode && !reordering && itemView is BubbleTextView) {
                itemView.startLongPressAction()
            }
            editModeHost?.invoke()
            true
        }
        itemView.isHapticFeedbackEnabled = false

        // Every item fills its cell; the layout manager has already sized the holder to the item's
        // footprint. Folders keep their stock arrangement (preview above label) like any other
        // icon -- the bespoke icon-left folder row belonged to the one-per-line list.
        //
        // §23: a WIDGET is inset inside that cell so neighbouring widgets do not touch. Icons and
        // folders are not, because they already draw centred with their own padding and the
        // profile sizes their content against the whole cell -- insetting them clips the label.
        // The inset goes on the CONTAINER's padding rather than the item's margins so the frost
        // box, which is the container's foreground and is drawn over its full bounds regardless of
        // padding, keeps describing the entire cell the widget occupies (§21).
        val widgetInset = if (itemView is AppWidgetHostView) {
            holder.container.resources.getDimensionPixelSize(R.dimen.ares_home_widget_inset) / 2
        } else {
            0
        }
        holder.container.setPadding(widgetInset, widgetInset, widgetInset, widgetInset)
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
        // syncEditVisualsFor decides what this particular item is entitled to.
        syncEditVisualsFor(holder.container, info)

        // Last, so the host sees a fully-built row. This is the one deterministic moment a
        // newly-inserted item exists: `onChildAttachedToWindow` is not, because RecyclerView
        // happily rebinds an already-attached holder when an insert and a removal land in the same
        // pass -- which is exactly what creating a folder from two tiles does.
        boundHost?.invoke(info, holder.container)
    }

    /**
     * Called after every row is bound, so the host can play one-shot arrival animations.
     *
     * Host-owned for the same reason as [resizeHost] and [removeHost]: the adapter knows *what*
     * was bound, and only [AresHomeListView] knows what the mode's resting scale is and which
     * animators are already running on the container.
     */
    var boundHost: ((ItemInfo, FrameLayout) -> Unit)? = null

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

        /** Stable id for the §C4 drop slot. Nothing else can hold it: real ids are positive. */
        const val DROP_SLOT_ID = Int.MIN_VALUE
        const val DUPLICATE_WIDGET_REASON =
            "AresLauncher: second database row for an appWidgetId already on the home grid"
    }
}
