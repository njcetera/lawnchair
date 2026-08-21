package app.lawnchair.arestests

import android.graphics.PointF
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.LargeTest
import com.google.common.truth.Truth.assertThat
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Ledger row 29 — the overscroll pull that was never released when the gesture went home.
 *
 * `mAllAppsOvershootStarted` is armed in `onDrag` by where the gesture **came from**; the release
 * (`getAppsView().onRelease()`) was nested under `if (targetState == ALL_APPS)` — where it
 * **ended**. Overscroll past the app list, reverse, settle on home: the block is skipped, and the
 * `EdgeEffect` stays in `STATE_PULL`. It leaves that state only via `onRelease()`/`onAbsorb()`,
 * and that was the only `onRelease` call in the tree. Second time the same call was lost the same
 * way — `3a633cb6fc` fixed the first (gated on the spring preference), and the wrapper it left in
 * place is where this one lived. The fix keys the release on the flag that armed it.
 *
 * ## The observable
 *
 * `EdgeEffect.isFinished` on the app list's glows, over the channel (`ares-overscroll-state`).
 * `STATE_PULL` is `isFinished == false`. The gesture: one continuous drag that opens the app-list
 * pane, keeps pulling past full open (that is the overscroll), reverses all the way back, and
 * releases on home.
 *
 * ## Non-vacuity
 *
 * A pull must actually have been observed mid-gesture (`isFinished == false` in some sample) —
 * otherwise the pane never overscrolled and a final "finished" proves nothing. When the pull never
 * starts, this fails as *could not reproduce*, which is a different statement from *the release is
 * broken*. Same discipline as the C4 test, same reason.
 *
 * ## Status: the pull CANNOT be armed here, and this test is out of the suite
 *
 * Measured 2026-08-21: 0 pulled samples in 101, across extreme over-pulls both starting FROM a
 * settled ALL_APPS and crossing full open in one gesture (which would need the mid-flight
 * from-state reinit). The overshoot is a stock path built for the VERTICAL all-apps swipe; this
 * fork opens the app list with the §10 horizontal pane, and nothing reachable by injection drives
 * `progress <= 0` with `mFromState == ALL_APPS`. The row-29 fix stands as hardening -- strictly
 * more release, never less -- but a check that cannot observe the armed state cannot falsify
 * anything, so it earns no place in the runner. If the owner ever SEES a stuck stretch on the app
 * list, that is the moment this becomes reachable and worth revisiting.
 */
@LargeTest
@RunWith(AndroidJUnit4::class)
class AresOverscrollTest {

    private val TAG = "AresSpike"
    private lateinit var ares: AresLauncherDriver

    @Before
    fun setUp() {
        ares = AresLauncherDriver()
        ares.openTestChannel()
        ares.setAnimatorScale(1)
        ares.goHome()
        ares.exitEditMode()
    }

    @After
    fun tearDown() {
        runCatching { ares.goHome() }
    }

    @Test
    fun reversingOutOfAnOverscrollReleasesThePull() {
        var pulled = false
        var attempt = 0
        var samples: List<Boolean> = emptyList()

        // The precondition is retried, the assertion is not. A pane pan is a plain drag, far more
        // reliable than a long-press, but the overscroll only arms once the controller has swapped
        // its from-state at full open -- timing that can miss. Retrying setup is setup.
        while (attempt < MAX_ATTEMPTS && !pulled) {
            attempt++
            val y = 1200f
            // The overshoot arms at `progress <= 0 && mFromState == ALL_APPS`
            // (AbstractStateChangeTouchController.java:227) -- a gesture measured FROM the app
            // list being pulled past its own zero. A single home-origin gesture only reaches that
            // through a mid-flight from-state reinit at the full-open crossing, which the first
            // version of this test missed by 80px of travel, three attempts straight. So: open the
            // pane and SETTLE first, then over-pull and reverse as one gesture from ALL_APPS --
            // which is also exactly the reviewer's reported scenario.
            ares.goHome()
            Thread.sleep(400)

            val sampler = AresSampler(intervalMs = 40L) { ares.overscrollFinished() }
            sampler.start()
            AresGestures.dragPath(
                listOf(
                    PointF(1000f, y),      // from the closed side, so the crossing reinit runs too
                    PointF(400f, y),
                    PointF(5f, y),         // cross full open and keep pulling to the bezel
                    PointF(5f, y),         // hold the pull
                    PointF(5f, y),         // and hold
                    PointF(1040f, y),      // reverse all the way home
                ),
                legMs = 500,
            )
            samples = sampler.stop()
            pulled = samples.any { !it }
            if (!pulled) Log.i(TAG, "overscroll attempt $attempt never pulled; retrying")
        }

        Log.i(
            TAG,
            "overscroll armed on attempt $attempt; pulled samples=" +
                "${samples.count { !it }}/${samples.size}",
        )
        check(pulled) {
            "no attempt in $MAX_ATTEMPTS produced an overscroll pull, so the scenario could not " +
                "be reproduced. That is NOT the same as the release being broken."
        }

        // The assertion: after settling home, the pull is released. Give the settle a beat; the
        // defect is not a slow release but NO release, so a bounded wait cannot mask it.
        ares.waitFor("overscroll pull to be released") { ares.overscrollFinished() }
        assertThat(ares.overscrollFinished()).isTrue()
        assertThat(ares.surfaceState().state).isEqualTo("NORMAL")
    }

    private companion object {
        const val MAX_ATTEMPTS = 3
    }
}
