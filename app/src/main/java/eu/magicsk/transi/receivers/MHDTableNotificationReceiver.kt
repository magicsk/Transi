package eu.magicsk.transi.receivers

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import eu.magicsk.transi.util.cancelTableNotification

class MHDTableNotificationReceiver : BroadcastReceiver()  {
    override fun onReceive(context: Context, intent: Intent) {
        cancelTableNotification(context)
    }
}