package app.lawnchair.areslauncher

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.animation.AccelerateInterpolator
import android.view.animation.DecelerateInterpolator
import android.view.animation.OvershootInterpolator
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.graphics.ColorUtils
import com.android.launcher3.Launcher
import com.android.launcher3.R
import com.android.launcher3.views.BaseDragLayer

/**
 * The edit-mode home-grid **column stepper** (owner 2026-08-26): a Material 3 pill at the bottom of
 * the screen — a tonal surface carrying two circular tonal icon buttons (`−` / `+`) around a
 * `N columns` label — that changes how many columns the home grid renders at.
 *
 * It drives [AresHomeListView.setGridColumns], a RENDER-ONLY change: the masonry packs by rank, so
 * the column count never touches cellX/cellY and cannot trip the loader's occupancy purge (that is
 * why this is safe where a normal grid-size change would be dangerous here). The chosen count
 * persists via the `aresHomeColumns` preference and applies to both postures.
 *
 * Lives in the shared [BaseDragLayer] like every other floating edit-mode affordance, added on
 * [attach] (from `enterEditMode`) and removed on [detach] (from `exitEditMode`). A single instance
 * at a time; [attach] is idempotent.
 *
 * **Disabled while a WP folder is open** (owner 2026-08-26): re-columning the grid mid-expansion
 * would fight the reserved run that keeps a folder and its children contiguous, so both buttons go
 * to the M3 disabled state until the folder collapses. The adapter calls [refreshEnabled] whenever a
 * folder opens or closes; it is a no-op when the stepper is not attached.
 */
object AresColumnStepper {

    /** M3 disabled-content opacity. */
    private const val DISABLED_ALPHA = 0.38f

    private var view: View? = null
    private var refresh: (() -> Unit)? = null

    /** Re-evaluate button enabled/disabled state (bounds + whether a folder is open). No-op if detached. */
    fun refreshEnabled() {
        refresh?.invoke()
    }

    /**
     * True if `(rawX, rawY)` (screen coordinates) falls within the pill's **resting** bounds --
     * deliberately ignoring the enter/exit slide translation, so a tap aimed at the settled pill
     * while it is still sliding in still counts as "on the pill". The grid's tap-to-leave-edit-mode
     * handler consults this so a tap on (or aimed at) the stepper never drops the user out of edit
     * mode (owner 2026-08-26). No-op false when detached.
     */
    fun tapWithinRestingPill(rawX: Float, rawY: Float): Boolean {
        val v = view ?: return false
        val loc = IntArray(2)
        v.getLocationOnScreen(loc)
        // getLocationOnScreen reports the CURRENT (translated) position; subtract the slide offset
        // to recover where the pill rests, so the guard holds all through the enter animation.
        val left = loc[0] - v.translationX
        val top = loc[1] - v.translationY
        return rawX >= left && rawX <= left + v.width && rawY >= top && rawY <= top + v.height
    }

    fun attach(launcher: Launcher, list: AresHomeListView) {
        val existing = view
        if (existing != null) {
            // Same activity re-entering edit mode: already showing, don't add a second pill.
            if (existing.context === launcher) return
            // Left over from a PREVIOUS activity that was recreated while edit mode was active: a
            // theme/config-change recreate() never runs exitEditMode, so detach() never fired and the
            // singleton still points at the destroyed activity. Drop the stale pill now (releasing
            // that activity) and attach a fresh one for this one (adversarial panel R1, 2026-08-26).
            clearStale()
        }
        val ctx: Context = launcher
        val density = ctx.resources.displayMetrics.density
        fun dp(v: Float): Int = (v * density).toInt()

        fun color(res: Int): Int = ContextCompat.getColor(ctx, res)
        val surface = color(R.color.materialColorSurfaceContainerHigh)
        // The buttons AND the label are the PRIMARY accent so they read clearly against the neutral
        // pill and the pill reads as one themed control -- secondaryContainer/onSurface were too
        // close to surfaceContainerHigh in this theme (owner 2026-08-26).
        val tonal = color(R.color.materialColorPrimary)
        val onTonal = color(R.color.materialColorOnPrimary)
        val labelColor = tonal // buttons and label share the primary accent

        val label = TextView(ctx).apply {
            setTextColor(labelColor)
            textSize = 15f
            gravity = Gravity.CENTER
            minWidth = dp(104f)
            setPadding(dp(12f), 0, dp(12f), 0)
            letterSpacing = 0.01f
        }

        lateinit var minusBtn: ImageView
        lateinit var plusBtn: ImageView

        fun setEnabled(btn: View, enabled: Boolean) {
            btn.isEnabled = enabled
            btn.isClickable = enabled
            btn.alpha = if (enabled) 1f else DISABLED_ALPHA
        }

        fun labelText(n: Int): CharSequence =
            ctx.resources.getQuantityString(R.plurals.ares_home_columns_label, n, n)

        // Update ENABLED state only (folder open + bounds); never the text -- an animated count
        // change owns the label so this must not clobber the roll mid-flight.
        val updateEnabled = {
            val n = list.currentColumns()
            val folderOpen = list.aresAdapter.expandedWpFolderInfo() != null
            label.alpha = if (folderOpen) DISABLED_ALPHA else 1f
            setEnabled(minusBtn, !folderOpen && n > AresHomeListView.ARES_HOME_COLUMNS_MIN)
            setEnabled(plusBtn, !folderOpen && n < AresHomeListView.ARES_HOME_COLUMNS_MAX)
        }

        // Full refresh (attach + folder open/close): enabled state plus the text set plainly, since
        // the count did not change through the stepper on those paths.
        val refreshFn = {
            updateEnabled()
            label.text = labelText(list.currentColumns())
        }

        fun tonalIconButton(iconRes: Int, delta: Int): ImageView = ImageView(ctx).apply {
            setImageResource(iconRes)
            imageTintList = ColorStateList.valueOf(onTonal)
            scaleType = ImageView.ScaleType.CENTER_INSIDE
            val d = dp(46f)
            minimumWidth = d
            minimumHeight = d
            val padIcon = dp(12f) // inset the 24dp glyph so it reads at ~22dp inside the disc
            setPadding(padIcon, padIcon, padIcon, padIcon)
            isClickable = true
            isFocusable = true
            // M3 filled icon button: a primary-accent disc with a state-layer ripple.
            val fill = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(tonal)
            }
            val mask = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.WHITE)
            }
            background = RippleDrawable(
                ColorStateList.valueOf(ColorUtils.setAlphaComponent(onTonal, 0x33)),
                fill,
                mask,
            )
            setOnClickListener {
                if (!isEnabled) return@setOnClickListener
                val before = list.currentColumns()
                list.setGridColumns(before + delta)
                val after = list.currentColumns()
                updateEnabled()
                bounce(this) // springy press feedback on the tapped disc
                if (after != before) {
                    // Odometer-style roll: old count leaves in the direction of change, new count
                    // enters from the opposite side.
                    animateCount(label, labelText(after), up = after > before)
                }
            }
        }

        minusBtn = tonalIconButton(R.drawable.ic_ares_stepper_remove, -1)
        plusBtn = tonalIconButton(R.drawable.ic_ares_stepper_add, +1)

        val row = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            clipChildren = false // let the count label's vertical roll slide past the row bounds
            clipToPadding = false
            // Consume any tap that lands on the pill but OFF the two discs (the label, the padding).
            // Without this a near-miss falls through to the grid's OnItemTouchListener, which reads a
            // stationary tap on non-item space as "leave edit mode" (AresHomeListView ~2410) -- so the
            // first tap at the stepper would sometimes drop the user out of edit mode (owner 2026-08-26).
            isClickable = true
            val padV = dp(8f)
            val padH = dp(10f)
            setPadding(padH, padV, padH, padV)
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(32f).toFloat()
                setColor(surface)
            }
            elevation = dp(3f).toFloat()
            addView(minusBtn, LinearLayout.LayoutParams(dp(46f), dp(46f)))
            addView(label, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT))
            addView(plusBtn, LinearLayout.LayoutParams(dp(46f), dp(46f)))
        }

        refresh = refreshFn
        refreshFn()

        val lp = BaseDragLayer.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
        ).apply {
            gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            // Sit right above the nav bar so the pill covers the least home content -- higher up it
            // overlapped tiles and blocked grabbing/editing them (owner 2026-08-26, then "lower it
            // ~15% more").
            bottomMargin = dp(2f) + launcher.deviceProfile.insets.bottom
        }
        launcher.dragLayer.addView(row, lp)
        view = row
        // Enter: slide up from below the bottom edge and settle with a bounce (owner 2026-08-26).
        row.translationY = dp(160f).toFloat()
        row.animate()
            .translationY(0f)
            .setDuration(380)
            .setInterpolator(OvershootInterpolator(1.7f))
            .start()
    }

    /**
     * Synchronously drop the current pill with no exit animation, releasing the captured
     * activity/list references. Used when a stale pill is left behind by an activity that was
     * recreated while edit mode was active (see [attach]); it does not play the exit slide.
     */
    private fun clearStale() {
        view?.let {
            it.animate().cancel()
            (it.parent as? ViewGroup)?.removeView(it)
        }
        view = null
        refresh = null
    }

    fun detach() {
        val v = view ?: return
        view = null
        refresh = null
        // Exit: slide back down out of the bottom edge, then remove.
        val marginBottom = (v.layoutParams as? ViewGroup.MarginLayoutParams)?.bottomMargin ?: 0
        val drop = v.height.toFloat() + marginBottom + v.resources.displayMetrics.density * 24f
        v.animate()
            .translationY(drop)
            .setDuration(240)
            .setInterpolator(AccelerateInterpolator())
            .withEndAction { (v.parent as? ViewGroup)?.removeView(v) }
            .start()
    }

    /**
     * Springy squash-and-release on the tapped +/- disc (M3 Expressive press feedback). The disc
     * overshoots slightly past its resting size on the way back, so the pill's row is laid out
     * clipChildren=false/clipToPadding=false -- otherwise the overshoot is cropped at the cell edge
     * (owner 2026-08-26: "the buttons ... getting clipped by padding").
     */
    private fun bounce(v: View) {
        v.animate().cancel()
        v.scaleX = 0.85f
        v.scaleY = 0.85f
        v.animate()
            .scaleX(1f).scaleY(1f)
            .setDuration(260)
            .setInterpolator(OvershootInterpolator(2.5f))
            .start()
    }

    /**
     * Odometer-style count change: the current label slides out (up when the count increases, down
     * when it decreases) and fades, then the new text is set and slides in from the opposite side
     * with a small overshoot -- so the number reads as rolling to its new value.
     */
    private fun animateCount(label: TextView, newText: CharSequence, up: Boolean) {
        val dist = (if (label.height > 0) label.height else label.lineHeight).toFloat() * 0.6f
        label.animate().cancel()
        label.animate()
            .translationY(if (up) -dist else dist)
            .alpha(0f)
            .setDuration(100)
            .withEndAction {
                label.text = newText
                label.translationY = if (up) dist else -dist
                label.alpha = 0f
                // Clean settle -- decelerate to rest, no overshoot/pop (owner 2026-08-26).
                label.animate()
                    .translationY(0f)
                    .alpha(1f)
                    .setDuration(180)
                    .setInterpolator(DecelerateInterpolator())
                    .start()
            }
            .start()
    }
}
