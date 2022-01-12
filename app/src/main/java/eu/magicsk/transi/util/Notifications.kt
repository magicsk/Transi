package eu.magicsk.transi.util

import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.PowerManager
import android.text.Html
import android.text.Spanned
import androidx.core.app.NotificationCompat
import androidx.core.text.buildSpannedString
import eu.magicsk.transi.MainActivity
import eu.magicsk.transi.R
import eu.magicsk.transi.data.models.MHDTableData
import eu.magicsk.transi.data.models.MHDTableVehicle
import eu.magicsk.transi.data.remote.responses.StopsJSONItem
import eu.magicsk.transi.receivers.MHDTableCancelNotificationReceiver
import io.socket.client.IO
import io.socket.client.Socket
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.net.URI
import java.text.SimpleDateFormat
import java.util.*


private const val TABLE_NOTIFICATION_ID = 0
private val vehicleInfo = mutableListOf<MHDTableVehicle>()
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
private var actualStopName = "Error"
private var tableNotificationActive = false
private var observersInitialized = false
private var wakeLock: PowerManager.WakeLock? = null

fun cancelTableNotification(context: Context) {
    tableNotificationActive = false
    val notificationManager = context.getSystemService(NotificationManager::class.java) as NotificationManager
    notificationManager.cancel(TABLE_NOTIFICATION_ID)
    socket.off(Socket.EVENT_CONNECT)
    socket.off(Socket.EVENT_DISCONNECT)
    socket.off(Socket.EVENT_RECONNECTING)
    socket.off(Socket.EVENT_RECONNECT)
    socket.off("tabs")
    socket.disconnect()
    connected = false
    socket.close()
    wakeLock?.release()
}

fun startNotificationUpdater(context: Context) {
    thread = object : Thread() {
        override fun run() {
            try {
                while (!this.isInterrupted) {
                    sleep(10000)
                    println(tableNotificationActive)
                    println(lastInfo)
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

fun tableNotification(stop: StopsJSONItem, connectionId: Long, context: Context) {
    actualStopName = stop.name
    if (connected) {
        cancelTableNotification(context)
    }
    wakeLock =
        (context.getSystemService(Context.POWER_SERVICE) as PowerManager).run {
            newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Transi::NotificationWakelock").apply {
                acquire(60 * 60 * 1000L) // 60min
            }
        }
    options.reconnection = true
    options.path = "/rt/sio2"
    tabArgs.put(0, stop.id)
    tabArgs.put(1, "*")
    if (observersInitialized) {
        socket.off(Socket.EVENT_CONNECT)
        socket.off(Socket.EVENT_DISCONNECT)
        socket.off(Socket.EVENT_RECONNECTING)
        socket.off(Socket.EVENT_RECONNECT)
        socket.off("tabs")
    }
    socket
        .on(Socket.EVENT_CONNECT) {
            socket.emit("tabStart", tabArgs)
            connected = true
            observersInitialized = true
        }
        .on(Socket.EVENT_DISCONNECT) {
            connected = false
            sendTableNotification(lastInfo, context, false, "Disconnected")
        }
        .on(Socket.EVENT_RECONNECTING) {
            sendTableNotification(lastInfo, context, true, "Reconnecting")
        }
        .on(Socket.EVENT_RECONNECT) {
            connected = true
            sendTableNotification(lastInfo, context, true, force = true)
            socket
                .emit("tabStart", tabArgs)
                .emit("infoStart")
        }
        .on("tabs") {
            println("new notification tabs")
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
                if (platform.size > 0) {
                    if (!exists && lastInfo.platform == platform[0].platform) {
                        cancelTableNotification(context)
                    }
                }
            }
        }
        .on("vInfo") {
            val data = JSONObject(it[0].toString())
            val keys = data.keys()
            val item = MHDTableVehicle(
                try {
                    data.getInt(keys.next())
                } catch (e: JSONException) {
                    0
                },
                try {
                    data.getInt(keys.next())
                } catch (e: JSONException) {
                    0
                },
                try {
                    data.getInt(keys.next())
                } catch (e: JSONException) {
                    0
                },
                try {
                    data.getInt(keys.next())
                } catch (e: JSONException) {
                    0
                },
                try {
                    data.getInt(keys.next())
                } catch (e: JSONException) {
                    0
                },
                try {
                    data.getString(keys.next())
                } catch (e: JSONException) {
                    "error"
                },
                try {
                    data.getString(keys.next())
                } catch (e: JSONException) {
                    "error"
                },
            )
            vehicleInfo.add(item)
        }
    socket.connect()
}

fun sendTableNotification(
    item: MHDTableData,
    context: Context,
    silent: Boolean = false,
    connectionInfo: String? = null,
    force: Boolean = false
): Boolean {
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

    var vehicleID = ""
    for (i in 0 until vehicleInfo.size) {
        if (vehicleInfo[i].issi == item.busID) {
            val currentVehicle = vehicleInfo[i]
            val busID = item.busID.drop(2)
            vehicleID = context.getString(R.string.vehicleText).format(currentVehicle.type, busID)
        }
    }

    val notificationTitle =
        connectionInfo ?: context.getString(R.string.table_notification_title, item.line, item.headsign, timeText)

    val notificationSubtitle = if (item.type == "online") {
        context.getString(R.string.lastStop, item.lastStopName)
    } else {
        context.getString(R.string.departureTime, formattedDeparture)
    }

    val notificationBody = if (item.type == "online") {
        context.getString(
            R.string.table_notification_body,
            formattedDeparture,
            item.lastStopName,
            delayText,
            stuckText,
            vehicleID
        )
    } else {
        context.getString(R.string.table_notification_body_offline, formattedDeparture)
    }

    val shareBody = if (item.type == "online") {
        context.getString(
            R.string.table_share_body,
            item.line,
            item.headsign,
            actualStopName,
            formattedDeparture,
            item.lastStopName,
            delayText,
            stuckText,
            vehicleID
        )
    } else {
        context.getString(
            R.string.table_share_body_offline,
            item.line,
            item.headsign,
            actualStopName,
            formattedDeparture
        )
    }

    if (lastInfo != item || time != lastTime || connectionInfo != null || force) {
        lastTime = time
        lastInfo = item
//            val target = Glide.with(context)
//                .asBitmap()
//                .load(url)
//                .submit()
        notificationManager.sendNotification(
            notificationTitle,
            notificationSubtitle,
            Html.fromHtml(notificationBody, Html.FROM_HTML_MODE_LEGACY),
            Html.fromHtml(shareBody, Html.FROM_HTML_MODE_LEGACY),
            mins > 6 || silent,
//                target.get(),
            context.getString(R.string.table_notification_channel_id),
            TABLE_NOTIFICATION_ID,
            false,
            context,
            cancelAction = true,
            shareAction = true
        )
        if (!thread.isAlive) startNotificationUpdater(context)
    }
    return true
}

fun NotificationManager.sendNotification(
    messageTitle: String,
    messageBody: String,
    messageBigBody: Spanned,
    shareMessageBody: Spanned = buildSpannedString {},
    silent: Boolean,
//    icon: Bitmap,
    channel_id: String,
    notification_id: Int,
    ongoing: Boolean,
    applicationContext: Context,
    cancelAction: Boolean = false,
    shareAction: Boolean = false
) {
    val contentIntent = Intent(applicationContext, MainActivity::class.java)
    val contentPendingIntent = PendingIntent.getActivity(
        applicationContext,
        notification_id,
        contentIntent,
        PendingIntent.FLAG_IMMUTABLE
    )

    val shareIntent = Intent.createChooser(
        Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, shareMessageBody)
        },
        "Share using:"
    )
    val sharePendingIntent = PendingIntent.getActivity(
        applicationContext,
        (0..30000).random(),
        shareIntent,
        PendingIntent.FLAG_IMMUTABLE
    )

    val cancelIntent = Intent(applicationContext, MHDTableCancelNotificationReceiver::class.java)
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
    if (shareAction) {
        builder.addAction(
            R.drawable.ic_launcher_foreground,
            applicationContext.getString(R.string.share),
            sharePendingIntent
        )
    }
    if (cancelAction) {
        builder.addAction(
            R.drawable.ic_launcher_foreground,
            applicationContext.getString(R.string.dismiss),
            cancelPendingIntent
        )
    }

    notify(notification_id, builder.build())
}