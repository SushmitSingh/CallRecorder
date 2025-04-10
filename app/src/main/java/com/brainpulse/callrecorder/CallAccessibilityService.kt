package com.brainpulse.callrecorder

import android.accessibilityservice.AccessibilityService
import android.content.ContentValues
import android.media.MediaRecorder
import android.os.*
import android.provider.MediaStore
import android.telephony.PhoneStateListener
import android.telephony.TelephonyManager
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.widget.Toast
import java.io.File
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.atomic.AtomicBoolean

class CallAccessibilityService : AccessibilityService() {

    private var recorder: MediaRecorder? = null
    private var isRecording = AtomicBoolean(false)
    private var outputPath: String = ""
    private var recordingThread: Thread? = null
    private val interrupt = AtomicBoolean(false)
    private var lastCallStartTime = 0L
    private lateinit var telephonyManager: TelephonyManager

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "Service created")
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        Log.d(TAG, "Accessibility service connected")
        telephonyManager = getSystemService(TELEPHONY_SERVICE) as TelephonyManager
        telephonyManager.listen(phoneStateListener, PhoneStateListener.LISTEN_CALL_STATE)
        Handler(Looper.getMainLooper()).post {
            Toast.makeText(this, "Call Recorder Service Connected", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        Log.d(TAG, "Accessibility event: ${event?.eventType}, Package: ${event?.packageName}")
    }

    override fun onInterrupt() {
        instance = null
        Log.d(TAG, "Service interrupted")
    }

    override fun onDestroy() {
        super.onDestroy()
        stopRecording()
        telephonyManager.listen(phoneStateListener, PhoneStateListener.LISTEN_NONE)
        instance = null
        Log.d(TAG, "Service destroyed")
    }

    private val phoneStateListener = object : PhoneStateListener() {
        override fun onCallStateChanged(state: Int, phoneNumber: String?) {
            super.onCallStateChanged(state, phoneNumber)
            val number = phoneNumber ?: "Unknown"
            Log.d(TAG, "Phone state changed (service): $state, Number: $number")

            when (state) {
                TelephonyManager.CALL_STATE_OFFHOOK -> {
                    if (!isRecording.get()) {
                        Log.d(TAG, "Call started (incoming or outgoing) - service")
                        Handler(Looper.getMainLooper()).postDelayed({
                            startRecordingThread()
                        }, 1000)
                    }
                }

                TelephonyManager.CALL_STATE_IDLE -> {
                    if (isRecording.get()) {
                        Log.d(TAG, "Call ended - service")
                        interrupt.set(true)
                    }
                }

                TelephonyManager.CALL_STATE_RINGING -> {
                    Log.d(TAG, "Phone is ringing - service")
                }
            }
        }
    }

    private fun startRecordingThread() {
        Log.d(TAG, "Attempting to start recording thread")

        // Debounce call start trigger (avoid multiple starts)
        val currentTime = System.currentTimeMillis()
        if (currentTime - lastCallStartTime < 3000) {
            Log.w(TAG, "Recording start skipped due to debounce")
            return
        }
        lastCallStartTime = currentTime

        // Avoid starting if already recording
        if (isRecording.getAndSet(true)) {
            Log.d(TAG, "Already recording, skipping thread")
            return
        }

        val wakeLockTag = "callrecorder:record_lock"
        val powerManager = getSystemService(POWER_SERVICE) as PowerManager
        val wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, wakeLockTag)

        recordingThread = Thread {
            wakeLock.acquire(10 * 60 * 1000L)
            Log.d(TAG, "Wake lock acquired")

            val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
            val fileName = "Call_${timestamp}_${UUID.randomUUID().toString().take(4)}.m4a"

            val sdk29OrAbove = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q

            if (sdk29OrAbove) {
                val values = ContentValues().apply {
                    put(MediaStore.Audio.Media.DISPLAY_NAME, fileName)
                    put(MediaStore.Audio.Media.MIME_TYPE, "audio/mp4")
                    put(MediaStore.Audio.Media.RELATIVE_PATH, "${Environment.DIRECTORY_MUSIC}/CallRecordings")
                    put(MediaStore.Audio.Media.IS_PENDING, 1)
                }

                val uri = contentResolver.insert(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, values)

                if (uri == null) {
                    Log.e(TAG, "Failed to create MediaStore entry")
                    isRecording.set(false)
                    wakeLock.release()
                    return@Thread
                }

                try {
                    contentResolver.openFileDescriptor(uri, "w")?.use { pfd ->
                        recorder = MediaRecorder().apply {
                            setAudioSource(MediaRecorder.AudioSource.VOICE_RECOGNITION)
                            setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                            setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                            setOutputFile(pfd.fileDescriptor)
                            prepare()
                            Thread.sleep(1000)
                            start()
                        }

                        Log.d(TAG, "Recording started (MediaStore)")
                        while (!interrupt.get()) {
                            Thread.sleep(1000)
                        }
                    }

                    values.clear()
                    values.put(MediaStore.Audio.Media.IS_PENDING, 0)
                    contentResolver.update(uri, values, null, null)
                    outputPath = uri.toString()

                } catch (e: Exception) {
                    Log.e(TAG, "Recording error (MediaStore): ${e.message}", e)
                    contentResolver.delete(uri, null, null)
                } finally {
                    stopRecording()
                    wakeLock.release()
                }

            } else {
                // Android 9 and below - legacy
                val legacyDir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC), "CallRecordings")
                if (!legacyDir.exists()) legacyDir.mkdirs()
                val legacyFile = File(legacyDir, fileName)
                outputPath = legacyFile.absolutePath

                try {
                    recorder = MediaRecorder().apply {
                        setAudioSource(MediaRecorder.AudioSource.VOICE_RECOGNITION)
                        setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                        setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                        setOutputFile(outputPath)
                        prepare()
                        Thread.sleep(1000)
                        start()
                    }

                    Log.d(TAG, "Recording started (Legacy)")
                    while (!interrupt.get()) {
                        Thread.sleep(1000)
                    }

                } catch (e: Exception) {
                    Log.e(TAG, "Recording error (Legacy): ${e.message}", e)
                } finally {
                    stopRecording()
                    wakeLock.release()
                }
            }
        }

        interrupt.set(false)
        recordingThread?.start()
    }

    private fun stopRecording() {
        if (recorder != null && isRecording.get()) {
            try {
                recorder?.stop()
                Log.d(TAG, "Recorder stopped")
            } catch (e: Exception) {
                Log.e(TAG, "Error stopping recorder: ${e.message}", e)
            }
            recorder?.release()
            recorder = null
            isRecording.set(false)
            checkRecordingFile()
        } else {
            Log.d(TAG, "No recording to stop")
        }
    }

    private fun checkRecordingFile() {
        if (outputPath.startsWith("content://")) {
            Log.d(TAG, "Recording saved (MediaStore): $outputPath")
        } else {
            val recordedFile = File(outputPath)
            if (recordedFile.exists()) {
                val size = recordedFile.length()
                Log.d(TAG, "Recording saved: $outputPath ($size bytes)")
                if (size < 1024) {
                    Log.w(TAG, "Warning: Recording might be silent.")
                }
                Handler(Looper.getMainLooper()).post {
                    Toast.makeText(applicationContext, "Recording saved:\n$outputPath", Toast.LENGTH_LONG).show()
                }
            } else {
                Log.e(TAG, "Recording file not found at $outputPath!")
            }
        }
    }

    companion object {
        private var instance: CallAccessibilityService? = null
        private const val TAG = "CallService"

        fun startRecordingExternally() {
            Log.d(TAG, "startRecordingExternally called")
            instance?.let {
                if (!it.isRecording.get()) {
                    Log.d(TAG, "Instance found, starting recording with delay")
                    Handler(Looper.getMainLooper()).postDelayed({
                        it.startRecordingThread()
                    }, 1000)
                } else {
                    Log.d(TAG, "Already recording")
                }
            } ?: Log.e(TAG, "Instance is null, service not connected")
        }

        fun stopRecordingExternally() {
            Log.d(TAG, "stopRecordingExternally called")
            instance?.let {
                if (it.isRecording.get()) {
                    Log.d(TAG, "Instance found, stopping recording")
                    it.interrupt.set(true)
                } else {
                    Log.d(TAG, "Not recording")
                }
            } ?: Log.e(TAG, "Instance is null, service not connected")
        }
    }
}
