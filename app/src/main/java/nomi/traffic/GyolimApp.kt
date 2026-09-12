package nomi.traffic

import android.app.Application
import nomi.android.traffic.AccessibilityOffAlert
import nomi.android.traffic.NavigationEventVoice

class GyolimApp : Application() {
    override fun onCreate() {
        super.onCreate()
        NavigationEventVoice.prepare(this)
        AccessibilityOffAlert.cancelLeftoverNotice(this)
    }
}
