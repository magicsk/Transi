@file:Suppress("DEPRECATION")

package eu.magicsk.transi.adapters

import android.annotation.SuppressLint
import android.app.Activity
import android.graphics.PorterDuff
import android.graphics.drawable.AnimatedVectorDrawable
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.content.res.ResourcesCompat
import androidx.core.view.isVisible
import androidx.core.view.setPadding
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import eu.magicsk.transi.R
import eu.magicsk.transi.data.models.MHDTable
import eu.magicsk.transi.data.models.MHDTableData
import eu.magicsk.transi.data.remote.responses.StopsJSON
import eu.magicsk.transi.data.remote.responses.StopsJSONItem
import eu.magicsk.transi.databinding.TableListItemBinding
import eu.magicsk.transi.util.*
import io.socket.client.IO
import io.socket.client.Socket
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.net.URI
import java.text.SimpleDateFormat
import java.util.*

@SuppressLint("NotifyDataSetChanged")
class MHDTableAdapter : RecyclerView.Adapter<MHDTableAdapter.MHDTableViewHolder>() {
    class MHDTableViewHolder(val binding: TableListItemBinding) : RecyclerView.ViewHolder(binding.root)

    private var _binding: TableListItemBinding? = null
    private val binding get() = _binding!!
    private val uri: URI = URI.create("https://imhd.sk/")
    private val options = IO.Options()
    private val socket: Socket = IO.socket(uri, options)
    private val tabArgs = JSONArray()
    var connected = false
    var dismissed = false
    private var stopList: StopsJSON = StopsJSON()
    private var actualStopId = 0
    private val mhdTable = MHDTable()
    private var updateTimeStamp = 0L

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
                        sleep(5100)
                        activity.runOnUiThread {
                            if (System.currentTimeMillis() - updateTimeStamp > 15000) {
                                notifyDataSetChanged()
                                updateTimeStamp = System.currentTimeMillis()
                            }
                            val connectInfo = activity.findViewById<TextView>(R.id.MHDTableListConnectInfo)
                            if (mhdTable.sortedTabs.size < 1) {
                                if (connectInfo?.visibility == View.GONE) {
                                    connectInfo.visibility = View.VISIBLE
                                    connectInfo.text = activity.getString(R.string.noDepartures)
                                }
                            } else {
                                activity.runOnUiThread {
                                    connectInfo?.visibility = View.GONE
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
        val connectInfo = activity.findViewById<TextView>(R.id.MHDTableListConnectInfo)
        socket
            .on(Socket.EVENT_CONNECTING) {
                activity.runOnUiThread {
                    connectInfo?.visibility = View.VISIBLE
                    connectInfo?.text = activity.getString(R.string.connecting)
                }
            }
            .on(Socket.EVENT_CONNECT) {
                connected = true
                println("connected")
                activity.runOnUiThread {
                    connectInfo?.visibility = View.GONE
                }
            }
            .on(Socket.EVENT_DISCONNECT) {
                connected = false
                println("disconnected")
                activity.runOnUiThread {
                    connectInfo?.visibility = View.VISIBLE
                    connectInfo?.text = activity.getString(R.string.disconnected)
                }
            }
            .on(Socket.EVENT_RECONNECTING) {
                activity.runOnUiThread {
                    connectInfo?.visibility = View.VISIBLE
                    connectInfo?.text = activity.getString(R.string.reconnecting)
                }
            }
            .on(Socket.EVENT_RECONNECT) {
                connected = true
                println("reconnected")
                activity.runOnUiThread {
                    connectInfo?.visibility = View.GONE
                }
                socket
                    .emit("tabStart", tabArgs)
                    .emit("infoStart")
            }
            .on("vInfo") {
                mhdTable.addVehicleInfo(JSONObject(it[0].toString()))
            }
            .on("tabs") {
                updateTimeStamp = System.currentTimeMillis()
                activity.runOnUiThread {
                    connectInfo?.visibility = View.GONE
                    addItems(mhdTable.addTabs(JSONObject(it[0].toString())))
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
                    if (info != "" && infos != "") infos = "$infos\n\n$info"
                    else if (info != "") infos = info
                }
                activity.runOnUiThread {
                    if (infos != "" && !dismissed) {
                        val adapter = TableInfoAdapter(mutableListOf(infos))
                        activity.findViewById<RecyclerView>(R.id.MHDTableInfoText)?.let { rv ->
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
                        activity.findViewById<RecyclerView>(R.id.MHDTableInfoText)?.visibility = View.GONE
                    }
                }
            }
    }

    fun ioDisconnect() {
        socket.close()
        val size = mhdTable.sortedTabs.size
        clearList()
        notifyItemRangeRemoved(0, size)
    }

    private fun clearList() {
        mhdTable.sortedTabs.clear()
        notifyItemRangeRemoved(0, itemCount)
    }

    private fun addItems(items: MutableList<MHDTableData>) {
        val newData = mutableListOf<MHDTableData>()
        val platforms = mutableSetOf<String>()
        val toRemove = mutableSetOf<Int>()
        newData.addAll(items)
        newData.forEach { platforms.add(it.platform) }

        fun findInList(item: MHDTableData): MHDTableData? {
            newData.forEach {
                if (it.Id == item.Id) return it
            }
            return null
        }

        // update
        mhdTable.sortedTabs.forEachIndexed { index, existing ->
            if (platforms.contains(existing.platform)) {
                val updated = findInList(existing)
                if (updated != null) {
                    updated.expanded = existing.expanded
                    mhdTable.sortedTabs[index] = updated
                    notifyItemChanged(index)
                    newData.remove(updated)
                } else {
                    toRemove.add(index)
                }
            }
        }

        // remove
        toRemove.forEach {
            mhdTable.sortedTabs.removeAt(it)
            notifyItemRemoved(it)
        }

        // add new
        val sorted = mutableListOf<MHDTableData>()
        sorted.addAll(mhdTable.sortedTabs)
        sorted.addAll(newData)
        sorted.sortBy { x -> x.departureTime }
        sorted.forEachIndexed { index, item ->
            if (!mhdTable.sortedTabs.contains(item)) {
                mhdTable.sortedTabs.add(index, item)
                notifyItemInserted(index)
            }
        }
        if (sorted != mhdTable.sortedTabs) {
            mhdTable.sortedTabs.sortBy { x -> x.departureTime }
            notifyDataSetChanged()
        }


//        if (items.isNotEmpty()) {
//            val tempList = mutableListOf<MHDTableData>()
//            if (mhdTable.sortedTabs.isNotEmpty()) {
//                val platform = items[0].platform
//                val forDelete = ArrayList<Int>()
//                for (i in 0 until itemCount) {
//                    if (mhdTable.sortedTabs[i].platform == platform) {
//                        var found = false
//                        for (j in 0 until items.size) {
//                            if (mhdTable.sortedTabs[i].Id == items[j].Id) {
//                                found = true
//                                items[j].expanded = mhdTable.sortedTabs[i].expanded
//                                mhdTable.sortedTabs[i] = items[j]
//                                items.removeAt(j)
//                                notifyItemChanged(i)
//                                break
//                            }
//                        }
//                        if (!found) {
//                            forDelete.add(i)
//                        }
//                    }
//                }
//                for (i in forDelete.size - 1 downTo 0) {
//                    mhdTable.sortedTabs.removeAt(forDelete[i])
//                    if (i == 0 || i == itemCount - 1) notifyDataSetChanged() else notifyItemRemoved(i)
//                }
//            }
//            tempList.addAll(mhdTable.sortedTabs)
//            mhdTable.sortedTabs.addAll(items)
//            mhdTable.sortedTabs.sortBy { x -> x.departureTime }
//            for (i in mhdTable.sortedTabs.size - 1 downTo tempList.size) {
//                notifyItemInserted(i)
//            }
//            for (i in 0 until tempList.size) {
//                if (mhdTable.sortedTabs[i] != tempList[i]) notifyItemChanged(i)
//            }
//        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): MHDTableViewHolder {
        _binding = TableListItemBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return MHDTableViewHolder(binding)
    }

    override fun onBindViewHolder(holder: MHDTableViewHolder, position: Int) {
        val current = mhdTable.sortedTabs[position]
        val mins =
            (current.departureTime - System.currentTimeMillis()).toDouble() / 60000
        val hours = SimpleDateFormat("H:mm", Locale.UK).format(current.departureTime)
        val o = if (current.type == "online") "" else "~"
        val time =
            when {
                mins > 60 -> "${o}$hours"
                mins < 0 -> "${o}now"
                mins < 1 -> "${o}<1 min"
                else -> "${o}${mins.toInt()} min"
            }
        val rounded =
            try {
                current.line.contains("S") || current.line.toInt() < 10
            } catch (e: NumberFormatException) {
                false
            }

        holder.binding.apply {
            val context = root.context
            val resources = root.resources
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
            MHDTableListHeadsign.isSelected = true

            // time left for departure
            if (time == "now" || time == "~now") {
                MHDTableListTime.text = ""
                MHDTableListTime.textSize = 12f
                MHDTableListTime.background = ResourcesCompat.getDrawable(
                    resources,
                    if (time == "now") R.drawable.ic_filled_now else R.drawable.ic_outline_now,
                    context?.theme
                )
                (MHDTableListTime.background as AnimatedVectorDrawable).start()
            } else {
                MHDTableListTime.text = time
                MHDTableListTime.textSize = 18f
                MHDTableListTime.background = null
            }

            // platform label
            stopList.forEach { item ->
                val currentStopId = tabArgs.getInt(0)
                if (currentStopId == item.id) {
                    if (item.platform_labels != null) {
                        item.platform_labels.forEach { platformLabel ->
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
                        MHDTableListPlatform.text = ""
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


                for (i in 0 until mhdTable.vehicleInfo.size) {
                    if (mhdTable.vehicleInfo[i].issi == current.busID) {
                        val currentVehicle = mhdTable.vehicleInfo[i]
                        val busID = currentVehicle.train ?: current.busID.drop(2)
                        val v = if (currentVehicle.imgt == 0) "vm" else "vs"
                        val url = "https://imhd.sk/ba/media/$v/${currentVehicle.img.toString().padStart(8, '0')}/$busID"
                        root.setOnClickListener {
                            if (current.expanded) {
                                MHDTableListDetailLayout.collapse()
                            } else {
                                Glide.with(this.root)
                                    .load(url)
                                    .into(MHDTableListVehicleImg)
                                MHDTableListVehicleImg.visibility = View.VISIBLE
                                MHDTableListDetailLayout.expand()
                            }
                            current.expanded = !current.expanded
                        }
                        if (current.expanded) {
                            Glide.with(this.root)
                                .load(url)
                                .into(MHDTableListVehicleImg)
                            MHDTableListVehicleImg.visibility = View.VISIBLE
                            MHDTableListDetailLayout.expand(false)
                        }
                        MHDTableListVehicleText.text =
                            context.getString(R.string.vehicleText).format(currentVehicle.type, busID)
                        MHDTableListAC.isVisible = currentVehicle.ac == 1
                    }
                }
            } else {
                root.setOnClickListener {
                    if (current.expanded) MHDTableListDetailLayout.collapse() else MHDTableListDetailLayout.expand()
                    current.expanded = !current.expanded
                }
                MHDTableListVehicleText.text = ""
                MHDTableListVehicleImg.visibility = View.GONE
                MHDTableListOnlineInfo.visibility = View.GONE
                MHDTableListAC.visibility = View.GONE
                MHDTableListLastStop.text = context.getString(R.string.offline)
            }

            root.setOnLongClickListener {
                getStopById(actualStopId)?.let {
                    tableNotification(it, current.Id, context)
                }
                true
            }

            if (current.expanded) MHDTableListDetailLayout.expand(false) else MHDTableListDetailLayout.collapse(false)
        }
    }

    override fun getItemCount(): Int {
        return mhdTable.sortedTabs.size
    }
}