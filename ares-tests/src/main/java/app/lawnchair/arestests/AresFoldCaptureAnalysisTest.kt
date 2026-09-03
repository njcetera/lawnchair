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
 * Analyses a ViewCapture proto that was recorded ELSEWHERE and pushed to the device.
 *
 * ## Why this exists: the fold transition cannot be recorded by a test
 *
 * Measured 2026-09-03, both devices. `cmd device_state state 0` does not simulate a fold well enough
 * to record one: it puts the device to SLEEP, both displays go `state OFF`, the launcher pauses,
 * `onDraw` stops, and the capture accumulates **4 frames** on the Pixel and 12 on the AVD — a
 * vacuous green over a lockscreen. So no automated test can produce this data.
 *
 * A PHYSICAL fold can. The owner folded and unfolded their Pixel once with a capture running and it
 * recorded **624 frames** (2026-09-03). The screen still locks on fold — the owner confirmed that is
 * normal for the hardware — but the launcher keeps drawing through the transition, which is the
 * difference that matters and the reason ledger row 74's original instinct ("needs the owner's
 * device") was right even though its stated reason was not.
 *
 * That makes fold coverage a HUMAN-IN-THE-LOOP recording plus an automated ANALYSIS, and this class
 * is the second half. Parsing is pure proto work, so it runs anywhere — the capture is recorded on
 * the foldable and analysed wherever the suite happens to run.
 *
 * ## Using it
 *
 * ```
 *   # with a capture running on the foldable, fold and unfold by hand, then:
 *   adb -s <foldable> pull .../files/ares-viewcapture.pb fold.pb
 *   adb -s <runner>   push fold.pb /sdcard/ares-fold-capture.pb
 *   bash design/scripts/run-ares-tests.sh <runner> AresFoldCaptureAnalysisTest
 * ```
 *
 * SKIPs when no fixture is present, which is the normal state — this is an analysis tool that
 * happens to be shaped like a test, not a check that should fail a routine suite run.
 */
@LargeTest
@RunWith(AndroidJUnit4::class)
class AresFoldCaptureAnalysisTest {

    private val TAG = "AresFoldCapture"

    @Test
    fun reportWhatTheFoldTransitionDid() {
        // Read through the DRIVER, not File(). `/sdcard` is not directly readable by this process
        // under scoped storage -- measured, the first attempt died with
        // `FileNotFoundException: EACCES (Permission denied)` and the size limit that looked like the
        // obvious suspect (61MB against protobuf's 64MB cap) was never the problem. The driver's
        // reader falls back to `UiAutomation.executeShellCommand`, which runs as shell and returns a
        // binary-safe descriptor.
        val driver = AresLauncherDriver()
        val bytes = driver.readCapturedProto(FIXTURE_PATH)
        assumeTrue(
            "no readable fold capture at $FIXTURE_PATH -- see this class's KDoc for how to record one",
            bytes != null && bytes.isNotEmpty(),
        )
        Log.i(TAG, "analysing ${bytes!!.size} bytes from $FIXTURE_PATH")

        // Streamed, with the size limit raised. A real fold recording is BIG -- the owner's single
        // fold+unfold came to 61MB -- and protobuf-java's CodedInputStream defaults to a 64MB
        // message cap that a longer recording would breach. Parsing from a byte[] also needs the
        // whole file resident twice. The failure is LOGGED rather than swallowed: the first attempt
        // at this reported only "did not parse", which says nothing about whether the fixture, the
        // size limit or the schema was at fault.
        val parsed = runCatching {
            val coded = com.google.protobuf.CodedInputStream.newInstance(bytes)
            coded.setSizeLimit(Int.MAX_VALUE)
            ExportedData.parseFrom(coded)
        }
        parsed.exceptionOrNull()?.let { Log.e(TAG, "fixture did not parse", it) }
        val data = parsed.getOrNull()
        assumeTrue("fixture did not parse: ${parsed.exceptionOrNull()}", data != null)
        data!!

        val frames = data.windowDataList.sumOf { it.frameDataList.size }
        Log.i(TAG, "windows=${data.windowDataList.size} frames=$frames")

        // Reported, not asserted. The point of a first fold recording is to find out what a fold
        // LOOKS like to these instruments; asserting a threshold before anyone has seen the data is
        // how the swap-geometry check ended up measuring the wrong thing three times in a row.
        val anomalies = ViewCaptureAnalyzer.getAnomalies(data)
        Log.i(TAG, "detectors reported ${anomalies.size} anomaly/anomalies")
        anomalies.entries.take(20).forEach { (path, message) ->
            Log.w(TAG, "ANOMALY ${path.substringAfterLast('|')} -> $message")
        }

        listOf(AresCaptureAnalysis.Axis.X, AresCaptureAnalysis.Axis.Y).forEach { axis ->
            val worst = AresCaptureAnalysis.maxPerFrameTranslationYJump(data, axis = axis)
            val coordinated = AresCaptureAnalysis.worstCoordinatedJump(data, CELL_PX, axis)
            Log.i(TAG, "$axis: worst single = $worst")
            Log.i(TAG, "$axis: worst coordinated = $coordinated")
        }
    }

    private companion object {
        const val FIXTURE_PATH = "/sdcard/ares-fold-capture.pb"

        /** Roughly one home cell; the unit a reflow moves by. */
        const val CELL_PX = 200f
    }
}
