package app.lawnchair.areslauncher

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
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
        val onSurface = color(R.color.materialColorOnSurface)
        val tonal = color(R.color.materialColorSecondaryContainer)
        val onTonal = color(R.color.materialColorOnSecondaryContainer)

        val label = TextView(ctx).apply {
            setTextColor(onSurface)
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

        val refreshFn = {
            val n = list.currentColumns()
            val folderOpen = list.aresAdapter.expandedWpFolderInfo() != null
            label.text = ctx.resources.getQuantityString(R.plurals.ares_home_columns_label, n, n)
            label.alpha = if (folderOpen) DISABLED_ALPHA else 1f
            setEnabled(minusBtn, !folderOpen && n > AresHomeListView.ARES_HOME_COLUMNS_MIN)
            setEnabled(plusBtn, !folderOpen && n < AresHomeListView.ARES_HOME_COLUMNS_MAX)
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
                list.setGridColumns(list.currentColumns() + delta)
                refresh?.invoke()
            }
        }

        minusBtn = tonalIconButton("−", -1) // MINUS SIGN
        plusBtn = tonalIconButton("+", +1)

        val row = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
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
    }

    fun detach() {
        view?.let { (it.parent as? ViewGroup)?.removeView(it) }
        view = null
        refresh = null
    }
}
