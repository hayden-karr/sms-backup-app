package com.heideen.smsbackup.model

data class MmsAttachment(
    val id: Long,
    val mmsId: Long,
    val contentType: String?,
    val filename: String?,
    val charset: Int,
    val data: ByteArray?
) {
  // ByteArray does not support structural equality, so equals/hashCode are based
  // on identity (id + mmsId) rather than the default data class behavior.
  override fun equals(other: Any?): Boolean {
    if (this === other) return true
    if (other !is MmsAttachment) return false
    return id == other.id && mmsId == other.mmsId
  }

  override fun hashCode(): Int = 31 * id.hashCode() + mmsId.hashCode()
}
