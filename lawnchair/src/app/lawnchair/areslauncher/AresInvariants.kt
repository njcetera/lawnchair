package app.lawnchair.areslauncher

import android.util.Log

/**
 * Named runtime invariants: conditions that must hold at a checkpoint, counted when they do not.
 *
 * ## Why a counter and not just a log
 *
 * A loud `Log.e` at a seam already existed here. `Folder.aresLogSeamInvariants` shipped on
 * 2026-08-23, fires at three checkpoints, and in the eleven days after it landed it produced
 * **nothing**: no ledger row, no morning report, no harness reads it, and the defect it was written
 * for (row 40, "folder won't open") is still open with its root state uncaptured. A log line in a
 * logcat nobody greps is not a detector.
 *
 * The difference here is that the counts are readable through the `ares-invariants` test channel
 * and **`ares-smoke` fails when the delta is non-zero**. That turns every smoke run, every journey,
 * every instrumentation class and every minute the owner spends using the launcher into a detector
 * for every invariant at once, without any of them being written to look for it.
 *
 * ## Rules
 *
 * - **Never throw.** This build runs on the owner's device. A violated invariant is a report, not
 *   a crash.
 * - **Keep the FIRST violating record**, not just a tally. A bare count says something went wrong
 *   somewhere in a session containing hundreds of folds, which is close to useless for finding it.
 *   The first record carries the checkpoint, the operand values and a stack.
 * - **Only add an invariant that can actually fire.** The obvious first candidate here could not:
 *   `mIsOpen == (getParent() != null)` describes the DETACHED wedge, and the ledger recorded on
 *   2026-08-22 -- a day before that check shipped -- that the detached state is *not* how row 40
 *   wedges. An invariant that has never fired is an untested claim, not a guarantee.
 */
object AresInvariants {

    private const val TAG = "AresInvariants"

    /** Violation counts by invariant id, since process start or the last [reset]. */
    private val counts = LinkedHashMap<String, Int>()

    /** The first violating record per invariant id, kept verbatim. */
    private val firstRecord = LinkedHashMap<String, String>()

    /**
     * Records a violation of [id] at [checkpoint], with [detail] carrying the operand values that
     * made it false. Logs loudly, counts, and keeps the first occurrence with a stack.
     */
    @JvmStatic
    @Synchronized
    fun violation(id: String, checkpoint: String, detail: String) {
        counts[id] = (counts[id] ?: 0) + 1
        if (!firstRecord.containsKey(id)) {
            firstRecord[id] = "at=$checkpoint $detail\n" +
                Log.getStackTraceString(Throwable("first $id violation"))
        }
        Log.e(TAG, "VIOLATION $id at=$checkpoint $detail")
    }

    /** Total violations across all invariants. What `ares-smoke` gates on. */
    @JvmStatic
    @Synchronized
    fun total(): Int = counts.values.sum()

    /**
     * Clears every counter and record. Called by the harness at the START of a run, so the check is
     * a DELTA over that run rather than a total since process start.
     *
     * The distinction is not pedantic. These counters live in a process-scoped `object`, so a total
     * is neither per-run nor per-install nor per-boot: any violation during startup would make the
     * suite permanently red, and any process kill would silently reset it to green. Both failures
     * are invisible, and a permanently red check is one nobody reads.
     */
    @JvmStatic
    @Synchronized
    fun reset() {
        counts.clear()
        firstRecord.clear()
        Log.i(TAG, "counters reset")
    }

    /**
     * `total=N|<id>=<count>|...` followed by the first record of each violated invariant.
     * `total=0` when clean.
     */
    @JvmStatic
    @Synchronized
    fun report(): String {
        val sb = StringBuilder("total=").append(total())
        for ((id, n) in counts) sb.append('|').append(id).append('=').append(n)
        for ((id, rec) in firstRecord) sb.append("\nFIRST ").append(id).append(": ").append(rec)
        return sb.toString()
    }

    // ------------------------------------------------------------------ invariant ids

    /**
     * A tap on a folder icon was DECLINED even after the stuck-open heal ran.
     *
     * This is the seed invariant, chosen because it is the only folder predicate with a real,
     * device-observed constituency: it is exactly ledger row 40 (Bug B, "folder won't open"),
     * reported by the owner on the Pixel, still open, and reproducible only in normal use. The
     * diagnostic at `ItemClickHandler.onClickFolderIcon` has been printing this since 2026-08-22
     * and nothing has ever read it.
     */
    const val FOLDER_OPEN_DECLINED = "INV-FOLDER-OPEN-DECLINED"

    /**
     * A WP folder finished expanding, but the number of children spliced inline does not match the
     * number the model says it has.
     *
     * ## Why this one, and why [FOLDER_OPEN_DECLINED] is no longer enough
     *
     * The seed invariant above cannot fire on the home grid any more, and ledger row 71 records why:
     * `ModelDbController.migrateAresWpFolders()` stamps `FLAG_ARES_WP` onto every desktop folder on
     * every load, and `AresHomeAdapter` replaces the click handler with `toggleWpFolder`, so
     * `ItemClickHandler.onClickFolderIcon`'s declined-open branch is unreachable for the folders the
     * owner actually taps. It is kept (the hotseat and any non-WP path still route through it) but
     * it is no longer where the defect lives.
     *
     * Row 40 ("folder won't open") is still open, and on the WP surface it can only appear one way:
     * the tap is accepted, the expansion latch is set, and the apps do not show up. That is exactly
     * a run-length mismatch, so this predicate targets the open flagship defect on the surface it
     * now lives on. It is also the shape of an already-fixed owner bug -- "adding apps to the folder
     * will not render them if the folder is already open" (2026-08-24) -- which is evidence the
     * splice really can disagree with the model rather than being a theoretical worry.
     *
     * NOT a tautology over the code that fills the run: the expected count comes from
     * `FolderInfo.getContents()` (the model) and the actual from a scan of the adapter's own list
     * (the render), so the two operands come from different sides of the seam.
     *
     * An EMPTY folder expanding with no children is legal and must not fire -- `expandWpFolder`
     * returns early for it on purpose -- which is why this compares against the model's count rather
     * than asserting the run is non-empty.
     */
    const val WP_EXPAND_RUN_MISMATCH = "INV-WP-EXPAND-RUN"
}
