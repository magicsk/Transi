package eu.magicsk.transi.repository

import eu.magicsk.transi.data.models.MHDTableData
import eu.magicsk.transi.data.models.MHDTableVehicle
import eu.magicsk.transi.data.models.mergeDepartures
import eu.magicsk.transi.data.models.parseTabs
import eu.magicsk.transi.data.models.parseVehicle
import eu.magicsk.transi.data.models.regionalDepartures
import eu.magicsk.transi.data.models.replacePlatforms
import eu.magicsk.transi.data.remote.responses.Stop
import io.socket.client.IO
import io.socket.client.Manager
import io.socket.client.Socket
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.net.URI
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

/**
 * The live departure board of one stop (iOS SimpleVirtualTableController): the imhd.sk Socket.IO board merged with
 * IDS BK regional departures. Used by the table and by followed departures. Socket.IO calls back on its own thread;
 * every state change and callback happens in [scope], which must run on the main thread.
 */
class LiveBoard(
    private val repository: DataRepository,
    private val idsbk: IdsbkSession,
    private val scope: CoroutineScope,
) {
    enum class Status { CONNECTING, CONNECTED, DISCONNECTED }

    var onStatus: (Status) -> Unit = {}
    /** The merged board, by expected time. */
    var onDepartures: (List<MHDTableData>) -> Unit = {}
    /** True only after both feeds answered and neither has a departure. */
    var onEmpty: (Boolean) -> Unit = {}
    var onVehicles: (Map<String, MHDTableVehicle>) -> Unit = {}
    var onInfoTexts: (List<String>) -> Unit = {}

    var departures = emptyList<MHDTableData>()
        private set
    /** Vehicles by `issi` (the departure's busID). */
    var vehicles = emptyMap<String, MHDTableVehicle>()
        private set

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
    private val wholeBoard = WholeBoard()

    // forceNew gives every board its own connection to the same server: the table's and each followed stop's.
    private val socket: Socket = IO.socket(URI.create("https://imhd.sk/"), IO.Options().apply {
        forceNew = true
        path = "/rt/sio2"
        reconnection = true
        reconnectionDelay = 1000
        reconnectionDelayMax = 1000
    })

    init {
        socket.on(Socket.EVENT_CONNECT) {
            scope.launch {
                wholeBoard.onConnect(System.currentTimeMillis())
                startUpdater()
            }
        }
        socket.on(Socket.EVENT_DISCONNECT) {
            scope.launch {
                wholeBoard.onDisconnect()
                onStatus(if (restarting) Status.CONNECTING else Status.DISCONNECTED)
                restarting = false
                regionalJob?.cancel()
            }
        }
        socket.on(Socket.EVENT_CONNECT_ERROR) { scope.launch { onStatus(Status.CONNECTING) } }
        socket.io().on(Manager.EVENT_RECONNECT_ATTEMPT) { scope.launch { onStatus(Status.CONNECTING) } }
        socket.on("cack") { emitStart() }
        socket.on("tabs") { args ->
            val message = (args.firstOrNull() as? JSONArray)?.let { parseTabs(it) }
            scope.launch {
                val stopId = stop?.id ?: 0
                socketRows = if (message == null) emptyList() else replacePlatforms(socketRows, message, stopId)
                wholeBoard.onSocketBoard(message?.get(stopId)?.keys.orEmpty(), System.currentTimeMillis())
                loadedStatus++
                publish()
            }
        }
        socket.on("vInfo") { args ->
            val vehicle = (args.firstOrNull() as? JSONObject)?.let { parseVehicle(it) } ?: return@on
            scope.launch {
                vehicles = vehicles + (vehicle.issi to vehicle)
                onVehicles(vehicles)
            }
        }
        socket.on("iText") { args ->
            val texts = (args.firstOrNull() as? JSONArray)?.let { array ->
                (0 until array.length()).map { array.optString(it) }.filter { it.isNotEmpty() }
            } ?: return@on
            scope.launch { onInfoTexts(texts) }
        }
    }

    /** Whether a departure of [platform] missing from [departures] has left; see [WholeBoard]. */
    fun hasWholeBoard(platform: Int): Boolean = wholeBoard.has(platform, System.currentTimeMillis())

    /** Keep the board connected. */
    fun connect() {
        active = true
        if (stop == null) return
        if (socket.connected()) {
            emitStart()
            startUpdater()
        } else {
            onStatus(Status.CONNECTING)
            socket.connect()
        }
    }

    fun disconnect() {
        active = false
        socket.disconnect()
    }

    fun reconnect() {
        if (!active || stop == null) return
        restarting = socket.connected()
        onStatus(Status.CONNECTING)
        socket.disconnect()
        socket.connect()
    }

    fun setStop(newStop: Stop?) {
        val changed = newStop?.id != stop?.id
        stop = newStop
        if (!changed) return
        socketRows = emptyList()
        regionalRows = emptyList()
        departures = emptyList()
        vehicles = emptyMap()
        onDepartures(departures)
        onVehicles(vehicles)
        onEmpty(false)
        if (active && newStop != null) {
            if (socket.connected()) reconnect() else connect()
        }
    }

    /** Disconnects for good; the board is unusable afterwards. */
    fun close() {
        regionalJob?.cancel()
        socket.off()
        socket.io().off()
        socket.disconnect()
    }

    private fun emitStart() {
        socket.emit("tabStart", JSONArray().put(stop?.id ?: 0).put("*"))
        socket.emit("infoStart")
    }

    // iOS startUpdater: count both feeds again and refetch the regional board every 50 s.
    private fun startUpdater() {
        loadedStatus = 0
        regionalJob?.cancel()
        regionalJob = scope.launch {
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
        departures = mergeDepartures(socketRows, regionalRows, System.currentTimeMillis() / 1000)
        onDepartures(departures)
        if (socket.connected()) {
            onStatus(Status.CONNECTED)
            if (loadedStatus > 1) onEmpty(departures.isEmpty() && socketRows.isEmpty() && regionalRows.isEmpty())
        }
    }
}

/**
 * Whether a board shows every departure of a platform, so a followed departure missing from it has left (iOS
 * hasWholeBoard). Right after connecting the socket sends its board a few platforms per message, so a platform counts
 * once a message listed it. Platforms only the regional feed serves count 10 s after the socket's first message, and
 * every platform 10 s after connecting when the socket sends no board (stops with only regional departures).
 * Nothing counts while disconnected: until the socket's board is back, the regional feed alone never ends anything.
 */
class WholeBoard {
    private var connectedAt: Long? = null
    private var firstBoardAt: Long? = null
    private val platforms = mutableSetOf<Int>()

    fun onConnect(now: Long) {
        connectedAt = now
        firstBoardAt = null
        platforms.clear()
    }

    fun onDisconnect() {
        connectedAt = null
        firstBoardAt = null
        platforms.clear()
    }

    fun onSocketBoard(listed: Set<Int>, now: Long) {
        if (connectedAt == null) return
        if (firstBoardAt == null) firstBoardAt = now
        platforms += listed
    }

    fun has(platform: Int, now: Long): Boolean {
        val since = firstBoardAt ?: connectedAt ?: return false
        return platform in platforms || now - since >= WAIT_MILLIS
    }

    private companion object {
        const val WAIT_MILLIS = 10_000L
    }
}
