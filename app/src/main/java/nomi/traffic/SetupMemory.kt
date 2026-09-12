package nomi.traffic

import android.content.Context

object SetupMemory {

    private const val PREFS = "setup"
    private const val ONBOARDING_DONE = "onboarding_done"
    private const val FIRST_VOICE_PLAYED = "first_voice_played"

    fun isOnboardingDone(context: Context): Boolean =
        prefs(context).getBoolean(ONBOARDING_DONE, false)

    fun markOnboardingDone(context: Context) {
        prefs(context).edit().putBoolean(ONBOARDING_DONE, true).apply()
    }

    fun isFirstVoicePlayed(context: Context): Boolean =
        prefs(context).getBoolean(FIRST_VOICE_PLAYED, false)

    fun markFirstVoicePlayed(context: Context) {
        prefs(context).edit().putBoolean(FIRST_VOICE_PLAYED, true).apply()
    }

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
