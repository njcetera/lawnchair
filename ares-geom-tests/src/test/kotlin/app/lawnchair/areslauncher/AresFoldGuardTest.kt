package app.lawnchair.areslauncher

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * The mid-fold stale-profile guard, checked against real device shapes.
 *
 * This branch cannot be observed on a device. It fires on a single transient frame — the window has
 * already resized to the unfolded panel while the `DeviceProfile` still describes the folded one —
 * and running the shape suite folded and then unfolded shows only the absence of a regression.
 * Three successive versions of this gate were wrong and the first two looked fine for weeks by
 * exactly that measure (ledger rows 69 and 69a). So the decision is pure arithmetic and this is
 * where it is actually checked.
 *
 * Numbers are measured or published, never invented. `AresFoldGuard` logged
 * `container=2036x1990 availableWidthPx=1080` on emulator-5554 during two fold cycles on 2026-09-03;
 * inner-panel and cover-display resolutions are the manufacturers' published figures.
 */
class AresFoldGuardTest {

    private fun stale(w: Int, h: Int, availableWidthPx: Int) =
        AresFoldGuard.profileIsStale(containerW = w, containerH = h, availableWidthPx = availableWidthPx)

    /**
     * The exact frame measured on the device, and the reason this file exists. An earlier version of
     * the guard matched the container against `DisplayController.Info.supportedBounds` and DECLINED
     * this frame — that set reported `1080x2364`, the folded display, because it follows the active
     * display and is no more posture-independent than `isMultiDisplay` was.
     */
    @Test
    fun firesOnTheStaleFrameMeasuredOnTheDevice() {
        assertThat(stale(2036, 1990, availableWidthPx = 1080)).isTrue()
    }

    /** Every inner panel that ships, against a folded profile. All are near-square. */
    @Test
    fun firesOnEveryUnfoldedInnerPanelKnown() {
        assertThat(stale(2076, 2152, availableWidthPx = 1080)).isTrue() // Pixel Fold
        assertThat(stale(1812, 2176, availableWidthPx = 904)).isTrue() // Galaxy Z Fold
        assertThat(stale(2440, 2268, availableWidthPx = 1116)).isTrue() // OnePlus Open
        assertThat(stale(2156, 2344, availableWidthPx = 1080)).isTrue() // Honor Magic V
    }

    /**
     * ROW 69a, the regression this rewrite is for. A folded phone rotated to landscape is 2.19x its
     * profile width -- past the old 1.5x ratio -- but it is a genuinely SINGLE-panel window, and
     * halving there is the very defect the guard prevents. The owner uses rotation, so this is a
     * live path rather than a theoretical one.
     */
    @Test
    fun doesNotFireOnAFoldedPhoneRotatedToLandscape() {
        assertThat(stale(2364, 1080, availableWidthPx = 1080)).isFalse() // AresFold / Pixel Fold cover
        assertThat(stale(2316, 904, availableWidthPx = 904)).isFalse() // Galaxy Z Fold cover
        assertThat(stale(2400, 1080, availableWidthPx = 1080)).isFalse() // ordinary 20:9 phone
    }

    /** Even a stubby 16:9 phone in landscape stays on the correct side of the band. */
    @Test
    fun doesNotFireOnAnOldFashionedSixteenByNinePhoneInLandscape() {
        assertThat(stale(1920, 1080, availableWidthPx = 1080)).isFalse()
    }

    /** No ratio could have separated these: the landscape width EXCEEDS the unfolded panel's. */
    @Test
    fun theLandscapeCaseIsWiderThanThePanelSoWidthAloneCannotOrderThem() {
        assertThat(2364).isGreaterThan(2076)
        assertThat(stale(2364, 1080, availableWidthPx = 1080)).isFalse()
        assertThat(stale(2076, 2152, availableWidthPx = 1080)).isTrue()
    }

    /** Once the profile has caught up there is nothing stale to correct, so it must stand down. */
    @Test
    fun doesNotFireWhenTheProfileAlreadyDescribesTheUnfoldedWindow() {
        assertThat(stale(2036, 1990, availableWidthPx = 2076)).isFalse()
    }

    /** A settled FOLDED window is not wide relative to its profile and must not be halved. */
    @Test
    fun doesNotFireOnASettledFoldedWindow() {
        assertThat(stale(1080, 2364, availableWidthPx = 1080)).isFalse()
    }

    /** Orientation must not matter: the same window expressed either way is the same window. */
    @Test
    fun theShapeTestIsRotationInvariant() {
        assertThat(stale(2036, 1990, availableWidthPx = 1080)).isTrue()
        assertThat(stale(1990, 2036, availableWidthPx = 900)).isTrue()
    }

    /** Degenerate inputs answer false rather than dividing by zero or halving on nothing. */
    @Test
    fun degenerateInputsDoNotFire() {
        assertThat(stale(0, 2152, 1080)).isFalse()
        assertThat(stale(2036, 0, 1080)).isFalse()
        assertThat(stale(2036, 1990, availableWidthPx = 0)).isFalse()
    }

    /**
     * The threshold sits in an EMPTY band, which is what makes it device-agnostic rather than tuned.
     * Insets make the container shorter than the window and so push a portrait-ish aspect up, so the
     * headroom above the worst inner panel is doing real work.
     */
    @Test
    fun theThresholdSitsInTheGapBetweenTheTwoPopulations() {
        val worstInnerPanel = 2176f / 1812f // Galaxy Z Fold, the least square panel that ships
        val leastElongatedPhoneLandscape = 16f / 9f
        assertThat(worstInnerPanel).isLessThan(AresFoldGuard.MAX_PANEL_ASPECT)
        assertThat(leastElongatedPhoneLandscape).isGreaterThan(AresFoldGuard.MAX_PANEL_ASPECT)
        // Concretely: the Z Fold panel keeps firing even after losing 20% of its height to insets.
        assertThat(stale(1812, (2176 * 0.8f).toInt(), availableWidthPx = 904)).isTrue()
    }
}
