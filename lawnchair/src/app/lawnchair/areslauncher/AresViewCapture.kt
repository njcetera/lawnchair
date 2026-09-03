package app.lawnchair.areslauncher

import android.content.Context
// `android.media.permission`, not `com.android.launcher3.util` -- ViewCapture.java:26 imports the
// framework one and `startCapture` returns it. The two are unrelated types with the same name.
import android.media.permission.SafeCloseable
import android.util.Log
import android.view.View
import com.android.app.viewcapture.SimpleViewCapture
import com.android.app.viewcapture.ViewCapture
import com.android.launcher3.Launcher
import java.io.File

/**
 * Records the launcher's own view tree so a test can analyse how it ANIMATED, not just where things
 * ended up.
 *
 * ## Why this exists
 *
 * Nine rows in the defect ledger are animation-class defects — jitter, a flash, a jump, a stranded
 * ghost — and not one instrument in this project can see them. Every check here reads a settled
 * state: the channel reports where a tile *is*, the smoke suite reads a `dumpsys` after things stop
 * moving, and a screenshot is one frame. A glyph that flashes white for three frames mid-transition
 * is invisible to all of them, and that is exactly the class of thing the owner reports by eye.
 *
 * Google already solved the analysis half: `AlphaJumpDetector`, `FlashDetector` and
 * `PositionJumpDetector` walk a `ViewCapture` proto and flag frames where a view's alpha or
 * position moves discontinuously. Those 890 lines were deleted upstream in `f3112aea02` and are
 * restored in `:ares-tests`. What was missing is the DATA, and this is the data.
 *
 * ## Why not `ViewCaptureFactory`
 *
 * `ViewCaptureFactory.getInstance` returns `PerfettoViewCapture` on a debuggable build, which routes
 * frames into a Perfetto trace session; with no session running there is nothing to read, and wiring
 * one up to answer "did this animation jump" is a great deal of machinery for a question an
 * in-memory ring buffer answers directly. `SimpleViewCapture` is that ring buffer — the same
 * `ViewCapture` base class, `mIsEnabled` true from construction, `getExportedData` returning the
 * proto the detectors already parse, and a 2000-frame history (`DEFAULT_MEMORY_SIZE`).
 *
 * Also NOT `QuickstepLauncher`'s own `mViewCapture`: that is a bare `null` at `:829` with an LC-Note
 * saying Lawnchair made it a no-op for Android 8-11 support. Reviving it would re-enable capture for
 * every user of every build to serve a test. This is separate, off by default, and started only when
 * the test channel asks.
 *
 * ## The stop/export ordering, which is not obvious and is easy to get wrong
 *
 * `startCapture` hands back a `SafeCloseable` whose `close()` does `mListeners.remove(listener)`,
 * and `getExportedData` builds its answer by streaming **that same `mListeners`** (filtered on
 * `mIsActive`). So closing the handle and then exporting yields an empty proto every time, with no
 * error — the frames were recorded and are simply unreachable.
 *
 * `stopCapture(root)` is the path that exists for this: it removes the draw listener and nulls
 * `mRoot`, leaving the listener in the list. Its own comment says the listeners are then "unusable
 * for anything except dumping previously captured information. They are still technically enabled to
 * allow for dumping." So [stop] stops recording, [export] can still read, and only [reset] closes the
 * handle — which is also what unregisters the ComponentCallbacks `startCapture` registered, so it
 * must still happen.
 *
 * ## Cost, and why it is off by default
 *
 * A running capture allocates a frame pool and copies view state on every draw. It is started by an
 * explicit channel call, stopped by another, and never runs unless a test turns it on.
 */
object AresViewCapture {

    private const val TAG = "AresViewCapture"

    /** Where [export] writes the proto. The test reads it from here. */
    const val EXPORT_NAME = "ares-viewcapture.pb"

    private var capture: ViewCapture? = null
    private var closeable: SafeCloseable? = null
    private var root: View? = null
    private var recording = false

    /**
     * Captured at [start] so [export] can run off the UI thread without a `Launcher` in hand.
     *
     * The APPLICATION context specifically: `getExportedData` only needs `getPackageName` and
     * `getResources`, and an activity held in a process-scoped object across a fold or a recreate is
     * the exact leak shape ledger row S5 already cost this project once.
     */
    private var appContext: Context? = null

    val isRecording: Boolean get() = recording

    /**
     * Begin recording [launcher]'s root view. Must run on the UI thread — `startCapture` attaches a
     * draw listener to the view tree.
     *
     * Idempotent: a second start while one is already running is a no-op rather than a leak of the
     * first `SafeCloseable`, which would keep copying frames forever with nobody able to stop it.
     */
    fun start(launcher: Launcher): String {
        if (recording) return "already-running"
        // A previous session that was stopped but never reset still holds a listener and its frames;
        // drop it so this run's export cannot be contaminated by the last one's data.
        if (capture != null) reset()
        // `Activity.getWindow()` is genuinely nullable before attach and after destroy. Answer with
        // a marker rather than `!!`: the channel's callers treat anything that is not `started` as
        // "capture did not run", which is the honest outcome, where a crash here would take the
        // launcher down over a test.
        val view = launcher.window?.decorView?.rootView ?: return "no-window"
        return try {
            val vc = SimpleViewCapture("ares-vc")
            closeable = vc.startCapture(view, ".AresLauncher")
            capture = vc
            root = view
            appContext = launcher.applicationContext
            recording = true
            "started"
        } catch (t: Throwable) {
            Log.w(TAG, "startCapture failed", t)
            capture = null
            closeable = null
            root = null
            appContext = null
            recording = false
            "error:" + t.javaClass.simpleName
        }
    }

    /**
     * Stop recording, keeping the captured frames readable by [export].
     *
     * Deliberately `stopCapture` and not `closeable.close()` — see the class comment. Safe to call
     * when not recording.
     */
    fun stop(): String {
        val vc = capture ?: return "not-running"
        val view = root ?: return "not-running"
        if (!recording) return "already-stopped"
        return try {
            vc.stopCapture(view)
            "stopped"
        } catch (t: Throwable) {
            Log.w(TAG, "stopCapture failed", t)
            "error:" + t.javaClass.simpleName
        } finally {
            recording = false
        }
    }

    /**
     * Write the captured frames to [EXPORT_NAME] in the app's external files dir and return the
     * absolute path plus a frame count, or a `not-running`/`empty`/`error:` marker.
     *
     * A FILE rather than the Bundle every other channel here answers with, deliberately: an
     * `ExportedData` for even a short animation is far past what a Binder transaction will carry,
     * and the failure mode for an oversize parcel is a `TransactionTooLargeException` at an
     * unrelated call site later. The external files dir is readable by the out-of-process test
     * without any `run-as` gymnastics.
     *
     * MUST NOT run on the UI thread. `getExportedData` blocks on `.get()` of a future whose first
     * stage is `CompletableFuture.supplyAsync(..., MAIN_EXECUTOR)` — so calling it from the main
     * thread deadlocks it against the very thread that has to produce the answer.
     */
    fun export(): String {
        val vc = capture ?: return "not-running"
        val context = appContext ?: return "not-running"
        return try {
            val data = vc.getExportedData(context)
            val frames = data.windowDataList.sumOf { it.frameDataCount }
            if (frames == 0) return "empty"
            val dir = context.getExternalFilesDir(null) ?: context.filesDir
            val out = File(dir, EXPORT_NAME)
            out.outputStream().use { data.writeTo(it) }
            Log.i(TAG, "exported " + frames + " frame(s) to " + out.absolutePath)
            out.absolutePath + "|frames=" + frames
        } catch (t: Throwable) {
            Log.w(TAG, "export failed", t)
            "error:" + t.javaClass.simpleName
        }
    }

    /**
     * Tear the capture down completely: stop recording, close the handle (which unregisters the
     * ComponentCallbacks and drops the listener), and forget the frames. A later [start] then begins
     * from an empty buffer.
     */
    fun reset(): String {
        stop()
        try {
            closeable?.close()
        } catch (t: Throwable) {
            Log.w(TAG, "close failed", t)
        }
        closeable = null
        capture = null
        root = null
        appContext = null
        recording = false
        return "reset"
    }
}
