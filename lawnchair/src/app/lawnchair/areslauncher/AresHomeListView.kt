package app.lawnchair.areslauncher

import android.content.Context
import android.view.ViewGroup
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.android.launcher3.Launcher
import com.android.launcher3.celllayout.CellLayoutLayoutParams

/**
 * Vertical, continuously-scrolling list of home-screen items -- the Strategy D
 * replacement for CellLayout's grid inside Workspace's single page. See
 * design/vertical-home-strategies.md and design/architecture-reassessment.md.
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
class AresHomeListView(context: Context, launcher: Launcher) : RecyclerView(context) {

    val aresAdapter = AresHomeAdapter(launcher)

    init {
        layoutManager = LinearLayoutManager(context, LinearLayoutManager.VERTICAL, false)
        adapter = aresAdapter
        clipToPadding = false
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

    override fun onMeasure(widthSpec: Int, heightSpec: Int) {
        // ShortcutAndWidgetContainer.onMeasure() calls setMeasuredDimension() *before* measuring
        // its children, so the parent's dimensions are already valid here. Size to them, and
        // sync the CellLayoutLayoutParams so layoutChild() -- which positions us from lp.x/y/
        // width/height rather than from our measured size -- places us full-bleed.
        val host = parent as? ViewGroup
        val width = host?.measuredWidth?.takeIf { it > 0 } ?: MeasureSpec.getSize(widthSpec)
        val height = host?.measuredHeight?.takeIf { it > 0 } ?: MeasureSpec.getSize(heightSpec)

        // Mutating the existing lp's fields rather than calling setLayoutParams() avoids
        // triggering a nested requestLayout() from inside a measure pass.
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
}
