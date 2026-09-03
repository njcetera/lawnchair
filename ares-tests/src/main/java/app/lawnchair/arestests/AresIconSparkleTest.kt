package app.lawnchair.arestests

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.LargeTest
import com.google.common.truth.Truth.assertWithMessage
import org.junit.After
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The edit-mode icon sparkle overlay ([AresIconTransition]) actually mounts on screen and tears back
 * down, on the current device and posture.
 *
 * ## What this guards, and what it deliberately does NOT
 *
 * Owner report 2026-09-03: unfolded, "the sparkle animation when changing icon shape or theming is
 * not happening." The sparkle is a per-tile overlay added to the drag layer; it can silently fail to
 * appear if the drag layer is null, has zero size, or a Monet color resolve throws — a device- and
 * posture-specific failure. This test drives the overlay MECHANISM through the `ares-icon-transition`
 * channel (`fire` -> is one mounted? -> `cancel` -> is it gone?) and proves it comes up and goes away
 * on whatever device runs the suite.
 *
 * It does NOT drive the edit-mode carousel tap that fires the sparkle in real use (that path is a
 * fragile UI automation and the owner's report was a stale-state one that a reinstall cleared, so a
 * tap-driven test would neither be reliable nor reproduce the actual break). This is the robust,
 * device-agnostic half: if a future code change breaks the overlay's construction on some device,
 * this fails; a carousel-wiring regression is out of its scope, by design.
 *
 * `fire` needs the home grid present; on a device/state without it the channel answers `no-home-list`
 * and this SKIPs rather than passing.
 */
@LargeTest
@RunWith(AndroidJUnit4::class)
class AresIconSparkleTest {

    private val TAG = "AresIconSparkle"
    private lateinit var ares: AresLauncherDriver

    @Before
    fun setUp() {
        ares = AresLauncherDriver()
        ares.openTestChannel()
        ares.goHome()
        // Clean slate: a sparkle left up from an earlier test would make `fire` a no-op (freeze
        // early-outs when one is already showing) and read as a false pass.
        ares.iconSparkle("cancel")
    }

    @After
    fun tearDown() {
        // Never leave an overlay mounted over the launcher for the next test.
        runCatching { ares.iconSparkle("cancel") }
    }

    @Test
    fun sparkleOverlayMountsAndTearsDown() {
        val fired = ares.iconSparkle("fire")
        assumeTrue("icon-transition channel did not answer", fired != null)
        assumeTrue("no home grid to sparkle over: $fired", fired != "no-home-list")
        Log.i(TAG, "fire -> $fired")
        assertWithMessage("sparkle overlay should be mounted after fire, was: $fired")
            .that(fired).contains("showing=true")

        val cleared = ares.iconSparkle("cancel")
        Log.i(TAG, "cancel -> $cleared")
        assertWithMessage("sparkle overlay should be gone after cancel, was: $cleared")
            .that(cleared).contains("showing=false")
    }
}
