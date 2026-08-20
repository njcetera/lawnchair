package app.lawnchair.areslauncher

import android.content.ActivityNotFoundException
import android.graphics.Rect
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import com.android.launcher3.BubbleTextView
import com.android.launcher3.Launcher
import com.android.launcher3.LauncherSettings.Favorites
import com.android.launcher3.R
import com.android.launcher3.model.data.ItemInfo
import com.android.launcher3.util.PackageManagerHelper

/**
 * The context-menu (!) affordance shown on icons and widgets while the grid is in edit mode.
 *
 * ## Why this exists at all
 *
 * Edit mode took the gesture that used to raise the popup. Long-press on the home grid now enters
 * edit mode and deliberately does **not** also show `PopupContainerWithArrow` — see the long note in
 * [AresHomeAdapter]'s long-click handler — which left App info, Uninstall and an app's shortcuts
 * with no route in from a tile. The owner asked for one:
 *
 * > *"for icons and widgets, add another option in the top right with a ! icon and when clicked, it
 * > pulls up the app menu... this will allow us to maintain that menu functionality"*
 *
 * ## Icons and widgets take different routes, because only one of them has a popup
 *
 * `BubbleTextView.startLongPressAction()` funnels into `PopupContainerWithArrow.showForIcon()`,
 * whose parameter type is `BubbleTextView`. **A widget can never show that popup** — there is no
 * such thing to show for one. So an icon gets the real menu, and a widget gets the entry that menu
 * would have led to anyway: the provider's App info page, via
 * [PackageManagerHelper.startDetailsActivityForInfo], which resolves the component from the
 * `ItemInfo` and therefore works for a widget as well as an icon.
 *
 * Folders get **no** badge. There is no popup for a folder either, and §18 already establishes that
 * a folder carries no × — you empty a folder to remove it — so adding a lone affordance to the one
 * item type that has no menu behind it would promise something that does not exist.
 *
 * ## Why this rides on the holder container
 *
 * Identical reasoning to [AresRemoveBadge]: for a widget the item view is an `AppWidgetHostView`
 * hosting the provider's RemoteViews, and our own child inside it is removed the next time the
 * provider pushes an update. Attaching to the container also means the edit-mode transform applies
 * for free, so the badge floats with its tile rather than sitting still over a moving one.
 *
 * Top-**end**, opposite [AresRemoveBadge]'s top-start ×, with the resize chevron at bottom-end. On a
 * 1×1 tile all three have to coexist, and those are the three corners that do not collide.
 */
object AresInfoBadge {

    /** Tag on the badge view, so the host can hit-test it without a resource id. */
    const val BADGE_TAG = "ares_info_badge"

    /**
     * Builds the ! overlay for a home cell.
     *
     * [label] is the item's own name and is spoken rather than drawn, for the same reason the ×
     * does it: it turns a row of identical "Options" buttons into "Chrome options".
     */
    fun createBadge(container: FrameLayout, label: CharSequence?, onTap: () -> Unit): View {
        val res = container.resources
        val touch = res.getDimensionPixelSize(R.dimen.ares_widget_resize_touch_size)
        val margin = res.getDimensionPixelSize(R.dimen.ares_widget_resize_margin)

        return ImageView(container.context).apply {
            tag = BADGE_TAG
            // CENTER, not a stretched background -- see ares_remove_badge.xml for why the drawn
            // badge is composed smaller than the touch target that hosts it.
            setImageResource(R.drawable.ares_info_badge)
            scaleType = ImageView.ScaleType.CENTER
            isClickable = true
            isFocusable = true
            contentDescription = if (label.isNullOrBlank()) {
                res.getString(R.string.ares_open_item_menu_generic)
            } else {
                res.getString(R.string.ares_open_item_menu, label)
            }
            AresA11y.describeAsButton(this)
            setOnClickListener { onTap() }
            layoutParams = FrameLayout.LayoutParams(touch, touch).apply {
                gravity = Gravity.TOP or Gravity.END
                setMargins(margin, margin, margin, margin)
            }
        }
    }

    /**
     * True when [x],[y] fall on the badge.
     *
     * The host's edit-mode touch listener consumes taps on items so tiles stay inert, which would
     * otherwise swallow this badge's click before it fired. It asks this first and declines to
     * consume when the answer is yes — the same arrangement the × and the chevron use.
     *
     * **[x],[y] must be in [container]'s own coordinate space, mapped through the container's
     * transform** — `AresHomeListView.toChildLocal` produces them. Subtracting `container.left`
     * alone does not, because edit mode scales the container.
     */
    fun isPointOnBadge(container: View, x: Float, y: Float): Boolean {
        val badge = container.findViewWithTag<View>(BADGE_TAG) ?: return false
        if (badge.visibility != View.VISIBLE) return false
        val bounds = Rect()
        badge.getHitRect(bounds)
        return bounds.contains(x.toInt(), y.toInt())
    }

    /**
     * Whether [info] has a menu worth offering.
     *
     * Icons and widgets do; folders do not (see the class note). Kept here rather than inline at the
     * call site so the badge and its hit-test cannot disagree about which items carry one.
     */
    fun hasMenu(info: ItemInfo): Boolean =
        info.itemType == Favorites.ITEM_TYPE_APPWIDGET ||
            info.itemType == Favorites.ITEM_TYPE_APPLICATION ||
            info.itemType == Favorites.ITEM_TYPE_DEEP_SHORTCUT

    /**
     * Raises the menu for [info], given the tile's [itemView].
     *
     * An icon gets the real `PopupContainerWithArrow` through the same call a long-press used to
     * make. Anything else — in practice a widget — gets App info for its provider, because no popup
     * exists for it.
     *
     * Returns false when nothing could be shown, so the caller can decline rather than leave the
     * user with a control that silently does nothing.
     */
    fun showMenu(launcher: Launcher, itemView: View?, info: ItemInfo): Boolean {
        if (itemView is BubbleTextView) {
            itemView.startLongPressAction()
            return true
        }
        return try {
            PackageManagerHelper.startDetailsActivityForInfo(launcher, info, null, null)
            true
        } catch (e: SecurityException) {
            false
        } catch (e: ActivityNotFoundException) {
            false
        }
    }
}
