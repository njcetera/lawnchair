package app.lawnchair.areslauncher

import android.view.MotionEvent
import android.view.View

/**
 * Drives the finger-tracking **jelly** warp on a home app icon (owner 2026-08-25): "when you touch
 * the icon it pulls up along where your finger is, like it's dragging along your finger." The actual
 * deformation lives in [AresJellyContainer] (the tile's own container, which redraws its icon+label
 * as a mesh); this only translates touches into anchor/finger/release calls on it.
 *
 * ## Observe-only
 *
 * The home grid's touch pipeline is finicky (scroll, long-press-to-edit, folder dwell, DragStarter),
 * so this listener **never consumes** — it returns false for every event and only reads them. The
 * click, the long-press and the RecyclerView's own scroll all keep working; when a finger travels
 * far enough to be a scroll, the RecyclerView intercepts and this tile receives ACTION_CANCEL, which
 * releases the warp. So the jelly tracks the finger only within the small pre-scroll window — the
 * range the owner chose.
 *
 * [suppressed] gates it OFF for a press that will not launch: in edit mode a tap does not open the
 * app, and the tile is being managed (dragged/reordered), so a jelly warp there would be wrong.
 */
object AresIconPress {

    /** Attach the jelly press to [view] (an app icon whose parent is an [AresJellyContainer]). */
    fun attach(view: View, suppressed: () -> Boolean) {
        var tracking = false // an eligible press is down (not edit mode)
        var warping = false // the warp has actually started (deferred to first movement)
        var anchorX = 0f
        var anchorY = 0f

        view.setOnTouchListener { v, e ->
            val jelly = v.parent as? AresJellyContainer
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    if (jelly != null && !suppressed()) {
                        tracking = true
                        warping = false
                        // The icon fills its container, so container coords = local + the view's
                        // offset within the container.
                        anchorX = e.x + v.left
                        anchorY = e.y + v.top
                    } else {
                        tracking = false
                    }
                }

                MotionEvent.ACTION_MOVE -> {
                    if (tracking && jelly != null) {
                        // Defer the snapshot to the first real movement, so a plain launch-tap (no
                        // drag) never pays for a capture -- the jelly is a drag-follow, not a press.
                        if (!warping) {
                            jelly.startJelly(anchorX, anchorY)
                            warping = true
                        }
                        jelly.updateFinger(e.x + v.left, e.y + v.top)
                    }
                }

                MotionEvent.ACTION_UP,
                MotionEvent.ACTION_CANCEL,
                -> {
                    tracking = false
                    if (warping) {
                        warping = false
                        jelly?.releaseJelly()
                    }
                }
            }
            // NEVER consume: click, long-press and scroll must all still see the event.
            false
        }
    }
}
