package app.lawnchair.areslauncher

import com.android.launcher3.allapps.BaseAllAppsAdapter

/**
 * Adapter item for a letter section header (A / B / C) in AresLauncher's app-list pane.
 *
 * Stock Launcher3 has no alphabetical-header view type at all: [BaseAllAppsAdapter]'s types cover
 * icons, dividers, work/private-space cards and folders, and `addAppsWithSections` records section
 * boundaries into a *separate* list that exists purely to drive the fast scroller. The A-Z model is
 * therefore data-only -- nothing ever rendered it in-list. See design/niagara-app-list.md §2.
 *
 * ## Why both diff methods are overridden
 *
 * [AlphabeticalAppsList] diffs adapter items through `MyDiffCallback`, which delegates to the base
 * [BaseAllAppsAdapter.AdapterItem] implementations:
 *
 * ```
 * isSameAs(other)      -> other.viewType == viewType && other.javaClass == javaClass
 * isContentSame(other) -> itemInfo == null && other.itemInfo == null
 * ```
 *
 * Headers carry no `itemInfo`, so without these overrides *every* header would compare equal to
 * every other header on both counts -- same view type, same class, both null. DiffUtil would then
 * treat a changed list as unchanged and skip rebinding, leaving stale letters behind whenever the
 * sections shift (an app installed or removed under a new initial, a profile toggle, a locale
 * change). Comparing [sectionName] makes header identity mean what it looks like it means.
 */
class AresSectionHeaderItem @JvmOverloads constructor(
    @JvmField val sectionName: String,
    /**
     * A drawable to draw **instead of** [sectionName], or `0` for an ordinary letter.
     *
     * The type flag that makes a section icon-marked, chosen over a sentinel section name so that
     * nothing has to recognise a magic string, and so [sectionName] is free to go on meaning
     * "identity" for diffing without also meaning "what to draw" (§11b: recents is a section like
     * any other, marked with a bolt instead of a letter).
     *
     * A header carrying one is otherwise identical to a letter header — same layout, same
     * typography, same insets, same height — which is the whole point of the request. The block is
     * *part of* the list's structure rather than a preamble to it.
     */
    @JvmField val iconRes: Int = 0,
) : BaseAllAppsAdapter.AdapterItem(BaseAllAppsAdapter.VIEW_TYPE_ARES_SECTION_HEADER) {

    override fun isSameAs(other: BaseAllAppsAdapter.AdapterItem): Boolean =
        other is AresSectionHeaderItem && other.sectionName == sectionName

    override fun isContentSame(other: BaseAllAppsAdapter.AdapterItem): Boolean =
        other is AresSectionHeaderItem &&
            other.sectionName == sectionName &&
            other.iconRes == iconRes
}
