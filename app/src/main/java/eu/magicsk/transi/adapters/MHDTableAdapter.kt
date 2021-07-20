package eu.magicsk.transi.adapters

import android.app.Activity
import android.graphics.PorterDuff
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.core.view.setPadding
import androidx.recyclerview.widget.RecyclerView
import eu.magicsk.transi.R
import eu.magicsk.transi.data.models.MHDTableData
import eu.magicsk.transi.getLineColor
import eu.magicsk.transi.getLineTextColor
import eu.magicsk.transi.util.dpToPx
import eu.magicsk.transi.util.isDarkTheme
import io.socket.client.IO
import io.socket.client.Socket
import kotlinx.android.synthetic.main.table_list_item.view.*
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.net.URI
import java.text.SimpleDateFormat
import java.util.*

class MHDTableAdapter(
    private val TableItemList: MutableList<MHDTableData>
) : RecyclerView.Adapter<MHDTableAdapter.MHDTableViewHolder>() {

    class MHDTableViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView)

    fun ioConnect(activity: Activity) {
        val uri = URI.create("https://imhd.sk/")
        val options = IO.Options()
        options.reconnection = true
        options.path = "/rt/sio2"
        val socket = IO.socket(uri, options)
        val tabArgs = JSONArray()
        tabArgs.put(0, 82)
        tabArgs.put(1, "*")
        socket.connect()
            .on(Socket.EVENT_CONNECT) { println("connecting") }
            .on(Socket.EVENT_DISCONNECT) { println("disconnected") }
            .on("cack") { println("connect successful") }
            .emit("tabStart", tabArgs)
            .emit("infoStart")
            .on("vInfo") {
            }
            .on("tabs") {
                val data = JSONObject(it[0].toString())
                val keys = data.keys()
                while (keys.hasNext()) {
                    val tabs = data.getJSONObject(keys.next()).getJSONArray("tab")
                    for (i in 0 until tabs.length()) {
                        val result = MHDTableData(
                            try {
                                tabs.getJSONObject(i).getLong("i")
                            } catch (e: JSONException) {
                                0
                            },
                            try {
                                tabs.getJSONObject(i).getString("linka")
                            } catch (e: JSONException) {
                                "Err"
                            },
                            try {
                                tabs.getJSONObject(i).getString("issi")
                            } catch (e: JSONException) {
                                "Error"
                            },
                            try {
                                tabs.getJSONObject(i).getString("cielStr")
                            } catch (e: JSONException) {
                                try {
                                    tabs.getJSONObject(i).getString("konecnaZstr")
                                } catch (e: JSONException) {
                                    "Error"
                                }
                            },
                            try {
                                tabs.getJSONObject(i).getLong("cas")
                            } catch (e: JSONException) {
                                0
                            },
                            try {
                                tabs.getJSONObject(i).getInt("casDelta")
                            } catch (e: JSONException) {
                                0
                            },
                            try {
                                tabs.getJSONObject(i).getString("typ")
                            } catch (e: JSONException) {
                                "cp"
                            },
                            try {
                                tabs.getJSONObject(i).getInt("predoslaZidx")
                            } catch (e: JSONException) {
                                0
                            }
                        )
                        activity.runOnUiThread() {
                            addItems(result)
                        }
                    }
                }
            }
            .on("iText") {
            }
    }

    private fun addItems(item: MHDTableData) {
        var size = TableItemList.size
        var exists = false
        if (size > 0) {
            for (q in 0 until size) {
                for (j in 0 until size) {
                    // TODO just change time
                    if (TableItemList[j].Id == item.Id) {
                        exists = true
                        TableItemList[j].departureTime = item.departureTime
                        TableItemList[j].delay = item.delay
                        TableItemList[j].lastStopId = item.lastStopId
                        TableItemList[j].busID = item.busID
                        TableItemList[j].type = item.type
                        notifyItemChanged(j)
                    }
                    if (TableItemList[j].departureTime < System.currentTimeMillis()) {
                        TableItemList.removeAt(j)
                        notifyItemRemoved(j)
                        size--
                    }
                }
            }
        }
        if (!exists) {
            TableItemList.add(item)
            notifyItemInserted(TableItemList.size - 1)
        }
        TableItemList.sortBy { x -> x.departureTime }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): MHDTableViewHolder {
        return MHDTableViewHolder(
            LayoutInflater.from(parent.context).inflate(
                R.layout.table_list_item,
                parent,
                false
            )
        )
    }

    override fun onBindViewHolder(holder: MHDTableViewHolder, position: Int) {
        val current = TableItemList[position]
        val mins =
            (current.departureTime - System.currentTimeMillis()).toDouble() / 60000
        val hours = SimpleDateFormat("H:mm", Locale.UK).format(current.departureTime)
        val time =
            if (mins > 60) hours else if (mins < 0.3) "●●  " else if (mins < 1) "<1 min" else "${mins.toInt()} min"
        val timeText = if (current.type == "online") time else "~ $time"
        val rounded =
            try {
                current.line.contains("S") || current.line.toInt() < 10
            } catch (e: NumberFormatException) {
                false
            }

        holder.itemView.apply {
            if (rounded) {
                MHDTableListLineNum.setBackgroundResource(R.drawable.round_shape)
                if (!current.line.contains("S")) MHDTableListLineNum.setPadding(
                    12f.dpToPx(context),
                    5f.dpToPx(context),
                    12f.dpToPx(context),
                    5f.dpToPx(context)
                ) else {
                    MHDTableListLineNum.setPadding(5f.dpToPx(context))
                }
            } else {
                MHDTableListLineNum.setBackgroundResource(R.drawable.rounded_shape)
            }
            val drawable = MHDTableListLineNum.background
            drawable.setColorFilter(
                ContextCompat.getColor(
                    context,
                    getLineColor(current.line, isDarkTheme(resources))
                ), PorterDuff.Mode.SRC
            )
            MHDTableListLineNum.setTextColor(
                ContextCompat.getColor(
                    context,
                    getLineTextColor(current.line)
                )
            )

            MHDTableListLineNum.background = drawable
            MHDTableListLineNum.text = current.line
            MHDTableListHeadsign.text = current.headsign
            MHDTableListTime.text = timeText

        }
    }

    override fun getItemCount(): Int {
        return TableItemList.size
    }
}