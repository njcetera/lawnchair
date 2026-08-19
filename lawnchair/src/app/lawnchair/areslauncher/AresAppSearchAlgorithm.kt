package app.lawnchair.areslauncher

import com.android.launcher3.allapps.AllAppsStore
import com.android.launcher3.allapps.BaseAllAppsAdapter.AdapterItem
import com.android.launcher3.model.data.AppInfo
import com.android.launcher3.search.SearchAlgorithm
import com.android.launcher3.search.SearchCallback
import java.text.Normalizer
import java.util.Locale

/**
 * AresLauncher §17 — local app-name filtering for the app-list pane's search.
 *
 * Deliberately minimal: v1 filters the already-loaded app list by name and nothing else. Lawnchair's
 * richer stack (`LawnchairSearchAdapterProvider` and friends) can search the web, contacts and
 * settings, which is more than this pane is meant to do — the search here is a filter over the list
 * you are already looking at.
 *
 * Matching is case- and diacritic-insensitive on a "word starts with" basis, then falls back to a
 * substring match. Prefix matches sort first, so typing "ma" surfaces *Maps* above *Gmail* rather
 * than ordering purely alphabetically.
 */
class AresAppSearchAlgorithm(private val appsStore: AllAppsStore<*>) : SearchAlgorithm<AdapterItem> {

    override fun doSearch(query: String, callback: SearchCallback<AdapterItem>) {
        val normalized = query.normalizeForSearch()
        if (normalized.isEmpty()) {
            callback.clearSearchResult()
            return
        }

        val scored = appsStore.apps
            .mapNotNull { app ->
                val title = app.title?.toString()?.normalizeForSearch() ?: return@mapNotNull null
                val rank = rank(title, normalized) ?: return@mapNotNull null
                app to rank
            }
            // Rank first, then alphabetically so equal-rank results have a stable, predictable order.
            .sortedWith(compareBy({ it.second }, { it.first.title?.toString()?.lowercase(Locale.getDefault()) }))
            .mapTo(ArrayList()) { AdapterItem.asApp(it.first) }

        callback.onSearchResult(query, scored, SearchCallback.FINAL)
    }

    /** Lower is better; null means no match. */
    private fun rank(title: String, query: String): Int? = when {
        title.startsWith(query) -> RANK_TITLE_PREFIX
        title.split(' ').any { it.startsWith(query) } -> RANK_WORD_PREFIX
        title.contains(query) -> RANK_SUBSTRING
        else -> null
    }

    override fun cancel(interruptActiveRequests: Boolean) {
        // Filtering is synchronous over an in-memory list, so there is never anything in flight.
    }

    private companion object {
        const val RANK_TITLE_PREFIX = 0
        const val RANK_WORD_PREFIX = 1
        const val RANK_SUBSTRING = 2

        /** Combining marks left behind by NFD decomposition — the accents themselves. */
        val COMBINING_MARKS = Regex("\\p{Mn}+")

        /**
         * Lower-cases and strips diacritics so "cafe" matches "Café".
         *
         * This runs on every keystroke for every installed app, so ASCII-only strings — the
         * overwhelming majority of app titles and essentially every query typed on a QWERTY
         * keyboard — take a fast path that skips [java.text.Normalizer] entirely. Only strings
         * that actually contain non-ASCII get decomposed to NFD and stripped of combining marks.
         */
        fun String.normalizeForSearch(): String {
            val lowered = trim().lowercase(Locale.getDefault())
            if (lowered.all { it.code < 0x80 }) return lowered
            return Normalizer.normalize(lowered, Normalizer.Form.NFD).replace(COMBINING_MARKS, "")
        }
    }
}

private val AllAppsStore<*>.apps: List<AppInfo>
    get() = this.getApps().toList()
