package app.lawnchair.areslauncher

import android.util.Log
import com.android.launcher3.LauncherAppState
import com.android.launcher3.LauncherSettings.Favorites
import com.android.launcher3.model.data.ItemInfo
import com.android.launcher3.util.Executors

/**
 * Detects a PARTIAL home bind: the loader finished, but the grid holds fewer desktop items than the
 * database has desktop rows.
 *
 * ## The defect this exists for
 *
 * Ledger row 89, owner report 2026-09-04: *"what's going on the the home screen? most of my widgets
 * are missing?"* Nothing had been deleted -- the rows were all still in the database, and a relaunch
 * brought every item back. The bind had simply stopped part-way through and nothing noticed, so the
 * owner was looking at a grid that silently disagreed with its own storage.
 *
 * That is the worst shape a defect can take here. There is no crash, no log, no failing test, and
 * the surface looks entirely plausible -- a home screen with fewer icons on it is still a home
 * screen. The only reason it was ever reported is that the owner happened to remember what used to
 * be there. An instrument is the only thing that can see this.
 *
 * ## Why the check is against the DATABASE and not the model
 *
 * Comparing the adapter against `BgDataModel` would be cheaper and would need no disk read, but it
 * cannot see this defect: if the loader itself dropped items, the model is short too and the two
 * agree perfectly. The database is the only reference that is upstream of every stage that could
 * have lost something, which is exactly why a relaunch cured it.
 *
 * ## Why `container == CONTAINER_DESKTOP` on both sides
 *
 * Strategy D flattens every screen's items into one list, so screen id is not a discriminator, but
 * an inline-expanded WP folder puts its CHILDREN in the adapter as well -- and a child carries its
 * folder's id as its container, not [Favorites.CONTAINER_DESKTOP]. Filtering both sides on the
 * container makes an expanded folder invisible to the check instead of a false alarm, without the
 * guard having to know anything about folder expansion.
 *
 * ## Direction
 *
 * Only a SHORTFALL is a violation. An excess is reachable from ordinary transients (an item added
 * to the grid whose write has not landed yet) and is not the failure mode row 89 describes, so
 * counting it would spend the invariant's credibility on noise. `ares-smoke` fails on a non-zero
 * total, and an invariant that cries wolf is one the harness gets taught to ignore.
 */
object AresBindGuard {

    private const val TAG = "AresBindGuard"

    /** The invariant id, as it appears in `ares-invariants` output and in a smoke failure. */
    const val ID = "home-bind-complete"

    /**
     * How many times [checkAfterBind] has RUN in this process. Exposed through `ares-home-bind` as
     * `checks=<n>` and asserted by `ares-smoke`.
     *
     * Adversarial review 2026-09-05, F3: this guard is invoked from `finishBindingItems`, which is
     * queued on the SAME `pendingExecutor` as the deferred bind items -- and a `ViewOnDrawExecutor`
     * that is cancelled DROPS its queue rather than running it (ledger row 92). So the one mechanism
     * that could leave the grid partially bound would also leave this guard un-run, and a guard that
     * never ran reports the same `total=0` as a clean bind. Nothing inside the guard can log its own
     * absence; a counter read from outside can. `checks=0` on a launcher whose grid has items is the
     * F3 blind spot made visible.
     */
    @JvmStatic
    @Volatile
    var checks: Int = 0
        private set

    /**
     * Checks the settled grid against the database. Call from `finishBindingItems`, AFTER the grid
     * is final (`finishSoftRebind`), on the UI thread.
     *
     * The adapter is read here, synchronously, because it is only safe to read on the UI thread and
     * because the number is only meaningful at this instant. The database read is then handed to
     * [Executors.MODEL_EXECUTOR]: `ModelDbController.query` is `@WorkerThread`, and a disk read on
     * the UI thread at the end of every load is not something to add to the owner's daily driver
     * just to run a check.
     */
    @JvmStatic
    fun checkAfterBind(app: LauncherAppState, items: List<ItemInfo>) {
        checks++
        val bound = items.count { it.container == Favorites.CONTAINER_DESKTOP }
        Executors.MODEL_EXECUTOR.execute {
            val rows = runCatching {
                app.model.modelDbController.query(
                    arrayOf(Favorites._ID),
                    "${Favorites.CONTAINER}=${Favorites.CONTAINER_DESKTOP}",
                    null,
                    null,
                ).use { it.count }
            }.getOrElse {
                // A query that could not run proves nothing either way. Say so and stop -- reporting
                // a violation off a failed read would be a fabricated defect, which costs more than
                // the missed check.
                Log.w(TAG, "desktop row count unavailable; check skipped", it)
                return@execute
            }
            if (bound < rows) {
                AresInvariants.violation(
                    ID,
                    "finishBindingItems",
                    "bound=$bound rows=$rows missing=${rows - bound}",
                )
            } else {
                Log.i(TAG, "bind complete: bound=$bound rows=$rows")
            }
        }
    }
}
