package com.uic.cs553.distributed.core

import scala.jdk.CollectionConverters._
import com.typesafe.config.{Config, ConfigFactory}

/**
 * Configuration models for traffic initiation.
 *
 * TimerNodeConfig  – a node that fires on a periodic Akka timer.
 *   mode = "pdf"   → samples message kind from the node's PDF each tick
 *   mode = "fixed" → always sends fixedMsg regardless of PDF
 *
 * InputNodeConfig  – a node that accepts externally injected messages
 *   from the simulation driver (CLI or test harness).
 *
 * TrafficConfig    – the assembled initiation plan read from sim.traffic.
 */
final case class TimerNodeConfig(
    nodeId: String,
    tickEveryMs: Int,
    mode: String,           // "pdf" or "fixed"
    fixedMsg: Option[String] // only used when mode == "fixed"
)

final case class InputNodeConfig(
    nodeId: String
)

final case class TrafficConfig(
    seed: Long,
    timers: List[TimerNodeConfig],
    inputs: List[InputNodeConfig]
) {
  def isTimerNode(nodeId: String): Boolean =
    timers.exists(_.nodeId == nodeId)

  def isInputNode(nodeId: String): Boolean =
    inputs.exists(_.nodeId == nodeId)

  def timerFor(nodeId: String): Option[TimerNodeConfig] =
    timers.find(_.nodeId == nodeId)
}

object TrafficConfig {

  /** Returns an empty config when the sim.traffic block is absent. */
  val empty: TrafficConfig = TrafficConfig(42L, Nil, Nil)

  def fromConfig(config: Config): TrafficConfig = {
    if (!config.hasPath("sim.traffic")) return empty

    val tc   = config.getConfig("sim.traffic")
    val seed = if (tc.hasPath("seed")) tc.getLong("seed") else 42L

    val timers =
      if (tc.hasPath("timers"))
        tc.getConfigList("timers").asScala.toList.map(parseTimer)
      else Nil

    val inputs =
      if (tc.hasPath("inputs"))
        tc.getConfigList("inputs").asScala.toList.map(c => InputNodeConfig(c.getString("node")))
      else Nil

    TrafficConfig(seed, timers, inputs)
  }

  private def parseTimer(c: Config): TimerNodeConfig = {
    val nodeId     = c.getString("node")
    val tickEveryMs = c.getInt("tickEveryMs")
    val mode       = if (c.hasPath("mode")) c.getString("mode") else "pdf"
    val fixedMsg   = if (c.hasPath("fixedMsg")) Some(c.getString("fixedMsg")) else None
    TimerNodeConfig(nodeId, tickEveryMs, mode, fixedMsg)
  }
}
