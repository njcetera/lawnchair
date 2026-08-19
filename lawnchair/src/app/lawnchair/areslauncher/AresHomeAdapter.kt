package app.lawnchair.areslauncher

import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.recyclerview.widget.RecyclerView
import com.android.launcher3.BubbleTextView
import com.android.launcher3.Launcher
import com.android.launcher3.LauncherSettings.Favorites
import com.android.launcher3.model.data.ItemInfo

/**
 * Adapter for AresLauncher's vertical home list (Strategy D: see
 * design/vertical-home-strategies.md and design/architecture-reassessment.md).
 *
 * Data-backed: rows hold [ItemInfo] and inflate their view on bind via
 * [Launcher.getItemInflater], the same API ModelCallbacks.bindItems uses. That keeps Launcher3's
 * whole view-construction pipeline -- icon loading, icon-pack theming, badges, click and focus
 * listeners -- without reimplementing any of it.
 *
 * An earlier revision stored already-inflated Views instead. That retained every item view for the
 * life of the list (only the containers recycled) and detached LauncherAppWidgetHostViews mid
 * scroll, which is hostile to widget sizing and RemoteViews state. See
 * design/architecture-reassessment.md §4.
 */
class AresHomeAdapter(private val launcher: Launcher) :
    RecyclerView.Adapter<AresHomeAdapter.ViewHolder>() {

    private val items = mutableListOf<ItemInfo>()

    init {
        // ItemInfo.id is the model's stable primary key, so holders survive rebinds correctly.
        setHasStableIds(true)
    }

    fun addItem(info: ItemInfo) {
        items.add(info)
        notifyItemInserted(items.size - 1)
    }

    fun clear() {
        val size = items.size
        if (size == 0) return
        items.clear()
        notifyItemRangeRemoved(0, size)
    }

    override fun getItemCount(): Int = items.size

    override fun getItemId(position: Int): Long = items[position].id.toLong()

    override fun getItemViewType(position: Int): Int = when (items[position].itemType) {
        Favorites.ITEM_TYPE_APPWIDGET, Favorites.ITEM_TYPE_CUSTOM_APPWIDGET -> TYPE_WIDGET
        else -> TYPE_ICON
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val container = FrameLayout(parent.context)
        container.layoutParams = RecyclerView.LayoutParams(
            RecyclerView.LayoutParams.MATCH_PARENT,
            RecyclerView.LayoutParams.WRAP_CONTENT,
        )
        val holder = ViewHolder(container)
        if (viewType == TYPE_WIDGET) {
            // Widget host views must not be detached and re-attached while scrolling -- doing so
            // disrupts AppWidgetHostView sizing and RemoteViews state. Opting the holder out of
            // recycling is the standard escape hatch.
            holder.setIsRecyclable(false)
        }
        return holder
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val info = items[position]
        holder.container.removeAllViews()

        // inflateItem() uses attachToRoot=false, so the view is ours to add. It returns null when
        // the model decides an item should be dropped (e.g. a widget pending deletion).
        val itemView = launcher.itemInflater.inflateItem(info, holder.container) ?: return

        if (itemView is BubbleTextView) {
            // Deliberately not ItemLongClickListener.INSTANCE_WORKSPACE: that listener's job is
            // starting a CellLayout grid-drag, which doesn't apply to list rows.
            // BubbleTextView.startLongPressAction() is the generic, CellLayout-free mechanism that
            // shows the popup menu -- see component-verification-1.md/-3.md.
            itemView.setOnLongClickListener {
                itemView.startLongPressAction()
                true
            }
        }
        itemView.isHapticFeedbackEnabled = false

        holder.container.addView(
            itemView,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
            ),
        )
    }

    override fun onViewRecycled(holder: ViewHolder) {
        holder.container.removeAllViews()
    }

    class ViewHolder(val container: FrameLayout) : RecyclerView.ViewHolder(container)

    private companion object {
        const val TYPE_ICON = 0
        const val TYPE_WIDGET = 1
    }
}
