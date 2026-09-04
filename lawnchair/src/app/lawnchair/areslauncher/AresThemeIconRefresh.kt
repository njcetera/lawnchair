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
 * Only the packages actually on the home grid, folder contents included. The drawer keeps the lazy
 * path deliberately: the Pixel has ~459 apps and evicting all of them would trade a 10s straggle
 * for a much longer regeneration, which is the opposite of the point.
 *
 * ## STATUS: UNPROVEN, and the A/B that was meant to prove it is INVALID
 *
 * Measured on the Pixel 2026-09-04, two runs per arm, counting cells that settle between t=5s and
 * t=18s (the window the lazy revalidation lands in):
 *
 * ```
 *                run 1   run 2
 *   lazy (control)   13      10
 *   eager (fix)       3      13
 * ```
 *
 * The one good run did not replicate, and the DESIGN is why: alternating dark<->light repeatedly
 * warms the disk cache for BOTH states, so after the first pass neither state is stale and the
 * defect being fixed is no longer present to fix. A valid fixture has to make exactly one state
 * stale per run -- delete `databases/app_icons.db`, let the launcher populate for the current
 * state, THEN switch -- which is the same "delete the DB between arms" rule the icon-wash A/B
 * already learned. Until that fixture exists, this stays gated off and must not be described as a
 * fix.
 *
 * The MECHANISM is still the best explanation on the table: the straggling cells are themed icons,
 * and eviction is exactly what the icon-pack pill needed for the same lazy revalidation.
 *
 * Gated on the icon state ACTUALLY changing — [AresIconTint.stateFragment], which folds in the night
 * bit — so an ordinary cold start does not pay for a regeneration nothing asked for.
 */
object AresThemeIconRefresh {

    private const val TAG = "AresThemeIconRefresh"

    /** `setprop debug.ares.eager_icon_refresh 1` to arm it. Off = the shipped lazy behaviour. */
    private const val PROP = "debug.ares.eager_icon_refresh"

    private const val STORE = "ares_icon_state"
    private const val KEY_LAST = "last_state_fragment"

    @JvmStatic
    fun enabled(): Boolean = Utilities.getSystemProperty(PROP, "") == "1"

    /**
     * Called from `onCreate`. Cheap and non-blocking: the comparison is a string equality against a
     * `SharedPreferences` value, and the work is enqueued as a model task so it runs on the loader
     * thread once the model is available.
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
        if (launcher.isDestroyed) return
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
