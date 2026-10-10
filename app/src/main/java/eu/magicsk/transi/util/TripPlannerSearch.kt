package eu.magicsk.transi.util

import eu.magicsk.transi.data.models.ArrivalDeparture
import eu.magicsk.transi.data.models.BRATISLAVA
import eu.magicsk.transi.data.models.Journey
import eu.magicsk.transi.data.remote.responses.Stop
import eu.magicsk.transi.data.remote.responses.idsbk.RaptorRequest
import java.time.Instant
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit

private const val SEARCH_HOURS = 2
private const val RETRY_SEARCH_HOURS = 8
private val cepoDateFormat = DateTimeFormatter.ofPattern("yyyy-MM-dd")
private val clockFormat = DateTimeFormatter.ofPattern("HH:mm")

enum class FetchSource { INITIAL, MORE_RAPTOR, MORE_IMHD }

/** Where the next page of each source starts. */
data class PagingDates(val raptor: Instant?, val imhd: Instant?)

/** "Actual position" from the stop pickers (id 0 here, -1 on iOS). */
val Stop.isActualLocation get() = id <= 0

/** The first real stop: the stops list is sorted by distance once the location is known. */
fun List<Stop>.nearest() = firstOrNull { it.id > 0 }

fun raptorRequest(
    fromStationId: Int,
    toStationId: Int,
    date: Instant,
    arrivalDeparture: ArrivalDeparture,
    maxWalkDuration: Int,
    maxTransfers: Int,
): RaptorRequest {
    val iso = DateTimeFormatter.ISO_INSTANT.format(date.truncatedTo(ChronoUnit.SECONDS))
    val departure = arrivalDeparture == ArrivalDeparture.DEPARTURE
    return RaptorRequest(
        max_walk_duration = maxWalkDuration,
        max_transfers = maxTransfers,
        search_from_hours = if (departure) null else SEARCH_HOURS,
        search_to_hours = if (departure) SEARCH_HOURS else null,
        search_from = if (departure) iso else null,
        search_to = if (departure) null else iso,
        from_station_id = listOf(fromStationId),
        to_station_id = listOf(toStationId),
    )
}

/** The one retry after an empty R-API answer: the same search over 8 hours; null when nothing would change. */
fun RaptorRequest.widened(): RaptorRequest? = copy(
    search_from_hours = if (search_from_hours == SEARCH_HOURS) RETRY_SEARCH_HOURS else search_from_hours,
    search_to_hours = if (search_to_hours == SEARCH_HOURS) RETRY_SEARCH_HOURS else search_to_hours,
).takeIf { it != this }

/** I-API `pd`: the Bratislava date. */
fun cepoDate(date: Instant): String = date.atZone(BRATISLAVA).format(cepoDateFormat)

/** The Bratislava HH:mm: the I-API `pt` and the planner's times on screen. */
fun clock(date: Instant): String = date.atZone(BRATISLAVA).format(clockFormat)

/** Both lists without repeats ([Journey.sameRidesKey]; the first one stays), soonest first. */
fun mergeJourneys(current: List<Journey>, new: List<Journey>): List<Journey> =
    (current + new).distinctBy { it.sameRidesKey }.sortedBy { it.parts.firstOrNull()?.startDeparture ?: Instant.MAX }

/**
 * The paging dates after a response, as iOS: the R-API moves on 2 hours per answer; the I-API 1 hour when it found
 * nothing, else to a minute after the departure of its last journey.
 */
fun nextPagingDates(
    source: FetchSource,
    dates: PagingDates,
    raptorAnswered: Boolean,
    imhdAnswered: Boolean,
    imhdJourneys: List<Journey>,
) = PagingDates(
    raptor = if (raptorAnswered || source == FetchSource.MORE_RAPTOR) dates.raptor?.plusSeconds(7200) else dates.raptor,
    imhd = if ((imhdAnswered || source == FetchSource.MORE_IMHD) && imhdJourneys.isEmpty()) {
        dates.imhd?.plusSeconds(3600)
    } else {
        imhdJourneys.lastOrNull()?.parts?.firstOrNull()?.startDeparture?.plusSeconds(60) ?: dates.imhd
    },
)

/** Paging dates for cached journeys: a minute after the last one of each source. */
fun pagingDatesFor(journeys: List<Journey>) = PagingDates(
    raptor = journeys.lastOrNull { it.isRaptor }?.parts?.firstOrNull()?.startDeparture?.plusSeconds(60),
    imhd = journeys.lastOrNull { it.isImhd }?.parts?.firstOrNull()?.startDeparture?.plusSeconds(60),
)
