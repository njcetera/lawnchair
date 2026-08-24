package app.lawnchair.areslauncher

import android.animation.ValueAnimator
import android.content.res.ColorStateList
import android.graphics.drawable.GradientDrawable
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.android.launcher3.Launcher
import com.android.launcher3.LauncherSettings.Favorites
import com.android.launcher3.R
import com.android.launcher3.model.data.FolderInfo
import com.android.launcher3.model.data.ItemInfo
import com.android.launcher3.views.BaseDragLayer

/**
 * WP folders (design/wp-folder-design.md): the edit-mode Material-You FAB that creates a new,
 * empty Windows-Phone-style folder.
 *
 * The owner's spec: *"In edit mode, a Material-You FAB at the bottom adds a new WP-style folder.
 * Clicking it creates an empty folder."* The FAB is a floating overlay on the DragLayer, shown
 * only while the home grid is in edit mode ([attach] from `enterEditMode`, [detach] from
 * `exitEditMode`), so it never competes with normal-mode gestures.
 *
 * Built by hand rather than as a `com.google.android.material` ExtendedFloatingActionButton: that
 * widget requires a Material Components/Material3 theme on the host context and throws at inflation
 * otherwise. This is the owner's daily driver, so a theme-dependent crash is unacceptable; a plain
 * `FrameLayout` + `GradientDrawable` gives the same extended-FAB shape (pill, primary-container
 * fill, icon + label, elevation) with no theme requirement, coloured from the wallpaper-derived
 * Material-You palette (`materialColorPrimaryContainer` / `materialColorOnPrimaryContainer`).
 *
 * Being a clickable view above the RecyclerView, a tap on the FAB is consumed here and never
 * reaches the empty-space-tap-exits-edit-mode listener on the grid (review finding m7).
 */
object AresWpFab {

    private const val TAG = "AresWpFab"

    /** The live FAB view, or null when detached. Single instance -- one edit session at a time. */
    private var fab: View? = null

    /** Show the FAB over [launcher]'s DragLayer. Idempotent. */
    fun attach(launcher: Launcher) {
        val dragLayer = launcher.dragLayer ?: return
        // A fold recreates the Launcher; a `fab` left over from the old activity would make this a
        // no-op and the new edit session would show no FAB. If the tracked view isn't a live child
        // of THIS drag layer, drop it and build fresh.
        val existing = fab
        if (existing != null) {
            if (existing.parent === dragLayer) return
            (existing.parent as? ViewGroup)?.removeView(existing)
            fab = null
        }
        val view = build(launcher)
        // BaseDragLayer regenerates any foreign LayoutParams into its OWN type (checkLayoutParams
        // only accepts BaseDragLayer.LayoutParams) and that copy constructor drops gravity -- a
        // plain FrameLayout.LayoutParams silently lands top-left. Construct the drag-layer type
        // directly and set gravity on it; with customPosition=false the FrameLayout super.onLayout
        // honours the gravity, so the FAB sits bottom-centre as asked.
        val lp = BaseDragLayer.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
        ).apply {
            gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            bottomMargin = dp(launcher, 40f)
        }
        view.setOnClickListener { onCreateClicked(launcher) }
        dragLayer.addView(view, lp)
        fab = view
        // Rise-and-fade in, matching the edit-mode entrance rather than snapping.
        view.alpha = 0f
        view.translationY = dp(launcher, 16f).toFloat()
        view.animate().alpha(1f).translationY(0f).setDuration(160L).start()
    }

    /** Remove the FAB. Idempotent; safe when not attached. */
    fun detach() {
        val view = fab ?: return
        fab = null
        (view.parent as? ViewGroup)?.removeView(view)
    }

    private fun build(launcher: Launcher): View {
        val container = launcher.materialColor(R.color.materialColorPrimaryContainer)
        val onContainer = launcher.materialColor(R.color.materialColorOnPrimaryContainer)

        val row = LinearLayout(launcher).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            val padH = dp(launcher, 20f)
            val padV = dp(launcher, 14f)
            setPadding(padH, padV, padH, padV)
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                // Fully rounded ends -- an extended FAB is a stadium/pill.
                cornerRadius = dp(launcher, 28f).toFloat()
                setColor(container)
            }
            elevation = dp(launcher, 6f).toFloat()
            isClickable = true
            isFocusable = true
        }

        val icon = ImageView(launcher).apply {
            setImageResource(R.drawable.ic_plus)
            imageTintList = ColorStateList.valueOf(onContainer)
            val s = dp(launcher, 22f)
            layoutParams = LinearLayout.LayoutParams(s, s)
        }
        val label = TextView(launcher).apply {
            text = launcher.getString(R.string.ares_wp_fab_label)
            setTextColor(onContainer)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            val gap = dp(launcher, 8f)
            setPadding(gap, 0, 0, 0)
        }
        row.addView(icon)
        row.addView(label)
        return row
    }

    private fun onCreateClicked(launcher: Launcher) {
        val folder = createEmptyWpFolder(launcher)
        if (folder == null) {
            Log.e(TAG, "WP folder create declined (no free cell?)")
        } else {
            Log.i(TAG, "created empty WP folder ${folder.id}")
        }
    }

    /**
     * Create a new, empty WP folder: a legal free cell, a [FolderInfo] flagged [FolderInfo.FLAG_ARES_WP]
     * with a default title, one atomic model write, then the tile into the adapter and a rank
     * renumber. Mirrors the proven overlay-folder create in [AresFolderDrop] minus the contents and
     * the overlay inflate (a WP folder never opens the overlay). Returns the folder, or null if the
     * grid is full.
     */
    fun createEmptyWpFolder(launcher: Launcher): FolderInfo? {
        val list = launcher.workspace?.aresHomeList ?: return null
        // Collapse any inline-expanded WP folder first: adding the new tile while a folder is
        // expanded could splice it into the middle of the expanded child run and truncate the
        // collapse scan (adversarial review 2026-08-23, finding 4). A collapsed list is a clean
        // insert target.
        list.aresAdapter.collapseWpFolder()
        val cell = IntArray(2)
        val screenId = AresWidgetAdd.findFreeCell(launcher, 1, 1, cell)
        if (screenId == AresWidgetAdd.NO_SCREEN) return null

        val folderInfo = FolderInfo()
        folderInfo.options = folderInfo.options or FolderInfo.FLAG_ARES_WP
        folderInfo.title = launcher.getString(R.string.ares_wp_folder_default_title)
        launcher.modelWriter.addItemToDatabase(
            folderInfo,
            Favorites.CONTAINER_DESKTOP,
            screenId,
            cell[0],
            cell[1],
        )
        if (folderInfo.id == ItemInfo.NO_ID) {
            Log.e(TAG, "WP folder row was not given an id")
            return null
        }

        val adapter = list.aresAdapter
        adapter.addItem(folderInfo)
        list.animateNextRelayout()
        AresHomeReorder.persistOrder(launcher, adapter.snapshot())
        return folderInfo
    }

    private fun dp(launcher: Launcher, value: Float): Int =
        (value * launcher.resources.displayMetrics.density).toInt()
}

/** Resolve a wallpaper-derived Material-You colour resource against the launcher context. */
private fun Launcher.materialColor(resId: Int): Int = getColor(resId)
