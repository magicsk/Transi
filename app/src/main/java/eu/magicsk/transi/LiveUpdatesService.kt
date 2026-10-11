package eu.magicsk.transi

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.Parcelable
import androidx.annotation.StringRes
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.core.content.IntentCompat
import androidx.core.net.toUri
import dagger.hilt.android.AndroidEntryPoint
import eu.magicsk.transi.data.models.AlertTriggers
import eu.magicsk.transi.data.models.DepartureReference
import eu.magicsk.transi.data.models.LiveState
import eu.magicsk.transi.data.models.MHDTableData
import eu.magicsk.transi.data.models.MHDTableVehicle
import eu.magicsk.transi.data.models.clockText
import eu.magicsk.transi.data.models.delayText
import eu.magicsk.transi.data.models.departureEnded
import eu.magicsk.transi.data.models.liveAlertMinutes
import eu.magicsk.transi.data.models.matches
import eu.magicsk.transi.data.models.remainingText
import eu.magicsk.transi.data.models.shortRemainingText
import eu.magicsk.transi.data.remote.responses.Stop
import eu.magicsk.transi.repository.DataRepository
import eu.magicsk.transi.repository.IdsbkSession
import eu.magicsk.transi.repository.LiveBoard
import eu.magicsk.transi.util.Prefs
import eu.magicsk.transi.util.getLineColor
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Follows departures the user picked, each in an ongoing notification that updates live until the vehicle leaves
 * (the iOS table Live Activity). Departures from one stop share that stop's [LiveBoard] connection; one followed
 * departure's notification keeps the service in the foreground, so it survives the app being swiped away and stops
 * itself once nothing is followed. Started only from a visible screen through [follow]; it runs on the main thread.
 *
 * The parts that are not about one departure (boards shared by stop, the foreground hand-over, the ticker and the stop
 * intent by notification id) are kept apart from [FollowedDeparture], so another kind of followed item can reuse them.
 */
@AndroidEntryPoint
class LiveUpdatesService : Service() {
    @Inject
    lateinit var repository: DataRepository

    @Inject
    lateinit var idsbk: IdsbkSession

    private val scope = MainScope()
    private val boards = mutableMapOf<Int, LiveBoard>()
    private val followed = mutableListOf<FollowedDeparture>()
    /** The followed item whose notification keeps the service in the foreground; 0 before the first one. */
    private var foregroundId = 0
    private var nextId = FIRST_NOTIFICATION_ID
    private var ticker: Job? = null
    private lateinit var notificationManager: NotificationManager

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        notificationManager = getSystemService(NotificationManager::class.java)
        createChannels()
        // Left from a run the system killed: only its foreground notification went away with it.
        notificationManager.activeNotifications
            .filter { it.notification.channelId in CHANNELS }
            .forEach { notificationManager.cancel(it.id) }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_FOLLOW -> {
                val departure = IntentCompat.getParcelableExtra(intent, EXTRA_DEPARTURE, MHDTableData::class.java)
                val stop = IntentCompat.getParcelableExtra(intent, EXTRA_STOP, Stop::class.java)
                if (departure != null && stop != null) follow(departure, stop)
            }
            ACTION_STOP -> stopFollowing(intent.getIntExtra(EXTRA_NOTIFICATION_ID, 0))
        }
        if (followed.isEmpty()) stopSelf(startId)
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        scope.cancel()
        boards.values.forEach { it.close() }
        boards.clear()
        followed.forEach { notificationManager.cancel(it.notificationId) }
        followed.clear()
        super.onDestroy()
    }

    private fun follow(departure: MHDTableData, stop: Stop) {
        // Following a departure again keeps its notification; the service is already in the foreground then.
        if (followed.any { departure.matches(it.reference) }) return
        val item = FollowedDeparture(nextId++, DepartureReference(departure), stop, departure, System.currentTimeMillis())
        followed += item
        boards.getOrPut(stop.id) {
            LiveBoard(repository, idsbk, scope).apply {
                onDepartures = { refresh(stop.id) }
                onVehicles = { refresh(stop.id) }
                setStop(stop)
                connect()
            }
        }
        update(item, System.currentTimeMillis())
        if (ticker?.isActive != true) {
            // iOS refreshes the remaining time every 10 s between board updates.
            ticker = scope.launch {
                while (isActive) {
                    delay(10_000)
                    refresh(null)
                }
            }
        }
    }

    private fun stopFollowing(notificationId: Int) {
        val item = followed.firstOrNull { it.notificationId == notificationId }
        // An unknown id is a notification left from a run the system killed.
        if (item != null) end(item) else notificationManager.cancel(notificationId)
    }

    /** Matches the followed departures of [stopId] (every stop when null) with their board again. */
    private fun refresh(stopId: Int?) {
        val now = System.currentTimeMillis()
        for (item in followed.toList()) {
            val itemStopId = item.reference.stopId
            if (stopId != null && itemStopId != stopId) continue
            val board = boards[itemStopId] ?: continue
            val row = board.departures.firstOrNull { it.matches(item.reference) }
            val expected = (row ?: item.row).departureTime
            if (departureEnded(row, board.hasWholeBoard(item.reference.platform), expected, now)) {
                end(item)
                continue
            }
            if (row != null) item.row = row
            item.vehicle = board.vehicles[item.row.busID]
            update(item, now)
        }
    }

    private fun end(item: FollowedDeparture) {
        followed.remove(item)
        val stopId = item.reference.stopId
        if (followed.none { it.reference.stopId == stopId }) boards.remove(stopId)?.close()
        val next = followed.firstOrNull()
        if (next == null) {
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
            stopSelf()
        } else if (item.notificationId == foregroundId) {
            // Moving the foreground to another notification removes this one.
            foregroundId = next.notificationId
            next.posted?.let { show(next, notification(next, it, alert = false, System.currentTimeMillis())) }
        }
        notificationManager.cancel(item.notificationId)
    }

    /** Posts [item] again when what it shows changed, alerting by the iOS rule. */
    private fun update(item: FollowedDeparture, now: Long) {
        val row = item.row
        val promoted = NotificationManagerCompat.from(this).canPostPromotedNotifications()
        val (delayRes, delayMinutes) = delayText(row.delay, row.type)
        val total = row.departureTime - item.startedAt
        val content = Content(
            LiveState(remainingText(this, row.departureTime, row.type, now), getString(delayRes, delayMinutes), row.lastStopName),
            row,
            item.vehicle,
            promoted,
            // Only Live Updates show it; elsewhere it would just post again every few seconds.
            if (!promoted) 0 else if (total > 0) ((now - item.startedAt) * 100 / total).coerceIn(0, 100).toInt() else 100,
        )
        if (content == item.posted) return
        val prefs = Prefs.get(this)
        val alertMinutes = item.posted?.let { old ->
            liveAlertMinutes(
                old.state, content.state, (row.departureTime - now) / 1000, item.lastAlertMinutes,
                AlertTriggers(
                    prefs.getBoolean(Prefs.NOTIFY_ON_TIME_CHANGE, true),
                    prefs.getBoolean(Prefs.NOTIFY_ON_DELAY_CHANGE, true),
                    prefs.getBoolean(Prefs.NOTIFY_ON_POSITION_CHANGE, true),
                ),
                prefs.getInt(Prefs.LIVE_ACTIVITY_THRESHOLD, Prefs.LIVE_ACTIVITY_THRESHOLD_DEFAULT),
            )
        }
        if (alertMinutes != null) item.lastAlertMinutes = alertMinutes
        item.posted = content
        show(item, notification(item, content, alertMinutes != null, now))
    }

    private fun show(item: FollowedDeparture, notification: Notification) {
        if (foregroundId == 0) foregroundId = item.notificationId
        if (item.notificationId == foregroundId) {
            val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            } else {
                0
            }
            ServiceCompat.startForeground(this, item.notificationId, notification, type)
        } else {
            notificationManager.notify(item.notificationId, notification)
        }
    }

    /**
     * iOS large view: line and headsign, remaining time, delay, expected time, last stop and vehicle; an alert shows the
     * iOS alert title and body instead. Android 16+ promotes it to a Live Update with the remaining time as the status
     * bar chip and the time to departure as progress; older versions colour it with the line and count down.
     */
    private fun notification(item: FollowedDeparture, content: Content, alert: Boolean, now: Long): Notification {
        val row = content.row
        val state = content.state
        val promoted = content.promoted
        val online = row.type == "online"
        val lastStop = row.lastStopName.takeIf { online && it != "none" }
        val vehicle = content.vehicle?.takeIf { online }
        val vehicleText = vehicle?.let { getString(R.string.vehicleText, it.type, row.busID.drop(2)) }
        val lines = if (alert) {
            listOfNotNull(lastStop, state.delay, vehicleText)
        } else {
            listOfNotNull(
                getString(R.string.live_updates_summary, state.remaining, state.delay),
                getString(R.string.departureTime, clockText(this, row.departureTime)),
                lastStop?.let { getString(R.string.live_updates_last_stop, it) },
                vehicleText?.let {
                    getString(if (vehicle.ac == 1) R.string.live_updates_vehicle_ac else R.string.live_updates_vehicle_no_ac, it)
                },
            )
        }
        val text = lines.joinToString("\n")
        val color = ContextCompat.getColor(this, getLineColor(row.line, false))
        val counting = row.departureTime > now
        val sounds = Prefs.get(this).getBoolean(Prefs.LIVE_ACTIVITIES_SOUNDS, true)
        val channel = when {
            !alert -> CHANNEL_UPDATES
            sounds -> CHANNEL_ALERTS
            else -> CHANNEL_ALERTS_SILENT
        }
        val stopIntent = PendingIntent.getService(
            this, item.notificationId,
            Intent(this, LiveUpdatesService::class.java).setAction(ACTION_STOP).putExtra(EXTRA_NOTIFICATION_ID, item.notificationId),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        // The table opens on the stop with the row expanded (the iOS widget URL).
        val open = Intent(Intent.ACTION_VIEW, "transi://table/${row.stopId}/${row.id}".toUri(), this, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        val share = Intent(Intent.ACTION_SEND).setType("text/plain")
            .putExtra(Intent.EXTRA_TEXT, departureShareText(this, row, item.stop))
        return NotificationCompat.Builder(this, channel)
            .setSmallIcon(R.drawable.ic_bus)
            .setContentTitle(
                if (alert) getString(R.string.live_updates_alert_title, row.line, row.headsign, state.remaining)
                else getString(R.string.table_departure_title, row.line, row.headsign)
            )
            // A Live Update's progress style shows the whole text when expanded.
            .setContentText(if (promoted) text else lines.first())
            .setStyle(
                if (promoted) {
                    NotificationCompat.ProgressStyle()
                        .setProgressSegments(listOf(NotificationCompat.ProgressStyle.Segment(100).setColor(color)))
                        .setProgress(content.progress)
                        .setStyledByProgress(true)
                } else {
                    NotificationCompat.BigTextStyle().bigText(text)
                }
            )
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setOngoing(true)
            .setOnlyAlertOnce(!alert)
            .setColor(color)
            // Colorized notifications are never promoted to Live Updates.
            .setColorized(!promoted)
            .setRequestPromotedOngoing(true)
            .setShortCriticalText(shortRemainingText(this, row.departureTime, now))
            .setWhen(row.departureTime)
            .setShowWhen(counting)
            .setUsesChronometer(counting)
            .setChronometerCountDown(true)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setContentIntent(
                PendingIntent.getActivity(
                    this, item.notificationId, open, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
                )
            )
            // Dismissing it (Android 14+ allows it for ongoing ones) stops following, like dismissing a Live Activity.
            .setDeleteIntent(stopIntent)
            .addAction(0, getString(R.string.live_updates_stop), stopIntent)
            .addAction(
                0, getString(R.string.share),
                PendingIntent.getActivity(
                    this, item.notificationId, Intent.createChooser(share, null),
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                )
            )
            .build()
    }

    // Updates are silent; alerts move the notification to a heads-up channel, with or without sound, because a
    // channel's sound cannot change per notification on Android 8+ and Settings > Sound effects picks it.
    private fun createChannels() {
        fun channel(id: String, @StringRes name: Int, @StringRes description: Int, importance: Int, sound: Boolean) =
            NotificationChannel(id, getString(name), importance).apply {
                this.description = getString(description)
                setShowBadge(false)
                if (!sound) {
                    setSound(null, null)
                    enableVibration(false)
                }
            }
        notificationManager.createNotificationChannels(
            listOf(
                channel(
                    CHANNEL_UPDATES, R.string.live_updates_channel, R.string.live_updates_channel_description,
                    NotificationManager.IMPORTANCE_DEFAULT, sound = false,
                ),
                channel(
                    CHANNEL_ALERTS, R.string.live_updates_alerts_channel, R.string.live_updates_alerts_channel_description,
                    NotificationManager.IMPORTANCE_HIGH, sound = true,
                ),
                channel(
                    CHANNEL_ALERTS_SILENT, R.string.live_updates_silent_alerts_channel,
                    R.string.live_updates_silent_alerts_channel_description, NotificationManager.IMPORTANCE_HIGH, sound = false,
                ),
            )
        )
    }

    /** One followed departure: the row last matched on its board and what its notification shows. */
    private class FollowedDeparture(
        val notificationId: Int,
        val reference: DepartureReference,
        val stop: Stop,
        var row: MHDTableData,
        /** When following started; the Live Update's progress runs from it to the departure. */
        val startedAt: Long,
    ) {
        var vehicle: MHDTableVehicle? = null
        var posted: Content? = null
        var lastAlertMinutes: Int? = null
    }

    /** What a departure's notification shows; it is posted again only when this changes. */
    private data class Content(
        val state: LiveState,
        val row: MHDTableData,
        val vehicle: MHDTableVehicle?,
        /** Android 16+ may show it as a Live Update (the user can turn them off per app). */
        val promoted: Boolean,
        val progress: Int,
    )

    companion object {
        private const val ACTION_FOLLOW = "eu.magicsk.transi.action.FOLLOW_DEPARTURE"
        private const val ACTION_STOP = "eu.magicsk.transi.action.STOP_FOLLOWING"
        private const val EXTRA_DEPARTURE = "departure"
        private const val EXTRA_STOP = "stop"
        private const val EXTRA_NOTIFICATION_ID = "notification_id"
        // The trip notification uses id 1.
        private const val FIRST_NOTIFICATION_ID = 100
        private const val CHANNEL_UPDATES = "live_updates"
        private const val CHANNEL_ALERTS = "live_update_alerts"
        private const val CHANNEL_ALERTS_SILENT = "live_update_alerts_silent"
        private val CHANNELS = setOf(CHANNEL_UPDATES, CHANNEL_ALERTS, CHANNEL_ALERTS_SILENT)

        /** The channel of the departure notification this service replaced. */
        const val LEGACY_CHANNEL = "virtual_table_notifier"

        /** Follows [departure] of [stop] in a live notification. Call only from a visible screen. */
        fun follow(context: Context, departure: MHDTableData, stop: Stop) {
            ContextCompat.startForegroundService(
                context,
                Intent(context, LiveUpdatesService::class.java)
                    .setAction(ACTION_FOLLOW)
                    .putExtra(EXTRA_DEPARTURE, departure as Parcelable)
                    .putExtra(EXTRA_STOP, stop as Parcelable),
            )
        }
    }
}
