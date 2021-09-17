package eu.magicsk.transi.adapters

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import eu.magicsk.transi.R
import eu.magicsk.transi.data.remote.responses.Route
import eu.magicsk.transi.data.remote.responses.Step
import kotlinx.android.synthetic.main.trip_planner_list.view.*

class TripPlannerAdapter(
    private val TripPlannerItemList: MutableList<Route>
) : RecyclerView.Adapter<TripPlannerAdapter.TripPlannerViewHolder>() {
    class TripPlannerViewHolder(itemView: View) :
        RecyclerView.ViewHolder(itemView)

   fun addItems(items: MutableList<Route>) {
        if (items.size > 0) {
            val oldSize = itemCount
            TripPlannerItemList.clear()
            TripPlannerItemList.addAll(items)
            notifyItemRangeRemoved(itemCount, oldSize)
            notifyItemRangeChanged(0, itemCount)
            notifyItemRangeInserted(oldSize, itemCount)
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): TripPlannerViewHolder {
        val context = parent.context
        val inflater = LayoutInflater.from(context)
        val view = inflater.inflate(R.layout.trip_planner_list, parent, false)
        return TripPlannerViewHolder(view)
    }

    override fun onBindViewHolder(holder: TripPlannerViewHolder, position: Int) {
        val current = TripPlannerItemList[position]
        holder.itemView.apply {
            TableListDurationStopList.text = current.duration
            TableListTime.text = context.getString(R.string.tripTime).format(current.arrival_departure_time)
            TableListItems.layoutManager =
                LinearLayoutManager(TableListItems.context, RecyclerView.VERTICAL, false)
            // TODO trip detail on tap
            TableListItems.adapter =
                TripPlannerStepsAdapter(TripPlannerItemList[position].steps as MutableList<Step>)
        }
    }

    override fun getItemCount(): Int {
        return TripPlannerItemList.size
    }
}