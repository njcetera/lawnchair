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
import android.graphics.Outline
import android.view.ViewOutlineProvider
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
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

    // ---- pill 2: icon theming (Material You) --------------------------------------------------

    // Icon-shape direct-select strip.
    private const val SHAPE_CELL_DP = 44f
    private const val SHAPE_STRIP_DP = 232f

    /**
     * The icon-theming PAGE (owner 2026-08-27): a single pill -- a droplet glyph, a "Themed icons"
     * label, and an on/off switch. Full theming renders EVERY app as an accent monochrome (see
     * [AresIconTint]), so there is nothing to partially tint and thus no amount stepper -- it is a
     * plain on/off. Persists via the PLAIN pref (no recreate); the label dims when off.
     */
    private fun buildTintPage(launcher: Launcher, list: AresHomeListView): List<Pill> {
        val ctx: Context = launcher
        fun color(res: Int) = ContextCompat.getColor(ctx, res)
        val surface = color(R.color.materialColorSurfaceContainerHigh)
        val tonal = color(R.color.materialColorPrimary)
        val onTonal = color(R.color.materialColorOnPrimary)

        val prefs = PreferenceManager2.getInstance(ctx)
        var enabled = prefs.aresIconTintEnabled.firstBlocking()

        val label = pillLabel(ctx, tonal).apply { text = "Themed icons" }
        lateinit var toggleSwitch: Switch
        // Guards programmatic toggle updates so syncing the switch to `enabled` on a page refresh
        // does not re-fire the checked listener.
        var syncing = false

        val updateControls = {
            label.alpha = if (enabled) 1f else DISABLED_ALPHA
            if (toggleSwitch.isChecked != enabled) {
                syncing = true; toggleSwitch.isChecked = enabled; syncing = false
            }
        }

        fun persist() {
            // onSet runs reloadHelper.reloadIcons() -- the live re-theme (icon reload, not a
            // recreate; edit mode retained).
            (launcher as? LawnchairLauncher)?.lifecycleScope?.launch {
                prefs.aresIconTintEnabled.set(enabled)
            }
        }

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
                updateControls()
                // Dissolve old icons into the newly (un)themed ones instead of a one-frame pop.
                AresIconTransition.reveal(launcher, list)
                persist()
            }
        }
        val togglePill = pillRow(ctx, surface).apply {
            addView(droplet, LinearLayout.LayoutParams(dpOf(ctx, 34f), dpOf(ctx, 46f)))
            addView(label, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT))
            addView(toggleSwitch, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, dpOf(ctx, 46f)))
        }

        updateControls()
        return listOf(Pill(togglePill) { updateControls() })
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
        // resolves to a device mask that is often none of them, and Lawnchair also ships shapes not
        // in the list (Sammy, RoundedHexagon). Surface the real current shape as a leading swatch so
        // it is shown and selectable rather than silently mislabelled/replaced (F1).
        val choices = if (currentIdx >= 0) baseChoices
        else listOf(ShapeChoice(current, offListShapeNameRes(current))) + baseChoices
        var selectedIdx = if (currentIdx >= 0) currentIdx else 0

        // Direct-select strip (owner 2026-08-27): every shape is a swatch; tap to select, the chosen
        // one fills with the accent. Replaces the -/+ cycle. The strip scrolls horizontally and only
        // hands the drag back to the carousel pager at its ends (see [ShapeStrip]).
        val strip = ShapeStrip(ctx)
        lateinit var cells: List<ShapeCell>
        fun select(i: Int) {
            if (i == selectedIdx) return
            cells[selectedIdx].isChosen = false
            selectedIdx = i
            cells[i].isChosen = true
            bounce(cells[i])
            strip.centerOn(i)
            // Dissolve old-shape icons into the new shape instead of a one-frame pop.
            AresIconTransition.reveal(launcher, list)
            (launcher as? LawnchairLauncher)?.lifecycleScope?.launch {
                // Folders follow the icon shape (owner 2026-08-27): a circle folder in a grid of
                // squircles looks out of place, and the folder's preview grid already distinguishes
                // it from an app. Set both so picking a shape reshapes icons AND folders.
                prefs.iconShape.set(choices[i].shape)
                prefs.folderShape.set(choices[i].shape)
            }
        }
        cells = choices.mapIndexed { i, choice ->
            ShapeCell(ctx, choice.shape, tonal, onTonal).apply {
                isChosen = i == selectedIdx
                setOnClickListener { select(i) }
                strip.row.addView(
                    this,
                    LinearLayout.LayoutParams(dpOf(ctx, SHAPE_CELL_DP), dpOf(ctx, SHAPE_CELL_DP))
                        .apply { marginStart = if (i == 0) 0 else dpOf(ctx, 2f) },
                )
            }
        }

        val pill = pillRow(ctx, surface).apply {
            addView(strip, LinearLayout.LayoutParams(dpOf(ctx, SHAPE_STRIP_DP), dpOf(ctx, SHAPE_CELL_DP)))
        }
        // Open with the current shape centred (no animation on first layout).
        strip.post { strip.centerOn(selectedIdx, smooth = false) }
        return listOf(Pill(pill) {})
    }

    /** Draws an [IconShape]'s mask (its 0..100 path) scaled and centred, filled with a colour. */
    /**
     * One shape swatch in the shape-select strip. Draws the shape's mask; when [isChosen] it fills
     * an accent disc behind and draws the shape in the on-accent colour, so the current shape reads
     * as selected at a glance.
     */
    private class ShapeCell(
        context: Context,
        shape: IconShape,
        private val accent: Int,
        private val onAccent: Int,
    ) : View(context) {
        private val discPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL; color = accent }
        private val shapePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
        private val scaled = Path()
        private val matrix = Matrix()
        private val base: Path = shape.getMaskPath()

        var isChosen = false
            set(value) { if (field != value) { field = value; invalidate() } }

        init {
            isClickable = true
            isFocusable = true
        }

        override fun onDraw(canvas: Canvas) {
            val cx = width / 2f
            val cy = height / 2f
            if (isChosen) {
                canvas.drawCircle(cx, cy, minOf(width, height) / 2f * 0.94f, discPaint)
            }
            val s = minOf(width, height).toFloat() * (if (isChosen) 0.48f else 0.54f)
            if (s <= 0f) return
            shapePaint.color = if (isChosen) onAccent else accent
            matrix.reset()
            matrix.setScale(s / 100f, s / 100f)
            matrix.postTranslate(cx - s / 2f, cy - s / 2f)
            scaled.set(base)
            scaled.transform(matrix)
            canvas.drawPath(scaled, shapePaint)
        }
    }

    /**
     * A horizontally-scrolling strip of [ShapeCell]s. Grabs the drag from the carousel [PillPager]
     * on touch-down so it scrolls, and only hands the drag back (so the pager can page) at its
     * scroll edges -- resolving the pill-swipe-vs-strip-scroll collision. Taps on cells still fire.
     */
    private class ShapeStrip(context: Context) : HorizontalScrollView(context) {
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        private var lastX = 0f

        init {
            isHorizontalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
            // The carousel pill and pager set clipChildren=false (so shadows/bounces aren't clipped),
            // which also lets the off-window swatches spill across the screen. clipToOutline clips
            // the strip's content to its own bounds regardless of the ancestors.
            outlineProvider = object : ViewOutlineProvider() {
                override fun getOutline(v: View, outline: Outline) {
                    outline.setRect(0, 0, v.width, v.height)
                }
            }
            clipToOutline = true
            addView(row, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.MATCH_PARENT))
        }

        // A few px of slack so a fling that stops just shy of the edge still counts as "at the edge"
        // and hands the drag off to the pager (otherwise paging away from the strip can feel stuck).
        private val edgeSlack = (4 * resources.displayMetrics.density).toInt()
        private fun atStart() = scrollX <= edgeSlack
        private fun atEnd() = scrollX >= (row.width - width).coerceAtLeast(0) - edgeSlack
        // True when the strip's content fits without scrolling -- then it must never trap paging.
        private fun notScrollable() = row.width <= width

        override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
            if (ev.actionMasked == MotionEvent.ACTION_DOWN) {
                lastX = ev.x
                // Claim the gesture from the pager up front; released only at the edges (below).
                parent?.requestDisallowInterceptTouchEvent(true)
            }
            return super.onInterceptTouchEvent(ev)
        }

        override fun onTouchEvent(ev: MotionEvent): Boolean {
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> lastX = ev.x
                MotionEvent.ACTION_MOVE -> {
                    val dx = ev.x - lastX
                    lastX = ev.x
                    // At an edge and still dragging outward: let the pager page instead of dead-scroll.
                    val handOff = notScrollable() || (atStart() && dx > 0f) || (atEnd() && dx < 0f)
                    parent?.requestDisallowInterceptTouchEvent(!handOff)
                }
            }
            return super.onTouchEvent(ev)
        }

        fun centerOn(index: Int, smooth: Boolean = true) {
            val child = row.getChildAt(index) ?: return
            val target = (child.left + child.width / 2 - width / 2).coerceAtLeast(0)
            if (smooth) smoothScrollTo(target, 0) else scrollTo(target, 0)
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
