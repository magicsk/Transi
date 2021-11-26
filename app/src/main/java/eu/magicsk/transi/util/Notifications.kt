package eu.magicsk.transi.util

import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.text.Html
import android.text.Spanned
import androidx.core.app.NotificationCompat
import eu.magicsk.transi.MainActivity
import eu.magicsk.transi.R
import eu.magicsk.transi.data.models.MHDTableData
import eu.magicsk.transi.receivers.MHDTableNotificationReceiver
import io.socket.client.IO
import io.socket.client.Socket
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.net.URI
import java.text.SimpleDateFormat
import java.util.*


private const val TABLE_NOTIFICATION_ID = 0
private var thread: Thread = Thread()
private val uri: URI = URI.create("https://imhd.sk/")
private val options = IO.Options()
private val socket: Socket = IO.socket(uri, options)
private val tabArgs = JSONArray()
private var connected = false
private var lastInfo = MHDTableData(
    0L, "", "", "", "", 0L, 0, "", 0, 0, "", stuck = false, expanded = false
)
private var lastTime = ""
private var tableNotificationActive = false

fun cancelTableNotification(context: Context) {
    tableNotificationActive = false
    val notificationManager = context.getSystemService(NotificationManager::class.java) as NotificationManager
    notificationManager.cancel(TABLE_NOTIFICATION_ID)
    socket.disconnect()
    socket.off("tabs")
    socket.close()
}

fun startNotificationUpdater(context: Context) {
    thread = object : Thread() {
        override fun run() {
            try {
                while (!this.isInterrupted) {
                    sleep(10000)
                    if (tableNotificationActive) {
                        sendTableNotification(lastInfo, context, true)
                    } else {
                        val notificationManager = context.getSystemService(NotificationManager::class.java) as NotificationManager
                        notificationManager.cancel(TABLE_NOTIFICATION_ID)
                    }
                }
            } catch (e: InterruptedException) {
            }
        }
    }
    thread.start()
}

fun tableNotification(stopId: Int, connectionId: Long, context: Context) {
    if (connected) {
        socket.disconnect()
        socket.off("tabs")
        socket.close()
    }
    println("connecting")
    options.reconnection = true
    options.path = "/rt/sio2"
    tabArgs.put(0, stopId)
    tabArgs.put(1, "*")
    socket.connect()
    socket.emit("tabStart", tabArgs)
    socket.on("tabs") {
        println("new tabs")
        val data = JSONObject(it[0].toString())
        val keys = data.keys()
        while (keys.hasNext()) {
            val platform = mutableListOf<MHDTableData>()
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
            var exists = false
            for (i in 0 until platform.size) {
                val item = platform[i]
                if (item.Id == connectionId) {
                    tableNotificationActive = true
                    exists = sendTableNotification(item, context)
                }
            }
            if (!exists && lastInfo.platform == platform[0].platform) {
                cancelTableNotification(context)
            }
        }
    }
}

fun sendTableNotification(item: MHDTableData, context: Context, silent: Boolean = false): Boolean {
    val notificationManager = context.getSystemService(NotificationManager::class.java) as NotificationManager
    val mins = (item.departureTime - System.currentTimeMillis()).toDouble() / 60000
    val hours = SimpleDateFormat("H:mm", Locale.UK).format(item.departureTime)
    val time =
        when {
            mins > 60 -> "at $hours"
            mins < 0 -> "now"
            mins < 1 -> "<1 min"
            else -> "${mins.toInt()} min"
        }
    val timeText =
        when {
            item.type == "online" && mins < 0 -> time
            item.type == "online" -> "in $time"
            mins > 60 -> "at ~$hours"
            mins < 0 -> "~now"
            else -> "in ~$time"
        }
    val formattedDeparture = SimpleDateFormat("H:mm", Locale.UK).format(item.departureTime)
    val delayText = when {
        item.delay > 0 -> context.getString(R.string.delay).format(item.delay)
        item.delay < 0 -> context.getString(R.string.inAdvance).format(item.delay.toString().drop(1))
        else -> context.getString(R.string.onTime).replaceFirstChar { "O" }
    }

//        val busID = item.busID.drop(2)
//        val v = if (item.imgt == 0) "vm" else "vs"
//        val url = "https://imhd.sk/ba/media/$v/${item.img.toString().padStart(8, '0')}/$busID"
//        val url = "https://imhd.sk/ba/media/vs/00000795/6638"
    val stuckText = if (item.stuck) "STUCK!" else ""

    val notificationBody = if (item.type == "online") {
        context.getString(
            R.string.table_notification_body,
            formattedDeparture,
            item.lastStopName,
            delayText,
            stuckText
        )
    } else {
        context.getString(R.string.table_notification_body_offline, formattedDeparture, item.lastStopName)
    }
    if (lastInfo != item || time != lastTime) {
        lastTime = time
        lastInfo = item
//            val target = Glide.with(context)
//                .asBitmap()
//                .load(url)
//                .submit()
        notificationManager.sendNotification(
            context.getString(R.string.table_notification_title, item.line, item.headsign, timeText),
            context.getString(R.string.lastStop, item.lastStopName),
            Html.fromHtml(notificationBody, Html.FROM_HTML_MODE_LEGACY),
            mins > 6 || silent,
//                target.get(),
            context.getString(R.string.table_notification_channel_id),
            TABLE_NOTIFICATION_ID,
            false,
            cancelAction = true,
            context
        )
        if (!thread.isAlive) startNotificationUpdater(context)
    }
    return true
}

fun NotificationManager.sendNotification(
    messageTitle: String,
    messageBody: String,
    messageBigBody: Spanned,
    silent: Boolean,
//    icon: Bitmap,
    channel_id: String,
    notification_id: Int,
    ongoing: Boolean,
    cancelAction: Boolean,
    applicationContext: Context
) {
    val contentIntent = Intent(applicationContext, MainActivity::class.java)
    val contentPendingIntent = PendingIntent.getActivity(
        applicationContext,
        notification_id,
        contentIntent,
        PendingIntent.FLAG_IMMUTABLE
    )

    val cancelIntent = Intent(applicationContext, MHDTableNotificationReceiver::class.java)
    val cancelPendingIntent: PendingIntent = PendingIntent.getBroadcast(
        applicationContext,
        notification_id,
        cancelIntent,
        PendingIntent.FLAG_IMMUTABLE
    )

    val builder = NotificationCompat.Builder(
        applicationContext,
        channel_id
    )
        .setSmallIcon(R.drawable.ic_launcher_foreground)
        .setContentTitle(messageTitle)
        .setContentText(messageBody)
        .setContentIntent(contentPendingIntent)
        .setAutoCancel(false)
        .setOngoing(ongoing)
        .setStyle(
            NotificationCompat.BigTextStyle()
                .bigText(messageBigBody)
        )
        .setPriority(NotificationCompat.PRIORITY_HIGH)
        .setSilent(silent)
//        .setLargeIcon(icon)
    if (cancelAction) {
        builder.addAction(
            R.drawable.ic_launcher_foreground,
            applicationContext.getString(R.string.dismiss),
            cancelPendingIntent
        )
    }

    notify(notification_id, builder.build())
}