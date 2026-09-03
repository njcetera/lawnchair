package app.lawnchair.arestests

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import com.android.app.viewcapture.data.ExportedData
import com.android.app.viewcapture.data.FrameData
import com.android.app.viewcapture.data.ViewNode
import com.android.app.viewcapture.data.WindowData
import com.android.launcher3.util.viewcapture_analysis.ViewCaptureAnalyzer
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import org.junit.After
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Asserts on how the launcher ANIMATED, not on where it ended up.
 *
 * ## Why this is different from everything else in this suite
 *
 * Every other check here reads a settled state — the channel says where a tile *is*, the smoke suite
 * reads a `dumpsys` after motion stops, a screenshot is one frame. Nine rows in the defect ledger are
 * animation-class defects (jitter, a flash, a jump, a stranded ghost) and **none of those
 * instruments can see any of them**. A glyph that flashes white for three frames mid-transition is
 * invisible to all of them, and is exactly what the owner reports by eye.
 *
 * This test records the real view tree frame by frame through `AresViewCapture` and hands the proto
 * to Google's own detectors — `AlphaJumpDetector`, `FlashDetector`, `PositionJumpDetector` — restored
 * into this module from upstream commit `f3112aea02^`.
 *
 * ## Every precondition is a SKIP, and there are a lot of them
 *
 * Deliberately. Between "the launcher is on screen" and "an anomaly report exists" sit: the capture
 * starting, the gesture actually drawing frames, the proto being readable across a UID boundary, and
 * the proto parsing. Any of those failing means the check *could not run*, which under this
 * project's rules must be louder than a failure, never quieter — `run-ares-tests.sh` turns any skip
 * here into exit 3 and SUITE INCOMPLETE.
 *
 * The one thing that is asserted rather than assumed is the frame count. A capture that yields two
 * or three frames technically "ran" but describes no animation, and asserting an empty anomaly map
 * over three frames is the vacuous green this whole file exists to avoid.
 */
@RunWith(AndroidJUnit4::class)
class AresAnimationAnomalyTest {

    private lateinit var driver: AresLauncherDriver
    private lateinit var device: UiDevice

    @Before
    fun setUp() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        device = UiDevice.getInstance(instrumentation)
        device.executeShellCommand(
            "cmd package set-home-activity app.lawnchair.debug/app.lawnchair.LawnchairLauncher",
        )
        device.executeShellCommand("input keyevent KEYCODE_WAKEUP")
        device.executeShellCommand("wm dismiss-keyguard")
        driver = AresLauncherDriver()
        driver.openTestChannel()
        driver.goHome()
        device.waitForIdle()
    }

    /**
     * Always release the capture. Nothing auto-stops it: the only backstop in the library is
     * `onTrimMemory(>= TRIM_MEMORY_BACKGROUND)`, which needs memory pressure with the process
     * backgrounded, so a test that dies between `start` and `reset` leaves a per-draw tree walk
     * running in the launcher until something evicts it.
     */
    @After
    fun tearDown() {
        if (::driver.isInitialized) driver.viewCapture("reset")
    }

    private fun requireLauncher() {
        val top = device.executeShellCommand("dumpsys activity activities")
            .lineSequence().firstOrNull { it.contains("topResumedActivity") } ?: ""
        assumeTrue(
            "AresLauncher is not the resumed activity; top was: ${top.trim()}",
            top.contains("app.lawnchair") && top.contains("LawnchairLauncher"),
        )
    }

    /**
     * Records the home <-> app-list page transition and reports what the detectors make of it.
     *
     * In Strategy D the app list is workspace panel 1, not a `LauncherState`, so the transition is a
     * page swipe rather than an AllApps open — it is driven here the way a finger drives it.
     */
    @Test
    fun homeToAppListTransitionIsFreeOfAnomalies() {
        requireLauncher()

        driver.viewCapture("reset")
        assumeTrue(
            "capture did not start",
            driver.viewCapture("start") == "started",
        )

        val w = device.displayWidth
        val h = device.displayHeight
        device.swipe(w - 60, h / 2, 60, h / 2, 20)
        device.waitForIdle()
        device.swipe(60, h / 2, w - 60, h / 2, 20)
        device.waitForIdle()

        val export = driver.viewCapture("export")
        assumeTrue("export produced no frames: $export", export.contains("|frames="))

        val path = export.substringBefore("|")
        val frames = export.substringAfter("|frames=").substringBefore("|").toIntOrNull() ?: 0
        Log.i(TAG, "captured $frames frame(s) at $path")

        // ASSERTED, not assumed: a handful of frames is not an animation, and an empty anomaly map
        // over three frames would be a vacuous pass. Two 20ms-per-step swipes plus their settle
        // produce ~70 frames at 60fps; 20 is a floor well under that and well over "a few stills".
        assertThat(frames).isAtLeast(20)

        val bytes = driver.readCapturedProto(path)
        assumeTrue("could not read the proto off the device", bytes != null && bytes.isNotEmpty())

        val data = runCatching { ExportedData.parseFrom(bytes) }.getOrNull()
        assumeTrue("proto did not parse", data != null)

        val anomalies = ViewCaptureAnalyzer.getAnomalies(data!!)
        anomalies.forEach { (path, message) -> Log.w(TAG, "ANOMALY $path -> $message") }
        Log.i(TAG, "detectors reported ${anomalies.size} anomaly/anomalies over $frames frames")

        // The anomaly text goes in the ASSERTION MESSAGE, not only in logcat.
        //
        // This failed once in roughly ten runs on 2026-09-03 and the report said exactly
        // "expected to be empty" -- the runner does not surface logcat, so the one occurrence that
        // mattered left no evidence and could not be reproduced in eight further attempts. An
        // intermittent check whose failure carries no diagnosis is worse than no check: it teaches
        // people to re-run until green, which is how every mechanism in this project decayed
        // before. Flakiness that is diagnosable is survivable for a new instrument; flakiness that
        // is opaque is not.
        //
        // Still a hard assertion rather than a warning. The thresholds ARE uncalibrated for Ares
        // (they were tuned for stock Launcher3 and the ignore-lists name no Ares view), so the
        // first real failure may well be the detector rather than the launcher -- but downgrading
        // a gate because it might be noisy is exactly how a gate gets switched off for good.
        assertWithMessage(
            "ViewCapture detectors reported ${anomalies.size} anomaly/anomalies over $frames " +
                "frames of the home <-> app-list transition:\n" +
                anomalies.entries.joinToString("\n") { (path, message) -> "  $path\n    $message" },
        ).that(anomalies).isEmpty()
    }

    /**
     * NEGATIVE CONTROL for [homeToAppListTransitionIsFreeOfAnomalies], and the reason that test is
     * worth anything.
     *
     * "0 anomalies over 140 frames" is indistinguishable from "the detectors analysed nothing".
     * They could be silently inert for several reasons that a green run cannot tell apart: every
     * node matching an ignore-list path, `getVisibleAlpha` skipping the whole tree, a classname
     * index mismatch throwing inside the walk. An assertion is not coverage until it has been made
     * to FAIL.
     *
     * So this hands `ViewCaptureAnalyzer` a synthetic two-frame capture built to trip
     * `AlphaJumpDetector` and requires it to notice. The trigger is the cheapest real one: a view
     * that is present and opaque in frame 0 and **absent** in frame 1. `analyzeFrame`'s second loop
     * calls the detectors with `newInfo = null` for any view that was in the previous frame and is
     * gone from this one, which makes `newAlpha` 0 against an `oldAlpha` of 1 — a delta of exactly
     * `ALPHA_JUMP_THRESHOLD` (`1f`). That is a real disappearing view, the same shape as the
     * stranded-ghost and flash defects in the ledger, not a poked field.
     *
     * Needs no device. It fails if the restored detectors ever stop working, including if a future
     * ignore-list edit swallows everything.
     */
    @Test
    fun theDetectorsCanActuallyFail() {
        val classnames = listOf("com.android.launcher3.Ares.FakeRoot", "com.android.launcher3.Ares.FakeChild")

        fun node(index: Int, hash: Int, id: String, children: List<ViewNode> = emptyList()) =
            ViewNode.newBuilder()
                .setClassnameIndex(index)
                .setHashcode(hash)
                .setId(id)
                .setLeft(0).setTop(0).setWidth(1000).setHeight(1000)
                .setScaleX(1f).setScaleY(1f)
                .setAlpha(1f)
                .setVisibility(0) // View.VISIBLE
                .setWillNotDraw(false)
                .addAllChildren(children)
                .build()

        fun frame(ts: Long, children: List<ViewNode>) = FrameData.newBuilder()
            .setTimestamp(ts)
            .setNode(node(0, 111, "root", children))
            .build()

        fun analyse(vararg frames: FrameData) = ViewCaptureAnalyzer.getAnomalies(
            ExportedData.newBuilder()
                .addAllClassname(classnames)
                .addWindowData(
                    WindowData.newBuilder().setTitle(".AresSynthetic")
                        .addAllFrameData(frames.toList()).build(),
                )
                .build(),
        )

        // Frame 0 is always discarded: analyzeWindowData treats the first frame at a new window
        // size as `isFirstFrame` and clears its state, so a two-frame capture only ever has one
        // frame under analysis. Every case below therefore uses three.
        val still = node(1, 222, "subject")
        val moved = still.toBuilder().setLeft(400).build()

        // POSITION JUMP: a border moving more than JUMP_MIW/1000 of the window (250/1000 * 1000px
        // = 250px) between consecutive frames. 400px clears it.
        val jump = analyse(
            frame(1_000_000_000L, listOf(still)),
            frame(1_016_000_000L, listOf(still)),
            frame(1_032_000_000L, listOf(moved)),
        )

        // FLASH: opaque for one 16ms frame, then gone. FLASH_DURATION_MS is 300.
        val flash = analyse(
            frame(2_000_000_000L, listOf(still)),
            frame(2_016_000_000L, listOf(still)),
            frame(2_032_000_000L, emptyList()),
        )

        jump.forEach { (p, m) -> Log.i(TAG, "control POSITION $p -> $m") }
        flash.forEach { (p, m) -> Log.i(TAG, "control FLASH $p -> $m") }
        Log.i(TAG, "control: position=${jump.size} flash=${flash.size}")

        // PositionJumpDetector is the one asserted on. It is the detector with a correct
        // previous-frame guard (`frameN != oldInfo.frameN + 1`), and a jump is the exact shape of
        // the reflow-snap and grid-churn defects in the ledger.
        assertThat(jump).isNotEmpty()
        assertThat(jump.values.joinToString()).contains("Position jump")

        // NOT asserted, and this is a finding rather than an omission: AlphaJumpDetector cannot
        // fire at all. Its first line is `if (oldInfo != null && oldInfo.frameN != frameN) return
        // null`, but `oldInfo` is the node as seen in the PREVIOUS frame on both call paths -- in
        // analyzeView it comes from `lastSeenNodes` and in the disappearance loop it is explicitly
        // selected by `info.frameN == frameN - 1`. So the guard is never satisfied and the detector
        // is dead code as restored. Upstream DELETED these files rather than fixing them
        // (`f3112aea02`), which is consistent. Left in place, unasserted, rather than silently
        // "fixed" -- changing Google's detector logic on a hunch is how a harness starts inventing
        // defects. Flash coverage is reported above so a future session can see whether it fires.
    }

    private companion object {
        const val TAG = "AresAnimAnomaly"
    }
}
