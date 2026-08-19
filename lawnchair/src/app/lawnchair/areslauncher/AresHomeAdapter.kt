package app.lawnchair.areslauncher

import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.recyclerview.widget.RecyclerView
import com.android.launcher3.BubbleTextView
import com.android.launcher3.Launcher
import com.android.launcher3.LauncherSettings.Favorites
import com.android.launcher3.R
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
            applyRowStyle(itemView)
        }
        itemView.isHapticFeedbackEnabled = false

        val rowHeight = if (itemView is BubbleTextView) {
            holder.container.resources.getDimensionPixelSize(R.dimen.ares_app_row_height)
        } else {
            // Widgets size themselves; don't force a row height on them.
            FrameLayout.LayoutParams.WRAP_CONTENT
        }
        holder.container.addView(
            itemView,
            FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, rowHeight),
        )
    }

    /**
     * Applies the Niagara-style row appearance to a home-list icon: icon on the left, label
     * dominant and adjacent, both vertically centred in a generous row.
     *
     * Done programmatically rather than in XML because workspace items are inflated by
     * [com.android.launcher3.util.ItemInflater], which hardcodes `R.layout.app_icon` with no
     * override hook. `app_icon.xml` is shared with folders, app pairs and other surfaces, so it is
     * deliberately not edited in place -- see design/gesture-transition-reassessment.md §4.
     *
     * Note both attributes below are corrections of the stock vertical-grid styling:
     *  - `centerVertically` must be OFF: its onMeasure() path sums icon + padding + text height,
     *    which only holds when the icon sits ABOVE the text. In horizontal mode that over-estimates
     *    content height and produces a large bogus top padding.
     *  - gravity must be overridden: `BaseIcon.Workspace` inherits `center_horizontal`, which would
     *    centre the label in the leftover width instead of placing it next to the icon.
     *
     * Known gap: the icon keeps the workspace icon size, because `BubbleTextView.mIconSize` is
     * `private final` and only settable via the `iconSizeOverride` XML attribute. The app-list pane
     * gets the smaller Niagara icon via `ares_all_apps_icon.xml`; matching it here would require an
     * override hook in the shared ItemInflater.
     */
    private fun applyRowStyle(icon: BubbleTextView) {
        val res = icon.resources
        icon.setLayoutHorizontal(true)
        icon.setCenterVertically(false)
        icon.gravity = Gravity.START or Gravity.CENTER_VERTICAL
        icon.compoundDrawablePadding =
            res.getDimensionPixelSize(R.dimen.ares_app_row_drawable_padding)
        val padH = res.getDimensionPixelSize(R.dimen.ares_app_row_padding_horizontal)
        icon.setPaddingRelative(padH, 0, padH, 0)
        icon.setTextSize(
            TypedValue.COMPLEX_UNIT_PX,
            res.getDimension(R.dimen.ares_app_row_text_size),
        )
        icon.maxLines = 1
        icon.ellipsize = TextUtils.TruncateAt.END
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
