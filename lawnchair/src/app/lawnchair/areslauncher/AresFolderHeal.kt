package app.lawnchair.areslauncher

import android.content.pm.LauncherApps
import com.android.launcher3.Launcher
import com.android.launcher3.LauncherAppState
import com.android.launcher3.LauncherSettings.Favorites
import com.android.launcher3.icons.cache.CacheLookupFlag
import com.android.launcher3.model.data.FolderInfo
import com.android.launcher3.model.data.ItemInfo
import com.android.launcher3.model.data.WorkspaceItemInfo
import com.android.launcher3.util.Executors

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

    /** Folders whose child icons have been refreshed this process, so it runs at most once each. */
    private val iconRefreshedFolders = HashSet<Int>()

    /**
     * Refreshes folder children whose STORED icon is a stale placeholder (owner 2026-08-25: two apps
     * render as a solid-colour circle inside a folder though the app list shows them fine). The
     * child's `bitmap` was loaded once at model-load down a path that kept a placeholder; the app
     * list renders these apps correctly because it loads via [IconCache.getTitleAndIcon] with the
     * live [LauncherActivityInfo]. This does exactly that for each app child, which pulls the real
     * icon the app list already shows. Runs once per folder per process (cheap, and the reload is
     * harmless for children whose icon was already fine).
     *
     * THREADING: [IconCache.getTitleAndIcon] asserts the model worker thread
     * ([BaseIconCache.assertWorkerThread]) and CRASHES if called on the main thread. Its caller
     * [com.android.launcher3.Workspace.addInScreen] runs on the MAIN thread during bind, so the
     * icon reload is dispatched to [Executors.MODEL_EXECUTOR]; when it has pulled fresh icons the
     * folder's preview is repainted back on [Executors.MAIN_EXECUTOR]. Fire-and-forget: the caller
     * does nothing with the result.
     */
    @JvmStatic
    fun refreshChildIcons(launcher: Launcher, folder: FolderInfo) {
        if (!iconRefreshedFolders.add(folder.id)) return
        val launcherApps = launcher.getSystemService(LauncherApps::class.java) ?: return
        val iconCache = LauncherAppState.getInstance(launcher).iconCache
        // Snapshot the app children on the caller's (main) thread so the worker never walks a
        // getContents() another pipeline may be mutating concurrently.
        val appChildren = folder.getContents()
            .mapNotNull { it as? WorkspaceItemInfo }
            .filter { it.itemType == Favorites.ITEM_TYPE_APPLICATION && it.targetComponent != null }
        if (appChildren.isEmpty()) return
        Executors.MODEL_EXECUTOR.execute {
            var changed = false
            for (wai in appChildren) {
                val comp = wai.targetComponent ?: continue
                val activityInfo = try {
                    launcherApps.getActivityList(comp.packageName, wai.user)
                        .firstOrNull { it.componentName == comp }
                } catch (e: Exception) {
                    null
                } ?: continue
                iconCache.getTitleAndIcon(wai, activityInfo, CacheLookupFlag.DEFAULT_LOOKUP_FLAG)
                changed = true
            }
            if (changed) {
                Executors.MAIN_EXECUTOR.execute { repaintFolderPreview(launcher, folder) }
            }
        }
    }

    /** Repaints [folder]'s preview after an async icon reload, wherever the folder lives. */
    private fun repaintFolderPreview(launcher: Launcher, folder: FolderInfo) {
        // Desktop (Ares home) folder row: rebind just that row so the fresh child bitmaps show.
        val list = launcher.workspace?.aresHomeList
        if (list != null) {
            val at = list.aresAdapter.indexOf(folder)
            if (at >= 0) {
                list.aresAdapter.notifyItemChanged(at)
                return
            }
        }
        // Hotseat / stock folder icon.
        launcher.findFolderIcon(folder.id)?.onItemsChanged(false)
    }

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
