package app.lawnchair.arestests

import com.android.app.viewcapture.data.ExportedData
import com.android.app.viewcapture.data.ViewNode
import kotlin.math.abs

/**
 * Explains a `PositionJumpDetector` anomaly by reconstructing the walk that produced it.
 *
 * `ViewCaptureAnalyzer` reports a jump as one line — a path, a border, and a number — which is
 * enough to fail a test and not nearly enough to say whether the jump is a real defect. The number
 * is an ABSOLUTE window coordinate accumulated down the whole view tree, so a jump attributed to a
 * leaf can have been introduced by any ancestor, and the reported path (`class:id` per level, no
 * sibling index) does not even identify WHICH of thirty identically-named tiles moved.
 *
 * This walks the same tree the same way — same alpha gate, same scale handling, same
 * `left + translationX` composition — but keeps every level, so a jump can be attributed to the
 * level that actually introduced it. It is a diagnostic, not an assertion: it answers "what moved",
 * and a human decides whether that was wrong.
 */
object AresCaptureExplain {

    /** One view at one frame, positioned exactly as `ViewCaptureAnalyzer` would position it. */
    data class Placed(
        val hashcode: Int,
        val className: String,
        val id: String,
        val left: Float,
        val top: Float,
        val width: Float,
        val height: Float,
        /** This view's own contribution, before ancestors: `left + translationX`. */
        val localLeft: Float,
        val localTop: Float,
        val translationX: Float,
        val translationY: Float,
        val scaleX: Float,
        val alpha: Float,
        val depth: Int,
        val parent: Placed?,
    ) {
        fun chain(): List<Placed> = generateSequence(this) { it.parent }.toList().reversed()

        override fun toString() =
            "%s%s#%d local=(%.1f,%.1f) trans=(%.1f,%.1f) abs=(%.1f,%.1f) %.0fx%.0f sx=%.2f a=%.2f"
                .format(className, if (id.isEmpty() || id == "NO_ID") "" else ":$id", hashcode,
                    localLeft, localTop, translationX, translationY, left, top, width, height,
                    scaleX, alpha)
    }

    /** A single view's absolute position changing by more than [minJumpPx] between adjacent frames. */
    data class Jump(
        val axis: String,
        val px: Float,
        val frameIndex: Int,
        /** Wall-clock gap to the previous CAPTURED frame. A tween collapses into one step if this is large. */
        val gapMs: Float,
        val before: Placed,
        val after: Placed,
    ) {
        /**
         * The ancestor that introduced the jump: the SHALLOWEST level whose own absolute position
         * moved by about as much. If that is the jumping view itself, nothing above it moved and the
         * view really did relocate inside a stationary parent.
         */
        fun origin(): Pair<Placed, Placed>? {
            val beforeChain = before.chain()
            val afterChain = after.chain()
            for (i in beforeChain.indices) {
                val b = beforeChain[i]
                val a = afterChain.getOrNull(i) ?: continue
                if (b.hashcode != a.hashcode) continue
                val moved = if (axis == "left") abs(a.left - b.left) else abs(a.top - b.top)
                if (moved >= px * 0.9f) return b to a
            }
            return null
        }
    }

    /**
     * Every per-view absolute-position jump over [minJumpPx], with full ancestry on both sides.
     *
     * Views absent from either frame are skipped, matching the detector: it compares only when the
     * same hashcode was placed in the immediately preceding frame, so an attach, a detach or an
     * alpha-0 subtree never counts as movement.
     */
    fun jumps(data: ExportedData, minJumpPx: Float): List<Jump> {
        val names = data.classnameList
        val out = ArrayList<Jump>()
        data.windowDataList.forEach { window ->
            var previous: Map<Int, Placed> = emptyMap()
            var previousTimeNs = 0L
            window.frameDataList.forEachIndexed { index, frame ->
                val current = HashMap<Int, Placed>()
                place(frame.node, null, 0f, 0f, 1f, 1f, 1f, 0, names, current)
                val gapMs = if (index > 0) (frame.timestamp - previousTimeNs) / 1_000_000f else 0f
                if (index > 0) {
                    current.forEach { (hashcode, now) ->
                        val was = previous[hashcode] ?: return@forEach
                        val dx = abs(now.left - was.left)
                        val dy = abs(now.top - was.top)
                        if (dx >= minJumpPx) out += Jump("left", dx, index, gapMs, was, now)
                        if (dy >= minJumpPx) out += Jump("top", dy, index, gapMs, was, now)
                    }
                }
                previous = current
                previousTimeNs = frame.timestamp
            }
        }
        return out.sortedByDescending { it.px }
    }

    /**
     * Every frame in which [hashcode] was placed, so a jump can be read in context.
     *
     * The single most useful thing after "what moved" is "for how long". One frame displaced and
     * back is a flash; twenty frames is an animation that ran. A jump number alone cannot tell them
     * apart, and on this surface that difference decides whether anything is wrong at all.
     */
    fun trace(data: ExportedData, hashcode: Int): List<Pair<Int, Placed>> {
        val names = data.classnameList
        val out = ArrayList<Pair<Int, Placed>>()
        data.windowDataList.forEach { window ->
            window.frameDataList.forEachIndexed { index, frame ->
                val current = HashMap<Int, Placed>()
                place(frame.node, null, 0f, 0f, 1f, 1f, 1f, 0, names, current)
                current[hashcode]?.let { out += index to it }
            }
        }
        return out
    }

    private fun place(
        node: ViewNode,
        parent: Placed?,
        leftShift: Float,
        topShift: Float,
        parentScaleX: Float,
        parentScaleY: Float,
        parentAlpha: Float,
        depth: Int,
        names: List<String>,
        into: MutableMap<Int, Placed>,
    ) {
        // Same gate as ViewCaptureAnalyzer: an invisible subtree is not placed at all, so it can
        // never register as movement when it comes back.
        val alpha = parentAlpha * node.alpha * (if (node.visibility == 0) 1f else 0f)
        if (alpha <= 0f) return

        val scaleX = parentScaleX * node.scaleX
        val scaleY = parentScaleY * node.scaleY
        val left = leftShift + (node.left + node.translationX) * parentScaleX +
            node.width * (parentScaleX - scaleX) / 2
        val top = topShift + (node.top + node.translationY) * parentScaleY +
            node.height * (parentScaleY - scaleY) / 2

        val placed = Placed(
            hashcode = node.hashcode,
            className = names.getOrNull(node.classnameIndex)?.substringAfterLast('.') ?: "?",
            id = node.id ?: "",
            left = left, top = top,
            width = node.width * scaleX, height = node.height * scaleY,
            localLeft = node.left.toFloat(), localTop = node.top.toFloat(),
            translationX = node.translationX, translationY = node.translationY,
            scaleX = scaleX, alpha = alpha, depth = depth, parent = parent,
        )
        into[node.hashcode] = placed
        node.childrenList.forEach {
            place(it, placed, left, top, scaleX, scaleY, alpha, depth + 1, names, into)
        }
    }
}
