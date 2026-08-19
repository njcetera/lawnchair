package app.lawnchair.areslauncher

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.android.launcher3.BubbleTextView
import com.android.launcher3.Launcher
import com.android.launcher3.R
import com.android.launcher3.model.data.AppInfo

/**
 * Alphabetical list of every installed app, for the persistent app-list panel shown on the right
 * half of an unfolded foldable (see [AresAppListView]).
 *
 * Deliberately a separate, much simpler adapter than the all-apps stack
 * (`AlphabeticalAppsList`/`BaseAllAppsAdapter`): that stack is bound to the `ALL_APPS`
 * `LauncherState` surface, which is a full-screen sheet driven by `AllAppsTransitionController`
 * (translation + alpha). Re-parenting that surface into a workspace panel would fight the
 * transition controller and break the folded single-pane mode that still needs it. Reading the
 * same underlying `AllAppsStore` from our own view is the smaller, non-invasive change.
 *
 * Rows reuse `ares_all_apps_icon.xml` so the panel matches the swipe-in pane exactly, and the
 * click/long-click listeners come from the same `ActivityContext` accessors `BaseAllAppsAdapter`
 * uses, so tap-to-launch and the long-press popup behave identically.
 */
class AresAppListAdapter(private val launcher: Launcher) :
    RecyclerView.Adapter<AresAppListAdapter.Holder>() {

    private var apps: List<AppInfo> = emptyList()

    fun setApps(newApps: List<AppInfo>) {
        apps = newApps
        notifyDataSetChanged()
    }

    override fun getItemCount(): Int = apps.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
        val icon = LayoutInflater.from(parent.context)
            .inflate(R.layout.ares_all_apps_icon, parent, false) as BubbleTextView
        icon.setLongPressTimeoutFactor(1f)
        // Same accessors BaseAllAppsAdapter uses (lines 198-199), so behaviour matches the
        // swipe-in pane rather than being a second, subtly different implementation.
        icon.setOnClickListener(launcher.itemOnClickListener)
        icon.setOnLongClickListener(launcher.allAppsItemLongClickListener)
        return Holder(icon)
    }

    override fun onBindViewHolder(holder: Holder, position: Int) {
        holder.icon.applyFromApplicationInfo(apps[position])
    }

    class Holder(val icon: BubbleTextView) : RecyclerView.ViewHolder(icon)
}
