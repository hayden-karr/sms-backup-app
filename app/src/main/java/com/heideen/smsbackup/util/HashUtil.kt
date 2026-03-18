package com.heideen.smsbackup.util

import com.heideen.smsbackup.model.MmsMessage
import com.heideen.smsbackup.model.SmsMessage
import java.security.MessageDigest

object HashUtil {
  private fun sha256(input: String): String =
      MessageDigest.getInstance("SHA-256").digest(input.toByteArray(Charsets.UTF_8)).joinToString(
          "") {
            "%02x".format(it)
          }

  private fun normalizeAddress(address: String?): String =
      address?.replace(Regex("[^0-9+]"), "") ?: ""

  fun smsHash(msg: SmsMessage): String =
      sha256("${normalizeAddress(msg.address)}|${msg.timestamp}|${msg.body ?: ""}")

  fun mmsHash(addresses: String, timestampMs: Long): String = sha256("$addresses|$timestampMs")

  fun mmsHash(msg: MmsMessage): String = mmsHash(msg.addresses, msg.timestampMs)
}
