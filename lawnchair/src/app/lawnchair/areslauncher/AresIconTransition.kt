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
 * A whole-surface cross-fade for a live icon re-render (owner 2026-08-27): when the theming toggle
 * flips or the icon shape changes, the icons regenerate in place through `onThemeChanged`, which
 * otherwise pops from the old look to the new one in a single frame. [crossFade] snapshots the
 * drag layer (home grid, app-list pane, and the carousel itself) an instant BEFORE the change, lays
 * that frozen frame over the top, and fades it out while the freshly themed/reshaped icons render
 * underneath -- so the change reads as a smooth dissolve instead of a jump.
 *
 * It is a pure visual overlay: not clickable, so touches fall through to the live views beneath, and
 * the switch/strip the user is holding keeps working during the fade. The snapshot is taken at half
 * resolution (the overlay only fades out, so the softness is invisible) to keep the one-shot bitmap
 * and its draw cheap on the large unfolded panel.
 */
object AresIconTransition {

    private const val TAG = "AresIconTransition"
    private const val FADE_MS = 420L
    // Half-res snapshot: a quarter of the pixels and draw cost of a full-panel capture.
    private const val SNAP_SCALE = 0.5f

    // The in-flight overlay, if any. Only one launcher is active at a time, so a single reference is
    // enough to drop a stale overlay from a rapid re-toggle before starting the next.
    private var active: ImageView? = null

    fun crossFade(launcher: Launcher) {
        val dragLayer: BaseDragLayer<*> = launcher.dragLayer ?: return
        val w = dragLayer.width
        val h = dragLayer.height
        if (w <= 0 || h <= 0) return
        try {
            // Drop any in-flight overlay from a rapid re-toggle so they never stack.
            active?.let {
                it.animate().cancel()
                (it.parent as? ViewGroup)?.removeView(it)
            }
            active = null

            val bw = (w * SNAP_SCALE).toInt().coerceAtLeast(1)
            val bh = (h * SNAP_SCALE).toInt().coerceAtLeast(1)
            val bmp = Bitmap.createBitmap(bw, bh, Bitmap.Config.ARGB_8888)
            Canvas(bmp).apply { scale(SNAP_SCALE, SNAP_SCALE); dragLayer.draw(this) }

            val overlay = ImageView(launcher).apply {
                setImageBitmap(bmp)
                scaleType = ImageView.ScaleType.FIT_XY
                isClickable = false
                isFocusable = false
            }
            active = overlay
            dragLayer.addView(
                overlay,
                BaseDragLayer.LayoutParams(
                    BaseDragLayer.LayoutParams.MATCH_PARENT,
                    BaseDragLayer.LayoutParams.MATCH_PARENT,
                ),
            )
            overlay.animate()
                .alpha(0f)
                .setDuration(FADE_MS)
                .withEndAction {
                    if (active === overlay) active = null
                    (overlay.parent as? ViewGroup)?.removeView(overlay)
                    if (!bmp.isRecycled) bmp.recycle()
                }
                .start()
        } catch (t: Throwable) {
            Log.w(TAG, "icon cross-fade failed", t)
        }
    }
}
