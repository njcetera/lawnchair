package app.lawnchair.areslauncher

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.view.animation.OvershootInterpolator
import android.widget.FrameLayout
import kotlin.math.exp

/**
 * A home-grid tile container that can render its own contents (icon + label) as **jelly** — a
 * localized elastic warp that follows the finger (owner 2026-08-25): "when you touch the icon it
 * pulls up along where your finger is, like it's dragging along your finger... drag up that portion
 * of the icon."
 *
 * The whole tile is snapshotted to a bitmap on press and redrawn through [Canvas.drawBitmapMesh] on
 * a grid of vertices. The vertices near the finger's touch point are pulled toward the finger's
 * current position (Gaussian falloff, so the touched region stretches and the rest stays anchored);
 * on release they spring back. This is self-contained — the container only changes HOW it draws its
 * own children, so there is no overlay to strand and no child state to restore: [warping] false
 * simply falls back to the normal `dispatchDraw`.
 *
 * The finger can only travel a few millimetres before the `RecyclerView` claims the gesture as a
 * scroll (and this tile receives ACTION_CANCEL → [releaseJelly]); that is the accepted range for the
 * effect (owner chose the "small elastic tug" scope). Widgets and edit mode never start a warp
 * (see [AresIconPress]), so for them this behaves as a plain `FrameLayout`.
 */
class AresJellyContainer(context: Context) : FrameLayout(context) {

    private val cols = 8
    private val rows = 8
    private val vertCount = (cols + 1) * (rows + 1)
    private val restVerts = FloatArray(vertCount * 2)
    private val verts = FloatArray(vertCount * 2)

    private var snapshot: Bitmap? = null
    private var warping = false
    private var anchorX = 0f // the touch-down point, centre of the falloff
    private var anchorY = 0f
    private var fingerX = 0f // the finger's current point
    private var fingerY = 0f
    private var pull = 0f // 0..1 elastic amount, springs to 0 on release
    private var releaseAnim: ValueAnimator? = null

    private val meshPaint = Paint(Paint.FILTER_BITMAP_FLAG).apply { isAntiAlias = true }

    /** How far a vertex can be dragged, as a fraction of tile width, before the pull is clamped. */
    private val maxPullFrac = 0.35f

    /** Begin a jelly warp anchored at ([downX],[downY]) in this container's coordinates. */
    fun startJelly(downX: Float, downY: Float) {
        if (width <= 0 || height <= 0) return
        releaseAnim?.cancel()
        releaseAnim = null
        val bmp = ensureSnapshotBitmap()
        // Capture the tile exactly as it renders right now (icon + label), on a fresh transparent
        // bitmap, then warp THAT instead of the live children.
        bmp.eraseColor(0)
        super.dispatchDraw(Canvas(bmp))
        buildRestVerts()
        anchorX = downX
        anchorY = downY
        fingerX = downX
        fingerY = downY
        pull = 1f
        warping = true
        computeVerts()
        invalidate()
    }

    /** Update the finger's current position (container coordinates) mid-warp. */
    fun updateFinger(x: Float, y: Float) {
        if (!warping) return
        fingerX = x
        fingerY = y
        computeVerts()
        invalidate()
    }

    /**
     * Abandon any warp IMMEDIATELY (no spring), back to normal drawing. Called when the tile is
     * rebound/recycled, so a warp can never strand across a content change (the ghost-view trap).
     */
    fun cancelJelly() {
        releaseAnim?.cancel()
        releaseAnim = null
        if (warping) {
            warping = false
            pull = 0f
            invalidate()
        }
    }

    /** Release the warp: spring the vertices back to rest, then drop the snapshot. */
    fun releaseJelly() {
        if (!warping || releaseAnim != null) return
        val from = pull
        releaseAnim = ValueAnimator.ofFloat(from, 0f).apply {
            duration = 340
            // A gentle elastic settle -- a little overshoot so it wobbles home, not a dead stop.
            interpolator = OvershootInterpolator(1.1f)
            addUpdateListener {
                pull = it.animatedValue as Float
                computeVerts()
                invalidate()
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    warping = false
                    releaseAnim = null
                    invalidate()
                }
            })
            start()
        }
    }

    private fun ensureSnapshotBitmap(): Bitmap {
        val existing = snapshot
        if (existing != null && !existing.isRecycled &&
            existing.width == width && existing.height == height
        ) {
            return existing
        }
        existing?.recycle()
        return Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).also { snapshot = it }
    }

    private fun buildRestVerts() {
        var i = 0
        for (r in 0..rows) {
            val y = height * r / rows.toFloat()
            for (c in 0..cols) {
                restVerts[i] = width * c / cols.toFloat()
                restVerts[i + 1] = y
                i += 2
            }
        }
        System.arraycopy(restVerts, 0, verts, 0, verts.size)
    }

    private fun computeVerts() {
        val maxPull = width * maxPullFrac
        var dx = (fingerX - anchorX) * pull
        var dy = (fingerY - anchorY) * pull
        // Clamp so a fast pre-CANCEL scroll can't stretch the tile absurdly before it releases.
        dx = dx.coerceIn(-maxPull, maxPull)
        dy = dy.coerceIn(-maxPull, maxPull)
        val sigma = width * 0.30f
        val twoSigmaSq = 2f * sigma * sigma
        var i = 0
        while (i < restVerts.size) {
            val rx = restVerts[i]
            val ry = restVerts[i + 1]
            val ax = rx - anchorX
            val ay = ry - anchorY
            val w = exp((-(ax * ax + ay * ay) / twoSigmaSq).toDouble()).toFloat()
            verts[i] = rx + dx * w
            verts[i + 1] = ry + dy * w
            i += 2
        }
    }

    override fun dispatchDraw(canvas: Canvas) {
        val bmp = snapshot
        if (warping && bmp != null && !bmp.isRecycled) {
            canvas.drawBitmapMesh(bmp, cols, rows, verts, 0, null, 0, meshPaint)
        } else {
            super.dispatchDraw(canvas)
        }
    }
}
