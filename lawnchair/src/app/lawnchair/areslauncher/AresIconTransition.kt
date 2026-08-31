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

    // Safety cap: if a frozen overlay's bind-complete signal never arrives, wipe it anyway so the
    // grid can never stay covered. Generous, because an icon-pack reload of a large pack is slow.
    private const val FREEZE_TIMEOUT_MS = 6000L

    // The in-flight overlay, if any. Only one launcher is active at a time, so a single reference is
    // enough to drop a stale overlay from a rapid re-toggle before starting the next.
    private var active: RevealOverlay? = null

    // A pinned, not-yet-wiped snapshot from [freeze], waiting for [playFrozen] (bind-complete) or its
    // safety timeout. Held separately from [active] because it is static (holeRadius stays 0) until
    // played, and can be replaced by a rapid re-pick before it ever animates.
    private var frozen: RevealOverlay? = null
    private var frozenTimeout: Runnable? = null
    private var frozenTarget: View? = null

    /**
     * Cancel any in-flight reveal AND any pinned [freeze] overlay, releasing their bitmaps. Called on
     * activity destroy (and on edit-mode exit) so a reveal still animating -- or a freeze still
     * waiting for a bind that will never come -- never pins a destroyed activity via a static field
     * (the same leak class the carousel guards as F2, nightly 2026-08-28 finding 5). Safe when nothing
     * is pending. Main thread.
     */
    fun cancel() {
        clearFrozen()
        val o = active ?: return
        active = null
        // anim.cancel() fires onAnimationCancel -> the doOnEnd cleanup (remove view + recycle bmp).
        o.anim?.cancel()
        (o.parent as? ViewGroup)?.removeView(o)
    }

    /** Play the radial reveal over [target] (the home grid). No-op if it has no size yet. */
    fun reveal(launcher: Launcher, target: View) {
        val overlay = addSnapshotOverlay(launcher, target) ?: return
        startWipe(overlay)
    }

    /**
     * Pin the CURRENT grid look under a static snapshot, to be uncovered later by [playFrozen]. For an
     * ASYNC icon change (icon pack): the new icons stream in over a model reload, so revealing at tap
     * time (like [reveal]) wipes to the OLD icons and the swap pops in afterwards -- and mid-reload the
     * grid shows a piecemeal half-swapped state. Freezing the old frame hides that churn; [playFrozen]
     * at bind-complete then wipes to the finished new grid. A safety timeout wipes anyway if the
     * bind-complete signal never arrives, so the grid can never stay covered.
     */
    fun freeze(launcher: Launcher, target: View) {
        clearFrozen()
        val overlay = addSnapshotOverlay(launcher, target) ?: return
        frozen = overlay
        frozenTarget = target
        frozenTimeout = Runnable { playFrozen(launcher, target) }.also {
            target.postDelayed(it, FREEZE_TIMEOUT_MS)
        }
    }

    /**
     * Uncover a [freeze] overlay with the radial wipe. Called from the launcher's bind-complete hook
     * once the reloaded icons are on the grid. No-op if nothing is frozen (so an unrelated bind, or a
     * second bind pass, does nothing).
     */
    fun playFrozen(launcher: Launcher, target: View?) {
        val overlay = frozen ?: return
        frozen = null
        frozenTimeout?.let { frozenTarget?.removeCallbacks(it) }
        frozenTimeout = null
        frozenTarget = null
        // A rapid re-pick could have started another reveal in the meantime; drop it so they never
        // stack, then wipe this frozen frame.
        active?.takeIf { it !== overlay }?.let {
            it.anim?.cancel()
            (it.parent as? ViewGroup)?.removeView(it)
        }
        startWipe(overlay)
    }

    private fun clearFrozen() {
        val o = frozen ?: return
        frozen = null
        frozenTimeout?.let { frozenTarget?.removeCallbacks(it) }
        frozenTimeout = null
        frozenTarget = null
        (o.parent as? ViewGroup)?.removeView(o)
        o.bmp?.let { if (!it.isRecycled) it.recycle() }
    }

    /**
     * Snapshots [target] into a half-res bitmap and adds a static [RevealOverlay] (hole closed) over it
     * in the drag layer, pixel-aligned to the grid. Returns the overlay, or null if the grid has no
     * size yet or anything throws -- a reveal must never break the change that triggered it.
     */
    private fun addSnapshotOverlay(launcher: Launcher, target: View): RevealOverlay? {
        val dragLayer: BaseDragLayer<*> = launcher.dragLayer ?: return null
        val tw = target.width
        val th = target.height
        if (tw <= 0 || th <= 0) return null
        return try {
            val bw = (tw * SNAP_SCALE).toInt().coerceAtLeast(1)
            val bh = (th * SNAP_SCALE).toInt().coerceAtLeast(1)
            val bmp = Bitmap.createBitmap(bw, bh, Bitmap.Config.ARGB_8888)
            Canvas(bmp).apply { scale(SNAP_SCALE, SNAP_SCALE); target.draw(this) }

            // The target's top-left in drag-layer coordinates, so the overlay sits exactly over it.
            val xy = intArrayOf(0, 0)
            dragLayer.getDescendantCoordRelativeToSelf(target, xy)

            val overlay = RevealOverlay(launcher).apply {
                setImageBitmap(bmp)
                this.bmp = bmp
                scaleType = ImageView.ScaleType.FIT_XY
                isClickable = false
                isFocusable = false
                cx = tw / 2f
                cy = th / 2f
            }
            val lp = BaseDragLayer.LayoutParams(tw, th).apply {
                customPosition = true
                x = xy[0]
                y = xy[1]
            }
            dragLayer.addView(overlay, lp)
            overlay
        } catch (t: Throwable) {
            Log.w(TAG, "icon reveal snapshot failed", t)
            null
        }
    }

    /** Opens the hole on an already-added [overlay], revealing the live grid, then cleans up. */
    private fun startWipe(overlay: RevealOverlay) {
        // Drop any in-flight overlay so they never stack.
        active?.takeIf { it !== overlay }?.let {
            it.anim?.cancel()
            (it.parent as? ViewGroup)?.removeView(it)
        }
        active = overlay
        val maxRadius = hypot(overlay.cx, overlay.cy)
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
                overlay.bmp?.let { if (!it.isRecycled) it.recycle() }
            }
            start()
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
        // The snapshot this overlay shows; held so cleanup can recycle it on either the reveal or the
        // freeze->play path.
        var bmp: Bitmap? = null
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
