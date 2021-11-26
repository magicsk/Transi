package eu.magicsk.transi.adapters

import android.app.NotificationManager
import android.text.Html
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import eu.magicsk.transi.R
import eu.magicsk.transi.data.remote.responses.Route
import eu.magicsk.transi.data.remote.responses.Step
import eu.magicsk.transi.util.sendNotification
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

    fun clear() {
        if (itemCount > 0) {
            val oldSize = itemCount
            TripPlannerItemList.clear()
            notifyItemRangeRemoved(itemCount, oldSize)
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
            TableListItems.adapter =
                TripPlannerStepsAdapter(TripPlannerItemList[position].steps as MutableList<Step>)
            setOnLongClickListener {
                val steps = current.steps
                val from = if (steps[0].departure_stop != null) steps[0].departure_stop else steps[1].departure_stop
                val notificationManager = context.getSystemService(NotificationManager::class.java) as NotificationManager
                var bigBody = ""
                for (i in steps.indices) {
                    val step = steps[i]
                    bigBody += if (step.type == "TRANSIT") {
                        "<b>${step.line.number}</b> ▶ <b>${step.headsign}</b><br>\t${step.departure_time} ${step.departure_stop}<br>\t${step.arrival_time} ${step.arrival_stop}"
                    } else {
                        "🚶\t${step.text}"
                    }
                    if (i != steps.size-1) bigBody += "<br>"
                }
                notificationManager.sendNotification(
                    context.getString(R.string.trip_notification_title, from, steps[steps.size - 1].arrival_stop),
                    context.getString(R.string.trip_notification_body, current.duration, current.arrival_departure_time),
                    Html.fromHtml(bigBody, Html.FROM_HTML_MODE_LEGACY),
                    true,
                    context.getString(R.string.trip_planner_notification_channel_id),
                    1,
                    false,
                    cancelAction = false,
                    context
                )
                true
            }
        }
    }

    override fun getItemCount(): Int {
        return TripPlannerItemList.size
    }
}