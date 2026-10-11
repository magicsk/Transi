package eu.magicsk.transi

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.location.Location
import android.location.LocationManager
import android.os.Build
import android.os.IBinder
import android.os.Parcelable
import android.os.PowerManager
import android.os.SystemClock
import androidx.annotation.StringRes
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.core.content.IntentCompat
import androidx.core.location.LocationListenerCompat
import androidx.core.location.LocationManagerCompat
import androidx.core.location.LocationRequestCompat
import androidx.core.net.toUri
import dagger.hilt.android.AndroidEntryPoint
import eu.magicsk.transi.data.models.AlertTriggers
import eu.magicsk.transi.data.models.Journey
import eu.magicsk.transi.data.models.LiveState
import eu.magicsk.transi.data.models.MHDTableData
import eu.magicsk.transi.data.models.MHDTableVehicle
import eu.magicsk.transi.data.models.SavedTrip
import eu.magicsk.transi.data.models.TripAlert
import eu.magicsk.transi.data.models.TripFix
import eu.magicsk.transi.data.models.TripState
import eu.magicsk.transi.data.models.TripStep
import eu.magicsk.transi.data.models.clockText
import eu.magicsk.transi.data.models.delayText
import eu.magicsk.transi.data.models.departureEnded
import eu.magicsk.transi.data.models.liveAlertMinutes
import eu.magicsk.transi.data.models.matches
import eu.magicsk.transi.data.models.plannerGson
import eu.magicsk.transi.data.models.remainingText
import eu.magicsk.transi.data.models.resolve
import eu.magicsk.transi.data.models.shortRemainingText
import eu.magicsk.transi.data.remote.responses.Stop
import eu.magicsk.transi.data.remote.responses.idsbk.StopGps
import eu.magicsk.transi.repository.DataRepository
import eu.magicsk.transi.repository.FollowedTrips
import eu.magicsk.transi.repository.IdsbkSession
import eu.magicsk.transi.repository.LiveBoard
import eu.magicsk.transi.timetable.OfflineRoutingEngine
import eu.magicsk.transi.util.Prefs
import eu.magicsk.transi.util.clock
import eu.magicsk.transi.util.getLineColor
import eu.magicsk.transi.util.readCachedStops
import eu.magicsk.transi.util.searchJourneys
import eu.magicsk.transi.util.tripIcon
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.time.Duration
import java.time.Instant
import javax.inject.Inject
import kotlin.math.roundToInt

/**
 * Follows what the user picked in ongoing notifications that update live: departures until the vehicle leaves (the iOS
 * table Live Activity), each on its stop's shared [LiveBoard], and one trip step by step until it arrives ([TripFollower],
 * the iOS trip Live Activity). The trip's notification, else the first departure's, keeps the service in the
 * foreground, so it survives the app being swiped away; it stops itself once nothing is followed. Started only from a
 * visible screen, the notifications, or the system restarting it for a saved trip; it runs on the main thread.
 *
 * A trip uses the location while the precise location is allowed: started from the app on screen, the service may run
 * as a `location` foreground service, which keeps receiving it in the background without the background location
 * permission.
 */
@AndroidEntryPoint
class LiveUpdatesService : Service() {
    @Inject
    lateinit var repository: DataRepository

    @Inject
    lateinit var idsbk: IdsbkSession

    @Inject
    lateinit var offlineRouting: OfflineRoutingEngine

    @Inject
    lateinit var followedTrips: FollowedTrips

    private val scope = MainScope()
    private val boards = mutableMapOf<Int, LiveBoard>()
    private val followed = mutableListOf<FollowedDeparture>()
    private var trip: TripFollower? = null
    /** The trip receives the location, which also makes the service a `location` one. */
    private var tripLocates = false
    private var location: Location? = null
    /** The notification that keeps the service in the foreground and the service types it went there with; 0 before. */
    private var foregroundId = 0
    private var foregroundType = 0
    private var nextId = FIRST_NOTIFICATION_ID
    private lateinit var notificationManager: NotificationManager
    private lateinit var wakeLock: PowerManager.WakeLock
    // The trip finds its boards, positions and other connections in the stops list.
    private val stops by lazy { scope.async(Dispatchers.IO) { readCachedStops(this@LiveUpdatesService).orEmpty().toList() } }
    // Each fix moves the trip on, also while the device sleeps between its own refreshes.
    private val locationListener = LocationListenerCompat {
        location = it
        trip?.refresh()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        notificationManager = getSystemService(NotificationManager::class.java)
        wakeLock = getSystemService(PowerManager::class.java).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Transi:LiveUpdates")
            .apply { setReferenceCounted(false) }
        createChannels()
        // Left from a run the system killed: only its foreground notification went away with it. The arrived trip's
        // last step is not ongoing and times out by itself.
        notificationManager.activeNotifications
            .filter { it.notification.channelId in CHANNELS && it.isOngoing }
            .forEach { notificationManager.cancel(it.id) }
        // iOS refreshes the remaining time every 10 s between board updates.
        scope.launch {
            while (isActive) {
                delay(10_000)
                refresh()
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_FOLLOW -> {
                val departure = IntentCompat.getParcelableExtra(intent, EXTRA_DEPARTURE, MHDTableData::class.java)
                val stop = IntentCompat.getParcelableExtra(intent, EXTRA_STOP, Stop::class.java)
                if (departure != null && stop != null) follow(departure, stop)
            }
            ACTION_STOP -> followed.firstOrNull { it.notificationId == intent.getIntExtra(EXTRA_NOTIFICATION_ID, 0) }?.let(::end)
            ACTION_START_TRIP -> intent.getStringExtra(EXTRA_JOURNEY)?.let { startTrip(plannerGson.fromJson(it, Journey::class.java), fromApp = true) }
            ACTION_END_TRIP -> endTrip()
            ACTION_KEEP_FOLLOWING -> trip?.keepFollowing()
            // From the app as it starts, or the system restarting the service after it ended the process. A trip the
            // system restarted gets the location once the app is on screen; showing it again moves the service into the
            // foreground as a location one.
            ACTION_RESTORE_TRIP, null -> when {
                trip == null -> restoreTrip(fromApp = intent != null)
                intent != null && !tripLocates && startLocation() -> {
                    tripLocates = true
                    trip?.let { showTrip(it.state, null) }
                }
            }
        }
        if (followed.isEmpty() && trip == null) stopSelf(startId)
        return if (trip != null) START_STICKY else START_NOT_STICKY
    }

    override fun onDestroy() {
        scope.cancel()
        boards.values.forEach { it.close() }
        followed.forEach { notificationManager.cancel(it.notificationId) }
        // The saved trip stays for the app to restore.
        trip?.close()
        stopLocation()
        if (wakeLock.isHeld) wakeLock.release()
        super.onDestroy()
    }

    // Departures

    private fun follow(departure: MHDTableData, stop: Stop) {
        // Following a departure again keeps its notification; the service is already in the foreground then.
        if (followed.any { departure.matches(it.reference) }) return
        val item = FollowedDeparture(nextId++, departure, stop, departure, System.currentTimeMillis())
        followed += item
        boards.getOrPut(stop.id) {
            LiveBoard(repository, idsbk, scope).apply {
                onDepartures = { refresh() }
                onVehicles = { refresh() }
                setStop(stop)
                connect()
            }
        }
        update(item, System.currentTimeMillis())
    }

    /** Matches the followed departures with their boards again, then keeps the device awake as near alerts need. */
    private fun refresh() {
        val now = System.currentTimeMillis()
        for (item in followed.toList()) {
            val board = boards[item.reference.stopId] ?: continue
            val row = board.departures.firstOrNull { it.matches(item.reference) }
            if (row != null) item.seen = true
            val expected = (row ?: item.row).departureTime
            if (departureEnded(row, item.seen, board.hasWholeBoard(item.reference.platform), expected, now)) {
                end(item)
                continue
            }
            if (row != null) item.row = row
            item.vehicle = board.vehicles[item.row.busID]
            update(item, now)
        }
        updateWakeLock(now)
    }

    private fun end(item: FollowedDeparture) {
        followed.remove(item)
        val stopId = item.reference.stopId
        if (followed.none { it.reference.stopId == stopId }) boards.remove(stopId)?.close()
        ended(item.notificationId)
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
                alertThreshold(),
            )
        }
        if (alertMinutes != null) item.lastAlertMinutes = alertMinutes
        item.posted = content
        show(item.notificationId, notification(item, content, alertMinutes != null, now))
    }

    private fun alertThreshold() = Prefs.get(this).getInt(Prefs.LIVE_ACTIVITY_THRESHOLD, Prefs.LIVE_ACTIVITY_THRESHOLD_DEFAULT)

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
        val stopIntent = PendingIntent.getService(
            this, item.notificationId,
            Intent(this, LiveUpdatesService::class.java).setAction(ACTION_STOP).putExtra(EXTRA_NOTIFICATION_ID, item.notificationId),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val share = Intent(Intent.ACTION_SEND).setType("text/plain")
            .putExtra(Intent.EXTRA_TEXT, departureShareText(this, row, item.stop))
        return NotificationCompat.Builder(this, channel(alert))
            .setSmallIcon(R.drawable.ic_bus)
            .setContentTitle(
                if (alert) getString(R.string.live_updates_alert_title, row.line, row.headsign, state.remaining)
                else getString(R.string.table_departure_title, row.line, row.headsign)
            )
            // A Live Update's progress style shows the whole text when expanded.
            .setContentText(if (promoted) text else lines.first())
            .setStyle(if (promoted) progressStyle(content.progress, color) else NotificationCompat.BigTextStyle().bigText(text))
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
            // Gone when following would have ended, also after the process ended without removing it.
            .setTimeoutAfter(row.departureTime + 30 * 60_000L - now)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            // The table opens on the stop with the row expanded (the iOS widget URL).
            .setContentIntent(open("transi://table/${row.stopId}/${row.id}", item.notificationId))
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

    // Trip

    private fun restoreTrip(fromApp: Boolean) {
        val saved = followedTrips.saved()
        if (saved == null) followedTrips.clear() else startTrip(saved.journey, fromApp, saved)
    }

    /** Follows [journey] ([saved] when restored), ending the trip followed so far; followed departures stay. */
    private fun startTrip(journey: Journey, fromApp: Boolean, saved: SavedTrip? = null) {
        trip?.end()
        stopLocation()
        val follower = TripFollower(
            journey, saved, stops, repository, idsbk, followedTrips, scope, ::searchAlternatives, ::tripFix,
            onUpdate = { state, alert ->
                showTrip(state, alert)
                updateWakeLock(System.currentTimeMillis())
            },
            onEnd = ::onTripEnded,
        )
        trip = follower
        tripLocates = fromApp && startLocation()
        // The first step at once: a service started for the foreground has seconds to show it.
        if (showTrip(follower.state, null)) follower.start()
    }

    private fun endTrip() {
        trip?.end() ?: return
        trip = null
        stopLocation()
        ended(TRIP_NOTIFICATION_ID)
    }

    /** The trip arrived (or would have, after a missed ride): its last step stays until 5 minutes after the arrival. */
    private fun onTripEnded(last: TripState) {
        trip = null
        stopLocation()
        ended(TRIP_NOTIFICATION_ID)
        val remaining = Duration.between(Instant.now(), last.time.plusSeconds(300)).toMillis()
        if (remaining > 0) {
            notificationManager.notify(
                TRIP_NOTIFICATION_ID, tripNotification(last, null).setAutoCancel(true).setTimeoutAfter(remaining).build(),
            )
        }
    }

    private suspend fun searchAlternatives(from: Stop, to: Stop, at: Instant): List<Journey> =
        searchJourneys(repository, idsbk, offlineRouting, Prefs.get(this), stops.await(), from, to, at)

    /** The last location when it tells stops apart: within 100 m, from the last half hour (it updates every 50 m). */
    private fun tripFix(): TripFix? {
        val fix = location ?: return null
        if (!fix.hasAccuracy() || fix.accuracy > 100f) return null
        if (SystemClock.elapsedRealtimeNanos() - fix.elapsedRealtimeNanos >= Duration.ofMinutes(30).toNanos()) return null
        return TripFix(StopGps(lon = fix.longitude, lat = fix.latitude), fix.accuracy.toDouble())
    }

    /**
     * Location updates for the trip while the precise location is allowed (coarse fixes cannot tell stops apart): high
     * accuracy every 15 s or 50 m, like the iOS app's best accuracy and 50 m filter, only while a trip is followed.
     */
    private fun startLocation(): Boolean {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            return false
        }
        val manager = getSystemService(LocationManager::class.java)
        val provider = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) LocationManager.FUSED_PROVIDER else LocationManager.GPS_PROVIDER
        if (provider !in manager.allProviders) return false
        location = manager.getLastKnownLocation(provider)
        val request = LocationRequestCompat.Builder(15_000)
            .setQuality(LocationRequestCompat.QUALITY_HIGH_ACCURACY)
            .setMinUpdateDistanceMeters(50f)
            .build()
        LocationManagerCompat.requestLocationUpdates(manager, provider, request, ContextCompat.getMainExecutor(this), locationListener)
        return true
    }

    // Revoking the permission ends the process, so it is still granted when the updates started.
    private fun stopLocation() {
        if (tripLocates && ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
            LocationManagerCompat.removeUpdates(getSystemService(LocationManager::class.java), locationListener)
        }
        tripLocates = false
        location = null
    }

    private fun showTrip(state: TripState, alert: TripAlert?): Boolean {
        val endIntent = PendingIntent.getService(
            this, TRIP_NOTIFICATION_ID, Intent(this, LiveUpdatesService::class.java).setAction(ACTION_END_TRIP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val notification = tripNotification(state, alert)
            .setOngoing(true)
            .setOnlyAlertOnce(alert == null)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            // Dismissing it stops following, like dismissing the iOS Live Activity.
            .setDeleteIntent(endIntent)
            .addAction(0, getString(R.string.trip_end), endIntent)
            .build()
        return show(TRIP_NOTIFICATION_ID, notification)
    }

    /**
     * The iOS trip Live Activity: the line's icon and colour, the step's title, detail, time and delay, the ride's progress
     * in the line colour and the change warning; an alert shows its title and body. Android 16+ promotes it to a Live
     * Update with the status bar chip; older versions colour it and show the progress below the text.
     */
    private fun tripNotification(state: TripState, alert: TripAlert?): NotificationCompat.Builder {
        val promoted = NotificationManagerCompat.from(this).canPostPromotedNotifications()
        val line = state.line
        val color = ContextCompat.getColor(
            this,
            when {
                line != null -> getLineColor(line, false)
                state.step == TripStep.ARRIVED -> R.color.table_green
                state.step == TripStep.MISSED -> R.color.table_orange
                else -> R.color.table_grey
            },
        )
        val detail = (alert?.body ?: state.detail).resolve(this)
        val delay = state.delay?.let { minutes -> delayText(minutes, "online").let { (res, value) -> getString(res, value) } }
        val lines = listOfNotNull(detail, listOfNotNull(clock(state.time), delay).joinToString(" · "), state.warning?.let { getString(it.text) })
        val text = lines.joinToString("\n")
        val progress = state.progress?.let { (it * 100).roundToInt() }
        return NotificationCompat.Builder(this, channel(alert != null))
            .setSmallIcon(tripIcon(state))
            .setContentTitle((alert?.title ?: state.title).resolve(this))
            // A Live Update's progress style shows the whole text when expanded.
            .setContentText(if (promoted) text else detail)
            .setStyle(if (promoted && progress != null) progressStyle(progress, color) else NotificationCompat.BigTextStyle().bigText(text))
            .apply { if (!promoted && progress != null) setProgress(100, progress, false) }
            .setCategory(NotificationCompat.CATEGORY_NAVIGATION)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setColor(color)
            // Colorized notifications are never promoted to Live Updates.
            .setColorized(!promoted)
            .setRequestPromotedOngoing(true)
            .setShortCriticalText(state.compact.resolve(this))
            .setShowWhen(false)
            .setContentIntent(open("transi://trip/active", TRIP_NOTIFICATION_ID))
    }

    // Both kinds

    /**
     * Posts the notification [id]. The trip's, else the first departure's, keeps the service in the foreground, as a
     * `location` service while the trip uses the location. False when the service may not enter the foreground: after
     * the system restarted it in the background on Android 12+, which the app's next start makes up for.
     */
    private fun show(id: Int, notification: Notification): Boolean {
        val owner = if (trip != null) TRIP_NOTIFICATION_ID else followed.firstOrNull()?.notificationId
        var type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0
        if (tripLocates && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) type = type or ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
        if (id != owner || (id == foregroundId && type == foregroundType)) {
            notificationManager.notify(id, notification)
            return true
        }
        try {
            ServiceCompat.startForeground(this, id, notification, type)
        } catch (_: IllegalStateException) {
            trip?.close()
            trip = null
            stopLocation()
            stopSelf()
            return false
        }
        val previous = foregroundId
        foregroundId = id
        foregroundType = type
        // Moving the foreground to another notification removes the previous one.
        if (previous != 0 && previous != id) repost(previous)
        return true
    }

    /** Posts [id] again, without alerting. */
    private fun repost(id: Int) {
        if (id == TRIP_NOTIFICATION_ID) {
            trip?.let { showTrip(it.state, null) }
            return
        }
        val item = followed.firstOrNull { it.notificationId == id } ?: return
        item.posted?.let { show(id, notification(item, it, alert = false, System.currentTimeMillis())) }
    }

    /** After the trip or a departure [id] was removed: the foreground moves to another one, or the service stops. */
    private fun ended(id: Int) {
        val next = if (trip != null) TRIP_NOTIFICATION_ID else followed.firstOrNull()?.notificationId
        if (next == null) {
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
            foregroundId = 0
            stopSelf()
        } else if (id == foregroundId) {
            repost(next)
        }
        notificationManager.cancel(id)
        updateWakeLock(System.currentTimeMillis())
    }

    /**
     * Keeps the device awake (2 minutes at a time) only while an alert may be due soon: a departure within the alert
     * threshold and a minute, or the trip near a step. Otherwise the refreshes pause while it sleeps, and board updates
     * and location fixes wake it.
     */
    private fun updateWakeLock(now: Long) {
        val window = -60_000L..(alertThreshold() + 60) * 1000L
        val near = followed.any { it.row.departureTime - now in window } || trip?.isNearAlert(Instant.ofEpochMilli(now)) == true
        if (near) wakeLock.acquire(WAKE_LOCK_MILLIS) else if (wakeLock.isHeld) wakeLock.release()
    }

    // Updates are silent; alerts move the notification to a heads-up channel, with or without sound, because a
    // channel's sound cannot change per notification on Android 8+ and Settings > Sound effects picks it.
    private fun channel(alert: Boolean) = when {
        !alert -> CHANNEL_UPDATES
        Prefs.get(this).getBoolean(Prefs.LIVE_ACTIVITIES_SOUNDS, true) -> CHANNEL_ALERTS
        else -> CHANNEL_ALERTS_SILENT
    }

    private fun progressStyle(progress: Int, color: Int) = NotificationCompat.ProgressStyle()
        .setProgressSegments(listOf(NotificationCompat.ProgressStyle.Segment(100).setColor(color)))
        .setProgress(progress)
        .setStyledByProgress(true)

    private fun open(link: String, requestCode: Int): PendingIntent = PendingIntent.getActivity(
        this, requestCode,
        Intent(Intent.ACTION_VIEW, link.toUri(), this, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

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

    /** One followed departure: its row when following started, the row last matched on its board, and what it shows. */
    private class FollowedDeparture(
        val notificationId: Int,
        val reference: MHDTableData,
        val stop: Stop,
        var row: MHDTableData,
        /** When following started; the Live Update's progress runs from it to the departure. */
        val startedAt: Long,
    ) {
        /** Its board listed it, so it can leave that board. */
        var seen = false
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
        private const val ACTION_START_TRIP = "eu.magicsk.transi.action.START_TRIP"
        private const val ACTION_END_TRIP = "eu.magicsk.transi.action.END_TRIP"
        private const val ACTION_KEEP_FOLLOWING = "eu.magicsk.transi.action.KEEP_FOLLOWING_TRIP"
        private const val ACTION_RESTORE_TRIP = "eu.magicsk.transi.action.RESTORE_TRIP"
        private const val EXTRA_DEPARTURE = "departure"
        private const val EXTRA_STOP = "stop"
        private const val EXTRA_JOURNEY = "journey"
        private const val EXTRA_NOTIFICATION_ID = "notification_id"
        // The planner's journey notification uses id 1.
        private const val TRIP_NOTIFICATION_ID = 2
        private const val FIRST_NOTIFICATION_ID = 100
        private const val WAKE_LOCK_MILLIS = 120_000L
        private const val CHANNEL_UPDATES = "live_updates"
        private const val CHANNEL_ALERTS = "live_update_alerts"
        private const val CHANNEL_ALERTS_SILENT = "live_update_alerts_silent"
        private val CHANNELS = setOf(CHANNEL_UPDATES, CHANNEL_ALERTS, CHANNEL_ALERTS_SILENT)

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

        /** Follows [journey] step by step in the trip notification, ending the trip followed so far. Call only from a visible screen. */
        fun startTrip(context: Context, journey: Journey) {
            ContextCompat.startForegroundService(
                context,
                Intent(context, LiveUpdatesService::class.java).setAction(ACTION_START_TRIP).putExtra(EXTRA_JOURNEY, plannerGson.toJson(journey)),
            )
        }

        fun endTrip(context: Context) {
            context.startService(Intent(context, LiveUpdatesService::class.java).setAction(ACTION_END_TRIP))
        }

        /** Goes on with the trip's steps after a missed ride; that ride is no longer checked. */
        fun keepFollowingTrip(context: Context) {
            context.startService(Intent(context, LiveUpdatesService::class.java).setAction(ACTION_KEEP_FOLLOWING))
        }

        /**
         * Follows the saved trip again after the process ended, or gives the location to one the system restarted; call
         * as the app comes on screen.
         */
        fun restoreTrip(context: Context) {
            try {
                context.startService(Intent(context, LiveUpdatesService::class.java).setAction(ACTION_RESTORE_TRIP))
            } catch (_: IllegalStateException) {
                // Android 8+ refuses while the app is not in the foreground after all; its next start restores it.
            }
        }
    }
}
