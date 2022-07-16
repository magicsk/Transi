package eu.magicsk.transi.util

import android.app.Activity
import android.content.Context
import android.os.Parcelable
import eu.magicsk.transi.R
import kotlinx.android.parcel.Parcelize
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

fun TripPlannerJsonParser(data: JSONObject, activity: Activity, context: Context): MutableList<Trip>? {
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

                for (j in 0 until parts.length()) {
                    val currentPart = parts.getJSONObject(j)
                    val partDeparture =
                        LocalDateTime.parse(currentPart.getJSONObject("departure").getString("date").split(".")[0], inputFormat)
                    val partArrival =
                        LocalDateTime.parse(currentPart.getJSONObject("arrival").getString("date").split(".")[0], inputFormat)
                    val partDuration = LocalDateTime.ofEpochSecond(
                        partArrival.toEpochSecond(ZoneOffset.UTC) - partDeparture.toEpochSecond(ZoneOffset.UTC),
                        0,
                        ZoneOffset.UTC
                    )
                    val stops = currentPart.getJSONArray("stops")
                    val departureStop = stops.getJSONObject(0)
                    val arrivalStop = stops.getJSONObject(stops.size())
                    val type = currentPart.getString("type") == "\uD83D\uDEB6"
                    println(partDuration.minute)

                    val part = TripPart(
                        type = if (type) 0 else 1,
                        line = if (type) null else currentPart.getJSONArray("line").getString(1),
                        headsign = if (type) null else currentPart.getString("destination"),
                        duration = LocalDateTime.ofEpochSecond(
                            partArrival.toEpochSecond(ZoneOffset.UTC) - partDuration.toEpochSecond(ZoneOffset.UTC),
                            0,
                            ZoneOffset.UTC
                        ).minute.toString(),
                        departure = if (type) null else TripDA(
                            time = partDeparture.format(timeFormat),
                            stop = TripStop(
                                time = partDeparture.format(timeFormat),
                                name = departureStop.getString("name"),
                                zone = departureStop.getJSONObject("fare_zones").keys().next(),
                                platform = try {
                                    departureStop.getString("label")
                                } catch (e: JSONException) {
                                    null
                                },
                                request = try {
                                    departureStop.getString("request_stop") == "1"
                                } catch (e: JSONException) {
                                    false
                                }
                            )
                        ),
                        arrival = if (type) null else TripDA(
                            time = partArrival.format(timeFormat),
                            stop = TripStop(
                                time = partArrival.format(timeFormat),
                                name = arrivalStop.getString("name"),
                                zone = arrivalStop.getJSONObject("fare_zones").keys().next(),
                                platform = try {
                                    arrivalStop.getString("label")
                                } catch (e: JSONException) {
                                    null
                                },
                                request = try {
                                    arrivalStop.getString("request_stop") == "1"
                                } catch (e: JSONException) {
                                    false
                                }
                            )
                        ),
                        stops = mutableListOf()
                    )
                    for (k in 1 until stops.length()-1) {
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
                            zone = currentStop.getJSONObject("fare_zones").keys().next(),
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
            simpleErrorAlert(activity, context.getString(R.string.ops), context.getString(R.string.error404))
            return null
        }
    } catch (e: JSONException) {
        println(e)
        simpleErrorAlert(activity, context.getString(R.string.ops), context.getString(R.string.unknownError))
        return null
    }
}