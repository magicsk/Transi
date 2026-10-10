package eu.magicsk.transi.data.models

import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import eu.magicsk.transi.data.remote.responses.idsbk.RaptorResponse
import eu.magicsk.transi.util.mapRaptorJourneys
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Duration
import java.time.Instant
import java.time.LocalDateTime
import java.util.Locale

/** Every case of the iOS `scripts/check-trip-tickets.sh`, against its fixture `ticket-types.json`. */
class TicketCatalogueTest {
    private val fixture: JsonObject = JsonParser.parseString(
        requireNotNull(javaClass.classLoader?.getResource("ticket-types.json")).readText()
    ).asJsonObject
    private val gson = Gson()
    private val catalogue: TicketCatalogue = gson.fromJson(fixture.get("catalogue"), TicketCatalogue::class.java)

    private data class Leg(val line: String?, val from: String, val to: String)

    private fun local(time: String): Instant = LocalDateTime.parse(time.replace(' ', 'T')).atZone(BRATISLAVA).toInstant()

    private fun journey(id: String, zones: List<String>?, legs: List<Leg>, ticketId: Int? = null) = Journey(
        id = id,
        parts = legs.map {
            Part(
                startStopName = null, endStopName = null, startStopCode = null, endStopCode = null,
                startDeparture = local(it.from), endArrival = local(it.to),
                routeType = if (it.line == null) ROUTE_TYPE_WALK else 3, tripHeadsign = null, routeShortName = it.line,
            )
        },
        zones = zones,
        ticketId = ticketId,
    )

    private fun ticket(id: Int) = requireNotNull(catalogue.tickets?.firstOrNull { it.ticketId == id })

    private fun ticketId(coverage: TicketCoverage): Int? = when (coverage) {
        is TicketCoverage.NotCovered -> coverage.ticket.ticketId
        is TicketCoverage.Expired -> coverage.ticket.ticketId
        is TicketCoverage.PartlyCovered -> coverage.ticket.ticketId
        else -> null
    }

    private val cityLegs = listOf(
        Leg("72", "2026-10-05 08:00", "2026-10-05 08:21"),
        Leg(null, "2026-10-05 08:21", "2026-10-05 08:31"),
        Leg("31", "2026-10-05 08:31", "2026-10-05 08:33"),
    )

    /** A 30-day 100+101 pass from 1 October is valid through 30 October. */
    private val cityPass = SeasonPass(30, local("2026-10-01 15:42"), setOf("100", "101"))
    private val october30 = local("2026-10-30 00:00")

    private fun shifted(journey: Journey, day: String): Journey {
        val offset = Duration.between(journey.parts[0].startDeparture, local("$day 08:00"))
        return journey.copy(parts = journey.parts.map { it.copy(startDeparture = it.startDeparture + offset, endArrival = it.endArrival + offset) })
    }

    /** [journey] with stops in [zones] (null: a stop without one), one list per transit leg. */
    private fun stopping(journey: Journey, zones: List<List<String?>>): Journey {
        val parts = journey.parts.toMutableList()
        val transit = parts.indices.filter { !parts[it].isWalk }
        transit.zip(zones).forEach { (index, legZones) ->
            val part = parts[index]
            parts[index] = part.copy(stops = legZones.map {
                PartStop(name = it ?: "-", platform = null, time = part.startDeparture, gps = null, zone = it, isRequestStop = false, stopId = null)
            })
        }
        return journey.copy(parts = parts)
    }

    @Test
    fun catalogueDecodesAndConfirmsTheCache() {
        assertEquals("Catalogue zones", 18, catalogue.zones?.size)
        assertEquals("Catalogue key", "2026-09-30T22:28:22.724Z", catalogue.cacheKey)
        assertEquals("Basic singles skip discounted, day and season tickets",
            listOf(804, 806, 808, 810, 812, 814, 816, 818, 820, 821), catalogue.basicSingles.map { it.ticketId })
        val unchanged = gson.fromJson("""{"cache_key":"2026-09-30T22:28:22.724Z"}""", TicketCatalogue::class.java)
        assertNull("A current cache key comes back alone", unchanged.tickets)
        assertNull(unchanged.zones)
        assertEquals(catalogue.cacheKey, unchanged.cacheKey)
        assertEquals("Cache round-trip", catalogue, gson.fromJson(gson.toJson(catalogue), TicketCatalogue::class.java))
        // Only the cached key coming back alone confirms the cache; error bodies and other keys leave it unconfirmed.
        val errorBody = gson.fromJson("""{"message":"Internal server error"}""", TicketCatalogue::class.java)
        val keyless = catalogue.copy(cacheKey = null)
        val confirmCases = listOf(
            Triple("same key alone", catalogue to unchanged, true),
            Triple("other key alone", catalogue to TicketCatalogue(cacheKey = "2026-10-01T00:00:00.000Z"), false),
            Triple("error body", catalogue to errorBody, false),
            Triple("cache without a key", keyless to errorBody, false),
            Triple("full catalogue", catalogue to catalogue, false),
        )
        for ((name, pair, confirmed) in confirmCases) {
            assertEquals("Confirm cache: $name", confirmed, pair.first.isConfirmed(pair.second))
        }
    }

    @Test
    fun zoneIdsAndNamesMap() {
        val zoneCases = listOf(
            Triple("r-1", listOf("1955", "1953"), setOf("101", "100")),
            Triple("r-2", listOf("64", "1955"), setOf("111", "101")),
            Triple("i-1", listOf("100", "101"), setOf("100", "101")),
            Triple("offline", listOf("101", "621"), setOf("101", "621")),
            Triple("r-3", listOf("1953", "9999"), null),
            Triple("i-2", listOf("1953"), null),
            Triple("offline", listOf("100", "-"), null),
            Triple("i-3", listOf("190"), null),
            Triple("i-4", emptyList(), null),
            Triple("i-5", null, null),
        )
        for ((id, zones, expected) in zoneCases) {
            assertEquals("$id $zones", expected, catalogue.zoneNames(journey(id, zones, cityLegs)))
        }
        assertNull("No catalogue zones", catalogue.copy(zones = null).zoneNames(journey("i-1", listOf("100"), cityLegs)))
    }

    @Test
    fun singleTicketsAreTheCheapestThatLast() {
        // 100 and 101 count as two zones.
        val ticketCases = listOf(
            Triple(1, 10, 804), Triple(2, 30, 804), Triple(2, 31, 806), Triple(2, 60, 806), Triple(2, 61, 808),
            Triple(3, 40, 808), Triple(4, 60, 810), Triple(9, 270, 820), Triple(10, 30, 821), Triple(2, 300, 821),
            Triple(2, 301, 821),
        )
        for ((zones, minutes, expected) in ticketCases) {
            assertEquals("$zones zones, $minutes min", expected, catalogue.singleTicket(zones, minutes)?.ticketId)
        }
        val basic30 = requireNotNull(catalogue.singleTicket(2, 30))
        assertEquals(1.09, basic30.price, 0.0)
        assertEquals(30, basic30.timeDuration)
        assertEquals(2, basic30.zonesCount)
        assertEquals("EUR", basic30.currency)
        assertEquals("Základný", basic30.name)
        assertEquals("€1.09", basic30.formattedPrice(Locale.US))
    }

    @Test
    fun rApiTicketIdsMatchTheDerivation() {
        // The R-API's ticket_id matches the derivation from zones and the time between boarding and alighting,
        // trailing walks excluded; derived journeys reuse it.
        val raptor = gson.fromJson(fixture.get("raptor"), RaptorResponse::class.java).journey.orEmpty()
        val journeys = mapRaptorJourneys(raptor)
        assertEquals(9, journeys.size)
        journeys.forEachIndexed { index, rJourney ->
            val expected = raptor[index].ticket_id
            assertEquals("R-API ticket $index", expected, ticketId(TicketCoverage.of(rJourney, null, catalogue)))
            val derived = rJourney.copy(id = "i-$index", zones = catalogue.zoneNames(rJourney)?.toList(), ticketId = null)
            assertEquals("Derived ticket $index", expected, ticketId(TicketCoverage.of(derived, null, catalogue)))
        }
        // The R-API ticket wins even where the derivation would differ.
        assertEquals("R-API ticket id", 808,
            ticketId(TicketCoverage.of(journey("r-1", listOf("1953"), cityLegs, ticketId = 808), null, catalogue)))
    }

    @Test
    fun passCoverage() {
        assertEquals(local("2026-10-01 00:00"), cityPass.start)
        assertEquals(local("2026-10-31 00:00"), cityPass.end)
        assertEquals(october30, cityPass.lastDay)
        val city = journey("r-1", listOf("1955", "1953"), cityLegs)
        val regional = stopping(journey("i-1", listOf("101", "111"), listOf(Leg("520", "2026-10-05 08:00", "2026-10-05 08:40"))),
            listOf(listOf("101", "111")))
        val lateNight = journey("i-2", listOf("100"), listOf(Leg("N72", "2026-10-30 23:50", "2026-10-31 00:20")))
        // A pass combines with a single ticket for the zones it lacks, on city lines and trains only, when the trip's
        // stops in those zones are one run at its start or end; the R-API ticket is for the whole trip.
        val cityAndTrainLegs = listOf(
            Leg("72", "2026-10-05 08:00", "2026-10-05 08:10"),
            Leg(null, "2026-10-05 08:10", "2026-10-05 08:13"),
            Leg("S20", "2026-10-05 08:13", "2026-10-05 08:30"),
        )
        val cityAndTrainNoStops = journey("i-3", listOf("100", "101", "111"), cityAndTrainLegs, ticketId = 806)
        val cityAndTrain = stopping(cityAndTrainNoStops, listOf(listOf("100", "100", "101"), listOf("101", "111")))
        val trainAndCity = stopping(
            journey("i-3", listOf("111", "101", "100"), listOf(
                Leg("S20", "2026-10-05 08:00", "2026-10-05 08:17"),
                Leg(null, "2026-10-05 08:17", "2026-10-05 08:20"),
                Leg("72", "2026-10-05 08:20", "2026-10-05 08:30"),
            )),
            listOf(listOf("111", "101"), listOf("101", "100")),
        )
        // 111 -> 101 -> 100 -> 101 -> 221: the other zones lie on both sides of the pass zones.
        val acrossTheCity = stopping(
            journey("i-3", listOf("111", "101", "100", "221"), listOf(
                Leg("S20", "2026-10-05 08:00", "2026-10-05 08:15"),
                Leg("72", "2026-10-05 08:20", "2026-10-05 08:40"),
                Leg("R 805", "2026-10-05 08:45", "2026-10-05 09:10"),
            )),
            listOf(listOf("111", "101"), listOf("101", "100", "100", "101"), listOf("101", "221")),
        )
        fun train(line: String, zones: List<String?>) =
            stopping(journey("i-1", listOf("101", "111"), listOf(Leg(line, "2026-10-05 08:00", "2026-10-05 08:40"))), listOf(zones))
        val bankCardCity = cityPass.copy(bankCard = true)
        val zones = { names: List<String> -> SeasonPass.Mismatch.Zones(names) }
        val regionalBus = { names: List<String> -> SeasonPass.Mismatch.RegionalBus(names) }
        val coverageCases: List<Triple<String, Pair<Journey, SeasonPass?>, TicketCoverage>> = listOf(
            Triple("city trip", city to cityPass, TicketCoverage.Covered(october30)),
            Triple("first day", shifted(city, "2026-10-01") to cityPass, TicketCoverage.Covered(october30)),
            Triple("last day", shifted(city, "2026-10-30") to cityPass, TicketCoverage.Covered(october30)),
            Triple("after the pass", shifted(city, "2026-11-01") to cityPass, TicketCoverage.Expired(october30, false, ticket(806))),
            Triple("past midnight of the last day", lateNight to cityPass, TicketCoverage.Expired(october30, false, ticket(804))),
            Triple("before the pass", shifted(city, "2026-09-30") to cityPass, TicketCoverage.NotCovered(ticket(806))),
            Triple("city line, then train beyond the pass zones", cityAndTrain to cityPass,
                TicketCoverage.PartlyCovered(listOf("100", "101"), listOf("111"), ticket(804))),
            Triple("train into the pass zones, then city line", trainAndCity to cityPass,
                TicketCoverage.PartlyCovered(listOf("100", "101"), listOf("111"), ticket(804))),
            Triple("R-API zone ids, stop zone names",
                stopping(journey("r-1", listOf("1953", "1955", "64"), cityAndTrainLegs, ticketId = 806),
                    listOf(listOf("100", "101"), listOf("101", "111"))) to cityPass,
                TicketCoverage.PartlyCovered(listOf("100", "101"), listOf("111"), ticket(804))),
            Triple("train two zones beyond",
                stopping(journey("i-1", listOf("101", "111", "221"), listOf(Leg("R 805", "2026-10-05 08:00", "2026-10-05 08:50"))),
                    listOf(listOf("101", "111", "221"))) to cityPass,
                TicketCoverage.PartlyCovered(listOf("101"), listOf("111", "221"), ticket(806))),
            // Passes in force that don't cover the trip say why: the zones they lack, or the bank-card limits.
            Triple("other zones on both sides", acrossTheCity to cityPass, TicketCoverage.NotCovered(ticket(810), zones(listOf("111", "221")))),
            Triple("other zone on both sides", train("S20", listOf("111", "101", "111")) to cityPass,
                TicketCoverage.NotCovered(ticket(806), zones(listOf("111")))),
            Triple("other zone in the middle", train("S20", listOf("101", "111", "101")) to cityPass,
                TicketCoverage.NotCovered(ticket(806), zones(listOf("111")))),
            Triple("stops in a zone the trip doesn't list",
                stopping(cityAndTrainNoStops, listOf(listOf("100", "101"), listOf("101", "111", "221"))) to cityPass,
                TicketCoverage.NotCovered(ticket(806), zones(listOf("111")))),
            Triple("no stops", cityAndTrainNoStops to cityPass, TicketCoverage.NotCovered(ticket(806), zones(listOf("111")))),
            Triple("one leg without stops", stopping(cityAndTrainNoStops, listOf(listOf("100", "101"))) to cityPass,
                TicketCoverage.NotCovered(ticket(806), zones(listOf("111")))),
            Triple("a stop without a zone", stopping(cityAndTrainNoStops, listOf(listOf("100", "101"), listOf("101", null, "111"))) to cityPass,
                TicketCoverage.NotCovered(ticket(806), zones(listOf("111")))),
            Triple("unmapped line", train("Err", listOf("101", "111")) to cityPass, TicketCoverage.NotCovered(ticket(806), zones(listOf("111")))),
            Triple("unknown line", train("?", listOf("101", "111")) to cityPass, TicketCoverage.NotCovered(ticket(806), zones(listOf("111")))),
            // Regional buses take no ticket for the other zones alongside a pass, so the trip needs one of its own.
            Triple("regional bus beyond the pass zones", regional to cityPass, TicketCoverage.NotCovered(ticket(806), regionalBus(listOf("111")))),
            Triple("train and regional bus",
                stopping(journey("i-1", listOf("101", "111"), listOf(
                    Leg("S20", "2026-10-05 08:00", "2026-10-05 08:20"),
                    Leg("520", "2026-10-05 08:25", "2026-10-05 08:40"),
                )), listOf(listOf("101"), listOf("101", "111"))) to cityPass,
                TicketCoverage.NotCovered(ticket(806), regionalBus(listOf("111")))),
            Triple("regional bus 901, zones on both sides",
                stopping(journey("i-1", listOf("111", "101", "221"), listOf(Leg("901", "2026-10-05 08:00", "2026-10-05 08:40"))),
                    listOf(listOf("111", "101", "221"))) to cityPass,
                TicketCoverage.NotCovered(ticket(808), regionalBus(listOf("111", "221")))),
            Triple("regional bus outside the pass zones",
                journey("i-1", listOf("111", "221"), listOf(Leg("520", "2026-10-05 08:00", "2026-10-05 08:20"))) to cityPass,
                TicketCoverage.NotCovered(ticket(804), zones(listOf("111", "221")))),
            Triple("bank-card pass and train", cityAndTrain to bankCardCity, TicketCoverage.NotCovered(ticket(806), SeasonPass.Mismatch.BankCard)),
            Triple("bank-card pass and city line beyond its zones",
                stopping(journey("i-1", listOf("101", "111"), listOf(Leg("37", "2026-10-05 08:00", "2026-10-05 08:30"))),
                    listOf(listOf("101", "111"))) to bankCardCity,
                TicketCoverage.NotCovered(ticket(804), SeasonPass.Mismatch.BankCard)),
            Triple("train before the pass", shifted(cityAndTrain, "2026-09-30") to cityPass, TicketCoverage.NotCovered(ticket(806))),
            Triple("train outside the pass zones",
                journey("i-1", listOf("111", "221"), listOf(Leg("S20", "2026-10-05 08:00", "2026-10-05 08:20"))) to cityPass,
                TicketCoverage.NotCovered(ticket(804), zones(listOf("111", "221")))),
            Triple("network-wide", regional to SeasonPass(7, local("2026-10-05 00:00"), emptySet(), networkWide = true),
                TicketCoverage.Covered(local("2026-10-11 00:00"))),
            Triple("regional zones", regional to SeasonPass(365, local("2026-01-01 00:00"), setOf("101", "111")),
                TicketCoverage.Covered(local("2026-12-31 00:00"))),
            Triple("no pass", city to null, TicketCoverage.NotCovered(ticket(806))),
            Triple("no catalogue", city to cityPass, TicketCoverage.Unknown),
            Triple("walk only", journey("i-1", null, listOf(Leg(null, "2026-10-05 08:00", "2026-10-05 08:10"))) to cityPass,
                TicketCoverage.NoTransit),
        )
        assertEquals(32, coverageCases.size)
        val today = local("2026-10-04 12:00")
        for ((name, input, expected) in coverageCases) {
            val (journey, pass) = input
            assertEquals(name, expected, TicketCoverage.of(journey, pass, catalogue.takeUnless { name == "no catalogue" }, today))
        }
        // "Expired" only once the last day is over; until midnight the pass merely ends before this trip.
        val expiryCases = listOf(
            "2026-10-30 00:00" to false, "2026-10-30 18:00" to false, "2026-10-30 23:59" to false,
            "2026-10-31 00:00" to true, "2026-10-31 00:10" to true,
        )
        for ((now, ended) in expiryCases) {
            assertEquals("Pass ended at $now", TicketCoverage.Expired(october30, ended, ticket(804)),
                TicketCoverage.of(lateNight, cityPass, catalogue, local(now)))
        }
    }

    @Test
    fun bankCardPassesCoverCityLinesInTheCityZonesOnly() {
        val bankCard = cityPass.copy(bankCard = true, zones = setOf("100", "101", "111"))
        val bankCardCases = listOf(
            Triple("city lines", cityLegs to listOf("100", "101"), true),
            Triple("night and replacement lines", listOf(
                Leg("N72", "2026-10-05 08:00", "2026-10-05 08:10"),
                Leg("X13", "2026-10-05 08:12", "2026-10-05 08:20"),
            ) to listOf("100"), true),
            Triple("regional bus in the city", listOf(Leg("727", "2026-10-05 08:00", "2026-10-05 08:10")) to listOf("101"), false),
            Triple("train", listOf(Leg("S20", "2026-10-05 08:00", "2026-10-05 08:10")) to listOf("100"), false),
            Triple("named train", listOf(Leg("R 805", "2026-10-05 08:00", "2026-10-05 08:10")) to listOf("100"), false),
            Triple("line 200", listOf(Leg("200", "2026-10-05 08:00", "2026-10-05 08:10")) to listOf("101"), false),
            Triple("901 to Hainburg", listOf(Leg("901", "2026-10-05 08:00", "2026-10-05 08:10")) to listOf("100"), false),
            Triple("city line into zone 111", listOf(Leg("37", "2026-10-05 08:00", "2026-10-05 08:30")) to listOf("101", "111"), false),
        )
        for ((name, input, covered) in bankCardCases) {
            val (legs, zones) = input
            val coverage = TicketCoverage.of(journey("i-1", zones, legs), bankCard, catalogue)
            if (covered) {
                assertEquals("Bank card, $name", TicketCoverage.Covered(october30), coverage)
            } else {
                assertTrue("Bank card, $name: $coverage",
                    coverage is TicketCoverage.NotCovered && coverage.reason == SeasonPass.Mismatch.BankCard)
            }
        }
        val cardOff = bankCard.copy(bankCard = false)
        assertEquals("IDS BK passes cover regional lines", TicketCoverage.Covered(october30),
            TicketCoverage.of(journey("i-1", listOf("101"), listOf(Leg("727", "2026-10-05 08:00", "2026-10-05 08:10"))), cardOff, catalogue))
    }

    @Test
    fun storedPassSettings() {
        // Zone 100 never without 101.
        val toggleCases = listOf(
            Triple("100", "", "100,101"), Triple("100", "111", "100,101,111"), Triple("101", "", "101"),
            Triple("111", "101", "101,111"), Triple("100", "100,101,111", "111"), Triple("101", "100,101,111", "111"),
            Triple("111", "100,101,111", "100,101"),
        )
        for ((zone, stored, expected) in toggleCases) {
            assertEquals("Toggle $zone in $stored", expected, SeasonPass.toggling(zone, stored))
        }
        assertNull("No pass", SeasonPass.fromStored(0, 0, "100,101", networkWide = false, bankCard = false))
        assertEquals("Stored pass",
            SeasonPass(90, Instant.EPOCH, setOf("100", "101", "111"), networkWide = true, bankCard = true),
            SeasonPass.fromStored(90, 0, "100,101,111", networkWide = true, bankCard = true))
        // Settings store the Bratislava midnight of the picked day in epoch seconds.
        val stored = local("2026-10-10 00:00").epochSecond
        assertEquals(local("2026-10-10 00:00"), SeasonPass.fromStored(7, stored, "100,101", networkWide = false, bankCard = false)?.start)
    }

    @Test
    fun passDaysAreBratislavaDaysAcrossDaylightSaving() {
        // Summer time ends on 25 October 2026: 30 days from 10 October are 30 × 24 h + 1 h.
        val autumn = SeasonPass(30, local("2026-10-10 23:30"), setOf("100", "101"))
        assertEquals(local("2026-10-10 00:00"), autumn.start)
        assertEquals(local("2026-11-09 00:00"), autumn.end)
        assertEquals(local("2026-11-08 00:00"), autumn.lastDay)
        assertEquals(30 * 24 + 1L, Duration.between(autumn.start, autumn.end).toHours())
        // Summer time starts on 29 March 2026: 7 days from 27 March are 7 × 24 h − 1 h.
        val spring = SeasonPass(7, local("2026-03-27 00:00"), setOf("100", "101"))
        assertEquals(local("2026-04-03 00:00"), spring.end)
        assertEquals(local("2026-04-02 00:00"), spring.lastDay)
        assertEquals(7 * 24 - 1L, Duration.between(spring.start, spring.end).toHours())
        // 23:30 UTC on 9 October is already 10 October in Bratislava.
        assertEquals(local("2026-10-10 00:00"), SeasonPass(1, Instant.parse("2026-10-09T23:30:00Z"), emptySet()).start)
    }

    @Test
    fun idsBkOpensTheAppThenItsStorePage() {
        assertEquals("com.casperise.urbi.online.bid", IdsBkLinks.PACKAGE)
        assertEquals(
            listOf("market://details?id=com.casperise.urbi.online.bid", "https://play.google.com/store/apps/details?id=com.casperise.urbi.online.bid"),
            IdsBkLinks.storeUrls,
        )
    }
}
