package app.lawnchair.arestests

import com.android.app.viewcapture.data.ExportedData
import com.android.app.viewcapture.data.ViewNode
import kotlin.math.abs

/**
 * Per-FRAME movement analysis over a ViewCapture recording.
 *
 * ## Why this exists
 *
 * Assertions about "the grid jumped" have to be made per rendered frame, and the only instrument in
 * this project that can see a frame is ViewCapture. The tempting alternative — poll a channel on a
 * timer and diff consecutive samples — silently answers a different question. Measured during the
 * bottom-row swap drag (ledger row 68a): the nominal 40ms sampler actually returned gaps of
 * **44–115ms**, i.e. 3–7 frames at 60fps, so several frames of legitimate edge auto-scroll piled up
 * into one apparent teleport and the test failed a launcher that was behaving. A capture records
 * every `onDraw`, so consecutive entries really are consecutive frames.
 *
 * ## Why translationY and not top
 *
 * `AresMasonryLayoutManager` scrolls by TRANSLATION, never by relayout. Measured across a 945-frame
 * capture, the largest per-node change in `top` was **0.0px** — the field never moves, so a "nothing
 * jumped" answer read off it means nothing at all. `translationY` is where the movement lives.
 *
 * Nodes are matched by `hashcode` across frames, so a view that appears or is recycled mid-capture
 * simply has no predecessor and contributes nothing, rather than registering as an infinite jump.
 */
object AresCaptureAnalysis {

    /** The largest single-frame movement found, and enough context to identify it. */
    data class Jump(
        val px: Float,
        /** The `hashcode` of the view that moved, as ViewCapture records it. */
        val hashcode: Int,
        /**
         * The moving view's class, resolved through `ExportedData.classname`.
         *
         * Load-bearing, not decoration: matching nodes by `hashcode` also matches a view that
         * RecyclerView **recycled** — same object, rebound to a different item, legitimately
         * repositioned in one frame — and a drag view being carried by the finger. Without the
         * class name a caller cannot tell "the grid teleported" from "a row got reused", and would
         * be left tuning a threshold against a number it does not understand.
         */
        val className: String,
        /** Index of the frame the movement landed ON (its predecessor is `frameIndex - 1`). */
        val frameIndex: Int,
        /** How many frame-to-frame comparisons were actually made; 0 means nothing was measured. */
        val comparisons: Int,
    ) {
        override fun toString() = "%.1fpx on %s (view %d) at frame %d (%d comparisons)"
            .format(px, className, hashcode, frameIndex, comparisons)
    }

    /**
     * The largest `translationY` change any single view underwent between two consecutive frames.
     *
     * Returns `px = 0` with `comparisons = 0` when the capture holds fewer than two frames — the
     * caller must treat that as "not measured" rather than "nothing moved", which is why the count
     * is part of the result instead of being dropped.
     */
    fun maxPerFrameTranslationYJump(
        data: ExportedData,
        /**
         * Restricts the scan to views whose class name contains this. Pass null to scan everything,
         * which is the right first move when you do not yet know what moves; pass a class once you
         * do, so the answer is about the surface you mean rather than whatever moved most.
         */
        classFilter: String? = null,
    ): Jump {
        val names = data.classnameList
        fun nameOf(node: ViewNode): String =
            names.getOrNull(node.classnameIndex)?.substringAfterLast('.') ?: "?"

        var worst = Jump(0f, 0, "none", -1, 0)
        var comparisons = 0
        data.windowDataList.forEach { window ->
            var previous: Map<Int, Pair<Float, String>>? = null
            window.frameDataList.forEachIndexed { index, frame ->
                val current = HashMap<Int, Pair<Float, String>>()
                if (frame.hasNode()) collect(frame.node, current, ::nameOf)
                previous?.let { before ->
                    comparisons++
                    before.forEach { (hashcode, was) ->
                        val now = current[hashcode] ?: return@forEach
                        if (classFilter != null && !now.second.contains(classFilter)) return@forEach
                        val moved = abs(now.first - was.first)
                        if (moved > worst.px) worst = Jump(moved, hashcode, now.second, index, comparisons)
                    }
                }
                previous = current
            }
        }
        return worst.copy(comparisons = comparisons)
    }

    /** How many views moved past the threshold together in the worst single frame. */
    data class Coordinated(
        /** The largest number of views that each moved more than the threshold in ONE frame. */
        val views: Int,
        val frameIndex: Int,
        val medianPx: Float,
        val comparisons: Int,
    ) {
        override fun toString() =
            "%d view(s) moved >threshold together at frame %d, median %.1fpx (%d comparisons)"
                .format(views, frameIndex, medianPx, comparisons)
    }

    /**
     * The worst COORDINATED movement: how many views jumped past [thresholdPx] in the same frame.
     *
     * This is the metric that separates the two things a raw per-view maximum cannot. Measured on
     * emulator-5554 during the bottom-row swap drag, the largest single-view jump is ~1109px on a
     * `FrameLayout` — which looks alarming and is not: it is a tile holder RecyclerView RECYCLED,
     * rebound to a different item, and since this grid scrolls by translation (its `top` never
     * changes) a reused container's `translationY` necessarily moves by a whole screen. One view
     * doing that is bookkeeping. The defect this test exists for — the stock `onMoved` calling
     * `scrollToPosition`, an ABSOLUTE jump — moves the WHOLE grid, so every visible tile steps by
     * the same large delta in one frame.
     *
     * So: count, do not maximise.
     */
    fun worstCoordinatedJump(
        data: ExportedData,
        thresholdPx: Float,
    ): Coordinated {
        var worst = Coordinated(0, -1, 0f, 0)
        var comparisons = 0
        data.windowDataList.forEach { window ->
            var previous: Map<Int, Pair<Float, String>>? = null
            window.frameDataList.forEachIndexed { index, frame ->
                val current = HashMap<Int, Pair<Float, String>>()
                if (frame.hasNode()) collect(frame.node, current) { "" }
                previous?.let { before ->
                    comparisons++
                    val movers = before.mapNotNull { (hashcode, was) ->
                        val now = current[hashcode] ?: return@mapNotNull null
                        abs(now.first - was.first).takeIf { it > thresholdPx }
                    }.sorted()
                    if (movers.size > worst.views) {
                        worst = Coordinated(movers.size, index, movers[movers.size / 2], comparisons)
                    }
                }
                previous = current
            }
        }
        return worst.copy(comparisons = comparisons)
    }

    private fun collect(
        node: ViewNode,
        into: MutableMap<Int, Pair<Float, String>>,
        nameOf: (ViewNode) -> String,
    ) {
        into[node.hashcode] = node.translationY to nameOf(node)
        node.childrenList.forEach { collect(it, into, nameOf) }
    }
}
