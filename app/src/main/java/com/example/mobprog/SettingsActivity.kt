package com.example.mobprog

import android.Manifest
import android.app.AlarmManager
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import com.example.mobprog.databinding.ActivitySettingsBinding
import com.example.mobprog.notify.Notifications
import com.example.mobprog.service.ChatHeadService
import com.example.mobprog.util.Insets
import com.google.android.material.dialog.MaterialAlertDialogBuilder

class SettingsActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySettingsBinding
    private var suppressSwitch = false
    private var awaitingOverlayPermission = false

    private val notificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { refresh() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)
        Insets.applySystemBars(binding.settingsRoot)

        binding.toolbar.setNavigationOnClickListener { finish() }

        binding.switchOverlay.setOnCheckedChangeListener { _, isChecked ->
            if (suppressSwitch) return@setOnCheckedChangeListener
            if (isChecked) {
                if (Settings.canDrawOverlays(this)) {
                    startBubble()
                } else {
                    requestOverlayPermission()
                }
            } else {
                stopService(Intent(this, ChatHeadService::class.java))
            }
        }

        binding.buttonOverlaySettings.setOnClickListener {
            if (Settings.canDrawOverlays(this)) {
                startActivity(systemOverlaySettingsIntent())
            } else {
                requestOverlayPermission()
            }
        }

        binding.buttonNotifications.setOnClickListener {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
            } else {
                openAppNotificationSettings()
            }
        }

        binding.buttonExactAlarms.setOnClickListener { requestExactAlarms() }

        binding.markdownPath.text = getExternalFilesDir("notes")?.absolutePath ?: "-"
        binding.versionText.text = getString(R.string.settings_version, versionName())

        refresh()
    }

    override fun onResume() {
        super.onResume()
        refresh()
        if (awaitingOverlayPermission) {
            awaitingOverlayPermission = false
            if (Settings.canDrawOverlays(this)) {
                startBubble()
            } else {
                toast(R.string.settings_overlay_denied_message)
            }
        }
    }

    private fun refresh() {
        suppressSwitch = true
        binding.switchOverlay.isChecked = Settings.canDrawOverlays(this)
        suppressSwitch = false
        binding.buttonOverlaySettings.isVisible = !Settings.canDrawOverlays(this)

        val canNotify = Notifications.canPost(this)
        binding.notificationStatus.setText(
            if (canNotify) R.string.settings_notification_allowed
            else R.string.settings_notification_denied
        )
        binding.buttonNotifications.isVisible = !canNotify

        val exactAllowed = canScheduleExactAlarms()
        binding.exactAlarmStatus.setText(
            if (exactAllowed) R.string.settings_exact_alarm_allowed
            else R.string.settings_exact_alarm_denied
        )
        binding.buttonExactAlarms.isVisible = !exactAllowed
    }

    private fun startBubble() {
        ContextCompat.startForegroundService(this, Intent(this, ChatHeadService::class.java))
    }

    private fun requestOverlayPermission() {
        awaitingOverlayPermission = true
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.settings_overlay_explain_title)
            .setMessage(R.string.settings_overlay_explain_message)
            .setNegativeButton(R.string.action_cancel) { _, _ ->
                awaitingOverlayPermission = false
                refresh()
            }
            .setPositiveButton(R.string.settings_open_system_settings) { _, _ ->
                startActivity(systemOverlaySettingsIntent())
            }
            .show()
    }

    private fun systemOverlaySettingsIntent(): Intent =
        Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))

    private fun openAppNotificationSettings() {
        val intent = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
            .putExtra(Settings.EXTRA_APP_PACKAGE, packageName)
        startActivity(intent)
    }

    private fun canScheduleExactAlarms(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return true
        val manager = getSystemService(AlarmManager::class.java) ?: return false
        return manager.canScheduleExactAlarms()
    }

    private fun requestExactAlarms() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return
        val intent = Intent(
            Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM,
            Uri.parse("package:$packageName")
        )
        runCatching { startActivity(intent) }.onFailure {
            startActivity(
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName"))
            )
        }
    }

    private fun versionName(): String = runCatching {
        packageManager.getPackageInfo(packageName, 0).versionName.orEmpty()
    }.getOrDefault("")

    private fun toast(messageRes: Int) {
        Toast.makeText(this, messageRes, Toast.LENGTH_LONG).show()
    }
}
