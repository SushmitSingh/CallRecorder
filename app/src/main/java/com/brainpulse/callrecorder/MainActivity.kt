package com.brainpulse.callrecorder

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.text.TextUtils
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import java.io.File

class MainActivity : Activity() {
    private val REQUEST_PERMISSIONS = 123
    private lateinit var statusText: TextView
    private lateinit var openSettingsBtn: Button
    private lateinit var recordingsList: ListView
    private lateinit var recordingsLabel: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        statusText = findViewById(R.id.statusText)
        openSettingsBtn = findViewById(R.id.openSettingsBtn)
        recordingsList = findViewById(R.id.recordingsList)
        recordingsLabel = findViewById(R.id.recordingsLabel)

        openSettingsBtn.setOnClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }

        requestRequiredPermissions()
        ignoreBatteryOptimizations()

        updateAccessibilityStatus()
        loadRecordings()
    }

    override fun onResume() {
        super.onResume()
        updateAccessibilityStatus()
        loadRecordings()
    }

    private fun requestRequiredPermissions() {
        val requiredPermissions = arrayOf(
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.READ_PHONE_STATE,
            Manifest.permission.WRITE_EXTERNAL_STORAGE
        )

        val missingPermissions = requiredPermissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }

        if (missingPermissions.isNotEmpty()) {
            ActivityCompat.requestPermissions(
                this,
                missingPermissions.toTypedArray(),
                REQUEST_PERMISSIONS
            )
        }
    }

    private fun ignoreBatteryOptimizations() {
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        val packageName = packageName
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !pm.isIgnoringBatteryOptimizations(
                packageName
            )
        ) {
            try {
                val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                    data = Uri.parse("package:$packageName")
                }
                startActivity(intent)
            } catch (e: Exception) {
                val fallbackIntent = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
                startActivity(fallbackIntent)
            }
        }
    }

    private fun updateAccessibilityStatus() {
        val isEnabled = isAccessibilityServiceEnabled(this, CallAccessibilityService::class.java)
        if (isEnabled) {
            statusText.text = "Accessibility Service is ENABLED ✅"
            openSettingsBtn.visibility = Button.GONE
        } else {
            statusText.text = "Accessibility Service is DISABLED ❌"
            openSettingsBtn.visibility = Button.VISIBLE
        }
    }

    private fun isAccessibilityServiceEnabled(context: Context, service: Class<*>): Boolean {
        val expectedComponentName = "$packageName/${service.name}"
        val enabledServices = Settings.Secure.getString(
            context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false

        val colonSplitter = TextUtils.SimpleStringSplitter(':')
        colonSplitter.setString(enabledServices)
        for (serviceString in colonSplitter) {
            if (serviceString.equals(expectedComponentName, ignoreCase = true)) {
                return true
            }
        }
        return false
    }

    private fun loadRecordings() {
        val dir = File(filesDir, "CallRecordings")
        if (!dir.exists() || !dir.isDirectory) {
            recordingsLabel.text = "No recordings found."
            recordingsList.adapter = null
            return
        }

        val files = dir.listFiles()?.map { it.name }?.sortedDescending() ?: emptyList()
        if (files.isEmpty()) {
            recordingsLabel.text = "No recordings found."
            recordingsList.adapter = null
        } else {
            recordingsLabel.text = "Recordings:"
            val adapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, files)
            recordingsList.adapter = adapter

            recordingsList.setOnItemClickListener { _, _, position, _ ->
                val fileName = files[position]
                val filePath = File(dir, fileName).absolutePath
                Toast.makeText(this, "Saved: $filePath", Toast.LENGTH_SHORT).show()
            }
        }
    }
}