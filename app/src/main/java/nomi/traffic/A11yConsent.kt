package nomi.traffic

import android.content.Context

enum class A11yConsentState { UNSET, AGREED, DECLINED }

/**
 * The user's answer to the accessibility disclosure. Settings open only after AGREED.
 * Kept in the "setup" prefs, which are excluded from backup, so a new device asks again.
 */
object A11yConsent {

    private const val PREFS = "setup"
    private const val KEY = "a11y_consent"

    fun state(context: Context): A11yConsentState =
        when (prefs(context).getString(KEY, null)) {
            A11yConsentState.AGREED.name -> A11yConsentState.AGREED
            A11yConsentState.DECLINED.name -> A11yConsentState.DECLINED
            else -> A11yConsentState.UNSET
        }

    fun isDeclined(context: Context): Boolean = state(context) == A11yConsentState.DECLINED

    fun agree(context: Context) = save(context, A11yConsentState.AGREED)

    fun decline(context: Context) = save(context, A11yConsentState.DECLINED)

    private fun save(context: Context, state: A11yConsentState) {
        prefs(context).edit().putString(KEY, state.name).apply()
    }

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
