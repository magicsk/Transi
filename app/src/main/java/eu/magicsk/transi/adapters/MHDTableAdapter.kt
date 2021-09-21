package eu.magicsk.transi.adapters

import android.app.Activity
import android.graphics.PorterDuff
import android.graphics.drawable.AnimatedVectorDrawable
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.core.content.res.ResourcesCompat
import androidx.core.view.setPadding
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import eu.magicsk.transi.R
import eu.magicsk.transi.data.models.MHDTableData
import eu.magicsk.transi.data.models.MHDTableVehicle
import eu.magicsk.transi.data.remote.responses.StopsJSON
import eu.magicsk.transi.util.dpToPx
import eu.magicsk.transi.util.getLineColor
import eu.magicsk.transi.util.getLineTextColor
import eu.magicsk.transi.util.isDarkTheme
import io.socket.client.IO
import io.socket.client.Socket
import kotlinx.android.synthetic.main.fragment_main.*
import kotlinx.android.synthetic.main.table_list_item.view.*
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.net.URI
import java.text.SimpleDateFormat
import java.util.*

class MHDTableAdapter(
    private val TableItemList: MutableList<MHDTableData>,
    private val TableVehicleInfo: MutableList<MHDTableVehicle>,
) : RecyclerView.Adapter<MHDTableAdapter.MHDTableViewHolder>() {
    class MHDTableViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView)

    private val uri: URI = URI.create("https://imhd.sk/")
    private val options = IO.Options()
    private val socket: Socket = IO.socket(uri, options)
    private val tabArgs = JSONArray()
    private var connected = false
    private var dismissed = false
    private var stopList: StopsJSON = StopsJSON()

    fun putStopList(stops: StopsJSON) {
        stopList.clear()
        stopList.addAll(stops)
    }

    fun ioConnect(stopId: Int) {
        if (connected) {
            ioDisconnect()
        }
        println("connecting")
        options.reconnection = true
        options.path = "/rt/sio2"
        tabArgs.put(0, stopId)
        tabArgs.put(1, "*")
        socket.connect()
        socket
            .emit("tabStart", tabArgs)
            .emit("infoStart")
    }

    fun ioObservers(activity: Activity) {
        socket
            .on(Socket.EVENT_CONNECT) {
                connected = true
                println("connected")
            }
            .on(Socket.EVENT_DISCONNECT) {
                connected = false
                println("disconnected")
            }
            .on(Socket.EVENT_RECONNECT) {
                connected = true
                println("reconnect")
                socket
                    .emit("tabStart", tabArgs)
                    .emit("infoStart")
            }
            .on("vInfo") {
                val data = JSONObject(it[0].toString())
                val keys = data.keys()
                val item = MHDTableVehicle(
                    try {
                        data.getInt(keys.next())
                    } catch (e: JSONException) {
                        0
                    },
                    try {
                        data.getInt(keys.next())
                    } catch (e: JSONException) {
                        0
                    },
                    try {
                        data.getInt(keys.next())
                    } catch (e: JSONException) {
                        0
                    },
                    try {
                        data.getInt(keys.next())
                    } catch (e: JSONException) {
                        0
                    },
                    try {
                        data.getInt(keys.next())
                    } catch (e: JSONException) {
                        0
                    },
                    try {
                        data.getString(keys.next())
                    } catch (e: JSONException) {
                        "error"
                    },
                    try {
                        data.getString(keys.next())
                    } catch (e: JSONException) {
                        "error"
                    },
                )
                TableVehicleInfo.add(item)
            }
            .on("tabs") {
                println("new tabs")
                val data = JSONObject(it[0].toString())
                val keys = data.keys()
                while (keys.hasNext()) {
                    val platform: MutableList<MHDTableData> = mutableListOf()
                    val key = keys.next()
                    val tabs = data.getJSONObject(key).getJSONArray("tab")
                    for (i in 0 until tabs.length()) {
                        val item = MHDTableData(
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
                            key,
                            try {
                                tabs.getJSONObject(i).getString("issi")
                            } catch (e: JSONException) {
                                "offline"
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
                                tabs.getJSONObject(i).getInt("tuZidx")
                            } catch (e: JSONException) {
                                -1
                            },
                            try {
                                tabs.getJSONObject(i).getInt("predoslaZidx")
                            } catch (e: JSONException) {
                                -1
                            },
                            try {
                                tabs.getJSONObject(i).getString("predoslaZstr")
                                    .replace("Bratislava, ", "")
                            } catch (e: JSONException) {
                                "none"
                            },
                            try {
                                tabs.getJSONObject(i).getBoolean("uviaznute")
                            } catch (e: JSONException) {
                                false
                            },
                            false
                        )
                        platform.add(item)
                    }
                    activity.runOnUiThread {
                        addItems(platform)
                    }
                }
            }
            .on("iText") {
                var infos = ""
                val data = JSONArray(it[0].toString())
                for (i in 0 until data.length()) {
                    val info = try {
                        data.getString(i)
                    } catch (e: JSONException) {
                        ""
                    }
                    if (info != "" && infos != "") infos = "$infos\n\n$info" else if (info != "") infos =
                        info
                }
                activity.runOnUiThread {
                    if (infos != "" && !dismissed) {
                            val adapter = TableInfoAdapter(mutableListOf(infos))
                            activity.MHDTableInfoText.adapter = adapter
                            ItemTouchHelper(object : ItemTouchHelper.SimpleCallback(0, ItemTouchHelper.RIGHT) {
                                override fun onMove(v: RecyclerView, h: RecyclerView.ViewHolder, t: RecyclerView.ViewHolder) = false
                                override fun onSwiped(h: RecyclerView.ViewHolder, dir: Int) {
                                    adapter.removeAt(h.adapterPosition)
                                    activity.MHDTableInfoText.visibility = View.GONE
                                    dismissed = true
                                }
                            }).attachToRecyclerView(activity.MHDTableInfoText)
                            activity.MHDTableInfoText.layoutManager = LinearLayoutManager(activity)
                            activity.MHDTableInfoText.visibility = View.VISIBLE
                    } else {
                        activity.MHDTableInfoText.visibility = View.GONE
                    }
                }
            }
    }

    fun ioDisconnect() {
        socket.disconnect()
        socket.close()
        val size = TableItemList.size
        clearList()
        notifyItemRangeRemoved(0, size)
    }

    private fun clearList() {
        TableItemList.clear()
        notifyItemRangeRemoved(0, itemCount)
    }

    private fun addItems(items: MutableList<MHDTableData>) {
        if (items.size > 0) {
            val tempList = mutableListOf<MHDTableData>()
            if (TableItemList.size > 0) {
                val platform = items[0].platform
                try {
                    for (i in 0 until itemCount) {
                        if (TableItemList[i].platform == platform) {
                            var found = false
                            for (j in 0 until items.size) {
                                if (TableItemList[i].Id == items[j].Id) {
                                    found = true
                                    items[j].expanded = TableItemList[i].expanded
                                    TableItemList[i] = items[j]
                                    items.removeAt(j)
                                    notifyItemChanged(i)
                                    break
                                }
                            }
                            if (!found) {
                                TableItemList.removeAt(i)
                                notifyItemRemoved(i)
                            }
                        }
                    }
                } catch (e: IndexOutOfBoundsException) {
                    //shouldn't be like this
                }
            }
            tempList.addAll(TableItemList)
            TableItemList.addAll(items)
            TableItemList.sortBy { x -> x.departureTime }
            for (i in TableItemList.size - 1 downTo tempList.size) {
                notifyItemInserted(i)
            }
            for (i in 0 until tempList.size) {
                if (TableItemList[i] != tempList[i]) notifyItemChanged(i)
            }
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): MHDTableViewHolder {
        val context = parent.context
        val inflater = LayoutInflater.from(context)
        val view = inflater.inflate(R.layout.table_list_item, parent, false)
        return MHDTableViewHolder(view)
    }

    override fun onBindViewHolder(holder: MHDTableViewHolder, position: Int) {
        val current = TableItemList[position]
        val mins =
            (current.departureTime - System.currentTimeMillis()).toDouble() / 60000
        val hours = SimpleDateFormat("H:mm", Locale.UK).format(current.departureTime)
        val time =
            if (mins > 60) hours else if (mins < 0) "now" else if (mins < 1) "<1 min" else "${mins.toInt()} min"
        val timeText = if (current.type == "online") time else if (mins < 0) "nowo" else "~ $time"
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

            // time left for departure
            if (timeText == "now" || timeText == "nowo") {
                MHDTableListTime.text = ""
                MHDTableListTime.textSize = 12f
                MHDTableListTime.background = ResourcesCompat.getDrawable(
                    resources,
                    if (timeText == "now") R.drawable.ic_filled_now else R.drawable.ic_outline_now,
                    context?.theme
                )
                (MHDTableListTime.background as AnimatedVectorDrawable).start()
            } else {
                MHDTableListTime.text = timeText
                MHDTableListTime.textSize = 18f
                MHDTableListTime.background = null
            }

            // platform label
            if (stopList.size > 1) {
                for (i in 0 until stopList.size) {
                    val currentStopId = tabArgs.getInt(0)
                    if (currentStopId == stopList[i].id) {
                        if (stopList[i].platform_labels != null) {
                            stopList[i].platform_labels?.forEach { platformLabel ->
                                if (current.platform.replace(
                                        "${currentStopId}.",
                                        ""
                                    ) == platformLabel.id
                                ) {
                                    MHDTableListPlatform.visibility = View.VISIBLE
                                    MHDTableListPlatform.text = platformLabel.label
                                }
                            }
                        } else {
                            MHDTableListPlatform.visibility = View.GONE
                        }
                    }
                }
            }

            // stuck status
            if (current.stuck) {
                MHDTableListStuck.visibility = View.VISIBLE
                MHDTableListStuckInfo.visibility = View.VISIBLE
            } else {
                MHDTableListStuck.visibility = View.GONE
                MHDTableListStuckInfo.visibility = View.GONE
            }

            // expected departure
            MHDTableListDeparture.text =
                context.getString(R.string.departureTime).format(SimpleDateFormat("H:mm", Locale.UK).format(current.departureTime))

            // latest stop & delay
            if (current.type == "online") {
                when {
                    current.delay > 0 -> {
                        when {
                            current.delay > 3 -> {
                                MHDTableListDelayIcon.backgroundTintList = ContextCompat.getColorStateList(context, R.color.delay3)
                            }
                            current.delay > 1 -> {
                                MHDTableListDelayIcon.backgroundTintList = ContextCompat.getColorStateList(context, R.color.delay2)
                            }
                            else -> {
                                MHDTableListDelayIcon.backgroundTintList = ContextCompat.getColorStateList(context, R.color.delay1)
                            }
                        }
                        MHDTableListDelayText.text = context.getString(R.string.delay).format(current.delay)
                    }
                    current.delay < 0 -> {
                        MHDTableListDelayIcon.backgroundTintList = ContextCompat.getColorStateList(context, R.color.inAdvance)
                        MHDTableListDelayText.text = context.getString(R.string.inAdvance).format(current.delay.toString().drop(1))
                    }
                    current.delay == 0 -> {
                        MHDTableListDelayIcon.backgroundTintList = ContextCompat.getColorStateList(context, R.color.onTime)
                        MHDTableListDelayText.text = context.getString(R.string.onTime)
                    }
                }
                MHDTableListOnlineInfo.visibility = View.VISIBLE
                MHDTableListLastStop.visibility = View.VISIBLE
                MHDTableListLastStop.text = context.getString(R.string.lastStop).format(current.lastStopName)
                for (i in 0 until TableVehicleInfo.size) {
                    if (TableVehicleInfo[i].issi == current.busID) {
                        val currentVehicle = TableVehicleInfo[i]
                        val busID = current.busID.drop(2)
                        val v = if (currentVehicle.imgt == 0) "vm" else "vs"
                        val url = "https://imhd.sk/ba/media/$v/${currentVehicle.img.toString().padStart(8, '0')}/$busID"
                        setOnClickListener {
                            if (current.expanded) {
                                MHDTableListDetailLayout.visibility = View.GONE
                            } else {
                                println(url)
                                Glide.with(this)
                                    .load(url)
                                    .into(MHDTableListVehicleImg)
                                MHDTableListVehicleImg.visibility = View.VISIBLE
                                MHDTableListDetailLayout.visibility = View.VISIBLE
                            }
                            current.expanded = !current.expanded
                        }
                        Glide.with(this)
                            .load(url)
                            .onlyRetrieveFromCache(true)
                            .into(MHDTableListVehicleImg)
                        MHDTableListVehicleText.text =
                            context.getString(R.string.vehicleText).format(currentVehicle.type, busID)
                    }
                }
            } else {
                setOnClickListener {
                    MHDTableListVehicleImg.visibility = View.GONE
                    MHDTableListDetailLayout.visibility = if (current.expanded) View.GONE else View.VISIBLE
                    current.expanded = !current.expanded
                }
                MHDTableListVehicleText.text = current.Id.toString()
                MHDTableListOnlineInfo.visibility = View.GONE
                MHDTableListLastStop.text = context.getString(R.string.offline)
            }
            MHDTableListDetailLayout.visibility = if (current.expanded) View.VISIBLE else View.GONE
        }
    }

    override fun getItemCount(): Int {
        return TableItemList.size
    }
}