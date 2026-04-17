package com.uic.cs553.distributed.examples

import com.typesafe.config.ConfigFactory
import com.uic.cs553.distributed.core.{NetGameSimLoader, TrafficConfig}
import com.uic.cs553.distributed.framework.ExperimentRunner
import com.uic.cs553.distributed.algorithms.laiyang.LaiYangNode
import com.uic.cs553.distributed.algorithms.echo.EchoNode
import com.uic.cs553.distributed.utils.GraphvizLogger

/**
 * Runs experiments using a topology loaded from a NetGameSim graph artifact.
 *
 * NetGameSim (https://github.com/0x1DOCD00D/NetGameSim) generates random
 * directed graphs and exports them as JSON.  This experiment demonstrates
 * the full pipeline: NetGameSim artifact → GraphTopology → Akka actor system
 * → distributed algorithm.
 *
 * Usage (from project root):
 *   sbt "runMain com.uic.cs553.distributed.examples.NetGameSimExperiment \
 *         --graph outputs/netgamesim-graph.json \
 *         --algorithm laiyang \
 *         --duration 20"
 *
 * Flags:
 *   --graph      path to NetGameSim JSON graph file
 *                (default: outputs/netgamesim-graph.json)
 *   --algorithm  laiyang | echo  (default: laiyang)
 *   --duration   seconds         (default: 15)
 */
object NetGameSimExperiment {

  def main(args: Array[String]): Unit = {
    val parsed    = parseArgs(args)
    val graphPath = parsed.getOrElse("graph", "outputs/netgamesim-graph.json")
    val algo      = parsed.getOrElse("algorithm", "laiyang")
    val duration  = parsed.getOrElse("duration", "15").toInt

    println(s"Loading NetGameSim graph from: $graphPath")

    // Load topology from NetGameSim JSON artifact.
    val topology = NetGameSimLoader.loadFromFile(graphPath) match {
      case Right(t) =>
        println(s"Loaded topology: ${t.summary}")
        t
      case Left(errors) =>
        System.err.println(s"Failed to load graph:\n${errors.mkString("\n")}")
        sys.exit(1)
    }

    // Load traffic config from the companion conf file if present.
    val confPath = "conf/experiment-netgamesim.conf"
    val trafficConfig =
      if (new java.io.File(confPath).exists()) {
        val cfg = ConfigFactory.parseFile(new java.io.File(confPath)).resolve()
        TrafficConfig.fromConfig(cfg)
      } else {
        TrafficConfig.empty
      }

    algo.toLowerCase match {

      case "laiyang" =>
        val dot = "netgamesim-laiyang.dot"
        GraphvizLogger.init(dot)
        try {
          ExperimentRunner.runTopologyExperiment(
            algorithmName   = "Lai-Yang Snapshot (NetGameSim topology)",
            topology        = topology,
            trafficConfig   = trafficConfig,
            nodeFactory     = gn => new LaiYangNode(gn.id),
            durationSeconds = duration
          )
        } finally {
          GraphvizLogger.finish()
          println(s"DOT written: $dot")
        }

      case "echo" =>
        val dot = "netgamesim-echo.dot"
        GraphvizLogger.init(dot)
        try {
          ExperimentRunner.runTopologyExperiment(
            algorithmName   = "Echo Extinction Anonymous (NetGameSim topology)",
            topology        = topology,
            trafficConfig   = trafficConfig,
            nodeFactory     = gn => new EchoNode(gn.id),
            durationSeconds = duration
          )
        } finally {
          GraphvizLogger.finish()
          println(s"DOT written: $dot")
        }

      case other =>
        System.err.println(s"Unknown algorithm '$other'. Choose: laiyang, echo")
        sys.exit(1)
    }
  }

  private def parseArgs(args: Array[String]): Map[String, String] =
    args.sliding(2, 2).collect {
      case Array(k, v) if k.startsWith("--") => k.stripPrefix("--") -> v
    }.toMap
}
