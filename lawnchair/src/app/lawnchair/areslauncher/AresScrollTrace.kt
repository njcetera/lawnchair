package app.lawnchair.areslauncher

import android.view.ViewTreeObserver
import com.android.launcher3.Launcher

/**
 * Records the home grid's scroll offset ONCE PER DRAWN FRAME, in-product.
 *
 * ## Why this exists rather than a channel poll or a ViewCapture
 *
 * The question this answers is narrow: did the grid move in ONE frame, or glide over several? Ledger
 * row 27 is a defect where the inherited `onMoved` calls `scrollToPosition`, an ABSOLUTE jump, while
 * `ItemTouchHelper`'s legitimate edge auto-scroll moves the same number gradually. Total travel
 * cannot separate them; the largest single-frame delta can.
 *
 * Both instruments this project already had are wrong for it, each for its own measured reason:
 *
 *  - **A channel poll** looks like it samples per frame and does not. Measured (ledger row 68a): a
 *    40ms sampler actually returned gaps of 44-115ms — 3 to 7 frames at 60fps — so it summed several
 *    frames of legitimate auto-scroll into one apparent teleport and failed a launcher that was
 *    behaving.
 *  - **A ViewCapture** does record every `onDraw`, which is why it replaced the poll. But it cannot
 *    see a `ViewPropertyAnimator` translation at all (ledger row 75): an in-product probe reading the
 *    Views' own `translationX/Y` mid-flight showed tiles at exactly HALF their armed value at t=100ms
 *    of a 200ms animation, while the capture of those same tiles showed 20+ flat frames and then a
 *    single-frame 762px jump. Every reflow therefore reads as a teleport, which is why the check
 *    built on it has been standing red without ever describing a real defect.
 *
 * So: sample the number itself, from inside the process, on the same callback the frame is drawn on.
 * No capture to be blind, no poll to alias. This is the instrument that settled row 75, in the shape
 * row 27 needs.
 *
 * Cheap by construction — one int read per frame into a preallocated array, recording only between
 * [start] and [stop], and bounded so a forgotten trace cannot grow without limit.
 */
object AresScrollTrace {

    /** ~17 seconds at 60fps. A swap drag is under 3s; anything longer is a leak, not a measurement. */
    private const val MAX_SAMPLES = 1024

    private val offsets = IntArray(MAX_SAMPLES)
    private val timesMs = LongArray(MAX_SAMPLES)
    private var count = 0
    private var recording = false

    private var listener: ViewTreeObserver.OnDrawListener? = null
    private var host: com.android.launcher3.Launcher? = null

    /** Begins recording. Idempotent: a second start restarts cleanly rather than double-listening. */
    @JvmStatic
    fun start(launcher: Launcher): String {
        stop(launcher)
        val list = launcher.workspace?.aresHomeList ?: return "no-home-list"
        val lm = list.layoutManager as? AresMasonryLayoutManager ?: return "no-masonry"
        recording = true
        host = launcher
        // SEED sample 0 with the offset as it is RIGHT NOW, rather than waiting for the first draw.
        // A static screen does not redraw, so without this the recording begins at whatever the next
        // draw happens to show -- and an instantaneous seek produces exactly ONE draw, already at its
        // destination, which the diff then reads as no movement at all. Measured 2026-09-03: the
        // teleport control recorded `frames=1|offsets=1682|maxStep=0` and reported a textbook
        // teleport as perfectly smooth.
        offsets[0] = lm.scrollOffsetPx()
        timesMs[0] = android.os.SystemClock.uptimeMillis()
        count = 1
        val l = ViewTreeObserver.OnDrawListener {
            // onDraw, not a Choreographer callback: this fires when the grid actually DRAWS, which
            // is the same instant ViewCapture samples, so the two are directly comparable and a
            // frame the grid skipped is absent from both rather than interpolated by one of them.
            if (recording && count < MAX_SAMPLES) {
                offsets[count] = lm.scrollOffsetPx()
                timesMs[count] = android.os.SystemClock.uptimeMillis()
                count++
            }
        }
        list.viewTreeObserver.addOnDrawListener(l)
        listener = l
        return "started"
    }

    /**
     * POSITIVE CONTROL: performs the very jump row 27 is about, so the metric built on [dump] can be
     * made to FAIL rather than merely asserted to pass.
     *
     * `scrollToPosition` is an ABSOLUTE seek -- the offset lands in a single frame -- which is
     * exactly the shape of the defect and the opposite of the decelerating ramp a healthy
     * `smoothScrollBy` draws. Without this, "no jump" is indistinguishable from an inert instrument,
     * which is the failure mode that left the previous check standing red for weeks.
     */
    @JvmStatic
    fun teleport(launcher: Launcher): String {
        val list = launcher.workspace?.aresHomeList ?: return "no-home-list"
        val before = (list.layoutManager as? AresMasonryLayoutManager)?.scrollOffsetPx() ?: return "no-masonry"
        // The LAST item, so a seek from the top covers the whole list in one frame. Seeking to a
        // near neighbour would move too little for the fraction to mean anything.
        val last = (list.adapter?.itemCount ?: 0) - 1
        if (last < 0) return "empty-list"
        list.scrollToPosition(last)
        return "teleported|from=$before|position=$last"
    }

    /** Stops recording and leaves the samples readable by [dump]. */
    @JvmStatic
    fun stop(launcher: Launcher): String {
        recording = false
        val l = listener ?: return "not-recording"
        launcher.workspace?.aresHomeList?.viewTreeObserver?.removeOnDrawListener(l)
        listener = null
        host = null
        return "stopped|frames=$count"
    }

    /**
     * `frames=N|maxStep=P|maxStepAtMs=T|total=D|offsets=...`
     *
     * `maxStep` is the largest offset change between CONSECUTIVE DRAWN FRAMES — the number row 27
     * turns on. `total` is reported alongside it precisely so the two cannot be confused: a long
     * gentle auto-scroll has a large total and a small maxStep, and the defect is the reverse.
     *
     * Measured 2026-09-03, and the reason the threshold built on this is a FRACTION rather than a
     * pixel count: ten runs on healthy code put the largest step at a consistent ~250px, because the
     * first frame of the drop's decelerating scroll legitimately covers a quarter of the travel.
     */
    @JvmStatic
    fun dump(): String {
        if (count == 0) {
            return "frames=0|maxStep=0|maxStepAtMs=0|movingFrames=0|total=0|offsets="
        }
        var maxStep = 0
        var maxAt = 0L
        var moving = 0
        for (i in 1 until count) {
            val step = kotlin.math.abs(offsets[i] - offsets[i - 1])
            if (step > 0) moving++
            if (step > maxStep) {
                maxStep = step
                maxAt = timesMs[i] - timesMs[0]
            }
        }
        val total = kotlin.math.abs(offsets[count - 1] - offsets[0])
        val sample = (0 until count).joinToString(",") { offsets[it].toString() }
        return "frames=$count|maxStep=$maxStep|maxStepAtMs=$maxAt|movingFrames=$moving|" +
            "total=$total|offsets=$sample"
    }
}
