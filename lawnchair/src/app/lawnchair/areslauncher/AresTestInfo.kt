package app.lawnchair.areslauncher

import android.content.Context
import android.content.ComponentName
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Bundle
import android.util.Log
import android.view.View
import android.view.ViewGroup
import com.android.launcher3.Launcher
import com.android.launcher3.LauncherSettings.Favorites
import com.android.launcher3.model.data.FolderInfo
import com.android.launcher3.Reorderable
import com.android.launcher3.testing.TestInformationHandler
import com.android.launcher3.AbstractFloatingView
import com.android.launcher3.BubbleTextView
import com.android.launcher3.R
import com.android.launcher3.folder.Folder
import com.android.launcher3.folder.FolderIcon

import com.android.launcher3.util.MultiTranslateDelegate.INDEX_REORDER_BOUNCE_OFFSET

/**
 * AresLauncher's own end of the test channel.
 *
 * ## Why this exists rather than `ro.test_harness`
 *
 * Stock opens the `TestInformationProvider` channel on `Utilities.isRunningInTestHarness()`, which
 * reads `ActivityManager.isRunningInTestHarness()`. Two problems, both measured rather than
 * reasoned:
 *
 * 1. That flag is **not** a test-channel switch, it is a global behaviour switch with ~41 product
 *    call sites in this tree, and at least one of them is on a surface this project has open
 *    defects on: `SpringLoadedDragController` picks `ENTER_SPRING_LOAD_HOVER_TIME_IN_TEST` (3000ms)
 *    instead of `ENTER_SPRING_LOAD_HOVER_TIME` (500ms) under it. Turning the channel on that way
 *    changes the thing under test.
 * 2. `Utilities.sIsRunningInTestHarness` is a `static` field initialised at class load, so the
 *    property has to be true before the launcher process starts. A test cannot set it.
 *
 * So the channel is opened by a **debug-build** predicate we own instead. Release builds are
 * unaffected, and no product behaviour moves.
 *
 * Note for anyone measuring on `emulator-5554`: that image already ships `ro.test_harness=1`
 * (`ro.kernel.qemu=1`), so `isRunningInTestHarness()` is *already* true there and the 3000ms dwell
 * is *already* in force. That is independent of this file and true of every measurement taken on
 * that emulator to date.
 */
object AresTestInfo {

    /** True when this build may answer test-channel requests. Debug builds only. */
    @JvmStatic
    fun isTestChannelOpen(context: Context): Boolean =
        (context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0

    /**
     * Enables `TestInformationProvider` on a debug build, from inside the launcher's own process.
     *
     * `AndroidManifest-common.xml` declares the provider `android:enabled="false"`, and stock
     * expects the test to switch it on. **An out-of-process test cannot**, measured:
     *
     * ```
     * $ adb shell pm enable app.lawnchair.debug/com.android.launcher3.testing.TestInformationProvider
     * SecurityException: Shell cannot change component state for ComponentInfo{...} to 1
     * ```
     *
     * `PackageManagerService` lets the shell uid change component state only for apps carrying
     * `FLAG_TEST_ONLY`, and a Gradle `assembleDebug` APK does not (`android:debuggable=true` is
     * there, `android:testOnly` is not). TAPL only gets away with it because AOSP's in-process
     * branch calls `setComponentEnabledSetting` as the launcher itself.
     *
     * So the launcher does it as the launcher -- same app, always permitted -- and only when
     * [isTestChannelOpen]. `DONT_KILL_APP` so a debug launch does not restart itself, and it is a
     * no-op once the state is already set. The alternatives were worse: shipping the provider
     * `enabled="true"` in release, or making every debug APK `testOnly` (which then refuses to
     * install without `-t`, on the owner's device as well as the emulator).
     */
    @JvmStatic
    fun enableTestProviderIfDebug(context: Context) {
        if (!isTestChannelOpen(context)) return
        val component = ComponentName(
            context.packageName,
            "com.android.launcher3.testing.TestInformationProvider",
        )
        try {
            val pm = context.packageManager
            if (pm.getComponentEnabledSetting(component) !=
                PackageManager.COMPONENT_ENABLED_STATE_ENABLED
            ) {
                pm.setComponentEnabledSetting(
                    component,
                    PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
                    PackageManager.DONT_KILL_APP,
                )
                Log.i(TAG, "test channel: enabled $component")
            }
        } catch (e: Exception) {
            Log.w(TAG, "test channel: could not enable $component", e)
        }
    }

    private const val TAG = "AresTestInfo"

    // ---------------------------------------------------------------- requests

    /** Titles of the home grid's items, in visual (adapter) order. */
    const val REQUEST_HOME_ORDER = "ares-home-order"

    /** `true` while the masonry home grid is in edit mode. */
    const val REQUEST_EDIT_MODE = "ares-edit-mode"

    /**
     * One line per attached home tile describing **where it is drawn**, not where it is laid out.
     *
     * Format, pipe separated (12 fields; the emitter in `tileMetrics` is the authority):
     * `pos|title|left,top,right,bottom|containerTx,containerTy|reflowX,reflowY|itemTx,itemTy|`
     * `containerScreenX,containerScreenY|itemScreenX,itemScreenY|width,height|scaleX|itemType|spanX,spanY`
     *
     * The container and the item view are reported separately on purpose. On the home grid the
     * orbit, the reflow and the follow are written to the **holder container** (a plain
     * `FrameLayout`, so a direct `translationX` write) while the label lift is written to the
     * **item view** through `MultiTranslateDelegate`'s [INDEX_REORDER_BOUNCE_OFFSET]. Reading
     * `getTranslationX()` off either one alone is a partial answer; the drawn position of the icon
     * is the sum.
     */
    const val REQUEST_TILE_METRICS = "ares-tile-metrics"

    /**
     * The W1 metric: `viewGroup|layoutManager|adapter` child counts for the home grid.
     *
     * The three disagree in exactly one interesting way. `RecyclerView.getChildCount()` is plain
     * `ViewGroup`, so it counts **every** attached child including ones `ChildHelper` is hiding;
     * `LayoutManager.getChildCount()` is `getUnhiddenChildCount()`, so it does not. A view that
     * `animateDisappearance` re-attached hidden and never removed shows up as
     * `viewGroup > layoutManager` and is invisible to everything the layout manager can reach —
     * it cannot be laid out, cannot be recycled, and keeps the bounds it had when it was deleted.
     *
     * That is the ghost the owner reported, and this is the number that says whether one is
     * present. It is the same divergence the ledger recorded by hand as "17 attached children
     * against 16 database rows", measured directly rather than parsed out of `dumpsys`.
     */
    const val REQUEST_CHILD_CENSUS = "ares-child-census"

    /**
     * Removes the first home item matching `arg` (`"widget"` or `"icon"`), returning its id or -1.
     *
     * Calls [AresHomeListView.removeFromHome] — the same function the × badge's click listener
     * calls, and the whole point of routing through it rather than poking the adapter. The W1 bug
     * lives downstream of `notifyItemRemoved`, in RecyclerView's disappearance handling, so what
     * matters is that the notify is real; whether the finger or the test asked for it is not part
     * of the mechanism. Driving it from here rather than through a synthesised tap on a badge also
     * keeps the check away from the gesture reliability this harness is separately known to be bad
     * at.
     */
    const val REQUEST_REMOVE_ITEM = "ares-remove-item"

    /**
     * WP folders Phase 2 spike (design/wp-phase2-spike.md): resolve the PURE drag-membership
     * classifier for a hypothetical drag, WITHOUT performing one. `arg` is `"<draggedId>,<targetId>"`
     * (targetId `none` = empty grid area). Returns the [AresWpMembership.Action] as a string, e.g.
     * `Extract(960)` / `ReorderInFolder(960)` / `AddToFolder(960)` / `None`. Lets the truth table be
     * verified exhaustively off a real gesture (which is ~2-in-5 flaky here). Expand the folder first
     * so its children are in the adapter.
     */
    const val REQUEST_WP_RESOLVE_DRAG = "ares-wp-resolve-drag"

    /**
     * WP folders Phase 2: exercise the reorder-inside WRITE without a flaky drag. `arg` is a WP
     * folder id; swaps its first two expanded children in the adapter (as an in-folder drag would)
     * and calls `persistWpChildOrder`, returning the resulting child id order. Verifies folder-local
     * rank persistence + reload survival; the real drag GESTURE is the owner-Pixel gate.
     */
    const val REQUEST_WP_REORDER_TEST = "ares-wp-reorder-test"

    /**
     * WP folders Phase 3 #5: rename a WP folder without the edit-mode dialog (the dialog's FEEL is
     * the owner-Pixel gate; the persistence is not). `arg` is `folderId,newTitle` -- split on the
     * FIRST comma so the title may contain spaces. Calls the same `renameWpFolder` the dialog does,
     * then returns `title|manual` where manual is whether FLAG_MANUAL_FOLDER_NAME is now set. Reload
     * survival is checked by re-reading the title over `ares-home-order` after a forced reload.
     */
    const val REQUEST_WP_RENAME = "ares-wp-rename"

    /** WP folders: toggle a folder's inline expansion by id (no gesture), for tests that need an
     * open folder (e.g. reading [REQUEST_PACK_CELLS] with children spliced). `arg` is the folder id;
     * returns `expanded=<bool>|contents=<n>`. */
    const val REQUEST_WP_EXPAND = "ares-wp-expand"

    /**
     * WP folders: raise or dismiss the INLINE RENAME editor on the currently expanded folder, with
     * no gesture -- the editor is normally opened by tapping the canvas-drawn title band, which a
     * test cannot hit without knowing where it was drawn. `arg` is "begin" or "end".
     *
     * Returns `editor=<bool>|scrollLocked=<bool>`, which is what the scroll-pin fix is asserted on
     * (owner 2026-09-02: the list scrolled during a rename while the editor, absolutely positioned
     * in the DragLayer, stayed put).
     */
    const val REQUEST_WP_RENAME_INLINE = "ares-wp-rename-inline"

    /**
     * WP folders: move an existing grid app into a folder and (if the folder is OPEN) splice it into
     * the run, exercising the RENDER path [AresHomeAdapter.addChildToExpandedRun] without the flaky
     * dwell gesture (owner bug 2026-08-24: an app added to an already-open folder did not render).
     * `arg` is `"<folderId>,<appId>"`. Does the minimal in-memory model move (container+rank) plus the
     * adapter splice; persistence is the real dwell path's job and the owner-Pixel gate. Returns
     * `added=<id>|contents=<n>|run=<range>`.
     */
    const val REQUEST_WP_ADD_CHILD = "ares-wp-add-child"

    /**
     * WP folders Phase 3 #3: the packer's placement of EVERY item, one `id|x,y` per adapter
     * position in order, so the reserved-run block (folder + children contiguous) can be verified
     * off-screen too. Lets a test assert the children pack immediately after the folder in row-major
     * order even when an upstream widget hole would otherwise scatter them.
     */
    const val REQUEST_PACK_CELLS = "ares-pack-cells"

    /**
     * WP folders Phase 3 #3: a self-contained proof that the reserved run keeps a folder+children
     * block contiguous EVEN when an upstream hole would otherwise scatter it -- runs the real
     * [AresPacker] on a crafted span list (a 3-wide item then a 2-wide item leave a 1-cell hole at
     * (3,0)) with and without the run, and returns both placements. Independent of whatever the live
     * grid happens to look like. `norun=...` `run=...`, each a list of `x,y`.
     */
    const val REQUEST_PACK_PROBE = "ares-pack-probe"


    /**
     * The folder surface's metrics, for S12 and D9. Empty array when no folder is open.
     *
     * Line 0 is the folder itself:
     * `folder|top,bottom|contentBottom|nameTop,nameBottom|state|dragViews`
     *
     * Then one line per icon:
     * `icon|title|stateLift|actualTy|screenX,screenY|width,height`
     *
     * **`stateLift` and `actualTy` are both reported, and the gap between them is the bug.**
     * `AresEditLabel.liftOf` returns what the label state *believes* it wrote; `actualTy` reads the
     * view's real `INDEX_REORDER_BOUNCE_OFFSET` channel. S12 is precisely those two disagreeing —
     * `AresEditWiggle.start`'s animators-off path called the full teardown, which dropped the whole
     * `Motion` entry including the lift, while the label state went on believing it had applied one
     * and therefore declined to write it again. A probe that read only `liftOf` would report the
     * belief and miss the defect entirely.
     *
     * The folder line carries D9: the reported symptom is *"with three items in a folder, starting
     * to move one sends the folder name from the bottom to the centre"*, which is a layout
     * consequence of the content shrinking by a row mid-drag. `nameTop` not moving between rest and
     * mid-drag is the evidence that closes it.
     */
    const val REQUEST_FOLDER_METRICS = "ares-folder-metrics"

    /**
     * Opens the first folder on the home grid, returning whether one was opened.
     *
     * Through `FolderIcon.performClick`, i.e. the path a tap takes, rather than a synthesised tap.
     * A scripted gesture aimed at a folder **closes it more often than it opens it** here — that is
     * why `folder-edit-chrome` and `folder-badge-geometry` SKIP in the PowerShell harness, and it is
     * a reachability problem, not something worth re-tuning. What is under test on this surface is
     * what happens once the folder is open, so getting there deterministically is the point.
     */
    const val REQUEST_OPEN_FOLDER = "ares-open-folder"

    /** Turns folder edit mode on or off on the open folder. `arg`: "on" (default) or "off". */
    const val REQUEST_FOLDER_EDIT = "ares-folder-edit"

    /** §25 create+open verification: create + open a real 2-item folder; returns what happened. */
    const val REQUEST_LIVE_CREATE_SPIKE = "ares-live-create-spike"

    /** §25 gate: `arg` "on"/"off" toggles live-create on the dwell-arm path; returns the new state. */
    const val REQUEST_LIVE_CREATE_ENABLE = "ares-live-create-enable"

    /**
     * The surface at a glance: `state|inTransition|optionsPopup|scrollOffset`.
     *
     * Exists for two defects that are invisible to layout bounds. Ledger row 25: while the
     * empty-space popup is up, a sideways drag must NOT pan the app-list pane -- pane movement is a
     * state transition, so `inTransition` going true with `optionsPopup` still open IS the bug.
     * Ledger row 27: the stock `onMoved` default called `scrollToPosition`, an ABSOLUTE jump of the
     * masonry scroll offset mid-drag; `scrollOffset` sampled across a swap is the number that
     * moves. Sampled, both of them -- each defect exists only while a finger is down.
     */
    const val REQUEST_SURFACE_STATE = "ares-surface-state"

    /**
     * SPIKE trigger for the WP-style home reveal ([AresHomeReveal]). `arg`:
     *  - `on` / `off` -- flip the every-home-appearance auto-trigger, or
     *  - absent / `play` -- fire a single reveal now.
     * Returns a short status string. Owner-review only; nothing here runs in a normal build.
     */
    const val REQUEST_HOME_REVEAL = "ares-home-reveal"

    /**
     * Home grid column override (owner 2026-08-26). No `arg` → reports the current effective count;
     * `arg` = 3..6 → sets it (the same render-only path the edit-mode stepper drives). Returns
     * `columns=<n>`. Lets a test drive the column count deterministically without the flaky
     * stepper tap.
     */
    const val REQUEST_HOME_COLUMNS = "ares-home-columns"

    /**
     * Runtime invariant violations ([AresInvariants]).
     *
     * `arg` = `reset` clears the counters and returns `total=0`; absent returns
     * `total=N|<id>=<count>|...` plus the first violating record for each.
     *
     * DELTA, NOT TOTAL. `ares-smoke` resets at the start of a run and asserts zero at the end. A
     * raw total would be neither per-run nor per-install nor per-boot -- the counters live in a
     * process-scoped object, so one violation at startup would make the suite permanently red while
     * any process kill would silently reset it to green, and both failures are invisible.
     *
     * Deliberately NOT routed through `getLauncherUIProperty`. Every other channel here resolves a
     * live `Launcher` and answers `null` when there is not one -- and a caller comparing `null > 0`
     * reads that as a PASS. These counters are process-scoped and need no Launcher, so this answers
     * even when the activity is gone, which is precisely when a violation is most interesting.
     */
    const val REQUEST_INVARIANTS = "ares-invariants"

    /**
     * The app list's edge-glow state: `topFinished|bottomFinished`.
     *
     * Ledger row 29: `mAllAppsOvershootStarted` armed by an overscroll pull was only released when
     * the gesture settled on ALL_APPS -- reverse to home and the `EdgeEffect` stayed in STATE_PULL
     * forever. An `EdgeEffect` leaves that state only via `onRelease()`/`onAbsorb()`, and
     * `isFinished` is the observable. Read by reflection on `SpringRelativeLayout`'s private
     * fields: our own APK, so no hidden-API concern, and no product accessor added for a test.
     */
    const val REQUEST_OVERSCROLL_STATE = "ares-overscroll-state"

    /**
     * Monotonic count of S4 decline-branch executions (release outside an open folder inside the
     * grace). Exists so the S4 journey can PROVE its scenario ran: the margin between exercised
     * and vacuously green is tens of milliseconds of settle timing, and a pass that never entered
     * the branch must be detectable.
     */
    const val REQUEST_FOLDER_DROP_STATS = "ares-folder-drop-stats"

    /**
     * Drives [AresViewCapture], the in-memory recording of the launcher's view tree that lets a test
     * assert on how something ANIMATED rather than where it ended up.
     *
     * `arg` is the sub-command:
     * - `start` — begin recording. Returns `started`, `already-running`, or `error:<Exception>`.
     * - `stop` — stop recording, keeping the frames readable. `stopped` / `already-stopped` /
     *   `not-running`.
     * - `export` — write the proto and return `<abs path>|frames=<n>`, or `empty` / `not-running`.
     * - `reset` — tear it down so a later `start` begins from an empty buffer.
     * - anything else (including absent) — `recording=<bool>` as a status read.
     *
     * ## Why the sub-commands are NOT uniformly routed through `getLauncherUIProperty`
     *
     * They genuinely need different threads, and getting it wrong deadlocks rather than fails:
     *
     * `start` attaches an `OnDrawListener` to the decor view, which must happen on the UI thread —
     * and it needs a live `Launcher`, so `getLauncherUIProperty` is exactly right, `null` when
     * there is no launcher included.
     *
     * `export` must NOT run on the UI thread. `ViewCapture.getExportedData` blocks on `.get()` of a
     * future whose first stage is `CompletableFuture.supplyAsync(..., MAIN_EXECUTOR)`; called from
     * main it waits on the thread that has to produce its answer. It runs on the binder thread,
     * using the application context [AresViewCapture] kept at `start`.
     *
     * `stop` and `reset` are `@AnyThread` in the library — `ViewCapture.runOnUiThread` posts to the
     * view's handler itself — so they also answer on the binder thread and, like the invariants
     * channel, work even when the activity is gone.
     */
    const val REQUEST_VIEW_CAPTURE = "ares-view-capture"

    /**
     * Handles an Ares request, or returns null if [method] is not one of ours.
     *
     * Called from `TestInformationHandler.call`'s `default:` branch, so stock's own switch is
     * untouched and a future Lawnchair merge sees one added line rather than a rewritten method.
     */
    @JvmStatic
    fun handle(method: String, arg: String?): Bundle? = when (method) {
        // Stock's own seam, used rather than reimplemented: `getLauncherUIProperty` submits the
        // lambda to MAIN_EXECUTOR and blocks on the result, so the lambda runs on the UI thread
        // with the LIVE Launcher and can read anything a frame callback could.
        REQUEST_HOME_ORDER -> TestInformationHandler.getLauncherUIProperty(
            { b, key, value -> b.putStringArray(key, value) },
            { launcher -> homeOrder(launcher) },
        )
        REQUEST_EDIT_MODE -> TestInformationHandler.getLauncherUIProperty(
            { b, key, value -> b.putBoolean(key, value) },
            { launcher -> launcher.workspace?.aresHomeList?.isEditMode() ?: false },
        )
        REQUEST_TILE_METRICS -> TestInformationHandler.getLauncherUIProperty(
            { b, key, value -> b.putStringArray(key, value) },
            { launcher -> tileMetrics(launcher) },
        )
        REQUEST_CHILD_CENSUS -> TestInformationHandler.getLauncherUIProperty(
            { b, key, value -> b.putString(key, value) },
            { launcher -> childCensus(launcher) },
        )
        REQUEST_REMOVE_ITEM -> TestInformationHandler.getLauncherUIProperty(
            { b, key, value -> b.putInt(key, value) },
            { launcher -> removeFirst(launcher, arg) },
        )
        REQUEST_WP_RESOLVE_DRAG -> TestInformationHandler.getLauncherUIProperty(
            { b, key, value -> b.putString(key, value) },
            { launcher -> wpResolveDrag(launcher, arg) },
        )
        REQUEST_WP_REORDER_TEST -> TestInformationHandler.getLauncherUIProperty(
            { b, key, value -> b.putString(key, value) },
            { launcher -> wpReorderTest(launcher, arg) },
        )
        REQUEST_WP_RENAME -> TestInformationHandler.getLauncherUIProperty(
            { b, key, value -> b.putString(key, value) },
            { launcher -> wpRename(launcher, arg) },
        )
        REQUEST_WP_EXPAND -> TestInformationHandler.getLauncherUIProperty(
            { b, key, value -> b.putString(key, value) },
            { launcher -> wpExpand(launcher, arg) },
        )
        REQUEST_WP_RENAME_INLINE -> TestInformationHandler.getLauncherUIProperty(
            { b, key, value -> b.putString(key, value) },
            { launcher -> wpRenameInline(launcher, arg) },
        )
        REQUEST_WP_ADD_CHILD -> TestInformationHandler.getLauncherUIProperty(
            { b, key, value -> b.putString(key, value) },
            { launcher -> wpAddChild(launcher, arg) },
        )
        REQUEST_PACK_CELLS -> TestInformationHandler.getLauncherUIProperty(
            { b, key, value -> b.putStringArray(key, value) },
            { launcher -> packCells(launcher) },
        )
        REQUEST_PACK_PROBE -> TestInformationHandler.getLauncherUIProperty(
            { b, key, value -> b.putStringArray(key, value) },
            { _ -> packProbe() },
        )
        REQUEST_FOLDER_EDIT -> TestInformationHandler.getLauncherUIProperty(
            { b, key, value -> b.putBoolean(key, value) },
            { launcher -> setFolderEdit(launcher, arg) },
        )
        REQUEST_SURFACE_STATE -> TestInformationHandler.getLauncherUIProperty(
            { b, key, value -> b.putString(key, value) },
            { launcher -> surfaceState(launcher) },
        )
        REQUEST_OVERSCROLL_STATE -> TestInformationHandler.getLauncherUIProperty(
            { b, key, value -> b.putString(key, value) },
            { launcher -> overscrollState(launcher) },
        )
        REQUEST_FOLDER_DROP_STATS -> TestInformationHandler.getLauncherUIProperty(
            { b, key, value -> b.putLong(key, value) },
            { _ -> AresFolderDrop.declinedExitingCount() },
        )
        REQUEST_FOLDER_METRICS -> TestInformationHandler.getLauncherUIProperty(
            { b, key, value -> b.putStringArray(key, value) },
            { launcher -> folderMetrics(launcher) },
        )
        REQUEST_OPEN_FOLDER -> TestInformationHandler.getLauncherUIProperty(
            { b, key, value -> b.putBoolean(key, value) },
            { launcher -> openFirstFolder(launcher) },
        )
        REQUEST_LIVE_CREATE_SPIKE -> TestInformationHandler.getLauncherUIProperty(
            { b, key, value -> b.putString(key, value) },
            { launcher -> liveCreateSpike(launcher) },
        )
        REQUEST_LIVE_CREATE_ENABLE -> TestInformationHandler.getLauncherUIProperty(
            { b, key, value -> b.putBoolean(key, value) },
            { _ ->
                AresFolderDrop.setLiveCreateEnabled(arg == "on")
                AresFolderDrop.isLiveCreateEnabled()
            },
        )
        REQUEST_HOME_COLUMNS -> TestInformationHandler.getLauncherUIProperty(
            { b, key, value -> b.putString(key, value) },
            { launcher -> homeColumns(launcher, arg) },
        )
        // No getLauncherUIProperty: see REQUEST_INVARIANTS. Answers with or without a live Launcher.
        REQUEST_INVARIANTS -> Bundle().apply {
            if (arg == "reset") AresInvariants.reset()
            // `selftest` records a synthetic violation so the HARNESS can be proven capable of
            // failing. The seeded invariant is defect-ledger row 40, which is not reproducible on
            // demand -- the whole reason it is still open -- so without this the smoke check would
            // be an assertion that has never been shown to fail, which this project treats as no
            // coverage at all. It proves the plumbing (record -> count -> channel -> smoke FAIL),
            // NOT the invariant's own predicate. Nothing in the product calls it.
            if (arg == "selftest") {
                AresInvariants.violation(
                    "INV-SELFTEST", "ares-invariants selftest",
                    "synthetic violation, injected by the harness",
                )
            }
            putString(
                com.android.launcher3.testing.shared.TestProtocol.TEST_INFO_RESPONSE_FIELD,
                AresInvariants.report(),
            )
        }
        REQUEST_HOME_REVEAL -> TestInformationHandler.getLauncherUIProperty(
            { b, key, value -> b.putString(key, value) },
            { launcher -> homeReveal(launcher, arg) },
        )
        REQUEST_VIEW_CAPTURE -> viewCapture(arg)
        else -> null
    }

    /** See [REQUEST_VIEW_CAPTURE]. Each sub-command picks its own thread; the KDoc says why. */
    private fun viewCapture(arg: String?): Bundle? = when (arg) {
        // UI thread, and needs a live Launcher: attaches an OnDrawListener to the decor view.
        "start" -> TestInformationHandler.getLauncherUIProperty(
            { b, key, value -> b.putString(key, value) },
            { launcher -> AresViewCapture.start(launcher) },
        )
        // Binder thread. `export` on main would deadlock against MAIN_EXECUTOR; `stop`/`reset` are
        // @AnyThread and are useful precisely when the activity is already gone.
        "stop" -> respond(AresViewCapture.stop())
        "export" -> respond(AresViewCapture.export())
        "reset" -> respond(AresViewCapture.reset())
        else -> respond("recording=${AresViewCapture.isRecording}")
    }

    /** A plain answer with no `Launcher` resolution, for the channels that do not need one. */
    private fun respond(value: String): Bundle = Bundle().apply {
        putString(com.android.launcher3.testing.shared.TestProtocol.TEST_INFO_RESPONSE_FIELD, value)
    }

    /** See [REQUEST_HOME_COLUMNS]. Runs on the UI thread via getLauncherUIProperty. */
    private fun homeColumns(launcher: Launcher, arg: String?): String {
        val list = launcher.workspace?.aresHomeList ?: return "no-list"
        arg?.trim()?.toIntOrNull()?.let { list.setGridColumns(it) }
        return "columns=${list.currentColumns()}"
    }

    /** See [REQUEST_HOME_REVEAL]. Runs on the UI thread via getLauncherUIProperty. */
    private fun homeReveal(launcher: Launcher, arg: String?): String = when (arg) {
        "on" -> { AresHomeReveal.enabled = true; "enabled" }
        "off" -> { AresHomeReveal.enabled = false; "disabled" }
        else -> { AresHomeReveal.play(launcher); "played(enabled=${AresHomeReveal.enabled})" }
    }

    /** See [REQUEST_LIVE_CREATE_SPIKE]. */
    private fun liveCreateSpike(launcher: Launcher): String {
        val list = launcher.workspace?.aresHomeList ?: return "no home list"
        return AresFolderDrop.spikeOneItemFolder(launcher, list)
    }

    // ---------------------------------------------------------------- internals

    /**
     * Item identity in visual order, as `id/title`.
     *
     * The database id leads because a **widget's `title` is null** -- an order made of titles alone
     * cannot tell two widgets apart, and telling two widgets apart is the whole of the widget-swap
     * regression check.
     */
    private fun homeOrder(launcher: Launcher): Array<String> {
        val list = launcher.workspace?.aresHomeList ?: return emptyArray()
        val adapter = list.aresAdapter
        return (0 until adapter.itemCount).map { i ->
            val info = adapter.itemAt(i)
            if (info == null) "?" else "${info.id}/${info.title ?: "type${info.itemType}"}"
        }.toTypedArray()
    }

    /** See [REQUEST_CHILD_CENSUS]. `viewGroup|layoutManager|adapter`. */
    private fun childCensus(launcher: Launcher): String {
        val list = launcher.workspace?.aresHomeList ?: return "0|0|0"
        // list.childCount is ViewGroup's, so it counts hidden children too. The layout manager's
        // is getUnhiddenChildCount(). Their difference is the ghost count.
        val viewGroup = list.childCount
        val layoutManager = list.layoutManager?.childCount ?: 0
        val adapter = list.aresAdapter.itemCount
        return "$viewGroup|$layoutManager|$adapter"
    }

    /** See [REQUEST_REMOVE_ITEM]. Returns the removed item's id, or -1. */
    private fun removeFirst(launcher: Launcher, arg: String?): Int {
        val list = launcher.workspace?.aresHomeList ?: return -1
        val adapter = list.aresAdapter
        val wantWidget = arg == "widget"
        for (i in 0 until adapter.itemCount) {
            val info = adapter.itemAt(i) ?: continue
            val isWidget = info.itemType == Favorites.ITEM_TYPE_APPWIDGET ||
                info.itemType == Favorites.ITEM_TYPE_CUSTOM_APPWIDGET
            if (isWidget != wantWidget) continue
            list.removeFromHome(info)
            return info.id
        }
        return -1
    }

    /** See [REQUEST_WP_RESOLVE_DRAG]. Resolves the pure classifier; no drag, no model write. */
    private fun wpResolveDrag(launcher: Launcher, arg: String?): String {
        val list = launcher.workspace?.aresHomeList ?: return "no-list"
        val parts = arg?.split(",") ?: return "bad-arg"
        val draggedId = parts.getOrNull(0)?.trim()?.toIntOrNull() ?: return "bad-dragged"
        val targetToken = parts.getOrNull(1)?.trim() ?: "none"
        val items = list.aresAdapter.snapshot()
        val dragged = items.firstOrNull { it.id == draggedId } ?: return "no-dragged($draggedId)"
        val target = if (targetToken == "none") {
            null
        } else {
            val tid = targetToken.toIntOrNull() ?: return "bad-target"
            items.firstOrNull { it.id == tid } ?: return "no-target($tid)"
        }
        val expanded = list.aresAdapter.expandedWpFolder()
        return when (val a = AresWpMembership.resolve(dragged, target, expanded)) {
            is AresWpMembership.Action.None -> "None"
            is AresWpMembership.Action.Extract -> "Extract(${a.folderId})"
            is AresWpMembership.Action.ReorderInFolder -> "ReorderInFolder(${a.folderId})"
            is AresWpMembership.Action.AddToFolder -> "AddToFolder(${a.folderId})"
        }
    }

    /** See [REQUEST_PACK_PROBE]. Runs the real packer on a crafted upstream-hole scenario. */
    private fun packProbe(): Array<String> {
        // 4 columns. A 3-wide then a 2-wide item leave a 1-cell hole at (3,0). Indices 2..5 are the
        // folder tile + 3 children that must stay together.
        val spans = listOf(
            AresPacker.Span(3, 1),
            AresPacker.Span(2, 1),
            AresPacker.Span(1, 1),
            AresPacker.Span(1, 1),
            AresPacker.Span(1, 1),
            AresPacker.Span(1, 1),
        )
        fun fmt(l: AresPacker.Layout) = l.cells.joinToString(" ") { "${it.x},${it.y}" }
        val noRun = AresPacker.pack(spans, 4, null)
        val withRun = AresPacker.pack(spans, 4, 2..5)
        // Folder-above-widget, the REAL owner case (report 2026-08-24): an app, then a full-width
        // 4x2 WIDGET, then a folder whose rank is AFTER the widget -- so collapsed it backfills into
        // row 0 ABOVE the widget -- then its 3 children (run 2..5), then a trailing app. The folder
        // must STAY at (1,0) above the widget, its children open on row 1, and the widget must be
        // PUSHED DOWN to rows 2-3. Expected: A(0,0) W(0,2) F(1,0) c(0,1)(1,1)(2,1) B(2,0).
        val fw = listOf(
            AresPacker.Span(1, 1), // 0 app A
            AresPacker.Span(4, 2), // 1 widget (ranked before the folder)
            AresPacker.Span(1, 1), // 2 folder (backfills above the widget when collapsed)
            AresPacker.Span(1, 1), // 3 child
            AresPacker.Span(1, 1), // 4 child
            AresPacker.Span(1, 1), // 5 child
            AresPacker.Span(1, 1), // 6 app B
        )
        val fwRun = AresPacker.pack(fw, 4, 2..5)
        return arrayOf("norun=${fmt(noRun)}", "run=${fmt(withRun)}", "folderAboveWidget=${fmt(fwRun)}")
    }

    /** See [REQUEST_PACK_CELLS]. `id|x,y` per adapter position, in order. */
    private fun packCells(launcher: Launcher): Array<String> {
        val list = launcher.workspace?.aresHomeList ?: return emptyArray()
        val lm = list.layoutManager as? AresMasonryLayoutManager ?: return emptyArray()
        val cells = lm.currentCells()
        return cells.mapIndexed { pos, cell ->
            val id = list.aresAdapter.itemAt(pos)?.id ?: -1
            "$id|${cell.x},${cell.y}"
        }.toTypedArray()
    }

    /** See [REQUEST_WP_EXPAND]. Toggles a WP folder's inline expansion (no gesture). */
    private fun wpExpand(launcher: Launcher, arg: String?): String {
        val list = launcher.workspace?.aresHomeList ?: return "no-list"
        val fid = arg?.trim()?.toIntOrNull() ?: return "bad-arg"
        val folder = list.aresAdapter.snapshot().firstOrNull { it.id == fid } as? FolderInfo
            ?: return "no-folder($fid)"
        if (!folder.isAresWpFolder) return "not-wp"
        val expanded = list.aresAdapter.toggleWpFolder(folder)
        return "expanded=$expanded|contents=${folder.getContents().size}"
    }

    /** See [REQUEST_WP_RENAME_INLINE]. Raises/dismisses the inline rename editor without a gesture. */
    private fun wpRenameInline(launcher: Launcher, arg: String?): String {
        val list = launcher.workspace?.aresHomeList ?: return "no-list"
        when (arg?.trim()) {
            "begin" -> list.beginInlineFolderRename()
            "end" -> list.dismissInlineFolderRename(false)
            else -> return "bad-arg"
        }
        return "editor=" + list.isInlineRenameActive() +
            "|scrollLocked=" + list.isScrollLockedForTest() +
            "|" + list.renameImeProbeForTest()
    }

    /** See [REQUEST_WP_ADD_CHILD]. Render-path check for adding into an OPEN folder (no gesture). */
    private fun wpAddChild(launcher: Launcher, arg: String?): String {
        val list = launcher.workspace?.aresHomeList ?: return "no-list"
        val comma = arg?.indexOf(',') ?: -1
        if (comma < 0) return "bad-arg"
        val fid = arg!!.substring(0, comma).trim().toIntOrNull() ?: return "bad-fid"
        val aid = arg.substring(comma + 1).trim().toIntOrNull() ?: return "bad-aid"
        val snap = list.aresAdapter.snapshot()
        val folder = snap.firstOrNull { it.id == fid } as? FolderInfo ?: return "no-folder($fid)"
        if (!folder.isAresWpFolder) return "not-wp"
        val item = snap.firstOrNull { it.id == aid } ?: return "no-item($aid)"
        if (item is FolderInfo) return "item-is-folder"
        // Minimal in-memory model move so the row reads as this folder's child.
        item.container = fid
        item.rank = folder.getContents().size
        if (folder.getContents().none { it.id == aid }) folder.add(item)
        list.aresAdapter.removeItems { it.id == aid }
        list.aresAdapter.addChildToExpandedRun(folder, item)
        return "added=$aid|contents=${folder.getContents().size}|run=${list.aresAdapter.expandedRunRange()}"
    }

    /** See [REQUEST_WP_RENAME]. Drives the persistence path; the dialog's feel is the owner gate. */
    private fun wpRename(launcher: Launcher, arg: String?): String {
        val list = launcher.workspace?.aresHomeList ?: return "no-list"
        val comma = arg?.indexOf(',') ?: -1
        if (comma < 0) return "bad-arg"
        val fid = arg!!.substring(0, comma).trim().toIntOrNull() ?: return "bad-id"
        val newTitle = arg.substring(comma + 1)
        val folder = list.aresAdapter.snapshot().firstOrNull { it.id == fid } as? FolderInfo
            ?: return "no-folder($fid)"
        list.aresAdapter.renameWpFolder(folder, newTitle)
        val manual = (folder.options and FolderInfo.FLAG_MANUAL_FOLDER_NAME) != 0
        return "${folder.title}|$manual"
    }

    /** See [REQUEST_WP_REORDER_TEST]. */
    private fun wpReorderTest(launcher: Launcher, arg: String?): String {
        val list = launcher.workspace?.aresHomeList ?: return "no-list"
        val fid = arg?.trim()?.toIntOrNull() ?: return "bad-arg"
        val adapter = list.aresAdapter
        val positions = (0 until adapter.itemCount).filter { adapter.itemAt(it)?.container == fid }
        if (positions.size < 2) return "need-2-children(${positions.size})"
        adapter.moveItem(positions[0], positions[1])
        adapter.persistWpChildOrder(launcher, fid)
        val order = (0 until adapter.itemCount)
            .mapNotNull { adapter.itemAt(it) }
            .filter { it.container == fid }
            .map { it.id }
        return "order=$order"
    }

    private fun tileMetrics(launcher: Launcher): Array<String> {
        val list = launcher.workspace?.aresHomeList ?: return emptyArray()
        val out = ArrayList<String>()
        val loc = IntArray(2)
        for (i in 0 until list.childCount) {
            val container = list.getChildAt(i) ?: continue
            val pos = list.getChildAdapterPosition(container)
            val info = list.aresAdapter.itemAt(pos)
            val title = if (info == null) "?" else "${info.id}/${info.title ?: "type${info.itemType}"}"
            val item = (container as? ViewGroup)?.getChildAt(0)
            val itemT = item?.let { translationOf(it) } ?: floatArrayOf(0f, 0f)
            container.getLocationOnScreen(loc)
            val containerScreenX = loc[0]
            val containerScreenY = loc[1]
            var itemScreenX = -1
            var itemScreenY = -1
            if (item != null) {
                item.getLocationOnScreen(loc)
                itemScreenX = loc[0]
                itemScreenY = loc[1]
            }
            out.add(
                buildString {
                    append(pos).append('|')
                    append(title).append('|')
                    append(container.left).append(',').append(container.top).append(',')
                    append(container.right).append(',').append(container.bottom).append('|')
                    append(container.translationX).append(',').append(container.translationY)
                        .append('|')
                    append(AresEditMotion.reflowX(container)).append(',')
                    append(AresEditMotion.reflowY(container)).append('|')
                    append(itemT[0]).append(',').append(itemT[1]).append('|')
                    // Ground truth: the full transform chain, scale and all. Compare against
                    // box + translation above -- edit mode scales the list by EDIT_MODE_SCALE, so
                    // the two are NOT the same number and only this one is where the finger has
                    // to land.
                    append(containerScreenX).append(',').append(containerScreenY).append('|')
                    append(itemScreenX).append(',').append(itemScreenY).append('|')
                    append(container.width).append(',').append(container.height).append('|')
                    append(container.scaleX).append('|')
                    // itemType, so a caller can pick out the widgets. ITEM_TYPE_APPWIDGET is 4.
                    append(info?.itemType ?: -1).append('|')
                    append(info?.spanX ?: -1).append(',').append(info?.spanY ?: -1)
                },
            )
        }
        return out.toTypedArray()
    }

    /** See [REQUEST_SURFACE_STATE]. */
    private fun surfaceState(launcher: Launcher): String {
        val state = launcher.stateManager.state.toString().substringAfterLast('.')
        val inTransition = launcher.stateManager.isInTransition
        val popup = AbstractFloatingView.getOpenView<AbstractFloatingView>(
            launcher, AbstractFloatingView.TYPE_OPTIONS_POPUP,
        ) != null
        val offset = (launcher.workspace?.aresHomeList?.layoutManager as? AresMasonryLayoutManager)
            ?.currentScrollOffset() ?: -1
        return "$state|$inTransition|$popup|$offset"
    }

    /** See [REQUEST_OVERSCROLL_STATE]. Reads SpringRelativeLayout's private glows by reflection. */
    private fun overscrollState(launcher: Launcher): String {
        val apps: View = launcher.appsView
        fun finished(name: String): Boolean = try {
            var c: Class<*>? = apps.javaClass
            var f: java.lang.reflect.Field? = null
            while (c != null && f == null) {
                f = try { c.getDeclaredField(name) } catch (e: NoSuchFieldException) { null }
                c = c.superclass
            }
            f?.isAccessible = true
            (f?.get(apps) as? android.widget.EdgeEffect)?.isFinished ?: true
        } catch (e: Exception) {
            true
        }
        return "${finished("mEdgeGlowTop")}|${finished("mEdgeGlowBottom")}"
    }

    /** See [REQUEST_OPEN_FOLDER]. */
    private fun openFirstFolder(launcher: Launcher): Boolean {
        val list = launcher.workspace?.aresHomeList ?: return false
        for (i in 0 until list.childCount) {
            val container = list.getChildAt(i) as? ViewGroup ?: continue
            val icon = container.getChildAt(0)
            if (icon is FolderIcon) {
                icon.performClick()
                return true
            }
        }
        return false
    }

    /**
     * Attaches or detaches folder edit mode on the open folder. `arg` "on" or "off".
     *
     * `AresFolderEdit.attach` is the product's own entry point -- it is what a click on a folder
     * takes while the grid is already editing. Reaching it by gesture would mean a long-press to
     * enter home edit mode and then a tap that opens rather than closes the folder, which is the
     * exact sequence this harness is documented as unable to perform reliably.
     */
    private fun setFolderEdit(launcher: Launcher, arg: String?): Boolean {
        if (arg == "off") {
            AresFolderEdit.detach()
            return true
        }
        val folder = Folder.getOpen(launcher) ?: return false
        val icon = folder.folderIcon ?: return false
        AresFolderEdit.attach(launcher, icon)
        return true
    }

    /** See [REQUEST_FOLDER_METRICS]. */
    private fun folderMetrics(launcher: Launcher): Array<String> {
        val folder = Folder.getOpen(launcher) ?: return emptyArray()
        val out = ArrayList<String>()
        val loc = IntArray(2)

        folder.getLocationOnScreen(loc)
        val folderTop = loc[1]
        val folderBottom = loc[1] + folder.height
        val content = folder.findViewById<View>(R.id.folder_content)
        val contentBottom = content?.let {
            it.getLocationOnScreen(loc); loc[1] + it.height
        } ?: -1
        val name = folder.findViewById<View>(R.id.folder_name)
        val nameTop = name?.let { it.getLocationOnScreen(loc); loc[1] } ?: -1
        val nameBottom = if (name != null && nameTop >= 0) nameTop + name.height else -1
        // DragViews in the DragLayer: the D4 tell. A bare hold that wrongly arms a drag lifts the
        // icon OUT of the folder container into a DragView, so the chrome is absent by
        // construction rather than lost -- see the D4 correction in defect-ledger.md.
        var dragViews = 0
        val dl = launcher.dragLayer
        for (i in 0 until dl.childCount) {
            if (dl.getChildAt(i)?.javaClass?.simpleName?.contains("DragView") == true) dragViews++
        }
        out.add(
            "folder|$folderTop,$folderBottom|$contentBottom|$nameTop,$nameBottom|" +
                "${if (folder.aresIsAnimating()) "animating" else "settled"}|$dragViews",
        )

        for (icon in folder.iconsInReadingOrder) {
            val t = translationOf(icon)
            icon.getLocationOnScreen(loc)
            val title = (icon as? BubbleTextView)?.text?.toString() ?: "?"
            // Both numbers, deliberately: their disagreement IS S12. See REQUEST_FOLDER_METRICS.
            out.add("icon|$title|${AresEditLabel.liftOf(icon)}|${t[1]}|${loc[0]},${loc[1]}|${icon.width},${icon.height}")
        }
        return out.toTypedArray()
    }

    private fun translationOf(view: View): FloatArray {
        val reorderable = view as? Reorderable
        return if (reorderable != null) {
            floatArrayOf(
                reorderable.translateDelegate.getTranslationX(INDEX_REORDER_BOUNCE_OFFSET).value,
                reorderable.translateDelegate.getTranslationY(INDEX_REORDER_BOUNCE_OFFSET).value,
            )
        } else {
            floatArrayOf(view.translationX, view.translationY)
        }
    }
}
