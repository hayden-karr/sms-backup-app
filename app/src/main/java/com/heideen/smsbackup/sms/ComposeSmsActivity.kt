package com.heideen.smsbackup.sms

import android.app.Activity
import android.os.Bundle
import android.widget.Toast

class ComposeSmsActivity : Activity() {
  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    Toast.makeText(this, "SMS Backup does not support composing messages", Toast.LENGTH_LONG).show()
    finish()
  }
}
