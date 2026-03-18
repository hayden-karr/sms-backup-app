package com.heideen.smsbackup.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.heideen.smsbackup.backup.BackupFileInfo
import com.heideen.smsbackup.backup.RestoreMode

private enum class WizardStep {
  SELECT_FILE,
  SAFETY_BACKUP,
  CHOOSE_MODE,
  CONFIRM_REPLACE,
  SET_DEFAULT_APP
}

@Composable
fun RestoreWizard(
    viewModel: MainViewModel,
    backupFiles: List<BackupFileInfo>,
    onDismiss: () -> Unit
) {
  var step by remember { mutableStateOf(WizardStep.SELECT_FILE) }
  var selectedFile by remember { mutableStateOf<BackupFileInfo?>(null) }
  var selectedMode by remember { mutableStateOf(RestoreMode.MERGE) }
  var confirmText by remember { mutableStateOf("") }

  AlertDialog(
      onDismissRequest = onDismiss,
      title = {
        Text(
            when (step) {
              WizardStep.SELECT_FILE -> "Select backup file"
              WizardStep.SAFETY_BACKUP -> "Back up first?"
              WizardStep.CHOOSE_MODE -> "Choose restore mode"
              WizardStep.CONFIRM_REPLACE -> "CONFIRM: Replace all messages"
              WizardStep.SET_DEFAULT_APP -> "Set as default SMS app"
            },
            fontWeight = FontWeight.SemiBold)
      },
      text = {
        Column(
            modifier = Modifier.verticalScroll(rememberScrollState()).fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
              when (step) {
                WizardStep.SELECT_FILE ->
                    SelectFileStep(backupFiles) { file ->
                      selectedFile = file
                      step = WizardStep.SAFETY_BACKUP
                    }

                WizardStep.SAFETY_BACKUP ->
                    SafetyBackupStep(
                        onBackupFirst = {
                          viewModel.startBackup()
                          onDismiss()
                        },
                        onContinue = { step = WizardStep.CHOOSE_MODE })

                WizardStep.CHOOSE_MODE ->
                    ChooseModeStep(
                        selectedMode = selectedMode, onModeSelected = { selectedMode = it })

                WizardStep.CONFIRM_REPLACE ->
                    ConfirmReplaceStep(
                        confirmText = confirmText, onConfirmTextChange = { confirmText = it })

                WizardStep.SET_DEFAULT_APP ->
                    SetDefaultAppStep(isAlreadyDefault = viewModel.isDefaultSmsApp())
              }
            }
      },
      confirmButton = {
        when (step) {
          WizardStep.SELECT_FILE -> {}

          WizardStep.SAFETY_BACKUP -> {}

          WizardStep.CHOOSE_MODE -> {
            Button(
                onClick = {
                  step =
                      if (selectedMode == RestoreMode.REPLACE) {
                        WizardStep.CONFIRM_REPLACE
                      } else {
                        WizardStep.SET_DEFAULT_APP
                      }
                }) {
                  Text("Continue")
                }
          }

          WizardStep.CONFIRM_REPLACE -> {
            Button(
                onClick = { step = WizardStep.SET_DEFAULT_APP },
                enabled = confirmText.trim().uppercase() == "REPLACE",
                colors =
                    ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)) {
                  Text("Confirm and continue")
                }
          }

          WizardStep.SET_DEFAULT_APP -> {
            Button(
                onClick = {
                  selectedFile?.let { viewModel.startRestore(it, selectedMode) }
                  onDismiss()
                }) {
                  Text("Start restore")
                }
          }
        }
      },
      dismissButton = {
        TextButton(
            onClick = {
              if (step == WizardStep.SELECT_FILE) {
                onDismiss()
              } else {
                step = WizardStep.entries[step.ordinal - 1]
              }
            }) {
              Text(if (step == WizardStep.SELECT_FILE) "Cancel" else "Back")
            }
      })
}

@Composable
private fun SelectFileStep(files: List<BackupFileInfo>, onSelect: (BackupFileInfo) -> Unit) {
  if (files.isEmpty()) {
    Text("No backup files found in the current storage location. Create a backup first.")
  } else {
    Text("Select a backup to restore from:")
    Spacer(Modifier.height(4.dp))
    files.forEach { info ->
      OutlinedButton(onClick = { onSelect(info) }, modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.fillMaxWidth()) {
          Text(info.name, fontWeight = FontWeight.Medium)
        }
      }
    }
  }
}

@Composable
private fun SafetyBackupStep(onBackupFirst: () -> Unit, onContinue: () -> Unit) {
  Card(
      colors =
          CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
          Text("Recommended", fontWeight = FontWeight.SemiBold)
          Text(
              "Before restoring, we strongly recommend backing up your current messages. " +
                  "This protects you if the restore has any issues.",
              style = MaterialTheme.typography.bodySmall)
        }
      }
  Spacer(Modifier.height(8.dp))
  Button(onClick = onBackupFirst, modifier = Modifier.fillMaxWidth()) { Text("Backup now first") }
  Spacer(Modifier.height(4.dp))
  OutlinedButton(onClick = onContinue, modifier = Modifier.fillMaxWidth()) { Text("Continue") }
}

@Composable
private fun ChooseModeStep(selectedMode: RestoreMode, onModeSelected: (RestoreMode) -> Unit) {
  Card(
      colors =
          CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
          Text("MERGE (recommended)", fontWeight = FontWeight.SemiBold)
          Text(
              "Adds messages from the backup to your current messages. " +
                  "Duplicate messages are automatically skipped. " +
                  "No existing messages are deleted.",
              style = MaterialTheme.typography.bodySmall)
        }
      }
  Row(verticalAlignment = Alignment.CenterVertically) {
    RadioButton(
        selected = selectedMode == RestoreMode.MERGE,
        onClick = { onModeSelected(RestoreMode.MERGE) })
    Text("Merge with current messages")
  }

  Spacer(Modifier.height(4.dp))

  Card(
      colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
          Text(
              "REPLACE — DANGER",
              fontWeight = FontWeight.SemiBold,
              color = MaterialTheme.colorScheme.error)
          Text(
              "Deletes ALL your current messages and replaces them with the backup. " +
                  "This is permanent and cannot be undone.",
              style = MaterialTheme.typography.bodySmall)
        }
      }
  Row(verticalAlignment = Alignment.CenterVertically) {
    RadioButton(
        selected = selectedMode == RestoreMode.REPLACE,
        onClick = { onModeSelected(RestoreMode.REPLACE) })
    Text("Replace all current messages", color = MaterialTheme.colorScheme.error)
  }
}

@Composable
private fun ConfirmReplaceStep(confirmText: String, onConfirmTextChange: (String) -> Unit) {
  Card(
      colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
          Text(
              "WARNING: This will permanently delete ALL your current messages.",
              fontWeight = FontWeight.SemiBold,
              color = MaterialTheme.colorScheme.error)
          Text(
              "You have selected REPLACE mode. Every message currently on your device will be " +
                  "deleted and replaced with the backup. This action cannot be undone.",
              style = MaterialTheme.typography.bodySmall)
        }
      }
  Text("Type REPLACE (all caps) to confirm:", fontWeight = FontWeight.Medium)
  OutlinedTextField(
      value = confirmText,
      onValueChange = onConfirmTextChange,
      singleLine = true,
      placeholder = { Text("REPLACE") },
      modifier = Modifier.fillMaxWidth())
}

@Composable
private fun SetDefaultAppStep(isAlreadyDefault: Boolean) {
  if (isAlreadyDefault) {
    Text("SMS Backup is already set as the default SMS app. You can start the restore.")
  } else {
    Card(
        colors =
            CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) {
          Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Set as default SMS app", fontWeight = FontWeight.SemiBold)
            Text(
                "Android requires the default SMS app to write messages. " +
                    "After tapping 'Start restore', Android will prompt you to set SMS Backup as the default. " +
                    "Once the restore completes, switch back to your preferred messaging app.",
                style = MaterialTheme.typography.bodySmall)
          }
        }
  }
}
