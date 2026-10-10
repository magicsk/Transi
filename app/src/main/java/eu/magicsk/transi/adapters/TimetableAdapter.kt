package eu.magicsk.transi.adapters

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import eu.magicsk.transi.data.remote.responses.idsbk.DirectionDeparture
import eu.magicsk.transi.databinding.TimetableListItemBinding

/** The stops of one trip, each paired with its formatted departure time. */
class TimetableAdapter(
    private val onStopClicked: (DirectionDeparture) -> Unit
) : ListAdapter<Pair<DirectionDeparture, String>, TimetableAdapter.TimetableViewHolder>(Diff) {

    class TimetableViewHolder(val binding: TimetableListItemBinding) : RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): TimetableViewHolder {
        val holder = TimetableViewHolder(
            TimetableListItemBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        )
        holder.binding.root.setOnClickListener {
            val position = holder.bindingAdapterPosition
            if (position != RecyclerView.NO_POSITION) onStopClicked(getItem(position).first)
        }
        return holder
    }

    override fun onBindViewHolder(holder: TimetableViewHolder, position: Int) {
        val (stop, time) = getItem(position)
        holder.binding.TimetableListItemStopName.text = stop.station_name
        holder.binding.TimetableListItemTime.text = time
    }

    private object Diff : DiffUtil.ItemCallback<Pair<DirectionDeparture, String>>() {
        override fun areItemsTheSame(
            oldItem: Pair<DirectionDeparture, String>,
            newItem: Pair<DirectionDeparture, String>,
        ) = oldItem.first.station_id == newItem.first.station_id

        override fun areContentsTheSame(
            oldItem: Pair<DirectionDeparture, String>,
            newItem: Pair<DirectionDeparture, String>,
        ) = oldItem == newItem
    }
}
