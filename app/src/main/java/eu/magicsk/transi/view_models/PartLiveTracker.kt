package eu.magicsk.transi.view_models

import eu.magicsk.transi.data.models.MHDTableData
import eu.magicsk.transi.data.models.Part
import eu.magicsk.transi.data.models.liveConnection
import eu.magicsk.transi.data.models.mergeDepartures
import eu.magicsk.transi.data.models.parseTabs
import eu.magicsk.transi.data.models.regionalDepartures
import eu.magicsk.transi.data.models.replacePlatforms
import eu.magicsk.transi.data.models.stop
import eu.magicsk.transi.data.remote.responses.Stop
import eu.magicsk.transi.repository.DataRepository
import eu.magicsk.transi.repository.IdsbkSession
import io.socket.client.IO
import io.socket.client.Socket
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.json.JSONArray
import java.net.URI
import java.time.Instant
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

/**
 * Follows one ride's vehicle on its boarding stop's live departure board (iOS PartLiveTracker on a
 * SimpleVirtualTableController): its own imhd.sk Socket.IO board, merged with the IDS BK regional departures like the
 * table's. Calls [onUpdate] on [scope]'s (main) thread with the leg's departure there, or null while it is not on the
 * board, once the whole board loaded. Follows the first vehicle matched live, or [busID] from an earlier tracker, and
 * never switches to another one. Call [stop] when done.
 */
class PartLiveTracker private constructor(
    private val part: Part,
    private val stop: Stop,
    private val platform: Int?,
    private val scheduled: Instant,
    private var busID: String?,
    private val repository: DataRepository,
    private val idsbk: IdsbkSession,
    private val scope: CoroutineScope,
    private val onUpdate: (MHDTableData?) -> Unit,
) {
    // forceNew keeps this socket apart from the table's and the departure notification's on the same server.
    private val socket: Socket = IO.socket(URI.create("https://imhd.sk/"), IO.Options().apply {
        forceNew = true
        path = "/rt/sio2"
        reconnection = true
        reconnectionDelay = 1000
        reconnectionDelayMax = 1000
    })
    private var socketRows = emptyList<MHDTableData>()
    private var regionalRows = emptyList<MHDTableData>()
    /** From each connect or drop until the socket's board arrives; null once it has. */
    private var awaitsBoardSince: Long? = System.currentTimeMillis()
    private var regionalJob: Job? = null
    private var stopped = false

    init {
        // Socket.IO calls back on its own thread; the state changes on the main thread.
        socket.on(Socket.EVENT_CONNECT) {
            scope.launch {
                if (stopped) return@launch
                awaitsBoardSince = System.currentTimeMillis()
                startRegionalUpdates()
            }
        }
        socket.on(Socket.EVENT_DISCONNECT) {
            scope.launch {
                socketRows = emptyList()
                regionalRows = emptyList()
                awaitsBoardSince = System.currentTimeMillis()
                regionalJob?.cancel()
            }
        }
        socket.on("cack") { socket.emit("tabStart", JSONArray().put(stop.id).put("*")) }
        socket.on("tabs") { args ->
            val message = (args.firstOrNull() as? JSONArray)?.let { parseTabs(it) } ?: return@on
            scope.launch {
                socketRows = replacePlatforms(socketRows, message, stop.id)
                awaitsBoardSince = null
                publish()
            }
        }
        socket.connect()
    }

    fun stop() {
        stopped = true
        regionalJob?.cancel()
        socket.off()
        socket.io().off()
        socket.disconnect()
    }

    // The regional board is fetched again every 50 s, as on the table.
    private fun startRegionalUpdates() {
        regionalJob?.cancel()
        regionalJob = scope.launch {
            while (isActive) {
                fetchRegionalDepartures()
                delay(50_000)
            }
        }
    }

    private suspend fun fetchRegionalDepartures() {
        if (stop.station_id <= 0) return
        val now = ZonedDateTime.now()
        val response = idsbk.token()?.let { token ->
            repository.getStationDepartures(
                token, stop.station_id, now.toLocalDate().format(DateTimeFormatter.BASIC_ISO_DATE), now.hour * 60 + now.minute,
            ).data
        } ?: return
        regionalRows = regionalDepartures(response.current.orEmpty(), stop, ZonedDateTime.now())
        publish()
    }

    /**
     * Until the socket's board arrives (or 10 s pass, as at stops outside the city with only regional departures), a
     * vehicle missing from it may only be missing from the regional rows, which could take the followed vehicle's place.
     */
    private fun publish() {
        if (stopped) return
        val hasWholeBoard = awaitsBoardSince?.let { System.currentTimeMillis() - it >= 10_000 } ?: true
        if (!hasWholeBoard) return
        val rows = mergeDepartures(socketRows, regionalRows, System.currentTimeMillis() / 1000)
        val connection = part.liveConnection(rows, platform, scheduled, busID)
        if (busID == null && connection?.type == "online") busID = connection.busID
        onUpdate(connection)
    }

    companion object {
        /** Watches [part] at its boarding stop; null for walks and boarding stops missing from [stops]. */
        fun start(
            part: Part,
            stops: List<Stop>,
            busID: String?,
            repository: DataRepository,
            idsbk: IdsbkSession,
            scope: CoroutineScope,
            onUpdate: (MHDTableData?) -> Unit,
        ): PartLiveTracker? {
            if (part.isWalk) return null
            val target = part.stops?.firstOrNull()
            val platformLabel = target?.platform ?: part.startStopCode
            val boardStop = target?.stopId?.let { id -> stops.firstOrNull { it.id == id } }
                ?: stops.stop(part.startStationId, target?.name ?: part.startStopName, platformLabel)
                ?: return null
            val platform = boardStop.platform_labels?.firstOrNull { it.label == platformLabel }?.id?.toIntOrNull()
            return PartLiveTracker(
                part, boardStop, platform, target?.time ?: part.startDeparture, busID, repository, idsbk, scope, onUpdate,
            )
        }
    }
}
