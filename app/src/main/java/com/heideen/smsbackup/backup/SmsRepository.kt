package com.heideen.smsbackup.backup

import android.content.Context
import android.net.Uri
import android.provider.Telephony
import android.util.Log
import com.heideen.smsbackup.model.MmsAttachment
import com.heideen.smsbackup.model.MmsMessage
import com.heideen.smsbackup.model.SmsMessage
import com.heideen.smsbackup.util.HashUtil
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch

private const val MMS_WORKER_COUNT = 4

class SmsRepository(private val context: Context) {

  fun readAllSms(): List<SmsMessage> {
    val result = mutableListOf<SmsMessage>()
    context.contentResolver
        .query(
            Telephony.Sms.CONTENT_URI,
            arrayOf(
                Telephony.Sms._ID,
                Telephony.Sms.THREAD_ID,
                Telephony.Sms.ADDRESS,
                Telephony.Sms.BODY,
                Telephony.Sms.DATE,
                Telephony.Sms.TYPE,
                Telephony.Sms.READ,
                Telephony.Sms.STATUS,
                Telephony.Sms.SERVICE_CENTER),
            null,
            null,
            "${Telephony.Sms.DATE} ASC")
        ?.use { cursor ->
          while (cursor.moveToNext()) {
            result.add(
                SmsMessage(
                    id = cursor.getLong(0),
                    threadId = cursor.getLong(1),
                    address = cursor.getString(2),
                    body = cursor.getString(3),
                    timestamp = cursor.getLong(4),
                    type = cursor.getInt(5),
                    read = cursor.getInt(6),
                    status = cursor.getInt(7),
                    serviceCenter = cursor.getString(8)))
          }
        }
    return result
  }

  fun getSmsCount(): Int {
    context.contentResolver
        .query(Telephony.Sms.CONTENT_URI, arrayOf(Telephony.Sms._ID), null, null, null)
        ?.use {
          return it.count
        }
    return 0
  }

  fun getMmsCount(): Int {
    context.contentResolver
        .query(Telephony.Mms.CONTENT_URI, arrayOf(Telephony.Mms._ID), null, null, null)
        ?.use {
          return it.count
        }
    return 0
  }

  suspend fun forEachMms(block: suspend (MmsMessage) -> Unit) {
    data class MmsRow(
        val id: Long,
        val threadId: Long,
        val timestampMs: Long,
        val msgBox: Int,
        val read: Int,
        val subject: String?
    )
    val rows = mutableListOf<MmsRow>()

    context.contentResolver
        .query(
            Telephony.Mms.CONTENT_URI,
            arrayOf(
                Telephony.Mms._ID,
                Telephony.Mms.THREAD_ID,
                Telephony.Mms.DATE,
                Telephony.Mms.MESSAGE_BOX,
                Telephony.Mms.READ,
                Telephony.Mms.SUBJECT),
            null,
            null,
            null // no ORDER BY — avoids slow full-table sort on devices without a date index
            )
        ?.use { cursor ->
          while (cursor.moveToNext()) {
            rows.add(
                MmsRow(
                    id = cursor.getLong(0),
                    threadId = cursor.getLong(1),
                    timestampMs = cursor.getLong(2) * 1000L,
                    msgBox = cursor.getInt(3),
                    read = cursor.getInt(4),
                    subject = cursor.getString(5)))
          }
        } // cursor closed — safe to suspend from here

    if (rows.isEmpty()) return

    // Step 2: fan-out to MMS_WORKER_COUNT IO workers.
    // Each worker independently queries addresses and parts for its assigned rows.
    // ContentProvider queries are IO-bound and safe to parallelize — no shared state.
    val workQueue = Channel<MmsRow>(capacity = Channel.UNLIMITED)
    rows.forEach { workQueue.trySend(it) }
    workQueue.close()

    // Output channel with back-pressure so workers don't get too far ahead of the DB writer.
    val assembled = Channel<MmsMessage>(capacity = MMS_WORKER_COUNT * 4)

    coroutineScope {
      // Launch workers, then close assembled when all are done.
      launch {
        coroutineScope {
          repeat(MMS_WORKER_COUNT) {
            launch(Dispatchers.IO) {
              for (row in workQueue) {
                try {
                  val addresses = readMmsAddresses(row.id)
                  val attachments = readMmsParts(row.id)
                  assembled.send(
                      MmsMessage(
                          id = row.id,
                          threadId = row.threadId,
                          timestampMs = row.timestampMs,
                          msgBox = row.msgBox,
                          read = row.read,
                          subject = row.subject,
                          addresses = addresses,
                          attachments = attachments))
                } catch (e: CancellationException) {
                  throw e
                } catch (e: Exception) {
                  Log.e("SmsBackup", "MMS read error id=${row.id}: ${e.message}")
                }
              }
            }
          }
        }
        assembled.close()
      }

      // Sequential consumer: calls block() one at a time so the caller never needs to
      // worry about concurrent access (e.g. channel.send to BackupManager's DB writer).
      for (msg in assembled) {
        block(msg)
      }
    }
  }

  private fun readMmsParts(mmsId: Long): List<MmsAttachment> {
    val result = mutableListOf<MmsAttachment>()
    // Use the per-message URI (content://mms/{id}/part) — more reliably supported across
    // Android versions and OEM ROMs than the global content://mms/part with a selection.
    context.contentResolver
        .query(
            Uri.parse("content://mms/$mmsId/part"),
            arrayOf(
                Telephony.Mms.Part._ID,
                Telephony.Mms.Part.CONTENT_TYPE,
                Telephony.Mms.Part.NAME,
                Telephony.Mms.Part.CHARSET),
            null,
            null,
            null)
        ?.use { cursor ->
          while (cursor.moveToNext()) {
            val partId = cursor.getLong(0)
            result.add(
                MmsAttachment(
                    id = partId,
                    mmsId = mmsId,
                    contentType = cursor.getString(1),
                    filename = cursor.getString(2),
                    charset = cursor.getInt(3),
                    data = readPartData(partId)))
          }
        }
    return result
  }

  private fun readMmsAddresses(mmsId: Long): String {
    val addresses = mutableListOf<String>()
    context.contentResolver
        .query(
            Uri.parse("content://mms/$mmsId/addr"),
            arrayOf(Telephony.Mms.Addr.ADDRESS, Telephony.Mms.Addr.TYPE),
            null,
            null,
            null)
        ?.use { cursor ->
          while (cursor.moveToNext()) {
            val addr = cursor.getString(0)
            if (!addr.isNullOrBlank() && addr != "insert-address-token") {
              addresses.add(addr)
            }
          }
        }
    return addresses.distinct().joinToString(",")
  }

  private fun readPartData(partId: Long): ByteArray? {
    return try {
      val uri = Uri.parse("content://mms/part/$partId")
      context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
    } catch (_: Exception) {
      null
    }
  }

  fun getSmsDedupHashes(): Set<String> {
    val hashes = mutableSetOf<String>()
    readAllSms().forEach { msg -> hashes.add(HashUtil.smsHash(msg)) }
    return hashes
  }

  suspend fun getMmsDedupHashes(
      onProgress: suspend (Float, String) -> Unit = { _, _ -> }
  ): Set<String> {
    data class MmsRow(val id: Long, val timestampMs: Long)
    val rows = mutableListOf<MmsRow>()
    context.contentResolver
        .query(
            Telephony.Mms.CONTENT_URI,
            arrayOf(Telephony.Mms._ID, Telephony.Mms.DATE),
            null,
            null,
            null)
        ?.use { cursor ->
          while (cursor.moveToNext()) {
            rows.add(MmsRow(cursor.getLong(0), cursor.getLong(1) * 1000L))
          }
        }
    val total = rows.size
    val hashes = mutableSetOf<String>()
    rows.forEachIndexed { index, row ->
      val addresses = readMmsAddresses(row.id)
      hashes.add(HashUtil.mmsHash(addresses, row.timestampMs))
      val count = index + 1
      if (count % 50 == 0 || count == total) {
        onProgress(count.toFloat() / total.coerceAtLeast(1), "Scanning MMS $count/$total...")
      }
    }
    return hashes
  }
}
