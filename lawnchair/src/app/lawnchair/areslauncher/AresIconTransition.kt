package app.lawnchair.areslauncher

import android.animation.ValueAnimator
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Path
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import android.widget.ImageView
import com.android.launcher3.Launcher
import com.android.launcher3.views.BaseDragLayer
import kotlin.math.hypot

/**
 * A radial-reveal transition for a live icon re-render (owner 2026-08-27): when the theming toggle
 * flips or the icon shape changes, the icons regenerate in place through `onThemeChanged`. Rather
 * than pop in a single frame, [reveal] snapshots the target grid an instant BEFORE the change, lays
 * that frozen frame exactly over the grid, then opens a circular hole in it that EXPANDS from the
 * centre, uncovering the freshly themed/reshaped icons underneath -- a radial wave of the new look.
 *
 * **Why it is scoped to one grid view.** A launcher window is transparent (the wallpaper is a
 * separate window behind it, not part of the drag layer), so a snapshot has a see-through
 * background and cannot occlude the live views -- if a snapshotted view reflows, the old frame and
 * the new one both show and it looks doubled. The home grid does NOT move on a theme/shape change,
 * so its snapshot lines up pixel-for-pixel with the live grid and the hole reads as a crisp
 * recolour/reshape wave. The app-list pane (a separate view that reflows) is deliberately left out
 * and simply updates; the reveal plays over the grid the user is looking at.
 *
 * It is a pure visual overlay: not clickable (touches fall through), the snapshot is half-resolution
 * to stay cheap, only one is ever in flight, and the whole thing is wrapped in try/catch so it can
 * never break the toggle.
 */
object AresIconTransition {

    private const val TAG = "AresIconTransition"
    private const val REVEAL_MS = 460L
    // Half-res snapshot: a quarter of the pixels and draw cost of a full-grid capture.
    private const val SNAP_SCALE = 0.5f

    // The in-flight overlay, if any. Only one launcher is active at a time, so a single reference is
    // enough to drop a stale overlay from a rapid re-toggle before starting the next.
    private var active: RevealOverlay? = null

    /** Play the radial reveal over [target] (the home grid). No-op if it has no size yet. */
    fun reveal(launcher: Launcher, target: View) {
        val dragLayer: BaseDragLayer<*> = launcher.dragLayer ?: return
        val tw = target.width
        val th = target.height
        if (tw <= 0 || th <= 0) return
        try {
            // Drop any in-flight overlay from a rapid re-toggle so they never stack.
            active?.let {
                it.anim?.cancel()
                (it.parent as? ViewGroup)?.removeView(it)
            }
            active = null

            val bw = (tw * SNAP_SCALE).toInt().coerceAtLeast(1)
            val bh = (th * SNAP_SCALE).toInt().coerceAtLeast(1)
            val bmp = Bitmap.createBitmap(bw, bh, Bitmap.Config.ARGB_8888)
            Canvas(bmp).apply { scale(SNAP_SCALE, SNAP_SCALE); target.draw(this) }

            // The target's top-left in drag-layer coordinates, so the overlay sits exactly over it.
            val xy = intArrayOf(0, 0)
            dragLayer.getDescendantCoordRelativeToSelf(target, xy)

            val overlay = RevealOverlay(launcher).apply {
                setImageBitmap(bmp)
                scaleType = ImageView.ScaleType.FIT_XY
                isClickable = false
                isFocusable = false
                cx = tw / 2f
                cy = th / 2f
            }
            active = overlay
            val lp = BaseDragLayer.LayoutParams(tw, th).apply {
                customPosition = true
                x = xy[0]
                y = xy[1]
            }
            dragLayer.addView(overlay, lp)

            val maxRadius = hypot(tw / 2f, th / 2f)
            overlay.anim = ValueAnimator.ofFloat(0f, maxRadius).apply {
                duration = REVEAL_MS
                interpolator = DecelerateInterpolator(1.5f)
                addUpdateListener {
                    overlay.holeRadius = it.animatedValue as Float
                    overlay.invalidate()
                }
                doOnEnd {
                    if (active === overlay) active = null
                    (overlay.parent as? ViewGroup)?.removeView(overlay)
                    if (!bmp.isRecycled) bmp.recycle()
                }
                start()
            }
        } catch (t: Throwable) {
            Log.w(TAG, "icon reveal failed", t)
        }
    }

    // Runs [action] when the animator ends OR is cancelled, exactly once, without extra androidx deps.
    private inline fun ValueAnimator.doOnEnd(crossinline action: () -> Unit) {
        addListener(object : android.animation.AnimatorListenerAdapter() {
            private var done = false
            override fun onAnimationEnd(animation: android.animation.Animator) {
                if (!done) { done = true; action() }
            }
            override fun onAnimationCancel(animation: android.animation.Animator) {
                if (!done) { done = true; action() }
            }
        })
    }

    /**
     * Draws its image everywhere EXCEPT inside a circle of [holeRadius] centred at ([cx],[cy]),
     * using an inverse clip. As the radius grows, the hole opens and the live grid beneath shows
     * through -- a radial expansion of the new state.
     */
    private class RevealOverlay(context: android.content.Context) : ImageView(context) {
        var holeRadius = 0f
        var cx = 0f
        var cy = 0f
        var anim: ValueAnimator? = null
        private val holePath = Path()

        override fun draw(canvas: Canvas) {
            if (holeRadius <= 0f) {
                super.draw(canvas)
                return
            }
            val save = canvas.save()
            holePath.reset()
            holePath.addCircle(cx, cy, holeRadius, Path.Direction.CW)
            holePath.fillType = Path.FillType.INVERSE_EVEN_ODD
            canvas.clipPath(holePath)
            super.draw(canvas)
            canvas.restoreToCount(save)
        }
    }
}
