package app.lawnchair.areslauncher

import android.content.Context
import android.os.UserHandle
import android.util.Log
import com.android.launcher3.Launcher
import com.android.launcher3.LauncherAppState
import com.android.launcher3.Utilities
import com.android.launcher3.util.Executors
import com.android.launcher3.model.CacheDataUpdatedTask
import com.android.launcher3.model.data.FolderInfo
import com.android.launcher3.model.data.ItemInfo
import app.lawnchair.preferences2.PreferenceManager2

/**
 * Regenerates the HOME icons as soon as the icon state changes, instead of ~10s later.
 *
 * ## The defect this is for (ledger row 77)
 *
 * Owner: *"I switched from light theme to dark theme, but it did take seconds for the icons to
 * update. about 10 seconds"*. For three iterations that was chased as a model-reload cost, on the
 * emulator, and it is not. Measured on the Pixel 2026-09-04 with a 12-sample screen timeline:
 *
 * ```
 *   t = 1.5s   nothing has changed yet
 *   t = 3.1s   the switch has visually LANDED -- 191 of 400 cells, matching the 2.8s loader
 *   3.1-12.0s  completely stable
 *   t = 12-13.8s   a further 12 cells change
 * ```
 *
 * Those last 12 cells are one icon column across four consecutive grid rows, going `97,0,51`
 * dark-magenta to `255,239,242` light-pink — themed icons, regenerating ten seconds after
 * everything else. The bulk of the switch was never slow; a handful of stragglers were, and
 * stragglers are what gets noticed.
 *
 * ## Why they straggle
 *
 * The icon cache serves the STALE DISK entry immediately and revalidates it lazily ~12s later.
 * That is the same mechanism the icon-pack pill already had to work around
 * (`AresEditCarousel.commitIconPack`, owner-measured 2026-08-31: "the same tiles re-bind twice,
 * old bitmap then new ~12s on"), and the fix there is the fix here — delete the disk entries so the
 * next read has to regenerate. `reloadIcons` does NOT do this; it clears the memory cache only.
 *
 * ## Scope, and why it is not "evict everything"
 *
 * Only the packages actually on the home grid, folder contents included. The ~435 packages that are
 * NOT on the grid keep the lazy path deliberately: the Pixel has ~459 apps and evicting all of them
 * would trade a 10s straggle for a much longer regeneration, which is the opposite of the point.
 *
 * Note the scoping is by PACKAGE, not by surface: `removeIconsForPkg` deletes a package-scoped disk
 * row shared by every surface, and `CacheDataUpdatedTask` also refreshes those packages' drawer
 * `AppInfo`s. So for the ~24 packages on the grid the drawer is refreshed too. That is wanted (it is
 * row 61's direction) but it is not "the drawer is excluded".
 *
 * ## STATUS: measured to work on the Pixel, 3 runs per arm, awaiting the owner's feel-gate
 *
 * Late settle = grid cells that change between t=5s and t=20s after the switch. That number IS the
 * defect. Natural alternating dark<->light fixture, one build, prop flipped between arms:
 *
 * ```
 *   lazy (debug.ares.eager_icon_refresh=0)   13  13  13
 *   eager (default)                            0   0   0
 * ```
 *
 * Total re-theme is unchanged (243/241/241 vs 263/246/246 cells against the pre-switch screen), so
 * the eager arm is not reaching zero by skipping work. The fix reports "regenerated 24 home
 * packages" ~4s after the switch, ahead of the ~12s lazy revalidation it replaces.
 *
 * ## Two fixtures that were WRONG, recorded so they are not retried
 *
 *  - **Alternating without a working fix.** The first Pixel A/B (13,10 vs 3,13) compared the control
 *    against a fix that never executed -- `enqueueModelUpdateTask` silently discards the task when
 *    the model is not loaded. Both arms were the control.
 *  - **Wiping `app_icons.db` per run.** Intended to guarantee staleness; it does the opposite. The
 *    row is keyed by component with the state in `freshnessId`, so deleting it leaves NOTHING to
 *    serve stale and the icon is generated eagerly by the ordinary path. Measured: the control's
 *    late settle collapsed to 13,0,0 -- the fixture removed the defect it was built to expose.
 *
 * Gated on the icon state ACTUALLY changing — [AresIconTint.stateFragment], which folds in the night
 * bit. NOTE it does open on the FIRST launch after an install or a clear-data, when there is no
 * stored fragment to compare against -- a one-time regeneration of the home packages, not the
 * steady-state cost.
 */
object AresThemeIconRefresh {

    private const val TAG = "AresThemeIconRefresh"

    /**
     * ON by default. `setprop debug.ares.eager_icon_refresh 0` restores the old lazy behaviour,
     * which is what the control arm of the A/B above uses -- keeping the escape hatch means the
     * comparison stays reproducible from one build instead of needing a revert.
     */
    private const val PROP = "debug.ares.eager_icon_refresh"

    private const val STORE = "ares_icon_state"
    private const val KEY_LAST = "last_state_fragment"

    // Cached: this is read from `finishBindingItems`, which runs on EVERY model bind (a fold
    // triggers one), and `getSystemProperty` is an uncached Class.forName + reflective invoke.
    // `AresIconTint` caches its own `debug.ares.*` reads the same way and for the same reason.
    private val enabledCached: Boolean by lazy { Utilities.getSystemProperty(PROP, "") != "0" }

    @JvmStatic
    fun enabled(): Boolean = enabledCached

    /**
     * Called from `finishBindingItems` -- NOT `onCreate`, which is too early:
     * `enqueueModelUpdateTask` silently discards the task while `isModelLoaded()` is false.
     *
     * The comparison itself is a `SharedPreferences` string equality, but reaching it costs a
     * DataStore `firstBlocking` inside `AresIconTint.stateFragment`, so this is not free -- see the
     * note on [enabledCached]. The regeneration is enqueued as a model task and runs on the loader
     * thread.
     */
    @JvmStatic
    fun refreshIfIconStateChanged(launcher: Launcher) {
        if (!enabled()) return
        val now = runCatching {
            AresIconTint.stateFragment(launcher, PreferenceManager2.getInstance(launcher))
        }.getOrElse {
            Log.w(TAG, "could not read the icon state, standing down", it)
            return
        }
        val store = launcher.getSharedPreferences(STORE, Context.MODE_PRIVATE)
        val last = store.getString(KEY_LAST, null)
        if (last == now) {
            // Logged on the DECLINE branch too. A refresh that has quietly stopped engaging is
            // otherwise indistinguishable in a log from a launch where the state genuinely matched,
            // and that is how the mid-fold guard stayed broken for weeks (ledger row 69a).
            Log.i(TAG, "icon state unchanged, nothing to regenerate ($now)")
            return
        }
        store.edit().putString(KEY_LAST, now).apply()
        Log.i(TAG, "icon state changed: $last -> $now")

        val iconCache = LauncherAppState.getInstance(launcher).iconCache
        // WAIT for the model rather than assuming a callback implies it. `enqueueModelUpdateTask`
        // silently returns without running the task when `isModelLoaded()` is false, so calling it
        // too early is indistinguishable from a launch where nothing needed regenerating -- measured
        // 2026-09-04, that swallowed the fix twice: first from `onCreate`, then from
        // `finishBindingItems`, which still runs BEFORE `LoaderTask` commits the model.
        awaitModelThenRun(launcher, attempt = 0) { enqueue(launcher, iconCache) }
    }

    /**
     * Retries on the model executor until the model is loaded, bounded and LOUD on give-up.
     *
     * Bounded because an unbounded retry against a model that never loads is a leak; loud because a
     * silent give-up is the exact failure this whole routine already hit twice.
     */
    private fun awaitModelThenRun(launcher: Launcher, attempt: Int, body: () -> Unit) {
        if (launcher.isDestroyed) {
            // The one abort branch that used to be silent. The state fragment is already persisted
            // by this point, so a launch that dies in this window leaves the store saying the work
            // was done -- the next launch then matches and stands down, and row 77's straggle comes
            // back permanently for that state pair, logging exactly like a healthy no-op. Say so.
            Log.w(TAG, "activity destroyed before the model loaded; icons stay lazy this launch")
            return
        }
        if (launcher.model.isModelLoaded()) {
            body()
            return
        }
        if (attempt >= MAX_WAIT_ATTEMPTS) {
            Log.w(TAG, "model still not loaded after ${MAX_WAIT_ATTEMPTS * WAIT_STEP_MS}ms; icons stay lazy")
            return
        }
        Executors.MODEL_EXECUTOR.handler.postDelayed(
            { awaitModelThenRun(launcher, attempt + 1, body) },
            WAIT_STEP_MS,
        )
    }

    private fun enqueue(launcher: Launcher, iconCache: com.android.launcher3.icons.IconCache) {
        launcher.model.enqueueModelUpdateTask { taskController, dataModel, apps ->
            val byUser = HashMap<UserHandle, HashSet<String?>>()
            fun add(info: ItemInfo) {
                // APPLICATIONS only. `CacheDataUpdatedTask` OP_CACHE_UPDATE re-reads exactly the
                // items whose itemType is ITEM_TYPE_APPLICATION, so collecting anything else --
                // a deep shortcut, say -- deletes its disk entry and then regenerates NOTHING,
                // leaving it worse off than if this had never run: it falls back to the same lazy
                // path, minus the cached bitmap it used to have.
                if (info.itemType != com.android.launcher3.LauncherSettings.Favorites.ITEM_TYPE_APPLICATION) return
                val pkg = info.targetComponent?.packageName ?: return
                byUser.getOrPut(info.user) { HashSet() }.add(pkg)
            }
            synchronized(dataModel) {
                dataModel.itemsIdMap.forEach { info ->
                    if (info is FolderInfo) info.getContents().forEach(::add) else add(info)
                }
            }
            if (byUser.isEmpty()) {
                Log.w(TAG, "no home items found, nothing regenerated")
                return@enqueueModelUpdateTask
            }
            var n = 0
            for ((user, pkgs) in byUser) {
                // Delete first, THEN re-read. The order is the whole fix: CacheDataUpdatedTask calls
                // getTitleAndIcon, which would otherwise be served the stale disk entry and change
                // nothing -- measured, that exact task moved 0 of 576 cells when run without the
                // eviction in front of it.
                pkgs.forEach { p -> if (p != null) iconCache.removeIconsForPkg(p, user) }
                n += pkgs.size
                CacheDataUpdatedTask(CacheDataUpdatedTask.OP_CACHE_UPDATE, user, pkgs)
                    .execute(taskController, dataModel, apps)
            }
            Log.i(TAG, "regenerated $n home packages across ${byUser.size} user(s)")
        }
    }

    /** ~6s of patience in 250ms steps: comfortably past a cold load, nowhere near a leak. */
    private const val WAIT_STEP_MS = 250L
    private const val MAX_WAIT_ATTEMPTS = 24
}
