package app.lawnchair.areslauncher

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
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
import androidx.lifecycle.lifecycleScope
import app.lawnchair.LawnchairLauncher
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
 * Registry-driven ([pillBuilders]) so adding a pill is one entry. The initial pills are the home
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

    /** Ordered registry. Append a builder to add a pill. */
    private val pillBuilders: List<(Launcher, AresHomeListView) -> Pill> = listOf(
        ::buildColumnPill,
        ::buildTintPill,
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

        val built = pillBuilders.map { it(launcher, list) }
        pills = built

        val pager = PillPager(ctx).apply { setPages(built.map { it.view }) }

        val dots = DotsIndicator(ctx, built.size)
        pager.onPageChanged = { dots.setActive(it) }

        val container = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            clipChildren = false
            clipToPadding = false
            // Only show the dots when there is more than one pill to move between.
            if (built.size > 1) {
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
    private const val TINT_MIN = 0
    private const val TINT_MAX = 100

    private fun buildTintPill(launcher: Launcher, list: AresHomeListView): Pill {
        val ctx: Context = launcher
        fun color(res: Int) = ContextCompat.getColor(ctx, res)
        val surface = color(R.color.materialColorSurfaceContainerHigh)
        val tonal = color(R.color.materialColorPrimary)
        val onTonal = color(R.color.materialColorOnPrimary)

        val prefs = PreferenceManager2.getInstance(ctx)
        // In-memory state; write async for persistence (plain prefs, no recreate).
        var enabled = prefs.aresIconTintEnabled.firstBlocking()
        var strength = prefs.aresIconTintStrength.firstBlocking().coerceIn(TINT_MIN, TINT_MAX)

        val label = pillLabel(ctx, tonal)
        lateinit var minusBtn: ImageView
        lateinit var plusBtn: ImageView

        fun labelText(): CharSequence = if (!enabled) "Tint off" else "Tint $strength%"

        val updateEnabled = {
            label.text = labelText()
            label.alpha = if (enabled) 1f else DISABLED_ALPHA
            setBtnEnabled(minusBtn, enabled && strength > TINT_MIN)
            setBtnEnabled(plusBtn, enabled && strength < TINT_MAX)
            // The switch reflects its own on/off state; the amount stepper dims when tint is off.
        }

        fun persist() {
            (launcher as? LawnchairLauncher)?.lifecycleScope?.launch {
                prefs.aresIconTintEnabled.set(enabled)
                prefs.aresIconTintStrength.set(strength)
            }
            // Phase 2 wires the live re-tint here.
            AresIconTint.apply(launcher, enabled, strength)
        }

        // A real on/off switch (owner 2026-08-26: reads clearly as on/off, one row with the stepper),
        // tinted to the primary accent. Set checked BEFORE the listener so setup does not fire it.
        val toggleSwitch = Switch(ctx).apply {
            isChecked = enabled
            trackTintList = ColorStateList(
                arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
                intArrayOf(tonal, ColorUtils.setAlphaComponent(tonal, 0x33)),
            )
            thumbTintList = ColorStateList(
                arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
                intArrayOf(onTonal, ColorUtils.blendARGB(tonal, Color.WHITE, 0.65f)),
            )
            val padH = dpOf(ctx, 6f)
            setPadding(padH, 0, padH, 0)
            setOnCheckedChangeListener { _, checked ->
                enabled = checked
                updateEnabled()
                persist()
            }
        }
        minusBtn = tonalIconButton(ctx, R.drawable.ic_ares_stepper_remove, tonal, onTonal) { disc ->
            val before = strength
            strength = (strength - TINT_STEP).coerceIn(TINT_MIN, TINT_MAX)
            updateEnabled()
            bounce(disc)
            if (strength != before) { animateCount(label, labelText(), up = false); persist() }
        }
        plusBtn = tonalIconButton(ctx, R.drawable.ic_ares_stepper_add, tonal, onTonal) { disc ->
            val before = strength
            strength = (strength + TINT_STEP).coerceIn(TINT_MIN, TINT_MAX)
            updateEnabled()
            bounce(disc)
            if (strength != before) { animateCount(label, labelText(), up = true); persist() }
        }

        val row = pillRow(ctx, surface).apply {
            addView(toggleSwitch, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, dpOf(ctx, 46f)))
            addView(minusBtn, LinearLayout.LayoutParams(dpOf(ctx, 46f), dpOf(ctx, 46f)))
            addView(label, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT))
            addView(plusBtn, LinearLayout.LayoutParams(dpOf(ctx, 46f), dpOf(ctx, 46f)))
        }
        updateEnabled()
        return Pill(row) { updateEnabled() }
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
