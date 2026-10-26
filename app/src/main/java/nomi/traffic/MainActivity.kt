package nomi.traffic

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.util.TypedValue
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
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
import nomi.android.traffic.setupnudge.SetupNudgeShadow

class MainActivity : AppCompatActivity() {

    private var showingOnboarding = false
    private var routeCards: RouteCardAdapter? = null
    private var a11yDialog: AlertDialog? = null
    private var a11yDialogView: View? = null
    private var a11yDialogSkipped = false
    private var consentFromHome = false
    private var a11yOpened = false
    private val consentBack = object : OnBackPressedCallback(false) {
        override fun handleOnBackPressed() {
            consentFromHome = false
            isEnabled = false
            render()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        a11yDialogSkipped = false
        onBackPressedDispatcher.addCallback(this, consentBack)
        render()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        a11yDialogSkipped = false
    }

    override fun onResume() {
        super.onResume()
        render()
        if (a11yOpened) {
            a11yOpened = false
            A11yDoneNotice.disarm(this)
        }
    }

    private fun openA11y(): Boolean {
        A11yNeeds.off(this).forEach { A11yDoneNotice.arm(this, it) }
        a11yOpened = AccessibilityAccess.openList(this)
        if (!a11yOpened) A11yDoneNotice.disarm(this)
        return a11yOpened
    }

    private fun render() {
        val consent = A11yConsent.state(this)
        val granted = SetupSteps.all.associateWith { SetupSteps.isGranted(this, it) }
        val pending = SetupGate.firstPending(granted, consent)
        val view = if (consentFromHome) {
            SetupView.A11Y_CONSENT
        } else {
            SetupGate.view(granted, SetupMemory.isOnboardingDone(this), consent)
        }
        when (view) {
            SetupView.A11Y_CONSENT -> {
                dismissA11yDialog()
                showConsent()
            }
            SetupView.HOME -> {
                val firstHome = !SetupMemory.isOnboardingDone(this)
                SetupMemory.markOnboardingDone(this)
                dismissA11yDialog()
                SetupNudgeShadow.cancelNotice(this)
                showHome()
                playFirstVoiceIfNeeded(firstHome)
                askNoticePermission()
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
            findViewById<Button>(R.id.homeA11yEnable).setOnClickListener {
                consentFromHome = true
                consentBack.isEnabled = true
                render()
            }
        }
        findViewById<View>(R.id.homeA11yEnable).visibility =
            if (A11yConsent.isDeclined(this)) View.VISIBLE else View.GONE
        reloadRouteCards()
    }

    /** Settings are opened only from screens reached after 동의합니다. */
    private fun showConsent() {
        if (findViewById<View>(R.id.a11yConsentAgree) != null) return
        showingOnboarding = false
        routeCards = null
        setContentView(R.layout.activity_a11y_consent)
        findViewById<Button>(R.id.a11yConsentAgree).setOnClickListener {
            leaveConsent()
            A11yConsent.agree(this)
            render()
        }
        findViewById<Button>(R.id.a11yConsentDisagree).setOnClickListener {
            leaveConsent()
            A11yConsent.decline(this)
            SetupNudgeShadow.cancelNotice(this)
            render()
            if (A11yItem.entries.any { A11yNeeds.isOn(this, it) }) showTurnOffHint()
        }
    }

    private fun leaveConsent() {
        consentFromHome = false
        consentBack.isEnabled = false
    }

    private fun showTurnOffHint() {
        AlertDialog.Builder(this)
            .setTitle(R.string.a11y_consent_turn_off_title)
            .setMessage(R.string.a11y_consent_turn_off_desc)
            .setPositiveButton(R.string.a11y_consent_turn_off_open) { _, _ ->
                AccessibilityAccess.openList(this)
            }
            .setNegativeButton(R.string.a11y_consent_turn_off_later, null)
            .show()
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

    /** Lets the "setting is off" notice be posted later. Asked once, after setup. */
    private fun askNoticePermission() {
        if (Build.VERSION.SDK_INT < 33) return
        if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        val prefs = getSharedPreferences("setup", MODE_PRIVATE)
        if (prefs.getBoolean(NOTICE_ASKED, false)) return
        prefs.edit().putBoolean(NOTICE_ASKED, true).apply()
        try {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        } catch (_: Exception) {
        }
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
        val visible = SetupSteps.visible(this)
        val index = visible.indexOf(step).coerceAtLeast(0)
        val total = visible.size
        findViewById<TextView>(R.id.onboardingProgress).text =
            getString(R.string.onboarding_step_progress, index + 1, total)
        findViewById<TextView>(R.id.onboardingTitle).text = titleOf(step)
        findViewById<TextView>(R.id.onboardingDesc).text = descOf(step)
        findViewById<Button>(R.id.onboardingAllow).text = buttonOf(step)
        val showHint = if (step == SetupStep.A11Y) View.VISIBLE else View.GONE
        listOf(
            R.id.onboardingA11yStep1,
            R.id.onboardingHintImage,
            R.id.onboardingA11yStep2,
            R.id.onboardingA11yNames,
        ).forEach { findViewById<View>(it).visibility = showHint }
        if (step == SetupStep.A11Y) {
            bindA11yNames(findViewById(R.id.onboardingA11yNames), findViewById(R.id.onboardingA11yStep2))
        }
        val showSwitch = if (step == SetupStep.NAVER_NOTIFY) View.VISIBLE else View.GONE
        findViewById<View>(R.id.onboardingNotifyImage).visibility = showSwitch
        findViewById<View>(R.id.onboardingNotifyCaption).visibility = showSwitch
        findViewById<View>(R.id.onboardingDesc).visibility =
            if (step == SetupStep.NAVER_NOTIFY || step == SetupStep.A11Y) View.GONE else View.VISIBLE
        drawDots(findViewById(R.id.onboardingDots), index, total)
        findViewById<Button>(R.id.onboardingAllow).setOnClickListener {
            if (!openStep(step)) {
                Toast.makeText(this, descOf(step), Toast.LENGTH_LONG).show()
            }
        }
        val mapPkg = SetupSteps.mapPackage(step)
        val askConfirm = mapPkg != null && MapAppNotifyAccess.isAllowed(this, mapPkg) == null
        val allow = findViewById<Button>(R.id.onboardingAllow)
        (allow.layoutParams as LinearLayout.LayoutParams).bottomMargin =
            if (askConfirm) dp(8) else dp(36)
        allow.requestLayout()
        val confirm = findViewById<Button>(R.id.onboardingConfirm)
        confirm.visibility = if (askConfirm) View.VISIBLE else View.GONE
        confirm.setOnClickListener {
            if (mapPkg != null) MapAppNotifyAccess.markConfirmed(this, mapPkg)
            render()
        }
    }

    private fun showA11yDialog() {
        if (a11yDialogSkipped) return
        a11yDialogView?.let { shown ->
            if (a11yDialog?.isShowing == true) {
                bindA11yDialog(shown)
                return
            }
        }
        val view = layoutInflater.inflate(R.layout.dialog_a11y_guide, null)
        val dialog = AlertDialog.Builder(this)
            .setView(view)
            .setOnCancelListener { a11yDialogSkipped = true }
            .create()
        bindA11yDialog(view)
        view.findViewById<Button>(R.id.a11yDialogAllow).setOnClickListener {
            openA11y()
        }
        a11yDialog = dialog
        a11yDialogView = view
        dialog.show()
    }

    private fun bindA11yDialog(view: View) {
        view.findViewById<TextView>(R.id.a11yDialogTitle).text = a11yTitle()
        bindA11yNames(view, view.findViewById(R.id.a11yDialogStep2))
    }

    private fun dismissA11yDialog() {
        a11yDialog?.dismiss()
        a11yDialog = null
        a11yDialogView = null
        a11yDialogSkipped = false
    }

    private fun openStep(step: SetupStep): Boolean = when (step) {
        SetupStep.NAVER_NOTIFY -> NaverNotificationAccess.openSettings(this)
        SetupStep.A11Y -> openA11y()
        SetupStep.GOOGLE_MAPS_NOTIFY, SetupStep.NAVER_MAP_NOTIFY ->
            MapAppNotifyAccess.openSettings(this, SetupSteps.mapPackage(step)!!)
    }

    private fun titleOf(step: SetupStep): String = when (step) {
        SetupStep.NAVER_NOTIFY -> getString(R.string.onboarding_naver_notify_title)
        SetupStep.A11Y -> a11yTitle()
        SetupStep.GOOGLE_MAPS_NOTIFY -> getString(R.string.onboarding_google_maps_notify_title)
        SetupStep.NAVER_MAP_NOTIFY -> getString(R.string.onboarding_naver_map_notify_title)
    }

    private fun descOf(step: SetupStep): String = when (step) {
        SetupStep.NAVER_NOTIFY -> getString(R.string.onboarding_naver_notify_desc)
        SetupStep.A11Y -> getString(R.string.onboarding_a11y_desc)
        SetupStep.GOOGLE_MAPS_NOTIFY -> getString(R.string.onboarding_google_maps_notify_desc)
        SetupStep.NAVER_MAP_NOTIFY -> getString(R.string.onboarding_naver_map_notify_desc)
    }

    private fun buttonOf(step: SetupStep): String = when (step) {
        SetupStep.NAVER_NOTIFY -> getString(R.string.onboarding_allow)
        SetupStep.A11Y -> getString(R.string.a11y_off_action)
        SetupStep.GOOGLE_MAPS_NOTIFY, SetupStep.NAVER_MAP_NOTIFY ->
            getString(R.string.onboarding_map_notify_open)
    }

    /** Switches to show: the needed ones still off. Falls back to all needed if none is off. */
    private fun a11yShown(): List<A11yItem> =
        A11yNeeds.off(this).ifEmpty { A11yNeeds.needed(this) }

    private fun a11yTitle(): String =
        if (a11yShown().size == 1) {
            getString(R.string.onboarding_a11y_title_one)
        } else {
            getString(R.string.onboarding_a11y_title)
        }

    /** Shows only the switches still off, numbered from 1. */
    private fun bindA11yNames(root: View, step2: TextView) {
        val needed = a11yShown()
        val google = A11yItem.GOOGLE in needed
        val naver = A11yItem.NAVER in needed
        root.findViewById<View>(R.id.a11yRowGoogle).visibility = if (google) View.VISIBLE else View.GONE
        root.findViewById<View>(R.id.a11yRowNaver).visibility = if (naver) View.VISIBLE else View.GONE
        root.findViewById<View>(R.id.a11yRowDivider).visibility =
            if (google && naver) View.VISIBLE else View.GONE
        root.findViewById<TextView>(R.id.a11yNumGoogle).text = if (naver) "2" else "1"
        root.findViewById<TextView>(R.id.a11yNumNaver).text = "1"
        step2.text = if (needed.size == 1) {
            getString(R.string.onboarding_a11y_step2_one)
        } else {
            getString(R.string.onboarding_a11y_step2)
        }
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

    private companion object {
        const val NOTICE_ASKED = "notice_permission_asked"
    }
}
