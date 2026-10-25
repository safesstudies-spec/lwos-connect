package com.lwos.connect.service

import android.app.Notification
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification

/**
 * Forwards other apps' notifications (title + text only — nothing
 * actionable like replying or answering calls yet; see the project
 * roadmap for why that's a much bigger undertaking) to whichever LWOS
 * devices are currently paired and reachable.
 *
 * This needs a permission Android doesn't offer through a normal
 * runtime dialog: the user has to flip it on manually under
 * Settings -> Apps -> Special app access -> Notification access. See
 * MainActivity's "Enable notification mirroring" button, which deep
 * links straight to that screen instead of making you hunt for it.
 */
class NotificationMirrorListenerService : NotificationListenerService() {

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        super.onNotificationPosted(sbn)

        // Don't mirror our own "watching for devices" notification.
        if (sbn.packageName == packageName) return

        val extras = sbn.notification.extras
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()
        val text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()
        if (title.isNullOrBlank() && text.isNullOrBlank()) return

        val appLabel = try {
            val pm = packageManager
            pm.getApplicationLabel(pm.getApplicationInfo(sbn.packageName, 0)).toString()
        } catch (e: Exception) {
            sbn.packageName
        }

        ContinuityService.instance?.broadcastNotification(appLabel, title, text)
    }
}
