package eu.magicsk.transi.util

import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.text.Spanned
import androidx.core.app.NotificationCompat
import androidx.core.text.buildSpannedString
import eu.magicsk.transi.MainActivity
import eu.magicsk.transi.R

/** Posts a plain notification that opens the app, with an optional share action (the trip planner's journey). */
fun NotificationManager.sendNotification(
    messageTitle: String,
    messageBody: String,
    messageBigBody: Spanned,
    shareMessageBody: Spanned = buildSpannedString {},
    silent: Boolean,
    channel_id: String,
    notification_id: Int,
    ongoing: Boolean,
    applicationContext: Context,
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
    if (shareAction) {
        builder.addAction(
            R.drawable.ic_launcher_foreground,
            applicationContext.getString(R.string.share),
            sharePendingIntent
        )
    }

    notify(notification_id, builder.build())
}
