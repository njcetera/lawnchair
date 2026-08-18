package app.lawnchair.areslauncher

import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.recyclerview.widget.RecyclerView

/**
 * Adapter for AresLauncher's vertical home list (Strategy D: see
 * design/vertical-home-strategies.md and design/component-verification-1.md).
 *
 * Rows wrap an already-built item View (BubbleTextView, LauncherAppWidgetHostView, ...)
 * produced by Launcher3's normal item-inflation pipeline -- Workspace.addInScreen hands
 * these views to [addItem] instead of placing them into a CellLayout.
 */
class AresHomeAdapter : RecyclerView.Adapter<AresHomeAdapter.ViewHolder>() {

    private val items = mutableListOf<View>()

    fun addItem(itemView: View) {
        items.add(itemView)
        notifyItemInserted(items.size - 1)
    }

    fun removeItem(itemView: View): Boolean {
        val index = items.indexOf(itemView)
        if (index == -1) return false
        items.removeAt(index)
        notifyItemRemoved(index)
        return true
    }

    fun clear() {
        val size = items.size
        if (size == 0) return
        items.clear()
        notifyItemRangeRemoved(0, size)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val container = FrameLayout(parent.context)
        container.layoutParams = RecyclerView.LayoutParams(
            RecyclerView.LayoutParams.MATCH_PARENT,
            RecyclerView.LayoutParams.WRAP_CONTENT,
        )
        return ViewHolder(container)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val child = items[position]
        (child.parent as? ViewGroup)?.removeView(child)
        holder.container.removeAllViews()
        holder.container.addView(
            child,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
            ),
        )
    }

    override fun onViewRecycled(holder: ViewHolder) {
        holder.container.removeAllViews()
    }

    override fun getItemCount(): Int = items.size

    class ViewHolder(val container: FrameLayout) : RecyclerView.ViewHolder(container)
}
