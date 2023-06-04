package eu.magicsk.transi.adapters

import android.annotation.SuppressLint
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import eu.magicsk.transi.data.remote.responses.idsbk.Departure
import eu.magicsk.transi.databinding.TimetableListItemBinding

class TimetableAdapter(
    private var DepartureList: Departure,
    private val onItemClicked: (stationId: Int, stationName: String) -> Unit
) : RecyclerView.Adapter<TimetableAdapter.TimetableViewHolder>() {
    class TimetableViewHolder(
        val binding: TimetableListItemBinding,
        private val DepartureList: Departure,
        private val onItemClicked: (stationId: Int, stationName: String) -> Unit
    ) :
        RecyclerView.ViewHolder(binding.root), View.OnClickListener {
        init {
            binding.root.setOnClickListener(this)
        }

        override fun onClick(v: View?) {
            val stationId = DepartureList.direction_departures[adapterPosition].station_id
            val stationName = DepartureList.direction_departures[adapterPosition].station_name
            onItemClicked(stationId, stationName)
        }
    }

    private var _binding: TimetableListItemBinding? = null
    private val binding get() = _binding!!

    @SuppressLint("NotifyDataSetChanged")
    fun replaceList(new: Departure) {
        DepartureList = new
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): TimetableViewHolder {
        _binding = TimetableListItemBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return TimetableViewHolder(binding, DepartureList, onItemClicked)
    }

    override fun onBindViewHolder(holder: TimetableViewHolder, position: Int) {
        val current = DepartureList.direction_departures[position]
        val departureTime = current.departure
        holder.binding.apply {
            TimetableListItemStopName.text = current.station_name
            TimetableListItemTime.text =
                "${departureTime / 60}:${
                    (departureTime % 60).toString().padStart(2, Char(48))
                }"
        }
    }

    override fun getItemCount(): Int {
        return DepartureList.direction_departures.size
    }
}