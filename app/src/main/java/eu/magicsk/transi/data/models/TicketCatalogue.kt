package eu.magicsk.transi.data.models

import com.google.gson.annotations.SerializedName
import eu.magicsk.transi.util.isCityLine
import eu.magicsk.transi.util.isTrain
import java.text.NumberFormat
import java.time.Duration
import java.time.Instant
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Currency
import java.util.Locale
import kotlin.math.ceil

/**
 * B-API `ticket/types/{cache_key}`. While `cache_key` is current only [cacheKey] comes back. Gson leaves missing
 * fields null; TicketCatalogueProvider publishes only [isComplete] catalogues.
 */
data class TicketCatalogue(
    val tickets: List<Ticket>? = null,
    val zones: List<Zone>? = null,
    @SerializedName("cache_key") val cacheKey: String? = null,
) {
    data class Ticket(
        @SerializedName("ticket_id") val ticketId: Int,
        @SerializedName("category_id") val categoryId: Int,
        @SerializedName("ticket_type") val ticketType: Int,
        /** Null for network-wide tickets. */
        @SerializedName("zones_count") val zonesCount: Int?,
        val name: String?,
        val price: Double?,
        val currency: String?,
        /** Minutes. */
        @SerializedName("time_duration") val timeDuration: Int,
        val discounted: Boolean,
    ) {
        /** [price] in [currency], like "€1.09". */
        fun formattedPrice(locale: Locale = Locale.getDefault(Locale.Category.FORMAT)): String {
            val price = price ?: return ""
            val format = NumberFormat.getCurrencyInstance(locale)
            val unit = try {
                Currency.getInstance(currency.orEmpty())
            } catch (_: IllegalArgumentException) {
                return "$price ${currency.orEmpty()}".trimEnd()
            }
            format.currency = unit
            // The locale's own currency set the digits: without this yen locales show "€1" and dinar ones "€1.090".
            unit.defaultFractionDigits.takeIf { it >= 0 }?.let {
                format.minimumFractionDigits = it
                format.maximumFractionDigits = it
            }
            return format.format(price)
        }
    }

    data class Zone(
        @SerializedName("zone_id") val zoneId: Int,
        /** Like "100". */
        @SerializedName("zone_name") val zoneName: String?,
        @SerializedName("zone_description") val zoneDescription: String?,
    )

    /** Whether this is a whole catalogue with every field the app reads, as iOS decoding requires. */
    val isComplete: Boolean
        get() = tickets != null && zones != null &&
            tickets.all { it.name != null && it.price != null && it.currency != null } &&
            zones.all { it.zoneName != null }

    /** Full-fare single-journey tickets ("Základné lístky"), network-wide included. */
    val basicSingles: List<Ticket>
        get() = tickets.orEmpty().filter { it.categoryId == 300 && it.ticketType == 2 && !it.discounted }

    /** The cheapest basic single ticket for [zoneCount] zones lasting [minutes]; the network-wide one when none lasts long enough. */
    fun singleTicket(zoneCount: Int, minutes: Int): Ticket? {
        val singles = basicSingles
        return singles.filter { (it.zonesCount ?: Int.MAX_VALUE) >= zoneCount && it.timeDuration >= minutes }
            .minByOrNull { it.price ?: Double.MAX_VALUE }
            ?: singles.firstOrNull { it.zonesCount == null }
    }

    /** Whether [response], fetched with this catalogue's cache key, says it is still current: the same key comes back alone. */
    fun isConfirmed(response: TicketCatalogue): Boolean =
        cacheKey != null && response.cacheKey == cacheKey && response.tickets == null

    /** The journey's fare zone names; R-API journeys carry catalogue zone ids. Null when a zone is not in the catalogue. */
    fun zoneNames(journey: Journey): Set<String>? {
        val zones = zones ?: return null
        val journeyZones = journey.zones?.takeIf { it.isNotEmpty() } ?: return null
        return journeyZones.mapTo(mutableSetOf()) { journeyZone ->
            val zone = zones.firstOrNull {
                if (journey.isRaptor) it.zoneId.toString() == journeyZone else it.zoneName == journeyZone
            } ?: return null
            zone.zoneName ?: return null
        }
    }
}

/** A season pass (PCL) from the "My pass" settings. */
data class SeasonPass(
    val days: Int,
    val validFrom: Instant,
    val zones: Set<String>,
    val networkWide: Boolean = false,
    /** Bought with a bank card through DPB: valid only on DPB city lines in zones 100 and 101. */
    val bankCard: Boolean = false,
) {
    /** Pass days are Bratislava days, wherever the phone is. */
    private val firstDay = validFrom.atZone(BRATISLAVA).toLocalDate()

    val start: Instant get() = firstDay.atStartOfDay(BRATISLAVA).toInstant()

    /** Midnight after the last valid day. */
    val end: Instant get() = firstDay.plusDays(days.toLong()).atStartOfDay(BRATISLAVA).toInstant()

    val lastDay: Instant get() = firstDay.plusDays(days - 1L).atStartOfDay(BRATISLAVA).toInstant()

    /** Why a pass doesn't cover a trip. */
    sealed interface Mismatch {
        /** Bank-card passes are valid only on city lines in zones 100 and 101. */
        data object BankCard : Mismatch

        /** Zones the trip passes through that the pass lacks. */
        data class Zones(val zones: List<String>) : Mismatch

        /**
         * Zones the pass lacks, on a trip it partly covers with a regional bus: those take no ticket for the other
         * zones alongside a pass (PP A.12.9), so the trip needs one of its own.
         */
        data class RegionalBus(val zones: List<String>) : Mismatch
    }

    /** Why the pass isn't valid in every one of [zones] on every [transit] leg; null when it is. */
    fun mismatch(zones: Set<String>, transit: List<Part>): Mismatch? {
        if (bankCard && (!CITY_ZONES.containsAll(zones) || !transit.all { isCityLine(it.routeShortName.orEmpty()) })) {
            return Mismatch.BankCard
        }
        val missing = if (networkWide) emptySet() else zones - this.zones
        return if (missing.isEmpty()) null else Mismatch.Zones(missing.sorted())
    }

    companion object {
        /** Zone 100 is sold only together with 101; passes bought with a bank card are valid only in these. */
        val CITY_ZONES = setOf("100", "101")
        val DURATIONS = listOf(7, 30, 90, 365)

        /** The stored settings; null without a pass. [validFromEpochSecond] counts from 1970, not Apple's 2001. */
        fun fromStored(days: Int, validFromEpochSecond: Long, zones: String, networkWide: Boolean, bankCard: Boolean) =
            if (days <= 0) null
            else SeasonPass(days, Instant.ofEpochSecond(validFromEpochSecond), zoneSet(zones), networkWide, bankCard)

        /** Stored zones are comma-separated names. */
        fun zoneSet(stored: String): Set<String> = stored.split(',').filter { it.isNotEmpty() }.toSet()

        /** [stored] with [zone] switched; switching on 100 adds 101, switching off either city zone drops both. */
        fun toggling(zone: String, stored: String): String {
            val zones = zoneSet(stored).toMutableSet()
            if (zone in zones) {
                zones -= if (zone in CITY_ZONES) CITY_ZONES else setOf(zone)
            } else {
                zones += if (zone == "100") CITY_ZONES else setOf(zone)
            }
            return zones.sorted().joinToString(",")
        }

        /** A Bratislava day in the medium date style of the phone's region format. */
        fun formatDay(day: Instant): String =
            DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).format(day.atZone(BRATISLAVA))
    }
}

sealed interface TicketCoverage {
    /** The pass covers the whole journey. */
    data class Covered(val lastDay: Instant) : TicketCoverage

    /** The pass ends before the journey does; [ended] once its last day is over. */
    data class Expired(val lastDay: Instant, val ended: Boolean, val ticket: TicketCatalogue.Ticket) : TicketCoverage

    /** The pass covers [passZones]; [ticket] covers the other [zones], combined as IDS BK allows (PP A.12.8, A.12.9). */
    data class PartlyCovered(
        val passZones: List<String>,
        val zones: List<String>,
        val ticket: TicketCatalogue.Ticket,
    ) : TicketCoverage

    /** [reason] says why a pass in force for the whole trip doesn't cover it; null without one. */
    data class NotCovered(val ticket: TicketCatalogue.Ticket, val reason: SeasonPass.Mismatch? = null) : TicketCoverage

    /** No catalogue yet, or a journey zone it does not list. */
    data object Unknown : TicketCoverage

    /** Walking only. */
    data object NoTransit : TicketCoverage

    companion object {
        fun of(journey: Journey, pass: SeasonPass?, catalogue: TicketCatalogue?, now: Instant = Instant.now()): TicketCoverage {
            val transit = journey.parts.filter { !it.isWalk }
            val first = transit.firstOrNull() ?: return NoTransit
            val last = transit.last()
            val zones = catalogue?.zoneNames(journey) ?: return Unknown
            // A ticket runs from boarding the first vehicle to leaving the last, like the R-API's ticket_id.
            val start = first.startDeparture
            val end = last.endArrival
            val minutes = ceil(Duration.between(start, end).toMillis() / 60_000.0).toInt()
            var reason: SeasonPass.Mismatch? = null
            if (pass != null && pass.start <= start && end <= pass.end) {
                reason = pass.mismatch(zones, transit) ?: return Covered(pass.lastDay)
                // A single ticket for the other zones, timed for the whole trip. Not with bank-card passes (B.5.2),
                // and only on city lines and trains: not on regional buses (A.12.9) or lines we can't name.
                // Its zones count from where it is first used (B.3.3), so the stops outside the pass must be in
                // exactly the other zones, in one run at the start or the end of the trip.
                val uncovered = zones - pass.zones
                if (!pass.bankCard && uncovered.size < zones.size &&
                    transit.all { isCityLine(it.routeShortName.orEmpty()) || isTrain(it.routeShortName.orEmpty()) }
                ) {
                    val sequence = zoneSequence(transit)
                    if (sequence != null && sequence.toSet() - pass.zones == uncovered &&
                        listOf(sequence, sequence.asReversed()).any { run ->
                            run.dropWhile { it in uncovered }.none { it in uncovered }
                        }
                    ) {
                        catalogue.singleTicket(uncovered.size, minutes)?.let {
                            return PartlyCovered(zones.intersect(pass.zones).sorted(), uncovered.sorted(), it)
                        }
                    }
                }
                // With a regional bus (lines 200 and up) no ticket for the other zones goes with the pass: say why.
                if (reason is SeasonPass.Mismatch.Zones && uncovered.size < zones.size &&
                    transit.any { (it.routeShortName?.toIntOrNull() ?: 0) >= 200 }
                ) {
                    reason = SeasonPass.Mismatch.RegionalBus(reason.zones)
                }
            }
            val rApiTicket = journey.ticketId?.let { id -> catalogue.tickets?.firstOrNull { it.ticketId == id } }
            val ticket = rApiTicket ?: catalogue.singleTicket(zones.size, minutes) ?: return Unknown
            return if (pass != null && pass.start <= start && pass.end < end) {
                Expired(pass.lastDay, ended = pass.end <= now, ticket = ticket)
            } else {
                NotCovered(ticket, reason)
            }
        }

        /**
         * The fare zones of [transit]'s stops in travel order, a zone repeated in a row once; null while a leg's
         * stops or a stop's zone are unknown.
         */
        private fun zoneSequence(transit: List<Part>): List<String>? {
            val sequence = mutableListOf<String>()
            for (part in transit) {
                val stops = part.stops?.takeIf { it.isNotEmpty() } ?: return null
                for (stop in stops) {
                    val zone = stop.zone ?: return null
                    if (zone != sequence.lastOrNull()) sequence += zone
                }
            }
            return sequence
        }
    }
}
