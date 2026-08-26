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

    fun isAttached(): Boolean = view != null

    /** Re-evaluate button enabled/disabled state (bounds + whether a folder is open). No-op if detached. */
    fun refreshEnabled() {
        refresh?.invoke()
    }

    fun attach(launcher: Launcher, list: AresHomeListView) {
        if (view != null) return
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
        val labelColor = color(R.color.materialColorPrimary)

        val label = TextView(ctx).apply {
            setTextColor(labelColor)
            textSize = 15f
            gravity = Gravity.CENTER
            minWidth = dp(104f)
            setPadding(dp(12f), 0, dp(12f), 0)
            letterSpacing = 0.01f
        }

        lateinit var minusBtn: TextView
        lateinit var plusBtn: TextView

        fun setEnabled(btn: TextView, enabled: Boolean) {
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

        fun tonalIconButton(glyph: String, delta: Int): TextView = TextView(ctx).apply {
            text = glyph
            setTextColor(onTonal)
            textSize = 20f
            gravity = Gravity.CENTER
            val d = dp(46f)
            minWidth = d
            minHeight = d
            isClickable = true
            isFocusable = true
            // M3 filled-tonal icon button: a secondary-container disc with a state-layer ripple.
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

        minusBtn = tonalIconButton("−", -1) // MINUS SIGN
        plusBtn = tonalIconButton("+", +1)

        val row = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            clipChildren = false // let the count label's vertical roll slide past the row bounds
            clipToPadding = false
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
            bottomMargin = dp(36f) + launcher.deviceProfile.insets.bottom
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
