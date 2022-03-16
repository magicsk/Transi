@file:Suppress("DEPRECATION")

package eu.magicsk.transi.adapters

import android.annotation.SuppressLint
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
import eu.magicsk.transi.data.remote.responses.StopsJSONItem
import eu.magicsk.transi.util.*
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

@SuppressLint("NotifyDataSetChanged")
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
    private var actualStopId = 0

    private fun getStopById(id: Int): StopsJSONItem? {
        stopList.let {
            for (i in 0 until stopList.size) {
                if (id == stopList[i].id) {
                    return stopList[i]
                }
            }
        }
        return null
    }

    fun putStopList(stops: StopsJSON) {
        stopList.clear()
        stopList.addAll(stops)
    }

    fun startUpdater(activity: Activity) {
        val thread: Thread = object : Thread() {
            override fun run() {
                try {
                    while (!this.isInterrupted) {
                        sleep(5000)
                        activity.runOnUiThread {
                            notifyDataSetChanged()
                            if (TableItemList.size < 1) {
                                if (activity.MHDTableListConnectInfo?.visibility == View.GONE) {
                                    activity.MHDTableListConnectInfo?.visibility = View.VISIBLE
                                    activity.MHDTableListConnectInfo?.text = activity.getString(R.string.noDepartures)
                                }
                            } else {
                                activity.runOnUiThread {
                                    activity.MHDTableListConnectInfo?.visibility = View.GONE
                                }
                            }
                        }
                    }
                } catch (_: InterruptedException) {
                }
            }
        }
        thread.start()
    }

    fun ioConnect(stopId: Int) {
        actualStopId = stopId
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
            .on(Socket.EVENT_CONNECTING) {
                activity.runOnUiThread {
                    activity.MHDTableListConnectInfo?.visibility = View.VISIBLE
                    activity.MHDTableListConnectInfo?.text = activity.getString(R.string.connecting)
                }
            }
            .on(Socket.EVENT_CONNECT) {
                connected = true
                println("connected")
                activity.runOnUiThread {
                    activity.MHDTableListConnectInfo?.visibility = View.GONE
                }
            }
            .on(Socket.EVENT_DISCONNECT) {
                connected = false
                println("disconnected")
                activity.runOnUiThread {
                    activity.MHDTableListConnectInfo?.visibility = View.VISIBLE
                    activity.MHDTableListConnectInfo?.text = activity.getString(R.string.disconnected)
                }
            }
            .on(Socket.EVENT_RECONNECTING) {
                activity.runOnUiThread {
                    activity.MHDTableListConnectInfo?.visibility = View.VISIBLE
                    activity.MHDTableListConnectInfo?.text = activity.getString(R.string.reconnecting)
                }
            }
            .on(Socket.EVENT_RECONNECT) {
                connected = true
                println("reconnected")
                activity.runOnUiThread {
                    activity.MHDTableListConnectInfo?.visibility = View.GONE
                }
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
                        activity.MHDTableListConnectInfo?.visibility = View.GONE
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
                        activity.MHDTableInfoText?.let { rv ->
                            rv.adapter = adapter
                            ItemTouchHelper(object : ItemTouchHelper.SimpleCallback(0, ItemTouchHelper.RIGHT) {
                                override fun onMove(v: RecyclerView, h: RecyclerView.ViewHolder, t: RecyclerView.ViewHolder) =
                                    false

                                override fun onSwiped(h: RecyclerView.ViewHolder, dir: Int) {
                                    adapter.removeAt(h.adapterPosition)
                                    rv.visibility = View.GONE
                                    dismissed = true
                                }
                            }).attachToRecyclerView(rv)
                            rv.layoutManager = LinearLayoutManager(activity)
                            rv.visibility = View.VISIBLE
                        }
                    } else {
                        activity.MHDTableInfoText?.visibility = View.GONE
                    }
                }
            }
    }

    fun ioDisconnect() {
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
                val forDelete = ArrayList<Int>()
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
                            forDelete.add(i)
                        }
                    }
                }
                for (i in forDelete.size - 1 downTo 0) {
                    TableItemList.removeAt(forDelete[i])
                    if (i == 0 || i == itemCount - 1) notifyDataSetChanged() else notifyItemRemoved(i)
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

            when (position) {
                0 -> MHDTableListLayout.setBackgroundResource(R.drawable.round_shape_top_25)
                (itemCount - 1) -> MHDTableListLayout.setBackgroundResource(R.drawable.round_shape_bottom_25)
                else -> MHDTableListLayout.setBackgroundResource(R.drawable.rectangle_shape)
            }

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
                context.getString(R.string.departureTime)
                    .format(SimpleDateFormat("H:mm", Locale.UK).format(current.departureTime))

            // latest stop & delay
            if (current.type == "online") {
                when {
                    current.delay > 0 -> {
                        when {
                            current.delay > 3 -> {
                                MHDTableListDelayIcon.backgroundTintList =
                                    ContextCompat.getColorStateList(context, R.color.delay3)
                            }
                            current.delay > 1 -> {
                                MHDTableListDelayIcon.backgroundTintList =
                                    ContextCompat.getColorStateList(context, R.color.delay2)
                            }
                            else -> {
                                MHDTableListDelayIcon.backgroundTintList =
                                    ContextCompat.getColorStateList(context, R.color.delay1)
                            }
                        }
                        MHDTableListDelayText.text = context.getString(R.string.delay).format(current.delay)
                    }
                    current.delay < 0 -> {
                        MHDTableListDelayIcon.backgroundTintList = ContextCompat.getColorStateList(context, R.color.inAdvance)
                        MHDTableListDelayText.text =
                            context.getString(R.string.inAdvance).format(current.delay.toString().drop(1))
                    }
                    else -> {
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
                                MHDTableListDetailLayout.collapse()
                            } else {
                                Glide.with(this)
                                    .load(url)
                                    .into(MHDTableListVehicleImg)
                                MHDTableListVehicleImg.visibility = View.VISIBLE
                                MHDTableListDetailLayout.expand()
                            }
                            current.expanded = !current.expanded
                        }
                        if (current.expanded) {
                            Glide.with(this)
                                .load(url)
                                .into(MHDTableListVehicleImg)
                            MHDTableListVehicleImg.visibility = View.VISIBLE
                            MHDTableListDetailLayout.expand(false)
                        }
                        MHDTableListVehicleText.text =
                            context.getString(R.string.vehicleText).format(currentVehicle.type, busID)
                    }
                }
            } else {
                setOnClickListener {
                    if (current.expanded) MHDTableListDetailLayout.collapse() else MHDTableListDetailLayout.expand()
                    current.expanded = !current.expanded
                }
                MHDTableListVehicleText.text = ""
                MHDTableListVehicleImg.visibility = View.GONE
                MHDTableListOnlineInfo.visibility = View.GONE
                MHDTableListLastStop.text = context.getString(R.string.offline)
            }

            setOnLongClickListener {
                getStopById(actualStopId)?.let {
                    tableNotification(it, current.Id, context)
                }
                true
            }

            if (current.expanded) MHDTableListDetailLayout.expand(false) else MHDTableListDetailLayout.collapse(false)
        }
    }

    override fun getItemCount(): Int {
        return TableItemList.size
    }
}