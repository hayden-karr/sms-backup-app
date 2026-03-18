package com.heideen.smsbackup.backup

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.provider.Telephony
import kotlinx.coroutines.CancellationException
import com.heideen.smsbackup.model.MmsAttachment
import com.heideen.smsbackup.model.MmsMessage
import com.heideen.smsbackup.model.SmsMessage
import com.heideen.smsbackup.util.HashUtil
import java.io.File

enum class RestoreMode {
    MERGE,
    REPLACE
}

data class RestoreResult(
    val smsInserted: Int,
    val mmsInserted: Int,
    val smsSkipped: Int,
    val mmsSkipped: Int,
    val smsErrors: Int = 0,
    val mmsErrors: Int = 0
)

class RestoreManager(private val context: Context) {

    private val repo = SmsRepository(context)

    suspend fun restore(
        backupFile: File,
        mode: RestoreMode,
        onProgress: suspend (Float, String) -> Unit
    ): Result<RestoreResult> {
        return try {
            BackupDatabase(backupFile).open().use { db ->
            if (!db.validate()) {
                return Result.failure(IllegalArgumentException("Not a valid SMS backup file"))
            }

            onProgress(0.05f, "Reading backup file...")
            val smsList = db.readSms()
            val mmsTotal = db.getMmsCount()

            val existingSmsHashes: Set<String>
            val existingMmsHashes: Set<String>

            if (mode == RestoreMode.REPLACE) {
                onProgress(0.1f, "Clearing existing messages...")
                context.contentResolver.delete(Telephony.Sms.CONTENT_URI, null, null)
                context.contentResolver.delete(Telephony.Mms.CONTENT_URI, null, null)
                existingSmsHashes = emptySet()
                existingMmsHashes = emptySet()
            } else {
                onProgress(0.1f, "Scanning existing SMS...")
                existingSmsHashes = repo.getSmsDedupHashes()
                onProgress(0.15f, "Scanning existing MMS...")
                existingMmsHashes = repo.getMmsDedupHashes { localProgress, msg ->
                    onProgress(0.15f + localProgress * 0.3f, msg)
                }
            }

            val smsToInsert = smsList.filter { HashUtil.smsHash(it) !in existingSmsHashes }
            onProgress(0.45f, "Restoring ${smsToInsert.size} SMS messages...")
            var smsInserted = 0
            var smsErrors = 0
            smsToInsert.forEachIndexed { index, msg ->
                try {
                    insertSms(msg)
                    smsInserted++
                } catch (_: Exception) {
                    smsErrors++
                }
                if (index % 50 == 0) {
                    val progress = 0.45f + (index.toFloat() / smsToInsert.size.coerceAtLeast(1)) * 0.15f
                    onProgress(progress, "Restoring SMS ${index + 1}/${smsToInsert.size}...")
                }
            }

            onProgress(0.6f, "Restoring $mmsTotal MMS messages...")
            var mmsInserted = 0
            var mmsSkipped = 0
            var mmsErrors = 0
            var mmsProcessed = 0

            db.forEachMms { msg ->
                if (HashUtil.mmsHash(msg) in existingMmsHashes) {
                    mmsSkipped++
                } else {
                    try {
                        insertMms(msg)
                        mmsInserted++
                    } catch (_: Exception) {
                        mmsErrors++
                    }
                }
                mmsProcessed++
                if (mmsProcessed % 10 == 0) {
                    val progress = 0.6f + (mmsProcessed.toFloat() / mmsTotal.coerceAtLeast(1)) * 0.37f
                    onProgress(progress, "Restoring MMS $mmsProcessed/$mmsTotal...")
                }
            }

            onProgress(1.0f, "Restore complete")
            Result.success(
                RestoreResult(
                    smsInserted = smsInserted,
                    mmsInserted = mmsInserted,
                    smsSkipped = smsList.size - smsInserted - smsErrors,
                    mmsSkipped = mmsSkipped,
                    smsErrors = smsErrors,
                    mmsErrors = mmsErrors
                )
            )
            } // close use { db ->
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private fun insertSms(msg: SmsMessage) {
        val values = ContentValues().apply {
            put(Telephony.Sms.ADDRESS, msg.address)
            put(Telephony.Sms.BODY, msg.body)
            put(Telephony.Sms.DATE, msg.timestamp)
            put(Telephony.Sms.TYPE, msg.type)
            put(Telephony.Sms.READ, msg.read)
            put(Telephony.Sms.STATUS, msg.status)
            msg.serviceCenter?.let { put(Telephony.Sms.SERVICE_CENTER, it) }
        }
        context.contentResolver.insert(Telephony.Sms.CONTENT_URI, values)
    }

    private fun insertMms(msg: MmsMessage) {
        val values = ContentValues().apply {
            put(Telephony.Mms.DATE, msg.timestampMs / 1000L)
            put(Telephony.Mms.MESSAGE_BOX, msg.msgBox)
            put(Telephony.Mms.READ, msg.read)
            put(Telephony.Mms.CONTENT_TYPE, "application/vnd.wap.multipart.related")
            put(Telephony.Mms.MESSAGE_TYPE, if (msg.msgBox == Telephony.Mms.MESSAGE_BOX_INBOX) 132 else 128)
            put(Telephony.Mms.MMS_VERSION, 18)
            put(Telephony.Mms.PRIORITY, 129)
            msg.subject?.let { put(Telephony.Mms.SUBJECT, it) }
        }
        val mmsUri = context.contentResolver.insert(Telephony.Mms.CONTENT_URI, values) ?: return
        val mmsId = mmsUri.lastPathSegment?.toLongOrNull() ?: return

        insertMmsAddresses(mmsId, msg)
        insertMmsParts(mmsId, msg.attachments)
    }

    private fun insertMmsAddresses(mmsId: Long, msg: MmsMessage) {
        val addrUri = Uri.parse("content://mms/$mmsId/addr")
        msg.addresses.split(",").filter { it.isNotBlank() }.forEachIndexed { index, addr ->
            val type = if (msg.msgBox == Telephony.Mms.MESSAGE_BOX_INBOX) {
                if (index == 0) 137 else 151
            } else {
                if (index == 0) 151 else 137
            }
            val values = ContentValues().apply {
                put(Telephony.Mms.Addr.MSG_ID, mmsId)
                put(Telephony.Mms.Addr.ADDRESS, addr.trim())
                put(Telephony.Mms.Addr.TYPE, type)
                put(Telephony.Mms.Addr.CHARSET, 106)
            }
            context.contentResolver.insert(addrUri, values)
        }
    }

    private fun insertMmsParts(mmsId: Long, attachments: List<MmsAttachment>) {
        for (att in attachments) {
            val partValues = ContentValues().apply {
                put(Telephony.Mms.Part.MSG_ID, mmsId)
                att.contentType?.let { put(Telephony.Mms.Part.CONTENT_TYPE, it) }
                att.filename?.let { put(Telephony.Mms.Part.NAME, it) }
                put(Telephony.Mms.Part.CHARSET, att.charset)
            }
            val partUri = context.contentResolver.insert(Uri.parse("content://mms/part"), partValues)
                ?: continue
            if (att.data != null) {
                try {
                    context.contentResolver.openOutputStream(partUri)?.use { stream ->
                        stream.write(att.data)
                    }
                } catch (_: Exception) {
                }
            }
        }
    }
}
