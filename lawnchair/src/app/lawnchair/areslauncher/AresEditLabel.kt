package app.lawnchair.areslauncher

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.graphics.Rect
import android.view.View
import android.view.ViewGroup
import android.view.animation.AccelerateDecelerateInterpolator
import com.android.launcher3.BubbleTextView
import com.android.launcher3.apppairs.AppPairIcon
import com.android.launcher3.folder.FolderIcon
import java.util.WeakHashMap

/**
 * **Hides a home tile's label while editing, and drops its icon to the centre of the cell.**
 *
 * > *"when in edit mode, let's try hiding the app names and centering the icon in its square/grid"*
 *
 * Edit mode is for *arranging*, not reading. The labels are the noisiest thing on the surface and
 * the least useful while tiles are being dragged around, so they fade out for the duration.
 *
 * The centring is not cosmetic tidying — it is required by the hiding. An icon sits **high** in its
 * cell only because the label needs the space beneath it: the profile lays a cell out as icon,
 * then drawable padding, then a line of text, top-aligned. Fade the text and the icon is left
 * visibly hanging off the top of an otherwise empty square. So the same transition that drops the
 * label slides the icon down to the cell's true centre, and puts it back on the way out.
 *
 * ## What moves, and what deliberately does not
 *
 * The lift is written to the **item view** — the `BubbleTextView`, `FolderIcon` or `AppPairIcon`
 * inside the holder — and *not* to the holder container. Everything else edit mode animates (the
 * float, the reflow, the 0.92 scale, the pick-up bump) moves the container, because the container
 * is what carries the × badge, the resize chevron and the §21 frost box along with the tile.
 *
 * Those three must **not** follow the icon down. The badges are positioned against the item's
 * allocated *cell*, and the frost outlines that cell — that is the whole point of §21, which exists
 * because badges drawn against a widget's own smaller content read as detached. Moving them with
 * the icon would undo it. So the icon slides inside a frost box that stays where the cell is, which
 * is also what makes the centring legible: there is a visible square for it to be centred in.
 *
 * A useful side effect: [AresEditMotion.DRAG_PRIORITY_RADIUS_DP], the small disc at a tile's exact
 * centre reserved for the drag, now sits **on the icon** rather than in the gap between it and the
 * label. Aiming at the middle of a tile to pick it up is now aiming at the picture.
 *
 * ## Which surfaces this applies to
 *
 * - **Apps and shortcuts on the home grid** — the case that was asked for.
 * - **Folder tiles on the home grid** — yes, same treatment. A grid where every tile centres its
 *   icon except the folder, which alone keeps a caption and stays high, reads as a bug rather than
 *   as a distinction. The folder's preview already shows its member icons, which identifies it at
 *   least as well as a truncated one-line title does. The counter-argument is real and recorded
 *   here rather than smoothed over: §18 makes a folder the one tile whose tap *does* something in
 *   edit mode (it descends into it), so its label is arguably an affordance and not just noise.
 *   Uniformity won because the whole request is a visual one; this is one line to reverse.
 * - **App pairs** — treated like folders, for consistency. Untested: there is no app pair on the
 *   fixture, and one cannot be created without the split-screen flow.
 * - **Widgets** — untouched. They have no label and no icon to centre; [labelOf] returns null for
 *   an `AppWidgetHostView` and nothing is written to it at all.
 * - **Apps inside an open folder (`AresFolderEdit`) — deliberately excluded.** Three reasons, in
 *   descending order of weight. The user already drew this exact line for the §21 frost — *"makes
 *   sense for it to just be on the home screen edit and not inside folders when editing"* — and the
 *   two are the same visual language, so they should not disagree about where they stop. A folder's
 *   cells are stock `CellLayout` cells and stock owns their translation during a rearrangement
 *   (`CellLayout.animateChildToPosition` drives `INDEX_REORDER_PREVIEW_OFFSET` on those very
 *   views), so a lift there is a fight rather than a composition. And `AresFolderEdit` positions
 *   each × badge by copying the icon's layout params and then tracking its translation every
 *   pre-draw ([AresEditMotion.setFollow]); a lifted icon would drag every badge down with it,
 *   which is the opposite of what the home grid wants. An open folder is also small and already
 *   captioned by its own name, so there is less label noise to remove.
 *
 * ## One entry point, so no path is missed
 *
 * [set] is idempotent and works out for itself whether to animate, so every caller can use it: the
 * bind, the child-attach hook and the edit-mode walk all funnel through
 * `AresHomeAdapter.syncEditVisualsFor` alongside the badges. That matters because the failure this
 * project keeps producing is a *restore* that misses a path — an × that outlived the mode, a
 * chevron on a row that was no longer editable. A label that stayed hidden after edit mode ended
 * would be worse than the thing it fixed, so it is bound to exactly the same funnel as the
 * affordances, plus [reset] on detach and [reassert] after layout.
 */
object AresEditLabel {

    /**
     * How long the label fades and the icon slides.
     *
     * The same 120ms as the edit-mode scale (`AresHomeListView.EDIT_SCALE_MS`) and the grid dots,
     * so entering the mode reads as one change rather than three staggered ones.
     */
    private const val TRANSITION_MS = 120L

    private class State {
        /** Whether this item is currently *meant* to be label-less. */
        var hidden = false

        /** The lift currently written to [AresEditMotion], in px. */
        var lift = 0f

        /** In-flight transition, so a fast toggle retargets instead of stacking. */
        var animator: ValueAnimator? = null
    }

    /**
     * Keyed on the **item view**, not the holder.
     *
     * `onBindViewHolder` re-inflates a fresh item view into a recycled container, so keying on the
     * container would carry the previous item's state onto a view that has none of it. Weak, so a
     * recycled view's entry goes with the view.
     */
    private val states = WeakHashMap<View, State>()

    /** Reused; every read of it is finished before the next one starts. */
    private val bounds = Rect()

    /**
     * Brings the item inside [container] in line with the mode.
     *
     * Safe to call repeatedly with the same value — that is what lets the bind, the attach hook and
     * the mode walk all use it. It animates only when the state actually **changes** and the view
     * has already been laid out; a row bound or attached mid-mode has no height yet, so it snaps
     * into the right state instead of animating from a position it never occupied.
     */
    fun set(container: View, hidden: Boolean) {
        val item = itemOf(container) ?: return
        val label = labelOf(item) ?: return
        val state = states.getOrPut(item) { State() }
        val targetLift = if (hidden) liftFor(item) else 0f

        if (state.hidden == hidden && state.animator == null) {
            // Already correct. The lift can still be stale — it is measured, and the row may have
            // been laid out (or folded, or repacked) since. See [reassert].
            if (state.lift != targetLift) writeLift(item, state, targetLift)
            return
        }

        val fromAlpha = BubbleTextView.TEXT_ALPHA_PROPERTY.get(label)
        // Same rule stock's own createTextAlphaAnimator uses, so restoring cannot re-show a label
        // that some other policy (the dock-label preference) wanted hidden anyway.
        val toAlpha = if (hidden || !label.shouldTextBeVisible()) 0f else 1f
        val fromLift = state.lift

        state.animator?.cancel()
        state.animator = null
        state.hidden = hidden

        if (!ValueAnimator.areAnimatorsEnabled() || item.height <= 0) {
            BubbleTextView.TEXT_ALPHA_PROPERTY.set(label, toAlpha)
            writeLift(item, state, targetLift)
            return
        }

        state.animator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = TRANSITION_MS
            interpolator = AccelerateDecelerateInterpolator()
            addUpdateListener {
                val t = it.animatedFraction
                BubbleTextView.TEXT_ALPHA_PROPERTY.set(label, fromAlpha + (toAlpha - fromAlpha) * t)
                writeLift(item, state, fromLift + (targetLift - fromLift) * t)
            }
            addListener(
                object : AnimatorListenerAdapter() {
                    override fun onAnimationEnd(animation: Animator) {
                        if (state.animator === animation) state.animator = null
                    }
                },
            )
            start()
        }
    }

    /**
     * Re-measures the lift on an already-hidden item and corrects it if it has drifted.
     *
     * Two things make it drift, and both are normal: a row bound or attached mid-mode has height 0
     * at the moment [set] runs, so it takes a lift of zero and needs correcting once the layout
     * manager has sized it; and folding changes the device profile, so every cell's height — and
     * therefore its centre — moves under the tiles.
     *
     * Called from `AresHomeListView` after layout and after each scroll, both of which run before
     * the frame is drawn, so the correction is never visible as a jump.
     */
    fun reassert(container: View) {
        val item = itemOf(container) ?: return
        val state = states[item] ?: return
        // Never during a transition: the animator is mid-interpolation toward its own target and
        // overwriting it would stutter. It re-measures on the next call anyway.
        if (!state.hidden || state.animator != null) return
        val target = liftFor(item)
        if (target != state.lift) writeLift(item, state, target)
    }

    /**
     * Puts the item inside [container] back to a visible label at rest, and forgets it.
     *
     * The counterpart to `AresEditWiggle.reset`, and called from the same place for the same
     * reason: a detached row is about to be recycled, and anything left half-applied would be
     * inherited by whatever item is bound into the view next.
     */
    fun reset(container: View) {
        val item = itemOf(container) ?: return
        val state = states.remove(item)
        state?.animator?.cancel()
        labelOf(item)?.let {
            BubbleTextView.TEXT_ALPHA_PROPERTY.set(it, if (it.shouldTextBeVisible()) 1f else 0f)
        }
        AresEditMotion.setLift(item, 0f)
    }

    private fun writeLift(item: View, state: State, lift: Float) {
        state.lift = lift
        AresEditMotion.setLift(item, lift)
    }

    /**
     * The item view inside a holder container.
     *
     * Child 0 by construction — `AresHomeAdapter.onBindViewHolder` adds it before any affordance,
     * and the affordances are appended above it.
     */
    private fun itemOf(container: View): View? = (container as? ViewGroup)?.getChildAt(0)

    /**
     * The `BubbleTextView` that draws [item]'s caption, or null when it has none.
     *
     * For an app the caption and the icon are the *same* view, which is why the text is faded via
     * `TEXT_ALPHA_PROPERTY` rather than `View.alpha` — the latter would fade the icon with it. A
     * folder and an app pair each keep their caption in a separate child and draw their picture
     * themselves, but going through the same property keeps one code path.
     *
     * An `AppWidgetHostView` matches nothing here and is left entirely alone.
     */
    private fun labelOf(item: View): BubbleTextView? = when (item) {
        is FolderIcon -> item.folderName
        is AppPairIcon -> item.titleTextView
        is BubbleTextView -> item
        else -> null
    }

    /** How far [item] must slide for its icon to sit in the middle of its cell, in px. */
    private fun liftFor(item: View): Float {
        val height = item.height
        if (height <= 0) return 0f
        val centreY = iconCentreY(item) ?: return 0f
        return height / 2f - centreY
    }

    /**
     * Where [item]'s picture is currently drawn, vertically, in the item's own coordinates.
     *
     * Measured rather than derived from the profile: the three item types position their picture by
     * three different rules (a compound drawable at `paddingTop`, a preview background offset, a
     * child view), and each of them already exposes the answer.
     */
    private fun iconCentreY(item: View): Float? = when (item) {
        is FolderIcon -> {
            item.getPreviewBounds(bounds)
            bounds.exactCenterY()
        }
        is AppPairIcon -> item.iconDrawableArea?.let { (it.top + it.bottom) / 2f }
        is BubbleTextView -> {
            item.getIconBounds(bounds)
            bounds.exactCenterY()
        }
        else -> null
    }
}
