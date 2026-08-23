package app.lawnchair.areslauncher

import android.content.Context
import app.lawnchair.search.adapter.SearchAdapterItem
import app.lawnchair.search.adapter.SearchTargetCompat
import app.lawnchair.search.algorithms.LawnchairLocalSearchAlgorithm
import com.android.app.search.LayoutType
import com.android.launcher3.allapps.AllAppsStore
import com.android.launcher3.allapps.BaseAllAppsAdapter.AdapterItem
import com.android.launcher3.search.SearchAlgorithm
import com.android.launcher3.search.SearchCallback

/**
 * AresLauncher §17 — hybrid app-list search: our own plain app rows (unchanged look) with Lawnchair's
 * richer result categories (calculator, settings, shortcuts, contacts, files, web) appended BELOW,
 * only while a query is active.
 *
 * Built by **composition, not inheritance**: [LawnchairSearchAlgorithm] is a `sealed class` and
 * [LawnchairLocalSearchAlgorithm] is final, so neither can be subclassed. Instead this holds a
 * private local-search instance, lets it produce the full result set, drops its APP rows, and
 * prepends our own from [AresAppSearchAlgorithm]. The app section stays byte-for-byte identical to
 * the app-only search (owner: "I like the current UI"); each category still self-gates on its
 * preference, so a source appears only when enabled (and permitted).
 */
class AresRichSearchAlgorithm(
    context: Context,
    appsStore: AllAppsStore<*>,
) : SearchAlgorithm<AdapterItem> {

    private val appAlgorithm = AresAppSearchAlgorithm(appsStore)
    private val richAlgorithm = LawnchairLocalSearchAlgorithm(context)

    override fun doSearch(query: String, callback: SearchCallback<AdapterItem>) {
        // Our plain app rows — computed synchronously, always shown first.
        val appItems = appAlgorithm.search(query) ?: ArrayList()

        // Lawnchair's engine fills in the rest asynchronously; we drop its app rows (we render those)
        // and append everything else under ours.
        richAlgorithm.doSearch(
            query,
            object : SearchCallback<AdapterItem> {
                override fun onSearchResult(q: String, items: ArrayList<AdapterItem>?) {
                    val categories = dropLeadingDividers(
                        (items ?: emptyList()).filterNot { it.isDropped() },
                    )
                    val combined = ArrayList<AdapterItem>(appItems.size + categories.size)
                    combined.addAll(appItems)
                    combined.addAll(categories)
                    callback.onSearchResult(q, combined)
                }

                override fun clearSearchResult() {
                    // Lawnchair found nothing, but our app match may still have rows to show.
                    if (appItems.isEmpty()) callback.clearSearchResult() else callback.onSearchResult(query, ArrayList(appItems))
                }
            },
        )
    }

    override fun cancel(interruptActiveRequests: Boolean) {
        richAlgorithm.cancel(interruptActiveRequests)
    }

    /**
     * Rows we don't want in the hybrid list:
     *  - app rows (`RESULT_TYPE_APPLICATION`): we render apps ourselves above (shortcuts stay);
     *  - the "search settings" entry (`RESULT_TYPE_SEARCH_SETTINGS`): its gear opens Lawnchair's
     *    stock settings, which clash with the Ares layouts — hidden until we ship our own settings
     *    page (see the custom-settings TODO).
     */
    private fun AdapterItem.isDropped(): Boolean {
        val target = (this as? SearchAdapterItem)?.searchTarget ?: return false
        return target.resultType == SearchTargetCompat.RESULT_TYPE_APPLICATION ||
            target.resultType == SearchTargetCompat.RESULT_TYPE_SEARCH_SETTINGS
    }

    /** After removing the app rows, a divider that now leads the category list would dangle. */
    private fun dropLeadingDividers(items: List<AdapterItem>): List<AdapterItem> =
        items.dropWhile {
            (it as? SearchAdapterItem)?.searchTarget?.layoutType == LayoutType.EMPTY_DIVIDER
        }
}
