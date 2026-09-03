package app.lawnchair.areslauncher

import android.content.Context
// `android.media.permission`, not `com.android.launcher3.util` -- ViewCapture.java:26 imports the
// framework one and `startCapture` returns it. Two unrelated types with the same name.
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
 * frames into a Perfetto trace session; with no session running there is nothing to read.
 * `SimpleViewCapture` is the plain in-memory ring buffer — same `ViewCapture` base class,
 * `mIsEnabled` true from construction, `getExportedData` returning the proto the detectors parse,
 * 2000-frame history (`DEFAULT_MEMORY_SIZE`).
 *
 * Also NOT `QuickstepLauncher`'s own `mViewCapture`: that is a bare `null` at `:829` with an LC-Note
 * saying Lawnchair made it a no-op for Android 8-11 support. Reviving it would enable capture for
 * every user of every build to serve a test.
 *
 * ## Three lifecycle facts, each of which cost a wrong first draft
 *
 * **One instance, forever.** `SimpleViewCapture`'s constructor calls
 * `createAndStartNewLooperExecutor`, which does `new HandlerThread(...).start()`
 * (`ViewCapture.java:111`) — and there is no `quit`, `shutdown` or `interrupt` anywhere in
 * `viewcapturelib`. A per-[start] instance therefore leaks one live looper thread into the
 * launcher process on every test, permanently. [capture] is created once and reused; the frame
 * buffer is per-`WindowListener`, created fresh by each `startCapture`, so reuse costs nothing.
 *
 * **[release] must close the handle while `mRoot` is still set.** The closeable `startCapture`
 * returns unregisters the `ComponentCallbacks` only inside
 * `if (listener.mRoot != null && listener.mRoot.getContext() != null)` (`ViewCapture.java:147`),
 * and `stopCapture` nulls exactly that field (`:174`). So a stop-then-close silently SKIPS the
 * unregister and leaves a listener owning a `ViewPropertyRef[2000]` registered on the application
 * for the life of the process. There is consequently **no `stop`**: the flow is
 * `start` → gesture → [export] → [reset], and [export] reads a running capture.
 *
 * **A recreate strands the capture.** `getLauncherUIProperty` resolves
 * `ACTIVITY_TRACKER::getCreatedContext` — *created*, not resumed — and this launcher recreates
 * routinely (theme, icon shape, fold). A capture started against the old decor view keeps a draw
 * listener on a dead tree and returns its pre-recreate frames, which are non-empty and therefore
 * look exactly like a successful capture of the animation under test. [start] guards both ends:
 * it refuses a launcher that is not resumed, and it treats a different root view as a new session
 * rather than answering `already-running`.
 *
 * ## Cost, and why it is off by default
 *
 * A running capture allocates a frame pool and copies view state on every draw. It starts only when
 * the test channel asks. Nothing auto-stops it: the one backstop is the library's own
 * `onTrimMemory(>= TRIM_MEMORY_BACKGROUND)` (`ViewCapture.java:598`), which frees the buffers and
 * unregisters — real, but it needs memory pressure with the process backgrounded, so a test that
 * dies between [start] and [reset] leaves capture running until something evicts the process. Call
 * [reset] in a finally.
 */
object AresViewCapture {

    private const val TAG = "AresViewCapture"

    /** Where [export] writes the proto. The test reads it from here. */
    const val EXPORT_NAME = "ares-viewcapture.pb"

    /**
     * Created once and never replaced. See the class comment: a per-start instance leaks a
     * `HandlerThread` that nothing in `viewcapturelib` can ever quit.
     */
    private val capture: ViewCapture by lazy { SimpleViewCapture("ares-vc") }

    /**
     * Written on the UI thread by [start] (it arrives via `getLauncherUIProperty`) and read on
     * binder threads by [export] and [reset]. `@Volatile` rather than a monitor on [export]: that
     * method blocks on a future staged through `MAIN_EXECUTOR`, so holding a lock across it while
     * [start] waits for the same lock ON the main thread is a two-way deadlock. Only the two
     * mutating paths take [lock], and neither of them blocks on main.
     */
    @Volatile
    private var closeable: SafeCloseable? = null

    @Volatile
    private var root: View? = null

    /**
     * Captured at [start] so [export] can run off the UI thread without a `Launcher` in hand.
     *
     * The APPLICATION context specifically. [root] does hold a decor view, and therefore the
     * activity, for as long as a capture runs — that is inherent to capturing a view tree, and it
     * is why [start] refuses to keep a stale one across a recreate.
     */
    @Volatile
    private var appContext: Context? = null

    private val lock = Any()

    val isRecording: Boolean get() = closeable != null

    /**
     * Begin recording [launcher]'s root view. Runs on the UI thread — `startCapture` attaches a
     * draw listener to the view tree.
     *
     * Answers `not-resumed` for a launcher that exists but is not on screen, `no-window` before
     * attach or after destroy, `already-running` only when the running capture is bound to *this*
     * launcher's current root, and `started` otherwise — including the recreate case, where the old
     * session is released first.
     */
    fun start(launcher: Launcher): String = synchronized(lock) {
        // Being the CREATED launcher is not being the launcher on screen. `getLauncherUIProperty`
        // resolves getCreatedContext(), a WeakReference set at handleCreate and cleared only at
        // onContextDestroyed -- the same "exists but is not the home screen" hole that produced the
        // pixel7Api36 false green in ledger row 62.
        if (!launcher.hasBeenResumed()) return "not-resumed"
        val view = launcher.window?.decorView?.rootView ?: return "no-window"

        val current = closeable
        if (current != null) {
            if (root === view) return "already-running"
            // Different root: the activity was recreated under us. The old session's draw listener
            // is on a dead tree and its frames are from before the recreate.
            Log.i(TAG, "root changed under a running capture; restarting")
            release()
        }

        // A previous run's file outlives a failed export, and every non-writing return path
        // (`not-running`, `empty`, `error:`) leaves it there with a plausible mtime. That is the
        // `uiautomator dump` trap -- stale file read as fresh -- with a new filename.
        deleteExport(launcher.applicationContext)

        return try {
            closeable = capture.startCapture(view, ".AresLauncher")
            root = view
            appContext = launcher.applicationContext
            "started"
        } catch (t: Throwable) {
            Log.w(TAG, "startCapture failed", t)
            closeable = null
            root = null
            appContext = null
            "error:" + t.javaClass.simpleName
        }
    }

    /**
     * Write the captured frames to [EXPORT_NAME] and return `<abs path>|frames=<n>|dir=<ext|int>`,
     * or a `not-running` / `empty` / `error:` marker.
     *
     * Reads a RUNNING capture — there is no stop, see the class comment. Call this before [reset].
     *
     * A FILE rather than the Bundle every other channel answers with: a two-swipe capture measured
     * 1,053,751 bytes, far past a Binder transaction, and the failure mode for an oversize parcel is
     * a `TransactionTooLargeException` at an unrelated call site later.
     *
     * MUST NOT run on the UI thread. `getExportedData` blocks on `.get()` of a future whose first
     * stage is `CompletableFuture.supplyAsync(..., MAIN_EXECUTOR)` (`ViewCapture.java:231`) — on
     * main it waits for the thread that owes it the answer.
     *
     * The `dir=` field is not decoration: `getExternalFilesDir` can return null, and the internal
     * fallback is NOT reachable by the out-of-process test, so a caller has to be able to tell which
     * branch ran without parsing the path.
     */
    fun export(): String {
        val context = appContext ?: return "not-running"
        if (closeable == null) return "not-running"
        return try {
            val data = capture.getExportedData(context)
            val frames = data.windowDataList.sumOf { it.frameDataCount }
            if (frames == 0) return "empty"
            val external = context.getExternalFilesDir(null)
            val dir = external ?: context.filesDir
            val out = File(dir, EXPORT_NAME)
            out.outputStream().use { data.writeTo(it) }
            val where = if (external != null) "ext" else "int"
            Log.i(TAG, "exported $frames frame(s) to ${out.absolutePath} ($where)")
            "${out.absolutePath}|frames=$frames|dir=$where"
        } catch (t: Throwable) {
            // Includes ConcurrentModificationException if a [reset] lands mid-export:
            // getWindowData streams mListeners (ViewCapture.java:232) and
            // Collections.synchronizedList does not synchronise stream(). Reported, not swallowed.
            Log.w(TAG, "export failed", t)
            "error:" + t.javaClass.simpleName
        }
    }

    /** Stop recording and release everything. A later [start] begins from an empty buffer. */
    fun reset(): String = synchronized(lock) {
        release()
        "reset"
    }

    /**
     * Closes the handle WITHOUT a prior `stopCapture`, which is the only ordering that reaches the
     * `unregisterComponentCallbacks` inside it. See the class comment.
     */
    private fun release() {
        appContext?.let { deleteExport(it) }
        try {
            closeable?.close()
        } catch (t: Throwable) {
            Log.w(TAG, "close failed", t)
        }
        closeable = null
        root = null
        appContext = null
    }

    private fun deleteExport(context: Context) {
        try {
            val dir = context.getExternalFilesDir(null) ?: context.filesDir
            File(dir, EXPORT_NAME).delete()
        } catch (t: Throwable) {
            Log.w(TAG, "could not delete stale export", t)
        }
    }
}
