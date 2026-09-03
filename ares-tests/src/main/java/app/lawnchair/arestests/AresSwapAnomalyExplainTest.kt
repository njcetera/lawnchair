package app.lawnchair.arestests

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.LargeTest
import com.android.app.viewcapture.data.ExportedData
import com.android.launcher3.util.viewcapture_analysis.ViewCaptureAnalyzer
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Attributes `AresSwapGeometryTest`'s standing ViewCapture anomaly to the view that caused it.
 *
 * The detector's own output — *"Position jump: left jumped by 758.49"* on a path ending
 * `AresHomeListView|FrameLayout|ImageView` — cannot settle whether that is a real snap. The number
 * is an absolute window coordinate accumulated down the tree, so any ancestor could have introduced
 * it, and the path names a CLASS at each level, not a sibling, so it does not even identify which of
 * ~30 identically-shaped tiles moved. Reasoning from it has already produced one retracted finding
 * on this surface (2026-09-03).
 *
 * So: re-walk the capture, keep every level, and print the jumping view's full ancestry on both
 * sides of the frame boundary. Reports, never asserts — the point is to find out what the instrument
 * is seeing before anyone decides what it means.
 *
 * ```
 *   adb push <capture>.pb /sdcard/ares-swap-capture.pb
 *   bash design/scripts/run-ares-tests.sh <serial> AresSwapAnomalyExplainTest
 * ```
 *
 * SKIPs when no fixture is present, which is the normal state.
 */
@LargeTest
@RunWith(AndroidJUnit4::class)
class AresSwapAnomalyExplainTest {

    @Test
    fun explainWhateverJumped() {
        val driver = AresLauncherDriver()
        val bytes = driver.readCapturedProto(FIXTURE_PATH)
        assumeTrue("no readable capture at $FIXTURE_PATH", bytes != null && bytes.isNotEmpty())

        val parsed = runCatching {
            val coded = com.google.protobuf.CodedInputStream.newInstance(bytes)
            coded.setSizeLimit(Int.MAX_VALUE)
            ExportedData.parseFrom(coded)
        }
        parsed.exceptionOrNull()?.let { Log.e(TAG, "fixture did not parse", it) }
        val data = parsed.getOrNull()
        assumeTrue("fixture did not parse", data != null)
        data!!

        val frames = data.windowDataList.sumOf { it.frameDataList.size }
        Log.i(TAG, "=== ${data.windowDataList.size} window(s), $frames frames ===")

        // What the detector itself says, so the two accounts can be compared directly.
        ViewCaptureAnalyzer.getAnomalies(data).forEach { (path, message) ->
            Log.w(TAG, "DETECTOR ${path.substringAfterLast("drag_layer|")} -> $message")
        }

        // CADENCE FIRST. ViewCapture samples on DRAW, so its "adjacent frames" are adjacent DRAWS,
        // not adjacent vsyncs. If the app stalls, a whole 200ms tween lands between two captured
        // frames and every animating view appears to teleport -- with no way to tell that from a
        // real snap by looking at positions alone. The gap distribution is what separates them.
        val gaps = data.windowDataList.flatMap { w ->
            w.frameDataList.zipWithNext { a, b -> (b.timestamp - a.timestamp) / 1_000_000f }
        }.sorted()
        if (gaps.isNotEmpty()) {
            fun pct(p: Double) = gaps[(gaps.size * p).toInt().coerceAtMost(gaps.size - 1)]
            Log.i(TAG, "frame gaps ms: min=%.1f p50=%.1f p90=%.1f p99=%.1f max=%.1f over %d gaps"
                .format(gaps.first(), pct(0.5), pct(0.9), pct(0.99), gaps.last(), gaps.size))
        }

        val jumps = AresCaptureExplain.jumps(data, MIN_JUMP_PX)
        Log.i(TAG, "=== ${jumps.size} per-view jump(s) over ${MIN_JUMP_PX}px ===")

        // Group by the frame boundary they occur on: a reflow moves many views at one instant, and
        // one entry per view would bury that. How MANY views moved together is the thing that
        // separates "the list scrolled" from "one tile teleported".
        jumps.groupBy { it.frameIndex to it.axis }
            .entries
            .sortedByDescending { it.value.maxOf { j -> j.px } }
            .take(MAX_BOUNDARIES)
            .forEach { (key, group) ->
                val (frame, axis) = key
                val worst = group.maxByOrNull { it.px }!!
                Log.w(TAG, "")
                Log.w(TAG, "--- frame $frame, $axis: ${group.size} view(s) moved, worst %.1fpx, gap %.1fms"
                    .format(worst.px, worst.gapMs))
                val origin = worst.origin()
                if (origin == null) {
                    Log.w(TAG, "    ORIGIN: none of its ancestors moved with it")
                } else {
                    val (b, a) = origin
                    Log.w(TAG, "    ORIGIN at depth ${b.depth}: ${b.className}#${b.hashcode}")
                    Log.w(TAG, "      before: $b")
                    Log.w(TAG, "      after : $a")
                }
                Log.w(TAG, "    full chain of the worst mover:")
                worst.before.chain().zip(worst.after.chain()).forEach { (b, a) ->
                    val moved = if (axis == "left") a.left - b.left else a.top - b.top
                    Log.w(TAG, "      d=%+8.1f  %s%s".format(
                        moved, "  ".repeat(b.depth), b.className))
                    if (kotlin.math.abs(moved) >= 1f) {
                        Log.w(TAG, "                    was $b")
                        Log.w(TAG, "                    now $a")
                    }
                }
                // How LONG it was displaced. One frame out and back is a flash the detector cannot
                // distinguish from an animation that ran; this settles it.
                AresCaptureExplain.trace(data, worst.after.hashcode)
                    .filter { (i, _) -> i >= frame - 22 && i <= frame + 3 }
                    .forEach { (i, p) ->
                        Log.w(TAG, "      %s frame %d: %s".format(if (i == frame) "->" else "  ", i, p))
                    }
                // Distinct classes tell scroll (everything) from reflow (tiles) from a single view.
                Log.w(TAG, "    movers by class: " +
                    group.groupingBy { it.after.className }.eachCount().entries
                        .sortedByDescending { it.value }.joinToString { "${it.key}x${it.value}" })
            }
    }

    private companion object {
        const val TAG = "AresSwapExplain"
        const val FIXTURE_PATH = "/sdcard/ares-swap-capture.pb"

        /** Well under the detector's own threshold, so near-misses show up alongside the hits. */
        const val MIN_JUMP_PX = 200f
        const val MAX_BOUNDARIES = 6
    }
}
