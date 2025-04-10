package com.brainpulse.callrecorder

import android.Manifest
import android.accessibilityservice.AccessibilityServiceInfo
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.*
import android.provider.MediaStore
import android.provider.Settings
import android.text.TextUtils
import android.util.Log
import android.view.accessibility.AccessibilityManager
import android.widget.*
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
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
            Toast.makeText(this, "Enable 'Call Recorder Service' in Accessibility", Toast.LENGTH_LONG).show()
        }

        requestRequiredPermissions()
        ignoreBatteryOptimizations()
        checkAndEnforceService()
    }

    override fun onResume() {
        super.onResume()
        checkAndEnforceService()
        loadRecordings()
    }

    private fun requestRequiredPermissions() {
        val permissionsToRequest = mutableListOf(
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.READ_PHONE_STATE
        )

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissionsToRequest.add(Manifest.permission.READ_MEDIA_AUDIO)
        } else {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
                permissionsToRequest.add(Manifest.permission.READ_EXTERNAL_STORAGE)
                permissionsToRequest.add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
            }
        }

        val missingPermissions = permissionsToRequest.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }

        if (missingPermissions.isNotEmpty()) {
            ActivityCompat.requestPermissions(
                this,
                missingPermissions.toTypedArray(),
                REQUEST_PERMISSIONS
            )
        } else {
            Log.d("MainActivity", "All permissions granted")
        }
    }

    private fun ignoreBatteryOptimizations() {
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        val packageName = packageName
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !pm.isIgnoringBatteryOptimizations(packageName)) {
            try {
                val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                    data = Uri.parse("package:$packageName")
                }
                startActivity(intent)
                Log.d("MainActivity", "Requested battery optimization ignore")
            } catch (e: Exception) {
                val fallbackIntent = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
                startActivity(fallbackIntent)
                Log.e("MainActivity", "Battery optimization request failed: ${e.message}", e)
            }
        } else {
            Log.d("MainActivity", "Battery optimization already ignored or not required")
        }
    }

    private fun checkAndEnforceService() {
        val isEnabled = isAccessibilityServiceEnabled(this, CallAccessibilityService::class.java)
        if (isEnabled) {
            statusText.text = "Accessibility Service is ENABLED ✅"
            openSettingsBtn.visibility = Button.GONE

            val accessibilityManager = getSystemService(Context.ACCESSIBILITY_SERVICE) as AccessibilityManager
            val runningServices = accessibilityManager.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
            val isServiceRunning = runningServices.any {
                it.id.contains("com.brainpulse.callrecorder/.CallAccessibilityService")
            }

            if (!isServiceRunning) {
                Log.w("MainActivity", "Service enabled but not running - possible system restriction")
                statusText.text = "Service enabled but not running - check device restrictions ⚠️"
            }
        } else {
            statusText.text = "Please enable Accessibility Service to proceed ❌"
            openSettingsBtn.visibility = Button.VISIBLE
            openSettingsBtn.performClick()
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
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            loadRecordingsFromMediaStore()
        } else {
            loadRecordingsFromLegacyStorage()
        }
    }

    private fun loadRecordingsFromMediaStore() {
        val recordings = mutableListOf<Pair<String, Uri>>()
        val projection = arrayOf(
            MediaStore.Audio.Media._ID,
            MediaStore.Audio.Media.DISPLAY_NAME
        )
        val selection = "${MediaStore.Audio.Media.RELATIVE_PATH} LIKE ?"
        val selectionArgs = arrayOf("%CallRecordings%")

        contentResolver.query(
            MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
            projection,
            selection,
            selectionArgs,
            "${MediaStore.Audio.Media.DATE_ADDED} DESC"
        )?.use { cursor ->
            val idColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
            val nameColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DISPLAY_NAME)
            while (cursor.moveToNext()) {
                val id = cursor.getLong(idColumn)
                val name = cursor.getString(nameColumn)
                val uri = Uri.withAppendedPath(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, id.toString())
                recordings.add(name to uri)
            }
        }

        if (recordings.isEmpty()) {
            recordingsLabel.text = "No recordings found in Music/CallRecordings."
            recordingsList.adapter = null
        } else {
            recordingsLabel.text = "Recordings:"
            val adapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, recordings.map { it.first })
            recordingsList.adapter = adapter

            recordingsList.setOnItemClickListener { _, _, position, _ ->
                val uri = recordings[position].second
                val intent = Intent(Intent.ACTION_VIEW).apply {
                    setDataAndType(uri, "audio/*")
                    flags = Intent.FLAG_GRANT_READ_URI_PERMISSION
                }
                startActivity(intent)
            }
        }
    }

    private fun loadRecordingsFromLegacyStorage() {
        val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC), "CallRecordings")
        if (!dir.exists() || !dir.isDirectory) {
            recordingsLabel.text = "No recordings found."
            recordingsList.adapter = null
            return
        }

        val files = dir.listFiles()
        if (files == null || files.isEmpty()) {
            recordingsLabel.text = "No recordings found."
            recordingsList.adapter = null
            return
        }

        val sortedFiles = files.sortedByDescending { file: File -> file.lastModified() }
        val fileNames = sortedFiles.map { file -> file.name }

        recordingsLabel.text = "Recordings (Legacy):"
        val adapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, fileNames)
        recordingsList.adapter = adapter

        recordingsList.setOnItemClickListener { _, _, position, _ ->
            val selectedFile = sortedFiles[position]
            val uri = FileProvider.getUriForFile(
                this,
                "$packageName.provider", // this must match the one in AndroidManifest
                selectedFile
            )

            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "audio/*")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(intent)

        }
    }

}
