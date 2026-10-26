package nomi.traffic

import android.app.Application
import nomi.android.traffic.AccessibilityOffAlert
import nomi.android.traffic.FieldLogs
import nomi.android.traffic.NavigationEventVoice

class GyolimApp : Application() {
    override fun onCreate() {
        super.onCreate()
        val dir = filesDir
        Thread({ FieldLogs.purgeIfRelease(dir) }, "field-logs-purge").start()
        NavigationEventVoice.prepare(this)
        AccessibilityOffAlert.cancelLeftoverNotice(this)
    }
}
