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
import eu.magicsk.transi.data.models.MHDTable
import eu.magicsk.transi.data.models.MHDTableData
import eu.magicsk.transi.data.remote.responses.Stop
import eu.magicsk.transi.receivers.MHDTableCancelNotificationReceiver
import io.socket.client.IO
import io.socket.client.Socket
import org.json.JSONArray
import org.json.JSONObject
import java.net.URI
import java.text.SimpleDateFormat
import java.util.*


private const val TABLE_NOTIFICATION_ID = 0
private val mhdTable = MHDTable()
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
    socket.off()
    socket.close()
    connected = false
    wakeLock?.release()
}

fun startNotificationUpdater(context: Context) {
    thread = object : Thread() {
        override fun run() {
            try {
                while (!this.isInterrupted) {
                    sleep(10000)
                    if (tableNotificationActive && connected) {
                        sendTableNotification(lastInfo, context, true)
                    } else {
                        val notificationManager = context.getSystemService(NotificationManager::class.java) as NotificationManager
                        notificationManager.cancel(TABLE_NOTIFICATION_ID)
                    }
                }
            } catch (_: InterruptedException) {
            }
        }
    }
    thread.start()
}

fun tableNotification(stop: Stop, connectionId: Long, context: Context) {
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
        socket.off()
    }
    socket
        .on(Socket.EVENT_CONNECT) {
            socket.emit("tabStart", tabArgs)
            connected = true
            observersInitialized = true
        }
        .on(Socket.EVENT_DISCONNECT) {
            connected = false
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
            mhdTable.addTabs(JSONObject(it[0].toString()))
            var exists = false
            for (platform in mhdTable.tabs) {
                if (platform.Id == connectionId) {
                    tableNotificationActive = true
                    exists = sendTableNotification(platform, context)
                }
            }
            if (mhdTable.tabs.size > 0) {
                if (!exists && lastInfo.platform == mhdTable.tabs[0].platform) {
                    cancelTableNotification(context)
                }
            }
        }
        .on("vInfo") {
            mhdTable.addVehicleInfo(JSONObject(it[0].toString()))
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
    val o = if (item.type == "online") "" else "~"
    val time =
        when {
            mins > 60 -> "at ${o}$hours"
            mins < 0 -> "${o}now"
            mins < 1 -> "in ${o}<1 min"
            else -> "in ${o}${mins.toInt()} min"
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
    for (i in 0 until mhdTable.vehicleInfo.size) {
        if (mhdTable.vehicleInfo[i].issi == item.busID) {
            val currentVehicle = mhdTable.vehicleInfo[i]
            val busID = item.busID.drop(2)
            vehicleID = context.getString(R.string.vehicleText).format(currentVehicle.type, busID)
        }
    }

    val notificationTitle =
        connectionInfo ?: context.getString(R.string.table_notification_title, item.line, item.headsign, time)

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