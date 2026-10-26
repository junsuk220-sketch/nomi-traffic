package nomi.traffic

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import android.view.Gravity
import android.view.WindowManager
import android.widget.TextView

/**
 * "설정이 완료되었습니다" when a switch is turned on after setup opened accessibility settings.
 * Shown only then: not after a reboot or an app update reconnects the service.
 * Drawn as an accessibility overlay: a plain Toast is suppressed while the app has no
 * notification permission yet, which is the case during first setup.
 */
object A11yDoneNotice {

    const val WINDOW_MS = 10 * 60_000L
    const val SHOW_MS = 4_000L
    private const val PREFS = "a11y_done_notice"

    fun shouldShow(armedAtMs: Long, nowMs: Long): Boolean =
        armedAtMs > 0L && nowMs - armedAtMs in 0..WINDOW_MS

    fun arm(context: Context, item: A11yItem) {
        prefs(context).edit().putLong(item.name, System.currentTimeMillis()).apply()
    }

    fun disarm(context: Context) {
        prefs(context).edit().clear().apply()
    }

    fun showIfArmed(service: AccessibilityService, item: A11yItem) {
        val prefs = prefs(service)
        val armedAt = prefs.getLong(item.name, 0L)
        if (armedAt == 0L) return
        prefs.edit().remove(item.name).apply()
        if (!shouldShow(armedAt, System.currentTimeMillis())) return
        val text = when (item) {
            A11yItem.NAVER -> R.string.a11y_done_naver
            A11yItem.GOOGLE -> R.string.a11y_done_google
        }
        show(service, service.getString(text))
    }

    private fun show(service: AccessibilityService, text: String) {
        val wm = service.getSystemService(WindowManager::class.java) ?: return
        val dp = { v: Int ->
            TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), service.resources.displayMetrics).toInt()
        }
        val amber = service.getColor(R.color.amber)
        val side = minOf(dp(300), service.resources.displayMetrics.widthPixels - dp(48))
        val view = TextView(service).apply {
            this.text = text
            setTextColor(amber)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 24f)
            setTypeface(typeface, Typeface.BOLD)
            setLineSpacing(dp(6).toFloat(), 1f)
            gravity = Gravity.CENTER
            minHeight = side
            setPadding(dp(24), dp(24), dp(24), dp(24))
            background = GradientDrawable().apply {
                cornerRadius = dp(28).toFloat()
                setColor(0xF2121212.toInt())
                setStroke(dp(3), amber)
            }
        }
        val params = WindowManager.LayoutParams(
            side,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.CENTER
        }
        wm.addView(view, params)
        Handler(Looper.getMainLooper()).postDelayed({
            try {
                wm.removeView(view)
            } catch (_: Exception) {
            }
        }, SHOW_MS)
    }

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
