package app.lawnchair.arestests

import java.util.concurrent.CopyOnWriteArrayList

/**
 * Samples the launcher on a background thread **while a gesture runs on the test thread**.
 *
 * Sampling from inside [AresGestures.pressHoldDragRelease]'s `onStep` looks simpler and is wrong.
 * Each sample is an IPC round-trip to the test provider, tens of milliseconds, and `onStep` runs
 * between injected MOVEs — so three samples per step turn a nominal 16ms cadence into something
 * closer to 100ms of wall clock while the *event timestamps* still say 16ms apart. The injected
 * stream drifts further behind the real clock with every step.
 *
 * That is not a theoretical concern. Measured on `AresFolderExitTest`, sampling inline:
 *
 * ```
 * run 1  sawDrag=false     run 2  sawDrag=false
 * run 3  sawDrag=true      run 4  sawDrag=false
 * ```
 *
 * The drag simply failed to arm three times in four — not a defect in the launcher, a defect in how
 * the gesture was being observed. **The act of measuring was breaking the thing measured.** With
 * sampling moved off the gesture thread the event stream stays tight and the same test is stable.
 *
 * Keep [intervalMs] comfortably above one IPC round-trip; the sampler is best-effort and will simply
 * take fewer samples than requested rather than fall behind.
 */
class AresSampler<T>(
    private val intervalMs: Long = 50L,
    private val sample: () -> T,
) {
    private val collected = CopyOnWriteArrayList<T>()
    @Volatile private var running = false
    private var thread: Thread? = null

    fun start() {
        running = true
        thread = Thread {
            while (running) {
                runCatching { collected += sample() }
                try {
                    Thread.sleep(intervalMs)
                } catch (e: InterruptedException) {
                    Thread.currentThread().interrupt()
                    return@Thread
                }
            }
        }.also { it.isDaemon = true; it.start() }
    }

    /** Stops sampling and returns everything collected, in order. */
    fun stop(): List<T> {
        running = false
        thread?.join(2_000)
        thread = null
        return collected.toList()
    }
}
