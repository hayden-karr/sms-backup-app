package com.heideen.smsbackup

import android.Manifest
import android.content.Intent
import android.os.Bundle
import android.provider.Telephony
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.isSystemInDarkTheme
import com.heideen.smsbackup.ui.HomeScreen
import com.heideen.smsbackup.ui.MainViewModel
import com.heideen.smsbackup.ui.theme.SmsBackupTheme

class MainActivity : ComponentActivity() {

  private val viewModel: MainViewModel by viewModels()

  private val permissionLauncher =
      registerForActivityResult(
          ActivityResultContracts
              .RequestMultiplePermissions()) { /* permissions handled gracefully in backup/restore flows */
          }

  private val defaultSmsLauncher =
      registerForActivityResult(
          ActivityResultContracts
              .StartActivityForResult()) { /* result handled by viewModel.isDefaultSmsApp() check */
          }

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    setContent {
      SmsBackupTheme(darkTheme = isSystemInDarkTheme()) {
        HomeScreen(viewModel = viewModel, onRequestPermissions = { requestPermissions() })
      }
    }
  }

  private fun requestPermissions() {
    permissionLauncher.launch(
        arrayOf(Manifest.permission.READ_SMS, Manifest.permission.READ_CONTACTS))

    if (!viewModel.isDefaultSmsApp()) {
      val intent =
          Intent(Telephony.Sms.Intents.ACTION_CHANGE_DEFAULT).apply {
            putExtra(Telephony.Sms.Intents.EXTRA_PACKAGE_NAME, packageName)
          }
      defaultSmsLauncher.launch(intent)
    }
  }
}
