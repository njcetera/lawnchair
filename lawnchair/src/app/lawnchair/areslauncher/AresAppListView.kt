package app.lawnchair.areslauncher

import android.content.Context
import android.view.ViewGroup
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.android.launcher3.Launcher
import com.android.launcher3.allapps.AllAppsStore
import com.android.launcher3.celllayout.CellLayoutLayoutParams

/**
 * The persistent app-list panel shown on the right half of an unfolded foldable
 * (design/foldable-dual-pane.md). Panel 0 keeps the home list ([AresHomeListView]); this fills
 * panel 1, so both panes are visible at once with no swipe needed.
 *
 * Hosted the same way as the home list -- as a child of a page's
 * [com.android.launcher3.ShortcutAndWidgetContainer] with `isLockedToGrid = false` -- so it
 * inherits workspace scale, per-page alpha and page translation, and leaves `PagedView` first claim
 * on horizontal drags. See design/architecture-reassessment.md §1.
 *
 * When folded, `Workspace.getPanelCount()` drops to 1, panel 1 ceases to exist, and this view is
 * detached; the established single-pane swipe navigation (§9) takes over unchanged.
 */
class AresAppListView(context: Context, private val launcher: Launcher) : RecyclerView(context) {

    val aresAdapter = AresAppListAdapter(launcher)

    private val storeListener = AllAppsStore.OnUpdateListener { refreshApps() }

    init {
        layoutManager = LinearLayoutManager(context, LinearLayoutManager.VERTICAL, false)
        adapter = aresAdapter
        clipToPadding = false
    }

    /**
     * No-op, for the same reason as [AresHomeListView.setPadding]: ShortcutAndWidgetContainer
     * unconditionally calls setPadding() on every non-widget child each measure pass to centre an
     * icon in its grid cell, which is meaningless for a full-bleed list and would clobber our own
     * padding.
     */
    override fun setPadding(left: Int, top: Int, right: Int, bottom: Int) {
        // Intentionally empty.
    }

    override fun onMeasure(widthSpec: Int, heightSpec: Int) {
        // ShortcutAndWidgetContainer.onMeasure() calls setMeasuredDimension() before measuring its
        // children, so the parent's dimensions are already valid here. Unlike the home panel there
        // is no pinned smartspace on this panel, so the list takes the full height.
        val host = parent as? ViewGroup
        val width = host?.measuredWidth?.takeIf { it > 0 } ?: MeasureSpec.getSize(widthSpec)
        val height = host?.measuredHeight?.takeIf { it > 0 } ?: MeasureSpec.getSize(heightSpec)

        // Mutate the existing lp rather than calling setLayoutParams(), which would trigger a
        // nested requestLayout() from inside a measure pass.
        (layoutParams as? CellLayoutLayoutParams)?.let { lp ->
            lp.isLockedToGrid = false
            lp.x = 0
            lp.y = 0
            lp.width = width
            lp.height = height
        }

        super.onMeasure(
            MeasureSpec.makeMeasureSpec(width, MeasureSpec.EXACTLY),
            MeasureSpec.makeMeasureSpec(height, MeasureSpec.EXACTLY),
        )
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        appsStore()?.addUpdateListener(storeListener)
        refreshApps()
    }

    override fun onDetachedFromWindow() {
        appsStore()?.removeUpdateListener(storeListener)
        super.onDetachedFromWindow()
    }

    /**
     * The apps view is created during `Launcher.setupViews()` and can legitimately be absent when
     * this panel is attached early in a bind, so this is nullable by design rather than defensively.
     */
    private fun appsStore(): AllAppsStore<Launcher>? = launcher.appsView?.appsStore

    fun refreshApps() {
        val store = appsStore() ?: return
        aresAdapter.setApps(
            store.apps.sortedBy { it.title?.toString()?.lowercase() ?: "" },
        )
    }
}
