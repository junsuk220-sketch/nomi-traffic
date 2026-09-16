package nomi.android.traffic

import android.app.Notification
import android.os.Bundle
import android.os.PowerManager
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import nomi.android.traffic.eventfirst.EventFirstEngine
import nomi.product.nav.NavigationEventType

/**
 * Reads Naver Map transit notifications and hands [NavigationEvent]s to speech.
 * Walk notifications are not watched. Does not click notifications or drive NavigationSession.
 */
class NaverNavigationNotificationListenerService : NotificationListenerService() {

    override fun onListenerConnected() {
        Log.i(NaverMapNotification.TAG, "listener connected")
        NaverRideObservationLog.attach(filesDir)
        EventFirstEngine.attach(filesDir)
        NavigationEventVoice.prepare(this)
        activeNotifications.orEmpty().forEach { onNotificationPosted(it) }
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        if (sbn == null) return
        if (sbn.packageName == GoogleMapsTransit.PACKAGE) return
        if (sbn.packageName != NaverMapNotification.PACKAGE) return
        val channel = sbn.notification.channelId
        if (!NaverMapNotification.isWatchedChannel(channel)) return
        if (channel == NaverMapNotification.CHANNEL_WALK) {
            recordRideObservation(sbn, channel)
            noteNearBoard(sbn)
            return
        }
        logPosted(sbn, channel)
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
        if (sbn == null) return
        if (sbn.packageName != NaverMapNotification.PACKAGE) return
        val channel = sbn.notification.channelId
        if (!NaverMapNotification.isWatchedChannel(channel)) return
        Log.i(
            NaverMapNotification.TAG,
            "[NAVER_REMOVED] id=${sbn.id} channel=$channel",
        )
        NaverRideObservationLog.recordNotificationRemoved(
            ts = System.currentTimeMillis(),
            pkg = sbn.packageName,
            interactive = getSystemService(PowerManager::class.java)?.isInteractive != false,
            id = sbn.id,
            channel = channel,
        )
        RouteCardAccess.naverIntake(this).onNotificationRemoved(sbn.id)
    }

    private fun logPosted(sbn: StatusBarNotification, channel: String) {
        val extras = sbn.notification.extras
        val title = extraText(extras, Notification.EXTRA_TITLE)
        val text = extraText(extras, Notification.EXTRA_TEXT)
        val bigText = extraText(extras, Notification.EXTRA_BIG_TEXT)
        val nowbarPrimary = extraText(extras, NaverMapNotification.EXTRA_NOWBAR_PRIMARY)
        val chip = extraText(extras, NaverMapNotification.EXTRA_CHIP)
        val secondary = extraText(extras, NaverMapNotification.EXTRA_SECONDARY)
        val nowbarSecondary = extraText(extras, NaverMapNotification.EXTRA_NOWBAR_SECONDARY)
        val primary = extraText(extras, NaverMapNotification.EXTRA_PRIMARY)
        val chipExpandedText = chip
        NaverRideObservationLog.recordNotification(
            ts = System.currentTimeMillis(),
            pkg = sbn.packageName,
            interactive = getSystemService(PowerManager::class.java)?.isInteractive != false,
            id = sbn.id,
            channel = channel,
            title = title,
            text = text,
            bigText = bigText,
            nowbarPrimary = nowbarPrimary,
            nowbarSecondary = nowbarSecondary,
            chip = chip,
            primary = primary,
            secondary = secondary,
            progress = extraProgress(extras),
            chipExpandedText = chipExpandedText,
        )
        Log.i(
            NaverMapNotification.TAG,
            NaverMapNotification.transitLog(
                id = sbn.id,
                channel = channel,
                title = title,
                text = text,
                nowbarPrimary = nowbarPrimary,
                chip = chip,
                extrasNote = transitExtrasNote(extras, text),
            ),
        )
        Log.i(
            NaverMapNotification.TAG,
            "[NAVER_EXTRAS] id=${sbn.id} channel=$channel " +
                "contentTitle=${title.orEmpty()} android.text=${text.orEmpty()} " +
                NaverMapNotification.extrasDump(flattenExtras(extras)),
        )
        // Event-First judges the same 302 from title/text alone. It claims speech
        // only once its flag is on, and then the legacy path must stay quiet for
        // this notification — never two judges on one event.
        val eventFirstOwnsSpeech = EventFirstEngine.onTransitNotification(
            context = this,
            title = title,
            text = text,
            nowMs = System.currentTimeMillis(),
        )
        val event = NaverNotificationParser.parse(
            NaverNotificationParser.Snapshot(
                packageName = sbn.packageName,
                notificationId = sbn.id,
                channel = channel,
                title = title,
                text = text,
                action = nowbarPrimary,
                chip = chip,
                secondary = secondary,
                nowbarSecondary = nowbarSecondary,
                bigText = bigText,
                timestampMillis = System.currentTimeMillis(),
            ),
        )
        if (event != null && event.type == NavigationEventType.TRANSIT) {
            Log.i(
                NaverMapNotification.TAG,
                "[NAV_EVENT] source=${event.source} type=${event.type} " +
                    "id=${event.notificationId} " +
                    "action=${event.action} " +
                    "raw=${event.rawText}",
            )
            if (eventFirstOwnsSpeech) {
                Log.i(NaverMapNotification.TAG, "[EVENT_FIRST] legacy 302 judging skipped")
            } else {
                listOf(title, text, nowbarPrimary, chip, secondary, nowbarSecondary, event.action)
                    .forEach {
                        NavigationEventVoice.noteNaverNearBoard(it)
                        NavigationEventVoice.noteNaverPrepareAlight(it)
                        NavigationEventVoice.noteNaverAlightStop(it)
                    }
                val startHint = listOf(title, text, nowbarPrimary, chip)
                    .any { NaverTripStartParser.isStartPhrase(it) }
                if (startHint) {
                    NavigationEventVoice.armNaverTripStart()
                    NavigationEventVoice.pinNaverFromTripCache()
                    Log.i(NaverMapNotification.TAG, "[NAVER_TRIP] notification start phrase")
                }
                // Do not re-arm trip start on every 302 ETA update — that only belongs
                // on the first live edge / start phrase.
                NavigationEventVoice.offer(this, event)
            }
        }
        RouteCardAccess.naverIntake(this).onNotificationPosted(
            notificationId = sbn.id,
            atMillis = System.currentTimeMillis(),
            destinationBlobs = listOf(
                title,
                text,
                bigText,
                nowbarPrimary,
                event?.action,
                event?.rawText,
            ),
        )
    }

    private fun flattenExtras(extras: Bundle): List<Pair<String, String>> {
        val keys = extras.keySet().orEmpty().sorted()
        return keys.mapNotNull { key ->
            val rendered = renderExtra(extras, key) ?: return@mapNotNull null
            key to rendered
        }
    }

    private fun renderExtra(extras: Bundle, key: String): String? {
        val value = extras.get(key) ?: return "null"
        return when (value) {
            is CharSequence -> value.toString()
            is Number, is Boolean -> value.toString()
            else -> value.javaClass.simpleName
        }
    }

    private fun transitExtrasNote(extras: Bundle, text: String?): String {
        val secondary = extraText(extras, NaverMapNotification.EXTRA_SECONDARY)
        val nowbarSecondary = extraText(extras, NaverMapNotification.EXTRA_NOWBAR_SECONDARY)
        val primary = extraText(extras, NaverMapNotification.EXTRA_PRIMARY)
        val bus = text ?: secondary ?: nowbarSecondary
        return "busArrival=${bus.orEmpty()} primary=${primary.orEmpty()} " +
            "secondary=${secondary.orEmpty()} nowbarSecondary=${nowbarSecondary.orEmpty()}"
    }

    private fun extraText(extras: Bundle, key: String): String? {
        val value = extras.getCharSequence(key) ?: extras.getString(key) ?: return null
        val text = value.toString()
        return text.ifBlank { null }
    }

    private fun recordRideObservation(sbn: StatusBarNotification, channel: String) {
        val extras = sbn.notification.extras
        val chip = extraText(extras, NaverMapNotification.EXTRA_CHIP)
        NaverRideObservationLog.recordNotification(
            ts = System.currentTimeMillis(),
            pkg = sbn.packageName,
            interactive = getSystemService(PowerManager::class.java)?.isInteractive != false,
            id = sbn.id,
            channel = channel,
            title = extraText(extras, Notification.EXTRA_TITLE),
            text = extraText(extras, Notification.EXTRA_TEXT),
            bigText = extraText(extras, Notification.EXTRA_BIG_TEXT),
            nowbarPrimary = extraText(extras, NaverMapNotification.EXTRA_NOWBAR_PRIMARY),
            nowbarSecondary = extraText(extras, NaverMapNotification.EXTRA_NOWBAR_SECONDARY),
            chip = chip,
            primary = extraText(extras, NaverMapNotification.EXTRA_PRIMARY),
            secondary = extraText(extras, NaverMapNotification.EXTRA_SECONDARY),
            progress = extraProgress(extras),
            chipExpandedText = chip,
        )
    }

    private fun extraProgress(extras: Bundle): Int? {
        if (extras.containsKey("android.ongoingActivityNoti.progress")) {
            return extras.getInt("android.ongoingActivityNoti.progress")
        }
        if (!extras.containsKey(Notification.EXTRA_PROGRESS)) return null
        return extras.getInt(Notification.EXTRA_PROGRESS)
    }

    private fun noteNearBoard(sbn: StatusBarNotification) {
        val extras = sbn.notification.extras
        listOf(
            extraText(extras, Notification.EXTRA_TITLE),
            extraText(extras, Notification.EXTRA_TEXT),
            extraText(extras, Notification.EXTRA_BIG_TEXT),
            extraText(extras, NaverMapNotification.EXTRA_NOWBAR_PRIMARY),
            extraText(extras, NaverMapNotification.EXTRA_CHIP),
            extraText(extras, NaverMapNotification.EXTRA_PRIMARY),
            extraText(extras, NaverMapNotification.EXTRA_SECONDARY),
            extraText(extras, NaverMapNotification.EXTRA_NOWBAR_SECONDARY),
        ).forEach {
            NavigationEventVoice.noteNaverNearBoard(it)
            NavigationEventVoice.noteNaverPrepareAlight(it)
        }
    }
}
