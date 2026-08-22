package app.lawnchair.arestests

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.LargeTest
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** SPIKE (§25): does a one-item folder create + open on our hosting? Gesture-free. */
@LargeTest
@RunWith(AndroidJUnit4::class)
class AresLiveCreateSpike {
    private lateinit var ares: AresLauncherDriver

    @Before fun setUp() { ares = AresLauncherDriver(); ares.openTestChannel(); ares.goHome(); ares.exitEditMode() }

    @Test fun spike() {
        val r = ares.liveCreateSpike()
        Log.i("AresSpike", "LIVE-CREATE-SPIKE: $r")
    }
}
