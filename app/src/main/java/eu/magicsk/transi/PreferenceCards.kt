package eu.magicsk.transi

import android.content.res.ColorStateList
import android.graphics.Rect
import android.graphics.drawable.RippleDrawable
import android.view.View
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.preference.Preference
import androidx.preference.PreferenceCategory
import androidx.preference.PreferenceFragmentCompat
import androidx.preference.PreferenceGroup
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.color.MaterialColors
import com.google.android.material.shape.MaterialShapeDrawable
import com.google.android.material.shape.ShapeAppearanceModel
import kotlin.math.roundToInt

/**
 * Draws a settings list (Theme.Transi.Settings) as cards: the rows between two section titles or footers
 * (`@layout/preference_footer`) form one card with large outer and small inner corners, each row a segment a little
 * apart from the next. Also gives the list the side and bottom insets; the top app bar takes the top one.
 */
fun PreferenceFragmentCompat.showAsCards() {
    val list = listView
    val density = resources.displayMetrics.density
    fun dp(value: Int) = (value * density).roundToInt()
    list.clipToPadding = false
    list.addItemDecoration(CardDecoration(this, dp(16), dp(2), dp(24).toFloat(), dp(4).toFloat()))
    // A row shown or hidden changes its neighbours' corners.
    list.adapter?.registerAdapterDataObserver(object : RecyclerView.AdapterDataObserver() {
        override fun onChanged() = list.invalidateItemDecorations()
        override fun onItemRangeInserted(positionStart: Int, itemCount: Int) = list.invalidateItemDecorations()
        override fun onItemRangeRemoved(positionStart: Int, itemCount: Int) = list.invalidateItemDecorations()
        override fun onItemRangeMoved(fromPosition: Int, toPosition: Int, itemCount: Int) = list.invalidateItemDecorations()
    })
    ViewCompat.setOnApplyWindowInsetsListener(list) { view, insets ->
        val bars = insets.getInsets(
            WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout() or WindowInsetsCompat.Type.ime()
        )
        view.updatePadding(left = bars.left, top = 0, right = bars.right, bottom = bars.bottom + dp(24))
        insets
    }
}

private class CardDecoration(
    private val fragment: PreferenceFragmentCompat,
    private val margin: Int,
    private val gap: Int,
    private val outerRadius: Float,
    private val innerRadius: Float,
) : RecyclerView.ItemDecoration() {
    private enum class Kind { HEADER, FOOTER, ROW }

    private data class Corners(val top: Float, val bottom: Float)

    private class Background(val corners: Corners, val drawable: RippleDrawable)

    // Runs after every bind, which resets the row's background, so the card background is set here.
    override fun getItemOffsets(outRect: Rect, view: View, parent: RecyclerView, state: RecyclerView.State) {
        val position = parent.getChildAdapterPosition(view)
        val kinds = kinds(parent)
        val kind = kinds[position] ?: return
        outRect.left = margin
        outRect.right = margin
        if (kind != Kind.ROW) return
        val previous = kinds[position - 1]
        val first = previous != Kind.ROW
        val last = kinds[position + 1] != Kind.ROW
        // Under a row of the same card, a footer or the bar: apart from it. A section title leaves its own space.
        outRect.top = when (previous) {
            Kind.ROW -> gap
            Kind.HEADER -> 0
            else -> margin
        }
        val corners = Corners(if (first) outerRadius else innerRadius, if (last) outerRadius else innerRadius)
        val background = (view.getTag(R.id.preferenceCardBackground) as? Background)?.takeIf { it.corners == corners }
            ?: Background(corners, cardDrawable(view, corners)).also { view.setTag(R.id.preferenceCardBackground, it) }
        if (view.background !== background.drawable) view.background = background.drawable
    }

    /** The card colour, rounded, with a ripple confined to it. */
    private fun cardDrawable(view: View, corners: Corners): RippleDrawable {
        val shape = ShapeAppearanceModel.builder()
            .setTopLeftCornerSize(corners.top).setTopRightCornerSize(corners.top)
            .setBottomLeftCornerSize(corners.bottom).setBottomRightCornerSize(corners.bottom)
            .build()
        val fill = MaterialShapeDrawable(shape).apply {
            fillColor = ColorStateList.valueOf(MaterialColors.getColor(view, com.google.android.material.R.attr.colorSurface))
        }
        val ripple = MaterialColors.getColor(view, androidx.appcompat.R.attr.colorControlHighlight)
        return RippleDrawable(ColorStateList.valueOf(ripple), fill, null)
    }

    /** Adapter position → kind, read from the visible preferences the way the adapter lists them. */
    private fun kinds(parent: RecyclerView): Map<Int, Kind> {
        val positions = parent.adapter as? PreferenceGroup.PreferencePositionCallback ?: return emptyMap()
        val screen = fragment.preferenceScreen ?: return emptyMap()
        val result = HashMap<Int, Kind>()
        fun visit(group: PreferenceGroup) {
            for (i in 0 until group.preferenceCount) {
                val preference = group.getPreference(i)
                if (!preference.isVisible) continue
                val position = positions.getPreferenceAdapterPosition(preference)
                if (position >= 0) result[position] = kindOf(preference)
                if (preference is PreferenceCategory) visit(preference)
            }
        }
        visit(screen)
        return result
    }

    private fun kindOf(preference: Preference) = when {
        preference is PreferenceCategory -> Kind.HEADER
        preference.layoutResource == R.layout.preference_footer -> Kind.FOOTER
        else -> Kind.ROW
    }
}
