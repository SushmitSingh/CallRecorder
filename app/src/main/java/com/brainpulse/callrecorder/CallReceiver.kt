package com.brainpulse.callrecorder

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.telephony.TelephonyManager
import android.util.Log

class CallReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val state = intent.getStringExtra(TelephonyManager.EXTRA_STATE)

        Log.d("CallReceiver", "Phone state changed: $state")

        when (state) {
            TelephonyManager.EXTRA_STATE_OFFHOOK -> {
                Log.d("CallReceiver", "Call started (incoming or outgoing)")
                CallAccessibilityService.startRecordingExternally(context)
            }

            TelephonyManager.EXTRA_STATE_IDLE -> {
                Log.d("CallReceiver", "Call ended")
                CallAccessibilityService.stopRecordingExternally()
            }

            TelephonyManager.EXTRA_STATE_RINGING -> {
                Log.d("CallReceiver", "Phone is ringing")
                // Optional: show notification, log, etc.
            }
        }
    }
}