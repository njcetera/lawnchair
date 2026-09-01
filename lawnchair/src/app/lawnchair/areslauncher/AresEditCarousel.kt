package app.lawnchair.areslauncher

import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.drawable.Drawable
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
import app.lawnchair.preferences.PreferenceManager
import app.lawnchair.preferences2.PreferenceManager2
import app.lawnchair.ui.preferences.iconPackIntents
import com.android.launcher3.Launcher
import com.android.launcher3.LauncherAppState
import com.android.launcher3.R
import com.android.launcher3.model.data.FolderInfo
import com.android.launcher3.model.data.ItemInfo
import com.android.launcher3.popup.SystemShortcut
import com.android.launcher3.util.Executors
import com.android.launcher3.views.BaseDragLayer
import com.android.launcher3.views.OptionsPopupView
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

    // Quiet period after the last icon-pack tap before the reload fires, so clicking through packs
    // coalesces into one apply. Small relative to the multi-second reload, so a single pick still
    // feels immediate.
    private const val ICON_PACK_APPLY_DEBOUNCE_MS = 500L

    private var view: View? = null
    private var pills: List<Pill> = emptyList()

    // Debounced icon-pack apply. Each apply is a full model reload that stopLoader+startLoaders the
    // previous one, so clicking THROUGH packs thrashes the loader and the grid settles long after
    // (owner 2026-08-31). The pill selection updates instantly; the actual pref write is held until
    // the user settles, so rapid switching fires one reload, not one per tap. Flushed on edit-mode
    // exit so leaving quickly still commits the settled choice.
    private var pendingIconPackApply: (() -> Unit)? = null
    private var pendingIconPackView: View? = null
    private var pendingIconPackRunnable: Runnable? = null

    private fun flushPendingIconPack() {
        pendingIconPackRunnable?.let { pendingIconPackView?.removeCallbacks(it) }
        pendingIconPackRunnable = null
        pendingIconPackView = null
        val commit = pendingIconPackApply ?: return
        pendingIconPackApply = null
        commit()
    }

    // Debounced icon-shape apply -- same rationale as the pack pill: each shape write is a reloadIcons
    // that stopLoader+startLoaders the previous, so rapid tapping is coalesced to one reload on settle.
    private var pendingShapeApply: (() -> Unit)? = null
    private var pendingShapeView: View? = null
    private var pendingShapeRunnable: Runnable? = null

    private fun flushPendingShape() {
        pendingShapeRunnable?.let { pendingShapeView?.removeCallbacks(it) }
        pendingShapeRunnable = null
        pendingShapeView = null
        val commit = pendingShapeApply ?: return
        pendingShapeApply = null
        commit()
    }

    /**
     * Show the sparkle veil over the home, then lift the settings pill back ABOVE it so the control the
     * user just touched stays visible on top of the animation (owner 2026-08-31). The veil and the
     * carousel are both children of the drag layer and the veil is added last, so without this it
     * would cover the pill.
     */
    private fun coverHome(launcher: Launcher, list: AresHomeListView) {
        AresIconTransition.freeze(launcher, list)
        view?.bringToFront()
    }

    /**
     * Applies an icon pack: invalidate the STALE disk-cache icons for the apps shown on home, then
     * write the pack pref (whose change hook clears the memory cache + reloads the model).
     *
     * Why the invalidation. A pack change otherwise binds home in ~0.4s but with the OLD icons --
     * `loadWorkspace` serves them from the disk cache, which `reloadIcons` does NOT clear (memory
     * only) -- and the new pack icons reach the grid only via a lazy background revalidation
     * dispatched ~12s later (measured on the Pixel, owner 2026-08-31: the same tiles re-bind twice,
     * old bitmap then new ~12s on). Deleting the home apps' disk entries first forces `loadWorkspace`
     * to regenerate those icons fresh from the new pack, so home shows the new look on the first
     * bind. Scoped to the home apps (and folder children); the drawer keeps the lazy path.
     *
     * On MODEL_EXECUTOR (the serial "launcher-loader" thread): it lands BEFORE the reload task the
     * pref write enqueues on that same executor, and keeps the DB deletes off the main thread.
     */
    private fun commitIconPack(
        ctx: Context,
        list: AresHomeListView,
        prefs: PreferenceManager,
        pkg: String,
    ) {
        val iconCache = LauncherAppState.INSTANCE.get(ctx).iconCache
        val keys = LinkedHashSet<Pair<String, android.os.UserHandle>>()
        fun add(info: ItemInfo) {
            info.targetComponent?.packageName?.let { keys.add(it to info.user) }
        }
        for (info in list.aresAdapter.snapshot()) {
            if (info is FolderInfo) info.getContents().forEach(::add) else add(info)
        }
        Executors.MODEL_EXECUTOR.execute {
            keys.forEach { (p, u) -> iconCache.removeIconsForPkg(p, u) }
        }
        prefs.iconPackPackage.set(pkg)
    }

    /** One personalization pill: its control view plus a hook to re-evaluate enabled/disabled state. */
    private class Pill(val view: View, val refreshEnabled: () -> Unit)

    /**
     * Ordered registry of carousel PAGES. Each builder returns the pills shown together on one page
     * (laid out side by side). One page == one swipe position == one dot. Columns is a single-pill
     * page; icon tint is a two-pill page (a toggle pill + an amount pill). Append a builder to add a
     * page.
     */
    private val pageBuilders: List<(Launcher, AresHomeListView) -> List<Pill>> = listOf(
        ::buildActionsPage,
        ::buildColumnPage,
        ::buildTintPage,
        ::buildShapePage,
        ::buildIconPackPage,
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
                // Also drop any in-flight icon reveal so its overlay never pins this destroyed
                // activity (nightly 2026-08-28, finding 5).
                AresIconTransition.cancel()
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
        // Drop (do NOT commit) any pending icon-pack apply: this is destroyed-activity teardown, so
        // firing a reload here is pointless and its callback would run on a detached view.
        pendingIconPackRunnable?.let { pendingIconPackView?.removeCallbacks(it) }
        pendingIconPackRunnable = null
        pendingIconPackView = null
        pendingIconPackApply = null
        pendingShapeRunnable?.let { pendingShapeView?.removeCallbacks(it) }
        pendingShapeRunnable = null
        pendingShapeView = null
        pendingShapeApply = null
        AresIconTransition.cancel()
        view?.let {
            it.animate().cancel()
            (it.parent as? ViewGroup)?.removeView(it)
        }
        view = null
        pills = emptyList()
    }

    fun detach() {
        // Commit a debounced icon-pack pick before tearing down, so leaving edit mode right after a
        // tap still applies it (this runs the plain pref write, no freeze -- there is no grid to
        // reveal on the way out).
        flushPendingIconPack()
        flushPendingShape()
        AresIconTransition.cancel()
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
                        // Small gap so a multi-pill page (the wallpaper + widget action pair) reads as
                        // a connected group squished toward the middle, not two separate pills.
                    ).apply { marginStart = if (i == 0) 0 else dpOf(ctx, 4f) },
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

    // ---- action pills: wallpaper & style + add widget (one shared page) -----------------------
    // Two shortcut pills that fire the SAME handlers as the "Wallpaper & style" and "Widgets"
    // buttons in the empty-space long-press menu (owner 2026-08-31), so edit mode reaches both
    // without leaving it for the long-press menu. They share ONE carousel page (side by side, one
    // dot) rather than a page each (owner).

    /** Which end of an action pill keeps the full pill radius; the other end is flattened to connect. */
    private enum class PillEnd { START, END }

    private fun buildActionsPage(launcher: Launcher, list: AresHomeListView): List<Pill> =
        listOf(
            buildActionPill(
                launcher,
                R.drawable.ic_palette,
                R.string.styles_wallpaper_button_text,
                PillEnd.START,
            ) { v -> OptionsPopupView.startWallpaperPicker(v) },
            buildActionPill(
                launcher,
                SystemShortcut.Widgets.getDrawableId(),
                R.string.ares_add_widget_pill,
                PillEnd.END,
            ) { v -> OptionsPopupView.onWidgetsClicked(v) },
        )

    /**
     * A single-tap action pill: an icon + label on the pill surface, the whole pill clickable. The
     * pill mirrors a long-press-menu button, so tapping it just runs that button's handler; there is
     * no state to reflect, so its refresh is a no-op.
     *
     * [roundedSide] shapes it as one half of a connected pair (M3 connected-button-group look, like
     * the grouped search results): the named end keeps the full pill radius, the facing inner end is
     * flattened so the two pills read as squished together toward the middle.
     */
    private fun buildActionPill(
        launcher: Launcher,
        iconRes: Int,
        labelRes: Int,
        roundedSide: PillEnd,
        onClick: (View) -> Unit,
    ): Pill {
        val ctx: Context = launcher
        fun color(res: Int) = ContextCompat.getColor(ctx, res)
        val surface = color(R.color.materialColorSurfaceContainerHigh)
        val tonal = color(R.color.materialColorPrimary)

        val icon = ImageView(ctx).apply {
            setImageResource(iconRes)
            imageTintList = ColorStateList.valueOf(tonal)
            scaleType = ImageView.ScaleType.CENTER_INSIDE
        }
        // Hug the text (drop pillLabel's 104dp min-width) so two labelled pills fit on one page.
        val label = pillLabel(ctx, tonal).apply {
            text = ctx.getString(labelRes)
            minWidth = 0
        }
        val row = pillRow(ctx, surface).apply {
            // Asymmetric corners for the connected pair: full radius on the outer end, a small radius
            // on the inner (facing) end. cornerRadii order is TL, TR, BR, BL (x/y pairs). LTR layout.
            (background as? GradientDrawable)?.let { bg ->
                val big = dpOf(ctx, 32f).toFloat()
                val small = dpOf(ctx, 8f).toFloat()
                bg.cornerRadii = if (roundedSide == PillEnd.START) {
                    floatArrayOf(big, big, small, small, small, small, big, big) // round left, flat right
                } else {
                    floatArrayOf(small, small, big, big, big, big, small, small) // flat left, round right
                }
            }
            addView(
                icon,
                // 46dp-tall box (the same height the other pills' controls use) so this pill matches
                // their height; CENTER_INSIDE keeps the glyph itself at ~28dp, centred (owner 2026-09-01).
                LinearLayout.LayoutParams(dpOf(ctx, 28f), dpOf(ctx, 46f)).apply {
                    marginStart = dpOf(ctx, 6f)
                },
            )
            addView(
                label,
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                ),
            )
            setOnClickListener { onClick(it) }
        }
        return Pill(row) { }
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
                // Cover the home with the sparkle veil, then dissolve it only once the re-themed icons
                // have actually bound (a theme toggle is a reloadIcons -> finishBindingItems ->
                // playFrozen, same async path as shape/pack).
                coverHome(launcher, list)
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
            // Cover the home with the sparkle veil IMMEDIATELY (freeze is a no-op if one is already
            // up, so tapping through shapes keeps the single veil). DEBOUNCE the pref write like the
            // pack pill: each write is a reloadIcons that stopLoader+startLoaders the previous, so
            // rapid tapping would otherwise thrash the loader and could dissolve the veil on an
            // intermediate reload. One reload fires when the user settles; its bind-complete
            // (finishBindingItems -> playFrozen) dissolves the veil over the finished new shape.
            coverHome(launcher, list)
            val chosen = choices[i].shape
            pendingShapeApply = {
                // Folders follow the icon shape (owner 2026-08-27): a circle folder in a grid of
                // squircles looks out of place, and the folder's preview grid already distinguishes
                // it from an app. Set both so picking a shape reshapes icons AND folders.
                (launcher as? LawnchairLauncher)?.lifecycleScope?.launch {
                    prefs.iconShape.set(chosen)
                    prefs.folderShape.set(chosen)
                }
            }
            pendingShapeView = strip
            pendingShapeRunnable?.let { strip.removeCallbacks(it) }
            pendingShapeRunnable = Runnable {
                pendingShapeRunnable = null
                pendingShapeView = null
                val commit = pendingShapeApply ?: return@Runnable
                pendingShapeApply = null
                commit()
            }.also { strip.postDelayed(it, ICON_PACK_APPLY_DEBOUNCE_MS) }
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

    // ---- pill 4: icon pack ------------------------------------------------------------------

    /** One choice in the icon-pack strip: a pack package (empty == System / no pack) + its swatch. */
    private class PackChoice(val packageName: String, val icon: Drawable?)

    /**
     * Icon-pack selector: a direct-select strip (same chrome as the shape pill) of the installed
     * icon packs, each shown by its own launcher icon, with a leading "System" swatch (empty
     * package = stock adaptive icons, no pack). Tapping sets `iconPackPackage`, whose change hook
     * clears the icon cache and reloads the model off the MODEL_EXECUTOR, so the whole grid
     * re-icons. Coexists with the shape pill (shape masks whatever the pack produces) and with the
     * tint/theming pill (when theming is on, the provider derives the accent monochrome FROM the
     * pack's icon, so the pack still shows through -- owner 2026-08-31).
     */
    private fun buildIconPackPage(launcher: Launcher, list: AresHomeListView): List<Pill> {
        val ctx: Context = launcher
        fun color(res: Int) = ContextCompat.getColor(ctx, res)
        val surface = color(R.color.materialColorSurfaceContainerHigh)
        val tonal = color(R.color.materialColorPrimary)

        val prefs = PreferenceManager.getInstance(ctx)
        val pm = ctx.packageManager

        // Installed icon packs (Nova/ADW/Atom/Apex intents), deduped by package, sorted by label,
        // with a leading System choice. Built once when the pill is created (edit-mode entry): a
        // few PM queries plus one loadIcon per pack, off any hot path. queryIntentActivities and
        // loadIcon are guarded so a flaky pack can't crash edit mode.
        val systemIcon: Drawable? = runCatching { pm.getApplicationIcon(ctx.packageName) }.getOrNull()
        val choices: List<PackChoice> = buildList {
            add(PackChoice("", systemIcon))
            iconPackIntents
                .flatMap { runCatching { pm.queryIntentActivities(it, 0) }.getOrDefault(emptyList()) }
                .associateBy { it.activityInfo.packageName }
                .values
                .sortedBy { it.loadLabel(pm).toString().lowercase() }
                .forEach {
                    add(PackChoice(it.activityInfo.packageName, runCatching { it.loadIcon(pm) }.getOrNull()))
                }
        }

        // Select the saved pack if it is still installed; otherwise show System selected without
        // rewriting the pref (an uninstalled pack simply resolves to default in the provider).
        val currentPkg = prefs.iconPackPackage.get()
        var selectedIdx = choices.indexOfFirst { it.packageName == currentPkg }.coerceAtLeast(0)

        val strip = ShapeStrip(ctx)
        lateinit var cells: List<IconPackCell>
        fun select(i: Int) {
            if (i == selectedIdx) return
            cells[selectedIdx].isChosen = false
            selectedIdx = i
            cells[i].isChosen = true
            bounce(cells[i])
            strip.centerOn(i)
            // Show the sparkle cover IMMEDIATELY on tap (owner 2026-08-31: it must start on the click,
            // not after the debounce) -- freeze() is a no-op if a cover is already up, so clicking
            // through packs keeps the one veil. Then DEBOUNCE the apply (see [pendingIconPackApply]):
            // each apply is a full model reload that cancels+restarts the previous, so clicking through
            // packs must not fire one reload per tap. commit() writes the pref (a no-op-safe flush on
            // edit-mode exit runs the same lambda). Because a pack change is an async reload (unlike
            // shape/tint, which apply on the next draw), the cover holds until
            // LawnchairLauncher.finishBindingItems (playFrozen) dissolves it over the finished new grid.
            coverHome(launcher, list)
            val pkg = choices[i].packageName
            pendingIconPackApply = { commitIconPack(ctx, list, prefs, pkg) }
            pendingIconPackView = strip
            pendingIconPackRunnable?.let { strip.removeCallbacks(it) }
            pendingIconPackRunnable = Runnable {
                pendingIconPackRunnable = null
                pendingIconPackView = null
                val commit = pendingIconPackApply ?: return@Runnable
                pendingIconPackApply = null
                commit()
            }.also { strip.postDelayed(it, ICON_PACK_APPLY_DEBOUNCE_MS) }
        }
        cells = choices.mapIndexed { i, choice ->
            IconPackCell(ctx, choice.icon, tonal).apply {
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
        strip.post { strip.centerOn(selectedIdx, smooth = false) }
        return listOf(Pill(pill) {})
    }

    /**
     * One icon-pack swatch: the pack's own launcher icon clipped to a circle (so a row of mixed
     * pack art reads uniformly), with an accent ring when chosen. A null icon (System with no
     * resolvable launcher icon) falls back to a filled accent disc.
     */
    private class IconPackCell(
        context: Context,
        icon: Drawable?,
        private val accent: Int,
    ) : View(context) {
        // Clone so setting bounds here never fights the PackageManager's shared drawable instance.
        private val icon: Drawable? = icon?.constantState?.newDrawable()?.mutate() ?: icon
        private val clip = Path()
        private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            color = accent
        }
        private val discPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            color = accent
        }
        private val iconBounds = Rect()

        var isChosen = false
            set(value) { if (field != value) { field = value; invalidate() } }

        init {
            isClickable = true
            isFocusable = true
        }

        override fun onDraw(canvas: Canvas) {
            val cx = width / 2f
            val cy = height / 2f
            // Shrink a touch when chosen to leave room for the ring.
            val r = minOf(width, height) / 2f * (if (isChosen) 0.78f else 0.90f)
            if (r <= 0f) return
            clip.reset()
            clip.addCircle(cx, cy, r, Path.Direction.CW)
            val save = canvas.save()
            canvas.clipPath(clip)
            val d = icon
            if (d != null) {
                val size = (r * 2f).toInt()
                val left = (cx - r).toInt()
                val top = (cy - r).toInt()
                iconBounds.set(left, top, left + size, top + size)
                d.bounds = iconBounds
                d.draw(canvas)
            } else {
                canvas.drawCircle(cx, cy, r, discPaint)
            }
            canvas.restoreToCount(save)
            if (isChosen) {
                ringPaint.strokeWidth = minOf(width, height) * 0.06f
                canvas.drawCircle(cx, cy, r + ringPaint.strokeWidth, ringPaint)
            }
        }
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
            // Travel far enough to clear the actual SCREEN edge, not just the pager's own bounds. The
            // pager is WRAP_CONTENT and centred on screen, so translating by `width` (~one pill wide)
            // stopped the outgoing pill at the pager edge and flipped it INVISIBLE there -- but with
            // clipChildren=false it was still on screen in the space beside the pager, so it "stopped
            // early and derendered" instead of sliding off the edge (owner 2026-08-31). Clear the near
            // edge: half the screen (pill sits centred) + half the pill + a small margin.
            val screenW = rootView?.width?.takeIf { it > 0 }?.toFloat()
                ?: resources.displayMetrics.widthPixels.toFloat()
            val pageW = (if (from.width > 0) from.width else width).toFloat().coerceAtLeast(1f)
            val travel = screenW / 2f + pageW / 2f + 8f * resources.displayMetrics.density
            // Incoming enters from the side the finger is heading toward.
            to.visibility = View.VISIBLE
            to.translationX = dir * travel
            to.animate().translationX(0f).setDuration(260).setInterpolator(DecelerateInterpolator()).start()
            from.animate().translationX(-dir * travel).setDuration(260).setInterpolator(DecelerateInterpolator())
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
