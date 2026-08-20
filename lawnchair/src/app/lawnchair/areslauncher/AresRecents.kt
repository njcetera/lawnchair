package app.lawnchair.areslauncher

import android.app.usage.UsageStatsManager
import android.content.Context
import android.graphics.drawable.Drawable
import android.os.Process
import android.text.SpannableString
import android.text.Spanned
import android.text.style.ImageSpan
import android.util.Log
import com.android.launcher3.R
import com.android.launcher3.model.data.AppInfo
import java.util.concurrent.TimeUnit

/**
 * The five most recently used apps, for the top of the app-list pane (§11b).
 *
 * > "at the top of the app list we should [show] the users 5 most recent apps in list form so it
 * > blends into the design."
 *
 * ## Recents, not predictions -- and why that choice was made
 *
 * These are two genuinely different things and §11b asks for the distinction to be made
 * deliberately. This returns apps ordered by [android.app.usage.UsageStats.lastTimeUsed] descending:
 * literally the last five apps the user opened. The alternative was the prediction service, which
 * returns apps it *expects* the user to want. Recency was chosen because it is what the user asked
 * for in those words, and because it is explainable -- a row whose contents the user can predict
 * from what they just did reads as correct, whereas a prediction that guesses wrong reads as broken.
 *
 * Lawnchair's own [app.lawnchair.predictions.UsageStatsRanker] is deliberately not reused: it blends
 * launch counts, foreground time and recency across three weighted windows, which makes it a good
 * *predictor* and a poor answer to "most recent".
 *
 * ## What happens without the permission
 *
 * `PACKAGE_USAGE_STATS` is declared in the manifest but is an appop the user grants under Settings >
 * Special app access > Usage access. Without it `queryAndAggregateUsageStats` returns an empty map
 * -- no exception, no error -- and this returns an empty list, so the app list simply starts at the
 * first letter header exactly as it did before. That is the intended degradation: an absent block is
 * better than a block full of arbitrary apps.
 *
 * ## Duplication with the A-Z list below is intentional
 *
 * A recent app also appears in its own letter section further down. That is the Niagara behaviour
 * the design follows: the top block is a shortcut, not a filter. The adapter tolerates two items
 * carrying the same [AppInfo] because `AdapterItem` diffing for icons is positional
 * (`isSameAs` compares only view type and class), so nothing downstream assumes uniqueness.
 */
object AresRecents {

    /** How many recents §11b asks for. */
    const val COUNT = 5

    private const val TAG = "AresRecents"

    /**
     * The recents section's identity, for adapter diffing only. **Never rendered.**
     *
     * A section is identified by its name everywhere in this stack — `AresSectionHeaderItem`'s
     * `isSameAs`/`isContentSame` compare it, and without a distinct value the recents header would
     * diff equal to whichever letter header happened to sit at the same index. It cannot collide
     * with a real section name, because those come from `AppInfo.sectionName` and are single
     * characters.
     *
     * It is not a *sentinel* in the sense of "a magic string a binder recognises": nothing branches
     * on this value. What the header and the fast scroller branch on is
     * [AresSectionHeaderItem.iconRes] being non-zero, which is a type flag and says what it means.
     */
    const val SECTION_ID = "ares-recents"

    /**
     * The glyph that stands in for a letter beside this section, in the fast-scroll popup.
     *
     * The in-list header takes the drawable directly (see [AresSectionHeaderItem.iconRes]); this is
     * for the places that can only accept a `CharSequence` — `FastScrollSectionInfo.sectionName`,
     * which `RecyclerViewFastScroller` puts straight into the popup bubble, and the A–Z rail's
     * `LetterListTextView` if it is ever revived.
     *
     * An [ImageSpan] rather than a sentinel string, because **stock already does exactly this**:
     * `AlphabeticalAppsList` builds `mPrivateProfileAppScrollerBadge` as a `SpannableString`
     * carrying an `ImageSpan` and hands it to `FastScrollSectionInfo` as a section name. So a
     * section name that renders as a picture is an established shape here, not a new convention —
     * and it means no consumer has to learn about recents to draw it correctly.
     *
     * @param sizePx the box to draw the bolt in. Passed rather than taken from the drawable's
     *   intrinsic 14dp, which is sized against the 13sp in-list header and is far too small beside
     *   the popup's 32dp letters.
     * @param color the colour to tint it, which every caller resolves from the text colour of the
     *   view it is going into. A colour baked into the drawable would be wrong on one of them.
     */
    @JvmStatic
    fun sectionMarker(context: Context, sizePx: Int, color: Int): CharSequence {
        val icon: Drawable = context.getDrawable(R.drawable.ares_ic_recents)
            ?: return ""
        icon.mutate()
        icon.setTint(color)
        icon.setBounds(0, 0, sizePx, sizePx)
        // One space to hang the span on: ImageSpan replaces the character it covers, so the string
        // needs exactly one and its content is never seen.
        return SpannableString(" ").apply {
            setSpan(
                ImageSpan(icon, ImageSpan.ALIGN_BOTTOM),
                0,
                1,
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
            )
        }
    }

    /**
     * How far back to look. Long enough that the block is populated after a quiet day, short enough
     * that "recent" still means something -- an app last opened three weeks ago is not a shortcut
     * the user is reaching for.
     */
    private val WINDOW_MS = TimeUnit.DAYS.toMillis(7)

    /**
     * The [limit] most recently used apps, most recent first, resolved against [apps].
     *
     * [apps] is the pane's own alphabetical app list, so anything the launcher does not show (work
     * profile filtering, hidden apps, the private space) is excluded by construction rather than by
     * a second filter that could drift out of step with the first.
     */
    @JvmStatic
    @JvmOverloads
    fun recentApps(context: Context, apps: List<AppInfo>, limit: Int = COUNT): List<AppInfo> {
        if (limit <= 0 || apps.isEmpty()) return emptyList()
        val usageStats = context.getSystemService(UsageStatsManager::class.java) ?: return emptyList()

        val now = System.currentTimeMillis()
        val lastUsedByPackage = try {
            usageStats.queryAndAggregateUsageStats(now - WINDOW_MS, now)
        } catch (e: Exception) {
            // Defensive: the documented failure is an empty map rather than a throw, but this runs
            // on the bind path for every app-list rebuild and must never be able to take the pane
            // down with it.
            Log.w(TAG, "Could not query usage stats", e)
            return emptyList()
        }
        if (lastUsedByPackage.isEmpty()) return emptyList()

        // One row per package. An app can publish several launcher activities, and five rows for
        // the same app would be a worse answer than four rows for four apps.
        val firstActivityByPackage = HashMap<String, AppInfo>(apps.size)
        for (app in apps) {
            val packageName = app.componentName?.packageName ?: continue
            // Recency is tracked per package and carries no user id, so a work-profile clone would
            // otherwise inherit the personal app's timestamp.
            if (app.user != Process.myUserHandle()) continue
            firstActivityByPackage.putIfAbsent(packageName, app)
        }

        return lastUsedByPackage.values
            .asSequence()
            .filter { it.lastTimeUsed > 0L && it.packageName != context.packageName }
            .sortedByDescending { it.lastTimeUsed }
            .mapNotNull { firstActivityByPackage[it.packageName] }
            .take(limit)
            .toList()
    }
}
