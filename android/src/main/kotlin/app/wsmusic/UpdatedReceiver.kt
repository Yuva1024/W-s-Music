package app.wsmusic

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build

/**
 * Android tells the app when it has been replaced by an update: reopen it, so updating feels like a restart.
 * (Android 10 and newer don't let apps open themselves from the background; there the person opens it.)
 */
class UpdatedReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_MY_PACKAGE_REPLACED || Build.VERSION.SDK_INT >= 29) return
        context.startActivity(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}
