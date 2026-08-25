package app.lawnchair.areslauncher

import com.android.launcher3.Launcher
import com.android.launcher3.LauncherSettings.Favorites
import com.android.launcher3.model.data.FolderInfo
import com.android.launcher3.model.data.ItemInfo

/**
 * Heals a folder that carries the SAME app twice as two distinct database rows (owner
 * "cache holdover -- icons in the folder not rendered correctly", 2026-08-25).
 *
 * The Bug #1 fixes (id-dedup in [com.android.launcher3.folder.Folder.addFolderContent], id-based
 * extract in `AresHomeAdapter.extractChildToDesktop`) stop NEW duplicates forming, but a profile
 * that already accumulated one keeps the stale row until something removes it. Two
 * ITEM_TYPE_APPLICATION children of one folder pointing at the same launcher component is never
 * intentional -- you cannot meaningfully pin one app into one folder twice -- and the redundant row
 * is what renders as a blank/wrong preview tile (its icon-cache entry is the one that went stale).
 *
 * Called from [com.android.launcher3.Workspace.addInScreen], the single funnel every bound item
 * passes through **regardless of container** -- so it covers the hotseat folder (container
 * CONTAINER_HOTSEAT), not just the desktop list. The first draft healed only in the Ares home
 * adapter and silently missed the owner's actual case, a hotseat folder, which never routes through
 * that adapter.
 *
 * The survivor is the earliest row (smallest id); each extra goes through `deleteItemFromDatabase`
 * (off the folder, never off the device -- the app stays installed) and out of `getContents()` so
 * the caller can repaint the preview this session. Scoped to plain apps keyed by (component, user);
 * shortcuts/deep-shortcuts are left alone, since two of those can legitimately share a component
 * under different shortcut ids.
 */
object AresFolderHeal {

    private const val DUPLICATE_FOLDER_CHILD_REASON =
        "AresLauncher: duplicate folder child -- same app pinned twice in one folder"

    /**
     * Removes duplicate app rows from [folder]. Returns true if anything was removed, so the caller
     * knows to refresh the folder icon's preview.
     */
    @JvmStatic
    fun dedupe(launcher: Launcher, folder: FolderInfo): Boolean {
        val contents = folder.getContents()
        if (contents.size < 2) return false
        val survivor = HashMap<String, ItemInfo>()
        val redundant = ArrayList<ItemInfo>()
        for (child in contents) {
            if (child.itemType != Favorites.ITEM_TYPE_APPLICATION) continue
            val comp = child.targetComponent ?: continue
            val key = "${comp.flattenToShortString()}#${child.user}"
            val kept = survivor[key]
            when {
                kept == null -> survivor[key] = child
                child.id < kept.id -> { survivor[key] = child; redundant.add(kept) }
                else -> redundant.add(child)
            }
        }
        if (redundant.isEmpty()) return false
        for (dup in redundant) {
            android.util.Log.w(
                "AresFolderFlow",
                "heal duplicate folder child: deleting id=${dup.id} ${dup.targetComponent} " +
                    "from folder ${folder.id}",
            )
            launcher.modelWriter.deleteItemFromDatabase(dup, DUPLICATE_FOLDER_CHILD_REASON)
        }
        contents.removeAll { c -> redundant.any { it.id == c.id } }
        return true
    }
}
