package com.heideen.smsbackup.sms

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class SmsReceiver : BroadcastReceiver() {
  override fun onReceive(context: Context, intent: Intent) {
    // No-op: this app does not handle incoming SMS as a messaging app.
    // This receiver exists only to satisfy the default SMS app requirement
    // needed temporarily during restore operations.
  }
}
