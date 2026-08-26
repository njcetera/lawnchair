package app.lawnchair.areslauncher

import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
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
 * The edit-mode home-grid **column stepper** (owner 2026-08-26): a small `−  N columns  +` pill at
 * the bottom of the screen, shown only while edit mode is active, that changes how many columns the
 * home grid renders at.
 *
 * It drives [AresHomeListView.setGridColumns], which is a RENDER-ONLY change — the masonry packs by
 * rank, so the column count never touches cellX/cellY and cannot trip the loader's occupancy purge
 * (that is why this is safe where a normal grid-size change would be dangerous here). The chosen
 * count persists via the `aresHomeColumns` preference and applies to both postures.
 *
 * Lives in the shared [BaseDragLayer] like every other floating edit-mode affordance, added on
 * [attach] (from `enterEditMode`) and removed on [detach] (from `exitEditMode`). A single instance
 * at a time; [attach] is idempotent.
 */
object AresColumnStepper {

    private var view: View? = null

    fun isAttached(): Boolean = view != null

    fun attach(launcher: Launcher, list: AresHomeListView) {
        if (view != null) return
        val ctx: Context = launcher
        val density = ctx.resources.displayMetrics.density
        fun dp(v: Float): Int = (v * density).toInt()

        val textColor = workspaceTextColor(ctx)

        val label = TextView(ctx).apply {
            setTextColor(textColor)
            textSize = 15f
            gravity = Gravity.CENTER
            minWidth = dp(96f)
            setPadding(dp(10f), 0, dp(10f), 0)
        }

        lateinit var minusBtn: TextView
        lateinit var plusBtn: TextView

        fun refresh() {
            val n = list.currentColumns()
            label.text = ctx.resources.getQuantityString(
                R.plurals.ares_home_columns_label, n, n,
            )
            minusBtn.isEnabled = n > AresHomeListView.ARES_HOME_COLUMNS_MIN
            plusBtn.isEnabled = n < AresHomeListView.ARES_HOME_COLUMNS_MAX
            minusBtn.alpha = if (minusBtn.isEnabled) 1f else 0.35f
            plusBtn.alpha = if (plusBtn.isEnabled) 1f else 0.35f
        }

        fun makeButton(glyph: String, delta: Int): TextView = TextView(ctx).apply {
            text = glyph
            setTextColor(textColor)
            textSize = 22f
            gravity = Gravity.CENTER
            val size = dp(44f)
            minWidth = size
            minHeight = size
            isClickable = true
            isFocusable = true
            setOnClickListener {
                list.setGridColumns(list.currentColumns() + delta)
                refresh()
            }
        }

        minusBtn = makeButton("−", -1) // MINUS SIGN
        plusBtn = makeButton("+", +1)

        val row = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(6f), dp(4f), dp(6f), dp(4f))
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(28f).toFloat()
                setColor(pillColor(ctx))
            }
            elevation = dp(6f).toFloat()
            addView(minusBtn)
            addView(label)
            addView(plusBtn)
        }
        refresh()

        val lp = BaseDragLayer.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
        ).apply {
            gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            bottomMargin = dp(28f) + launcher.deviceProfile.insets.bottom
        }
        launcher.dragLayer.addView(row, lp)
        view = row
    }

    fun detach() {
        view?.let { (it.parent as? ViewGroup)?.removeView(it) }
        view = null
    }

    private fun workspaceTextColor(ctx: Context): Int {
        val tv = TypedValue()
        return if (ctx.theme.resolveAttribute(R.attr.workspaceTextColor, tv, true)) {
            if (tv.resourceId != 0) ContextCompat.getColor(ctx, tv.resourceId) else tv.data
        } else {
            0xFFFFFFFF.toInt()
        }
    }

    /** A slightly translucent surface so the pill reads as chrome over the wallpaper. */
    private fun pillColor(ctx: Context): Int {
        val surface = try {
            ContextCompat.getColor(ctx, R.color.materialColorSurfaceContainerHigh)
        } catch (t: Throwable) {
            0xFF202124.toInt()
        }
        return ColorUtils.setAlphaComponent(surface, 0xF0)
    }
}
