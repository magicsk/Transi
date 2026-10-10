package eu.magicsk.transi.adapters

import android.content.res.ColorStateList
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.PopupMenu
import androidx.core.content.ContextCompat
import androidx.core.graphics.ColorUtils
import androidx.core.view.ViewCompat
import androidx.core.view.isVisible
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import eu.magicsk.transi.R
import eu.magicsk.transi.data.remote.responses.Stop
import eu.magicsk.transi.databinding.StopsListItemBinding

data class StopItem(val stop: Stop, val favorite: Boolean)

class TypeAheadAdapter(
    private val showDirections: Boolean,
    private val onItemClicked: (Stop) -> Unit,
    private val onDirectionClicked: (Stop) -> Unit,
    private val onDirectionLongClicked: () -> Unit,
    private val onFavoriteToggled: (Stop) -> Unit,
) : ListAdapter<StopItem, TypeAheadAdapter.TypeAheadViewHolder>(DIFF) {

    class TypeAheadViewHolder(val binding: StopsListItemBinding) : RecyclerView.ViewHolder(binding.root) {
        var favoriteActionId = View.NO_ID
    }

    fun getStop(position: Int): Stop = getItem(position).stop

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): TypeAheadViewHolder {
        val holder = TypeAheadViewHolder(StopsListItemBinding.inflate(LayoutInflater.from(parent.context), parent, false))
        fun stop() = holder.bindingAdapterPosition.takeIf { it != RecyclerView.NO_POSITION }?.let { getStop(it) }
        holder.binding.root.setOnClickListener { stop()?.let(onItemClicked) }
        holder.binding.root.setOnLongClickListener { view ->
            val stop = stop()?.takeIf { it.id > 0 } ?: return@setOnLongClickListener false
            PopupMenu(view.context, view).apply {
                menu.add(view.context.getString(favoriteLabel(getItem(holder.bindingAdapterPosition).favorite)))
                    .setOnMenuItemClickListener {
                        onFavoriteToggled(stop)
                        true
                    }
            }.show()
            true
        }
        holder.binding.directionBtn.setOnClickListener { stop()?.let(onDirectionClicked) }
        holder.binding.directionBtn.setOnLongClickListener {
            onDirectionLongClicked()
            true
        }
        return holder
    }

    override fun onBindViewHolder(holder: TypeAheadViewHolder, position: Int) {
        val (stop, favorite) = getItem(position)
        holder.binding.apply {
            val context = root.context
            stopName.text = stop.name
            stopName.isSelected = true
            stopFavorite.isVisible = favorite
            directionBtn.isVisible = showDirections && stop.id > 0
            val (icon, color) = when (stop.type) {
                "bus" -> R.drawable.ic_bus to R.color.table_red
                "regio_bus" -> R.drawable.ic_regiobus to R.color.table_yellow
                "train" -> R.drawable.ic_train to R.color.table_blue
                "location" -> R.drawable.ic_location_on to R.color.table_blue
                "map" -> R.drawable.ic_map to R.color.table_green
                else -> R.drawable.ic_bus to R.color.table_grey
            }
            val tint = ContextCompat.getColor(context, color)
            stopIconDrawable.setImageResource(icon)
            stopIconDrawable.imageTintList = ColorStateList.valueOf(tint)
            stopIconBackground.imageTintList = ColorStateList.valueOf(ColorUtils.setAlphaComponent(tint, 0x33))

            ViewCompat.removeAccessibilityAction(root, holder.favoriteActionId)
            holder.favoriteActionId = if (stop.id > 0) {
                ViewCompat.addAccessibilityAction(root, context.getString(favoriteLabel(favorite))) { _, _ ->
                    onFavoriteToggled(stop)
                    true
                }
            } else {
                View.NO_ID
            }
        }
    }

    companion object {
        fun favoriteLabel(favorite: Boolean) = if (favorite) R.string.table_remove_favorite else R.string.table_favorite

        private val DIFF = object : DiffUtil.ItemCallback<StopItem>() {
            override fun areItemsTheSame(oldItem: StopItem, newItem: StopItem) =
                oldItem.stop.id == newItem.stop.id && oldItem.stop.type == newItem.stop.type

            override fun areContentsTheSame(oldItem: StopItem, newItem: StopItem) = oldItem == newItem
        }
    }
}
