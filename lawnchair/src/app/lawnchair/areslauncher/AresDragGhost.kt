package app.lawnchair.areslauncher

import android.graphics.Bitmap
import android.graphics.Canvas
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import com.android.launcher3.Launcher
import com.android.launcher3.views.BaseDragLayer

/**
 * Keeps the tile the finger is holding **visible above an open folder** (§18).
 *
 * > *"holding an app in edit mode and adding it to a folder opens the folder correctly but the app
 * > icon is now behind the folder"*
 *
 * ## Why the dragged tile cannot simply be raised
 *
 * The in-grid drag is an [androidx.recyclerview.widget.ItemTouchHelper] reorder, so the thing being
 * dragged is a **child of [AresHomeListView]**, buried under
 * `ShortcutAndWidgetContainer → CellLayout → Workspace → DragLayer`. `ItemTouchHelper` raises it
 * within the RecyclerView by giving it elevation, which orders it against its *siblings* and
 * nothing else. An opened folder is added straight to the `DragLayer`, several levels above that
 * whole subtree, so no amount of elevation on a list row can put it in front — and the row is
 * clipped to the list either way.
 *
 * The only place a view can be above the folder is the `DragLayer` itself, which is exactly where
 * stock puts its `DragView` for a `DragController` drag. This is the same idea for the drag
 * pipeline that has no `DragView`: a still image of the tile, parked in the DragLayer above the
 * folder, moved with the drag, while the real row is held at `alpha = 0` so there are not two of it.
 *
 * ## A still image, not the view
 *
 * Re-parenting the live row into the DragLayer would take it out of the RecyclerView mid-drag,
 * which `ItemTouchHelper` is in the middle of animating and which would retire the holder the drop
 * has to resolve against. A bitmap has no such coupling: it cannot be recycled underneath us, it
 * cannot receive a touch, and it costs one icon-sized allocation for the life of one folder
 * preview.
 */
object AresDragGhost {

    private const val TAG = "AresDragGhost"

    /**
     * How far above everything else in the DragLayer the ghost sits.
     *
     * `ViewGroup` draws children in Z order once any of them has a non-zero Z, so an explicit
     * elevation is what guarantees the ghost is in front of the folder rather than relying on the
     * order the two happened to be added in — which a later re-add of either would silently
     * reverse.
     */
    private const val GHOST_ELEVATION_DP = 24f

    private var ghost: ImageView? = null

    /** The real row being stood in for, held transparent while the ghost is up. */
    private var hiddenTile: View? = null

    private var halfWidth = 0f
    private var halfHeight = 0f

    /**
     * Puts a still image of [tile] into the DragLayer, above everything already in it, and hides
     * the real row.
     *
     * @return true when the ghost is up. False leaves nothing behind and the caller simply gets the
     *   previous behaviour — the tile drawn under the folder — rather than a half-applied state.
     */
    @JvmStatic
    fun show(launcher: Launcher, tile: View?): Boolean {
        hide()
        if (tile == null || tile.width <= 0 || tile.height <= 0) return false
        val dragLayer = launcher.dragLayer ?: return false

        val bitmap = try {
            Bitmap.createBitmap(tile.width, tile.height, Bitmap.Config.ARGB_8888).also {
                tile.draw(Canvas(it))
            }
        } catch (e: OutOfMemoryError) {
            // An icon-sized bitmap, so this is close to impossible -- but a drag that dies here
            // would take the whole gesture with it, and being one tile short of perfect is a much
            // better outcome than a crash mid-placement.
            Log.e(TAG, "no memory for the drag ghost; the tile will stay behind the folder", e)
            return false
        }

        val view = ImageView(launcher).apply {
            setImageBitmap(bitmap)
            // Never a touch target: the gesture belongs to the RecyclerView for its whole life, and
            // a view in the DragLayer that consumed a move would end the drag it is illustrating.
            isClickable = false
            isFocusable = false
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            elevation = GHOST_ELEVATION_DP * launcher.resources.displayMetrics.density
            // The pick-up swell is on the row; the ghost stands in for it, so it wears it too.
            scaleX = tile.scaleX
            scaleY = tile.scaleY
        }
        val lp = BaseDragLayer.LayoutParams(tile.width, tile.height)
        lp.customPosition = true
        lp.x = 0
        lp.y = 0
        dragLayer.addView(view, lp)

        ghost = view
        hiddenTile = tile
        halfWidth = tile.width / 2f
        halfHeight = tile.height / 2f
        tile.alpha = 0f
        Log.i(TAG, "ghost up at ${tile.width}x${tile.height}")
        return true
    }

    /** Centres the ghost on [x],[y], given in DragLayer coordinates. */
    @JvmStatic
    fun moveTo(x: Float, y: Float) {
        val view = ghost ?: return
        view.translationX = x - halfWidth
        view.translationY = y - halfHeight
    }

    /** Takes the ghost down and gives the real row back. Safe to call at any time. */
    @JvmStatic
    fun hide() {
        ghost?.let { view ->
            (view.parent as? ViewGroup)?.removeView(view)
            view.setImageDrawable(null)
        }
        // Restored unconditionally, including on a row that has since been recycled: alpha 1 is
        // the resting value for every row, so putting it back can never be wrong, whereas leaving
        // a row at 0 makes an app invisible on the home screen with nothing to explain it.
        hiddenTile?.alpha = 1f
        ghost = null
        hiddenTile = null
    }
}
