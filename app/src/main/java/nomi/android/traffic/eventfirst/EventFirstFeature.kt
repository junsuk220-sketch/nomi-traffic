package nomi.android.traffic.eventfirst

import java.io.File

/**
 * Whether Event-First is allowed to speak.
 *
 * Default is false: the Event-First path parses, judges and logs every 302
 * while the existing Journey path stays the only voice. Two judges must never
 * speak at once, so turning this on also takes 302 speech away from the legacy
 * path (see NaverNavigationNotificationListenerService).
 *
 * Flip it on device by creating `event-first-speech.enabled` in filesDir, the
 * same way the ride observer is armed.
 */
internal object EventFirstFeature {

    const val FLAG_NAME = "event-first-speech.enabled"

    @Volatile
    var speechEnabled: Boolean = false

    fun attach(filesDir: File) {
        if (!speechEnabled && File(filesDir, FLAG_NAME).exists()) {
            speechEnabled = true
        }
    }
}
