package eu.magicsk.transi.adapters

import android.animation.ValueAnimator
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.drawable.Animatable
import android.graphics.drawable.GradientDrawable
import android.text.format.DateFormat
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.annotation.DrawableRes
import androidx.core.content.ContextCompat
import androidx.core.content.res.ResourcesCompat
import androidx.core.view.ViewCompat
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat.AccessibilityActionCompat
import androidx.core.view.isVisible
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.google.android.material.color.MaterialColors
import eu.magicsk.transi.R
import eu.magicsk.transi.data.models.MHDTableData
import eu.magicsk.transi.data.models.MHDTableVehicle
import eu.magicsk.transi.data.models.delayColor
import eu.magicsk.transi.data.models.delayText
import eu.magicsk.transi.data.models.remainingMinutes
import eu.magicsk.transi.data.remote.responses.PlatformLabel
import eu.magicsk.transi.databinding.TableListItemBinding
import eu.magicsk.transi.util.customizeLineText
import eu.magicsk.transi.util.dpToPx
import java.util.Date

class MHDTableAdapter(
    private val onLongClick: (MHDTableData) -> Unit,
) : ListAdapter<MHDTableData, MHDTableAdapter.MHDTableViewHolder>(DIFF) {
    class MHDTableViewHolder(val binding: TableListItemBinding) : RecyclerView.ViewHolder(binding.root) {
        @DrawableRes
        var nowIndicator = 0
    }

    var platformLabels: List<PlatformLabel>? = null
        set(value) {
            field = value
            refresh()
        }
    var vehicles: Map<String, MHDTableVehicle> = emptyMap()
        set(value) {
            field = value
            refresh()
        }
    private val expanded = mutableSetOf<String>()

    /** Rebinds every row in place: remaining times, corners, platforms and vehicles. */
    fun refresh() = notifyItemRangeChanged(0, itemCount, REFRESH)

    /** Expands the listed row with [id]; its position, or -1 when it is not listed. */
    fun expand(id: String): Int {
        val position = currentList.indexOfFirst { it.id == id }
        if (position >= 0 && expanded.add(id)) notifyItemChanged(position)
        return position
    }

    override fun onCurrentListChanged(previousList: MutableList<MHDTableData>, currentList: MutableList<MHDTableData>) {
        expanded.retainAll(currentList.map { it.id }.toSet())
        // The first and last rows round their outer corners.
        refresh()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): MHDTableViewHolder {
        val holder = MHDTableViewHolder(TableListItemBinding.inflate(LayoutInflater.from(parent.context), parent, false))
        val card = holder.binding.MHDTableListLayout
        card.setOnClickListener {
            val position = holder.bindingAdapterPosition
            if (position == RecyclerView.NO_POSITION) return@setOnClickListener
            val item = getItem(position)
            if (!expanded.remove(item.id)) expanded.add(item.id)
            bind(holder, item, position, animate = true)
        }
        card.setOnLongClickListener {
            val position = holder.bindingAdapterPosition
            if (position != RecyclerView.NO_POSITION) onLongClick(getItem(position))
            position != RecyclerView.NO_POSITION
        }
        ViewCompat.replaceAccessibilityAction(
            card, AccessibilityActionCompat.ACTION_LONG_CLICK, parent.context.getString(R.string.table_departure_actions), null
        )
        return holder
    }

    override fun onBindViewHolder(holder: MHDTableViewHolder, position: Int) {
        bind(holder, getItem(position), position, animate = false)
    }

    private fun bind(holder: MHDTableViewHolder, item: MHDTableData, position: Int, animate: Boolean) {
        holder.binding.apply {
            val context = root.context
            val online = item.type == "online"
            val isExpanded = item.id in expanded
            val vehicle = vehicles[item.busID]?.takeIf { online }

            val large = 25f.dpToPx(context).toFloat()
            val small = 3f.dpToPx(context).toFloat()
            val top = if (position == 0) large else small
            val bottom = if (position == itemCount - 1) large else small
            MHDTableListLayout.background = GradientDrawable().apply {
                setColor(MaterialColors.getColor(root, com.google.android.material.R.attr.colorSurface))
                cornerRadii = floatArrayOf(top, top, top, top, bottom, bottom, bottom, bottom)
            }

            customizeLineText(MHDTableListLineNum, item.line, context, root.resources)
            // Setting the same text again would restart the marquee on every refresh.
            if (MHDTableListHeadsign.text.toString() != item.headsign) MHDTableListHeadsign.text = item.headsign
            MHDTableListHeadsign.isSelected = true
            MHDTableListLastStopSmall.text = item.lastStopName
            MHDTableListLastStopSmall.isVisible = item.lastStopName != "none" && !isExpanded
            MHDTableListStuck.isVisible = item.stuck
            bindRemainingTime(holder, item, context)
            MHDTableListPlatform.text =
                platformLabels?.firstOrNull { it.id.toIntOrNull() == item.platform }?.label.orEmpty()

            MHDTableListDeparture.text = context.getString(R.string.departureTime, clock(context, item.departureTime))
            MHDTableListLastStop.isVisible = online
            MHDTableListLastStop.text = item.lastStopName
            MHDTableListVehicleText.text = vehicle?.let {
                context.getString(R.string.vehicleText, it.type, item.busID.drop(2))
            }.orEmpty()
            MHDTableListAC.isVisible = vehicle?.ac == 1
            MHDTableListDelayIcon.imageTintList =
                ColorStateList.valueOf(ContextCompat.getColor(context, delayColor(item.delay, item.type)))
            val (delayRes, delayMinutes) = delayText(item.delay, item.type)
            MHDTableListDelayText.text = context.getString(delayRes, delayMinutes)

            if (vehicle != null && isExpanded) {
                val folder = if (vehicle.imgt == 0) "vm" else "vs"
                Glide.with(MHDTableListVehicleImg)
                    .load("https://imhd.sk/ba/media/$folder/${vehicle.img.toString().padStart(8, '0')}/${item.busID.drop(2)}")
                    .into(MHDTableListVehicleImg)
                MHDTableListVehicleImg.isVisible = true
            } else {
                Glide.with(MHDTableListVehicleImg).clear(MHDTableListVehicleImg)
                MHDTableListVehicleImg.isVisible = false
            }
            MHDTableListDetailLayout.setExpanded(isExpanded, animate)
            ViewCompat.replaceAccessibilityAction(
                MHDTableListLayout, AccessibilityActionCompat.ACTION_CLICK,
                context.getString(if (isExpanded) R.string.table_hide_details else R.string.table_show_details), null
            )
        }
    }

    // "now" is a pulsing pair of dots, outlined when the time is only scheduled; reduced motion keeps them still.
    private fun bindRemainingTime(holder: MHDTableViewHolder, item: MHDTableData, context: Context) {
        val time = holder.binding.MHDTableListTime
        val minutes = remainingMinutes(item.departureTime, System.currentTimeMillis())
        val online = item.type == "online"
        val text = when {
            minutes == null -> context.getString(R.string.table_now)
            minutes == 0 -> context.getString(R.string.table_under_minute)
            minutes > 59 -> clock(context, item.departureTime)
            else -> context.getString(R.string.table_minutes, minutes)
        }.let { if (online) it else context.getString(R.string.table_approximate, it) }
        if (minutes == null) {
            val indicator = if (online) R.drawable.ic_filled_now else R.drawable.ic_outline_now
            if (holder.nowIndicator != indicator) {
                holder.nowIndicator = indicator
                val drawable = ResourcesCompat.getDrawable(context.resources, indicator, context.theme)
                time.text = ""
                time.background = drawable
                if (ValueAnimator.areAnimatorsEnabled()) (drawable as? Animatable)?.start()
            }
            time.contentDescription = text
        } else {
            holder.nowIndicator = 0
            time.background = null
            time.text = text
            time.contentDescription = null
        }
    }

    private fun clock(context: Context, millis: Long): String = DateFormat.getTimeFormat(context).format(Date(millis))

    companion object {
        private val REFRESH = Any()

        private val DIFF = object : DiffUtil.ItemCallback<MHDTableData>() {
            override fun areItemsTheSame(oldItem: MHDTableData, newItem: MHDTableData) = oldItem.id == newItem.id
            override fun areContentsTheSame(oldItem: MHDTableData, newItem: MHDTableData) = oldItem == newItem
        }
    }
}
