package com.uic.cs553.distributed.algorithms.laiyang

import com.uic.cs553.distributed.framework.DistributedMessage

trait SnapshotPayload extends DistributedMessage

object SnapshotPayload {

  /** Internal trigger used only by the initiator */
  case object StartSnapshot extends SnapshotPayload

  /**
   * Lai–Yang application message.
   * color = "white" or "red"
   * snapshotId identifies the snapshot wave
   */
  final case class AppMessage(
      content: String,
      color: String,
      snapshotId: String,
      sequenceNo: Long
  ) extends SnapshotPayload
}