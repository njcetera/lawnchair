package app.lawnchair.arestests

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.LargeTest
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * §26 — the edit-mode label lift, on the **home grid**, held across frames with animators off and on.
 *
 * ## What this is a port OF, and why it moved surfaces
 *
 * This replaces `AresFolderLiftTest` (S12), which asserted the same invariant inside an **overlay**
 * folder in folder edit mode. The WP migration removed that surface — a folder now expands inline
 * and its children ARE home-grid tiles — so the overlay test could never open a folder again and
 * timed out every run (ledger row 67). The invariant did not go away; it moved. `AresEditLabel.set`
 * is called on every home tile from `AresHomeAdapter` (`:215`), so the lift the old test measured on
 * folder icons is now measured on ordinary home tiles, and an expanded folder's children are covered
 * for free because they are those tiles.
 *
 * ## The invariant, and the bug it guards
 *
 * In edit mode `AresEditLabel` fades each tile's caption and slides its icon DOWN to the cell centre
 * (an icon sits high only to leave room for the label beneath it). The defect it once had: with
 * animators disabled, `AresEditWiggle.start` returned null and its teardown funnel dropped the lift,
 * and because no animator was recorded the "already running" early-out never fired, so **every
 * pre-draw wiped the lift again** — the icon rode high in an empty cell. The fix holds the lift for
 * the duration of edit mode.
 *
 * ## The observable, measured before this test was written
 *
 * `ares-tile-metrics` reports each tile's `itemTranslation` — the real `INDEX_REORDER_BOUNCE_OFFSET`
 * the view carries. Spiked on emulator-5554, animators off:
 *
 * ```
 *   not editing   itemTranslation.y = 0.0   (baseline: nothing lifted)
 *   editing       itemTranslation.y = 37.0  (every icon tile, slid to centre)
 * ```
 *
 * The old test had a second number — `stateLift`, what `AresEditLabel` *believed* it wrote — and
 * asserted belief and reality agreed. The home tile metric exposes only the reality, which is the
 * right thing to assert here: the user-visible bug was the reality being wiped to zero, and a probe
 * that reads only what the code believes would agree with itself and miss it. So this samples the
 * reality repeatedly and requires it to STAY lifted — a single sample at the right instant reads
 * correct on the broken build, because the wipe was per-pre-draw.
 *
 * Widgets are excluded: they carry no label, so no lift ([iconTiles] filters to `itemType == 0`).
 */
@LargeTest
@RunWith(AndroidJUnit4::class)
class AresLabelLiftTest {

    private val TAG = "AresSpike"
    private lateinit var ares: AresLauncherDriver

    @Before
    fun setUp() {
        ares = AresLauncherDriver()
        ares.openTestChannel()
        ares.goHome()
        ares.exitEditMode()
    }

    @After
    fun tearDown() {
        // Restore unconditionally. Leaving the scale at 0 silently disables the float and the reflow
        // spring for every test that runs afterwards -- the trap this whole suite exists to avoid.
        ares.setAnimatorScale(1)
        runCatching { ares.exitEditMode() }
    }

    /** The configuration the original defect lived in, and the PowerShell harness has always run. */
    @Test
    fun labelLiftHoldsWithAnimatorsDisabled() = assertLiftHolds(scale = 0)

    /** The configuration the owner's device runs: the lift plus the orbit float on top of it. */
    @Test
    fun labelLiftHoldsWithAnimatorsEnabled() = assertLiftHolds(scale = 1)

    private fun assertLiftHolds(scale: Int) {
        ares.setAnimatorScale(scale)
        ares.restartLauncher()

        // Baseline: nothing is lifted before edit mode. If this is already non-zero the metric does
        // not mean what the rest of the test assumes.
        val atRest = ares.iconTiles()
        assumeTrueNonEmpty(atRest)
        Log.i(TAG, "lift/scale$scale at rest: ${atRest.map { it.title to it.itemTranslation.y }}")
        atRest.forEach {
            assertWithMessage("tile '${it.title}' before edit mode")
                .that(it.itemTranslation.y).isWithin(BASELINE_TOLERANCE_PX).of(0f)
        }

        ares.enterEditModeNoDrag()
        ares.waitFor("label lift to be applied") {
            ares.iconTiles().any { it.itemTranslation.y > MIN_LIFT_PX }
        }

        // Sampled repeatedly on purpose: the defect was a per-pre-draw wipe, so a single sample taken
        // at the right instant reads correct on a broken build. ~3s is ~180 frames.
        repeat(SAMPLES) { i ->
            val tiles = ares.iconTiles()
            Log.i(TAG, "lift/scale$scale sample $i: ${tiles.map { it.title to it.itemTranslation.y }}")
            assertThat(tiles).isNotEmpty()
            tiles.forEach { tile ->
                // The lift dominates the orbit (measured 37 vs a ±8 float), so a floor well above the
                // float and far above zero separates "lifted" from "wiped" with margin at either
                // animator scale. The broken build drops this to 0.
                assertWithMessage("tile '${tile.title}' sample $i, itemTranslation.y")
                    .that(tile.itemTranslation.y).isGreaterThan(MIN_LIFT_PX)
            }
            Thread.sleep(SAMPLE_GAP_MS)
        }
    }

    private fun assumeTrueNonEmpty(tiles: List<AresLauncherDriver.Tile>) {
        org.junit.Assume.assumeTrue("no icon tiles on the home grid", tiles.isNotEmpty())
    }

    private companion object {
        const val SAMPLES = 4
        const val SAMPLE_GAP_MS = 750L

        /** Above the orbit float (measured ±8) and far below the lift (measured 37). */
        const val MIN_LIFT_PX = 12f

        /** At rest the lift is zero; allow a hair for sub-pixel rounding. */
        const val BASELINE_TOLERANCE_PX = 2f
    }
}
