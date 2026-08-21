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
 * - **Apps inside an open folder (`AresFolderEdit`) — included, via [setItem].** This bullet used
 *   to say the opposite, and the three reasons it gave are kept here rather than deleted, because
 *   two of them were *wrong* and the way they were wrong is worth recognising again:
 *
 *   1. *"The user already drew this line for the §21 frost."* True when written, and the only real
 *      reason of the three — but a preference, not a fact, and they reversed it: the frost went
 *      into folders because a corner badge with no box to be in the corner of reads as floating.
 *      Labels follow it for the same reason the frost did. A grid where every tile centres its
 *      icon and the folder's contents alone stay high reads as a bug, not as a distinction.
 *   2. *"Stock owns those views' translation, so a lift there is a fight."* Wrong. Stock's
 *      rearrangement drives `INDEX_REORDER_PREVIEW_OFFSET`; every write in [AresEditMotion] lands
 *      on `INDEX_REORDER_BOUNCE_OFFSET` (see `AresEditMotion.write`). A `MultiTranslateDelegate`
 *      exists precisely so independent channels **sum** instead of fighting — which is the whole
 *      reason that file has a single writer. The folder float has composed with the stock slide
 *      since it shipped; the lift joins it on the same channel.
 *   3. *"A lifted icon would drag every badge down with it."* Wrong, and refutable by reading the
 *      one line it is about: `AresFolderEdit` copies `getTranslation(INDEX_REORDER_PREVIEW_OFFSET)`
 *      into [AresEditMotion.setFollow] — the stock channel only, never the total. The badge tracks
 *      the *slide* and is blind to the lift, so it does exactly what the home grid wants without
 *      any change: it stays on the cell while the icon moves inside it.
 *
 *   Both wrong reasons share a shape this project has produced before: a claim about a channel or a
 *   cast, plausible from the class names, never checked against the line it describes.
 *
 *   The folder's own name in the footer is **not** hidden. It is not an app name, it is the only
 *   text identifying which folder is open, and hiding it would mask D9 — the name jumping from the
 *   footer to the centre when an item moves — rather than fix it.
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
        setItem(item, hidden)
    }

    /**
     * [set], for a surface whose icon is **not** wrapped in a holder container.
     *
     * An open folder's cells hold the `BubbleTextView` itself — there is no per-tile container to
     * unwrap, because a folder page is a stock `CellLayout` and the icon is its own direct child.
     * Everything else is identical, including the idempotence: `AresFolderEdit.sync` runs on every
     * pre-draw and calls this each time, which is what re-measures the lift once the folder has
     * laid its icons out. (The home grid needs an explicit [reassert] for that only because its
     * funnel does *not* run every frame.)
     */
    fun setItem(item: View, hidden: Boolean) {
        val label = labelOf(item) ?: return
        val state = states.getOrPut(item) { State() }
        val targetLift = if (hidden) liftFor(item) else 0f

        if (state.hidden == hidden) {
            // Already in the right state, **or already on its way there**. The second half is what
            // makes this safe to call every frame, and leaving it out is not a missed nicety --
            // it silently disables the whole transition.
            //
            // The guard used to also require `state.animator == null`, so an in-flight fade fell
            // through to the code below, which cancels it and starts a fresh one from wherever the
            // view currently is. On the home grid that is harmless: the funnel runs on bind and on
            // the mode walk, so the animator is started once and left alone. `AresFolderEdit.sync`
            // is a **pre-draw listener** and the edit-mode float is a never-ending animator, so it
            // runs on every single frame -- and the fade was therefore cancelled and restarted from
            // alpha 1.0 roughly every 19ms, forever. Measured on emulator-5554 before the fix: an
            // icon reporting `hidden=true, anim=true` for as long as the folder was open, with
            // `alpha=1.0` and `lift=0.0` on every frame -- the transition perpetually reborn and
            // never once advancing.
            //
            // This is the same failure the folder's own reorder alarm has, and this file's
            // neighbour already describes it: `Folder.onDragOver` re-arms `REORDER_DELAY` on every
            // change of target, so while the finger moves nothing ever rearranges. See
            // `AresFolderEdit.reorderDelayMs`. A repeating caller must not restart work that is
            // already heading where it asked.
            if (state.animator == null) {
                // Settled. Assert both ends rather than trusting the flag: the lift is measured and
                // the row may have been laid out, folded or repacked since (see [reassert]), and
                // the text alpha is owned by other policies too -- `CellLayout.addViewToCellLayout`
                // sets it on every re-add, which is what `FolderPagedView.arrangeChildren` does to
                // every icon in the folder.
                val want = if (hidden || !label.shouldTextBeVisible()) 0f else 1f
                if (BubbleTextView.TEXT_ALPHA_PROPERTY.get(label) != want) {
                    BubbleTextView.TEXT_ALPHA_PROPERTY.set(label, want)
                }
                if (state.lift != targetLift) writeLift(item, state, targetLift)
            }
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

        // The TEXT, not only the lift.
        //
        // This used to re-measure the lift alone, on the reasoning that the alpha cannot drift
        // because nothing else writes it. Stock does: `Folder.closeComplete` runs
        // `mFolderIcon.mFolderName.setTextVisibility(true)`, which is this exact property, and it
        // is reached on the most ordinary path there is — §18 makes tapping a folder the one tap
        // edit mode does NOT make inert, so opening a folder and closing it again leaves that one
        // tile captioned while every other tile on the grid is bare, for the rest of the mode.
        //
        // Worse under B2, where dwelling out of a folder closes it mid-drag
        // (`AresFolderPreview.close` -> `aresEndPreviewDrag` -> `close(true)` -> same
        // closeComplete), so the caption pops back on every dwell-out with the finger still down.
        //
        // None of the four write sites covers it: the row is not detached, not rebound, and the
        // edit-mode walk does not re-run, so this hook is the only thing that fires afterwards.
        // The same argument the settled branch of [setItem] already makes — the alpha is owned by
        // other policies too — applies here and was simply missed.
        // Unconditionally 0: this branch is only reached when the item is meant to be label-less.
        labelOf(item)?.let { label ->
            if (BubbleTextView.TEXT_ALPHA_PROPERTY.get(label) != 0f) {
                BubbleTextView.TEXT_ALPHA_PROPERTY.set(label, 0f)
            }
        }

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
        resetItem(item)
    }

    /**
     * [reset], for an unwrapped item view. See [setItem].
     *
     * Harmless on a view this file has never touched — a folder's × badge cell arrives here from
     * `AresFolderEdit`'s sweep over everything that left the folder, and falls straight through
     * [labelOf] with nothing written. That is deliberate: the sweep must not have to know which of
     * the two views it is holding.
     *
     * Note what this does **not** rely on. `AresEditWiggle.reset` already clears every
     * [AresEditMotion] contribution for a view, the lift included, so a folder icon that stops
     * editing gets its translation back either way. The text alpha is the part only this knows
     * about, and a label left at alpha 0 on a view the folder's cache is about to rebind would be
     * an app with no name and no way to get one back.
     */
    fun resetItem(item: View) {
        val state = states.remove(item)
        state?.animator?.cancel()
        labelOf(item)?.let {
            BubbleTextView.TEXT_ALPHA_PROPERTY.set(it, if (it.shouldTextBeVisible()) 1f else 0f)
        }
        AresEditMotion.setLift(item, 0f)
    }

    /**
     * How far [item] is currently slid from its layout position, in px. Zero when it is not hidden.
     *
     * For a caller that has to map a point between the item and something that is **not** moving
     * with it. `AresFolderEdit.isPointOnBadgeFor` is the case: a folder's × rides in a sibling cell
     * whose layout box is identical to the icon's, and it used to be true that a point in one was
     * already a point in the other. The lift ends that — it is written to the icon and not to the
     * cell, precisely so the badge stays on the cell — so the difference has to be added back.
     */
    fun liftOf(item: View): Float = states[item]?.lift ?: 0f

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
