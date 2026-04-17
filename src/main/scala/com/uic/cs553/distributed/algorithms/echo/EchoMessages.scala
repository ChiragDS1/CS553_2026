package com.uic.cs553.distributed.algorithms.echo

import com.uic.cs553.distributed.framework.DistributedMessage

sealed trait EchoPayload extends DistributedMessage

object EchoPayload {

  // External trigger (optional)
  case object StartWave extends EchoPayload

  // Internal trigger (FIXED: moved from EchoNode → avoids Scala warning)
  final case class StartWaveInternal(waveId: String) extends EchoPayload

  // Main algorithm messages
  final case class Wave(origin: String, waveId: String) extends EchoPayload

  final case class Echo(origin: String, waveId: String) extends EchoPayload

  final case class Extinguish(origin: String, waveId: String) extends EchoPayload
}