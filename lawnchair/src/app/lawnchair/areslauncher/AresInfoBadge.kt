package app.lawnchair.areslauncher

import android.graphics.Rect
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import com.android.launcher3.BubbleTextView
import com.android.launcher3.LauncherSettings.Favorites
import com.android.launcher3.R
import com.android.launcher3.model.data.ItemInfo

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
 * ## The glyph is an information "i", not the exclamation that was asked for
 *
 * Reversed by the owner after use — *"the menu icon on apps should be an !, its shoild be the i in
 * a circle as the information icon"*. An exclamation mark is the universal glyph for a **warning**,
 * so a tile wearing one reads as "something is wrong with this app" rather than "there is more to
 * know about it". `ares_info_badge_glyph.xml` carries the full note, including the argument this
 * project originally made *against* the i and why it was the weaker half.
 *
 * Nothing but the glyph changed: same backdrop, same sizes, same centred composition, so the badge
 * still matches the × on the opposite corner. The spoken label was already glyph-agnostic
 * (`ares_open_item_menu`, "More options"), so it needed no revision — which is the argument for
 * naming a string after what a control *does* rather than what it looks like.
 *
 * ## App icons only
 *
 * Not widgets. The first cut gave them one too — the ask said "for icons and widgets" — and it was
 * withdrawn after use: *"we shouldn't have the ! for widgets. only app icons."* It was never a
 * clean fit. `BubbleTextView.startLongPressAction()` funnels into
 * `PopupContainerWithArrow.showForIcon()`, whose parameter type is `BubbleTextView`, so **a widget
 * can never show that popup** — the badge could only ever have opened App info for the provider,
 * which is one entry from a menu rather than the menu, and a widget already carries the resize
 * chevron and the × without needing a third control.
 *
 * Folders get none either. There is no popup for a folder, and §18 already gives a folder no ×
 * — you empty a folder to remove it — so a lone affordance on the one item type with no menu
 * behind it would promise something that does not exist. The apps **inside** a folder do carry
 * one; that surface is [AresFolderEdit].
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
    @JvmOverloads
    fun createBadge(
        container: FrameLayout,
        label: CharSequence?,
        touchSizePx: Int = 0,
        onTap: () -> Unit,
    ): View {
        val res = container.resources
        // See AresRemoveBadge.createBadge: a folder cell cannot carry two 48dp targets side by side.
        val touch = if (touchSizePx > 0) {
            touchSizePx
        } else {
            res.getDimensionPixelSize(R.dimen.ares_widget_resize_touch_size)
        }
        val margin = res.getDimensionPixelSize(R.dimen.ares_widget_resize_margin)
        // Mirror of the ×: pull the drawn circle into the top-END corner by padding the far sides,
        // leaving the whole 48dp view touchable. Through the same clamp, so the two corners stay
        // symmetric and a folder's smaller target cannot clip this one either -- see
        // AresRemoveBadge.cornerPull.
        val pull = AresRemoveBadge.cornerPullFor(res, touch)

        return ImageView(container.context).apply {
            tag = BADGE_TAG
            setPaddingRelative(pull, 0, 0, pull)
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
        info.itemType == Favorites.ITEM_TYPE_APPLICATION ||
            info.itemType == Favorites.ITEM_TYPE_DEEP_SHORTCUT

    /**
     * Raises the menu for the tile currently drawn as [itemView].
     *
     * The same call a long-press used to make, so this is the real `PopupContainerWithArrow` with
     * the app's shortcuts, App info, Uninstall and Widgets — not a reconstruction of it.
     *
     * Returns false when there is nothing to show, so the caller can decline rather than leave the
     * user with a control that silently does nothing. That happens when the holder is not currently
     * attached (scrolled off between the tap and the lookup), which is why the badge's own presence
     * is not taken as proof that a view exists to anchor to.
     */
    fun showMenu(itemView: View?): Boolean {
        val icon = itemView as? BubbleTextView ?: return false
        icon.startLongPressAction()
        return true
    }
}
