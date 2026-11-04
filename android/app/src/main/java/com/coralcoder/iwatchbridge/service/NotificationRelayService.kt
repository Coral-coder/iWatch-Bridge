package com.coralcoder.iwatchbridge.service

import android.app.Notification
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import com.coralcoder.iwatchbridge.core.NotificationQueue

/**
 * Mirrors phone notifications to the watch, like iPhone's "Mirror my iPhone" setting. Each
 * notification becomes a bulletin for the watch's bulletin distributor; they queue until the
 * IDS session to the active watch is up.
 */
class NotificationRelayService : NotificationListenerService() {
    override fun onListenerConnected() { instance = this }
    override fun onListenerDisconnected() { if (instance === this) instance = null }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        val n = sbn.notification
        if (sbn.isOngoing || n.flags and Notification.FLAG_GROUP_SUMMARY != 0) return
        actions[sbn.key] = n.actions?.toList().orEmpty()
        val extras = n.extras
        NotificationQueue.post(
            NotificationQueue.Item(
                key = sbn.key,
                app = sbn.packageName,
                appName = runCatching { packageManager.getApplicationLabel(packageManager.getApplicationInfo(sbn.packageName, 0)).toString() }.getOrDefault(sbn.packageName),
                title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty(),
                body = (extras.getCharSequence(Notification.EXTRA_BIG_TEXT) ?: extras.getCharSequence(Notification.EXTRA_TEXT))?.toString().orEmpty(),
                postedAt = sbn.postTime,
            ),
        )
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification) {
        actions.remove(sbn.key)
        NotificationQueue.remove(sbn.key)
    }

    companion object {
        @Volatile private var instance: NotificationRelayService? = null
        private val actions = java.util.concurrent.ConcurrentHashMap<String, List<Notification.Action>>()

        private fun replyAction(key: String) = actions[key]?.firstOrNull { a -> a.remoteInputs?.any { it.allowFreeFormInput } == true }

        fun canReply(key: String) = replyAction(key) != null

        fun dismiss(key: String) { instance?.cancelNotification(key) }

        /** Answer a messaging notification from the watch, as Android Auto and Wear OS do. */
        fun reply(context: android.content.Context, key: String, text: String) {
            val a = replyAction(key) ?: return
            val intent = android.content.Intent()
            val results = android.os.Bundle()
            a.remoteInputs.filter { it.allowFreeFormInput }.forEach { results.putCharSequence(it.resultKey, text) }
            android.app.RemoteInput.addResultsToIntent(a.remoteInputs, intent, results)
            runCatching { a.actionIntent.send(context, 0, intent) }
        }

        fun invokeAction(key: String, title: String) {
            actions[key]?.firstOrNull { it.title?.toString() == title }?.let { runCatching { it.actionIntent.send() } }
        }
    }
}
