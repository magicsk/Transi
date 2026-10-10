package eu.magicsk.transi.view_models

import android.content.Context
import androidx.core.content.edit
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import eu.magicsk.transi.data.models.MHDTableData
import eu.magicsk.transi.data.models.MHDTableVehicle
import eu.magicsk.transi.data.models.mergeDepartures
import eu.magicsk.transi.data.models.parseTabs
import eu.magicsk.transi.data.models.parseVehicle
import eu.magicsk.transi.data.models.regionalDepartures
import eu.magicsk.transi.data.models.replacePlatforms
import eu.magicsk.transi.data.remote.responses.Stop
import eu.magicsk.transi.repository.DataRepository
import eu.magicsk.transi.repository.IdsbkSession
import eu.magicsk.transi.util.Prefs
import io.socket.client.IO
import io.socket.client.Manager
import io.socket.client.Socket
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.net.URI
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import javax.inject.Inject

/** The live departure board of one stop: the imhd.sk Socket.IO board merged with IDS BK regional departures. */
@HiltViewModel
class TableViewModel @Inject constructor(
    private val repository: DataRepository,
    private val idsbk: IdsbkSession,
    @ApplicationContext context: Context,
) : ViewModel() {
    enum class Status { CONNECTING, CONNECTED, DISCONNECTED }

    val departures = MutableLiveData<List<MHDTableData>>(emptyList())
    val vehicles = MutableLiveData<Map<String, MHDTableVehicle>>(emptyMap())
    val status = MutableLiveData(Status.CONNECTING)
    /** True only after both feeds answered and neither has a departure. */
    val empty = MutableLiveData(false)
    val infoTexts = MutableLiveData<List<String>>(emptyList())
    val unreadInfoCount = MutableLiveData(0)

    private val prefs = Prefs.get(context)
    private val readInfoTexts = prefs.getStringSet(Prefs.READ_INFO_TEXTS, null).orEmpty().toMutableSet()
    // Read by emitStart on the socket thread.
    @Volatile
    private var stop: Stop? = null
    private var socketRows = emptyList<MHDTableData>()
    private var regionalRows = emptyList<MHDTableData>()
    private var loadedStatus = 0
    private var active = false
    // A disconnect asked for by reconnect() shows as connecting, not as a lost connection.
    private var restarting = false
    private var regionalJob: Job? = null

    // forceNew keeps this socket apart from the departure notification's one on the same server.
    private val socket: Socket = IO.socket(URI.create("https://imhd.sk/"), IO.Options().apply {
        forceNew = true
        path = "/rt/sio2"
        reconnection = true
        reconnectionDelay = 1000
        reconnectionDelayMax = 1000
    })

    init {
        // Socket.IO calls back on its own thread; every state change happens on the main thread.
        socket.on(Socket.EVENT_CONNECT) { viewModelScope.launch { startUpdater() } }
        socket.on(Socket.EVENT_DISCONNECT) {
            viewModelScope.launch {
                status.value = if (restarting) Status.CONNECTING else Status.DISCONNECTED
                restarting = false
                regionalJob?.cancel()
            }
        }
        socket.on(Socket.EVENT_CONNECT_ERROR) { viewModelScope.launch { status.value = Status.CONNECTING } }
        socket.io().on(Manager.EVENT_RECONNECT_ATTEMPT) { viewModelScope.launch { status.value = Status.CONNECTING } }
        socket.on("cack") { emitStart() }
        socket.on("tabs") { args ->
            val message = (args.firstOrNull() as? JSONArray)?.let { parseTabs(it) }
            viewModelScope.launch {
                socketRows = if (message == null) emptyList() else replacePlatforms(socketRows, message, stop?.id ?: 0)
                loadedStatus++
                publish()
            }
        }
        socket.on("vInfo") { args ->
            val vehicle = (args.firstOrNull() as? JSONObject)?.let { parseVehicle(it) } ?: return@on
            viewModelScope.launch { vehicles.value = vehicles.value.orEmpty() + (vehicle.issi to vehicle) }
        }
        socket.on("iText") { args ->
            val texts = (args.firstOrNull() as? JSONArray)?.let { array ->
                (0 until array.length()).map { array.optString(it) }.filter { it.isNotEmpty() }
            } ?: return@on
            viewModelScope.launch {
                infoTexts.value = texts
                unreadInfoCount.value = texts.count { it !in readInfoTexts }
            }
        }
    }

    /** The table is on screen: keep the board connected. */
    fun connect() {
        active = true
        if (stop == null) return
        if (socket.connected()) {
            emitStart()
            startUpdater()
        } else {
            status.value = Status.CONNECTING
            socket.connect()
        }
    }

    /** The table left the screen. */
    fun disconnect() {
        active = false
        socket.disconnect()
    }

    fun reconnect() {
        if (!active || stop == null) return
        restarting = socket.connected()
        status.value = Status.CONNECTING
        socket.disconnect()
        socket.connect()
    }

    fun setStop(newStop: Stop?) {
        val changed = newStop?.id != stop?.id
        stop = newStop
        if (!changed) return
        socketRows = emptyList()
        regionalRows = emptyList()
        departures.value = emptyList()
        vehicles.value = emptyMap()
        empty.value = false
        if (active && newStop != null) {
            if (socket.connected()) reconnect() else connect()
        }
    }

    fun markInfoTextsRead() {
        readInfoTexts.addAll(infoTexts.value.orEmpty())
        // A copy: the preferences keep the set they are given and skip writing an equal one.
        prefs.edit { putStringSet(Prefs.READ_INFO_TEXTS, readInfoTexts.toSet()) }
        unreadInfoCount.value = 0
    }

    private fun emitStart() {
        socket.emit("tabStart", JSONArray().put(stop?.id ?: 0).put("*"))
        socket.emit("infoStart")
    }

    // iOS startUpdater: count both feeds again and refetch the regional board every 50 s.
    private fun startUpdater() {
        loadedStatus = 0
        regionalJob?.cancel()
        regionalJob = viewModelScope.launch {
            while (isActive) {
                fetchRegionalDepartures()
                delay(50_000)
            }
        }
    }

    private suspend fun fetchRegionalDepartures() {
        val stop = stop
        val stationId = stop?.station_id ?: 0
        if (stop == null || stationId <= 0) {
            loadedStatus++
            return
        }
        val response = idsbk.token()?.let { token ->
            // The device's date and minutes since midnight, as iOS sends them.
            val now = ZonedDateTime.now()
            repository.getStationDepartures(
                token, stationId, now.toLocalDate().format(DateTimeFormatter.BASIC_ISO_DATE), now.hour * 60 + now.minute
            ).data
        }
        if (stop.id != this.stop?.id) return
        if (response != null) regionalRows = regionalDepartures(response.current.orEmpty(), stop, ZonedDateTime.now())
        loadedStatus++
        if (response != null) publish()
    }

    private fun publish() {
        val rows = mergeDepartures(socketRows, regionalRows, System.currentTimeMillis() / 1000)
        departures.value = rows
        if (socket.connected()) {
            status.value = Status.CONNECTED
            if (loadedStatus > 1) empty.value = rows.isEmpty() && socketRows.isEmpty() && regionalRows.isEmpty()
        }
    }

    override fun onCleared() {
        socket.off()
        socket.io().off()
        socket.disconnect()
    }
}
