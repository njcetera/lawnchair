package app.lawnchair.arestests

import android.content.ComponentName
import android.content.ContentResolver
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.PointF
import android.graphics.RectF
import android.net.Uri
import android.os.SystemClock
import android.util.Log
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until

/**
 * Page object for AresLauncher's masonry home grid.
 *
 * TAPL's `Workspace` is unusable here: it drives a paged `CellLayout` and addresses items by
 * `(cellX, cellY, page)`. Ares's home is a `RecyclerView` with `AresMasonryLayoutManager` where
 * position is derived from `rank` alone, so a tile's address is an **index**, and its geometry has
 * to be read from the live view rather than computed from a cell grid.
 *
 * TAPL's `AppIcon.waitForLongPressConfirmation()` is likewise unusable -- it waits for
 * `popup_container`, and a first long-press on the Ares home grid raises no popup at all, it enters
 * edit mode. [enterEditModeAndDrag] waits for edit mode instead.
 *
 * What IS reused is the substrate: the same test-channel provider, the same
 * `UiAutomation.injectInputEvent` gesture mechanism ([AresGestures]).
 */
class AresLauncherDriver {

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val device: UiDevice = UiDevice.getInstance(instrumentation)
    private val context = instrumentation.context

    val launcherPackage: String = resolveLauncherPackage()
    private val providerUri: Uri = Uri.Builder()
        .scheme(ContentResolver.SCHEME_CONTENT)
        .authority("$launcherPackage.TestInfo")
        .build()

    /**
     * The package that is actually the current home app.
     *
     * NOT `PackageManager.resolveActivity(ACTION_MAIN/CATEGORY_HOME)` and not
     * `UiDevice.getLauncherPackageName()`, both of which resolved to **`com.android.settings`** on
     * emulator-5554 -- `com.android.settings/.FallbackHome` carries the HOME category and wins the
     * match from a test process. Measured, not guessed; it is what the first out-of-process run
     * failed on.
     *
     * `cmd package resolve-activity` asks the system the question the system itself answers when
     * HOME is pressed, so it returns the real one.
     */
    private fun resolveLauncherPackage(): String {
        val out = device.executeShellCommand(
            "cmd package resolve-activity --brief -a android.intent.action.MAIN " +
                "-c android.intent.category.HOME",
        )
        val component = out.lineSequence()
            .map { it.trim() }
            .firstOrNull { it.contains('/') && !it.startsWith("priority") }
        val pkg = component?.substringBefore('/')
        check(!pkg.isNullOrEmpty() && pkg != "com.android.settings") {
            "could not resolve the home package; `cmd package resolve-activity` said:\n$out"
        }
        return pkg
    }

    // ------------------------------------------------------------------ channel

    /**
     * Waits for the launcher's `TestInformationProvider` to answer, permissioning this APK first.
     *
     * What actually enables the provider is the LAUNCHER ITSELF: `pm enable` from here fails --
     * measured as `SecurityException: Shell cannot change component state` (see `e132886f6d`) --
     * and the launcher self-enables the component at startup when it is `FLAG_DEBUGGABLE`. So the
     * enable block below is a best-effort no-op kept from TAPL, and what makes this method work
     * is the `pressHome()` (starting the launcher, which self-enables) plus the wait. The one
     * step that IS load-bearing from this side: `WRITE_SECURE_SETTINGS` is a `development`
     * permission, so `pm grant` can hand it to this test APK -- which declares it in its own
     * manifest -- and without it every provider call is refused.
     */
    fun openTestChannel() {
        device.executeShellCommand(
            "pm grant ${context.packageName} android.permission.WRITE_SECURE_SETTINGS",
        )
        val pi = context.packageManager.resolveContentProvider(
            "$launcherPackage.TestInfo",
            PackageManager.MATCH_ALL or PackageManager.MATCH_DISABLED_COMPONENTS,
        ) ?: error("no TestInfo provider for $launcherPackage")
        val cn = ComponentName(pi.packageName, pi.name)
        if (context.packageManager.getComponentEnabledSetting(cn) !=
            PackageManager.COMPONENT_ENABLED_STATE_ENABLED
        ) {
            val pidCmd = "pidof ${pi.packageName}"
            val before = device.executeShellCommand(pidCmd).trim()
            // `Context.getUserId()` is hidden API; ask the shell, which is where the enable runs.
            val userId = device.executeShellCommand("am get-current-user").trim()
            device.executeShellCommand(
                "pm enable --user $userId ${cn.flattenToString()}",
            )
            // Enabling a component restarts the app -- but only if it was running. Out-of-process
            // the launcher may not be up yet at all (`pidof` empty), and waiting unconditionally
            // for a pid CHANGE then never terminates. Measured: that is a 30s timeout, not a hang
            // with a cause you can see.
            if (before.isNotEmpty()) {
                waitFor("launcher restart after enabling the test provider") {
                    val now = device.executeShellCommand(pidCmd).trim()
                    now.isNotEmpty() && now != before
                }
            }
        }
        device.pressHome()
        waitFor("test provider to answer") {
            context.contentResolver.acquireContentProviderClient(providerUri)
                ?.also { it.close() } != null
        }
    }

    private fun call(method: String, arg: String? = null) =
        context.contentResolver.acquireContentProviderClient(providerUri).use {
            requireNotNull(it) { "test provider not available" }.call(method, arg, null)
        }

    /** Titles of the home grid's items, in visual order. */
    fun homeOrder(): List<String> =
        call("ares-home-order")?.getStringArray("response")?.toList() ?: emptyList()

    fun isEditMode(): Boolean = call("ares-edit-mode")?.getBoolean("response") ?: false

    /**
     * Home grid column count, or null when the channel cannot answer.
     *
     * NULLABLE ON PURPOSE. Every other reader here defaults a missing answer to a number, and a
     * caller comparing that default against a range reads "the launcher was not there" as a PASS.
     * A shape test on an unfamiliar device is exactly where that happens, so this makes the
     * caller decide -- see AresDeviceShapeTest, which turns a null into a SKIP.
     */
    fun homeColumnsOrNull(): Int? =
        call("ares-home-columns")?.getString("response")
            ?.substringAfter("columns=", "")
            ?.trim()
            ?.toIntOrNull()

    /**
     * Total AresInvariants violations, or null when the channel cannot answer.
     *
     * The response is `total=N|<id>=<count>|...`; only the total is needed here. Nullable for the
     * same reason as [homeColumnsOrNull]: a missing answer must not read as zero violations.
     */
    fun invariantTotalOrNull(): Long? =
        call("ares-invariants")?.getString("response")
            ?.substringAfter("total=", "")
            ?.substringBefore("|")
            ?.trim()
            ?.toLongOrNull()

    /** Monotonic S4 decline-branch counter; -1 when the channel cannot answer. */
    fun folderDropDeclinedCount(): Long =
        call("ares-folder-drop-stats")?.getLong("response") ?: -1L

    fun tiles(): List<Tile> =
        (call("ares-tile-metrics")?.getStringArray("response") ?: emptyArray())
            .map { Tile.parse(it) }
            .sortedBy { it.position }

    /** One home tile, as the launcher itself sees it. See `AresTestInfo.REQUEST_TILE_METRICS`. */
    data class Tile(
        val position: Int,
        val title: String,
        /** Layout box in the RecyclerView's own coordinates. */
        val box: RectF,
        /** The holder container's translation: orbit + reflow + follow (+ ItemTouchHelper's dX). */
        val containerTranslation: PointF,
        /** The reflow spring's contribution alone. */
        val reflow: PointF,
        /** The item view's `INDEX_REORDER_BOUNCE_OFFSET` channel: the label lift. */
        val itemTranslation: PointF,
        /** `getLocationOnScreen` on the container -- ground truth, scale and all. */
        val containerOnScreen: PointF,
        /** `getLocationOnScreen` on the item view inside it. */
        val itemOnScreen: PointF,
        val size: PointF,
        val scale: Float,
        /** `LauncherSettings.Favorites.ITEM_TYPE_*`; 4 is a widget. */
        val itemType: Int,
        val span: PointF,
    ) {
        val isWidget get() = itemType == 4

        /** Where a finger has to land to hit this tile's middle. */
        fun screenCenter() = PointF(
            containerOnScreen.x + size.x * scale / 2f,
            containerOnScreen.y + size.y * scale / 2f,
        )

        companion object {
            fun parse(line: String): Tile {
                val f = line.split('|')
                fun pt(s: String): PointF {
                    val (a, b) = s.split(',')
                    return PointF(a.toFloat(), b.toFloat())
                }
                val b = f[2].split(',').map { it.toFloat() }
                return Tile(
                    position = f[0].toInt(),
                    title = f[1],
                    box = RectF(b[0], b[1], b[2], b[3]),
                    containerTranslation = pt(f[3]),
                    reflow = pt(f[4]),
                    itemTranslation = pt(f[5]),
                    containerOnScreen = pt(f[6]),
                    itemOnScreen = pt(f[7]),
                    size = pt(f[8]),
                    scale = f[9].toFloat(),
                    itemType = f[10].toInt(),
                    span = pt(f[11]),
                )
            }
        }
    }

    // ------------------------------------------------------------------ actions

    fun goHome() {
        device.pressHome()
        device.wait(Until.hasObject(By.pkg(launcherPackage).depth(0)), 10_000)
        waitFor("home grid to have items") { homeOrder().isNotEmpty() }
    }

    /**
     * Enters home edit mode with a bare long-press and NO drag, then confirms it armed.
     *
     * `travelMs = 0` is the whole point: a long-press with no travel enters edit mode in this fork
     * (stock Launcher3 would start a drag — see `longPressEntersEditModeWithoutStartingADrag`).
     * The synthetic long-press is the flakiest gesture in this suite, so it retries: on this AVD it
     * armed on the second attempt in a hand-run spike. Each retry re-reads the tile centre because
     * entering edit mode scales the grid to 0.92 and every tile moves.
     *
     * Throws if edit mode will not arm — a precondition that cannot be met must be loud.
     */
    fun enterEditModeNoDrag(iconIndex: Int = 0) {
        repeat(4) {
            if (isEditMode()) return
            val tile = iconTiles().getOrNull(iconIndex) ?: iconTiles().firstOrNull()
                ?: error("no icon tiles on the home grid to long-press")
            AresGestures.pressHoldDragRelease(
                start = tile.screenCenter(),
                holdMs = 800,
                travelMs = 0,
                target = { iconTiles().getOrNull(iconIndex)?.screenCenter() ?: tile.screenCenter() },
            )
            SystemClock.sleep(400)
        }
        check(isEditMode()) { "edit mode did not arm after 4 long-press attempts" }
    }

    fun exitEditMode() {
        // BACK peels one layer at a time: an open folder or popup consumes the first press and
        // leaves edit mode standing, which is exactly the state a failed folder-drag attempt
        // leaves behind. One press followed by a 30s wait therefore hung an entire run. Press,
        // give the dismissal a beat, and press again -- bounded, and each press is what a person
        // would do.
        repeat(4) {
            if (!isEditMode()) return
            device.pressBack()
            SystemClock.sleep(600)
        }
        waitFor("edit mode to end") { !isEditMode() }
    }

    /**
     * The home grid's three child counts. See `AresTestInfo.REQUEST_CHILD_CENSUS`.
     *
     * [viewGroup] counts hidden children, [layoutManager] does not, so `viewGroup - layoutManager`
     * is the number of views RecyclerView is holding attached but invisible to layout — a ghost
     * count. [adapter] is what the model says should be there; it does **not** equal either of the
     * others in normal operation, because only what fits on screen is attached.
     */
    data class Census(val viewGroup: Int, val layoutManager: Int, val adapter: Int) {
        override fun toString() = "$viewGroup|$layoutManager|$adapter"
    }

    fun childCensus(): Census {
        val raw = call("ares-child-census")?.getString("response") ?: "0|0|0"
        val p = raw.split("|")
        return Census(
            p.getOrNull(0)?.toIntOrNull() ?: 0,
            p.getOrNull(1)?.toIntOrNull() ?: 0,
            p.getOrNull(2)?.toIntOrNull() ?: 0,
        )
    }

    /** Removes the first `"widget"` or `"icon"` on the grid via the × badge's own code path. */
    fun removeFirstItem(kind: String): Int =
        call("ares-remove-item", kind)?.getInt("response") ?: -1

    /** Opens the first folder on the grid, through `FolderIcon.performClick`. */
    fun openFolder(): Boolean = call("ares-open-folder")?.getBoolean("response") ?: false

    /** SPIKE (§25): create + open a one-item folder; returns what happened. */
    fun liveCreateSpike(): String = call("ares-live-create-spike")?.getString("response") ?: "null"

    /** Attaches or detaches folder edit mode on the open folder. */
    fun setFolderEdit(on: Boolean): Boolean =
        call("ares-folder-edit", if (on) "on" else "off")?.getBoolean("response") ?: false

    /**
     * One icon inside the open folder.
     *
     * [stateLift] is what `AresEditLabel` believes it wrote; [actualTy] is the view's real
     * translation channel. **Their disagreement is the defect** — see `AresFolderLiftTest`.
     */
    data class FolderIcon(
        val title: String,
        val stateLift: Float,
        val actualTy: Float,
        val screenX: Int,
        val screenY: Int,
        val width: Int,
        val height: Int,
    ) {
        fun center() = PointF(screenX + width / 2f, screenY + height / 2f)
        override fun toString() = "$title(lift=$stateLift actual=$actualTy at=$screenX,$screenY)"
    }

    /**
     * Whether the §C4 drop slot is currently in the adapter. The C4 observable.
     *
     * The slot is a real adapter entry carrying `DROP_SLOT_ID` = `Int.MIN_VALUE`, so it shows up in
     * `ares-home-order` like anything else. That is the point of the design: the gap is expressed
     * in the ONE position model the grid has -- an ordered sequence -- rather than drawn as a second
     * parallel layout that agrees with the packer only by luck.
     */
    fun hasDropSlot(): Boolean = homeOrder().any { it.startsWith("${Int.MIN_VALUE}/") }

    /** Number of `DragView`s in the DragLayer. The D4 observable: a bare hold must produce none. */
    fun dragViewCount(): Int = folderMetrics()
        .firstOrNull { it.startsWith("folder|") }
        ?.split("|")?.getOrNull(5)?.toIntOrNull() ?: -1

    /** `nameTop` of the open folder, or -1. The D9 observable. */
    fun folderNameTop(): Int = folderMetrics()
        .firstOrNull { it.startsWith("folder|") }
        ?.split("|")?.getOrNull(3)?.split(",")?.getOrNull(0)?.toIntOrNull() ?: -1

    private fun folderMetrics(): List<String> =
        (call("ares-folder-metrics")?.getStringArray("response") ?: emptyArray()).toList()

    fun folderIcons(): List<FolderIcon> = folderMetrics()
        .filter { it.startsWith("icon|") }
        .map { it.split("|") }
        .map {
            val at = (it.getOrNull(4) ?: "0,0").split(",")
            val wh = (it.getOrNull(5) ?: "0,0").split(",")
            FolderIcon(
                it.getOrElse(1) { "?" },
                it.getOrNull(2)?.toFloatOrNull() ?: 0f,
                it.getOrNull(3)?.toFloatOrNull() ?: 0f,
                at.getOrNull(0)?.toIntOrNull() ?: -1,
                at.getOrNull(1)?.toIntOrNull() ?: -1,
                wh.getOrNull(0)?.toIntOrNull() ?: 0,
                wh.getOrNull(1)?.toIntOrNull() ?: 0,
            )
        }

    /**
     * Sets all three animation scales.
     *
     * A test that touches this **must** put it back — `AresEditWiggle.start()` and
     * `AresEditMotion.displaceTo()` both early-out when animators are disabled, so a scale left at 0
     * silently deletes the float and the reflow spring for everything that runs afterwards.
     */
    fun setAnimatorScale(scale: Int) {
        for (key in listOf(
            "window_animation_scale",
            "transition_animation_scale",
            "animator_duration_scale",
        )) {
            shell("settings put global $key $scale")
        }
    }

    /**
     * Scrolls the grid back to offset 0 with plain downward drags.
     *
     * Gesture tests assume tile coordinates are ON SCREEN, and `tiles()` reports them wherever the
     * grid happens to be scrolled -- a prior test that scrolled to the end left the next test
     * aiming at `y = -247`, which `injectInputEvent` refuses, and every later gesture in the batch
     * then inherited the stuck stream. Scroll state is shared mutable fixture, same as the
     * database; reset it, do not hope.
     */
    fun scrollGridToTop() {
        var guard = 0
        while (surfaceState().scrollOffset > 0 && guard++ < 10) {
            AresGestures.dragPath(
                listOf(android.graphics.PointF(540f, 700f), android.graphics.PointF(540f, 1900f)),
                legMs = 300,
            )
            Thread.sleep(500)
        }
    }

    /** `state|inTransition|optionsPopup|scrollOffset`. See `AresTestInfo.REQUEST_SURFACE_STATE`. */
    data class SurfaceState(
        val state: String,
        val inTransition: Boolean,
        val optionsPopupOpen: Boolean,
        val scrollOffset: Int,
    )

    fun surfaceState(): SurfaceState {
        val p = (call("ares-surface-state")?.getString("response") ?: "?|false|false|-1").split("|")
        return SurfaceState(
            p.getOrElse(0) { "?" }.uppercase(),
            p.getOrNull(1)?.toBoolean() ?: false,
            p.getOrNull(2)?.toBoolean() ?: false,
            p.getOrNull(3)?.toIntOrNull() ?: -1,
        )
    }

    /**
     * The two unfolded panes' real on-screen geometry (see AresTestInfo.REQUEST_PANE_ALIGN). Every
     * field is a screen coordinate or a live padding read on the launcher UI thread — nothing here
     * trusts the padding formula. `null` when the channel could not answer at all. When the launcher
     * is not in two-panel posture the pane fields come back as -1 (the pane view is absent), which
     * [AresPaneAlignTest] turns into a SKIP rather than a pass.
     */
    fun paneAlign(): PaneAlign? {
        val raw = call("ares-pane-align")?.getString("response") ?: return null
        fun field(name: String): Int =
            Regex("$name=(-?\\d+)").find(raw)?.groupValues?.get(1)?.toIntOrNull() ?: -1
        return PaneAlign(
            homeTop = field("homeTop"),
            homePad = field("homePad"),
            homeChild = field("homeChild"),
            paneTop = field("paneTop"),
            rvPad = field("rvPad"),
            paneChild = field("paneChild"),
            insetsTop = field("insetsTop"),
            wsPadTop = field("wsPadTop"),
            homeListPad = field("homeListPad"),
            recon = field("recon"),
            raw = raw,
        )
    }

    data class PaneAlign(
        val homeTop: Int,
        val homePad: Int,
        val homeChild: Int,
        val paneTop: Int,
        val rvPad: Int,
        val paneChild: Int,
        val insetsTop: Int,
        val wsPadTop: Int,
        val homeListPad: Int,
        val recon: Int,
        val raw: String,
    ) {
        /** Signed gap between the app list's first row and the home grid's first row, in px. */
        val delta: Int get() = paneChild - homeChild

        /** Both panes present and laid out — i.e. the launcher is in two-panel (unfolded) posture. */
        val bothPanesLaidOut: Boolean get() = homeChild > 0 && paneChild > 0
    }

    /**
     * Drives a fold→unfold on the emulator and recovers the launcher, so a caller can check an
     * invariant SURVIVES the posture change. `cmd device_state state N` re-arms the keyguard on the
     * AVD and the fold triggers a full model rebind (both documented traps), so the recovery here is
     * load-bearing: wake, dismiss keyguard, HOME, then wait for the home grid to rebind before
     * returning. Returns false if the device has no foldable states to drive.
     */
    fun foldCycleAndRecover(): Boolean {
        val states = shell("cmd device_state print-states")
        if (!states.contains("OPENED") || !states.contains("CLOSED")) return false
        for (state in listOf(0, 2)) {
            shell("cmd device_state state $state")
            device.pressKeyCode(android.view.KeyEvent.KEYCODE_WAKEUP)
            shell("wm dismiss-keyguard")
            goHome()
            waitFor("home grid to rebind after fold state $state") { homeOrder().isNotEmpty() }
        }
        return true
    }

    /** True when BOTH app-list edge glows are finished (no held overscroll pull). */
    fun overscrollFinished(): Boolean {
        val p = (call("ares-overscroll-state")?.getString("response") ?: "true|true").split("|")
        return p.all { it.toBoolean() }
    }

    fun pressBack() = device.pressBack()

    private fun shell(cmd: String): String = device.executeShellCommand(cmd)

    /**
     * Fails loudly if the two-widget fixture is not present.
     *
     * NOT self-healing, deliberately, after trying. Tests in this suite mutate the grid and one of
     * them -- AresGhostWidgetTest -- has to DELETE a widget to test widget deletion, so class order
     * (alphabetical) decided whether a later test found its fixture. Restoring the rows in-process
     * does not work: `run-as <pkg> ls databases` succeeds through
     * `UiAutomation.executeShellCommand`, but `run-as <pkg> sqlite3 <db> "<sql>"` returns empty
     * for every quoting variant tried (bare, single, double, wrapped in `sh -c` both ways) while
     * the identical command works over `adb shell`. So re-seeding belongs to the runner, which has
     * a real shell: `design/scripts/run-ares-tests.sh` seeds before every class.
     *
     * A precondition that cannot be met must be LOUDER than a failure, never quieter -- so this
     * throws with the command to run rather than skipping, and never reports a pass it did not
     * earn.
     */
    fun requireWidgetFixture() {
        var widgets = homeOrder().count { it.substringAfter("/") == "type4" }
        if (widgets < 2) {
            // Seen once, right after the widget-swap stress class: the db held both rows but the
            // fresh bind surfaced only one, and an ordinary force-stop + relaunch bound both again
            // from the very same bytes. A precondition may retry through a restart; an assertion
            // never retries at all.
            Log.w("AresSpike", "widget fixture short ($widgets); restarting launcher once")
            restartLauncher()
            widgets = homeOrder().count { it.substringAfter("/") == "type4" }
        }
        check(widgets >= 2) {
            "FIXTURE MISSING: this test needs 2 widgets on the grid, found $widgets. " +
                "Re-seed with: design/scripts/seed-widget-fixture.sh emulator-5554 " +
                "(or run the whole suite via design/scripts/run-ares-tests.sh, which seeds " +
                "before every class -- AresGhostWidgetTest deletes a widget by design)."
        }
    }

    /**
     * Force-stops the launcher and waits for the grid to come back.
     *
     * Needed by anything measuring attached children: a view that RecyclerView has left attached
     * but hidden survives every ordinary interaction and is only cleared by activity recreation, so
     * without this a leak from one test is read as a leak in the next.
     */
    fun restartLauncher() {
        device.executeShellCommand("am force-stop $launcherPackage")
        device.pressHome()
        device.wait(Until.hasObject(By.pkg(launcherPackage).depth(0)), 10_000)
        // Both conditions: the adapter fills before the children attach, and a census taken in
        // between reads `0|0|32` -- which is not a ghost, but would be read as one.
        waitFor("home grid to rebind after restart") {
            homeOrder().isNotEmpty() && childCensus().viewGroup > 0
        }
    }

    /**
     * The whole Ares drag, as ONE gesture: long-press to enter edit mode, then travel without
     * lifting.
     *
     * **A bare long-press does not start a drag in this fork.** `AresHomeReorder`'s callback
     * returns false from `isLongPressDragEnabled()`; `AresHomeListView`'s touch listener calls
     * `itemTouchHelper.startDrag(holder)` itself, only once the same gesture passes the touch slop.
     * A test that presses and releases moves nothing.
     *
     * [holdMs] must exceed the system long-press (~400ms) for the mode to be entered at all.
     */
    fun enterEditModeAndDrag(
        fromIndex: Int,
        toIndex: Int,
        holdMs: Long = 700,
        travelMs: Long = 700,
        hangMs: Long = 0,
        onDragStep: (Int) -> Unit = {},
        onHangStep: (Int) -> Unit = {},
        // Second leg after the hang, by adapter index, resolved late like toIndex. For the
        // dwell-then-leave shapes; see AresGestures.pressHoldDragRelease.
        secondTargetIndex: Int = -1,
        secondTravelMs: Long = 0,
    ) {
        val start = tiles().first { it.position == fromIndex }.screenCenter()
        AresGestures.pressHoldDragRelease(
            start = start,
            holdMs = holdMs,
            travelMs = travelMs,
            // Read AFTER the hold: entering edit mode scales the grid to 0.92 and every tile moves.
            target = {
                check(isEditMode()) { "long-press did not enter edit mode" }
                val t = tiles().first { it.position == toIndex }
                aimPoint(start, t)
            },
            onStep = { i, _ -> onDragStep(i) },
            hangMs = hangMs,
            onHangStep = onHangStep,
            secondTarget = if (secondTargetIndex < 0) null else ({
                val t = tiles().first { it.position == secondTargetIndex }
                aimPoint(start, t)
            }),
            secondTravelMs = secondTravelMs,
        )
    }

    /**
     * Where to put the finger to make [target] actually swap.
     *
     * **Aiming at the target tile's centre is not enough, and this is the single fact that made the
     * first green-looking run move nothing.** `AresHomeReorder.hasReached` projects the drag centre
     * onto the line between the two tiles' layout slots and requires
     * `SWAP_TRAVEL_FRACTION = 1.10` of that span -- a deliberate 10% overshoot, so that a tile's
     * leading half stays un-reflowed and dwell-to-create-a-folder has something to dwell on.
     * Landing exactly on the centre is 1.00 and is refused.
     *
     * The overshoot must also stay INSIDE the target's bounds, because the icon branch of
     * `chooseDropTarget` first requires the drag centre to be within `v.left..v.right`. So this
     * goes 35% of the target's half-size past the centre, along the direction of travel: past 1.10
     * for adjacent tiles, still comfortably inside the tile.
     */
    private fun aimPoint(from: PointF, target: Tile): PointF {
        val c = target.screenCenter()
        val dx = c.x - from.x
        val dy = c.y - from.y
        val len = kotlin.math.hypot(dx, dy)
        if (len < 1f) return c
        val past = 0.35f * minOf(target.size.x, target.size.y) * target.scale / 2f
        return PointF(c.x + dx / len * past, c.y + dy / len * past)
    }

    /** Tiles that are plain icons, in visual order. `itemType` 0 is an application. */
    fun iconTiles(): List<Tile> = tiles().filter { it.itemType == 0 }

    // ------------------------------------------------------------------ view capture

    /**
     * Drives the `ares-view-capture` channel. Sub-commands `start` / `export` / `reset` / `status`;
     * there is deliberately no `stop` (see `AresViewCapture`).
     */
    fun viewCapture(sub: String): String =
        call("ares-view-capture", sub)?.getString("response") ?: "null"

    /** Toggles a WP folder's inline expansion. `expanded=<bool>|contents=<n>`, or a marker. */
    fun wpExpand(folderId: Int): String =
        call("ares-wp-expand", folderId.toString())?.getString("response") ?: "null"

    /**
     * The id of the first WP folder on the home grid, or null if there is none.
     *
     * Found by PROBING rather than by reading a type, because [homeOrder] reports `id/title` and a
     * folder's title is indistinguishable from an app's. `ares-wp-expand` answers `expanded=…` only
     * for a real WP folder and `no-folder(id)` / `not-wp` otherwise, so the probe is exact.
     *
     * The probe TOGGLES the folder it finds — that is unavoidable through this channel — so it
     * returns the id and leaves the caller to put the folder in whatever state it wants.
     */
    fun findWpFolderId(): Int? {
        for (entry in homeOrder()) {
            val id = entry.substringBefore('/').toIntOrNull() ?: continue
            if (wpExpand(id).startsWith("expanded=")) return id
        }
        return null
    }

    /**
     * Reads the exported proto off the device, or null if it cannot be read.
     *
     * NOT a plain `File(path).readBytes()`, and this is the whole reason the method exists. The
     * proto lands in the LAUNCHER's external files dir, `Android/data/app.lawnchair.debug/files/`,
     * and this test APK is a separate application id and therefore a separate UID. Under scoped
     * storage one app cannot read another's external files dir. `adb pull` works on it only because
     * adb runs as shell, which is specially allowlisted — that is not evidence the test can.
     *
     * So it tries the direct read first (cheap, and if a future platform allows it we want to know),
     * then falls back to `UiAutomation.executeShellCommand`, which runs as shell and returns a
     * `ParcelFileDescriptor` — binary-safe, unlike `UiDevice.executeShellCommand`, which hands back
     * a `String` and would corrupt a megabyte of proto. Which path won is logged, because guessing
     * here is exactly what this project's `run-as … sqlite3` trap punishes.
     *
     * Returns null rather than throwing so the caller can report SKIP: a proto that cannot be read
     * is a check that could not run, not a launcher defect.
     */
    fun readCapturedProto(path: String): ByteArray? {
        runCatching { java.io.File(path).readBytes() }
            .onSuccess { if (it.isNotEmpty()) { Log.i("AresViewCap", "proto read directly (${it.size} bytes)"); return it } }
            .onFailure { Log.i("AresViewCap", "direct read refused (${it.javaClass.simpleName}), falling back to shell") }

        return runCatching {
            instrumentation.uiAutomation.executeShellCommand("cat $path").use { pfd ->
                android.os.ParcelFileDescriptor.AutoCloseInputStream(pfd).use { it.readBytes() }
            }
        }.onFailure { Log.w("AresViewCap", "shell read failed", it) }
            .getOrNull()
            ?.takeIf { it.isNotEmpty() }
            ?.also { Log.i("AresViewCap", "proto read via shell (${it.size} bytes)") }
    }

    fun waitFor(what: String, timeoutMs: Long = 30_000, condition: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + timeoutMs
        while (SystemClock.uptimeMillis() < deadline) {
            if (runCatching(condition).getOrDefault(false)) return
            SystemClock.sleep(100)
        }
        throw AssertionError("timed out after ${timeoutMs}ms waiting for $what")
    }
}
