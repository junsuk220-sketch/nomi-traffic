package nomi.traffic

import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.util.TypedValue
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.graphics.toColorInt
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import nomi.android.traffic.NaverNotificationAccess
import nomi.android.traffic.NavigationEventVoice
import nomi.android.traffic.RouteCardAccess
import nomi.android.traffic.RouteCardAdapter
import nomi.android.traffic.RouteCardMapLaunch

class MainActivity : AppCompatActivity() {

    private var showingOnboarding = false
    private var routeCards: RouteCardAdapter? = null
    private var a11yDialog: AlertDialog? = null
    private var a11yDialogSkipped = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        a11yDialogSkipped = false
        render()
    }

    override fun onResume() {
        super.onResume()
        render()
    }

    private fun render() {
        val granted = SetupSteps.all.associateWith { SetupSteps.isGranted(this, it) }
        val pending = SetupGate.firstPending(granted)
        val view = SetupGate.view(granted, SetupMemory.isOnboardingDone(this))
        when (view) {
            SetupView.HOME -> {
                val firstHome = !SetupMemory.isOnboardingDone(this)
                SetupMemory.markOnboardingDone(this)
                dismissA11yDialog()
                showHome()
                playFirstVoiceIfNeeded(firstHome)
            }
            SetupView.ONBOARDING -> {
                dismissA11yDialog()
                showOnboarding(pending ?: return)
            }
            SetupView.A11Y_DIALOG -> {
                showHome()
                showA11yDialog()
            }
        }
    }

    private fun showHome() {
        if (showingOnboarding || findViewById<View>(R.id.routeCardList) == null) {
            showingOnboarding = false
            routeCards = null
            setContentView(R.layout.activity_main)
            val adapter = RouteCardAdapter { card ->
                if (!RouteCardMapLaunch.open(this, card)) {
                    Toast.makeText(this, R.string.route_card_open_failed, Toast.LENGTH_SHORT).show()
                }
            }
            routeCards = adapter
            val listView = findViewById<RecyclerView>(R.id.routeCardList)
            listView.layoutManager = LinearLayoutManager(this)
            listView.adapter = adapter
        }
        reloadRouteCards()
    }

    private fun playFirstVoiceIfNeeded(firstHome: Boolean) {
        if (!FirstRunGuide.shouldPlay(firstHome.not(), SetupMemory.isFirstVoicePlayed(this))) {
            return
        }
        try {
            NavigationEventVoice.speakNotice(this, getString(R.string.first_voice_speech))
        } catch (_: Exception) {
        }
        SetupMemory.markFirstVoicePlayed(this)
    }

    private fun reloadRouteCards() {
        val adapter = routeCards ?: return
        val now = System.currentTimeMillis()
        val items = RouteCardAccess.repository(this).recent()
        adapter.submit(items, now)
        findViewById<View>(R.id.routeCardEmpty).visibility =
            if (items.isEmpty()) View.VISIBLE else View.GONE
    }

    private fun showOnboarding(step: SetupStep) {
        val already = showingOnboarding && findViewById<TextView>(R.id.onboardingTitle) != null
        if (!already) {
            showingOnboarding = true
            routeCards = null
            setContentView(R.layout.activity_onboarding)
        }
        val index = SetupSteps.index(step)
        val total = SetupSteps.all.size
        findViewById<TextView>(R.id.onboardingProgress).text =
            getString(R.string.onboarding_step_progress, index + 1, total)
        findViewById<TextView>(R.id.onboardingTitle).text = titleOf(step)
        findViewById<TextView>(R.id.onboardingDesc).text = descOf(step)
        findViewById<Button>(R.id.onboardingAllow).text = buttonOf(step)
        val showHint = step == SetupStep.A11Y
        findViewById<View>(R.id.onboardingA11yNames).visibility =
            if (showHint) View.VISIBLE else View.GONE
        findViewById<View>(R.id.onboardingHintImage).visibility =
            if (showHint) View.VISIBLE else View.GONE
        findViewById<View>(R.id.onboardingHintCaption).visibility =
            if (showHint) View.VISIBLE else View.GONE
        drawDots(findViewById(R.id.onboardingDots), index, total)
        findViewById<Button>(R.id.onboardingAllow).setOnClickListener {
            if (!openStep(step)) {
                Toast.makeText(this, descOf(step), Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun showA11yDialog() {
        if (a11yDialogSkipped) return
        if (a11yDialog?.isShowing == true) return
        val view = layoutInflater.inflate(R.layout.dialog_a11y_guide, null)
        val dialog = AlertDialog.Builder(this)
            .setView(view)
            .setOnCancelListener { a11yDialogSkipped = true }
            .create()
        view.findViewById<Button>(R.id.a11yDialogAllow).setOnClickListener {
            AccessibilityAccess.openList(this)
        }
        a11yDialog = dialog
        dialog.show()
    }

    private fun dismissA11yDialog() {
        a11yDialog?.dismiss()
        a11yDialog = null
        a11yDialogSkipped = false
    }

    private fun openStep(step: SetupStep): Boolean = when (step) {
        SetupStep.NAVER_NOTIFY -> NaverNotificationAccess.openSettings(this)
        SetupStep.A11Y -> AccessibilityAccess.openList(this)
    }

    private fun titleOf(step: SetupStep): String = when (step) {
        SetupStep.NAVER_NOTIFY -> getString(R.string.onboarding_naver_notify_title)
        SetupStep.A11Y -> getString(R.string.onboarding_a11y_title)
    }

    private fun descOf(step: SetupStep): String = when (step) {
        SetupStep.NAVER_NOTIFY -> getString(R.string.onboarding_naver_notify_desc)
        SetupStep.A11Y -> getString(R.string.onboarding_a11y_desc)
    }

    private fun buttonOf(step: SetupStep): String = when (step) {
        SetupStep.NAVER_NOTIFY -> getString(R.string.onboarding_allow)
        SetupStep.A11Y -> getString(R.string.a11y_off_action)
    }

    private fun drawDots(row: LinearLayout, active: Int, total: Int) {
        row.removeAllViews()
        val gap = dp(8)
        for (i in 0 until total) {
            val dot = View(this)
            val size = if (i == active) dp(10) else dp(8)
            val params = LinearLayout.LayoutParams(size, size)
            if (i > 0) params.marginStart = gap
            dot.layoutParams = params
            val color = when {
                i == active -> "#E8A838"
                i < active -> "#C9A24A"
                else -> "#D9D3C8"
            }
            dot.background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(color.toColorInt())
            }
            row.addView(dot)
        }
    }

    private fun dp(value: Int): Int =
        TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            value.toFloat(),
            resources.displayMetrics,
        ).toInt()
}
