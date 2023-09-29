package eu.magicsk.transi.util

import android.content.Context
import android.os.Parcelable
import androidx.fragment.app.FragmentManager
import eu.magicsk.transi.R
import kotlinx.parcelize.Parcelize
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.io.Serializable
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

@Parcelize
data class Trip(
    val date: String,
    val departure: String,
    val arrival: String,
    val duration: String,
    val parts: MutableList<TripPart>,
) : Parcelable, Serializable

@Parcelize
data class TripPart(
    val type: Int,
    val line: String?,
    val headsign: String?,
    val departure: TripDA?,
    val arrival: TripDA?,
    val duration: String?,
    val stops: MutableList<TripStop>,
    val message: String?
) : Parcelable, Serializable

@Parcelize
data class TripStop(
    val time: String,
    val name: String,
    val zone: String,
    val platform: String?,
    val request: Boolean
) : Parcelable, Serializable

@Parcelize
data class TripDA(
    val time: String,
    val stop: TripStop,
) : Parcelable, Serializable

fun tripPlannerJsonParser(data: JSONObject, fragmentManager: FragmentManager, context: Context): MutableList<Trip>? {
    val timeFormat = DateTimeFormatter.ofPattern("HH:mm")
    val inputFormat = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
    try {
        var journeys = JSONArray()
        try {
            journeys = data.getJSONArray("journeys")
        } catch (e: JSONException) {
            val keys = data.getJSONObject("journeys").keys()
            while (keys.hasNext()) {
                journeys.put(data.getJSONObject("journeys").getJSONObject(keys.next()))
            }
        }
        if (journeys.length() > 0) {
            val trips = mutableListOf<Trip>()
            for (i in 0 until journeys.length()) {
                val current = journeys.getJSONObject(i)
                val departure =
                    LocalDateTime.parse(current.getJSONObject("departure").getString("date").split(".")[0], inputFormat)
                val arrival = LocalDateTime.parse(current.getJSONObject("arrival").getString("date").split(".")[0], inputFormat)
                val duration = LocalDateTime.ofEpochSecond(
                    arrival.toEpochSecond(ZoneOffset.UTC) - departure.toEpochSecond(ZoneOffset.UTC),
                    0,
                    ZoneOffset.UTC
                )
                val parts = current.getJSONArray("parts")

                val trip = Trip(
                    date = current.getJSONObject("departure").getString("date").split(" ")[0],
                    departure = departure.format(timeFormat),
                    arrival = arrival.format(timeFormat),
                    duration = if (duration.hour > 0) context.getString(R.string.tripDurationH)
                        .format(duration.hour, duration.minute) else context.getString(R.string.tripDuration)
                        .format(duration.minute),
                    parts = mutableListOf()
                )

                val tripLines = current.getJSONArray("lines")


                for (j in 0 until parts.length()) {
                    val currentPart = parts.getJSONObject(j)
                    val partDeparture =
                        LocalDateTime.parse(currentPart.getJSONObject("departure").getString("date").split(".")[0], inputFormat)
                    val partArrival =
                        LocalDateTime.parse(currentPart.getJSONObject("arrival").getString("date").split(".")[0], inputFormat)
                    val partDurationRaw = LocalDateTime.ofEpochSecond(
                        partArrival.toEpochSecond(ZoneOffset.UTC) - partDeparture.toEpochSecond(ZoneOffset.UTC),
                        0,
                        ZoneOffset.UTC
                    )
                    val partDuration =
                        if (partDurationRaw.minute > 59) "${partDurationRaw.hour} h ${partDurationRaw.minute}" else partDurationRaw.minute.toString()
                    val stops = currentPart.getJSONArray("stops")
                    val departureStopJson = stops.getJSONObject(0)
                    val arrivalStopJson = stops.getJSONObject(stops.size())
                    fun parsedStop(stopJson: JSONObject, time: LocalDateTime): TripStop {
                        return TripStop(
                            time = time.format(timeFormat),
                            name = stopJson.getString("name"),
                            zone = try {
                                stopJson.getJSONObject("fare_zones").keys().next()
                            } catch (e: JSONException) {
                                "none"
                            },
                            platform = try {
                                stopJson.getString("label")
                            } catch (e: JSONException) {
                                null
                            },
                            request = try {
                                stopJson.getString("request_stop") == "1"
                            } catch (e: JSONException) {
                                false
                            }
                        )
                    }

                    val arrivalStop = parsedStop(arrivalStopJson, partArrival)
                    val departureStop = parsedStop(departureStopJson, partDeparture)
                    val type = currentPart.getString("type") == "\uD83D\uDEB6"
                    val arrivalPlace = if (arrivalStop.name == departureStop.name && j + 1 < parts.length()) {
                        try {
                            "to platform ${
                                parts.getJSONObject(j + 1).getJSONArray("stops").getJSONObject(0).getString("label")
                            }"
                        } catch (e: JSONException) {
                            "transfer between platforms"
                        }
                    } else if (arrivalStop.name != "") "to ${arrivalStop.name}" else "to the destination"
                    val message = "$partDuration min $arrivalPlace"
                    val part = TripPart(
                        type = if (type) 0 else 1,
                        line = if (type) null else tripLines.getJSONArray(j).getString(1),
                        headsign = if (type) null else currentPart.getString("destination").removePrefix(", "),
                        duration = partDuration,
                        departure = if (type) null else TripDA(
                            time = partDeparture.format(timeFormat),
                            stop = departureStop
                        ),
                        arrival = if (type) null else TripDA(
                            time = partArrival.format(timeFormat),
                            stop = arrivalStop
                        ),
                        stops = mutableListOf(),
                        message = message
                    )
                    for (k in 1 until stops.length() - 1) {
                        val currentStop = stops.getJSONObject(k)
                        val stopsDeparture = try {
                            LocalDateTime.parse(
                                currentStop.getJSONObject("departure").getString("date").split(".")[0],
                                inputFormat
                            )
                        } catch (e: JSONException) {
                            LocalDateTime.parse(currentStop.getJSONObject("arrival").getString("date").split(".")[0], inputFormat)
                        }
                        val stop = TripStop(
                            time = stopsDeparture.format(timeFormat),
                            name = currentStop.getString("name"),
                            zone = try {
                                currentStop.getJSONObject("fare_zones").keys().next()
                            } catch (e: JSONException) {
                                "none"
                            },
                            platform = try {
                                currentStop.getString("label")
                            } catch (e: JSONException) {
                                null
                            },
                            request = try {
                                currentStop.getString("request_stop") == "1"
                            } catch (e: JSONException) {
                                false
                            }
                        )
                        part.stops.add(stop)
                    }
                    trip.parts.add(part)
                }
                trips.add(trip)
            }
            return trips
        } else {
            ErrorAlert(context.getString(R.string.ops), context.getString(R.string.error404)).show(fragmentManager, "error")
            return null
        }
    } catch (e: JSONException) {
        ErrorAlert(context.getString(R.string.ops), context.getString(R.string.unknownError)).show(fragmentManager, "error")
        println(e)
        return null
    }
}