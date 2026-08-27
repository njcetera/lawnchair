package app.lawnchair.areslauncher

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.view.animation.AccelerateInterpolator
import android.view.animation.DecelerateInterpolator
import android.view.animation.OvershootInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.Switch
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.graphics.ColorUtils
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import app.lawnchair.LawnchairLauncher
import app.lawnchair.icons.shape.IconShape
import app.lawnchair.preferences2.PreferenceManager2
import com.android.launcher3.Launcher
import com.android.launcher3.R
import com.android.launcher3.views.BaseDragLayer
import com.patrykmichalik.opto.core.firstBlocking
import kotlin.math.abs
import kotlinx.coroutines.launch

/**
 * The edit-mode **personalization carousel** (owner 2026-08-26): a looping, swipeable row of
 * Material 3 pills at the bottom of the screen, one visible at a time, with a dots indicator above.
 * Each pill is one personalization control; swipe left/right ON THE PILL to move between them.
 *
 * Registry-driven ([pageBuilders]) so adding a page is one entry. The initial pages are the home
 * **column** stepper and the **icon tint** control (see `design/personalization-carousel.md`).
 *
 * Lives in the shared [BaseDragLayer] like every other floating edit-mode affordance, added on
 * [attach] (from `enterEditMode`) and removed on [detach] (from `exitEditMode`). One instance at a
 * time; [attach] is idempotent for the same activity and replaces a pill left by a recreated one.
 *
 * The horizontal page swipe is CONSUMED within the carousel bounds (see [PillPager]) so it does not
 * fight `AresPaneSwipeController`, which claims horizontal drags on the home screen. Taps on a pill's
 * own controls still work; only horizontal drags page.
 */
object AresEditCarousel {

    /** M3 disabled-content opacity. */
    private const val DISABLED_ALPHA = 0.38f

    private var view: View? = null
    private var pills: List<Pill> = emptyList()

    /** One personalization pill: its control view plus a hook to re-evaluate enabled/disabled state. */
    private class Pill(val view: View, val refreshEnabled: () -> Unit)

    /**
     * Ordered registry of carousel PAGES. Each builder returns the pills shown together on one page
     * (laid out side by side). One page == one swipe position == one dot. Columns is a single-pill
     * page; icon tint is a two-pill page (a toggle pill + an amount pill). Append a builder to add a
     * page.
     */
    private val pageBuilders: List<(Launcher, AresHomeListView) -> List<Pill>> = listOf(
        ::buildColumnPage,
        ::buildTintPage,
        ::buildShapePage,
    )

    /** Re-evaluate every pill's enabled/disabled state (folder open, bounds, etc.). No-op if detached. */
    fun refreshEnabled() {
        pills.forEach { it.refreshEnabled() }
    }

    /**
     * True if `(rawX, rawY)` (screen coordinates) falls within the carousel's **resting** bounds --
     * ignoring the enter/exit slide translation, so a tap aimed at the settled carousel while it is
     * still sliding in still counts as "on it". The grid's tap-to-leave-edit-mode handler consults
     * this so a tap on (or aimed at) the carousel never drops the user out of edit mode. False when
     * detached.
     */
    fun tapWithinRestingPill(rawX: Float, rawY: Float): Boolean {
        val v = view ?: return false
        val loc = IntArray(2)
        v.getLocationOnScreen(loc)
        val left = loc[0] - v.translationX
        val top = loc[1] - v.translationY
        return rawX >= left && rawX <= left + v.width && rawY >= top && rawY <= top + v.height
    }

    fun attach(launcher: Launcher, list: AresHomeListView) {
        val existing = view
        if (existing != null) {
            // Same activity re-entering edit mode: already showing.
            if (existing.context === launcher) return
            // Stale carousel left by a recreated activity (recreate() skips exitEditMode -> detach
            // never ran). Drop it and build a fresh one for this activity.
            clearStale()
        }
        val ctx: Context = launcher
        val density = ctx.resources.displayMetrics.density
        fun dp(v: Float): Int = (v * density).toInt()

        val pages: List<List<Pill>> = pageBuilders.map { it(launcher, list) }
        pills = pages.flatten()
        val pageViews = pages.map { buildPageContainer(ctx, it) }

        val pager = PillPager(ctx).apply { setPages(pageViews) }

        val dots = DotsIndicator(ctx, pageViews.size)
        pager.onPageChanged = { dots.setActive(it) }

        val container = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            clipChildren = false
            clipToPadding = false
            // Only show the dots when there is more than one page to move between.
            if (pageViews.size > 1) {
                addView(
                    dots,
                    LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                    ).apply { bottomMargin = dp(8f) },
                )
            }
            addView(
                pager,
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                ),
            )
        }

        pills.forEach { it.refreshEnabled() }

        val lp = BaseDragLayer.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
        ).apply {
            gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            bottomMargin = dp(2f) + launcher.deviceProfile.insets.bottom
        }
        launcher.dragLayer.addView(container, lp)
        view = container
        // F2 (nightly review 2026-08-27): if this activity is destroyed while still "attached" --
        // a recreate() mid edit-mode skips exitEditMode->detach -- release the carousel so the
        // DESTROYED activity (and the launcher/list its pills captured) is not pinned until the next
        // edit-mode entry. Keyed on the activity's DESTROY lifecycle event, NOT view-detach: the
        // launcher handles fold as a config change (no destroy), so this never fires on a fold --
        // that is the regression the old onHostDetached (view-detach) hook caused (af49c86).
        (launcher as? LawnchairLauncher)?.lifecycle?.addObserver(object : DefaultLifecycleObserver {
            override fun onDestroy(owner: LifecycleOwner) {
                if (view?.context === owner) clearStale()
                owner.lifecycle.removeObserver(this)
            }
        })
        // Enter: slide up from below the bottom edge and settle with a bounce.
        container.translationY = dp(160f).toFloat()
        container.animate()
            .translationY(0f)
            .setDuration(380)
            .setInterpolator(OvershootInterpolator(1.7f))
            .start()
    }

    /** Synchronously drop the carousel with no exit animation, releasing captured references. */
    private fun clearStale() {
        view?.let {
            it.animate().cancel()
            (it.parent as? ViewGroup)?.removeView(it)
        }
        view = null
        pills = emptyList()
    }

    fun detach() {
        val v = view ?: return
        view = null
        pills = emptyList()
        val marginBottom = (v.layoutParams as? ViewGroup.MarginLayoutParams)?.bottomMargin ?: 0
        val drop = v.height.toFloat() + marginBottom + v.resources.displayMetrics.density * 24f
        v.animate()
            .translationY(drop)
            .setDuration(240)
            .setInterpolator(AccelerateInterpolator())
            .withEndAction { (v.parent as? ViewGroup)?.removeView(v) }
            .start()
    }

    // ---- shared pill chrome ------------------------------------------------------------------

    private fun dpOf(ctx: Context, v: Float): Int = (v * ctx.resources.displayMetrics.density).toInt()

    private fun pillRow(ctx: Context, surface: Int): LinearLayout = LinearLayout(ctx).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        clipChildren = false
        clipToPadding = false
        // Consume any tap on the pill but off its controls, so a near-miss never falls through to the
        // grid's OnItemTouchListener (which reads a stationary non-item tap as "leave edit mode").
        isClickable = true
        val padV = dpOf(ctx, 8f)
        val padH = dpOf(ctx, 10f)
        setPadding(padH, padV, padH, padV)
        background = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dpOf(ctx, 32f).toFloat()
            setColor(surface)
        }
        elevation = dpOf(ctx, 3f).toFloat()
    }

    /** Lays one page's pills side by side (with a gap) into a single swipeable page view. */
    private fun buildPageContainer(ctx: Context, pagePills: List<Pill>): View =
        LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            clipChildren = false
            clipToPadding = false
            pagePills.forEachIndexed { i, pill ->
                addView(
                    pill.view,
                    LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                    ).apply { marginStart = if (i == 0) 0 else dpOf(ctx, 8f) },
                )
            }
        }

    /** An M3 filled circular icon button (primary-accent disc, state-layer ripple). */
    private fun tonalIconButton(
        ctx: Context,
        iconRes: Int,
        tonal: Int,
        onTonal: Int,
        onClick: (View) -> Unit,
    ): ImageView = ImageView(ctx).apply {
        setImageResource(iconRes)
        imageTintList = ColorStateList.valueOf(onTonal)
        scaleType = ImageView.ScaleType.CENTER_INSIDE
        val d = dpOf(ctx, 46f)
        minimumWidth = d
        minimumHeight = d
        val padIcon = dpOf(ctx, 12f)
        setPadding(padIcon, padIcon, padIcon, padIcon)
        isClickable = true
        isFocusable = true
        val fill = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(tonal) }
        val mask = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(Color.WHITE) }
        background = RippleDrawable(
            ColorStateList.valueOf(ColorUtils.setAlphaComponent(onTonal, 0x33)),
            fill,
            mask,
        )
        setOnClickListener { if (isEnabled) onClick(this) }
    }

    private fun setBtnEnabled(btn: View, enabled: Boolean) {
        btn.isEnabled = enabled
        btn.isClickable = enabled
        btn.alpha = if (enabled) 1f else DISABLED_ALPHA
    }

    private fun pillLabel(ctx: Context, color: Int): TextView = TextView(ctx).apply {
        setTextColor(color)
        textSize = 15f
        gravity = Gravity.CENTER
        minWidth = dpOf(ctx, 104f)
        setPadding(dpOf(ctx, 12f), 0, dpOf(ctx, 12f), 0)
        letterSpacing = 0.01f
    }

    // ---- pill 1: home columns ----------------------------------------------------------------

    private fun buildColumnPage(launcher: Launcher, list: AresHomeListView): List<Pill> =
        listOf(buildColumnPill(launcher, list))

    private fun buildColumnPill(launcher: Launcher, list: AresHomeListView): Pill {
        val ctx: Context = launcher
        fun color(res: Int) = ContextCompat.getColor(ctx, res)
        val surface = color(R.color.materialColorSurfaceContainerHigh)
        val tonal = color(R.color.materialColorPrimary)
        val onTonal = color(R.color.materialColorOnPrimary)

        val label = pillLabel(ctx, tonal)
        lateinit var minusBtn: ImageView
        lateinit var plusBtn: ImageView

        fun labelText(n: Int): CharSequence =
            ctx.resources.getQuantityString(R.plurals.ares_home_columns_label, n, n)

        val updateEnabled = {
            val n = list.currentColumns()
            val folderOpen = list.aresAdapter.expandedWpFolderInfo() != null
            label.alpha = if (folderOpen) DISABLED_ALPHA else 1f
            setBtnEnabled(minusBtn, !folderOpen && n > AresHomeListView.ARES_HOME_COLUMNS_MIN)
            setBtnEnabled(plusBtn, !folderOpen && n < AresHomeListView.ARES_HOME_COLUMNS_MAX)
        }
        val refreshFn = {
            updateEnabled()
            label.text = labelText(list.currentColumns())
        }

        fun step(delta: Int, disc: View) {
            val before = list.currentColumns()
            list.setGridColumns(before + delta)
            val after = list.currentColumns()
            updateEnabled()
            bounce(disc)
            if (after != before) animateCount(label, labelText(after), up = after > before)
        }
        minusBtn = tonalIconButton(ctx, R.drawable.ic_ares_stepper_remove, tonal, onTonal) { step(-1, it) }
        plusBtn = tonalIconButton(ctx, R.drawable.ic_ares_stepper_add, tonal, onTonal) { step(+1, it) }

        val row = pillRow(ctx, surface).apply {
            addView(minusBtn, LinearLayout.LayoutParams(dpOf(ctx, 46f), dpOf(ctx, 46f)))
            addView(label, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT))
            addView(plusBtn, LinearLayout.LayoutParams(dpOf(ctx, 46f), dpOf(ctx, 46f)))
        }
        refreshFn()
        return Pill(row, refreshFn)
    }

    // ---- pill 2: icon tint (Phase 1 UI shell) ------------------------------------------------

    private const val TINT_STEP = 20
    // 0% tint is identical to off, so the amount floors at 20% and stepping below it turns the
    // tint OFF (owner 2026-08-27) rather than offering a meaningless "Tint 0%".
    private const val TINT_MIN = 20
    private const val TINT_MAX = 100

    /**
     * The icon-tint PAGE (owner 2026-08-26): two side-by-side pills -- a toggle pill (droplet glyph +
     * on/off switch) and an amount pill (`-  Tint N%  +`). Only the amount pill dims when tint is off;
     * the toggle pill stays bright, since it is how you turn tint on. Both pills share the in-memory
     * enabled/strength state and persist via the PLAIN prefs (no recreate).
     */
    private fun buildTintPage(launcher: Launcher, list: AresHomeListView): List<Pill> {
        val ctx: Context = launcher
        fun color(res: Int) = ContextCompat.getColor(ctx, res)
        val surface = color(R.color.materialColorSurfaceContainerHigh)
        val tonal = color(R.color.materialColorPrimary)
        val onTonal = color(R.color.materialColorOnPrimary)

        val prefs = PreferenceManager2.getInstance(ctx)
        var enabled = prefs.aresIconTintEnabled.firstBlocking()
        var strength = prefs.aresIconTintStrength.firstBlocking().coerceIn(TINT_MIN, TINT_MAX)

        val label = pillLabel(ctx, tonal)
        lateinit var minusBtn: ImageView
        lateinit var plusBtn: ImageView
        lateinit var toggleSwitch: Switch
        // Guards programmatic toggle updates so syncing the switch to `enabled` (e.g. when stepping
        // below the floor turns the tint off) does not re-fire the checked listener.
        var syncing = false

        fun labelText(): CharSequence = if (enabled) "Tint $strength%" else "Tint off"

        // Controls only -- alpha, +/- enablement, and the toggle's checked state. It deliberately
        // does NOT touch label.text: the label is set by `setLabelNow()` (immediate: toggle/off) or
        // by `animateCount()` (the roll on a strength change), so the roll goes old->new not new->new
        // (nightly review F4).
        val updateControls = {
            label.alpha = if (enabled) 1f else DISABLED_ALPHA
            // Minus stays live at the floor so a further press can turn the tint off.
            setBtnEnabled(minusBtn, enabled)
            setBtnEnabled(plusBtn, enabled && strength < TINT_MAX)
            if (toggleSwitch.isChecked != enabled) {
                syncing = true; toggleSwitch.isChecked = enabled; syncing = false
            }
        }
        fun setLabelNow() { label.text = labelText() }

        fun persist() {
            // onSet on both prefs runs reloadHelper.reloadIcons() -- the live re-tint (icon reload,
            // not a recreate; edit mode retained).
            (launcher as? LawnchairLauncher)?.lifecycleScope?.launch {
                prefs.aresIconTintEnabled.set(enabled)
                prefs.aresIconTintStrength.set(strength)
            }
        }

        // ---- toggle pill: droplet + switch ----
        val droplet = ImageView(ctx).apply {
            setImageResource(R.drawable.ic_ares_tint)
            imageTintList = ColorStateList.valueOf(tonal)
            scaleType = ImageView.ScaleType.CENTER_INSIDE
            val pad = dpOf(ctx, 5f)
            setPadding(pad, pad, pad, pad)
        }
        // Set checked BEFORE attaching the listener so setup does not fire it.
        toggleSwitch = Switch(ctx).apply {
            isChecked = enabled
            trackTintList = ColorStateList(
                arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
                intArrayOf(tonal, ColorUtils.setAlphaComponent(tonal, 0x33)),
            )
            thumbTintList = ColorStateList(
                arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
                intArrayOf(onTonal, ColorUtils.blendARGB(tonal, Color.WHITE, 0.65f)),
            )
            setPadding(dpOf(ctx, 2f), 0, dpOf(ctx, 6f), 0)
            setOnCheckedChangeListener { _, checked ->
                if (syncing) return@setOnCheckedChangeListener
                enabled = checked
                // Turning on from a floored/zeroed state starts at the minimum.
                if (enabled && strength < TINT_MIN) strength = TINT_MIN
                updateControls()
                setLabelNow()
                persist()
            }
        }
        val togglePill = pillRow(ctx, surface).apply {
            addView(droplet, LinearLayout.LayoutParams(dpOf(ctx, 34f), dpOf(ctx, 46f)))
            addView(toggleSwitch, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, dpOf(ctx, 46f)))
        }

        // ---- amount pill: - Tint N% + ----
        minusBtn = tonalIconButton(ctx, R.drawable.ic_ares_stepper_remove, tonal, onTonal) { disc ->
            bounce(disc)
            if (!enabled) return@tonalIconButton
            if (strength > TINT_MIN) {
                strength -= TINT_STEP
                updateControls()
                animateCount(label, labelText(), up = false)
                persist()
            } else {
                // At the floor: a further step down turns the tint off (owner 2026-08-27).
                enabled = false
                updateControls()
                setLabelNow()
                persist()
            }
        }
        plusBtn = tonalIconButton(ctx, R.drawable.ic_ares_stepper_add, tonal, onTonal) { disc ->
            bounce(disc)
            if (!enabled || strength >= TINT_MAX) return@tonalIconButton
            strength += TINT_STEP
            updateControls()
            animateCount(label, labelText(), up = true)
            persist()
        }
        val amountPill = pillRow(ctx, surface).apply {
            addView(minusBtn, LinearLayout.LayoutParams(dpOf(ctx, 46f), dpOf(ctx, 46f)))
            addView(label, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT))
            addView(plusBtn, LinearLayout.LayoutParams(dpOf(ctx, 46f), dpOf(ctx, 46f)))
        }

        updateControls()
        setLabelNow()
        return listOf(
            Pill(togglePill) {},
            Pill(amountPill) { updateControls(); setLabelNow() },
        )
    }

    // ---- pill 3: icon shape ------------------------------------------------------------------

    private class ShapeChoice(val shape: IconShape, val nameRes: Int)

    /** A curated, ordered set of Lawnchair's built-in icon shapes offered by the shape pill. */
    private fun shapeChoices(): List<ShapeChoice> = listOf(
        ShapeChoice(IconShape.Circle, R.string.icon_shape_circle),
        ShapeChoice(IconShape.RoundedSquare, R.string.icon_shape_rounded_square),
        ShapeChoice(IconShape.Square, R.string.icon_shape_square),
        ShapeChoice(IconShape.SharpSquare, R.string.icon_shape_sharp_square),
        ShapeChoice(IconShape.Squircle, R.string.icon_shape_squircle),
        ShapeChoice(IconShape.Teardrop, R.string.icon_shape_teardrop),
        ShapeChoice(IconShape.Cylinder, R.string.icon_shape_cylinder),
        ShapeChoice(IconShape.Cupertino, R.string.icon_shape_cupertino),
        ShapeChoice(IconShape.Hexagon, R.string.icon_shape_hexagon),
        ShapeChoice(IconShape.Octagon, R.string.icon_shape_octagon),
        ShapeChoice(IconShape.Diamond, R.string.icon_shape_diamond),
        ShapeChoice(IconShape.Pebble, R.string.icon_shape_pebble),
        ShapeChoice(IconShape.Egg, R.string.icon_shape_egg),
        ShapeChoice(IconShape.Cloudy, R.string.icon_shape_cloudy),
        ShapeChoice(IconShape.Flower, R.string.icon_shape_flower),
        ShapeChoice(IconShape.Heart, R.string.icon_shape_heart),
    )

    /** Label for a current shape that is not one of [shapeChoices]; honest, never a wrong name. */
    private fun offListShapeNameRes(shape: IconShape): Int = when (shape.toString()) {
        "sammy" -> R.string.icon_shape_sammy
        else -> R.string.custom
    }

    /**
     * The icon-shape page (owner 2026-08-26): a single pill whose `-`/`+` cycle every app icon's
     * shape through Lawnchair's built-in shapes, with a live swatch of the current shape. Applies
     * globally (home, app list, folders) via the existing `iconShape` preference -- writing it
     * reloads the icon cache in the new shape (NOT a recreate, so edit mode is retained). The list
     * wraps around, so the `-`/`+` never disable.
     */
    private fun buildShapePage(launcher: Launcher, list: AresHomeListView): List<Pill> {
        val ctx: Context = launcher
        fun color(res: Int) = ContextCompat.getColor(ctx, res)
        val surface = color(R.color.materialColorSurfaceContainerHigh)
        val tonal = color(R.color.materialColorPrimary)
        val onTonal = color(R.color.materialColorOnPrimary)

        val prefs = PreferenceManager2.getInstance(ctx)
        val baseChoices = shapeChoices()
        val current = prefs.iconShape.firstBlocking()
        val currentIdx = baseChoices.indexOfFirst { it.shape.toString() == current.toString() }
        // The current shape can be OFF the curated 16: the default `icon_shape` is "system", which
        // resolves to a device mask that is often none of them, and Lawnchair also ships shapes we
        // don't cycle (Sammy, RoundedHexagon). Coercing a -1 match to 0 would mislabel the real
        // shape as "Circle" AND make the first +/- silently discard it (jump to a neighbour of
        // Circle). Instead, prepend the real current shape as a leading entry so the swatch + label
        // are honest and the user can cycle away from -- and back to -- their actual shape.
        val choices = if (currentIdx >= 0) baseChoices
        else listOf(ShapeChoice(current, offListShapeNameRes(current))) + baseChoices
        var idx = if (currentIdx >= 0) currentIdx else 0

        val swatch = ShapeSwatchView(ctx)
        val name = pillLabel(ctx, tonal)
        lateinit var minusBtn: ImageView
        lateinit var plusBtn: ImageView

        val sync = {
            val c = choices[idx]
            swatch.set(c.shape, tonal)
            name.text = ctx.getString(c.nameRes)
        }

        fun cycle(delta: Int, disc: View) {
            idx = ((idx + delta) % choices.size + choices.size) % choices.size
            sync()
            bounce(disc)
            val shape = choices[idx].shape
            (launcher as? LawnchairLauncher)?.lifecycleScope?.launch {
                prefs.iconShape.set(shape)
            }
        }
        minusBtn = tonalIconButton(ctx, R.drawable.ic_ares_stepper_remove, tonal, onTonal) { cycle(-1, it) }
        plusBtn = tonalIconButton(ctx, R.drawable.ic_ares_stepper_add, tonal, onTonal) { cycle(+1, it) }

        val mid = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(swatch, LinearLayout.LayoutParams(dpOf(ctx, 26f), dpOf(ctx, 26f)))
            addView(name, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        }
        val pill = pillRow(ctx, surface).apply {
            addView(minusBtn, LinearLayout.LayoutParams(dpOf(ctx, 46f), dpOf(ctx, 46f)))
            addView(mid, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT))
            addView(plusBtn, LinearLayout.LayoutParams(dpOf(ctx, 46f), dpOf(ctx, 46f)))
        }
        sync()
        return listOf(Pill(pill) { sync() })
    }

    /** Draws an [IconShape]'s mask (its 0..100 path) scaled and centred, filled with a colour. */
    private class ShapeSwatchView(context: Context) : View(context) {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
        private val scaled = Path()
        private val matrix = Matrix()
        private var basePath: Path? = null

        fun set(shape: IconShape, color: Int) {
            paint.color = color
            basePath = shape.getMaskPath()
            invalidate()
        }

        override fun onDraw(canvas: Canvas) {
            val p = basePath ?: return
            val s = minOf(width, height).toFloat()
            if (s <= 0f) return
            matrix.reset()
            matrix.setScale(s / 100f, s / 100f)
            matrix.postTranslate((width - s) / 2f, (height - s) / 2f)
            scaled.set(p)
            scaled.transform(matrix)
            canvas.drawPath(scaled, paint)
        }
    }

    // ---- feel ---------------------------------------------------------------------------------

    private fun bounce(v: View) {
        v.animate().cancel()
        v.scaleX = 0.85f
        v.scaleY = 0.85f
        v.animate().scaleX(1f).scaleY(1f).setDuration(260).setInterpolator(OvershootInterpolator(2.5f)).start()
    }

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
                label.animate().translationY(0f).alpha(1f).setDuration(180).setInterpolator(DecelerateInterpolator()).start()
            }
            .start()
    }

    // ---- pager --------------------------------------------------------------------------------

    /**
     * A one-page-at-a-time horizontal pager over the pills. Horizontal drags page (looping); a tap
     * (no horizontal travel) passes through to the pill's own controls. The horizontal drag is
     * intercepted and the parent is asked NOT to intercept, so it never reaches the pane-swipe
     * controller -- the carousel owns horizontal gestures within its bounds.
     */
    private class PillPager(context: Context) : FrameLayout(context) {
        private val slop = ViewConfiguration.get(context).scaledTouchSlop
        private val threshold = slop * 3f
        private val pages = mutableListOf<View>()
        private var currentIdx = 0
        private var downX = 0f
        private var downY = 0f
        private var dragging = false
        var onPageChanged: ((Int) -> Unit)? = null

        init {
            clipChildren = false
            clipToPadding = false
        }

        fun setPages(views: List<View>) {
            pages.clear()
            removeAllViews()
            views.forEachIndexed { i, v ->
                addView(
                    v,
                    LayoutParams(
                        LayoutParams.WRAP_CONTENT,
                        LayoutParams.WRAP_CONTENT,
                        Gravity.CENTER,
                    ),
                )
                v.visibility = if (i == 0) View.VISIBLE else View.INVISIBLE
            }
            pages.addAll(views)
            currentIdx = 0
        }

        private fun wrap(i: Int): Int = ((i % pages.size) + pages.size) % pages.size

        override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = ev.x; downY = ev.y; dragging = false
                }
                MotionEvent.ACTION_MOVE -> {
                    if (!dragging && pages.size > 1) {
                        val dx = ev.x - downX
                        val dy = ev.y - downY
                        if (abs(dx) > slop && abs(dx) > abs(dy)) {
                            dragging = true
                            parent?.requestDisallowInterceptTouchEvent(true)
                            return true
                        }
                    }
                }
            }
            return dragging
        }

        override fun onTouchEvent(ev: MotionEvent): Boolean {
            if (pages.size <= 1) return false
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = ev.x; downY = ev.y
                    return true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = ev.x - downX
                    if (!dragging && abs(dx) > slop) {
                        dragging = true
                        parent?.requestDisallowInterceptTouchEvent(true)
                    }
                    if (dragging) {
                        // Rubber-band the current page a little with the finger.
                        pages[currentIdx].translationX = dx * 0.5f
                    }
                }
                MotionEvent.ACTION_UP -> {
                    val dx = ev.x - downX
                    if (dragging && abs(dx) > threshold) {
                        pageBy(if (dx < 0) +1 else -1)
                    } else {
                        pages[currentIdx].animate().translationX(0f).setDuration(160)
                            .setInterpolator(DecelerateInterpolator()).start()
                    }
                    dragging = false
                    parent?.requestDisallowInterceptTouchEvent(false)
                }
                MotionEvent.ACTION_CANCEL -> {
                    pages[currentIdx].animate().translationX(0f).setDuration(160).start()
                    dragging = false
                    parent?.requestDisallowInterceptTouchEvent(false)
                }
            }
            return true
        }

        private fun pageBy(dir: Int) {
            val from = pages[currentIdx]
            val toIdx = wrap(currentIdx + dir)
            val to = pages[toIdx]
            val w = (if (width > 0) width else from.width).toFloat().coerceAtLeast(1f)
            // Incoming enters from the side the finger is heading toward.
            to.visibility = View.VISIBLE
            to.translationX = dir * w
            to.animate().translationX(0f).setDuration(220).setInterpolator(DecelerateInterpolator()).start()
            from.animate().translationX(-dir * w).setDuration(220).setInterpolator(DecelerateInterpolator())
                .withEndAction {
                    from.visibility = View.INVISIBLE
                    from.translationX = 0f
                }
                .start()
            currentIdx = toIdx
            onPageChanged?.invoke(toIdx)
        }
    }

    // ---- dots ---------------------------------------------------------------------------------

    private class DotsIndicator(context: Context, count: Int) : LinearLayout(context) {
        private val dots = mutableListOf<View>()
        private val activeColor = ContextCompat.getColor(context, R.color.materialColorPrimary)
        // Sits on its own surface chip so blue-on-blue-wallpaper dots still read (owner 2026-08-26).
        private val inactiveColor = ColorUtils.setAlphaComponent(activeColor, 0x66)

        init {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER
            val padH = dpOf(context, 9f)
            val padV = dpOf(context, 5f)
            setPadding(padH, padV, padH, padV)
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dpOf(context, 12f).toFloat()
                setColor(ContextCompat.getColor(context, R.color.materialColorSurfaceContainerHigh))
            }
            elevation = dpOf(context, 3f).toFloat()
            val size = dpOf(context, 7f)
            val gap = dpOf(context, 6f)
            repeat(count) { i ->
                val dot = View(context).apply {
                    background = GradientDrawable().apply {
                        shape = GradientDrawable.OVAL
                        setColor(if (i == 0) activeColor else inactiveColor)
                    }
                }
                addView(dot, LayoutParams(size, size).apply { marginStart = if (i == 0) 0 else gap })
                dots.add(dot)
            }
        }

        fun setActive(index: Int) {
            dots.forEachIndexed { i, dot ->
                (dot.background as? GradientDrawable)?.setColor(if (i == index) activeColor else inactiveColor)
            }
        }
    }
}
