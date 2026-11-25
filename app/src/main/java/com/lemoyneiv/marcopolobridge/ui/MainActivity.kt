package com.lemoyneiv.marcopolobridge.ui

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.button.MaterialButton
import com.google.android.material.materialswitch.MaterialSwitch
import com.lemoyneiv.marcopolobridge.R
import com.lemoyneiv.marcopolobridge.audio.AudioBridgeController
import com.lemoyneiv.marcopolobridge.audio.BridgeRuntimeStatus
import com.lemoyneiv.marcopolobridge.core.RoutingMode
import com.lemoyneiv.marcopolobridge.core.ScoTiming
import com.lemoyneiv.marcopolobridge.core.WatchedApp
import com.lemoyneiv.marcopolobridge.service.MarcoPoloAccessibilityService
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    private lateinit var controller: AudioBridgeController
    private var updatingControlsFromState = false

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) {
        controller.refreshState()
        updateSetupChecklist()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        val root = findViewById<android.view.View>(R.id.rootScroll)
        val basePadLeft = root.paddingLeft
        val basePadTop = root.paddingTop
        val basePadRight = root.paddingRight
        val basePadBottom = root.paddingBottom
        ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(
                basePadLeft + bars.left,
                basePadTop + bars.top,
                basePadRight + bars.right,
                basePadBottom + bars.bottom
            )
            insets
        }

        controller = AudioBridgeController.getInstance(this)
        bindActions()

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                controller.status.collect { st ->
                    renderStatus(st)
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        controller.refreshState()
        updateSetupChecklist()
    }

    private fun bindActions() {
        findViewById<MaterialButton>(R.id.btnManualToggle).setOnClickListener {
            controller.toggleManualHold()
        }

        findViewById<MaterialButton>(R.id.btnOpenMarcoPolo).setOnClickListener {
            launchWatchedApp(com.lemoyneiv.marcopolobridge.core.WatchedApp.MARCO_POLO)
        }

        findViewById<MaterialButton>(R.id.btnOpenSpeechify).setOnClickListener {
            launchWatchedApp(com.lemoyneiv.marcopolobridge.core.WatchedApp.SPEECHIFY)
        }

        findViewById<MaterialButton>(R.id.btnGrantPermissions).setOnClickListener {
            val perms = mutableListOf(Manifest.permission.BLUETOOTH_CONNECT)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                perms.add(Manifest.permission.POST_NOTIFICATIONS)
            }
            permissionLauncher.launch(perms.toTypedArray())
        }

        findViewById<MaterialButton>(R.id.btnOpenAppInfo).setOnClickListener {
            openAppDetails()
        }

        findViewById<MaterialButton>(R.id.btnOpenAccessibility).setOnClickListener {
            runCatching {
                startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            }.onFailure {
                openAppDetails()
            }
        }

        findViewById<MaterialButton>(R.id.btnOpenBattery).setOnClickListener {
            openAppDetails()
        }

        findViewById<MaterialButton>(R.id.btnOpenAaSettings).setOnClickListener {
            openAndroidAutoSettings()
        }

        findViewById<MaterialSwitch>(R.id.switchAutoDetect).setOnCheckedChangeListener { _, checked ->
            if (!updatingControlsFromState) {
                controller.preferences.setAutoDetectEnabled(checked)
            }
        }

        findViewById<MaterialSwitch>(R.id.switchEnableMarcoPolo).setOnCheckedChangeListener { _, checked ->
            if (!updatingControlsFromState) {
                controller.preferences.setEnableMarcoPolo(checked)
            }
        }

        findViewById<MaterialSwitch>(R.id.switchEnableSpeechify).setOnCheckedChangeListener { _, checked ->
            if (!updatingControlsFromState) {
                controller.preferences.setEnableSpeechify(checked)
            }
        }

        findViewById<MaterialSwitch>(R.id.switchKeepBackground).setOnCheckedChangeListener { _, checked ->
            if (!updatingControlsFromState) {
                controller.preferences.setKeepPlayingInBackground(checked)
            }
        }

        findViewById<MaterialSwitch>(R.id.switchOnlyWhenAa).setOnCheckedChangeListener { _, checked ->
            if (!updatingControlsFromState) {
                controller.preferences.setOnlyWhenAndroidAutoConnected(checked)
            }
        }

        findViewById<MaterialSwitch>(R.id.switchResumeOnExit).setOnCheckedChangeListener { _, checked ->
            if (!updatingControlsFromState) {
                controller.preferences.setResumeMediaOnExit(checked)
            }
        }

        findViewById<RadioGroup>(R.id.radioRoutingMode).setOnCheckedChangeListener { _, checkedId ->
            if (updatingControlsFromState) return@setOnCheckedChangeListener
            when (checkedId) {
                R.id.rbModeSmartAuto -> controller.preferences.setRoutingMode(RoutingMode.SMART_AUTO)
                R.id.rbModeAaOnly -> controller.preferences.setRoutingMode(RoutingMode.AA_MEDIA_HOLD_ONLY)
            }
        }

        findViewById<RadioGroup>(R.id.radioScoTiming).setOnCheckedChangeListener { _, checkedId ->
            if (updatingControlsFromState) return@setOnCheckedChangeListener
            when (checkedId) {
                R.id.rbTimingForeground -> controller.preferences.setScoTiming(ScoTiming.ON_FOREGROUND)
                R.id.rbTimingPlayback -> controller.preferences.setScoTiming(ScoTiming.ON_AUDIO_PLAYBACK)
            }
        }

        findViewById<MaterialButton>(R.id.btnChooseBtDevice).setOnClickListener {
            showBluetoothPickerDialog()
        }
    }

    private fun launchWatchedApp(app: com.lemoyneiv.marcopolobridge.core.WatchedApp) {
        val launch = app.packageNames.firstNotNullOfOrNull { pkg ->
            packageManager.getLaunchIntentForPackage(pkg)
        }
        if (launch != null) {
            startActivity(launch)
        } else {
            Toast.makeText(
                this,
                "${app.displayName} is not installed on this phone.",
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    private fun renderStatus(st: BridgeRuntimeStatus) {
        val txtHeadline = findViewById<TextView>(R.id.txtStatusHeadline)
        val txtDetails = findViewById<TextView>(R.id.txtStatusDetails)
        val btnToggle = findViewById<MaterialButton>(R.id.btnManualToggle)

        val active = st.snapshot.shouldHoldBridge
        val armedManual = st.snapshot.manualHold && !active
        txtHeadline.text = when {
            active -> "● Active — ${st.snapshot.reason}"
            armedManual -> "◐ Armed — ${st.snapshot.reason}"
            st.androidAutoConnected -> "● Armed — ${st.snapshot.reason}"
            else -> "○ Standby — ${st.snapshot.reason}"
        }

        val aaLabel = if (st.androidAutoConnected) "Connected (Projecting)" else "Not connected"
        val btTarget = st.activeBtDeviceName ?: "None detected"
        val commCurrent = st.currentCommDeviceLabel ?: "Default"
        val a11yLabel = if (MarcoPoloAccessibilityService.isEnabledInSettings(this)) {
            "Enabled (Marco Polo & Speechify)"
        } else {
            "Not enabled yet"
        }

        txtDetails.text = buildString {
            appendLine("Android Auto: $aaLabel")
            appendLine("Target Car Bluetooth: $btTarget")
            appendLine("Active Voice Route: $commCurrent")
            append("App Auto-Detect: $a11yLabel")
        }

        btnToggle.text = if (active || armedManual) "Release Car Audio" else "Hold Car Audio Now"

        updatingControlsFromState = true
        findViewById<MaterialSwitch>(R.id.switchAutoDetect).isChecked = st.config.autoDetectEnabled
        findViewById<MaterialSwitch>(R.id.switchEnableMarcoPolo).isChecked = st.config.enableMarcoPolo
        findViewById<MaterialSwitch>(R.id.switchEnableSpeechify).isChecked = st.config.enableSpeechify
        findViewById<MaterialSwitch>(R.id.switchKeepBackground).isChecked = st.config.keepPlayingInBackground
        findViewById<MaterialSwitch>(R.id.switchOnlyWhenAa).isChecked = st.config.onlyWhenAndroidAutoConnected
        findViewById<MaterialSwitch>(R.id.switchResumeOnExit).isChecked = st.config.resumeMediaOnExit

        findViewById<RadioButton>(R.id.rbModeSmartAuto).isChecked =
            st.config.routingMode == RoutingMode.SMART_AUTO
        findViewById<RadioButton>(R.id.rbModeAaOnly).isChecked =
            st.config.routingMode == RoutingMode.AA_MEDIA_HOLD_ONLY

        findViewById<RadioButton>(R.id.rbTimingForeground).isChecked =
            st.config.scoTiming == ScoTiming.ON_FOREGROUND
        findViewById<RadioButton>(R.id.rbTimingPlayback).isChecked =
            st.config.scoTiming == ScoTiming.ON_AUDIO_PLAYBACK

        val prefBt = st.config.preferredBtIdentifier.ifBlank { "Automatic (Car Hands-Free)" }
        findViewById<TextView>(R.id.txtPreferredBt).text = "Target Car Bluetooth: $prefBt"
        updatingControlsFromState = false

        updateSetupChecklist()
    }

    private fun updateSetupChecklist() {
        val btGranted = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.BLUETOOTH_CONNECT
        ) == PackageManager.PERMISSION_GRANTED
        val notifGranted = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
        } else {
            true
        }
        val permsDone = btGranted && notifGranted
        findViewById<TextView>(R.id.txtStepPermissions).text =
            if (permsDone) "✓ 1. Bluetooth & Notification Permissions (Ready)"
            else "1. Bluetooth & Notification Permissions (Needed)"

        val a11yDone = MarcoPoloAccessibilityService.isEnabledInSettings(this)
        findViewById<TextView>(R.id.txtStepAccessibility).text =
            if (a11yDone) "✓ 2. Auto-Detect Marco Polo & Speechify (Enabled)"
            else "2. Auto-Detect Marco Polo & Speechify (Accessibility Needed)"

        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        val unrestricted = pm.isIgnoringBatteryOptimizations(packageName)
        findViewById<TextView>(R.id.txtStepBattery).text =
            if (unrestricted) "✓ 3. Unrestricted Background Battery (Ready)"
            else "3. Unrestricted Background Battery (Recommended)"
    }

    private fun showBluetoothPickerDialog() {
        val candidates = controller.listBluetoothCommCandidates()
        val labels = mutableListOf("Automatic (Prefer Car Hands-Free)")
        val values = mutableListOf("")
        for (c in candidates) {
            val idVal = c.address.ifBlank { c.name }
            val suffix = if (c.address.isNotBlank()) " (${c.address})" else ""
            labels.add("${c.name}$suffix")
            values.add(idVal)
        }

        val currentPref = controller.preferences.config.value.preferredBtIdentifier
        val checkedIndex = values.indexOfFirst { it.equals(currentPref, ignoreCase = true) }
            .let { if (it >= 0) it else 0 }

        AlertDialog.Builder(this)
            .setTitle("Target Car Bluetooth Device")
            .setSingleChoiceItems(labels.toTypedArray(), checkedIndex) { dialog, which ->
                controller.preferences.setPreferredBtIdentifier(values[which])
                dialog.dismiss()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun openAppDetails() {
        runCatching {
            val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                data = Uri.fromParts("package", packageName, null)
            }
            startActivity(intent)
        }
    }

    private fun openAndroidAutoSettings() {
        val candidates = listOf(
            Intent().setComponent(
                ComponentName(
                    "com.google.android.projection.gearhead",
                    "com.google.android.projection.gearhead.companion.settings.Material3SettingsActivity"
                )
            ),
            Intent().setComponent(
                ComponentName(
                    "com.google.android.projection.gearhead",
                    "com.google.android.projection.gearhead.companion.settings.DefaultSettingsActivity"
                )
            ),
            Intent(Settings.ACTION_BLUETOOTH_SETTINGS)
        )
        for (intent in candidates) {
            val started = runCatching {
                startActivity(intent)
                true
            }.getOrDefault(false)
            if (started) return
        }
        Toast.makeText(
            this,
            "Open Settings -> Connected devices -> Connection preferences -> Android Auto",
            Toast.LENGTH_LONG
        ).show()
    }
}
