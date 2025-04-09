package com.brainpulse.callrecorder

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.media.MediaRecorder
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.widget.Toast
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

class CallAccessibilityService : AccessibilityService() {

    private var recorder: MediaRecorder? = null
    private var isRecording = false
    private var outputPath: String = ""
    private var recordingThread: Thread? = null
    private val interrupt = AtomicBoolean(false)

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // Optional: for accessibility-triggered start
    }

    override fun onInterrupt() {}

    override fun onDestroy() {
        super.onDestroy()
        instance = null
    }

    private fun startRecordingThread() {
        val wakeLockTag = "myapp:record_lock"
        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        val wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, wakeLockTag)

        recordingThread = Thread {
            wakeLock.acquire()

            var started = false
            val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
            val dir = getExternalFilesDir(Environment.DIRECTORY_MUSIC)?.let {
                File(it, "CallRecordings")
            } ?: filesDir

            if (!dir.exists()) dir.mkdirs()
            outputPath = "${dir.absolutePath}/Call_$timestamp.m4a"

            try {
                recorder = MediaRecorder().apply {
                    setAudioSource(MediaRecorder.AudioSource.VOICE_RECOGNITION)
                    setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                    setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                    setOutputFile(outputPath)
                    prepare()
                    Thread.sleep(1500)
                    start()
                }

                isRecording = true
                started = true
                Log.d("CallService", "Recording started at $outputPath")

                while (!interrupt.get()) {
                    Thread.sleep(1000)
                }

            } catch (e: Exception) {
                Log.e("CallService", "Error during recording: ${e.message}", e)
                recorder?.reset()
            } finally {
                if (started) {
                    try {
                        recorder?.stop()
                        Log.d("CallService", "Recording stopped.")
                    } catch (e: Exception) {
                        Log.e("CallService", "Error stopping recorder: ${e.message}", e)
                    }
                }

                recorder?.release()
                recorder = null
                isRecording = false
                wakeLock.release()

                checkRecordingFile()
            }
        }

        interrupt.set(false)
        recordingThread?.start()
    }

    private fun checkRecordingFile() {
        val recordedFile = File(outputPath)
        if (recordedFile.exists()) {
            val size = recordedFile.length()
            Log.d("CallService", "Recording saved: $outputPath ($size bytes)")

            if (size < 1024) {
                Log.w("CallService", "Warning: Recording might be silent.")
            }

            Handler(Looper.getMainLooper()).post {
                Toast.makeText(
                    applicationContext, "Recording saved:\n$outputPath", Toast.LENGTH_LONG
                ).show()
            }

        } else {
            Log.e("CallService", "Recording file not found!")
        }
    }

    companion object {
        private var instance: CallAccessibilityService? = null

        fun startRecordingExternally(context: Context) {
            instance?.let {
                if (!it.isRecording) {
                    Handler(Looper.getMainLooper()).postDelayed({
                        it.startRecordingThread()
                    }, 1000)
                }
            }
        }

        fun stopRecordingExternally() {
            instance?.let {
                if (it.isRecording) {
                    it.interrupt.set(true)
                }
            }
        }
    }
}
