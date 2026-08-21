package app.lawnchair.arestests

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.LargeTest
import com.google.common.truth.Truth.assertThat
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.abs

/**
 * S12 — §26's label lift, inside an open folder, **with animators disabled**.
 *
 * ## Why this test turns animations OFF, when everything else here insists they stay on
 *
 * Because that is the only configuration where the defect exists, and it is the configuration the
 * PowerShell harness has always run in. `AresEditWiggle.start` returns null when
 * `ValueAnimator.areAnimatorsEnabled()` is false, and it used to call `reset(view)` on the way out —
 * the *teardown* funnel, which calls `AresEditMotion.clear` and drops the whole `Motion` entry
 * including `liftY`. The view had not stopped editing; it was editing without motion.
 *
 * It compounds: a null return means the caller never records an animator, so its "already running"
 * early-out never fires and **every pre-draw wipes the lift again**, while `AresEditLabel` declines
 * to rewrite it because its own state still says it applied one.
 *
 * ## The observable, and why it needs both numbers
 *
 * `ares-folder-metrics` reports `stateLift` (what `AresEditLabel.liftOf` *believes* it wrote) and
 * `actualTy` (the view's real `INDEX_REORDER_BOUNCE_OFFSET` channel) **side by side**. The bug is
 * exactly those two disagreeing. A probe that read only `liftOf` would report the belief, agree with
 * itself, and miss the defect completely — which is the shape of mistake that produced a fictitious
 * bug report on this project before.
 *
 * Measured on emulator-5554, three icons, `animator_duration_scale=0`:
 *
 * ```
 *   bug present   stateLift 24.5   actualTy 0.0    screenY 1433   <- icon riding high, empty band under it
 *   fixed         stateLift 24.5   actualTy 24.5   screenY 1458
 * ```
 *
 * And at scale 1, which is what the owner's device runs, the fixed build reads
 * `24.5 / 28.1`, `24.5 / 21.7`, `24.5 / 25.0` — the lift **plus the orbit**, scattered by roughly
 * the float's amplitude and *differing per icon*, which is the golden-angle phase offset doing its
 * job. That is the tolerance [ORBIT_TOLERANCE_PX] allows for.
 */
@LargeTest
@RunWith(AndroidJUnit4::class)
class AresFolderLiftTest {

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
        // Restore unconditionally. Leaving the scale at 0 would silently disable the float and the
        // reflow spring for every test that ran afterwards, which is the trap this whole suite
        // exists to get away from.
        ares.setAnimatorScale(1)
        runCatching { ares.setFolderEdit(false) }
        runCatching { ares.pressBack() }
    }

    @Test
    fun folderIconsKeepTheirLiftWithAnimatorsDisabled() = assertLiftHolds(scale = 0)

    /** The same invariant where the owner actually lives, with the float running on top of it. */
    @Test
    fun folderIconsKeepTheirLiftWithAnimatorsEnabled() = assertLiftHolds(scale = 1)

    private fun assertLiftHolds(scale: Int) {
        ares.setAnimatorScale(scale)
        ares.restartLauncher()

        assertThat(ares.openFolder()).isTrue()
        ares.waitFor("folder to open") { ares.folderIcons().isNotEmpty() }

        val atRest = ares.folderIcons()
        Log.i(TAG, "lift/scale$scale at rest: $atRest")
        // Nothing is lifted before edit mode; that is the baseline the lift is measured against.
        atRest.forEach { assertThat(it.stateLift).isWithin(0.01f).of(0f) }

        assertThat(ares.setFolderEdit(true)).isTrue()
        ares.waitFor("lift to be applied") { ares.folderIcons().any { it.stateLift > 0f } }

        // Sampled repeatedly on purpose: the defect was a per-pre-draw wipe, so a single sample
        // taken at the right instant reads correct on a broken build. Three seconds is ~180 frames.
        repeat(SAMPLES) { i ->
            val icons = ares.folderIcons()
            Log.i(TAG, "lift/scale$scale sample $i: $icons")
            assertThat(icons).isNotEmpty()
            icons.forEach { icon ->
                assertThat(icon.stateLift).isGreaterThan(0f)
                // The assertion that matters: what the state believes and what the view carries.
                val drift = abs(icon.actualTy - icon.stateLift)
                assertThat(drift).isLessThan(if (scale == 0) 0.01f else ORBIT_TOLERANCE_PX)
            }
            Thread.sleep(SAMPLE_GAP_MS)
        }
    }

    private companion object {
        const val SAMPLES = 4
        const val SAMPLE_GAP_MS = 750L

        /**
         * How far the float may legitimately carry an icon away from its lift.
         *
         * `AresEditWiggle.ORBIT_DP` is 1.5dp, so the orbit spans ±1.5dp vertically — about ±4px at
         * this density. 8px leaves room for that plus the reflow's own settle without admitting a
         * wipe, which is a full 24.5px away.
         */
        const val ORBIT_TOLERANCE_PX = 8f
    }
}
