package eu.magicsk.transi.adapters

import android.app.NotificationManager
import android.content.Context
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

    private fun onLongClick(current: Route, context: Context) {
        val steps = current.steps
        val from = if (steps[0].departure_stop != "") steps[0].departure_stop else steps[1].departure_stop
        val to = if (steps[steps.size - 1].arrival_stop != "") steps[steps.size - 1].arrival_stop else steps[steps.size - 2].arrival_stop
        val notificationManager = context.getSystemService(NotificationManager::class.java) as NotificationManager
        println(from)
        println(to)
        val title = context.getString(R.string.trip_notification_title, from, to)
        println(title)
        var bigBody = ""
        for (i in steps.indices) {
            val step = steps[i]
            bigBody += if (step.type == "TRANSIT") {
                "<b>${step.line.number}</b> ▶ <b>${step.headsign}</b><br>\t\t\t${step.departure_time} ${step.departure_stop}<br>\t\t\t${step.arrival_time} ${step.arrival_stop}"
            } else {
                "\t\t\t\t🚶\t\t<b>${step.text.replace("zo štartu ", "")}</b>"
            }
            if (i != steps.size - 1) bigBody += "<br>"
        }
        val shareBody = context.getString(R.string.trip_share_body, title, bigBody)


        notificationManager.sendNotification(
            title,
            context.getString(R.string.trip_notification_body, current.duration, current.arrival_departure_time),
            Html.fromHtml(bigBody, Html.FROM_HTML_MODE_LEGACY),
            Html.fromHtml(shareBody, Html.FROM_HTML_MODE_LEGACY),
            true,
            context.getString(R.string.trip_planner_notification_channel_id),
            1,
            false,
            context,
            shareAction = true
        )
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
                TripPlannerStepsAdapter(TripPlannerItemList[position].steps as MutableList<Step>) {
                    onLongClick(
                        current,
                        context
                    )
                }
            setOnLongClickListener {
                onLongClick(current, context)
                true
            }
        }
    }

    override fun getItemCount(): Int {
        return TripPlannerItemList.size
    }
}