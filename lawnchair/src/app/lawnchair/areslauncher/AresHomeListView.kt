package app.lawnchair.areslauncher

import android.content.Context
import android.view.MotionEvent
import android.view.ViewGroup
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.android.launcher3.Launcher
import com.android.launcher3.R
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
     */
    private var gestureStartedOnEmptySpace = false

    override fun onInterceptTouchEvent(e: MotionEvent): Boolean {
        if (e.actionMasked == MotionEvent.ACTION_DOWN) {
            gestureStartedOnEmptySpace = findChildViewUnder(e.x, e.y) == null
        }
        if (gestureStartedOnEmptySpace) return false
        return super.onInterceptTouchEvent(e)
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (gestureStartedOnEmptySpace) return false
        return super.onTouchEvent(e)
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
}
